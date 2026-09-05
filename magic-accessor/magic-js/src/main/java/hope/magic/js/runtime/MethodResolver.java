package hope.magic.js.runtime;

import java.lang.reflect.AccessibleObject;
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

	public static final class MethodKey {
		public final Class<?> clazz;
		public final String name;
		public final int arity;
		public final boolean isStatic;
		private final int hash;

		public MethodKey(Class<?> clazz, String name, int arity, boolean isStatic) {
			this.clazz = clazz;
			this.name = name;
			this.arity = arity;
			this.isStatic = isStatic;
			this.hash = (clazz.hashCode() * 31 + name.hashCode()) * 31 + (arity << 1 | (isStatic ? 1 : 0));
		}

		@Override
		public int hashCode() { return hash; }

		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (!(obj instanceof MethodKey o)) return false;
			return arity == o.arity && isStatic == o.isStatic && clazz == o.clazz && name.equals(o.name);
		}
	}

	public static final class CtorKey {
		public final Class<?> clazz;
		public final int arity;
		private final int hash;

		public CtorKey(Class<?> clazz, int arity) {
			this.clazz = clazz;
			this.arity = arity;
			this.hash = clazz.hashCode() * 31 + arity;
		}

		@Override
		public int hashCode() { return hash; }

		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (!(obj instanceof CtorKey o)) return false;
			return arity == o.arity && clazz == o.clazz;
		}
	}

	public static final class PropKey {
		public final Class<?> clazz;
		public final String propName;
		private final int hash;

		public PropKey(Class<?> clazz, String propName) {
			this.clazz = clazz;
			this.propName = propName;
			this.hash = clazz.hashCode() * 31 + propName.hashCode();
		}

		@Override
		public int hashCode() { return hash; }

		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (!(obj instanceof PropKey o)) return false;
			return clazz == o.clazz && propName.equals(o.propName);
		}
	}

	private static final Map<MethodKey, Method> METHOD_CACHE = new ConcurrentHashMap<>();
	private static final Map<CtorKey, Constructor<?>> CTOR_CACHE = new ConcurrentHashMap<>();
	private static final Map<PropKey, List<Method>> CANDIDATE_CACHE = new ConcurrentHashMap<>();
	private static final Map<PropKey, Method> GETTER_CACHE = new ConcurrentHashMap<>();
	private static final Map<PropKey, Method> SETTER_CACHE = new ConcurrentHashMap<>();

	private MethodResolver() {}

	private static boolean trySetAccessible(AccessibleObject ao) {
		try {
			return ao.trySetAccessible();
		} catch (Throwable ignored) {
			return false;
		}
	}

	/**
	 * 按类、方法名与参数个数精确查找方法（优先 declared，后 public，匹配 static/instance 语义）。
	 */
	public static Method findMethod(Class<?> clazz, String methodName, int arity, boolean isStatic) {
		if (clazz == null || methodName == null) return null;
		MethodKey key = new MethodKey(clazz, methodName, arity, isStatic);
		Method cached = METHOD_CACHE.get(key);
		if (cached != null) return cached;

		Method found = null;
		try {
			for (Method m : clazz.getDeclaredMethods()) {
				if (m.getName().equals(methodName) && m.getParameterCount() == arity && Modifier.isStatic(m.getModifiers()) == isStatic) {
					if (trySetAccessible(m)) {
						found = m;
						break;
					}
				}
			}
		} catch (Throwable ignored) {}
		if (found == null) {
			for (Method m : clazz.getMethods()) {
				if (m.getName().equals(methodName) && m.getParameterCount() == arity && Modifier.isStatic(m.getModifiers()) == isStatic) {
					trySetAccessible(m);
					found = m;
					break;
				}
			}
		}
		// 宽松回退（不强求 static 修饰符精确匹配）
		if (found == null) {
			try {
				for (Method m : clazz.getDeclaredMethods()) {
					if (m.getName().equals(methodName) && m.getParameterCount() == arity) {
						if (trySetAccessible(m)) {
							found = m;
							break;
						}
					}
				}
			} catch (Throwable ignored) {}
		}
		if (found == null) {
			for (Method m : clazz.getMethods()) {
				if (m.getName().equals(methodName) && m.getParameterCount() == arity) {
					trySetAccessible(m);
					found = m;
					break;
				}
			}
		}
		if (found != null) {
			METHOD_CACHE.put(key, found);
		}
		return found;
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
		List<Method> cached = CANDIDATE_CACHE.get(key);
		if (cached != null) {
			return cached;
		}

		List<Method> list = new ArrayList<>();
		for (Method m : clazz.getMethods()) {
			if (m.getName().equals(methodName)) {
				trySetAccessible(m);
				list.add(m);
			}
		}
		try {
			for (Method m : clazz.getDeclaredMethods()) {
				if (m.getName().equals(methodName) && !list.contains(m)) {
					if (trySetAccessible(m)) {
						list.add(m);
					}
				}
			}
		} catch (Throwable ignored) {}
		List<Method> unmod = Collections.unmodifiableList(list);
		CANDIDATE_CACHE.put(key, unmod);
		return unmod;
	}

	/**
	 * 查找目标参数个数的构造函数。
	 */
	public static Constructor<?> findConstructor(Class<?> clazz, int arity) {
		if (clazz == null) return null;
		CtorKey key = new CtorKey(clazz, arity);
		Constructor<?> cached = CTOR_CACHE.get(key);
		if (cached != null) return cached;

		Constructor<?> found = null;
		try {
			for (Constructor<?> c : clazz.getDeclaredConstructors()) {
				if (c.getParameterCount() == arity) {
					if (trySetAccessible(c)) {
						found = c;
						break;
					}
				}
			}
		} catch (Throwable ignored) {}
		if (found == null) {
			for (Constructor<?> c : clazz.getConstructors()) {
				if (c.getParameterCount() == arity) {
					trySetAccessible(c);
					found = c;
					break;
				}
			}
		}
		if (found != null) {
			CTOR_CACHE.put(key, found);
		}
		return found;
	}

	/**
	 * 查找 JavaBean 规范 getter 方法 (getProp / isProp / prop)。
	 */
	public static Method findGetterMethod(Class<?> clazz, String propName) {
		if (clazz == null || propName == null || propName.isEmpty()) return null;
		PropKey key = new PropKey(clazz, propName);
		Method cached = GETTER_CACHE.get(key);
		if (cached != null) return cached;

		String capName = Character.toUpperCase(propName.charAt(0)) + (propName.length() > 1 ? propName.substring(1) : "");
		String[] getterCandidates = new String[]{"get" + capName, "is" + capName, propName};
		Method found = null;
		for (String candidate : getterCandidates) {
			try {
				Method method = clazz.getMethod(candidate);
				if (method.getParameterCount() == 0) {
					trySetAccessible(method);
					found = method;
					break;
				}
			} catch (Throwable ignored) {
			}
		}
		if (found == null) {
			try {
				for (Method m : clazz.getDeclaredMethods()) {
					if (m.getParameterCount() == 0) {
						for (String candidate : getterCandidates) {
							if (m.getName().equals(candidate)) {
								if (trySetAccessible(m)) {
									found = m;
									break;
								}
							}
						}
						if (found != null) break;
					}
				}
			} catch (Throwable ignored) {}
		}
		if (found != null) {
			GETTER_CACHE.put(key, found);
		}
		return found;
	}

	/**
	 * 查找 JavaBean 规范 setter 方法 (setProp)。
	 */
	public static Method findSetterMethod(Class<?> clazz, String propName) {
		if (clazz == null || propName == null || propName.isEmpty()) return null;
		PropKey key = new PropKey(clazz, propName);
		Method cached = SETTER_CACHE.get(key);
		if (cached != null) return cached;

		String capName = Character.toUpperCase(propName.charAt(0)) + (propName.length() > 1 ? propName.substring(1) : "");
		String setterName = "set" + capName;
		Method found = null;
		for (Method m : clazz.getMethods()) {
			if (m.getName().equals(setterName) && m.getParameterCount() == 1) {
				trySetAccessible(m);
				found = m;
				break;
			}
		}
		if (found == null) {
			try {
				for (Method m : clazz.getDeclaredMethods()) {
					if (m.getName().equals(setterName) && m.getParameterCount() == 1) {
						if (trySetAccessible(m)) {
							found = m;
							break;
						}
					}
				}
			} catch (Throwable ignored) {}
		}
		if (found != null) {
			SETTER_CACHE.put(key, found);
		}
		return found;
	}
}
