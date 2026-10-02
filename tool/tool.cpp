#include <jni.h>
#include <jvmti.h>
#include <atomic>
#include <cstdio>
#include <expected>
#include <mutex>
#include <span>
#include <type_traits>
#include <utility>

/** 万能 Scope Guard 实现，利用析构函数在离开作用域时执行清理逻辑 */
template <typename F>
class ScopeGuard {
    F fn;
    bool active{true};
public:
    // C++20 requires 约束：防止万能引用劫持拷贝与移动构造函数
    template <typename Fn>
        requires (!std::is_same_v<std::remove_cvref_t<Fn>, ScopeGuard>)
    explicit ScopeGuard(Fn&& f) : fn(std::forward<Fn>(f)) {}

    ~ScopeGuard() { if (active) fn(); }
    void dismiss() noexcept { active = false; }
    ScopeGuard(const ScopeGuard&) = delete;
    ScopeGuard& operator=(const ScopeGuard&) = delete;
    ScopeGuard(ScopeGuard&& other) noexcept : fn(std::move(other.fn)), active(other.active) {
        other.dismiss();
    }
};

template <typename F>
[[nodiscard]] auto make_scope_guard(F&& f) {
    return ScopeGuard<std::decay_t<F>>(std::forward<F>(f));
}

#define CONCAT_IMPL(a, b) a##b
#define CONCAT(a, b) CONCAT_IMPL(a, b)
#define SCOPE_EXIT auto CONCAT(_scope_guard_, __LINE__) = make_scope_guard

/** 全局 Raw Monitor 句柄与互斥保护锁 */
static std::atomic<jrawMonitorID> g_raw_monitor{nullptr};
static std::mutex g_monitor_mutex;

/** 线程安全获取或创建全局 JVMTI Raw Monitor，支持失败后安全重试 */
static jrawMonitorID getOrCreateRawMonitor(jvmtiEnv* jvmti) {
    jrawMonitorID monitor = g_raw_monitor.load(std::memory_order_acquire);
    if (monitor) return monitor;

    std::lock_guard<std::mutex> lock(g_monitor_mutex);
    monitor = g_raw_monitor.load(std::memory_order_relaxed);
    if (monitor) return monitor;

    jrawMonitorID created = nullptr;
    if (jvmti->CreateRawMonitor("ToolGlobalHeapMonitor", &created) == JVMTI_ERROR_NONE && created) {
        g_raw_monitor.store(created, std::memory_order_release);
        return created;
    }
    return nullptr;
}

/** 全局递增 Tag 序列号，用于生成唯一的动态标签 */
static std::atomic<jlong> g_tag_sequence{100000};

struct TagPair {
    jlong first;
    jlong second;
};

/** 原子分配成对的唯一 JVMTI 标签 */
static inline TagPair allocateTagPair() {
    jlong base = g_tag_sequence.fetch_add(2, std::memory_order_relaxed);
    return {.first = base, .second = base + 1};
}

/** 确保当前 JVMTI 环境已启用对象标记能力 */
static inline void ensureCapabilities(jvmtiEnv* jvmti) {
    jvmtiCapabilities caps{
        .can_tag_objects = 1,
    };
    jvmti->AddCapabilities(&caps);
}

/** 遍历清理回调：清空指定类的所有带标记实例 */
static jvmtiIterationControl JNICALL RollbackClassTagCallback(
    jlong /*class_tag*/, jlong /*size*/, jlong* tag_ptr, void* user_data
) {
    if (tag_ptr && *tag_ptr == *static_cast<const jlong*>(user_data)) {
        *tag_ptr = 0;
    }
    return JVMTI_ITERATION_CONTINUE;
}

/** 全堆遍历回调：通过 IterateThroughHeap 清空任意孤立 Tag（零 JNI 局部引用开销） */
static jint JNICALL ClearHeapTagCallback(
    jlong /*class_tag*/, jlong /*size*/, jlong* tag_ptr, jint /*length*/, void* user_data
) {
    if (tag_ptr && *tag_ptr == *static_cast<const jlong*>(user_data)) {
        *tag_ptr = 0;
    }
    return JVMTI_VISIT_OBJECTS;
}

