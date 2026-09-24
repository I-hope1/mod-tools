package hope.magic.js.runtime;

import hope.magic.runtime.*;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

@SuppressWarnings({"unused", "unchecked", "rawtypes", "RedundantCast"})
public class JSJavaInterop {
	public static final MethodHandles.Lookup LOOKUP = Magic.lookup;

	public static final ThreadLocal<JSObject> CURRENT_SUPER_PROTO = new ThreadLocal<>();

	public static final MethodHandle MH_INVOKE_INTERFACE_1;
	public static final MethodHandle MH_NEW_ARRAY_0;
	public static final MethodHandle MH_NEW_ARRAY_1;
	public static final MethodHandle MH_NEW_ARRAY_N;

	static {
		try {
			MH_INVOKE_INTERFACE_1 = LOOKUP.findStatic(JSJavaInterop.class, "invokeInterfaceAdapter1", MethodType.methodType(Object.class, Object.class, Object.class));
			MH_NEW_ARRAY_0        = LOOKUP.findStatic(JSJavaInterop.class, "newArrayInstance0", MethodType.methodType(Object.class, Class.class));
			MH_NEW_ARRAY_1        = LOOKUP.findStatic(JSJavaInterop.class, "newArrayInstance1", MethodType.methodType(Object.class, Class.class, Object.class));
			MH_NEW_ARRAY_N        = LOOKUP.findStatic(JSJavaInterop.class, "newArrayInstanceN", MethodType.methodType(Object.class, Class.class, Object[].class));
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	public static int getPrimitiveTypeIndex(Class<?> c) {
		if (c == byte.class || c == Byte.class) return 0;
		if (c == short.class || c == Short.class) return 1;
		if (c == char.class || c == Character.class) return 2;
		if (c == int.class || c == Integer.class) return 3;
		if (c == long.class || c == Long.class) return 4;
		if (c == float.class || c == Float.class) return 5;
		if (c == double.class || c == Double.class) return 6;
		if (c == boolean.class || c == Boolean.class) return 7;
		return -1;
	}

	public static int getInheritanceDistance(Class<?> from, Class<?> to) {
		if (from == to) return 0;
		if (to.isArray() && from.isArray()) {
			return getInheritanceDistance(from.getComponentType(), to.getComponentType());
		}
		if (to.isInterface()) {
			int minDistance = MethodResolver.COST_INCOMPATIBLE;
			for (Class<?> iface : from.getInterfaces()) {
				if (iface == to) return 1;
				if (to.isAssignableFrom(iface)) {
					int d = 1 + getInheritanceDistance(iface, to);
					if (d < minDistance) minDistance = d;
				}
			}
			Class<?> superclass = from.getSuperclass();
			if (superclass != null && to.isAssignableFrom(superclass)) {
				int d = 1 + getInheritanceDistance(superclass, to);
				if (d < minDistance) minDistance = d;
			}
			return minDistance != MethodResolver.COST_INCOMPATIBLE ? minDistance : 10;
		}
		int      distance = 0;
		Class<?> curr     = from;
		while (curr != null && curr != to) {
			curr = curr.getSuperclass();
			distance++;
		}
		return curr == to ? distance : MethodResolver.COST_INCOMPATIBLE;
	}

	public static int getHierarchyDepth(Class<?> clazz) {
		int      depth = 0;
		Class<?> curr  = clazz;
		while (curr != null) {
			depth++;
			curr = curr.getSuperclass();
		}
		return depth;
	}

	public static int computeConversionCost(Object arg, Class<?> targetType) {
		if (arg == null) {
			if (targetType.isPrimitive()) return MethodResolver.COST_INCOMPATIBLE;
			if (targetType == Object.class) return 100;
			return Math.max(1, 100 - getHierarchyDepth(targetType));
		}

		Class<?> fromType = arg.getClass();
		if (fromType == targetType) return 0;

		if (targetType.isInterface()) {
			if (arg instanceof JSFunction) {
				if (JSOps.getSingleAbstractMethod(targetType) != null) return 2;
				if (targetType == JSFunction.class) return 0;
			}
			if (arg instanceof JSObject) {
				if (targetType == JSObject.class) return 0;
				return 5;
			}
		}

		if (targetType.isAssignableFrom(fromType)) {
			if (targetType == Object.class) return 50;
			return getInheritanceDistance(fromType, targetType);
		}

		int fromPrim = getPrimitiveTypeIndex(fromType);
		int toPrim   = getPrimitiveTypeIndex(targetType);

		if (fromPrim >= 0 && toPrim >= 0) {
			if (fromPrim == 7 || toPrim == 7) {
				return (fromPrim == toPrim) ? 1 : MethodResolver.COST_INCOMPATIBLE;
			}
			if (fromPrim == toPrim) return 1;

			if (fromPrim == 2 && toPrim >= 3) {
				return 2 + (toPrim - 3);
			}
			if (fromPrim < toPrim && toPrim != 2) {
				return 2 + (toPrim - fromPrim);
			}

			if (arg instanceof Number num) {
				double d = num.doubleValue();
				if (Double.isFinite(d) && d == Math.floor(d)) {
					if (toPrim == 3 && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) return 3;
					if (toPrim == 4 && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) return 3;
					if (toPrim == 1 && d >= Short.MIN_VALUE && d <= Short.MAX_VALUE) return 4;
					if (toPrim == 0 && d >= Byte.MIN_VALUE && d <= Byte.MAX_VALUE) return 5;
				}
				return 20;
			}
		}

		if (targetType == String.class || targetType == CharSequence.class) {
			if (arg instanceof String) return 1;
			return 20;
		}
		if ((targetType == char.class || targetType == Character.class) && arg instanceof String s && s.length() == 1) {
			return 5;
		}

		return MethodResolver.COST_INCOMPATIBLE;
	}

	public static boolean isMoreSpecific(Method m1, Method m2) {
		Class<?>[] p1              = m1.getParameterTypes();
		Class<?>[] p2              = m2.getParameterTypes();
		boolean    oneMoreSpecific = false;
		for (int i = 0; i < p1.length; i++) {
			Class<?> t1 = p1[i];
			Class<?> t2 = p2[i];
			if (t1 != t2) {
				if (t2.isAssignableFrom(t1)) {
					oneMoreSpecific = true;
				} else if (t1.isInterface() && t2 == Object.class) {
					oneMoreSpecific = true;
				} else if (t1.isPrimitive() && !t2.isPrimitive()) {
					oneMoreSpecific = true;
				} else if (t1.isPrimitive()) {
					int idx1 = getPrimitiveTypeIndex(t1);
					int idx2 = getPrimitiveTypeIndex(t2);
					if (idx1 >= 0 && idx2 >= 0 && idx1 < idx2) {
						oneMoreSpecific = true;
					} else {
						return false;
					}
				} else {
					return false;
				}
			}
		}
		return oneMoreSpecific;
	}

	public static Object invokeIndex(Object target, Object index, Object[] args) throws Throwable {
		if (target == null || target == JSUndefined.INSTANCE) {
			throw new NullPointerException("Cannot invoke method on null/undefined");
		}
		Object fn = JSIndexOps.getIndex(target, index);
		if (fn instanceof JSFunction func) {
			return func.call(JSContext.CURRENT.get(), target, args);
		}
		throw JSContext.makeTypeError(JSArray.toPropertyKey(index) + " is not a function");
	}

	public static double invokeDoubleGeneric(Object target, Object[] args, String methodName) throws Throwable {
		return JSOps.toDouble(invokeGeneric(target, args, methodName));
	}

	public static Object invokeGeneric(Object target, Object[] args, String methodName) throws Throwable {
		if (target == null || target == JSUndefined.INSTANCE) {
			throw new NullPointerException("Cannot invoke method '" + methodName + "' on null/undefined");
		}

		if (target instanceof JSFunction func && "$invoke$".equals(methodName)) {
			return func.call(JSContext.current(), JSUndefined.INSTANCE, args);
		}

		if (target instanceof Class<?> clazz && clazz.isInterface() && "$invoke$".equals(methodName)) {
			if (args.length == 1) {
				return invokeInterfaceAdapter1(clazz, args[0]);
			}
			throw new NoSuchMethodException("Interface " + clazz.getName() + " cannot be invoked with " + args.length + " args");
		}

		if (target instanceof JSFunction func && "call".equals(methodName)) {
			Object   thisArg = args.length > 0 && args[0] != null ? args[0] : JSUndefined.INSTANCE;
			Object[] rest    = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new Object[0];
			return func.call(JSContext.current(), thisArg, rest);
		}

		if (methodName.startsWith("__magic_super_")) {
			String realName = methodName.substring("__magic_super_".length());

			if (target instanceof JSBridgedObject) {
				Class<?> clazz        = target.getClass();
				Method   targetMethod = MethodResolver.findBestMatchingMethod(clazz, methodName, args);
				if (targetMethod != null) {
					return invokeMatchedMethod(target, targetMethod, args, clazz, methodName);
				}
			}

			JSObject jsObj = (target instanceof JSBridgedObject bridged)
			 ? bridged.getJSObject()
			 : (target instanceof JSObject obj ? obj : null);

			if (jsObj != null) {
				JSObject currentSuper = CURRENT_SUPER_PROTO.get();
				JSObject superProto;
				if (currentSuper != null) {
					superProto = currentSuper.getPrototype();
				} else {
					JSObject proto = jsObj.getPrototype();
					if (proto != null && proto.hasOwnProperty("constructor") && proto.getPrototype() != null) {
						superProto = proto.getPrototype();
					} else {
						superProto = proto;
					}
				}
				if (superProto != null) {
					Object member = superProto.get(realName);
					if (member instanceof JSFunction func) {
						JSObject prev = currentSuper;
						CURRENT_SUPER_PROTO.set(superProto);
						try {
							return func.call(null, target, args);
						} finally {
							CURRENT_SUPER_PROTO.set(prev);
						}
					}
				}
			}
		}

		if (target instanceof JSObject jsObj) {
			Object member = jsObj.get(methodName);
			if (member instanceof JSFunction func) {
				return func.call(null, jsObj, args);
			}
			if (member instanceof Class<?> clazz) {
				if (clazz.isInterface()) {
					if (args.length == 1) {
						return invokeInterfaceAdapter1(clazz, args[0]);
					}
					throw new NoSuchMethodException("Interface " + clazz.getName() + " cannot be invoked with " + args.length + " args");
				}
			}
		}

		if (target instanceof JSBridgedObject bridged && !methodName.startsWith("__magic_super_")) {
			JSObject jsObj = bridged.getJSObject();
			if (jsObj != null) {
				Object member = jsObj.get(methodName);
				if (member instanceof JSFunction func) {
					return func.call(null, target, args);
				}
			}
		}

		if (target instanceof CharSequence seq) {
			Object strRes = invokeStringMethod(seq.toString(), methodName, args);
			if (strRes != null || "search".equals(methodName) || "match".equals(methodName)) {
				return strRes;
			}
		}

		Class<?> clazz        = (target instanceof Class<?>) ? (Class<?>) target : target.getClass();
		Method   targetMethod = MethodResolver.findBestMatchingMethod(clazz, methodName, args);
		if (targetMethod != null) {
			return invokeMatchedMethod(target, targetMethod, args, clazz, methodName);
		}

		return invokeJavaMethod(target, methodName, args);
	}

	public static Object[] packVarArgs(Class<?>[] paramTypes, Object[] args) {
		int      paramCount      = paramTypes.length;
		Class<?> varargArrayType = paramTypes[paramCount - 1];
		Class<?> elemType        = varargArrayType.getComponentType();

		if (args.length == paramCount && args[paramCount - 1] != null) {
			Object lastArg = args[paramCount - 1];
			if (varargArrayType.isInstance(lastArg)) {
				Object[] casted = new Object[paramCount];
				for (int i = 0; i < paramCount - 1; i++) {
					casted[i] = JSOps.castValue(args[i], paramTypes[i]);
				}
				casted[paramCount - 1] = lastArg;
				return casted;
			}
		}

		Object[] packed = new Object[paramCount];
		for (int i = 0; i < paramCount - 1; i++) {
			packed[i] = (i < args.length) ? JSOps.castValue(args[i], paramTypes[i]) : null;
		}

		int    varargLen = Math.max(0, args.length - (paramCount - 1));
		Object varargArr = Array.newInstance(elemType, varargLen);
		for (int i = 0; i < varargLen; i++) {
			Object raw = args[paramCount - 1 + i];
			Array.set(varargArr, i, JSOps.castValue(raw, elemType));
		}
		packed[paramCount - 1] = varargArr;
		return packed;
	}

	public static Object invokeMatchedMethod(Object target, Method targetMethod, Object[] args, Class<?> clazz,
	                                          String methodName) throws Throwable {
		try {
			targetMethod.setAccessible(true);
		} catch (Throwable ignored) {
		}
		Class<?>[] paramTypes = targetMethod.getParameterTypes();
		boolean    isVoid     = (targetMethod.getReturnType() == void.class);

		if (targetMethod.isVarArgs()) {
			Object[] packedArgs = packVarArgs(paramTypes, args);
			Object   res        = targetMethod.invoke(target, packedArgs);
			return isVoid ? JSUndefined.INSTANCE : res;
		}

		int                   arity   = args.length;
		MagicJIT.MagicInvoker invoker = MagicJIT.getMethodInvoker(clazz, targetMethod);
		if (invoker != null) {
			Object res = switch (arity) {
				case 0 -> invoker.invoke0(target);
				case 1 -> invoker.invoke1(target, JSOps.castValue(args[0], paramTypes[0]));
				case 2 ->
				 invoker.invoke2(target, JSOps.castValue(args[0], paramTypes[0]), JSOps.castValue(args[1], paramTypes[1]));
				case 3 ->
				 invoker.invoke3(target, JSOps.castValue(args[0], paramTypes[0]), JSOps.castValue(args[1], paramTypes[1]), JSOps.castValue(args[2], paramTypes[2]));
				default -> {
					Object[] castedArgs = new Object[arity];
					for (int i = 0; i < arity; i++) {
						castedArgs[i] = JSOps.castValue(args[i], paramTypes[i]);
					}
					yield invoker.invoke(target, castedArgs);
				}
			};
			return isVoid ? JSUndefined.INSTANCE : res;
		}
		Object[] castedArgs = new Object[arity];
		for (int i = 0; i < arity; i++) {
			castedArgs[i] = JSOps.castValue(args[i], paramTypes[i]);
		}
		Object res = targetMethod.invoke(target, castedArgs);
		return isVoid ? JSUndefined.INSTANCE : res;
	}

	public static boolean hasComplexParameters(Method method) {
		for (Class<?> pType : method.getParameterTypes()) {
			if (pType.isInterface() && pType != JSFunction.class && pType != JSObject.class) {
				return true;
			}
		}
		return false;
	}

	private static final ClassValue<MethodHandle> INTERFACE_FILTER_CACHE = new ClassValue<>() {
		@Override
		protected MethodHandle computeValue(Class<?> type) {
			return MethodHandles.insertArguments(JSLinker.MH_TO_INTERFACE, 1, type);
		}
	};

	public static MethodHandle getArgumentFilter(Class<?> targetType) {
		if (targetType == int.class) return JSLinker.MH_TO_INT;
		if (targetType == long.class) return JSLinker.MH_TO_LONG;
		if (targetType == double.class) return JSLinker.MH_TO_DOUBLE;
		if (targetType == float.class) return JSLinker.MH_TO_FLOAT;
		if (targetType == short.class) return JSLinker.MH_TO_SHORT;
		if (targetType == byte.class) return JSLinker.MH_TO_BYTE;
		if (targetType == char.class) return JSLinker.MH_TO_CHAR;
		if (targetType == boolean.class) return JSLinker.MH_TO_BOOLEAN;
		if (targetType == String.class) return JSLinker.MH_TO_STRING;
		if (targetType.isInterface() && targetType != JSFunction.class && targetType != JSObject.class) {
			return INTERFACE_FILTER_CACHE.get(targetType);
		}
		return null;
	}

	public static final class MethodLookupKey {
		final String  methodName;
		final int     arity;
		final boolean isStatic;
		final int     hash;

		MethodLookupKey(String methodName, int arity, boolean isStatic) {
			this.methodName = methodName;
			this.arity = arity;
			this.isStatic = isStatic;
			int h = methodName.hashCode();
			h = 31 * h + arity;
			h = 31 * h + (isStatic ? 1 : 0);
			this.hash = h;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof MethodLookupKey that)) return false;
			return arity == that.arity && isStatic == that.isStatic && methodName.equals(that.methodName);
		}

		@Override
		public int hashCode() {
			return hash;
		}
	}

