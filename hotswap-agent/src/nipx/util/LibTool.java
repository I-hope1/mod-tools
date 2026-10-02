package nipx.util;

import nipx.HotSwapAgent;

import java.io.File;
import java.lang.reflect.Array;

/** 依赖jvmti的工具类 */
public class LibTool {
	private static boolean initialized;

	public static boolean initialized() {
		return initialized;
	}

	public static void init() {
		if (initialized) return;
		String libPath = System.getProperty("nipx.path.libtool");
		if (libPath != null && !libPath.isEmpty()) {
			System.load(new File(libPath).getAbsolutePath());
		} else {
			System.loadLibrary("tool64");
		}
		initialized = true;
		HotSwapAgent.info("[Lib] Loaded libtool.");
	}

	private static native Object[] nGetInstances(Class<?> clazz);
	private static native Object[] nGetReferrers(Object targetObject);

	/** 获取指定类在 JVM 中的所有活跃实例 */
	@SuppressWarnings("unchecked")
	public synchronized static <T> T[] getInstances(Class<T> clazz) {
		if (!initialized) init();
		T[] res = (T[]) nGetInstances(clazz);
		return res != null ? res : (T[]) Array.newInstance(clazz, 0);
	}

	/** 获取指定对象在 JVM 中的所有引用对象 */
	public synchronized static Object[] getReferrers(Object targetObject) {
		if (!initialized) init();
		Object[] res = nGetReferrers(targetObject);
		return res != null ? res : new Object[0];
	}
}