static void clearTagFromHeap(jvmtiEnv* jvmti, jlong tag) {
    jvmtiHeapCallbacks cbs{ .heap_iteration_callback = ClearHeapTagCallback };
    jvmti->IterateThroughHeap(JVMTI_HEAP_FILTER_UNTAGGED, nullptr, &cbs, &tag);
}

/** 实例查找回调：为目标类及其子类、接口实现类的实例打上 tag */
static jvmtiIterationControl JNICALL HeapObjectCallback(
    jlong /*class_tag*/, jlong /*size*/, jlong* tag_ptr, void* user_data
) {
    if (tag_ptr) {
        *tag_ptr = *static_cast<const jlong*>(user_data);
    }
    return JVMTI_ITERATION_CONTINUE;
}

/**
 * 获取指定类及其子类在堆中所有尚未被回收的实例并返回局部引用数组。
 * 包含多态子类与接口实现，包含不可达但尚未被 GC 物理回收的对象。
 */
static std::expected<jobjectArray, jvmtiError>
getInstancesInternal(jvmtiEnv* jvmti, JNIEnv* env, jclass klass) {
    if (!jvmti || !env || !klass) return std::unexpected(JVMTI_ERROR_NULL_POINTER);
    ensureCapabilities(jvmti);

    jrawMonitorID monitor = getOrCreateRawMonitor(jvmti);
    if (!monitor || jvmti->RawMonitorEnter(monitor) != JVMTI_ERROR_NONE) {
        return std::unexpected(JVMTI_ERROR_INTERNAL);
    }
    SCOPE_EXIT([&] { jvmti->RawMonitorExit(monitor); });

    jlong tag = allocateTagPair().first;

    // 先给目标类及其所有子类、接口实现的未回收实例打上 tag
    if (auto e = jvmti->IterateOverInstancesOfClass(klass, JVMTI_HEAP_OBJECT_EITHER, HeapObjectCallback, &tag);
        e != JVMTI_ERROR_NONE) {
        jvmti->IterateOverInstancesOfClass(klass, JVMTI_HEAP_OBJECT_TAGGED, RollbackClassTagCallback, &tag);
        return std::unexpected(e);
    }

    // 提取所有打上标记的对象句柄
    jint count = 0;
    jobject* instances = nullptr;
    if (auto e = jvmti->GetObjectsWithTags(1, &tag, &count, &instances, nullptr);
        e != JVMTI_ERROR_NONE) {
        jvmti->IterateOverInstancesOfClass(klass, JVMTI_HEAP_OBJECT_TAGGED, RollbackClassTagCallback, &tag);
        return std::unexpected(e);
    }
    SCOPE_EXIT([&] {
        if (instances) jvmti->Deallocate(reinterpret_cast<unsigned char*>(instances));
    });

    // 构建结果数组，不再调用 EnsureLocalCapacity 以避免触发超过容量限制的误报失败
    jobjectArray result = env->NewObjectArray(count, klass, nullptr);
    if (!result) {
        for (jint i = 0; i < count; ++i) {
            jvmti->SetTag(instances[i], 0);
            env->DeleteLocalRef(instances[i]);
        }
        return std::unexpected(JVMTI_ERROR_OUT_OF_MEMORY);
    }

    // 填充结果并清空 Tag，释放临时局部引用
    std::span<jobject> objs(instances, static_cast<size_t>(count));
    jsize idx = 0;
    for (jobject o : objs) {
        env->SetObjectArrayElement(result, idx++, o);
        jvmti->SetTag(o, 0);
        env->DeleteLocalRef(o);
    }

    return result;
}

/** 引用者扫描上下文 */
struct ReferrerContext {
    jlong target_tag;
    jlong referrer_tag;
    bool has_self_ref{false};
};

