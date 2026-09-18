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

	static final int COST_INCOMPATIBLE = 1_000_000;
	public static Method findBestMatchingMethod(Class<?> clazz, String methodName, Object[] args) {
		List<Method> candidates = findCandidateMethods(clazz, methodName);
		if (candidates.isEmpty()) return null;

		// Phase 1: 固定参数匹配 (Fixed-Arity)
		Method       bestMethod = null;
		int          minCost    = COST_INCOMPATIBLE;
		List<Method> applicable = new ArrayList<>();

		for (Method m : candidates) {
			if (m.getParameterCount() != args.length) continue;
			Class<?>[] params    = m.getParameterTypes();
			int        totalCost = 0;
			boolean    ok        = true;
			for (int i = 0; i < args.length; i++) {
				int c = JSLinker.computeConversionCost(args[i], params[i]);
				if (c >= COST_INCOMPATIBLE) {
					ok = false;
					break;
				}
				totalCost += c;
			}
			if (ok) {
				applicable.add(m);
				if (totalCost < minCost) {
					minCost = totalCost;
					bestMethod = m;
				}
			}
		}

		if (!applicable.isEmpty()) {
			// 在低成本候选方法中应用 JLS Pairwise Specificity
			List<Method> bestCandidates = new ArrayList<>();
			for (Method m : applicable) {
				Class<?>[] params = m.getParameterTypes();
				int        cost   = 0;
				for (int i = 0; i < args.length; i++) cost += JSLinker.computeConversionCost(args[i], params[i]);
				if (cost == minCost) bestCandidates.add(m);
			}
			if (bestCandidates.size() == 1) return bestCandidates.get(0);
			// 挑选最具体的方法
			Method mostSpecific = bestCandidates.get(0);
			for (int i = 1; i < bestCandidates.size(); i++) {
				Method curr = bestCandidates.get(i);
				if (JSLinker.isMoreSpecific(curr, mostSpecific)) {
					mostSpecific = curr;
				}
			}
			return mostSpecific;
		}

		// Phase 2: 可变参数匹配 (Varargs)
		for (Method m : candidates) {
			if (!m.isVarArgs()) continue;
			int paramCount = m.getParameterCount();
			if (args.length < paramCount - 1) continue;
			Class<?>[] params         = m.getParameterTypes();
			Class<?>   varargElemType = params[paramCount - 1].getComponentType();
			boolean    ok             = true;
			int        totalCost      = 1000; // Varargs 惩罚项
			for (int i = 0; i < paramCount - 1; i++) {
				int c = JSLinker.computeConversionCost(args[i], params[i]);
				if (c >= COST_INCOMPATIBLE) {
					ok = false;
					break;
				}
				totalCost += c;
			}
			if (ok) {
				for (int i = paramCount - 1; i < args.length; i++) {
					int c = JSLinker.computeConversionCost(args[i], varargElemType);
					if (c >= COST_INCOMPATIBLE) {
						ok = false;
						break;
					}
					totalCost += c;
				}
			}
			if (ok && totalCost < minCost) {
				minCost = totalCost;
				bestMethod = m;
			}
		}

		return bestMethod;
	}
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

	private static final class MethodLookupKey {
		final String  name;
		final int     arity;
		final boolean isStatic;
		final int     hash;

		MethodLookupKey(String name, int arity, boolean isStatic) {
			this.name = name;
			this.arity = arity;
			this.isStatic = isStatic;
			this.hash = 31 * name.hashCode() + (arity << 1 | (isStatic ? 1 : 0));
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof MethodLookupKey that)) return false;
			return arity == that.arity && isStatic == that.isStatic && name.equals(that.name);
		}

		@Override
		public int hashCode() {
			return hash;
		}
	}

	private static final class ClassReflectionData {
		final Map<MethodLookupKey, Method> methodCache = new ConcurrentHashMap<>();
		final Map<Integer, Constructor<?>> ctorCache = new ConcurrentHashMap<>();
		final Map<String, List<Method>> candidateCache = new ConcurrentHashMap<>();
		volatile List<Constructor<?>> candidateCtors;
		final Map<String, Method> getterCache = new ConcurrentHashMap<>();
		final Map<String, Method> setterCache = new ConcurrentHashMap<>();
	}

	private static final ClassValue<ClassReflectionData> REFLECTION_DATA = new ClassValue<>() {
		@Override
		protected ClassReflectionData computeValue(Class<?> type) {
			return new ClassReflectionData();
		}
	};

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
		ClassReflectionData data = REFLECTION_DATA.get(clazz);
		MethodLookupKey key = new MethodLookupKey(methodName, arity, isStatic);
		Method cached = data.methodCache.get(key);
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
			data.methodCache.put(key, found);
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
		ClassReflectionData data = REFLECTION_DATA.get(clazz);
		List<Method> cached = data.candidateCache.get(methodName);
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
		data.candidateCache.put(methodName, unmod);
		return unmod;
	}

	/**
	 * 查找类中所有构造函数（缓存化）。
	 */
	public static List<Constructor<?>> findCandidateConstructors(Class<?> clazz) {
		if (clazz == null) return Collections.emptyList();
		ClassReflectionData data = REFLECTION_DATA.get(clazz);
		List<Constructor<?>> cached = data.candidateCtors;
		if (cached != null) return cached;

		List<Constructor<?>> list = new ArrayList<>();
		for (Constructor<?> c : clazz.getConstructors()) {
			trySetAccessible(c);
			list.add(c);
		}
		try {
			for (Constructor<?> c : clazz.getDeclaredConstructors()) {
				if (!list.contains(c)) {
					if (trySetAccessible(c)) {
						list.add(c);
					}
				}
			}
		} catch (Throwable ignored) {}
		List<Constructor<?>> unmod = Collections.unmodifiableList(list);
		data.candidateCtors = unmod;
		return unmod;
	}

	/**
	 * 基于入参动态类型和 JLS Pairwise Specificity 查找最佳匹配的构造函数。
	 */
	public static Constructor<?> findBestMatchingConstructor(Class<?> clazz, Object[] args) {
		List<Constructor<?>> candidates = findCandidateConstructors(clazz);
		if (candidates.isEmpty()) return null;

		Constructor<?>       bestCtor   = null;
		int                  minCost    = COST_INCOMPATIBLE;
		List<Constructor<?>> applicable = new ArrayList<>();

		for (Constructor<?> c : candidates) {
			if (c.getParameterCount() != args.length) continue;
			Class<?>[] params    = c.getParameterTypes();
			int        totalCost = 0;
			boolean    ok        = true;
			for (int i = 0; i < args.length; i++) {
				int cost = JSLinker.computeConversionCost(args[i], params[i]);
				if (cost >= COST_INCOMPATIBLE) {
					ok = false;
					break;
				}
				totalCost += cost;
			}
			if (ok) {
				applicable.add(c);
				if (totalCost < minCost) {
					minCost = totalCost;
					bestCtor = c;
				}
			}
		}

		if (!applicable.isEmpty()) {
			List<Constructor<?>> bestCandidates = new ArrayList<>();
			for (Constructor<?> c : applicable) {
				Class<?>[] params = c.getParameterTypes();
				int        cost   = 0;
				for (int i = 0; i < args.length; i++) cost += JSLinker.computeConversionCost(args[i], params[i]);
				if (cost == minCost) bestCandidates.add(c);
			}
			if (bestCandidates.size() == 1) return bestCandidates.get(0);
			Constructor<?> mostSpecific = bestCandidates.get(0);
			for (int i = 1; i < bestCandidates.size(); i++) {
				Constructor<?> curr = bestCandidates.get(i);
				if (isMoreSpecific(curr, mostSpecific)) {
					mostSpecific = curr;
				}
			}
			return mostSpecific;
		}

		return findConstructor(clazz, args.length);
	}

	private static boolean isMoreSpecific(Constructor<?> c1, Constructor<?> c2) {
		Class<?>[] p1 = c1.getParameterTypes();
		Class<?>[] p2 = c2.getParameterTypes();
		if (p1.length != p2.length) return false;
		boolean oneMoreSpecific = false;
		for (int i = 0; i < p1.length; i++) {
			Class<?> t1 = p1[i];
			Class<?> t2 = p2[i];
			if (t1 != t2) {
				if (t2.isAssignableFrom(t1)) {
					oneMoreSpecific = true;
				} else if (t1.isPrimitive() && !t2.isPrimitive()) {
					oneMoreSpecific = true;
				} else {
					return false;
				}
			}
		}
		return oneMoreSpecific;
	}

	/**
	 * 查找目标参数个数的构造函数。
	 */
	public static Constructor<?> findConstructor(Class<?> clazz, int arity) {
		if (clazz == null) return null;
		ClassReflectionData data = REFLECTION_DATA.get(clazz);
		Constructor<?> cached = data.ctorCache.get(arity);
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
			data.ctorCache.put(arity, found);
		}
		return found;
	}

	/**
	 * 查找 JavaBean 规范 getter 方法 (getProp / isProp / prop)。
	 */
	public static Method findGetterMethod(Class<?> clazz, String propName) {
		if (clazz == null || propName == null || propName.isEmpty()) return null;
		ClassReflectionData data = REFLECTION_DATA.get(clazz);
		Method cached = data.getterCache.get(propName);
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
			data.getterCache.put(propName, found);
		}
		return found;
	}

	/**
	 * 查找 JavaBean 规范 setter 方法 (setProp)。
	 */
	public static Method findSetterMethod(Class<?> clazz, String propName) {
		if (clazz == null || propName == null || propName.isEmpty()) return null;
		ClassReflectionData data = REFLECTION_DATA.get(clazz);
		Method cached = data.setterCache.get(propName);
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
			data.setterCache.put(propName, found);
		}
		return found;
	}
}
