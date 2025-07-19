package modtools.utils.reflect;


import arc.func.Cons;
import arc.struct.ObjectSet;
import modtools.jsfunc.IScript;
import modtools.jsfunc.type.CAST;

import java.lang.reflect.*;

public class ClassUtils {
	/**
	 * 根据类名获取类对象
	 * @param name 类名
	 * @return 类对象
	 */
	public static Class<?> forName(String name) {
		try {
			return Class.forName(name);
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
	}

	/** 是否存在某个类 */
	public static boolean exists(String className) {
		try {
			Class.forName(className);
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}

	}

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
	 * @param cls 指定的类
	 * @return 非匿名父类的Class对象
	 */
	public static Class<?> getSuperExceptAnonymous(Class<?> cls) {
		while (cls.isAnonymousClass()) cls = cls.getSuperclass();
		return cls;
	}

	/**
	 * 遍历类中所有非静态的公有字段
	 * @param cls  要遍历的类
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
	public static void walkNotStaticMethod(Class<?> cls, Cons<Method> cons) {
		while (cls != null) {
			for (Method method : cls.getDeclaredMethods()) {
				int mod = method.getModifiers();
				// 跳过静态字段和非公有字段
				if (Modifier.isStatic(mod)) continue;
				cons.get(method);
			}
			cls = cls.getSuperclass();
		}
	}
	public static String paramsToString(Class<?>[] parameterTypes) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < parameterTypes.length; i++) {
			Class<?> parameterType = parameterTypes[i];
			sb.append("var").append(i);
			sb.append(": ").append(parameterType.getSimpleName());
			sb.append(i == parameterTypes.length - 1 ? "" : ", ");
		}
		return "(" + sb + ")";
	}
	public static Object[] paramsToArgs(Class<?>[] parameterTypes) {
		Object[] result = new Object[parameterTypes.length * 2];
		for (int i = 0; i < parameterTypes.length; i++) {
			Class<?> type = CAST.box(parameterTypes[i]);
			result[i * 2] = "var" + i;
			result[i * 2 + 1] = IScript.cx.getWrapFactory().wrap(IScript.cx, IScript.scope, new Object(), type);
		}
		return result;
	}
	public static Object[] wrapArgs(Object[] args) {
		Object[] result = new Object[args.length * 2];
		for (int i = 0; i < args.length; i++) {
			result[i * 2] = "var" + i;
			result[i * 2 + 1] = args[i];
		}
		return result;
	}
}
