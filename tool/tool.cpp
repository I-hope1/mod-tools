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

/** 遍历回调：将堆中匹配 tag_a 或 tag_b 的对象标记清零，不创建任何 JNI 局部引用 */
struct ClearTagsCtx {
    jlong tag_a;
    jlong tag_b;
};

static jint JNICALL ClearTagsCallback(
    jlong /*class_tag*/, jlong /*size*/, jlong* tag_ptr, jint /*length*/, void* user_data
) {
    auto* ctx = static_cast<const ClearTagsCtx*>(user_data);
    if (tag_ptr != nullptr && (*tag_ptr == ctx->tag_a || *tag_ptr == ctx->tag_b)) {
        *tag_ptr = 0;
    }
    return JVMTI_VISIT_OBJECTS;
}

/** 通过 IterateThroughHeap 清除指定 tag（零 JNI 局部引用，不会复活垃圾对象，一次可清两个 tag） */
static void clearTags(jvmtiEnv* jvmti, jlong tag_a, jlong tag_b = 0) {
    jvmtiHeapCallbacks cbs{ .heap_iteration_callback = ClearTagsCallback };
    ClearTagsCtx ctx{ .tag_a = tag_a, .tag_b = tag_b };
    jvmti->IterateThroughHeap(JVMTI_HEAP_FILTER_UNTAGGED, nullptr, &cbs, &ctx);
}

/** 单次遍历实例查找上下文 */
struct InstanceCtx {
    jlong class_marker;
    jlong reachable_tag;
};

/** 单次遍历引用回调：结合 Class 标记直接识别存活目标类及其子类实例 */
static jint JNICALL InstanceCallback(
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
    (void)referrer_class_tag;
    (void)size;
    (void)referrer_tag_ptr;
    (void)length;
    auto* ctx = static_cast<const InstanceCtx*>(user_data);
    if (tag_ptr != nullptr && (class_tag == ctx->class_marker || class_tag == ctx->reachable_tag)) {
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
    jlong tag_marker = tags.first;
    jlong tag_reachable = tags.second;

    SCOPE_EXIT([&] {
        clearTags(jvmti, tag_marker);
    });

    int retries = 0;
    while (true) {
        jint class_count = 0;
        jclass* loaded_classes = nullptr;
        if (jvmti->GetLoadedClasses(&class_count, &loaded_classes) != JVMTI_ERROR_NONE) {
            return std::unexpected(JVMTI_ERROR_INTERNAL);
        }
        // PushLocalFrame 将这批类的局部引用限定在独立帧内，不污染外层帧
        if (env->PushLocalFrame(class_count + 16) != JNI_OK) {
            jvmti->Deallocate(reinterpret_cast<unsigned char*>(loaded_classes));
            return std::unexpected(JVMTI_ERROR_OUT_OF_MEMORY);
        }
        for (jint i = 0; i < class_count; ++i) {
            if (env->IsAssignableFrom(loaded_classes[i], klass)) {
                jvmti->SetTag(loaded_classes[i], tag_marker);
            }
        }
        jvmti->Deallocate(reinterpret_cast<unsigned char*>(loaded_classes));
        env->PopLocalFrame(nullptr);

        jvmtiHeapCallbacks callbacks{
            .heap_reference_callback = InstanceCallback,
        };
        InstanceCtx ctx{
            .class_marker = tag_marker,
            .reachable_tag = tag_reachable,
        };

        jvmtiError err_follow = jvmti->FollowReferences(
            0,
            nullptr,
            nullptr,
            &callbacks,
            &ctx
        );
        if (err_follow != JVMTI_ERROR_NONE) {
            clearTags(jvmti, tag_reachable);
            return std::unexpected(err_follow);
        }

        jint check_count = 0;
        jclass* check_classes = nullptr;
        if (jvmti->GetLoadedClasses(&check_count, &check_classes) == JVMTI_ERROR_NONE) {
            if (env->PushLocalFrame(check_count + 16) == JNI_OK) {
                env->PopLocalFrame(nullptr); // check_classes 都是局部引用，批量释放
            }
            jvmti->Deallocate(reinterpret_cast<unsigned char*>(check_classes));
        }

        if (check_count != class_count && retries < 2) {
            retries++;
            clearTags(jvmti, tag_marker, tag_reachable);
            continue;
        }
        break;
    }

    jint count = 0;
    jobject* instances = nullptr;
    jlong search_tag = tag_reachable;
    jvmtiError err2 = jvmti->GetObjectsWithTags(1, &search_tag, &count, &instances, nullptr);
    if (err2 != JVMTI_ERROR_NONE) {
        clearTags(jvmti, tag_reachable);
        return std::unexpected(err2);
    }

    SCOPE_EXIT([&] {
        if (instances != nullptr) {
            jvmti->Deallocate(reinterpret_cast<unsigned char*>(instances));
        }
    });

    if (env->EnsureLocalCapacity(count + 16) != JNI_OK) {
        clearTags(jvmti, tag_reachable);
        return std::unexpected(JVMTI_ERROR_OUT_OF_MEMORY);
    }

    jobjectArray result_array = env->NewObjectArray(count, klass, nullptr);
    if (!result_array) {
        clearTags(jvmti, tag_reachable);
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
        clearTags(jvmti, referrer_tag);
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
        clearTags(jvmti, referrer_tag);
        return std::unexpected(err_get);
    }

    SCOPE_EXIT([&] {
        if (instances != nullptr) {
            jvmti->Deallocate(reinterpret_cast<unsigned char*>(instances));
        }
    });

    if (env->EnsureLocalCapacity(count + 16) != JNI_OK) {
        jvmti->SetTag(target_object, 0);
        clearTags(jvmti, referrer_tag);
        return std::unexpected(JVMTI_ERROR_OUT_OF_MEMORY);
    }

    jclass obj_class = env->FindClass("java/lang/Object");
    if (!obj_class) {
        jvmti->SetTag(target_object, 0);
        clearTags(jvmti, referrer_tag);
        return std::unexpected(JVMTI_ERROR_CLASS_NOT_PREPARED);
    }
    SCOPE_EXIT([&] { env->DeleteLocalRef(obj_class); });

    jobjectArray result_array = env->NewObjectArray(count, obj_class, nullptr);
    if (!result_array) {
        jvmti->SetTag(target_object, 0);
        clearTags(jvmti, referrer_tag);
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
