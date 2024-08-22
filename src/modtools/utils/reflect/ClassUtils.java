package modtools.utils.reflect;


import arc.func.Cons;
import arc.struct.ObjectSet;

import java.lang.reflect.*;

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

	/**
	 * 获取指定类的非匿名父类
	 *
	 * @param cls 指定的类
	 * @return 非匿名父类的Class对象
	 */
	public static Class<?> getSuperExceptAnonymous(Class<?> cls) {
	    while (cls.isAnonymousClass()) cls = cls.getSuperclass();
	    return cls;
	}

	/**
	 * 遍历类中所有非静态的公有字段
	 *
	 * @param cls 要遍历的类
	 * @param cons 处理字段的消费者函数，用于对每个符合条件的字段执行操作
	 */
	public static void walkPublicNotStaticKeys(Class<?> cls, Cons<Field> cons) {
	    for (Field field : cls.getFields()) {
	        int mod = field.getModifiers();
	        // 跳过静态字段和非公有字段
	        if (Modifier.isStatic(mod) || !Modifier.isPublic(mod)) continue;
	        cons.get(field);
	    }
	}
}
