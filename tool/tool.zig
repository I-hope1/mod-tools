const std = @import("std");

const jvm = @cImport({
    @cInclude("jvmti.h");
});

/// 轻量级自旋互斥锁
///
/// 在 Zig 0.16 中，系统级阻塞锁被迁移至 std.Io.Mutex。
/// 此处使用原子变量实现无依赖的自旋锁，适用于无 std.Io 环境的底层 JNI/JVMTI 动态库场景。
const SpinLock = struct {
    state: std.atomic.Value(bool) = std.atomic.Value(bool).init(false),

    /// 获取锁，若已被占用则通过 CPU 提示进行忙等待自旋
    pub fn lock(self: *SpinLock) void {
        while (self.state.cmpxchgWeak(false, true, .acquire, .monotonic) != null) {
            std.atomic.spinLoopHint();
        }
    }

    /// 释放锁
    pub fn unlock(self: *SpinLock) void {
        self.state.store(false, .release);
    }
};

/// 全局堆操作互斥锁
///
/// 由于 JVM 堆对象的 JVMTI Tag 槽位为全局唯一且跨线程共享，
/// 必须通过互斥锁保证任何时刻只有一个线程在执行堆打标与遍历。
var heap_lock: SpinLock = .{};

/// 全局递增 Tag 序列号，用于生成全局唯一的动态标签
var tag_sequence = std.atomic.Value(jvm.jlong).init(100000);

/// 动态分配唯一的 JVMTI 标签值
fn allocateTag() jvm.jlong {
    return tag_sequence.fetchAdd(1, .monotonic);
}

/// 清理堆中被打上特定 Tag 的对象标记，并释放底层分配的局部引用与缓冲区
///
/// 用于在遍历结束、筛选完成或发生错误时清理残留标签，避免污染 JVM 堆状态。
fn clearTaggedObjects(
    jvmti_ptr: *jvm.jvmtiEnv,
    env_ptr: *jvm.JNIEnv,
    tag: jvm.jlong,
) void {
    const jvmti_env = jvmti_ptr.*;
    const jni_env = env_ptr.*;
    var count: jvm.jint = 0;
    var instances: [*c]jvm.jobject = null;
    var search_tag = tag;

    if (jvmti_env.*.GetObjectsWithTags.?(
        jvmti_ptr,
        1,
        &search_tag,
        &count,
        &instances,
        null,
    ) == jvm.JVMTI_ERROR_NONE) {
        var j: jvm.jint = 0;
        while (j < count) : (j += 1) {
            const elem = instances[@intCast(j)];
            _ = jvmti_env.*.SetTag.?(jvmti_ptr, elem, 0);
            _ = jni_env.*.DeleteLocalRef.?(env_ptr, elem);
        }
        if (instances != null) {
            _ = jvmti_env.*.Deallocate.?(jvmti_ptr, @ptrCast(instances));
        }
    }
}

/// 全量候选对象遍历标记回调
///
/// 在 STW 期间为指定类及其子类的所有堆对象打上初始候选标签。
fn HeapObjectCallback(
    _class_tag: jvm.jlong,
    _size: jvm.jlong,
    tag_ptr: [*c]jvm.jlong,
    user_data: ?*anyopaque,
) callconv(.c) jvm.jvmtiIterationControl {
    _ = _class_tag;
    _ = _size;

    const tag_value = @as(*const jvm.jlong, @ptrCast(@alignCast(user_data.?)));
    tag_ptr.* = tag_value.*;
    return jvm.JVMTI_ITERATION_CONTINUE;
}

/// 可达性筛选上下文
const ReachableContext = struct {
    candidate_tag: jvm.jlong,
    reachable_tag: jvm.jlong,
};

/// 可达性过滤引用回调函数
///
/// 从 GC Roots 开始遍历引用关系图，仅将存活可达的候选对象晋升为可达标签。
fn ReachableFilterCallback(
    _reference_kind: jvm.jvmtiHeapReferenceKind,
    _reference_info: [*c]const jvm.jvmtiHeapReferenceInfo,
    _class_tag: jvm.jlong,
    _referrer_class_tag: jvm.jlong,
    _size: jvm.jlong,
    tag_ptr: [*c]jvm.jlong,
    _referrer_tag_ptr: [*c]jvm.jlong,
    _length: jvm.jint,
    user_data: ?*anyopaque,
) callconv(.c) jvm.jint {
    _ = _reference_kind;
    _ = _reference_info;
    _ = _class_tag;
    _ = _referrer_class_tag;
    _ = _size;
    _ = _referrer_tag_ptr;
    _ = _length;

    const ctx = @as(*const ReachableContext, @ptrCast(@alignCast(user_data.?)));

    // 判空保护，并仅提升带候选标记的对象
    if (tag_ptr != null and tag_ptr.* == ctx.candidate_tag) {
        tag_ptr.* = ctx.reachable_tag;
    }
    return jvm.JVMTI_VISIT_OBJECTS;
}