	public static final class ClassSpreaderData {
		final Map<Integer, MethodHandle>         ctorSpreaderCache      = new ConcurrentHashMap<>();
		final Map<Constructor<?>, MethodHandle>  exactCtorSpreaderCache = new ConcurrentHashMap<>();
		final Map<MethodLookupKey, MethodHandle> methodSpreaderCache    = new ConcurrentHashMap<>();
	}

	public static void invalidateClass(Class<?> clazz) {
		if (clazz == null) return;
		SPREADER_DATA.remove(clazz);
		INTERFACE_FILTER_CACHE.remove(clazz);
	}

	public static final ClassValue<ClassSpreaderData> SPREADER_DATA = new ClassValue<>() {
		@Override
		protected ClassSpreaderData computeValue(Class<?> type) {
			return new ClassSpreaderData();
		}
	};

	public static MethodHandle getConstructorSpreader(Class<?> clazz, Constructor<?> c) {
		if (c == null) return null;
		ClassSpreaderData data   = SPREADER_DATA.get(clazz);
		MethodHandle      cached = data.exactCtorSpreaderCache.get(c);
		if (cached != null) return cached;
		try {
			MethodHandle mh         = Magic.lookup.unreflectConstructor(c);
			Class<?>[]   paramTypes = c.getParameterTypes();
			int          arity      = paramTypes.length;
			MethodHandle adapted    = mh;
			for (int i = 0; i < paramTypes.length; i++) {
				MethodHandle filter = getArgumentFilter(paramTypes[i]);
				if (filter != null) {
					adapted = MethodHandles.filterArguments(adapted, i, filter);
				}
			}
			MethodHandle genericMh = adapted.asType(MethodType.genericMethodType(arity));
			MethodHandle spreader  = genericMh.asSpreader(Object[].class, arity);
			data.exactCtorSpreaderCache.put(c, spreader);
			return spreader;
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	public static MethodHandle getConstructorSpreader(Class<?> clazz, int arity) {
		Constructor<?> c = MethodResolver.findConstructor(clazz, arity);
		if (c == null) return null;
		return getConstructorSpreader(clazz, c);
	}

	public static JSObject getPrototypeFromConstructor(JSContext cx, Object constructor, String intrinsicDefaultProto) {
		if (constructor instanceof JSObject ctorObj) {
			Object proto = ctorObj.get("prototype");
			if (proto instanceof JSObject protoObj) {
				return protoObj;
			}
			JSContext realm = ctorObj.realm;
			if (realm == null) realm = cx;
			if (realm != null) {
				if ("%Array.prototype%".equals(intrinsicDefaultProto)) {
					Object arrayCtor = realm.get("Array");
					if (arrayCtor instanceof JSObject ac) {
						Object p = ac.get("prototype");
						if (p instanceof JSObject po) return po;
					}
					return JSContext.LazyArray.ARRAY_PROTOTYPE;
				} else if ("%Function.prototype%".equals(intrinsicDefaultProto)) {
					Object fnCtor = realm.get("Function");
					if (fnCtor instanceof JSObject fc) {
						Object p = fc.get("prototype");
						if (p instanceof JSObject po) return po;
					}
					return JSContext.LazyFunction.FUNCTION_PROTOTYPE;
				} else {
					Object objCtor = realm.get("Object");
					if (objCtor instanceof JSObject oc) {
						Object p = oc.get("prototype");
						if (p instanceof JSObject po) return po;
					}
					return JSContext.LazyObject.OBJECT_PROTOTYPE;
				}
			}
		}
		return "%Array.prototype%".equals(intrinsicDefaultProto)
		 ? JSContext.LazyArray.ARRAY_PROTOTYPE
		 : JSContext.LazyObject.OBJECT_PROTOTYPE;
	}

	public static Object newGeneric(Object ctor, Object[] args) throws Throwable {
		return newGeneric(ctor, args, ctor);
	}

	public static Object newGeneric(Object ctor, Object[] args, Object newTarget) throws Throwable {
		if (ctor instanceof Class<?> clazz) {
			if (clazz.isArray()) {
				int      arity         = args.length;
				Class<?> componentType = clazz.getComponentType();
				if (arity == 1) return newArrayInstance1(componentType, args[0]);
				if (arity == 0) return newArrayInstance0(componentType);
				return newArrayInstanceN(componentType, args);
			}
			if (clazz.isInterface()) {
				if (args.length == 1) {
					return invokeInterfaceAdapter1(clazz, args[0]);
				}
				throw new NoSuchMethodException("Interface " + clazz.getName() + " cannot be instantiated with " + args.length + " args");
			}
			Constructor<?> c = MethodResolver.findBestMatchingConstructor(clazz, args);
			if (c != null) {
				Class<?>[] paramTypes = c.getParameterTypes();
				Object[]   castedArgs = c.isVarArgs() ? packVarArgs(paramTypes, args) : new Object[args.length];
				if (!c.isVarArgs()) {
					for (int i = 0; i < args.length; i++) {
						castedArgs[i] = JSOps.castValue(args[i], paramTypes[i]);
					}
				}
				return c.newInstance(castedArgs);
			}
			throw new NoSuchMethodException("No matching constructor for " + clazz.getName() + " with " + args.length + " args");
		}

		if (ctor instanceof JSContext.JSBuiltinMethod bm) {
			throw JSContext.makeTypeError(bm.getMethodName() + " is not a constructor");
		}

		if (ctor == JSContext.LazySymbol.SYMBOL) {
			throw JSContext.makeTypeError("Symbol is not a constructor");
		}

		if (ctor == JSContext.LazyDate.DATE) {
			JSContext currentCx = JSContext.current();
			JSObject  proto     = getPrototypeFromConstructor(currentCx, newTarget, "%Date.prototype%");
			return ((JSFunction) ctor).call(currentCx, new JSContext.JSDate(0, proto != null ? proto : JSContext.LazyDate.DATE_PROTOTYPE), args);
		}

		if (ctor == JSContext.LazyArray.ARRAY || ctor instanceof JSContext.JSArrayConstructor) {
			JSContext currentCx = JSContext.current();
			JSObject  proto     = getPrototypeFromConstructor(currentCx, newTarget, "%Array.prototype%");
			Object    res       = ((JSFunction) ctor).call(currentCx, null, args);
			if (res instanceof JSObject jo) {
				jo.setPrototype(proto);
				return jo;
			}
			return res;
		}

		if (ctor instanceof JSFunction) {
			JSContext currentCx = JSContext.current();
			JSObject  proto     = getPrototypeFromConstructor(currentCx, newTarget, "%Object.prototype%");
			JSObject  newObj    = (proto != null) ? new JSObject(proto) : new JSObject();
			if (ctor instanceof JSObject ctorObj && ctorObj.realm != null) {
				newObj.realm = ctorObj.realm;
			} else if (newTarget instanceof JSObject ntObj && ntObj.realm != null) {
				newObj.realm = ntObj.realm;
			} else {
				newObj.realm = currentCx;
			}
			Object res = ((JSFunction) ctor).call(currentCx, newObj, args);
			if (res instanceof JSBridgedObject || res instanceof JSObject || (res != null && res != JSUndefined.INSTANCE && !(res instanceof Number || res instanceof Boolean || res instanceof String || res instanceof Character))) {
				if (res instanceof JSObject jo && jo.realm == null) {
					jo.realm = newObj.realm;
				}
				return res;
			}
			return newObj;
		}

		throw JSContext.makeTypeError(ctor + " is not a constructor");
	}

	public static boolean isConstructor(Object ctor) {
		if (ctor == null || ctor == JSUndefined.INSTANCE) return false;
		if (ctor instanceof Class<?>) return true;
		if (ctor instanceof JSContext.JSBuiltinMethod) return false;
		if (ctor == JSContext.LazySymbol.SYMBOL) return false;
		if (ctor instanceof JSContext.JSArrayConstructor) return true;
		if (ctor instanceof JSContext.JSBuiltinConstructor) return true;
		if (ctor instanceof JSFunction) {
			if (ctor instanceof JSObject jo) {
				if (jo.shape.getOffset("prototype") >= 0) {
					return true;
				}
				Object p = jo.get("prototype");
				return p != JSUndefined.INSTANCE && p != null;
			}
			return true;
		}
		return false;
	}

	public static Object newArrayInstance0(Class<?> componentType) {
		return Array.newInstance(componentType, 0);
	}

	public static Object newArrayInstance1(Class<?> componentType, Object lenOrInit) {
		if (lenOrInit instanceof Number num) {
			return Array.newInstance(componentType, num.intValue());
		}
		if (lenOrInit instanceof JSArray jsArr) {
			int    len = (int) jsArr.length();
			Object arr = Array.newInstance(componentType, len);
			for (int i = 0; i < len; i++) {
				Array.set(arr, i, JSOps.castValue(jsArr.getElement(i), componentType));
			}
			return arr;
		}
		if (lenOrInit instanceof List<?> list) {
			int    len = list.size();
			Object arr = Array.newInstance(componentType, len);
			for (int i = 0; i < len; i++) {
				Array.set(arr, i, JSOps.castValue(list.get(i), componentType));
			}
			return arr;
		}
		if (lenOrInit != null && lenOrInit.getClass().isArray()) {
			int    len = Array.getLength(lenOrInit);
			Object arr = Array.newInstance(componentType, len);
			for (int i = 0; i < len; i++) {
				Array.set(arr, i, JSOps.castValue(Array.get(lenOrInit, i), componentType));
			}
			return arr;
		}
		return Array.newInstance(componentType, JSOps.toInt(lenOrInit));
	}

	public static Object newArrayInstanceN(Class<?> componentType, Object[] args) {
		int    len = args != null ? args.length : 0;
		Object arr = Array.newInstance(componentType, len);
		for (int i = 0; i < len; i++) {
			Array.set(arr, i, JSOps.castValue(args[i], componentType));
		}
		return arr;
	}

	public static Object invokeInterfaceAdapter1(Object target, Object arg) {
		if (target instanceof Class<?> iface && iface.isInterface()) {
			return JSOps.castValue(arg, iface);
		}
		throw new IllegalArgumentException("Target is not an interface: " + target);
	}

	public static MethodHandle getMethodSpreader(Class<?> clazz, String methodName, int arity, boolean isStatic) {
		ClassSpreaderData data   = SPREADER_DATA.get(clazz);
		MethodLookupKey   key    = new MethodLookupKey(methodName, arity, isStatic);
		MethodHandle      cached = data.methodSpreaderCache.get(key);
		if (cached != null) return cached;

		Method targetMethod = MethodResolver.getAccessibleMethod(MethodResolver.findMethod(clazz, methodName, arity, isStatic));
		if (targetMethod == null) return null;
		try {
			targetMethod.setAccessible(true);
		} catch (Throwable ignored) {
		}
		try {
			MethodHandle mh         = Magic.lookup.unreflect(targetMethod);
			MethodHandle adapted    = isStatic ? MethodHandles.dropArguments(mh, 0, Object.class) : mh;
			Class<?>[]   paramTypes = targetMethod.getParameterTypes();
			for (int i = 0; i < paramTypes.length; i++) {
				MethodHandle filter = getArgumentFilter(paramTypes[i]);
				if (filter != null) {
					adapted = MethodHandles.filterArguments(adapted, 1 + i, filter);
				}
			}
			if (targetMethod.getReturnType() == void.class) {
				adapted = MethodHandles.filterReturnValue(adapted, MethodHandles.constant(Object.class, JSUndefined.INSTANCE));
			}
			MethodType   genericType = MethodType.genericMethodType(1 + arity);
			MethodHandle genericMh   = adapted.asType(genericType);
			MethodHandle spreader    = genericMh.asSpreader(Object[].class, arity);
			data.methodSpreaderCache.put(key, spreader);
			return spreader;
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	public static Object invokeStringMethod(String str, String methodName, Object[] args) throws Throwable {
		if ("match".equals(methodName)) {
			Object   regArg = args.length > 0 ? args[0] : "";
			JSRegExp reg    = regArg instanceof JSRegExp r ? r : new JSRegExp(JSOps.toStr(regArg), "");
			if (reg.isGlobal()) {
				Matcher m   = reg.getCompiledPattern().matcher(str);
				JSArray arr = new JSArray();
				while (m.find()) {
					arr.push(m.group(0));
				}
				return arr.length() > 0 ? arr : null;
			} else {
				return reg.exec(str);
			}
		}
		if ("search".equals(methodName)) {
			Object   regArg = args.length > 0 ? args[0] : "";
			JSRegExp reg    = regArg instanceof JSRegExp r ? r : new JSRegExp(JSOps.toStr(regArg), "");
			Matcher  m      = reg.getCompiledPattern().matcher(str);
			return m.find() ? (double) m.start() : -1.0;
		}
		if ("replace".equals(methodName)) {
			Object regArg = args.length > 0 ? args[0] : "";
			Object repArg = args.length > 1 ? args[1] : "";
			if (regArg instanceof JSRegExp reg) {
				return replaceWithRegExp(str, reg, repArg, false);
			} else {
				String searchStr = JSOps.toStr(regArg);
				int    idx       = str.indexOf(searchStr);
				if (idx < 0) return str;

				String substring = str.substring(idx + searchStr.length());
				if (repArg instanceof JSFunction func) {
					Object[] funcArgs = new Object[]{searchStr, (double) idx, str};
					String   replStr  = JSOps.toStr(func.call(null, null, funcArgs));
					return str.substring(0, idx) + replStr + substring;
				} else {
					String repStr = JSOps.toStr(repArg);
					if (repStr.contains("$")) {
						repStr = repStr.replace("$$", "\0")
						 .replace("$&", searchStr)
						 .replace("\0", "$");
					}
					return str.substring(0, idx) + repStr + substring;
				}
			}
		}
		if ("replaceAll".equals(methodName)) {
			Object regArg = args.length > 0 ? args[0] : "";
			Object repArg = args.length > 1 ? args[1] : "";
			if (regArg instanceof JSRegExp reg) {
				return replaceWithRegExp(str, reg, repArg, true);
			} else {
				String searchStr = JSOps.toStr(regArg);
				if (searchStr.isEmpty()) return str;
				String repStr = JSOps.toStr(repArg);
				if (repStr.contains("$")) {
					repStr = repStr.replace("$$", "\0")
					 .replace("$&", searchStr)
					 .replace("\0", "$");
				}
				return str.replace(searchStr, repStr);
			}
		}
		if ("split".equals(methodName) && args.length > 0 && args[0] instanceof JSRegExp reg) {
			String[] parts = reg.getCompiledPattern().split(str, args.length > 1 ? JSOps.toInt(args[1]) : 0);
			JSArray  arr   = new JSArray();
			for (String p : parts) arr.push(p);
			return arr;
		}
		return null;
	}

	public static String replaceWithRegExp(String str, JSRegExp reg, Object repArg, boolean forceAll) throws Throwable {
		Matcher m      = reg.getCompiledPattern().matcher(str);
		boolean global = forceAll || reg.isGlobal();
		if (repArg instanceof JSFunction func) {
			StringBuilder sb = new StringBuilder();
			while (m.find()) {
				int      groupCount = m.groupCount();
				Object[] funcArgs   = new Object[groupCount + 3];
				funcArgs[0] = m.group(0);
				for (int i = 1; i <= groupCount; i++) {
					funcArgs[i] = m.group(i);
				}
				funcArgs[groupCount + 1] = (double) m.start();
				funcArgs[groupCount + 2] = str;
				Object replRes = func.call(null, null, funcArgs);
				String replStr = JSOps.toStr(replRes);
				m.appendReplacement(sb, Matcher.quoteReplacement(replStr));
				if (!global) break;
			}
			m.appendTail(sb);
			return sb.toString();
		} else {
			String repStr = toJavaReplacement(JSOps.toStr(repArg));
			if (global) {
				return m.replaceAll(repStr);
			} else {
				return m.replaceFirst(repStr);
			}
		}
	}

	public static String toJavaReplacement(String jsRep) {
		if (jsRep == null || !jsRep.contains("$")) return jsRep != null ? jsRep.replace("\\", "\\\\") : "";
		StringBuilder sb = new StringBuilder(jsRep.length() * 2);
		for (int i = 0; i < jsRep.length(); i++) {
			char c = jsRep.charAt(i);
			if (c == '$') {
				if (i + 1 < jsRep.length()) {
					char next = jsRep.charAt(i + 1);
					if (next == '&') {
						sb.append("$0");
					} else if (next == '$') {
						sb.append("\\$");
						i++;
					} else if (Character.isDigit(next)) {
						if (next == '0') {
							sb.append("\\$0");
						} else {
							sb.append("$").append(next);
						}
						i++;
					} else {
						sb.append("\\$");
					}
				} else {
					sb.append("\\$");
				}
			} else if (c == '\\') {
				sb.append("\\\\");
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	public static Object invokeJavaMethod(Object target, String methodName, Object[] args) throws Throwable {
		if (args == null) {
			args = JSFunction.EMPTY_ARGS;
		}
		Class<?> clazz    = (target instanceof Class<?>) ? (Class<?>) target : target.getClass();
		boolean  isStatic = (target instanceof Class<?>);

		Method targetMethod = MethodResolver.findBestMatchingMethod(clazz, methodName, args);
		if (targetMethod != null) {
			return invokeMatchedMethod(target, targetMethod, args, clazz, methodName);
		}

		MethodHandle spreader = getMethodSpreader(clazz, methodName, args.length, isStatic);
		if (spreader != null) {
			return spreader.invoke(target, args);
		}

		throw new NoSuchMethodException("Method '" + methodName + "' with " + args.length + " args not found on " + clazz.getName());
	}
}
