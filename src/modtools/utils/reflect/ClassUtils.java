package modtools.utils.reflect;


import arc.struct.ObjectSet;

public class ClassUtils {

	/**
	 * 获取给定类及其父类的所有类对象的集合
	 * @param cls 给定的类对象
	 * @return 包含给定类及其父类的所有类对象的集合
	 */
	public static ObjectSet<Class<?>> getClassAndParents(Class<?> cls) {
		ObjectSet<Class<?>> seq = new ObjectSet<>();
		seq.add(cls);

		for (Class<?> inter : cls.getInterfaces()) {
			seq.add(inter);
		}
		Class<?> superclass = cls.getSuperclass();
		if (superclass != null) seq.addAll(getClassAndParents(superclass));
		return seq;
	}

}
