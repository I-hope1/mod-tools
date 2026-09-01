package hope.magic.js.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一的 Java 方法与构造函数反射查找与重载解析器。
 * 消除 JSLinker 与 MagicJIT 之间重复的反射查找逻辑，提供基于 ConcurrentHashMap 的 O(1) 方法缓存。
 */
public final class MethodResolver {

	public record MethodKey(Class<?> clazz, String name, int arity, boolean isStatic) {}
	public record CtorKey(Class<?> clazz, int arity) {}
	public record PropKey(Class<?> clazz, String propName) {}

	private static final Map<MethodKey, Method> METHOD_CACHE = new ConcurrentHashMap<>();
	private static final Map<CtorKey, Constructor<?>> CTOR_CACHE = new ConcurrentHashMap<>();
	private static final Map<PropKey, List<Method>> CANDIDATE_CACHE = new ConcurrentHashMap<>();
	private static final Map<PropKey, Method> GETTER_CACHE = new ConcurrentHashMap<>();
	private static final Map<PropKey, Method> SETTER_CACHE = new ConcurrentHashMap<>();

	private MethodResolver() {}

	/**
	 * 按类、方法名与参数个数精确查找方法（优先 declared，后 public，匹配 static/instance 语义）。
	 */
	public static Method findMethod(Class<?> clazz, String methodName, int arity, boolean isStatic) {
		if (clazz == null || methodName == null) return null;
		MethodKey key = new MethodKey(clazz, methodName, arity, isStatic);
		return METHOD_CACHE.computeIfAbsent(key, k -> {
			for (Method m : clazz.getDeclaredMethods()) {
				if (m.getName().equals(methodName) && m.getParameterCount() == arity && Modifier.isStatic(m.getModifiers()) == isStatic) {
					m.setAccessible(true);
					return m;
				}
			}
			for (Method m : clazz.getMethods()) {
				if (m.getName().equals(methodName) && m.getParameterCount() == arity && Modifier.isStatic(m.getModifiers()) == isStatic) {
					m.setAccessible(true);
					return m;
				}
			}
			// 宽松回退（不强求 static 修饰符精确匹配）
			for (Method m : clazz.getDeclaredMethods()) {
				if (m.getName().equals(methodName) && m.getParameterCount() == arity) {
					m.setAccessible(true);
					return m;
				}
			}
			for (Method m : clazz.getMethods()) {
				if (m.getName().equals(methodName) && m.getParameterCount() == arity) {
					m.setAccessible(true);
					return m;
				}
			}
			return null;
		});
	}

	public static Method findMethod(Class<?> clazz, String methodName, int arity) {
		return findMethod(clazz, methodName, arity, false);
	}

	/**
	 * 查找类中所有同名重载候选方法（public + declared，去重）。
	 */
	public static List<Method> findCandidateMethods(Class<?> clazz, String methodName) {
		if (clazz == null || methodName == null) return Collections.emptyList();
		PropKey key = new PropKey(clazz, methodName);
		return CANDIDATE_CACHE.computeIfAbsent(key, k -> {
			List<Method> list = new ArrayList<>();
			for (Method m : clazz.getMethods()) {
				if (m.getName().equals(methodName)) {
					m.setAccessible(true);
					list.add(m);
				}
			}
			for (Method m : clazz.getDeclaredMethods()) {
				if (m.getName().equals(methodName) && !list.contains(m)) {
					m.setAccessible(true);
					list.add(m);
				}
			}
			return Collections.unmodifiableList(list);
		});
	}

	/**
	 * 查找目标参数个数的构造函数。
	 */
	public static Constructor<?> findConstructor(Class<?> clazz, int arity) {
		if (clazz == null) return null;
		CtorKey key = new CtorKey(clazz, arity);
		return CTOR_CACHE.computeIfAbsent(key, k -> {
			for (Constructor<?> c : clazz.getDeclaredConstructors()) {
				if (c.getParameterCount() == arity) {
					c.setAccessible(true);
					return c;
				}
			}
			for (Constructor<?> c : clazz.getConstructors()) {
				if (c.getParameterCount() == arity) {
					c.setAccessible(true);
					return c;
				}
			}
			return null;
		});
	}

	/**
	 * 查找 JavaBean 规范 getter 方法 (getProp / isProp / prop)。
	 */
	public static Method findGetterMethod(Class<?> clazz, String propName) {
		if (clazz == null || propName == null || propName.isEmpty()) return null;
		PropKey key = new PropKey(clazz, propName);
		return GETTER_CACHE.computeIfAbsent(key, k -> {
			String capName = Character.toUpperCase(propName.charAt(0)) + (propName.length() > 1 ? propName.substring(1) : "");
			String[] getterCandidates = new String[]{"get" + capName, "is" + capName, propName};
			for (String candidate : getterCandidates) {
				try {
					Method method = clazz.getMethod(candidate);
					if (method.getParameterCount() == 0) {
						method.setAccessible(true);
						return method;
					}
				} catch (Throwable ignored) {
				}
			}
			for (Method m : clazz.getDeclaredMethods()) {
				if (m.getParameterCount() == 0) {
					for (String candidate : getterCandidates) {
						if (m.getName().equals(candidate)) {
							m.setAccessible(true);
							return m;
						}
					}
				}
			}
			return null;
		});
	}

	/**
	 * 查找 JavaBean 规范 setter 方法 (setProp)。
	 */
	public static Method findSetterMethod(Class<?> clazz, String propName) {
		if (clazz == null || propName == null || propName.isEmpty()) return null;
		PropKey key = new PropKey(clazz, propName);
		return SETTER_CACHE.computeIfAbsent(key, k -> {
			String capName = Character.toUpperCase(propName.charAt(0)) + (propName.length() > 1 ? propName.substring(1) : "");
			String setterName = "set" + capName;
			for (Method m : clazz.getMethods()) {
				if (m.getName().equals(setterName) && m.getParameterCount() == 1) {
					m.setAccessible(true);
					return m;
				}
			}
			for (Method m : clazz.getDeclaredMethods()) {
				if (m.getName().equals(setterName) && m.getParameterCount() == 1) {
					m.setAccessible(true);
					return m;
				}
			}
			return null;
		});
	}
}
