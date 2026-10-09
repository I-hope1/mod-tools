package nipx;

import java.util.*;

/**
 * 存活实例追踪器（字节码插桩回退方案）。
 *
 * <p>本类使用基于 {@link WeakHashMap} 的线程安全弱引用集合维护被追踪的对象实例。
 * 配合 {@link nipx.annotation.Tracker} 注解由 {@link AnnotationTransformer} 在构造函数末尾插桩，
 * 自动调用 {@link #register(Object)} 进行注册。</p>
 *
 * <p><b>分工与优先级</b>：
 * <ul>
 *   <li><b>高优先主路径</b>：当 Native 动态库已加载时（{@code LibTool.initialized() == true}），
 *       系统优先使用 JVMTI Native 堆遍历（{@link nipx.util.LibTool#getInstances(Class)}，
 *       参见 {@code docs/initfix/05-jvmti-heap.md}），具备无需字节码插桩、天然多态覆盖子类、零预埋开销的优势。</li>
 *   <li><b>回退/注解路径</b>：当 Native 底座未加载或运行在不支持 JVMTI 的环境时，
 *       或者针对标有 {@code @Tracker} 的特定类，降级使用本类检索存活实例。</li>
 * </ul>
 *
 * @see nipx.util.LibTool#getInstances(Class)
 * @see nipx.annotation.Tracker
 * @see nipx.annotation.OnReload
 */
public class InstanceTracker {
	// 线程安全的弱引用集合
	private static final Set<Object> watchedInstances =
	 Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

	// 被注入的代码会调用这个
	public static void register(Object obj) {
		/* // 已经有更好的方法
		if (LibTool.initialized()) return; */
		if (obj == null) return;
		watchedInstances.add(obj);
	}

	// 获取某个类的所有存活实例
	public static <T> List<T> getInstances(Class<T> clazz) {
		List<T> list = new ArrayList<>();
		synchronized (watchedInstances) {
			for (Object obj : watchedInstances) {
				if (clazz.isInstance(obj)) {
					list.add(clazz.cast(obj));
				}
			}
		}
		return list;
	}
}