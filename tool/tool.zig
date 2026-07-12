const std = @import("std");

const jvm = @cImport({
    @cInclude("jvmti.h");
});

/// 安全的 STW 遍历回调：只负责打 Tag
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

export fn GetInstances(
    jvmti: ?*jvm.jvmtiEnv, // 单指针
    env: ?*jvm.JNIEnv,     // 单指针
    klass: jvm.jclass,
) callconv(.c) jvm.jobjectArray {
    const jvmti_ptr = jvmti orelse return null;
    const env_ptr = env orelse return null;

    // 在 Zig 线程栈上将单指针存为局部变量
    const jvmti_env = jvmti_ptr.*;
    const jni_env = env_ptr.*;

    var tag: jvm.jlong = 88888;

    // 1. 全量打 Tag
    const err1 = jvmti_env.*.IterateOverInstancesOfClass.?(
        jvmti_ptr,
        klass,
        jvm.JVMTI_HEAP_OBJECT_EITHER,
        HeapObjectCallback,
        &tag,
    );
    if (err1 != jvm.JVMTI_ERROR_NONE) {
        std.log.err("JVMTI error on IterateOverInstancesOfClass: {d}", .{err1});
        return null;
    }

    // 2. 获取所有被标记的对象
    var count: jvm.jint = 0;
    var instances: [*c]jvm.jobject = null;
    const err2 = jvmti_env.*.GetObjectsWithTags.?(
        jvmti_ptr,
        1,
        &tag,
        &count,
        &instances,
        null,
    );
    if (err2 != jvm.JVMTI_ERROR_NONE) {
        std.log.err("JVMTI error on GetObjectsWithTags: {d}", .{err2});
        return null;
    }

    if (count == 0) {
        return jni_env.*.NewObjectArray.?(
            env_ptr,
            0,
            klass,
            null,
        );
    }

    // 3. 创建 Java 对象数组
    const result_array = jni_env.*.NewObjectArray.?(
        env_ptr,
        count,
        klass,
        null,
    );

    // 4. 填充并及时在 Native 释放局部引用
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
        // 关键点：DeleteLocalRef，防止全量拉取时本地引用溢出
        _ = jni_env.*.DeleteLocalRef.?(env_ptr, element);
    }

    _ = jvmti_env.*.Deallocate.?(
        jvmti_ptr,
        @ptrCast(instances),
    );

    return result_array;
}

export fn GetReferrers(
    jvmti: ?*jvm.jvmtiEnv,     // 双重指针
    env: ?*jvm.JNIEnv,         // 双重指针
    target_object: jvm.jobject, // 目标 Java 对象句柄
) callconv(.c) jvm.jobjectArray {
    const jvmti_ptr = jvmti orelse return null;
    const env_ptr = env orelse return null;
    const target = target_object orelse return null;

    const jvmti_env = jvmti_ptr.*;
    const jni_env = env_ptr.*;

    const target_tag: jvm.jlong = 11111;   // 目标对象的临时 Tag
    const referrer_tag: jvm.jlong = 22222; // 引用者（Referrer）的临时 Tag

    // 1. 给目标对象打上特殊的临时 Tag
    const err_tag = jvmti_env.*.SetTag.?(jvmti_ptr, target, target_tag);
    if (err_tag != jvm.JVMTI_ERROR_NONE) {
        std.log.err("JVMTI error on SetTag: {d}", .{err_tag});
        return null;
    }

    // 2. 配置 FollowReferences 的引用遍历回调
    var callbacks = std.mem.zeroes(jvm.jvmtiHeapCallbacks);
    callbacks.heap_reference_callback = ReferrerCallback;

    // 3. 开始全局遍历整个堆的引用关系
    const target_tag_val = target_tag;
    const err_follow = jvmti_env.*.FollowReferences.?(
        jvmti_ptr,
        0,     // 0 代表遍历所有可达对象
        null,  // Class 过滤器（null 代表全局扫描）
        null,  // 起始对象（null 代表从 GC Roots 开始全堆扫描）
        &callbacks,
        &target_tag_val,
    );
    if (err_follow != jvm.JVMTI_ERROR_NONE) {
        std.log.err("JVMTI error on FollowReferences: {d}", .{err_follow});
        // 容错：恢复目标对象的 Tag 并返回空
        _ = jvmti_env.*.SetTag.?(jvmti_ptr, target, 0);
        return null;
    }

    // 4. 获取所有在遍历中被打上引用者标记（22222）的对象
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
        return null;
    }

    // 5. 如果没有找到任何引用者（例如该对象已经被孤立，仅等待 GC 回收）
    if (count == 0) {
        _ = jvmti_env.*.SetTag.?(jvmti_ptr, target, 0);
        return jni_env.*.NewObjectArray.?(
            env_ptr,
            0,
            jni_env.*.FindClass.?(env_ptr, "java/lang/Object"),
            null,
        );
    }

    // 6. 创建 java.lang.Object[] 类型的 Java 数组
    const obj_class = jni_env.*.FindClass.?(env_ptr, "java/lang/Object");
    const result_array = jni_env.*.NewObjectArray.?(
        env_ptr,
        count,
        obj_class,
        null,
    );

    // 7. 填充数组、重置引用者的 Tag 标签，并及时清理本地引用防止溢出
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

    // 8. 恢复目标对象本身的 Tag
    _ = jvmti_env.*.SetTag.?(jvmti_ptr, target, 0);

    // 9. 释放 JVMTI 内部分配的临时内存
    _ = jvmti_env.*.Deallocate.?(
        jvmti_ptr,
        @ptrCast(instances),
    );

    return result_array;
}

/// FollowReferences 核心回调函数：当 JVM 扫描到 A 引用 B 时触发
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

    const target_tag = @as(*const jvm.jlong, @ptrCast(@alignCast(user_data.?))).*;

    // 如果当前引用链指向的终点（Referee）正是我们打过标记的目标对象 (11111)
    if (tag_ptr.* == target_tag) {
        // 且引用起点（Referrer）是一个合法的堆对象（非 GC Roots）
        if (referrer_tag_ptr != null) {
            referrer_tag_ptr.* = 22222; // 给引用者打上 Tag
        }
    }

    return jvm.JVMTI_VISIT_OBJECTS; // 告诉 JVM 继续向下遍历
}