/** 引用关系遍历核心回调函数，支持自引用标记与空指针防护 */
static jint JNICALL ReferrerCallback(
    jvmtiHeapReferenceKind reference_kind,
    const jvmtiHeapReferenceInfo* reference_info,
    jlong class_tag,
    jlong referrer_class_tag,
    jlong size,
    jlong* tag_ptr,
    jlong* referrer_tag_ptr,
    jint length,
    void* user_data
) {
    (void)reference_kind;
    (void)reference_info;
    (void)class_tag;
    (void)referrer_class_tag;
    (void)size;
    (void)length;
    auto* ctx = reinterpret_cast<ReferrerContext*>(user_data);
    if (tag_ptr != nullptr && *tag_ptr == ctx->target_tag) {
        // GC Roots（如线程栈局部变量、JNI 全局引用）的 referrer_tag_ptr 为空指针，此处仅追踪堆内对象的字段引用
        if (referrer_tag_ptr != nullptr) {
            if (*referrer_tag_ptr == ctx->target_tag) {
                ctx->has_self_ref = true;
            } else {
                *referrer_tag_ptr = ctx->referrer_tag;
            }
        }
    }
    return JVMTI_VISIT_OBJECTS;
}

/**
 * 获取指定 Java 对象在堆中的所有直接引用者（Referrers）并返回局部引用数组。
 * 语义说明：仅包含堆中其他对象对它的字段引用，不包含线程栈局部变量、JNI 全局引用等 GC Roots。
 * 若目标对象本身为 Class 对象，堆中所有该类的实例均会被视作引用者（即实例对自身类的类引用关系）。
 */
static std::expected<jobjectArray, jvmtiError> getReferrersInternal(jvmtiEnv* jvmti, JNIEnv* env, jobject target_object) {
    if (!jvmti || !env || !target_object) return std::unexpected(JVMTI_ERROR_NULL_POINTER);

    ensureCapabilities(jvmti);

    jrawMonitorID monitor = getOrCreateRawMonitor(jvmti);
    if (!monitor) return std::unexpected(JVMTI_ERROR_INTERNAL);

    if (jvmti->RawMonitorEnter(monitor) != JVMTI_ERROR_NONE) {
        return std::unexpected(JVMTI_ERROR_INTERNAL);
    }
    SCOPE_EXIT([&] { jvmti->RawMonitorExit(monitor); });

    TagPair tags = allocateTagPair();
    jlong target_tag = tags.first;
    jlong referrer_tag = tags.second;

    jvmtiError err_tag = jvmti->SetTag(target_object, target_tag);
    if (err_tag != JVMTI_ERROR_NONE) {
        return std::unexpected(err_tag);
    }

    jvmtiHeapCallbacks callbacks{
        .heap_reference_callback = ReferrerCallback,
    };

    ReferrerContext ctx{
        .target_tag = target_tag,
        .referrer_tag = referrer_tag,
        .has_self_ref = false,
    };

    jvmtiError err_follow = jvmti->FollowReferences(
        0,
        nullptr,
        nullptr,
        &callbacks,
        &ctx
    );
    if (err_follow != JVMTI_ERROR_NONE) {
        jvmti->SetTag(target_object, 0);
        clearTagFromHeap(jvmti, referrer_tag);
        return std::unexpected(err_follow);
    }

    if (ctx.has_self_ref) {
        jvmti->SetTag(target_object, referrer_tag);
    }

    jint count = 0;
    jobject* instances = nullptr;
    jlong search_tag = referrer_tag;
    jvmtiError err_get = jvmti->GetObjectsWithTags(1, &search_tag, &count, &instances, nullptr);
    if (err_get != JVMTI_ERROR_NONE) {
        jvmti->SetTag(target_object, 0);
        clearTagFromHeap(jvmti, referrer_tag);
        return std::unexpected(err_get);
    }

    SCOPE_EXIT([&] {
        if (instances != nullptr) {
            jvmti->Deallocate(reinterpret_cast<unsigned char*>(instances));
        }
    });

    jclass obj_class = env->FindClass("java/lang/Object");
    if (!obj_class) {
        jvmti->SetTag(target_object, 0);
        clearTagFromHeap(jvmti, referrer_tag);
        // 清理已生成的局部引用，避免在非 JNI 托管的调用路径下发生泄露
        for (jint i = 0; i < count; ++i) {
            env->DeleteLocalRef(instances[i]);
        }
        return std::unexpected(JVMTI_ERROR_CLASS_NOT_PREPARED);
    }
    SCOPE_EXIT([&] { env->DeleteLocalRef(obj_class); });

    jobjectArray result_array = env->NewObjectArray(count, obj_class, nullptr);
    if (!result_array) {
        jvmti->SetTag(target_object, 0);
        clearTagFromHeap(jvmti, referrer_tag);
        // 清理已生成的局部引用，避免在非 JNI 托管的调用路径下发生泄露
        for (jint i = 0; i < count; ++i) {
            env->DeleteLocalRef(instances[i]);
        }
        return std::unexpected(JVMTI_ERROR_OUT_OF_MEMORY);
    }

    std::span<jobject> objs(instances, static_cast<size_t>(count));
    jsize idx = 0;
    for (jobject element : objs) {
        env->SetObjectArrayElement(result_array, idx++, element);
        jvmti->SetTag(element, 0);
        env->DeleteLocalRef(element);
    }

    jvmti->SetTag(target_object, 0);
    return result_array;
}

