package nipx.jvmti;

import nipx.jni.JNIEnv;
import nipx.jni.helper.GlobalRef;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.util.*;

import static nipx.jvmti.JVMTIEnv.*;

public class ReferenceFinder {
	private static final long TARGET_TAG   = 10001L;
	private static final long REFERRER_TAG = 20002L;

	// 回调的核心逻辑：如果指向的对象是我们的目标 Lambda（带 TARGET_TAG），
	// 那么就给引用者（referrer）打上 REFERRER_TAG
	static int heapRefCallback(
	 int refKind, MemorySegment refInfo, long classTag, long referrerClassTag,
	 long size, MemorySegment tagPtr, MemorySegment referrerTagPtr,
	 int length, MemorySegment userData) {

		long targetTag = tagPtr.get(ValueLayout.JAVA_LONG, 0);
		if (targetTag == TARGET_TAG) {
			// 找到了引用关系！将父对象打上 REFERRER_TAG
			referrerTagPtr.set(ValueLayout.JAVA_LONG, 0, REFERRER_TAG);
		}
		return 0; // 继续遍历 (JVMTI_VISIT_OBJECTS)
	}

	public static List<Object> findReferrers(JNIEnv jniEnv, JVMTIEnv jvmtiEnv, Object lambdaObj) {
		return findReferrers0(jniEnv, jvmtiEnv, lambdaObj);
	}
	private static List<Object> findReferrers0(JNIEnv jniEnv, JVMTIEnv jvmtiEnv, Object lambdaObj) {
		// 获取底层 jvmtiEnv*
		MemorySegment jvmtiEnvPtr = jvmtiEnv.jvmtiEnvPtr;
		MemorySegment fpSetTag    = jvmtiEnv.fpSetTag;

		try (Arena arena = Arena.ofConfined();
		     GlobalRef targetRef = jniEnv.JavaObjectToJObject(lambdaObj)) {

			// 1. 给目标 Lambda 对象打标
			int rc = (int) MH_SetTag.invokeExact(fpSetTag, jvmtiEnvPtr, targetRef.ref(), TARGET_TAG);
			if (rc != 0) throw new RuntimeException("SetTag failed: " + rc);

			// 2. 生成 Java 回调的 upcall stub
			MethodHandle upcallMH = MethodHandles.lookup().findStatic(
			 ReferenceFinder.class, "heapRefCallback",
			 MethodType.methodType(int.class, int.class, MemorySegment.class, long.class, long.class, long.class, MemorySegment.class, MemorySegment.class, int.class, MemorySegment.class)
			);
			MemorySegment upcallStub = Linker.nativeLinker().upcallStub(upcallMH, HEAP_REF_CALLBACK_DESC, arena);

			// 3. 构建 jvmtiHeapCallbacks 结构体
			MemorySegment callbacks = arena.allocate(JVMTI_HEAP_CALLBACKS_LAYOUT);
			// 写入 heap_reference_callback 槽位 (第2个地址指针)
			callbacks.set(ValueLayout.ADDRESS, 8, upcallStub);

			System.out.println("[Debug] jvmtiEnvPtr address: " + jvmtiEnvPtr.address());
			System.out.println("[Debug] callbacks struct address: " + callbacks.address());
			System.out.println("[Debug] upcallStub address: " + upcallStub.address());

			// 读取刚刚写入 callbacks 结构体偏移量为 8 的位置，看它是否正确保存了 upcallStub 的地址
			MemorySegment storedRefCallback = callbacks.get(ValueLayout.ADDRESS, 8);
			System.out.println("[Debug] Stored heap_reference_callback address: " + storedRefCallback.address());

			// 4. 开始跟随引用关系（这里传入 klass=NULL, initial_object=NULL 扫描全局堆）
			rc = (int) MH_FollowReferences.invokeExact(
			 jvmtiEnv.fp(IDX_FollowReferences), jvmtiEnvPtr,
			 0, // heap_filter
			 MemorySegment.NULL, // klass
			 MemorySegment.NULL, // initial_object
			 callbacks,
			 MemorySegment.NULL // user_data
			);
			checkError(rc, "FollowReferences");

			// 5. 提取所有被打上 REFERRER_TAG 的父对象
			MemorySegment tagBuf = arena.allocate(ValueLayout.JAVA_LONG);
			tagBuf.set(ValueLayout.JAVA_LONG, 0, REFERRER_TAG);

			MemorySegment countOut     = arena.allocate(ValueLayout.JAVA_INT);
			MemorySegment objResultPtr = arena.allocate(ValueLayout.ADDRESS);

			rc = (int) MH_GetObjectsWithTags.invokeExact(
			 jvmtiEnv.fp(IDX_GetObjectsWithTags), jvmtiEnvPtr,
			 1, tagBuf, countOut, objResultPtr, MemorySegment.NULL
			);
			checkError(rc, "GetObjectsWithTags");

			int count = countOut.get(ValueLayout.JAVA_INT, 0);
			if (count == 0) {
				return Collections.emptyList();
			}

			// 6. 将 jobject 地址通过你的 JNIEnv 转换成 Java 堆对象
			List<Object>  referrers    = new ArrayList<>(count);
			MemorySegment jobjectArray = objResultPtr.get(ValueLayout.ADDRESS, 0).reinterpret(count * ValueLayout.ADDRESS.byteSize());
			for (int i = 0; i < count; i++) {
				MemorySegment parentJObject = jobjectArray.get(ValueLayout.ADDRESS, i * ValueLayout.ADDRESS.byteSize());
				Object        parentJavaObj = jniEnv.jObjectToJavaObject(parentJObject);
				if (parentJavaObj != null) {
					referrers.add(parentJavaObj);
				}
			}

			// 7. 清理 Tags，避免干扰下一次查找，并释放底层内存
			MH_SetTag.invokeExact(fpSetTag, jvmtiEnvPtr, targetRef.ref(), 0L);
			for (int i = 0; i < count; i++) {
				MemorySegment parentJObject = jobjectArray.get(ValueLayout.ADDRESS, i * ValueLayout.ADDRESS.byteSize());
				MH_SetTag.invokeExact(fpSetTag, jvmtiEnvPtr, parentJObject, 0L);
			}
			// 释放由 JVMTI 分配的 jobject* 数组内存
			jvmtiEnv.jvmtiDeallocate(objResultPtr.get(ValueLayout.ADDRESS, 0));

			return referrers;
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}
}