/// 获取指定类及其子类在堆中所有存活且可达的实例
///
/// - 通过原生自旋互斥锁与动态 Tag 保证并发安全。
/// - 通过结合全量类遍历与 GC Roots 引用图扫描，过滤掉等待 GC 回收的不可达垃圾对象。
/// - 支持接口与抽象类多态查找。
/// - 返回一个包含所有实例对象的 Java 数组全局引用（JNI GlobalRef）。
/// - 若不存在任何实例，返回长度为 0 的对象数组而非 null。
export fn GetInstances(
    jvmti: ?*jvm.jvmtiEnv,
    env: ?*jvm.JNIEnv,
    klass: jvm.jclass,
) callconv(.c) jvm.jobjectArray {
    const jvmti_ptr = jvmti orelse return null;
    const env_ptr = env orelse return null;

    // 获取互斥锁，确保同一时刻只有一个线程操作堆 Tag
    heap_lock.lock();
    defer heap_lock.unlock();

    const jvmti_env = jvmti_ptr.*;
    const jni_env = env_ptr.*;

    // 动态生成本轮操作专用的全局唯一 Tag，防止跨周期污染
    const tag_candidate = allocateTag();
    const tag_reachable = allocateTag();

    var candidate_tag = tag_candidate;

    // 全量标记该类及其子类的实例（包含不可达对象）
    const err1 = jvmti_env.*.IterateOverInstancesOfClass.?(
        jvmti_ptr,
        klass,
        jvm.JVMTI_HEAP_OBJECT_EITHER,
        HeapObjectCallback,
        &candidate_tag,
    );
    if (err1 != jvm.JVMTI_ERROR_NONE) {
        std.log.err("JVMTI error on IterateOverInstancesOfClass: {d}", .{err1});
        return null;
    }

    // 从 GC Roots 开始进行存活遍历，仅将可达候选对象晋升为 tag_reachable
    var callbacks = std.mem.zeroes(jvm.jvmtiHeapCallbacks);
    callbacks.heap_reference_callback = ReachableFilterCallback;

    var filter_ctx = ReachableContext{
        .candidate_tag = tag_candidate,
        .reachable_tag = tag_reachable,
    };

    const err_follow = jvmti_env.*.FollowReferences.?(
        jvmti_ptr,
        0,     // 遍历所有可达对象
        null,  // 不限定单一类，以完整支持接口和子类多态
        null,  // 从 GC Roots 开始扫描
        &callbacks,
        &filter_ctx,
    );
    if (err_follow != jvm.JVMTI_ERROR_NONE) {
        std.log.err("JVMTI error on FollowReferences: {d}", .{err_follow});
        clearTaggedObjects(jvmti_ptr, env_ptr, tag_candidate);
        clearTaggedObjects(jvmti_ptr, env_ptr, tag_reachable);
        return null;
    }

    // 仅获取被确认为可达的对象
    var count: jvm.jint = 0;
    var instances: [*c]jvm.jobject = null;
    var search_tag = tag_reachable;
    const err2 = jvmti_env.*.GetObjectsWithTags.?(
        jvmti_ptr,
        1,
        &search_tag,
        &count,
        &instances,
        null,
    );
    if (err2 != jvm.JVMTI_ERROR_NONE) {
        std.log.err("JVMTI error on GetObjectsWithTags: {d}", .{err2});
        clearTaggedObjects(jvmti_ptr, env_ptr, tag_candidate);
        clearTaggedObjects(jvmti_ptr, env_ptr, tag_reachable);
        return null;
    }

    // 清理留在堆中不可达垃圾对象上的候选标记
    clearTaggedObjects(jvmti_ptr, env_ptr, tag_candidate);

    // 统一创建结果数组（count 为 0 时创建 0 长度数组）
    const result_array = jni_env.*.NewObjectArray.?(
        env_ptr,
        count,
        klass,
        null,
    ) orelse {
        if (instances != null) _ = jvmti_env.*.Deallocate.?(jvmti_ptr, @ptrCast(instances));
        clearTaggedObjects(jvmti_ptr, env_ptr, tag_reachable);
        return null;
    };

    // 填充数组、重置 Tag 并释放局部引用
    var i: jvm.jint = 0;
    while (i < count) : (i += 1) {
        const element = instances[@intCast(i)];
        _ = jni_env.*.SetObjectArrayElement.?(
            env_ptr,
            result_array,
            i,
            element,
        );
        _ = jvmti_env.*.SetTag.?(
            jvmti_ptr,
            element,
            0,
        );
        _ = jni_env.*.DeleteLocalRef.?(env_ptr, element);
    }

    if (instances != null) {
        _ = jvmti_env.*.Deallocate.?(
            jvmti_ptr,
            @ptrCast(instances),
        );
    }

    // 统一包装为全局引用返回
    const global = jni_env.*.NewGlobalRef.?(env_ptr, result_array);
    _ = jni_env.*.DeleteLocalRef.?(env_ptr, result_array);
    return global;
}

