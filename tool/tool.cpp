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
    template <typename Fn>
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

/** 清理堆中被打上特定 Tag 的对象标记并释放局部引用与内存缓冲区 */
static void clearTaggedObjects(jvmtiEnv* jvmti, JNIEnv* env, jlong tag) {
    jint count = 0;
    jobject* instances = nullptr;
    jlong search_tag = tag;
    if (jvmti->GetObjectsWithTags(1, &search_tag, &count, &instances, nullptr) == JVMTI_ERROR_NONE) {
        SCOPE_EXIT([&] {
            if (instances != nullptr) {
                jvmti->Deallocate(reinterpret_cast<unsigned char*>(instances));
            }
        });
        if (env && count > 0) {
            env->EnsureLocalCapacity(count + 16);
        }
        std::span<jobject> objs(instances, static_cast<size_t>(count));
        for (jobject elem : objs) {
            jvmti->SetTag(elem, 0);
            if (env) {
                env->DeleteLocalRef(elem);
            }
        }
    }
}

/** 全量候选对象遍历标记回调 */
static jvmtiIterationControl JNICALL HeapObjectCallback(
    jlong class_tag,
    jlong size,
    jlong* tag_ptr,
    void* user_data
) {
    (void)class_tag;
    (void)size;
    jlong tag_val = *reinterpret_cast<jlong*>(user_data);
    *tag_ptr = tag_val;
    return JVMTI_ITERATION_CONTINUE;
}

/** 可达性筛选上下文 */
struct ReachableContext {
    jlong candidate_tag;
    jlong reachable_tag;
};

/** 可达性过滤引用回调函数，仅将存活可达的候选对象提升为可达标签 */
static jint JNICALL ReachableFilterCallback(
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
    (void)referrer_tag_ptr;
    (void)length;
    const auto* ctx = reinterpret_cast<const ReachableContext*>(user_data);
    if (tag_ptr != nullptr && *tag_ptr == ctx->candidate_tag) {
        *tag_ptr = ctx->reachable_tag;
    }
    return JVMTI_VISIT_OBJECTS;
}

