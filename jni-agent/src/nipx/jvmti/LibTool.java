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
		private static final MethodHandle MH_GetInstances;
		private static void load() {}

		static {
			try {
				// 1. 加载 libtool.dll 动态链接库
				Path         libPath = Paths.get(System.getProperty("nipx.path.libtool")).toAbsolutePath();
				SymbolLookup lib     = SymbolLookup.libraryLookup(libPath, Arena.global());

				MemorySegment getInstancesFp = lib.find("GetInstances")
				 .orElseThrow(() -> new UnsatisfiedLinkError("Cannot find GetInstances in libtool.dll"));

				// 2. 绑定 Downcall 方法句柄
				// Zig 签名: export fn GetInstances(jvmti: ?*jvm.jvmtiEnv, env: ?*jvm.JNIEnv, klass: jvm.jclass) callconv(.c) jvm.jobjectArray
				MH_GetInstances = Linker.nativeLinker().downcallHandle(
				 getInstancesFp,
				 FunctionDescriptor.of(
					ValueLayout.ADDRESS, // 返回值：jobjectArray (MemorySegment 地址)
					ValueLayout.ADDRESS, // 参数 1：jvmtiEnv* jvmti (指针的指针)
					ValueLayout.ADDRESS, // 参数 2：JNIEnv* env     (指针的指针)
					ValueLayout.ADDRESS  // 参数 3：jclass klass      (jclass 句柄地址)
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
}
