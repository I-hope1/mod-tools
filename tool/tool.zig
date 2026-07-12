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