package nipx.util;

import nipx.HotSwapAgent;

import java.io.File;
import java.lang.ref.*;
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

	/**
	 * 获取指定目标对象的所有【强引用持有者】，并全部以 WeakReference 包装返回。
	 *
	 * <p>特性：
	 * <ul>
	 *   <li>自动排除 WeakReference、SoftReference、PhantomReference 等通过 referent 指向它的弱引者；</li>
	 *   <li>支持识别 WeakHashMap.Entry（若目标是 Key 则被视作弱引用排除；若目标是 Value 则正确保留为强引用）；</li>
	 *   <li>返回的全部为 WeakReference，确保诊断工具本身【绝对不会】阻碍这些引用者被 GC 回收；</li>
	 *   <li>默认剔除自引用（持有自身的循环引用）。</li>
	 * </ul>
	 * @param target 目标对象
	 * @return 强引用者的弱引用列表（若无强引用或 target 为 null，返回空列表）
	 */
	public static List<WeakReference<Object>> getStrongReferrersAsWeak(Object target) {
		return getStrongReferrersAsWeak(target, true);
	}

	/**
	 * @param target      目标对象
	 * @param excludeSelf 是否排除自身对自身的引用
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static List<WeakReference<Object>> getStrongReferrersAsWeak(Object target, boolean excludeSelf) {
		if (target == null) return Collections.emptyList();

		// 调用底层 C++ JVMTI 接口获取堆中所有直接引用者（包含强、弱、软、虚引用）
		Object[] rawReferrers = LibTool.nGetReferrers(target);
		if (rawReferrers == null || rawReferrers.length == 0) {
			return Collections.emptyList();
		}

		List<WeakReference<Object>> safeList = new ArrayList<>(rawReferrers.length);

		// 内存过滤与弱引用转化
		for (Object referrer : rawReferrers) {
			if (referrer == null) continue;

			// 选项：排除自身引用自身（如 this.self = this）
			if (excludeSelf && referrer == target) {
				continue;
			}

			// 核心判定：如果引用者本身是一个 java.lang.ref.Reference，
			// 且它正是通过弱指针槽位（referent）指向了 target，则说明这是无害的弱引用，直接过滤！
			if (referrer instanceof Reference ref && ref.refersTo(target)) {
				continue;
			}

			// 走到这里的全部是真正的【强引用持有者】！
			// 使用 WeakReference 封装，打破长效观察者对元凶对象的生命周期锁定
			safeList.add(new WeakReference<>(referrer));
		}

		// \rawReferrers 数组在方法结束后随局部变量出栈，强引用瞬间全部解脱！
		return safeList;
	}
}
