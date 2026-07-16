package nipx.jvmti;

import nipx.jni.JNIEnv;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.*;
import java.util.*;

import static nipx.jvmti.JVMTIEnv.*;

public class CrashVariableInterceptor {

	// 全局弱引用 Map：自动在 Throwable 被 GC 时回收内存
	public static final    Map<Throwable, List<FrameLocals>> CRASH_LOCALS_MAP =
	 Collections.synchronizedMap(new WeakHashMap<>());

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
	 ValueLayout.ADDRESS, // methodID method
	 ValueLayout.JAVA_LONG, // jlocation location
	 ValueLayout.ADDRESS, // jobject exception
	 ValueLayout.ADDRESS, // methodID catch_method
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


	private static final ThreadLocal<Boolean>       IN_CALLBACK = ThreadLocal.withInitial(() -> false);
	private static final ThreadLocal<StringBuilder> SB          = ThreadLocal.withInitial(StringBuilder::new);
	// 异常抛出时的 C++ 回调入口
	public static void onExceptionThrown(
	 MemorySegment jvmtiEnvPtr, MemorySegment jniEnvPtr, MemorySegment jthread,
	 MemorySegment method, long location, MemorySegment exception,
	 MemorySegment catchMethod, long catchLocation) {
		if (Thread.currentThread().getContextClassLoader() == null
		    || exception.address() == 0) { return; }
		// boolean isFatal = catchMethod.address() == 0L;
		// if (isFatal) return;


		if (IN_CALLBACK.get() == Boolean.TRUE) return;
		IN_CALLBACK.set(Boolean.TRUE);


		try (Arena arena = Arena.ofConfined()) {
			if (!captureAllExceptions() && !isCatchMethodInClass(arena, JVMTIEnv.getInstance(), catchMethod, "arc.backend.sdl.SdlApplication")) {
				return;
			}
			StringBuilder sb = SB.get();
			JNIEnv jniEnv = new JNIEnv(arena, jniEnvPtr);
			// 将底层的 jobject exception 转换回 Java 的 Throwable 实例
			// System.out.println(Thread.currentThread());
			Object javaThrowable = jniEnv.jObjectToJavaObject(exception);
			if (javaThrowable instanceof IOException
			    || javaThrowable instanceof ReflectiveOperationException) return;
			if (javaThrowable.getClass().getName().startsWith("sun.nio.fs.")) return;
			if (javaThrowable instanceof Throwable th) {
				var locals = JVMTIEnv.getInstance().captureThreadLocals(jniEnv, MemorySegment.NULL, 32, 14, true);
				sb.setLength(0);
				sb.append(th.getClass().getName()).append(": ").append(th.getMessage()).append('\n');
				for (FrameLocals frame : locals) {
					try (frame) {
						int lineNumber = JVMTIEnv.getInstance().getLineNumber(MemorySegment.ofAddress(frame.methodID()), frame.location());
						// int lineNumber = stackTrace[frame.depth()].getLineNumber();
						sb.append("Frame#").append(frame.depth()).append(" ")
						 .append(frame.className()).append('.').append(frame.methodName()).append(frame.methodSignature())
						 .append(" :").append(lineNumber)
						 .append('\n');
						for (LocalVariable local : (frame.locals())) {
							sb.append('\t').append(local.name()).append(": ");
							if (local.isReference() && local.value() != null) {
								String str = local.typeName();
							/* if (local.value() instanceof MemorySegment ref) {
								Object o = jniEnv.jObjectToJavaObject(ref);
								if (o.getClass().isInterface()) str = o.toString();
							} */
								sb.append(str).append('@').append(Integer.toHexString(local.hash()));
							} else {
								sb.append(local.value());
							}
							sb.append('\n');
						}
					}
				}
				System.out.println(sb);
				/* StackCapture.captureInto(jniEnv, Thread.currentThread(), ((className, methodName, methodSig, thisAddress) -> {
					System.out.println(methodSig);
				})); */
			}
		} catch (Throwable _) {
		} finally {
			IN_CALLBACK.set(Boolean.FALSE);
		}
	}
	private static boolean captureAllExceptions() {
		return Boolean.parseBoolean(System.getProperty("nipx.agent.capture_all_exceptions"));
	}
	/**
	 * 判断 catchMethod 是否属于指定类的方法
	 * @param jvmtiEnv             JVMTI 环境指针 (MemorySegment)
	 * @param catchMethod          要检查的 methodID (MemorySegment)
	 * @param targetClassSignature 目标类的 JVM 内部签名，如 "Ljava/lang/RuntimeException;"
	 * @return true 如果 catchMethod 属于目标类
	 */
	public static boolean isCatchMethodInClass(
	 Arena arena,
	 JVMTIEnv jvmtiEnv,
	 MemorySegment catchMethod,
	 String targetClassSignature
	) {
		if (catchMethod == null || catchMethod.address() == 0L) {
			return false; // 未捕获的异常
		}

		try {
			// 获取 catchMethod 的声明类
			MemorySegment classOut = arena.allocate(ValueLayout.ADDRESS);
			int           rc       = jvmtiEnv.getMethodDeclaringClass(catchMethod, classOut);
			if (rc != JVMTI_ERROR_NONE) return false;
			MemorySegment declaringClass  = classOut.get(ValueLayout.ADDRESS, 0);
			String        actualSignature = jvmtiEnv.fetchClassSig(arena, declaringClass);
			return targetClassSignature.equals(actualSignature);
		} catch (Throwable t) {
			return false;
		}
	}
	/* public static boolean isBoxClass(Class<?> c) {
		if (c == null) return false;
		return c == Integer.class ||
		       c == Float.class ||
		       c == Long.class ||
		       c == Double.class ||
		       c == Boolean.class ||
		       c == Character.class ||
		       c == Byte.class ||
		       c == Short.class ||
		       c == Void.class;
	} */

	/** 在游戏启动初始化时调用此方法，开启崩溃现场变量捕获 */
	public static void install() {
		try {
			Arena         globalArena = Arena.global();
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