package nipx.util;

import nipx.HotSwapAgent;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.util.*;

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

	/**
	 * 获取指定类及其子类在堆中所有尚未被回收的实例并返回局部引用数组。
	 * 包含多态子类与接口实现，包含不可达但尚未被 GC 物理回收的对象。
	 */
	@SuppressWarnings("unchecked")
	public synchronized static <T> T[] getInstances(Class<T> clazz) {
		if (!initialized) init();
		T[] res = (T[]) nGetInstances(clazz);
		return res != null ? res : (T[]) Array.newInstance(clazz, 0);
	}

	/**
	 * 获取指定对象在 JVM 中的所有引用对象，如果是Class
	 * 语义说明：仅包含堆中其他对象对它的字段引用，不包含线程栈局部变量、JNI 全局引用等 GC Roots。
	 * 若目标对象本身为 Class 对象，堆中所有该类的实例均会被视作引用者（即实例对自身类的类引用关系）。
	 */
	public synchronized static Object[] getReferrers(Object targetObject) {
		if (!initialized) init();
		Object[] res = nGetReferrers(targetObject);
		return res != null ? res : new Object[0];
	}

	/** 获取指定对象在 JVM 中的所有引用对象，使用 WeakReference 包装 */
	public static List<WeakReference<Object>> getReferrersSafe(Object target) {
		Object[] raw = nGetReferrers(target);
		if (raw == null || raw.length == 0) return Collections.emptyList();

		List<WeakReference<Object>> safeList = new ArrayList<>(raw.length);
		for (Object o : raw) {
			if (o != null) {
				safeList.add(new WeakReference<>(o));
			}
		}
		return safeList;
	}
}