/** 全局缓存的 JavaVM 与 JVMTI 实例原子引用及初始化互斥锁 */
static std::atomic<JavaVM*> g_jvm{nullptr};
static std::atomic<jvmtiEnv*> g_jvmti{nullptr};
static std::mutex g_jvmti_mutex;

static jvmtiEnv* getOrAcquireJvmti(JNIEnv* env) {
    jvmtiEnv* ti = g_jvmti.load(std::memory_order_acquire);
    if (ti) return ti;

    std::lock_guard<std::mutex> lock(g_jvmti_mutex);
    ti = g_jvmti.load(std::memory_order_relaxed);
    if (ti) return ti;

    JavaVM* vm = g_jvm.load(std::memory_order_acquire);
    if (!vm) {
        JavaVM* raw_vm = nullptr;
        if (env->GetJavaVM(&raw_vm) == JNI_OK && raw_vm) {
            g_jvm.store(raw_vm, std::memory_order_release);
            vm = raw_vm;
        }
    }
    if (vm) {
        void* jvmti_raw = nullptr;
        if (vm->GetEnv(&jvmti_raw, JVMTI_VERSION_1_2) == JNI_OK && jvmti_raw) {
            ti = reinterpret_cast<jvmtiEnv*>(jvmti_raw);
            ensureCapabilities(ti);
            g_jvmti.store(ti, std::memory_order_release);
            return ti;
        }
    }
    return nullptr;
}

static void throwJvmtiException(JNIEnv* env, const char* msg, jvmtiError err) {
    // 优先尊重底层已有异常（如内存不足导致的 OOM），避免二次调用引发未知行为
    if (env->ExceptionCheck()) return;
    jclass ex_class = env->FindClass("java/lang/RuntimeException");
    if (ex_class) {
        char buf[256];
        std::snprintf(buf, sizeof(buf), "%s (jvmtiError: %d)", msg, static_cast<int>(err));
        env->ThrowNew(ex_class, buf);
        env->DeleteLocalRef(ex_class);
    }
}

extern "C" {

/**
 * 兼容传统 Panama FFM 符号调用的导出包装 (获取指定类的活跃实例，调用方拥有全局引用)
 * 注意：首参数 jvmtiEnv* 被忽略，内部统一使用经过验证并缓存的全局单例 JVMTI 环境。
 */
JNIEXPORT jobjectArray JNICALL GetInstances(jvmtiEnv* /*ignored*/, JNIEnv* env, jclass klass) {
    if (!env) return nullptr;
    jvmtiEnv* ti = getOrAcquireJvmti(env);
    if (!ti) return nullptr;
    auto res = getInstancesInternal(ti, env, klass);
    if (!res) {
        // Panama FFM 离开 native 栈不会自动抛出异常，必须清空内部遗留的未决异常
        env->ExceptionClear();
        return nullptr;
    }
    jobjectArray local = *res;
    jobjectArray global = reinterpret_cast<jobjectArray>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!global) {
        // 全局引用创建失败时清空未决异常，避免污染后续调用栈
        env->ExceptionClear();
        return nullptr;
    }
    return global;
}

/**
 * 兼容传统 Panama FFM 符号调用的导出包装 (获取指定对象的引用者，调用方拥有全局引用)
 * 注意：首参数 jvmtiEnv* 被忽略，内部统一使用经过验证并缓存的全局单例 JVMTI 环境。
 */