/// 引用者扫描上下文
const ReferrerContext = struct {
    target_tag: jvm.jlong,
    referrer_tag: jvm.jlong,
    has_self_ref: bool = false,
};

/// 引用扫描核心回调函数
///
/// 当扫描到 A 引用 B 时触发，若 B 匹配目标对象，则为 A 打上引用者标签。
/// 内置空指针检查与自引用保护机制，防止标签被意外覆盖。
fn ReferrerCallback(
    reference_kind: jvm.jvmtiHeapReferenceKind,
    reference_info: [*c]const jvm.jvmtiHeapReferenceInfo,
    class_tag: jvm.jlong,
    referrer_class_tag: jvm.jlong,
    size: jvm.jlong,
    tag_ptr: [*c]jvm.jlong,
    referrer_tag_ptr: [*c]jvm.jlong,
    length: jvm.jint,
    user_data: ?*anyopaque,
) callconv(.c) jvm.jint {
    _ = reference_kind;
    _ = reference_info;
    _ = class_tag;
    _ = referrer_class_tag;
    _ = size;
    _ = length;

    const ctx = @as(*ReferrerContext, @ptrCast(@alignCast(user_data.?)));

    // 判空保护：必须先对 tag_ptr 判空，防止解引用空指针引发 SIGSEGV 崩溃
    if (tag_ptr != null and tag_ptr.* == ctx.target_tag) {
        if (referrer_tag_ptr != null) {
            // 自引用保护：若引用者为目标对象自身，仅记录标记而不在遍历期间覆盖 Tag，避免破坏目标标签导致后续匹配失效
            if (referrer_tag_ptr == tag_ptr or referrer_tag_ptr.* == ctx.target_tag) {
                ctx.has_self_ref = true;
            } else {
                referrer_tag_ptr.* = ctx.referrer_tag;
            }
        }
    }

    return jvm.JVMTI_VISIT_OBJECTS;
}

