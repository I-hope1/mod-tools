package hope.magic.js.runtime;

import hope.magic.runtime.*;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JSLinker {
	public enum InvocationStrategy {
		MAGIC_ACCESSOR, // 基于 MagicAccessorImpl (MAGICIMPL) 的原生字节码 JIT 直调 (1.95ns)
		SPREADER        // 基于 MethodHandle.asSpreader 的数组自适应展开 (5.15ns)
	}

	public static volatile InvocationStrategy STRATEGY = InvocationStrategy.MAGIC_ACCESSOR;

	private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

	// ==================== BSM 引导方法 ====================

	public static CallSite bootstrapGetProp(
		MethodHandles.Lookup caller,
		String name,
		MethodType type,
		String propName
	) {
		MutableCallSite site = new MutableCallSite(type);
		MethodHandle fallback = findStatic("getPropFallback", MethodType.methodType(Object.class, MutableCallSite.class, Object.class, String.class))
			.bindTo(site);
		fallback = MethodHandles.insertArguments(fallback, 1, propName);
		site.setTarget(fallback.asType(type));
		return site;
	}

	public static CallSite bootstrapGetPropInt(
		MethodHandles.Lookup caller,
		String name,
		MethodType type,
		String propName
	) {
		MutableCallSite site = new MutableCallSite(type);
		MethodHandle fallback = findStatic("getPropIntFallback", MethodType.methodType(int.class, MutableCallSite.class, Object.class, String.class))
			.bindTo(site);
		fallback = MethodHandles.insertArguments(fallback, 1, propName);
		site.setTarget(fallback.asType(type));
		return site;
	}

	public static CallSite bootstrapGetPropDouble(
		MethodHandles.Lookup caller,
		String name,
		MethodType type,
		String propName
	) {
		MutableCallSite site = new MutableCallSite(type);
		MethodHandle fallback = findStatic("getPropDoubleFallback", MethodType.methodType(double.class, MutableCallSite.class, Object.class, String.class))
			.bindTo(site);
		fallback = MethodHandles.insertArguments(fallback, 1, propName);
		site.setTarget(fallback.asType(type));
		return site;
	}

	public static CallSite bootstrapGetPropLong(
		MethodHandles.Lookup caller,
		String name,
		MethodType type,
		String propName
	) {
		MutableCallSite site = new MutableCallSite(type);
		MethodHandle fallback = findStatic("getPropLongFallback", MethodType.methodType(long.class, MutableCallSite.class, Object.class, String.class))
			.bindTo(site);
		fallback = MethodHandles.insertArguments(fallback, 1, propName);
		site.setTarget(fallback.asType(type));
		return site;
	}

	public static CallSite bootstrapSetProp(
		MethodHandles.Lookup caller,
		String name,
		MethodType type,
		String propName
	) {
		MutableCallSite site = new MutableCallSite(type);
		MethodHandle fallback = findStatic("setPropFallback", MethodType.methodType(void.class, MutableCallSite.class, Object.class, Object.class, String.class))
			.bindTo(site);
		fallback = MethodHandles.insertArguments(fallback, 2, propName);
		site.setTarget(fallback.asType(type));
		return site;
	}

	public static CallSite bootstrapInvoke(
		MethodHandles.Lookup caller,
		String name,
		MethodType type,
		String methodName
	) {
		MutableCallSite site = new MutableCallSite(type);
		MethodHandle fallback = findStatic("invokeFallback", MethodType.methodType(Object.class, MutableCallSite.class, Object.class, Object[].class, String.class))
			.bindTo(site);
		fallback = MethodHandles.insertArguments(fallback, 2, methodName);
		// type is (target, args...) -> Object
		MethodHandle collector = fallback.asCollector(1, Object[].class, type.parameterCount() - 1);
		site.setTarget(collector.asType(type));
		return site;
	}

	public static CallSite bootstrapNew(
		MethodHandles.Lookup caller,
		String name,
		MethodType type
	) {
		MutableCallSite site = new MutableCallSite(type);
		MethodHandle fallback = findStatic("newFallback", MethodType.methodType(Object.class, MutableCallSite.class, Object.class, Object[].class))
			.bindTo(site);
		MethodHandle collector = fallback.asCollector(1, Object[].class, type.parameterCount() - 1);
		site.setTarget(collector.asType(type));
		return site;
	}

	public static CallSite bootstrapBinaryOp(
		MethodHandles.Lookup caller,
		String name,
		MethodType type,
		String op
	) {
		MethodHandle mh = switch (op) {
			case "+" -> findStatic(JSOps.class, "add", MethodType.methodType(Object.class, Object.class, Object.class));
			case "-" -> findStatic(JSOps.class, "sub", MethodType.methodType(Object.class, Object.class, Object.class));
			case "*" -> findStatic(JSOps.class, "mul", MethodType.methodType(Object.class, Object.class, Object.class));
			case "/" -> findStatic(JSOps.class, "div", MethodType.methodType(Object.class, Object.class, Object.class));
			case "%" -> findStatic(JSOps.class, "mod", MethodType.methodType(Object.class, Object.class, Object.class));
			case "==" -> findStatic(JSOps.class, "eq", MethodType.methodType(Object.class, Object.class, Object.class));
			case "===" ->
			 findStatic(JSOps.class, "strictEq", MethodType.methodType(Object.class, Object.class, Object.class));
			case "!=" -> findStatic(JSOps.class, "ne", MethodType.methodType(Object.class, Object.class, Object.class));
			case "!==" ->
			 findStatic(JSOps.class, "strictNe", MethodType.methodType(Object.class, Object.class, Object.class));
			case "<" -> findStatic(JSOps.class, "lt", MethodType.methodType(Object.class, Object.class, Object.class));
			case "<=" -> findStatic(JSOps.class, "lte", MethodType.methodType(Object.class, Object.class, Object.class));
			case ">" -> findStatic(JSOps.class, "gt", MethodType.methodType(Object.class, Object.class, Object.class));
			case ">=" -> findStatic(JSOps.class, "gte", MethodType.methodType(Object.class, Object.class, Object.class));
			case "&&" -> findStatic(JSOps.class, "and", MethodType.methodType(Object.class, Object.class, Object.class));
			case "||" -> findStatic(JSOps.class, "or", MethodType.methodType(Object.class, Object.class, Object.class));
			default -> throw new IllegalArgumentException("Unknown binary operator: " + op);
		};
		return new ConstantCallSite(mh.asType(type));
	}

	public static CallSite bootstrapGetIndex(
		MethodHandles.Lookup caller,
		String name,
		MethodType type
	) {
		MethodHandle mh = findStatic("getIndex", MethodType.methodType(Object.class, Object.class, Object.class));
		return new ConstantCallSite(mh.asType(type));
	}

	public static CallSite bootstrapSetIndex(
		MethodHandles.Lookup caller,
		String name,
		MethodType type
	) {
		MethodHandle mh = findStatic("setIndex", MethodType.methodType(void.class, Object.class, Object.class, Object.class));
		return new ConstantCallSite(mh.asType(type));
	}

	// ==================== Fallback 与 Inline Cache 实现 ====================

	public static Object getPropFallback(MutableCallSite site, Object target, String propName) throws Throwable {
		if (target == null || target == JSUndefined.INSTANCE) {
			return JSUndefined.INSTANCE;
		}

		if (target instanceof JSObject jsObj) {
			int offset = jsObj.shape.getOffset(propName);
			if (offset >= 0) {
				MethodHandle test = findStatic("isExactShape", MethodType.methodType(boolean.class, JSShape.class, Object.class))
					.bindTo(jsObj.shape);
				MethodHandle directSlotGetter;
				if (STRATEGY == InvocationStrategy.MAGIC_ACCESSOR && offset < JSObject.IN_OBJECT_SLOTS) {
					directSlotGetter = MagicJIT.createExactFieldGetterStub(JSObject.class, "slot" + offset);
				} else {
					directSlotGetter = MethodHandles.insertArguments(
						findStatic("getJSObjSlot", MethodType.methodType(Object.class, int.class, Object.class)),
						0,
						offset
					);
				}
				MethodHandle currentFallback = site.getTarget();
				MethodHandle guard = MethodHandles.guardWithTest(test, directSlotGetter.asType(site.type()), currentFallback);
				site.setTarget(guard);
				return jsObj.getSlot(offset);
			}
			return jsObj.get(propName);
		}

		if (target instanceof Map) {
			Object v = ((Map<?, ?>) target).get(propName);
			return v == null ? JSUndefined.INSTANCE : v;
		}

		Class<?> targetClass = target.getClass();

		// 1. 尝试匹配 Java 字段 (私有/公有字段通过 MAGICIMPL 字节码直读或 Unsafe 偏移直读)
		if (STRATEGY == InvocationStrategy.MAGIC_ACCESSOR) {
			try {
				MethodHandle exactGetter = MagicJIT.getFieldGetterStub(targetClass, propName);
				if (exactGetter != null) {
					MethodHandle test = findStatic("isExactClass", MethodType.methodType(boolean.class, Class.class, Object.class))
						.bindTo(targetClass);
					MethodHandle currentFallback = site.getTarget();
					MethodHandle guard = MethodHandles.guardWithTest(test, exactGetter.asType(site.type()), currentFallback);
					site.setTarget(guard);

					return exactGetter.invokeExact(target);
				}
			} catch (Throwable ignored) {
			}
		}

		try {
			Field field = getDeclaredFieldRecursive(targetClass, propName);
			long offset = LinkerHelper.getFieldOffset(targetClass, propName);
			MethodHandle directGetter = buildDirectFieldGetter(targetClass, field, offset);

			// 构造单态内联缓存 Monomorphic Inline Cache (MIC)
			MethodHandle test = findStatic("isExactClass", MethodType.methodType(boolean.class, Class.class, Object.class))
				.bindTo(targetClass);
			MethodHandle currentFallback = site.getTarget();
			MethodHandle guard = MethodHandles.guardWithTest(test, directGetter.asType(site.type()), currentFallback);
			site.setTarget(guard);

			return directGetter.invoke(target);
		} catch (Throwable ignored) {
		}

		// 2. 尝试匹配 getter 方法 (getFoo / isFoo)
		String capName = Character.toUpperCase(propName.charAt(0)) + (propName.length() > 1 ? propName.substring(1) : "");
		String[] getterCandidates = new String[]{"get" + capName, "is" + capName, propName};
		for (String candidate : getterCandidates) {
			try {
				Method method = targetClass.getMethod(candidate);
				method.setAccessible(true);
				MethodHandle mh = Magic.lookup.unreflect(method);
				return mh.invoke(target);
			} catch (Throwable ignored) {
			}
		}

		// 3. 尝试匹配方法名并返回绑定的 JS 方法函数
		try {
			Method[] methods = targetClass.getMethods();
			for (Method m : methods) {
				if (m.getName().equals(propName)) {
					return (JSFunction) (cx, thisObj, args) -> invokeJavaMethod(target, propName, args);
				}
			}
		} catch (Throwable ignored) {
		}

		return JSUndefined.INSTANCE;
	}

	public static void setPropFallback(MutableCallSite site, Object target, Object value, String propName) throws Throwable {
		if (target == null || target == JSUndefined.INSTANCE) return;

		if (target instanceof JSObject jsObj) {
			int offset = jsObj.shape.getOffset(propName);
			if (offset >= 0) {
				MethodHandle test = findStatic("isExactShape", MethodType.methodType(boolean.class, JSShape.class, Object.class))
					.bindTo(jsObj.shape);
				test = MethodHandles.dropArguments(test, 1, Object.class);
				MethodHandle directSlotSetter;
				if (STRATEGY == InvocationStrategy.MAGIC_ACCESSOR && offset < JSObject.IN_OBJECT_SLOTS) {
					directSlotSetter = MagicJIT.createExactFieldSetterStub(JSObject.class, "slot" + offset);
				} else {
					directSlotSetter = MethodHandles.insertArguments(
						findStatic("setJSObjSlot", MethodType.methodType(void.class, int.class, Object.class, Object.class)),
						0,
						offset
					);
				}
				MethodHandle currentFallback = site.getTarget();
				MethodHandle guard = MethodHandles.guardWithTest(test, directSlotSetter.asType(site.type()), currentFallback);
				site.setTarget(guard);
				jsObj.setSlot(offset, value);
				return;
			}
			jsObj.put(propName, value);
			return;
		}

		if (target instanceof Map) {
			((Map<Object, Object>) target).put(propName, value);
			return;
		}

		Class<?> targetClass = target.getClass();

		// 1. 尝试通过 MAGICIMPL 直写字段或 Unsafe 偏移直写
		if (STRATEGY == InvocationStrategy.MAGIC_ACCESSOR) {
			try {
				MethodHandle exactSetter = MagicJIT.getFieldSetterStub(targetClass, propName);
				if (exactSetter != null) {
					MethodHandle test = findStatic("isExactClass", MethodType.methodType(boolean.class, Class.class, Object.class))
						.bindTo(targetClass);
					test = MethodHandles.dropArguments(test, 1, Object.class);
					MethodHandle currentFallback = site.getTarget();
					MethodHandle guard = MethodHandles.guardWithTest(test, exactSetter.asType(site.type()), currentFallback);
					site.setTarget(guard);

					exactSetter.invokeExact(target, value);
					return;
				}
			} catch (Throwable ignored) {
			}
		}

		try {
			Field field = getDeclaredFieldRecursive(targetClass, propName);
			long offset = LinkerHelper.getFieldOffset(targetClass, propName);
			MethodHandle directSetter = buildDirectFieldSetter(targetClass, field, offset);

			MethodHandle test = findStatic("isExactClass", MethodType.methodType(boolean.class, Class.class, Object.class))
				.bindTo(targetClass);
			test = MethodHandles.dropArguments(test, 1, Object.class);
			MethodHandle currentFallback = site.getTarget();
			MethodHandle guard = MethodHandles.guardWithTest(test, directSetter.asType(site.type()), currentFallback);
			site.setTarget(guard);

			directSetter.invoke(target, value);
			return;
		} catch (Throwable ignored) {
		}

		// 2. 尝试匹配 setter 方法 (setFoo)
		String capName = Character.toUpperCase(propName.charAt(0)) + (propName.length() > 1 ? propName.substring(1) : "");
		for (Method m : targetClass.getMethods()) {
			if (m.getName().equals("set" + capName) && m.getParameterCount() == 1) {
				m.setAccessible(true);
				Object casted = castValue(value, m.getParameterTypes()[0]);
				m.invoke(target, casted);
				return;
			}
		}
	}

	public static Object invokeFallback(MutableCallSite site, Object target, Object[] args, String methodName) throws Throwable {
		if (target == null || target == JSUndefined.INSTANCE) {
			throw new NullPointerException("Cannot invoke method '" + methodName + "' on null/undefined");
		}

		if (target instanceof JSFunction) {
			return ((JSFunction) target).call(null, target, args);
		}

		Class<?> clazz = (target instanceof Class<?>) ? (Class<?>) target : target.getClass();
		boolean isStatic = (target instanceof Class<?>);

		// 查找匹配的方法
		Method targetMethod = null;
		for (Method m : clazz.getDeclaredMethods()) {
			if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
				targetMethod = m;
				break;
			}
		}
		if (targetMethod == null) {
			for (Method m : clazz.getMethods()) {
				if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
					targetMethod = m;
					break;
				}
			}
		}

		if (targetMethod != null) {
			targetMethod.setAccessible(true);

			if (STRATEGY == InvocationStrategy.MAGIC_ACCESSOR) {
				try {
					MethodHandle exactMh = MagicJIT.createExactMethodStub(clazz, targetMethod);
					if (exactMh != null) {
						MethodHandle test = findStatic("isExactClass", MethodType.methodType(boolean.class, Class.class, Object.class))
							.bindTo(clazz);
						if (site.type().parameterCount() > 1) {
							test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
						}
						MethodHandle currentFallback = site.getTarget();
						MethodHandle genericMh = exactMh.asType(site.type());
						MethodHandle guard = MethodHandles.guardWithTest(test, genericMh, currentFallback);
						site.setTarget(guard);

						MethodHandle spreader = genericMh.asSpreader(Object[].class, args.length);
						return spreader.invokeExact(target, args);
					}
				} catch (Throwable ignored) {
				}
			}

			try {
				MethodHandle mh = Magic.lookup.unreflect(targetMethod);
				MethodHandle adapted = isStatic ? MethodHandles.dropArguments(mh, 0, Object.class) : mh;

				Class<?>[] paramTypes = targetMethod.getParameterTypes();
				int argOffset = 1; // index 0 is receiver or dropped target
				for (int i = 0; i < paramTypes.length; i++) {
					Class<?> pType = paramTypes[i];
					MethodHandle filter = getArgumentFilter(pType);
					if (filter != null) {
						adapted = MethodHandles.filterArguments(adapted, argOffset + i, filter);
					}
				}

				MethodHandle test = findStatic("isExactClass", MethodType.methodType(boolean.class, Class.class, Object.class))
					.bindTo(clazz);
				if (site.type().parameterCount() > 1) {
					test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
				}
				MethodHandle currentFallback = site.getTarget();
				MethodHandle genericMh = adapted.asType(site.type());
				MethodHandle guard = MethodHandles.guardWithTest(test, genericMh, currentFallback);
				site.setTarget(guard);

				// 首次调用使用 asSpreader 极速展开
				MethodHandle spreader = genericMh.asSpreader(Object[].class, args.length);
				return spreader.invokeExact(target, args);
			} catch (Throwable ignored) {
			}
		}

		return invokeJavaMethod(target, methodName, args);
	}

	public static MethodHandle getArgumentFilter(Class<?> targetType) {
		if (targetType == int.class) return findStatic("toInt", MethodType.methodType(int.class, Object.class));
		if (targetType == long.class) return findStatic("toLong", MethodType.methodType(long.class, Object.class));
		if (targetType == double.class) return findStatic("toDoubleVal", MethodType.methodType(double.class, Object.class));
		if (targetType == float.class) return findStatic("toFloat", MethodType.methodType(float.class, Object.class));
		if (targetType == boolean.class) return findStatic("toBoolean", MethodType.methodType(boolean.class, Object.class));
		if (targetType == String.class) return findStatic("toStringVal", MethodType.methodType(String.class, Object.class));
		return null;
	}

	public static int toInt(Object val) {
		if (val instanceof Number) return ((Number) val).intValue();
		return (int) JSOps.toDouble(val);
	}

	public static long toLong(Object val) {
		if (val instanceof Number) return ((Number) val).longValue();
		return (long) JSOps.toDouble(val);
	}

	public static double toDoubleVal(Object val) {
		return JSOps.toDouble(val);
	}

	public static float toFloat(Object val) {
		if (val instanceof Number) return ((Number) val).floatValue();
		return (float) JSOps.toDouble(val);
	}

	public static boolean toBoolean(Object val) {
		if (val instanceof Boolean) return (Boolean) val;
		return JSOps.isTruthy(val);
	}

	public static String toStringVal(Object val) {
		return String.valueOf(val);
	}

	private static final Map<String, MethodHandle> CTOR_SPREADER_CACHE = new ConcurrentHashMap<>();

	private static MethodHandle getConstructorSpreader(Class<?> clazz, int arity) {
		String key = clazz.getName() + "#" + arity;
		return CTOR_SPREADER_CACHE.computeIfAbsent(key, k -> {
			for (Constructor<?> c : clazz.getDeclaredConstructors()) {
				if (c.getParameterCount() == arity) {
					c.setAccessible(true);
					try {
						MethodHandle mh = Magic.lookup.unreflectConstructor(c);
						Class<?>[] paramTypes = c.getParameterTypes();
						MethodHandle adapted = mh;
						for (int i = 0; i < paramTypes.length; i++) {
							MethodHandle filter = getArgumentFilter(paramTypes[i]);
							if (filter != null) {
								adapted = MethodHandles.filterArguments(adapted, i, filter);
							}
						}
						MethodHandle genericMh = adapted.asType(MethodType.genericMethodType(arity));
						return genericMh.asSpreader(Object[].class, arity);
					} catch (Throwable e) {
						throw new RuntimeException(e);
					}
				}
			}
			return null;
		});
	}

	public static Object newFallback(MutableCallSite site, Object ctor, Object[] args) throws Throwable {
		if (ctor instanceof Class<?> clazz) {

			if (STRATEGY == InvocationStrategy.MAGIC_ACCESSOR) {
				try {
					MagicJIT.MagicConstructorInvoker ctorInvoker = MagicJIT.getConstructorInvoker(clazz, args.length);
					if (ctorInvoker != null) {
						return ctorInvoker.newInstance(args);
					}
				} catch (Throwable ignored) {
				}
			}

			MethodHandle ctorSpreader = getConstructorSpreader(clazz, args.length);
			if (ctorSpreader != null) {
				return ctorSpreader.invokeExact(args);
			}
			throw new NoSuchMethodException("No matching constructor for " + clazz.getName() + " with " + args.length + " args");
		}

		if (ctor instanceof JSFunction) {
			JSObject newObj = new JSObject();
			Object res = ((JSFunction) ctor).call(null, newObj, args);
			if (res instanceof JSObject) return res;
			return newObj;
		}

		throw new IllegalArgumentException("Cannot instantiate non-constructor: " + ctor);
	}

	public static Object getIndex(Object target, Object index) {
		if (target == null || target == JSUndefined.INSTANCE) return JSUndefined.INSTANCE;
		if (target instanceof JSObject jsObj) {
			return jsObj.get(String.valueOf(index));
		}
		if (target instanceof Object[]) {
			int idx = ((Number) index).intValue();
			return ((Object[]) target)[idx];
		}
		if (target instanceof Map) {
			return ((Map<?, ?>) target).get(index);
		}
		return JSUndefined.INSTANCE;
	}

	public static void setIndex(Object target, Object index, Object value) {
		if (target == null || target == JSUndefined.INSTANCE) return;
		if (target instanceof JSObject jsObj) {
			jsObj.put(String.valueOf(index), value);
			return;
		}
		if (target instanceof Object[]) {
			int idx = ((Number) index).intValue();
			((Object[]) target)[idx] = value;
			return;
		}
		if (target instanceof Map) {
			((Map<Object, Object>) target).put(index, value);
		}
	}

	// ==================== 辅助方法与直接 MethodHandle 构建 ====================

	public static boolean isExactClass(Class<?> expected, Object target) {
		return target != null && target.getClass() == expected;
	}

	public static boolean isExactShape(JSShape expected, Object target) {
		return target instanceof JSObject && ((JSObject) target).shape == expected;
	}

	public static Object getJSObjSlot(int slot, Object target) {
		return ((JSObject) target).getSlot(slot);
	}

	public static void setJSObjSlot(int slot, Object target, Object val) {
		((JSObject) target).setSlot(slot, val);
	}

	public static int getPropIntFallback(MutableCallSite site, Object target, String propName) throws Throwable {
		if (target == null || target == JSUndefined.INSTANCE) return 0;
		Class<?> targetClass = target.getClass();

		try {
			Field field = getDeclaredFieldRecursive(targetClass, propName);
			long offset = LinkerHelper.getFieldOffset(targetClass, propName);
			MethodHandle directGetter = MethodHandles.insertArguments(
				findStatic("getIntDirectPrim", MethodType.methodType(int.class, long.class, Object.class)),
				0,
				offset
			);
			MethodHandle test = findStatic("isExactClass", MethodType.methodType(boolean.class, Class.class, Object.class))
				.bindTo(targetClass);
			MethodHandle currentFallback = site.getTarget();
			MethodHandle guard = MethodHandles.guardWithTest(test, directGetter.asType(site.type()), currentFallback);
			site.setTarget(guard);
			return getIntDirectPrim(offset, target);
		} catch (Throwable ignored) {
		}

		Object val = getPropFallback(new MutableCallSite(MethodType.methodType(Object.class, Object.class)), target, propName);
		return JSOps.toInt(val);
	}

	public static double getPropDoubleFallback(MutableCallSite site, Object target, String propName) throws Throwable {
		if (target == null || target == JSUndefined.INSTANCE) return Double.NaN;
		Class<?> targetClass = target.getClass();

		try {
			Field field = getDeclaredFieldRecursive(targetClass, propName);
			long offset = LinkerHelper.getFieldOffset(targetClass, propName);
			MethodHandle directGetter = MethodHandles.insertArguments(
				findStatic("getDoubleDirectPrim", MethodType.methodType(double.class, long.class, Object.class)),
				0,
				offset
			);
			MethodHandle test = findStatic("isExactClass", MethodType.methodType(boolean.class, Class.class, Object.class))
				.bindTo(targetClass);
			MethodHandle currentFallback = site.getTarget();
			MethodHandle guard = MethodHandles.guardWithTest(test, directGetter.asType(site.type()), currentFallback);
			site.setTarget(guard);
			return getDoubleDirectPrim(offset, target);
		} catch (Throwable ignored) {
		}

		Object val = getPropFallback(new MutableCallSite(MethodType.methodType(Object.class, Object.class)), target, propName);
		return JSOps.toDouble(val);
	}

	public static long getPropLongFallback(MutableCallSite site, Object target, String propName) throws Throwable {
		if (target == null || target == JSUndefined.INSTANCE) return 0L;
		Class<?> targetClass = target.getClass();

		try {
			Field field = getDeclaredFieldRecursive(targetClass, propName);
			long offset = LinkerHelper.getFieldOffset(targetClass, propName);
			MethodHandle directGetter = MethodHandles.insertArguments(
				findStatic("getLongDirectPrim", MethodType.methodType(long.class, long.class, Object.class)),
				0,
				offset
			);
			MethodHandle test = findStatic("isExactClass", MethodType.methodType(boolean.class, Class.class, Object.class))
				.bindTo(targetClass);
			MethodHandle currentFallback = site.getTarget();
			MethodHandle guard = MethodHandles.guardWithTest(test, directGetter.asType(site.type()), currentFallback);
			site.setTarget(guard);
			return getLongDirectPrim(offset, target);
		} catch (Throwable ignored) {
		}

		Object val = getPropFallback(new MutableCallSite(MethodType.methodType(Object.class, Object.class)), target, propName);
		return JSOps.toLong(val);
	}

	public static int getIntDirectPrim(long offset, Object target) {
		return Magic.unsafe.getInt(target, offset);
	}

	public static double getDoubleDirectPrim(long offset, Object target) {
		return Magic.unsafe.getDouble(target, offset);
	}

	public static long getLongDirectPrim(long offset, Object target) {
		return Magic.unsafe.getLong(target, offset);
	}

	private static final Map<String, MethodHandle> METHOD_SPREADER_CACHE = new ConcurrentHashMap<>();

	private static MethodHandle getMethodSpreader(Class<?> clazz, String methodName, int arity, boolean isStatic) {
		String key = clazz.getName() + "#" + methodName + "#" + arity + "#" + isStatic;
		return METHOD_SPREADER_CACHE.computeIfAbsent(key, k -> {
			Method targetMethod = null;
			for (Method m : clazz.getDeclaredMethods()) {
				if (m.getName().equals(methodName) && m.getParameterCount() == arity) {
					targetMethod = m;
					break;
				}
			}
			if (targetMethod == null) {
				for (Method m : clazz.getMethods()) {
					if (m.getName().equals(methodName) && m.getParameterCount() == arity) {
						targetMethod = m;
						break;
					}
				}
			}
			if (targetMethod == null) return null;
			targetMethod.setAccessible(true);
			try {
				MethodHandle mh = Magic.lookup.unreflect(targetMethod);
				MethodHandle adapted = isStatic ? MethodHandles.dropArguments(mh, 0, Object.class) : mh;
				Class<?>[] paramTypes = targetMethod.getParameterTypes();
				for (int i = 0; i < paramTypes.length; i++) {
					MethodHandle filter = getArgumentFilter(paramTypes[i]);
					if (filter != null) {
						adapted = MethodHandles.filterArguments(adapted, 1 + i, filter);
					}
				}
				MethodType genericType = MethodType.genericMethodType(1 + arity);
				MethodHandle genericMh = adapted.asType(genericType);
				return genericMh.asSpreader(Object[].class, arity);
			} catch (Throwable e) {
				throw new RuntimeException(e);
			}
		});
	}

	private static Object invokeJavaMethod(Object target, String methodName, Object[] args) throws Throwable {
		Class<?> clazz = (target instanceof Class<?>) ? (Class<?>) target : target.getClass();
		boolean isStatic = (target instanceof Class<?>);

		if (STRATEGY == InvocationStrategy.MAGIC_ACCESSOR) {
			try {
				MagicJIT.MagicInvoker invoker = MagicJIT.getMethodInvoker(clazz, methodName, args.length, isStatic);
				if (invoker != null) {
					return invoker.invoke(target, args);
				}
			} catch (Throwable ignored) {
			}
		}

		MethodHandle spreader = getMethodSpreader(clazz, methodName, args.length, isStatic);
		if (spreader != null) {
			return spreader.invokeExact(target, args);
		}

		throw new NoSuchMethodException("Method '" + methodName + "' with " + args.length + " args not found on " + clazz.getName());
	}

	public static Object castValue(Object val, Class<?> targetType) {
		if (val == null) return null;
		if (targetType.isInstance(val)) return val;

		if (targetType == int.class || targetType == Integer.class) return ((Number) val).intValue();
		if (targetType == long.class || targetType == Long.class) return ((Number) val).longValue();
		if (targetType == double.class || targetType == Double.class) return ((Number) val).doubleValue();
		if (targetType == float.class || targetType == Float.class) return ((Number) val).floatValue();
		if (targetType == boolean.class || targetType == Boolean.class) {
			if (val instanceof Boolean) return val;
			return JSOps.isTruthy(val);
		}
		if (targetType == String.class) return String.valueOf(val);
		return val;
	}

	private static MethodHandle buildDirectFieldGetter(Class<?> clazz, Field field, long offset) {
		Class<?> type = field.getType();
		MethodHandle mh;
		if (type == int.class) {
			mh = findStatic("getIntDirect", MethodType.methodType(Object.class, long.class, Object.class));
		} else if (type == double.class) {
			mh = findStatic("getDoubleDirect", MethodType.methodType(Object.class, long.class, Object.class));
		} else if (type == boolean.class) {
			mh = findStatic("getBooleanDirect", MethodType.methodType(Object.class, long.class, Object.class));
		} else {
			mh = findStatic("getObjectDirect", MethodType.methodType(Object.class, long.class, Object.class));
		}
		return MethodHandles.insertArguments(mh, 0, offset);
	}

	private static MethodHandle buildDirectFieldSetter(Class<?> clazz, Field field, long offset) {
		Class<?> type = field.getType();
		MethodHandle mh;
		if (type == int.class) {
			mh = findStatic("putIntDirect", MethodType.methodType(void.class, long.class, Object.class, Object.class));
		} else if (type == double.class) {
			mh = findStatic("putDoubleDirect", MethodType.methodType(void.class, long.class, Object.class, Object.class));
		} else if (type == boolean.class) {
			mh = findStatic("putBooleanDirect", MethodType.methodType(void.class, long.class, Object.class, Object.class));
		} else {
			mh = findStatic("putObjectDirect", MethodType.methodType(void.class, long.class, Object.class, Object.class));
		}
		return MethodHandles.insertArguments(mh, 0, offset);
	}

	public static Object getIntDirect(long offset, Object target) {
		return (double) Magic.unsafe.getInt(target, offset);
	}

	public static Object getDoubleDirect(long offset, Object target) {
		return Magic.unsafe.getDouble(target, offset);
	}

	public static Object getBooleanDirect(long offset, Object target) {
		return Magic.unsafe.getBoolean(target, offset);
	}

	public static Object getObjectDirect(long offset, Object target) {
		Object val = Magic.unsafe.getObject(target, offset);
		return val == null ? JSUndefined.INSTANCE : val;
	}

	public static void putIntDirect(long offset, Object target, Object val) {
		Magic.unsafe.putInt(target, offset, ((Number) val).intValue());
	}

	public static void putDoubleDirect(long offset, Object target, Object val) {
		Magic.unsafe.putDouble(target, offset, ((Number) val).doubleValue());
	}

	public static void putBooleanDirect(long offset, Object target, Object val) {
		Magic.unsafe.putBoolean(target, offset, (Boolean) val);
	}

	public static void putObjectDirect(long offset, Object target, Object val) {
		Magic.unsafe.putObject(target, offset, val);
	}

	private static Field getDeclaredFieldRecursive(Class<?> clazz, String name) throws NoSuchFieldException {
		Class<?> curr = clazz;
		while (curr != null && curr != Object.class) {
			try {
				Field f = curr.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException e) {
				curr = curr.getSuperclass();
			}
		}
		throw new NoSuchFieldException("Field " + name + " not found in " + (clazz == null ? null : clazz.getName()));
	}

	private static MethodHandle findStatic(String name, MethodType type) {
		return findStatic(JSLinker.class, name, type);
	}

	private static MethodHandle findStatic(Class<?> clazz, String name, MethodType type) {
		try {
			return LOOKUP.findStatic(clazz, name, type);
		} catch (Throwable e) {
			throw new RuntimeException("Failed to find static method " + name, e);
		}
	}
}