/** 获取指定类及其子类在堆中所有存活且可达的实例并返回局部引用数组 */
static std::expected<jobjectArray, jvmtiError> getInstancesInternal(jvmtiEnv* jvmti, JNIEnv* env, jclass klass) {
    if (!jvmti || !env || !klass) return std::unexpected(JVMTI_ERROR_NULL_POINTER);

    ensureCapabilities(jvmti);

    jrawMonitorID monitor = getOrCreateRawMonitor(jvmti);
    if (!monitor) return std::unexpected(JVMTI_ERROR_INTERNAL);

    if (jvmti->RawMonitorEnter(monitor) != JVMTI_ERROR_NONE) {
        return std::unexpected(JVMTI_ERROR_INTERNAL);
    }
    SCOPE_EXIT([&] { jvmti->RawMonitorExit(monitor); });

    TagPair tags = allocateTagPair();
    jlong tag_candidate = tags.first;
    jlong tag_reachable = tags.second;

    jvmtiError err1 = jvmti->IterateOverInstancesOfClass(
        klass,
        JVMTI_HEAP_OBJECT_EITHER,
        HeapObjectCallback,
        &tag_candidate
    );
    if (err1 != JVMTI_ERROR_NONE) {
        clearTaggedObjects(jvmti, env, tag_candidate);
        return std::unexpected(err1);
    }

    jvmtiHeapCallbacks callbacks{
        .heap_reference_callback = ReachableFilterCallback,
    };

    ReachableContext filter_ctx{
        .candidate_tag = tag_candidate,
        .reachable_tag = tag_reachable,
    };

    jvmtiError err_follow = jvmti->FollowReferences(
        0,
        nullptr,
        nullptr,
        &callbacks,
        &filter_ctx
    );
    if (err_follow != JVMTI_ERROR_NONE) {
        clearTaggedObjects(jvmti, env, tag_candidate);
        clearTaggedObjects(jvmti, env, tag_reachable);
        return std::unexpected(err_follow);
    }

    jint count = 0;
    jobject* instances = nullptr;
    jlong search_tag = tag_reachable;
    jvmtiError err2 = jvmti->GetObjectsWithTags(1, &search_tag, &count, &instances, nullptr);
    if (err2 != JVMTI_ERROR_NONE) {
        clearTaggedObjects(jvmti, env, tag_candidate);
        clearTaggedObjects(jvmti, env, tag_reachable);
        return std::unexpected(err2);
    }

    SCOPE_EXIT([&] {
        if (instances != nullptr) {
            jvmti->Deallocate(reinterpret_cast<unsigned char*>(instances));
        }
    });

    clearTaggedObjects(jvmti, env, tag_candidate);

    if (env->EnsureLocalCapacity(count + 16) != JNI_OK) {
        clearTaggedObjects(jvmti, env, tag_reachable);
        return std::unexpected(JVMTI_ERROR_OUT_OF_MEMORY);
    }

    jobjectArray result_array = env->NewObjectArray(count, klass, nullptr);
    if (!result_array) {
        clearTaggedObjects(jvmti, env, tag_reachable);
        return std::unexpected(JVMTI_ERROR_OUT_OF_MEMORY);
    }

    std::span<jobject> objs(instances, static_cast<size_t>(count));
    jsize idx = 0;
    for (jobject element : objs) {
        env->SetObjectArrayElement(result_array, idx++, element);
        jvmti->SetTag(element, 0);
        env->DeleteLocalRef(element);
    }

    return result_array;
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

/** 获取指定 Java 对象在堆中的所有直接引用者并返回局部引用数组 */
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
        clearTaggedObjects(jvmti, env, referrer_tag);
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
        clearTaggedObjects(jvmti, env, referrer_tag);
        return std::unexpected(err_get);
    }

    SCOPE_EXIT([&] {
        if (instances != nullptr) {
            jvmti->Deallocate(reinterpret_cast<unsigned char*>(instances));
        }
    });

    if (env->EnsureLocalCapacity(count + 16) != JNI_OK) {
        jvmti->SetTag(target_object, 0);
        clearTaggedObjects(jvmti, env, referrer_tag);
        return std::unexpected(JVMTI_ERROR_OUT_OF_MEMORY);
    }

    jclass obj_class = env->FindClass("java/lang/Object");
    if (!obj_class) {
        jvmti->SetTag(target_object, 0);
        clearTaggedObjects(jvmti, env, referrer_tag);
        return std::unexpected(JVMTI_ERROR_CLASS_NOT_PREPARED);
    }
    SCOPE_EXIT([&] { env->DeleteLocalRef(obj_class); });

    jobjectArray result_array = env->NewObjectArray(count, obj_class, nullptr);
    if (!result_array) {
        jvmti->SetTag(target_object, 0);
        clearTaggedObjects(jvmti, env, referrer_tag);
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

/** 线程安全获取或按需懒加载唯一的全局 JVMTI 环境指针，防止多线程竞争泄漏 */
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

/** 向 Java 层抛出携带 JVMTI 错误码的 RuntimeException 异常 */
static void throwJvmtiException(JNIEnv* env, const char* msg, jvmtiError err) {
    jclass ex_class = env->FindClass("java/lang/RuntimeException");
    if (ex_class) {
        char buf[256];
        std::snprintf(buf, sizeof(buf), "%s (jvmtiError: %d)", msg, static_cast<int>(err));
        env->ThrowNew(ex_class, buf);
        env->DeleteLocalRef(ex_class);
    }
}

extern "C" {

/** 兼容传统 Panama FFM 符号调用的导出包装 (获取指定类的活跃实例，调用方拥有全局引用) */
JNIEXPORT jobjectArray JNICALL GetInstances(jvmtiEnv* /*jvmti*/, JNIEnv* env, jclass klass) {
    if (!env) return nullptr;
    jvmtiEnv* ti = getOrAcquireJvmti(env);
    if (!ti) return nullptr;
    auto res = getInstancesInternal(ti, env, klass);
    if (!res) return nullptr;
    jobjectArray local = *res;
    jobjectArray global = reinterpret_cast<jobjectArray>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    return global;
}

/** 兼容传统 Panama FFM 符号调用的导出包装 (获取指定对象的引用者，调用方拥有全局引用) */
JNIEXPORT jobjectArray JNICALL GetReferrers(jvmtiEnv* /*jvmti*/, JNIEnv* env, jobject target_object) {
    if (!env) return nullptr;
    jvmtiEnv* ti = getOrAcquireJvmti(env);
    if (!ti) return nullptr;
    auto res = getReferrersInternal(ti, env, target_object);
    if (!res) return nullptr;
    jobjectArray local = *res;
    jobjectArray global = reinterpret_cast<jobjectArray>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    return global;
}

/** JNI 导出函数：获取指定类所有存活实例，失败时向 Java 抛出异常 */
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

/** JNI 动态库加载入口点，注册原生方法并初始化环境 */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    if (!vm) return JNI_ERR;
    g_jvm.store(vm, std::memory_order_release);

    void* env_raw = nullptr;
    if (vm->GetEnv(&env_raw, JNI_VERSION_1_6) != JNI_OK || !env_raw) {
        return JNI_ERR;
    }
    auto* env = reinterpret_cast<JNIEnv*>(env_raw);

    // 尽早初始化全局单例 JVMTI 环境
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
        if (env->RegisterNatives(clazz, methods, 2) != JNI_OK) {
            env->ExceptionClear();
        }
    } else {
        env->ExceptionClear();
    }

    return JNI_VERSION_1_6;
}

} // extern "C"