JNIEXPORT jobjectArray JNICALL GetReferrers(jvmtiEnv* /*ignored*/, JNIEnv* env, jobject target_object) {
    if (!env) return nullptr;
    jvmtiEnv* ti = getOrAcquireJvmti(env);
    if (!ti) return nullptr;
    auto res = getReferrersInternal(ti, env, target_object);
    if (!res) {
        // Panama FFM 离开 native 栈不会自动抛出异常，必须清空内部遗留的未决异常
        env->ExceptionClear();
        return nullptr;
    }
    jobjectArray local = *res;
    jobjectArray global = reinterpret_cast<jobjectArray>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!global) {
        // 全局引用创建失败时清空未决异常，避免污染后续调用栈
        env->ExceptionClear();
        return nullptr;
    }
    return global;
}

/** JNI 导出函数：获取指定类所有未回收实例，失败时向 Java 抛出异常 */
JNIEXPORT jobjectArray JNICALL Java_nipx_util_LibTool_nGetInstances(
    JNIEnv* env,
    jclass,
    jclass target_clazz
) {
    if (!env) return nullptr;
    jvmtiEnv* jvmti = getOrAcquireJvmti(env);
    if (!jvmti) {
        throwJvmtiException(env, "Failed to acquire JVMTI environment", JVMTI_ERROR_INTERNAL);
        return nullptr;
    }
    auto res = getInstancesInternal(jvmti, env, target_clazz);
    if (!res) {
        throwJvmtiException(env, "JVMTI getInstances failed", res.error());
        return nullptr;
    }
    return *res;
}

/** JNI 导出函数：获取指定对象的所有直接引用者，失败时向 Java 抛出异常 */
JNIEXPORT jobjectArray JNICALL Java_nipx_util_LibTool_nGetReferrers(
    JNIEnv* env,
    jclass,
    jobject target_object
) {
    if (!env) return nullptr;
    jvmtiEnv* jvmti = getOrAcquireJvmti(env);
    if (!jvmti) {
        throwJvmtiException(env, "Failed to acquire JVMTI environment", JVMTI_ERROR_INTERNAL);
        return nullptr;
    }
    auto res = getReferrersInternal(jvmti, env, target_object);
    if (!res) {
        throwJvmtiException(env, "JVMTI getReferrers failed", res.error());
        return nullptr;
    }
    return *res;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    if (!vm) return JNI_ERR;
    g_jvm.store(vm, std::memory_order_release);

    void* env_raw = nullptr;
    if (vm->GetEnv(&env_raw, JNI_VERSION_1_6) != JNI_OK || !env_raw) {
        return JNI_ERR;
    }
    auto* env = reinterpret_cast<JNIEnv*>(env_raw);

    (void)getOrAcquireJvmti(env);

    jclass clazz = env->FindClass("nipx/util/LibTool");
    if (clazz) {
        SCOPE_EXIT([&] { env->DeleteLocalRef(clazz); });

        JNINativeMethod methods[] = {
            {
                .name = const_cast<char*>("nGetInstances"),
                .signature = const_cast<char*>("(Ljava/lang/Class;)[Ljava/lang/Object;"),
                .fnPtr = reinterpret_cast<void*>(&Java_nipx_util_LibTool_nGetInstances),
            },
            {
                .name = const_cast<char*>("nGetReferrers"),
                .signature = const_cast<char*>("(Ljava/lang/Object;)[Ljava/lang/Object;"),
                .fnPtr = reinterpret_cast<void*>(&Java_nipx_util_LibTool_nGetReferrers),
            },
        };

        // 仅在明确找到类但方法绑定失败时返回 JNI_ERR，阻止未完成初始化的模块运行
        if (env->RegisterNatives(clazz, methods, 2) != JNI_OK) {
            env->ExceptionClear();
            return JNI_ERR;
        }
    } else {
        // 未找到类时不阻止加载，以允许纯 Panama FFM 调用方在任意类加载器环境下成功加载该动态库
        env->ExceptionClear();
    }

    return JNI_VERSION_1_6;
}

} // extern "C"