/// 获取指定 Java 对象在堆中的所有直接引用者（Referrers）
///
/// - 通过原生自旋互斥锁与动态 Tag 保证并发安全。
/// - 全堆扫描引用关系，查找所有指向目标对象的直接引用发起者。
/// - 支持检测并包含持有自身引用的自引用对象。
/// - 返回一个 java.lang.Object[] 类型的全局引用（JNI GlobalRef）。
/// - 若目标对象无任何引用者，返回长度为 0 的数组而非 null。
export fn GetReferrers(
    jvmti: ?*jvm.jvmtiEnv,
    env: ?*jvm.JNIEnv,
    target_object: jvm.jobject,
) callconv(.c) jvm.jobjectArray {
    const jvmti_ptr = jvmti orelse return null;
    const env_ptr = env orelse return null;
    const target = target_object orelse return null;

    // 获取互斥锁，确保同一时刻只有一个线程操作堆 Tag
    heap_lock.lock();
    defer heap_lock.unlock();

    const jvmti_env = jvmti_ptr.*;
    const jni_env = env_ptr.*;

    // 动态生成本轮操作专用的全局唯一 Tag
    const target_tag = allocateTag();
    const referrer_tag = allocateTag();

    // 给目标对象打上临时 Tag
    const err_tag = jvmti_env.*.SetTag.?(jvmti_ptr, target, target_tag);
    if (err_tag != jvm.JVMTI_ERROR_NONE) {
        std.log.err("JVMTI error on SetTag: {d}", .{err_tag});
        return null;
    }

    // 配置遍历引用关系的回调与上下文
    var callbacks = std.mem.zeroes(jvm.jvmtiHeapCallbacks);
    callbacks.heap_reference_callback = ReferrerCallback;

    var ctx = ReferrerContext{
        .target_tag = target_tag,
        .referrer_tag = referrer_tag,
        .has_self_ref = false,
    };

    const err_follow = jvmti_env.*.FollowReferences.?(
        jvmti_ptr,
        0,
        null,
        null,
        &callbacks,
        &ctx,
    );
    if (err_follow != jvm.JVMTI_ERROR_NONE) {
        std.log.err("JVMTI error on FollowReferences: {d}", .{err_follow});
        _ = jvmti_env.*.SetTag.?(jvmti_ptr, target, 0);
        clearTaggedObjects(jvmti_ptr, env_ptr, referrer_tag);
        return null;
    }

    // 遍历结束后处理自引用，将目标对象纳入引用者标签
    if (ctx.has_self_ref) {
        _ = jvmti_env.*.SetTag.?(jvmti_ptr, target, referrer_tag);
    }

    // 获取所有标记为引用者的对象
    var count: jvm.jint = 0;
    var instances: [*c]jvm.jobject = null;
    var search_tag = referrer_tag;
    const err_get = jvmti_env.*.GetObjectsWithTags.?(
        jvmti_ptr,
        1,
        &search_tag,
        &count,
        &instances,
        null,
    );
    if (err_get != jvm.JVMTI_ERROR_NONE) {
        std.log.err("JVMTI error on GetObjectsWithTags: {d}", .{err_get});
        _ = jvmti_env.*.SetTag.?(jvmti_ptr, target, 0);
        clearTaggedObjects(jvmti_ptr, env_ptr, referrer_tag);
        return null;
    }

    // 查找 java.lang.Object 类并使用 defer 保证局部引用及时释放
    const obj_class = jni_env.*.FindClass.?(env_ptr, "java/lang/Object") orelse {
        _ = jvmti_env.*.SetTag.?(jvmti_ptr, target, 0);
        clearTaggedObjects(jvmti_ptr, env_ptr, referrer_tag);
        if (instances != null) _ = jvmti_env.*.Deallocate.?(jvmti_ptr, @ptrCast(instances));
        return null;
    };
    defer _ = jni_env.*.DeleteLocalRef.?(env_ptr, obj_class);

    // 统一创建数组（count 为 0 时创建 0 长度数组）
    const result_array = jni_env.*.NewObjectArray.?(
        env_ptr,
        count,
        obj_class,
        null,
    ) orelse {
        _ = jvmti_env.*.SetTag.?(jvmti_ptr, target, 0);
        clearTaggedObjects(jvmti_ptr, env_ptr, referrer_tag);
        if (instances != null) _ = jvmti_env.*.Deallocate.?(jvmti_ptr, @ptrCast(instances));
        return null;
    };

    // 填充引用者对象并重置 Tag
    var i: jvm.jint = 0;
    while (i < count) : (i += 1) {
        const element = instances[@intCast(i)];
        _ = jni_env.*.SetObjectArrayElement.?(
            env_ptr,
            result_array,
            i,
            element,
        );
        _ = jvmti_env.*.SetTag.?(
            jvmti_ptr,
            element,
            0,
        );
        _ = jni_env.*.DeleteLocalRef.?(env_ptr, element);
    }

    // 确保目标对象本身的 Tag 恢复为 0
    _ = jvmti_env.*.SetTag.?(jvmti_ptr, target, 0);

    // 释放 JVMTI 分配的临时数组句柄
    if (instances != null) {
        _ = jvmti_env.*.Deallocate.?(
            jvmti_ptr,
            @ptrCast(instances),
        );
    }

    // 统一包装为全局引用返回
    const global = jni_env.*.NewGlobalRef.?(env_ptr, result_array);
    _ = jni_env.*.DeleteLocalRef.?(env_ptr, result_array);
    return global;
}