package nipx;

import java.util.*;

public class InstanceTracker {
	// 线程安全的弱引用集合
	private static final Set<Object> watchedInstances =
	 Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

	// 被注入的代码会调用这个
	public static void register(Object obj) {
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