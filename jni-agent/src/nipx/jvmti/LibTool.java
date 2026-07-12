package nipx.jvmti;

import nipx.jni.JNIEnv;
import nipx.jni.helper.GlobalRef;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Array;
import java.nio.file.*;

public class LibTool {
	private static boolean initialized;
	public static boolean initialized() {
		return initialized;
	}
	public static void init() {
		if (initialized) return;
		Lib.load();
		initialized = true;
		// getInstances(Unsafe.class);
		System.out.println("[NIPX] Loaded libtool.");
	}
	static class Lib {
		private static final MethodHandle
		 MH_GetInstances,
		 MH_GetReferrers;
		private static void load() { }

		static {
			try {
				// 加载 libtool.dll 动态链接库
				Path         libPath = Paths.get(System.getProperty("nipx.path.libtool")).toAbsolutePath();
				SymbolLookup lib     = SymbolLookup.libraryLookup(libPath, Arena.global());

				MemorySegment getInstancesFp = lib.find("GetInstances")
				 .orElseThrow(() -> new UnsatisfiedLinkError("Cannot find GetInstances in libtool.dll"));

				// export fn GetInstances(jvmti: ?*jvm.jvmtiEnv, env: ?*jvm.JNIEnv, klass: jvm.jclass) callconv(.c) jvm.jobjectArray
				MH_GetInstances = Linker.nativeLinker().downcallHandle(
				 getInstancesFp,
				 FunctionDescriptor.of(ValueLayout.ADDRESS, // jobjectArray
					ValueLayout.ADDRESS, // jvmtiEnv* jvmti (指针的指针)
					ValueLayout.ADDRESS, // JNIEnv* env   (指针的指针)
					ValueLayout.ADDRESS  // jclass klass   (jclass 句柄地址)
				 )
				);

				// export fn GetReferrers(jvmti: ?*jvm.jvmtiEnv, env: ?*jvm.JNIEnv, target_object: jvm.jobject) callconv(.c) jvm.jobjectArray
				MemorySegment getReferrers = lib.find("GetReferrers")
				 .orElseThrow(() -> new UnsatisfiedLinkError("Cannot find GetReferrers in libtool.dll"));
				MH_GetReferrers = Linker.nativeLinker().downcallHandle(
				 getReferrers,
				 FunctionDescriptor.of(ValueLayout.ADDRESS, // jobjectArray
					ValueLayout.ADDRESS, // jvmtiEnv* jvmti (指针的指针)
					ValueLayout.ADDRESS, // JNIEnv* env   (指针的指针)
					ValueLayout.ADDRESS  // jobject target_object   (jclass 句柄地址)
				 )
				);
			} catch (Throwable t) {
				throw new ExceptionInInitializerError(t);
			}
		}
	}

	/**
	 * 获取指定类在 JVM 中的所有活跃实例
	 * @param clazz 目标 Class
	 * @return 该类的实例数组
	 */
	@SuppressWarnings("unchecked")
	public static <T> T[] getInstances(Class<T> clazz) {
		JVMTIEnv jvmtiEnv = JVMTIEnv.getInstance();

		try (Arena arena = Arena.ofConfined()) {
			// 初始化本次调用所需的 JNIEnv
			JNIEnv jniEnv = new JNIEnv(arena);

			// 获取目标类的 jclass 引用
			try (GlobalRef classRef = jniEnv.FindClass(clazz)) {
				MemorySegment klass = classRef.ref();

				// 原生调用
				MemorySegment resultArrayRef = (MemorySegment) Lib.MH_GetInstances.invokeExact(
				 jvmtiEnv.jvmtiEnvPtr, jniEnv.getJniEnvPointer(), klass);

				if (resultArrayRef.address() == 0) {
					return (T[]) Array.newInstance(clazz, 0);
				}

				// 将 native 的 jobjectArray 转换回 Java 侧真实的 T[] 数组
				Object arrayObj = jniEnv.jObjectToJavaObject(resultArrayRef);
				return (T[]) arrayObj;
			}
		} catch (Throwable t) {
			throw new RuntimeException("Failed to retrieve instances from JVMTI", t);
		}
	}
	/** 获取指定对象在 JVM 中的所有引用对象 */
	public static Object[] getReferrers(Object targetObject) {
		JVMTIEnv jvmtiEnv = JVMTIEnv.getInstance();
		try (Arena arena = Arena.ofConfined()) {
			JNIEnv jniEnv = new JNIEnv(arena);
			try (GlobalRef targetObjectRef = jniEnv.JavaObjectToJObject(targetObject)) {
				MemorySegment resultArrayRef = (MemorySegment) Lib.MH_GetReferrers.invokeExact(
				 jvmtiEnv.jvmtiEnvPtr, jniEnv.getJniEnvPointer(), targetObjectRef.ref());

				return (Object[]) jniEnv.jObjectToJavaObject(resultArrayRef);
			}
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}
}
