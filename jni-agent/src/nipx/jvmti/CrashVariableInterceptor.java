package nipx.jvmti;

import nipx.jni.JNIEnv;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.util.*;

import static nipx.jvmti.JVMTIEnv.*;

public class CrashVariableInterceptor {

	// 全局弱引用 Map：自动在 Throwable 被 GC 时回收内存
	public static final    Map<Throwable, List<FrameLocals>> CRASH_LOCALS_MAP =
	 Collections.synchronizedMap(new WeakHashMap<>());
	public static volatile List<FrameLocals>                 lastLocals;

	// Exception 事件类型 ID = 58
	private static final int JVMTI_EVENT_EXCEPTION       = 58;
	private static final int JVMTI_EVENT_EXCEPTION_CATCH = 59;
	// 启用模式 = 1
	private static final int JVMTI_ENABLE                = 1;

	// 回调函数的 Panama FFM 签名
	private static final FunctionDescriptor EXCEPTION_CALLBACK_DESC = FunctionDescriptor.ofVoid(
	 ValueLayout.ADDRESS, // jvmtiEnv *jvmti_env
	 ValueLayout.ADDRESS, // JNIEnv* jni_env
	 ValueLayout.ADDRESS, // jthread thread
	 ValueLayout.ADDRESS, // jmethodID method
	 ValueLayout.JAVA_LONG, // jlocation location
	 ValueLayout.ADDRESS, // jobject exception
	 ValueLayout.ADDRESS, // jmethodID catch_method
	 ValueLayout.JAVA_LONG  // jlocation catch_location
	);

	private static final MethodHandle CALLBACK_MH;

	static {
		try {
			CALLBACK_MH = MethodHandles.lookup().findStatic(
			 CrashVariableInterceptor.class, "onExceptionThrown", MethodType.methodType(
				void.class,
				MemorySegment.class, MemorySegment.class, MemorySegment.class,
				MemorySegment.class, long.class, MemorySegment.class,
				MemorySegment.class, long.class
			 ));
		} catch (Throwable t) { throw new ExceptionInInitializerError(t); }
	}


	private static final ThreadLocal<Boolean> IN_CALLBACK =
        ThreadLocal.withInitial(() -> false);
	// 异常抛出时的 C++ 回调入口
	public static void onExceptionThrown(
	 MemorySegment jvmtiEnvPtr, MemorySegment jniEnvPtr, MemorySegment jthread,
	 MemorySegment method, long location, MemorySegment exception,
	 MemorySegment catchMethod, long catchLocation) {
		if (Thread.currentThread().getContextClassLoader() == null) return;
		boolean isFatal = catchMethod.address() == 0L;
		if (isFatal) return;

		if (IN_CALLBACK.get()) return;
		IN_CALLBACK.set(true);

		try (Arena arena = Arena.ofConfined()) {
			JNIEnv jniEnv = new JNIEnv(arena, jniEnvPtr);
			// 将底层的 jobject exception 转换回 Java 的 Throwable 实例
			// System.out.println(Thread.currentThread());
			Object javaThrowable = jniEnv.jObjectToJavaObject(exception);
			if (javaThrowable instanceof Throwable throwable) {
				// System.out.println(thread);
				StackCapture.captureInto(jniEnv, Thread.currentThread(), ((className, methodName, methodSig, thisAddress) -> {
					System.out.println(thisAddress);
				}));
			}
		}
	}

	/** 在游戏启动初始化时调用此方法，开启崩溃现场变量捕获 */
	public static void install() {
		try {
			Arena         globalArena = Arena.global();
			JNIEnv        jniEnv      = new JNIEnv(globalArena);
			JVMTIEnv      jvmtiEnv    = JVMTIEnv.getInstance();
			MemorySegment upcallStub  = Linker.nativeLinker().upcallStub(CALLBACK_MH, EXCEPTION_CALLBACK_DESC, globalArena);

			// 分配 jvmtiEventCallbacks 结构体 (大小为 35 × 8 字节)
			MemorySegment callbacks = globalArena.allocate(280, 8);
			// 填充到第 9 个槽位（Exception 监听，Offset = 64）
			callbacks.set(ValueLayout.ADDRESS, 64, upcallStub);

			// 3. 注册回调函数
			int rc = (int) MH_SetEventCallbacks.invokeExact(
			 jvmtiEnv.fp(IDX_SetEventCallbacks), jvmtiEnv.jvmtiEnvPtr, callbacks, (int) callbacks.byteSize()
			);
			checkError(rc, "SetEventCallbacks");

			// 4. 开启全局异常通知监听
			rc = (int) MH_SetEventNotificationMode.invokeExact(
			 jvmtiEnv.fp(IDX_SetEventNotificationMode), jvmtiEnv.jvmtiEnvPtr,
			 JVMTI_ENABLE, JVMTI_EVENT_EXCEPTION, MemorySegment.NULL // NULL 代表全局所有线程
			);
			checkError(rc, "SetEventNotificationMode");

			System.out.println("[CrashHandler] Variable interceptor installed successfully.");
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}
}