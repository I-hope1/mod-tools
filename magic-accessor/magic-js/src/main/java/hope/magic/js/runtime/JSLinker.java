package hope.magic.js.runtime;

import hope.magic.js.module.*;
import hope.magic.runtime.*;
import sun.misc.Unsafe;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static hope.magic.js.runtime.SlotMH.*;

@SuppressWarnings({"unused", "unchecked", "rawtypes", "RedundantCast", "UnnecessaryUnboxing"})
public class JSLinker {
	private static final Unsafe               UNSAFE = Magic.unsafe;
	private static final MethodHandles.Lookup LOOKUP = Magic.lookup;

	public enum InvocationStrategy {
		MAGIC_ACCESSOR, // 基于 MagicAccessorImpl (MAGICIMPL) 的原生字节码 JIT 直调 (1.95ns)
		SPREADER,       // 基于 MethodHandle.asSpreader 的数组自适应展开 (5.15ns)
		HYBRID          // 混合自适应策略：简单基础参数方法极速轻量展开，复杂接口/SAM回调走平铺字节码 Stub
	}

	public static volatile InvocationStrategy STRATEGY = InvocationStrategy.HYBRID;


	//region 基础类型转换与快路径 MethodHandle 常量 (核心加载)
	public static final MethodHandle MH_TO_INT;
	public static final MethodHandle MH_TO_LONG;
	public static final MethodHandle MH_TO_DOUBLE;
	public static final MethodHandle MH_TO_FLOAT;
	public static final MethodHandle MH_TO_SHORT;
	public static final MethodHandle MH_TO_BYTE;
	public static final MethodHandle MH_TO_CHAR;
	public static final MethodHandle MH_TO_BOOLEAN;
	public static final MethodHandle MH_TO_STRING;
	public static final MethodHandle MH_TO_INTERFACE;
	public static final MethodHandle MH_IS_EXACT_CLASS;
	public static final MethodHandle MH_IS_EXACT_CLASS_AND_ARGS;
	public static final MethodHandle MH_IS_EXACT_SHAPE;
	public static final MethodHandle MH_IS_EXACT_SHAPE_AND_PROTO;
	public static final MethodHandle MH_IS_SAME_OBJECT;
	public static final MethodHandle MH_IS_SAME_OBJECT_AND_ARGS;
	public static final MethodHandle MH_INVOKE_INTERFACE_1        = JSJavaInterop.MH_INVOKE_INTERFACE_1;
	public static final MethodHandle MH_TRANSITION_SET_DOUBLE;
	public static final MethodHandle MH_TRANSITION_SET_OBJECT;
	public static final MethodHandle MH_TRANSITION_SET_OBJECT_DOUBLE;
	public static final MethodHandle MH_GET_ACCESSOR_PROP;
	public static final MethodHandle MH_GET_PROTO_ACCESSOR_PROP;
	public static final MethodHandle MH_SET_ACCESSOR_PROP;
	public static final MethodHandle MH_SET_NOOP_PROP;
	public static final MethodHandle MH_ARRAY_LENGTH_INT;
	public static final MethodHandle MH_ARRAY_LENGTH_DOUBLE;
	public static final MethodHandle MH_GET_INDEX_JS_ARRAY        = JSIndexOps.MH_GET_INDEX_JS_ARRAY;
	public static final MethodHandle MH_GET_INDEX_LIST            = JSIndexOps.MH_GET_INDEX_LIST;
	public static final MethodHandle MH_GET_INDEX_OBJECT_ARRAY    = JSIndexOps.MH_GET_INDEX_OBJECT_ARRAY;
	public static final MethodHandle MH_GET_INDEX_PRIMITIVE_ARRAY = JSIndexOps.MH_GET_INDEX_PRIMITIVE_ARRAY;
	public static final MethodHandle MH_GET_INDEX_INT_ARRAY       = JSIndexOps.MH_GET_INDEX_INT_ARRAY;
	public static final MethodHandle MH_GET_INDEX_DOUBLE_ARRAY    = JSIndexOps.MH_GET_INDEX_DOUBLE_ARRAY;
	public static final MethodHandle MH_GET_INDEX_LONG_ARRAY      = JSIndexOps.MH_GET_INDEX_LONG_ARRAY;
	public static final MethodHandle MH_GET_INDEX_MAP             = JSIndexOps.MH_GET_INDEX_MAP;

	public static final MethodHandle MH_SET_INDEX_JS_ARRAY        = JSIndexOps.MH_SET_INDEX_JS_ARRAY;
	public static final MethodHandle MH_SET_INDEX_LIST            = JSIndexOps.MH_SET_INDEX_LIST;
	public static final MethodHandle MH_SET_INDEX_OBJECT_ARRAY    = JSIndexOps.MH_SET_INDEX_OBJECT_ARRAY;
	public static final MethodHandle MH_SET_INDEX_INT_ARRAY       = JSIndexOps.MH_SET_INDEX_INT_ARRAY;
	public static final MethodHandle MH_SET_INDEX_DOUBLE_ARRAY    = JSIndexOps.MH_SET_INDEX_DOUBLE_ARRAY;
	public static final MethodHandle MH_SET_INDEX_LONG_ARRAY      = JSIndexOps.MH_SET_INDEX_LONG_ARRAY;
	public static final MethodHandle MH_SET_INDEX_PRIMITIVE_ARRAY = JSIndexOps.MH_SET_INDEX_PRIMITIVE_ARRAY;
	public static final MethodHandle MH_SET_INDEX_MAP             = JSIndexOps.MH_SET_INDEX_MAP;
	public static final MethodHandle MH_NEW_ARRAY_0               = JSJavaInterop.MH_NEW_ARRAY_0;
	public static final MethodHandle MH_NEW_ARRAY_1               = JSJavaInterop.MH_NEW_ARRAY_1;
	public static final MethodHandle MH_NEW_ARRAY_N               = JSJavaInterop.MH_NEW_ARRAY_N;
	public static final MethodHandle MH_CREATE_BOUND_INSTANCE_METHOD;
	public static final MethodHandle MH_JS_ARRAY_LENGTH_OBJ;
	public static final MethodHandle MH_JS_ARRAY_LENGTH_DOUBLE;
	public static final MethodHandle MH_JS_ARRAY_LENGTH_LONG;
	public static final MethodHandle MH_JS_ARRAY_LENGTH_INT;
	public static final MethodHandle MH_JS_ARRAY_FAST_PUSH0;
	public static final MethodHandle MH_JS_ARRAY_FAST_PUSH1;
	public static final MethodHandle MH_JS_ARRAY_FAST_PUSH2;
	public static final MethodHandle MH_JS_ARRAY_FAST_POP0;
	public static final MethodHandle MH_CALL_OWN_METHOD0;
	public static final MethodHandle MH_CALL_OWN_METHOD1;
	public static final MethodHandle MH_CALL_OWN_METHOD2;
	public static final MethodHandle MH_CALL_OWN_METHOD3;
	public static final MethodHandle MH_CALL_OWN_METHOD4;
	public static final MethodHandle MH_CALL_OWN_METHOD_N;

	static {
		try {
			MH_TO_INT = LOOKUP.findStatic(JSOps.class, "toInt", MethodType.methodType(int.class, Object.class));
			MH_TO_LONG = LOOKUP.findStatic(JSOps.class, "toLong", MethodType.methodType(long.class, Object.class));
			MH_TO_DOUBLE = LOOKUP.findStatic(JSOps.class, "toDouble", MethodType.methodType(double.class, Object.class));
			MH_TO_FLOAT = LOOKUP.findStatic(JSOps.class, "toFloat", MethodType.methodType(float.class, Object.class));
			MH_TO_SHORT = LOOKUP.findStatic(JSOps.class, "toShort", MethodType.methodType(short.class, Object.class));
			MH_TO_BYTE = LOOKUP.findStatic(JSOps.class, "toByte", MethodType.methodType(byte.class, Object.class));
			MH_TO_CHAR = LOOKUP.findStatic(JSOps.class, "toChar", MethodType.methodType(char.class, Object.class));
			MH_TO_BOOLEAN = LOOKUP.findStatic(JSOps.class, "toBoolean", MethodType.methodType(boolean.class, Object.class));
			MH_TO_STRING = LOOKUP.findStatic(JSOps.class, "toStr", MethodType.methodType(String.class, Object.class));
			MH_TO_INTERFACE = LOOKUP.findStatic(JSOps.class, "castValue", MethodType.methodType(Object.class, Object.class, Class.class));
			MH_IS_EXACT_CLASS = LOOKUP.findStatic(JSLinker.class, "isExactClass", MethodType.methodType(boolean.class, Class.class, Object.class));
			MH_IS_EXACT_CLASS_AND_ARGS = LOOKUP.findStatic(JSLinker.class, "isExactClassAndArgs", MethodType.methodType(boolean.class, Class.class, Class[].class, Object.class, Object[].class));
			MH_IS_EXACT_SHAPE = LOOKUP.findStatic(JSLinker.class, "isExactShape", MethodType.methodType(boolean.class, JSShape.class, Object.class));
			MH_IS_EXACT_SHAPE_AND_PROTO = LOOKUP.findStatic(JSLinker.class, "isExactShapeAndProto", MethodType.methodType(boolean.class, JSShape.class, JSObject.class, Object.class));
			MH_IS_SAME_OBJECT = LOOKUP.findStatic(JSLinker.class, "isSameObject", MethodType.methodType(boolean.class, Object.class, Object.class));
			MH_IS_SAME_OBJECT_AND_ARGS = LOOKUP.findStatic(JSLinker.class, "isSameObjectAndArgs", MethodType.methodType(boolean.class, Object.class, Class[].class, Object.class, Object[].class));
			MH_TRANSITION_SET_DOUBLE = LOOKUP.findStatic(JSLinker.class, "transitionSetDouble", MethodType.methodType(void.class, JSShape.class, int.class, Object.class, double.class));
			MH_TRANSITION_SET_OBJECT = LOOKUP.findStatic(JSLinker.class, "transitionSetObject", MethodType.methodType(void.class, JSShape.class, int.class, Object.class, Object.class));
			MH_TRANSITION_SET_OBJECT_DOUBLE = LOOKUP.findStatic(JSLinker.class, "transitionSetObjectDouble", MethodType.methodType(void.class, JSShape.class, int.class, Object.class, Object.class));
			MH_GET_ACCESSOR_PROP = LOOKUP.findStatic(JSLinker.class, "getAccessorProp", MethodType.methodType(Object.class, int.class, Object.class));
			MH_GET_PROTO_ACCESSOR_PROP = LOOKUP.findStatic(JSLinker.class, "getPrototypeAccessorProp", MethodType.methodType(Object.class, PropertyAccessor.class, Object.class));
			MH_SET_ACCESSOR_PROP = LOOKUP.findStatic(JSLinker.class, "setAccessorProp", MethodType.methodType(void.class, int.class, Object.class, Object.class));
			MH_SET_NOOP_PROP = LOOKUP.findStatic(JSLinker.class, "setNoopProp", MethodType.methodType(void.class, Object.class, Object.class));
			MH_ARRAY_LENGTH_INT = LOOKUP.findStatic(JSLinker.class, "getArrayLengthInt", MethodType.methodType(int.class, Object.class));
			MH_ARRAY_LENGTH_DOUBLE = LOOKUP.findStatic(JSLinker.class, "getArrayLengthDouble", MethodType.methodType(double.class, Object.class));
			MH_CREATE_BOUND_INSTANCE_METHOD = LOOKUP.findStatic(JSLinker.class, "createBoundInstanceMethod", MethodType.methodType(Object.class, Object.class, Class.class, String.class, int.class));
			MH_JS_ARRAY_LENGTH_OBJ = LOOKUP.findStatic(JSLinker.class, "getJSArrayLengthObj", MethodType.methodType(Object.class, Object.class));
			MH_JS_ARRAY_LENGTH_DOUBLE = LOOKUP.findStatic(JSLinker.class, "getJSArrayLengthDouble", MethodType.methodType(double.class, Object.class));
			MH_JS_ARRAY_LENGTH_LONG = LOOKUP.findStatic(JSLinker.class, "getJSArrayLengthLong", MethodType.methodType(long.class, Object.class));
			MH_JS_ARRAY_LENGTH_INT = LOOKUP.findStatic(JSLinker.class, "getJSArrayLengthInt", MethodType.methodType(int.class, Object.class));
			MH_JS_ARRAY_FAST_PUSH0 = LOOKUP.findStatic(JSLinker.class, "jsArrayFastPush0", MethodType.methodType(Object.class, Object.class));
			MH_JS_ARRAY_FAST_PUSH1 = LOOKUP.findStatic(JSLinker.class, "jsArrayFastPush1", MethodType.methodType(Object.class, Object.class, Object.class));
			MH_JS_ARRAY_FAST_PUSH2 = LOOKUP.findStatic(JSLinker.class, "jsArrayFastPush2", MethodType.methodType(Object.class, Object.class, Object.class, Object.class));
			MH_JS_ARRAY_FAST_POP0 = LOOKUP.findStatic(JSLinker.class, "jsArrayFastPop0", MethodType.methodType(Object.class, Object.class));
			MH_CALL_OWN_METHOD0 = LOOKUP.findStatic(JSLinker.class, "callOwnMethod0", MethodType.methodType(Object.class, int.class, Object.class));
			MH_CALL_OWN_METHOD1 = LOOKUP.findStatic(JSLinker.class, "callOwnMethod1", MethodType.methodType(Object.class, int.class, Object.class, Object.class));
			MH_CALL_OWN_METHOD2 = LOOKUP.findStatic(JSLinker.class, "callOwnMethod2", MethodType.methodType(Object.class, int.class, Object.class, Object.class, Object.class));
			MH_CALL_OWN_METHOD3 = LOOKUP.findStatic(JSLinker.class, "callOwnMethod3", MethodType.methodType(Object.class, int.class, Object.class, Object.class, Object.class, Object.class));
			MH_CALL_OWN_METHOD4 = LOOKUP.findStatic(JSLinker.class, "callOwnMethod4", MethodType.methodType(Object.class, int.class, Object.class, Object.class, Object.class, Object.class, Object.class));
			MH_CALL_OWN_METHOD_N = LOOKUP.findStatic(JSLinker.class, "callOwnMethodN", MethodType.methodType(Object.class, int.class, Object.class, Object[].class));
		} catch (Throwable e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	static MethodHandle findStaticMH(Class<?> clazz, String name, MethodType type) {
		try {
			return LOOKUP.findStatic(clazz, name, type);
		} catch (Throwable e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	static MethodHandle findVirtualMH(Class<?> clazz, String name, MethodType type) {
		try {
			return LOOKUP.findVirtual(clazz, name, type);
		} catch (Throwable e) {
			throw new ExceptionInInitializerError(e);
		}
	}
	//endregion

	//region PolySnapshot & Flat Polymorphic Jump-Table Guard (扁平多态 Switch 守卫)

	/** 多态 IC 快照：shape 数组（插入顺序）+ 每个 shape 对应的槽位 offset + 每个 shape 对应的类型 type + 目标属性 propId。 */
	public record PolySnapshot(JSShape[] shapes, int[] offsets, byte[] types, int propId) {
		public PolySnapshot(JSShape[] shapes, int[] offsets, byte[] types) {
			this(shapes, offsets, types, -1);
		}
	}

	public static MethodHandle buildFlatPolySwitchObject(PolySnapshot snap, MethodHandle fallback) {
		return JSPolyGuards.buildFlatPolySwitchObject(snap, fallback);
	}

	public static MethodHandle buildFlatPolySwitchDouble(PolySnapshot snap, MethodHandle fallback) {
		return JSPolyGuards.buildFlatPolySwitchDouble(snap, fallback);
	}

	public static MethodHandle buildFlatPolySwitchInt(PolySnapshot snap, MethodHandle fallback) {
		return JSPolyGuards.buildFlatPolySwitchInt(snap, fallback);
	}

	public static MethodHandle buildFlatPolySwitchLong(PolySnapshot snap, MethodHandle fallback) {
		return JSPolyGuards.buildFlatPolySwitchLong(snap, fallback);
	}

	public static MethodHandle buildFlatPolySwitchSetterObject(PolySnapshot snap, MethodHandle fallback) {
		return JSPolyGuards.buildFlatPolySwitchSetterObject(snap, fallback);
	}

	public static MethodHandle buildFlatPolySwitchSetterDouble(PolySnapshot snap, MethodHandle fallback) {
		return JSPolyGuards.buildFlatPolySwitchSetterDouble(snap, fallback);
	}

	public static int shapeIdSelector(int minId, int span, Object target) {
		return JSPolyGuards.shapeIdSelector(minId, span, target);
	}

	public static Object polyGetObject(JSShape[] shapes, int[] offsets, MethodHandle fallback, Object target)
	 throws Throwable {
		return JSPolyGuards.polyGetObject(shapes, offsets, fallback, target);
	}

	public static double polyGetDouble(JSShape[] shapes, int[] offsets, MethodHandle fallback, Object target)
	 throws Throwable {
		return JSPolyGuards.polyGetDouble(shapes, offsets, fallback, target);
	}

	public static int polyGetInt(JSShape[] shapes, int[] offsets, MethodHandle fallback, Object target) throws Throwable {
		return JSPolyGuards.polyGetInt(shapes, offsets, fallback, target);
	}

	public static long polyGetLong(JSShape[] shapes, int[] offsets, MethodHandle fallback, Object target)
	 throws Throwable {
		return JSPolyGuards.polyGetLong(shapes, offsets, fallback, target);
	}

	public static void polySetObject(JSShape[] shapes, int[] offsets, MethodHandle fallback, Object target, Object value)
	 throws Throwable {
		JSPolyGuards.polySetObject(shapes, offsets, fallback, target, value);
	}

	public static void polySetDouble(JSShape[] shapes, int[] offsets, MethodHandle fallback, Object target, double value)
	 throws Throwable {
		JSPolyGuards.polySetDouble(shapes, offsets, fallback, target, value);
	}

	public static boolean isMatchPropAt(int propId, int offset, Object target) {
		return JSPolyGuards.isMatchPropAt(propId, offset, target);
	}

	public static boolean isShapeN(JSShape[] shapes, Object target) {
		return JSPolyGuards.isShapeN(shapes, target);
	}

	static MethodHandle buildMultiShapeGuard(JSShape[] shapes, int propId, int commonOff) {
		return JSPolyGuards.buildMultiShapeGuard(shapes, propId, commonOff);
	}

	public static boolean isShapeNSetterDouble(JSShape[] shapes, Object target, double val) {
		return JSPolyGuards.isShapeNSetterDouble(shapes, target, val);
	}

	static MethodHandle buildMultiShapeGuardSetterDouble(JSShape[] shapes) {
		return JSPolyGuards.buildMultiShapeGuardSetterDouble(shapes);
	}

	public static boolean isShapeNSetterObject(JSShape[] shapes, Object target, Object val) {
		return JSPolyGuards.isShapeNSetterObject(shapes, target, val);
	}

	static MethodHandle buildMultiShapeGuardSetterObject(JSShape[] shapes) {
		return JSPolyGuards.buildMultiShapeGuardSetterObject(shapes);
	}

	static MethodHandle getAdaptiveFallback(ChainedCallSite site) {
		return JSPolyGuards.getAdaptiveFallback(site);
	}
	//endregion


	//region BSM 引导方法

	public static CallSite bootstrapGetProp(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type,
	 String propName
	) {
		String          sym  = SymbolTable.symbol(propName);
		ChainedCallSite site = new ChainedCallSite(type, null);
		site.setPropId(SymbolTable.id(sym));
		MethodHandle megamorphic = MethodHandles.insertArguments(PropMH.GET_MEGAMORPHIC, 2, sym).bindTo(site);
		site.setMegamorphicTarget(megamorphic);
		MethodHandle fallback = MethodHandles.insertArguments(PropMH.GET_FALLBACK, 2, sym).bindTo(site);
		MethodHandle fbTyped  = fallback.asType(type);
		site.setInitialFallback(fbTyped);
		site.setTarget(fbTyped);
		return site;
	}

	public static CallSite bootstrapGetPropInt(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type,
	 String propName
	) {
		String          sym  = SymbolTable.symbol(propName);
		ChainedCallSite site = new ChainedCallSite(type, null);
		site.setPropId(SymbolTable.id(sym));
		MethodHandle megamorphic = MethodHandles.insertArguments(PropMH.GET_INT_MEGAMORPHIC, 2, sym).bindTo(site);
		site.setMegamorphicTarget(megamorphic);
		MethodHandle fallback = MethodHandles.insertArguments(PropMH.GET_INT_FALLBACK, 2, sym).bindTo(site);
		MethodHandle fbTyped  = fallback.asType(type);
		site.setInitialFallback(fbTyped);
		site.setTarget(fbTyped);
		return site;
	}

	public static CallSite bootstrapGetPropDouble(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type,
	 String propName
	) {
		String          sym  = SymbolTable.symbol(propName);
		ChainedCallSite site = new ChainedCallSite(type, null);
		site.setPropId(SymbolTable.id(sym));
		MethodHandle megamorphic = MethodHandles.insertArguments(PropMH.GET_DOUBLE_MEGAMORPHIC, 2, sym).bindTo(site);
		site.setMegamorphicTarget(megamorphic);
		MethodHandle fallback = MethodHandles.insertArguments(PropMH.GET_DOUBLE_FALLBACK, 2, sym).bindTo(site);
		MethodHandle fbTyped  = fallback.asType(type);
		site.setInitialFallback(fbTyped);
		site.setTarget(fbTyped);
		return site;
	}

	public static CallSite bootstrapGetPropLong(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type,
	 String propName
	) {
		String          sym  = SymbolTable.symbol(propName);
		ChainedCallSite site = new ChainedCallSite(type, null);
		site.setPropId(SymbolTable.id(sym));
		MethodHandle megamorphic = MethodHandles.insertArguments(PropMH.GET_LONG_MEGAMORPHIC, 2, sym).bindTo(site);
		site.setMegamorphicTarget(megamorphic);
		MethodHandle fallback = MethodHandles.insertArguments(PropMH.GET_LONG_FALLBACK, 2, sym).bindTo(site);
		MethodHandle fbTyped  = fallback.asType(type);
		site.setInitialFallback(fbTyped);
		site.setTarget(fbTyped);
		return site;
	}

	public static CallSite bootstrapSetProp(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type,
	 String propName
	) {
		String          sym  = SymbolTable.symbol(propName);
		ChainedCallSite site = new ChainedCallSite(type, null);
		site.setPropId(SymbolTable.id(sym));
		MethodHandle megamorphic = MethodHandles.insertArguments(PropMH.SET_MEGAMORPHIC, 3, sym).bindTo(site);
		site.setMegamorphicTarget(megamorphic);
		MethodHandle fallback = MethodHandles.insertArguments(PropMH.SET_FALLBACK, 3, sym).bindTo(site);
		MethodHandle fbTyped  = fallback.asType(type);
		site.setInitialFallback(fbTyped);
		site.setTarget(fbTyped);
		return site;
	}

	public static CallSite bootstrapSetPropDouble(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type,
	 String propName
	) {
		String          sym  = SymbolTable.symbol(propName);
		ChainedCallSite site = new ChainedCallSite(type, null);
		site.setPropId(SymbolTable.id(sym));
		MethodHandle megamorphic = MethodHandles.insertArguments(PropMH.SET_DOUBLE_MEGAMORPHIC, 3, sym).bindTo(site);
		site.setMegamorphicTarget(megamorphic);
		MethodHandle fallback = MethodHandles.insertArguments(PropMH.SET_DOUBLE_FALLBACK, 3, sym).bindTo(site);
		MethodHandle fbTyped  = fallback.asType(type);
		site.setInitialFallback(fbTyped);
		site.setTarget(fbTyped);
		return site;
	}

	public static CallSite bootstrapInvoke(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type,
	 String methodName
	) {
		MethodHandle megamorphic = MethodHandles.insertArguments(InvokeMH.INVOKE_GENERIC, 2, methodName)
		 .asCollector(1, Object[].class, type.parameterCount() - 1);
		ChainedCallSite site = new ChainedCallSite(type, megamorphic);
		MethodHandle fallback = MethodHandles.insertArguments(InvokeMH.INVOKE_FALLBACK, 3, methodName)
		 .bindTo(site).asCollector(1, Object[].class, type.parameterCount() - 1);
		MethodHandle fbTyped = fallback.asType(type);
		site.setInitialFallback(fbTyped);
		site.setTarget(fbTyped);
		return site;
	}
	public static CallSite bootstrapInvokeDouble(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type,
	 String methodName
	) {
		MethodHandle megamorphic = MethodHandles.insertArguments(InvokeMH.INVOKE_DOUBLE_GENERIC, 2, methodName)
		 .asCollector(1, Object[].class, type.parameterCount() - 1);
		ChainedCallSite site = new ChainedCallSite(type, megamorphic);
		MethodHandle fallback = MethodHandles.insertArguments(InvokeMH.INVOKE_DOUBLE_FALLBACK, 3, methodName)
		 .bindTo(site).asCollector(1, Object[].class, type.parameterCount() - 1);
		MethodHandle fbTyped = fallback.asType(type);
		site.setInitialFallback(fbTyped);
		site.setTarget(fbTyped);
		return site;
	}

	public static CallSite bootstrapNew(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type
	) {
		MethodHandle    megamorphic = InvokeMH.NEW_GENERIC.asCollector(1, Object[].class, type.parameterCount() - 1);
		ChainedCallSite site        = new ChainedCallSite(type, megamorphic);
		MethodHandle fallback = InvokeMH.NEW_FALLBACK.bindTo(site)
		 .asCollector(1, Object[].class, type.parameterCount() - 1);
		MethodHandle fbTyped = fallback.asType(type);
		site.setInitialFallback(fbTyped);
		site.setTarget(fbTyped);
		return site;
	}

	public static CallSite bootstrapBinaryOp(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type,
	 String op
	) {
		return JSBinaryOps.bootstrapBinaryOp(caller, name, type, op);
	}

	public static MethodHandle findSpecializedBinaryOp(String op, MethodType type) {
		return JSBinaryOps.findSpecializedBinaryOp(op, type);
	}

	public static CallSite bootstrapGetIndex(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type
	) {
		return JSIndexOps.bootstrapGetIndex(caller, name, type);
	}

	public static CallSite bootstrapSetIndex(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type
	) {
		return JSIndexOps.bootstrapSetIndex(caller, name, type);
	}
	//endregion

	//region Fallback 롢 Inline Cache ʵ

	@SuppressWarnings("EqualsReplaceableByObjectsCall")
	public static boolean isExactShapeAndKey(JSShape expectedShape, String expectedKey, Object target, Object key) {
		return JSIndexOps.isExactShapeAndKey(expectedShape, expectedKey, target, key);
	}

	public static boolean isExactShapeAndStringKey(JSShape expectedShape, String expectedKey, Object target, Object key) {
		return JSIndexOps.isExactShapeAndStringKey(expectedShape, expectedKey, target, key);
	}

	public static boolean isExactShapeAndSymbol(JSShape expectedShape, JSSymbol expectedSymbol, Object target,
	                                            Object key) {
		return JSIndexOps.isExactShapeAndSymbol(expectedShape, expectedSymbol, target, key);
	}

	public static boolean isExactShapeAndProtoAndStringKey(JSShape expectedShape, JSObject expectedProto,
	                                                       String expectedKey, Object target, Object key) {
		return JSIndexOps.isExactShapeAndProtoAndStringKey(expectedShape, expectedProto, expectedKey, target, key);
	}

	public static boolean isExactShapeAndProtoAndSymbol(JSShape expectedShape, JSObject expectedProto,
	                                                    JSSymbol expectedSymbol, Object target, Object key) {
		return JSIndexOps.isExactShapeAndProtoAndSymbol(expectedShape, expectedProto, expectedSymbol, target, key);
	}

	public static Object getIndexDynamicFallback(ChainedCallSite site, Object target, Object index) throws Throwable {
		return JSIndexOps.getIndexDynamicFallback(site, target, index);
	}

	public static void setIndexDynamicFallback(ChainedCallSite site, Object target, Object index, Object value)
	 throws Throwable {
		JSIndexOps.setIndexDynamicFallback(site, target, index, value);
	}

	public static Object getPropMegamorphic(ChainedCallSite site, Object target, String propName) {
		if (target instanceof JSObject jsObj) {
			JSShape s     = jsObj.shape;
			long[]  cache = site.directCache;
			if (cache == null) cache = site.getOrCreateDirectCache();
			int idx = ChainedCallSite.cacheIndex(s.id);

			// 64-bit 严格原子读取，防指令重排与 32 位 JVM 字撕裂
			long entry = (long) ChainedCallSite.CACHE_VH.getOpaque(cache, idx);
			if (entry != 0L && (int) (entry >>> 32) == s.id) {
				if (ChainedCallSite.ENABLE_STATS) ChainedCallSite.STATS_HITS.increment();
				int    offset = (int) entry;
				Object raw    = jsObj.getRawObjectSlot(offset);
				if (raw != JSObject.NOT_FOUND) {
					return jsObj.getSlot(offset);
				}
				return jsObj.get(propName);
			}

			if (ChainedCallSite.ENABLE_STATS) ChainedCallSite.STATS_MISSES.increment();
			int propId = site.getPropId();
			int offset = (propId >= 0) ? s.getOffset(propId) : s.getOffset(propName);
			if (offset >= 0) {
				if (!s.hasAccessors || !s.isAccessor(offset)) {
					// 64-bit 原子无锁写入 (高位 shape.id, 低位 offset)
					long newEntry = ((long) s.id << 32) | (offset & 0xFFFFFFFFL);
					ChainedCallSite.CACHE_VH.setOpaque(cache, idx, newEntry);
					Object raw = jsObj.getRawObjectSlot(offset);
					if (raw != JSObject.NOT_FOUND) {
						return jsObj.getSlot(offset);
					}
				}
			}
			return jsObj.get(propName);
		}
		if (target == null || target == JSUndefined.INSTANCE) return JSUndefined.INSTANCE;
		return getPropGeneric(target, propName);
	}

	public static double getPropDoubleMegamorphic(ChainedCallSite site, Object target, String propName) {
		if (target instanceof JSObject jsObj) {
			JSShape s     = jsObj.shape;
			long[]  cache = site.directCache;
			if (cache == null) cache = site.getOrCreateDirectCache();
			int idx = ChainedCallSite.cacheIndex(s.id);

			// 64-bit 严格原子读取，防指令重排与 32 位 JVM 字撕裂
			long entry = (long) ChainedCallSite.CACHE_VH.getOpaque(cache, idx);
			if (entry != 0L && (int) (entry >>> 32) == s.id) {
				if (ChainedCallSite.ENABLE_STATS) ChainedCallSite.STATS_HITS.increment();
				return jsObj.getDoubleSlot((int) entry);
			}

			if (ChainedCallSite.ENABLE_STATS) ChainedCallSite.STATS_MISSES.increment();
			int propId = site.getPropId();
			int offset = (propId >= 0) ? s.getOffset(propId) : s.getOffset(propName);
			if (offset >= 0) {
				if (s.getBaseType(offset) == JSShape.TYPE_DOUBLE || jsObj.isDoubleSlot(offset)) {
					ChainedCallSite.CACHE_VH.setOpaque(cache, idx, ((long) s.id << 32) | (offset & 0xFFFFFFFFL));
					return jsObj.getDoubleSlot(offset);
				}
			}
			return jsObj.getAsDouble(propName);
		}
		if (target == null || target == JSUndefined.INSTANCE) return Double.NaN;
		return getPropDoubleGeneric(target, propName);
	}

	public static int getPropIntMegamorphic(ChainedCallSite site, Object target, String propName) {
		if (target instanceof JSObject jsObj) {
			JSShape s     = jsObj.shape;
			long[]  cache = site.directCache;
			if (cache == null) cache = site.getOrCreateDirectCache();
			int idx = ChainedCallSite.cacheIndex(s.id);

			// 64-bit 严格原子读取，防指令重排与 32 位 JVM 字撕裂
			long entry = (long) ChainedCallSite.CACHE_VH.getOpaque(cache, idx);
			if (entry != 0L && (int) (entry >>> 32) == s.id) {
				if (ChainedCallSite.ENABLE_STATS) ChainedCallSite.STATS_HITS.increment();
				return (int) jsObj.getDoubleSlot((int) entry);
			}

			if (ChainedCallSite.ENABLE_STATS) ChainedCallSite.STATS_MISSES.increment();
			int propId = site.getPropId();
			int offset = (propId >= 0) ? s.getOffset(propId) : s.getOffset(propName);
			if (offset >= 0) {
				if (s.getBaseType(offset) == JSShape.TYPE_DOUBLE || jsObj.isDoubleSlot(offset)) {
					ChainedCallSite.CACHE_VH.setOpaque(cache, idx, ((long) s.id << 32) | (offset & 0xFFFFFFFFL));
					return (int) jsObj.getDoubleSlot(offset);
				}
			}
			return JSOps.toInt(jsObj.get(propName));
		}
		if (target == null || target == JSUndefined.INSTANCE) return 0;
		return getPropIntGeneric(target, propName);
	}

	public static long getPropLongMegamorphic(ChainedCallSite site, Object target, String propName) {
		if (target instanceof JSObject jsObj) {
			JSShape s     = jsObj.shape;
			long[]  cache = site.directCache;
			if (cache == null) cache = site.getOrCreateDirectCache();
			int idx = ChainedCallSite.cacheIndex(s.id);

			// 64-bit 严格原子读取，防指令重排与 32 位 JVM 字撕裂
			long entry = (long) ChainedCallSite.CACHE_VH.getOpaque(cache, idx);
			if (entry != 0L && (int) (entry >>> 32) == s.id) {
				if (ChainedCallSite.ENABLE_STATS) ChainedCallSite.STATS_HITS.increment();
				return (long) jsObj.getDoubleSlot((int) entry);
			}

			if (ChainedCallSite.ENABLE_STATS) ChainedCallSite.STATS_MISSES.increment();
			int propId = site.getPropId();
			int offset = (propId >= 0) ? s.getOffset(propId) : s.getOffset(propName);
			if (offset >= 0) {
				if (s.getBaseType(offset) == JSShape.TYPE_DOUBLE || jsObj.isDoubleSlot(offset)) {
					ChainedCallSite.CACHE_VH.setOpaque(cache, idx, ((long) s.id << 32) | (offset & 0xFFFFFFFFL));
					return (long) jsObj.getDoubleSlot(offset);
				}
			}
			return JSOps.toLong(jsObj.get(propName));
		}
		if (target == null || target == JSUndefined.INSTANCE) return 0L;
		return getPropLongGeneric(target, propName);
	}

	public static void setPropMegamorphic(ChainedCallSite site, Object target, Object value, String propName) {
		if (target instanceof JSObject jsObj) {
			JSShape s     = jsObj.shape;
			long[]  cache = site.directCache;
			if (cache == null) cache = site.getOrCreateDirectCache();
			int idx = ChainedCallSite.cacheIndex(s.id);

			// 64-bit 严格原子读取，防指令重排与 32 位 JVM 字撕裂
			long entry = (long) ChainedCallSite.CACHE_VH.getOpaque(cache, idx);
			if (entry != 0L && (int) (entry >>> 32) == s.id) {
				int offset = (int) entry;
				if (s.isAccessor(offset) || !s.isWritable(offset)) {
					jsObj.put(propName, value);
					return;
				}
				if (value instanceof Number num) {
					if (s.getBaseType(offset) == JSShape.TYPE_DOUBLE) {
						jsObj.setDoubleSlot(offset, num.doubleValue());
						return;
					}
				} else {
					if (s.getBaseType(offset) == JSShape.TYPE_OBJECT) {
						jsObj.setSlot(offset, value);
						return;
					}
				}
				// 发生跨类型写入 (Double <-> Object)，必须走 put 执行状态机形状迁移
				jsObj.put(propName, value);
				return;
			}

			int propId = site.getPropId();
			int offset = (propId >= 0) ? s.getOffset(propId) : s.getOffset(propName);
			if (offset >= 0) {
				ChainedCallSite.CACHE_VH.setOpaque(cache, idx, ((long) s.id << 32) | (offset & 0xFFFFFFFFL));
				if (s.isAccessor(offset) || !s.isWritable(offset)) {
					jsObj.put(propName, value);
					return;
				}
				if (value instanceof Number num) {
					if (s.getBaseType(offset) == JSShape.TYPE_DOUBLE) {
						jsObj.setDoubleSlot(offset, num.doubleValue());
						return;
					}
				} else {
					if (s.getBaseType(offset) == JSShape.TYPE_OBJECT) {
						jsObj.setSlot(offset, value);
						return;
					}
				}
				jsObj.put(propName, value);
				return;
			}
			jsObj.put(propName, value);
			return;
		}
		setPropGeneric(target, value, propName);
	}

	public static void setPropDoubleMegamorphic(ChainedCallSite site, Object target, double value, String propName) {
		if (target instanceof JSObject jsObj) {
			JSShape s     = jsObj.shape;
			long[]  cache = site.directCache;
			if (cache == null) cache = site.getOrCreateDirectCache();
			int idx = ChainedCallSite.cacheIndex(s.id);

			long entry = (long) ChainedCallSite.CACHE_VH.getOpaque(cache, idx);
			if (entry != 0L && (int) (entry >>> 32) == s.id) {
				int offset = (int) entry;
				if (s.isAccessor(offset) || !s.isWritable(offset) || s.getBaseType(offset) != JSShape.TYPE_DOUBLE) {
					jsObj.putDouble(propName, value);
					return;
				}
				jsObj.setDoubleSlot(offset, value);
				return;
			}

			int propId = site.getPropId();
			int offset = (propId >= 0) ? s.getOffset(propId) : s.getOffset(propName);
			if (offset >= 0) {
				ChainedCallSite.CACHE_VH.setOpaque(cache, idx, ((long) s.id << 32) | (offset & 0xFFFFFFFFL));
				if (s.isAccessor(offset) || !s.isWritable(offset) || s.getBaseType(offset) != JSShape.TYPE_DOUBLE) {
					jsObj.putDouble(propName, value);
					return;
				}
				jsObj.setDoubleSlot(offset, value);
				return;
			}
			jsObj.putDouble(propName, value);
			return;
		}
		setPropDoubleGeneric(target, value, propName);
	}

	public static Object getPropGeneric(Object target, String propName) {
		if (target == null || target == JSUndefined.INSTANCE) {
			return JSUndefined.INSTANCE;
		}

		if (propName.startsWith("__magic_super_")) {
			String realName = propName.substring("__magic_super_".length());

			if (target instanceof JSBridgedObject) {
				List<Method> candidates = MethodResolver.findCandidateMethods(target.getClass(), propName);
				if (!candidates.isEmpty()) {
					int arity = candidates.stream().mapToInt(Method::getParameterCount).min().orElse(0);
					return new BoundJavaMethod(target, target.getClass(), propName, arity, false);
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
					if (member != JSUndefined.INSTANCE) {
						return member;
					}
				}
			}
		}

		if (target instanceof JSSymbol sym) {
			if ("description".equals(propName)) {
				return sym.getDescription() != null ? sym.getDescription() : JSUndefined.INSTANCE;
			}
			return JSContext.LazySymbol.SYMBOL_PROTOTYPE.get(propName, sym);
		}

		if (target instanceof JSObject jsObj) {
			return jsObj.get(propName);
		}

		if (target instanceof JSBridgedObject bridged) {
			JSObject jsObj = bridged.getJSObject();
			if (jsObj != null) {
				Object v = jsObj.get(propName);
				if (v != JSUndefined.INSTANCE) {
					return v;
				}
			}
		}

		if (target instanceof Map) {
			Object v = ((Map<?, ?>) target).get(propName);
			return v == null ? JSUndefined.INSTANCE : v;
		}

		if (target instanceof Map.Entry<?, ?> entry) {
			if ("length".equals(propName) || "size".equals(propName)) return 2.0;
			if ("0".equals(propName) || "key".equals(propName)) return entry.getKey();
			if ("1".equals(propName) || "value".equals(propName)) return entry.getValue();
		}

		if (target.getClass().isArray() && "length".equals(propName)) {
			return (double) Array.getLength(target);
		}

		boolean  isStatic = false;
		Class<?> targetClass;
		if (target instanceof Class<?> c) {
			targetClass = c;
			isStatic = true;
		} else {
			targetClass = target.getClass();
		}

		l:
		try {
			Field field = MagicJIT.getDeclaredFieldRecursive(targetClass, propName);
			if (field == null) break l;
			field.setAccessible(true);
			if (isStatic) {
				if (Modifier.isStatic(field.getModifiers())) {
					return field.get(null);
				}
			} else {
				return field.get(target);
			}
		} catch (Throwable ignored) {
		}

		Method getterMethod = MethodResolver.findGetterMethod(targetClass, propName);
		if (getterMethod != null) {
			try {
				getterMethod.setAccessible(true);
				if (isStatic) {
					if (Modifier.isStatic(getterMethod.getModifiers())) {
						return getterMethod.invoke(null);
					}
				} else {
					return getterMethod.invoke(target);
				}
			} catch (Throwable ignored) {
			}
		}

		try {
			List<Method> candidates = MethodResolver.findCandidateMethods(targetClass, propName);
			if (!candidates.isEmpty()) {
				boolean hasStatic   = candidates.stream().anyMatch(m -> Modifier.isStatic(m.getModifiers()));
				boolean hasInstance = candidates.stream().anyMatch(m -> !Modifier.isStatic(m.getModifiers()));
				if (isStatic && hasStatic) {
					int arity = candidates.stream().filter(m -> Modifier.isStatic(m.getModifiers())).mapToInt(Method::getParameterCount).min().orElse(0);
					return getOrCreateStaticBoundMethod(targetClass, propName, arity);
				} else if (!isStatic && (hasInstance || hasStatic)) {
					int arity = candidates.stream().mapToInt(Method::getParameterCount).min().orElse(0);
					return new BoundJavaMethod(target, targetClass, propName, arity, false);
				}
			}
		} catch (Throwable ignored) {
		}

		return JSUndefined.INSTANCE;
	}

	public static int getPropIntGeneric(Object target, String propName) {
		return JSOps.toInt(getPropGeneric(target, propName));
	}

	public static double getPropDoubleGeneric(Object target, String propName) {
		return JSOps.toDouble(getPropGeneric(target, propName));
	}

	public static long getPropLongGeneric(Object target, String propName) {
		return JSOps.toLong(getPropGeneric(target, propName));
	}

	public static void setPropGeneric(Object target, Object value, String propName) {
		if (target == null || target == JSUndefined.INSTANCE) return;

		if (target instanceof JSObject jsObj) {
			jsObj.put(propName, value);
			return;
		}

		if (target instanceof Map) {
			((Map<Object, Object>) target).put(propName, value);
			return;
		}

		boolean  isStatic = false;
		Class<?> targetClass;
		if (target instanceof Class<?> c) {
			targetClass = c;
			isStatic = true;
		} else {
			targetClass = target.getClass();
		}

		l:
		try {
			Field field = MagicJIT.getDeclaredFieldRecursive(targetClass, propName);
			if (field == null) break l;
			field.setAccessible(true);
			if (isStatic) {
				if (Modifier.isStatic(field.getModifiers())) {
					field.set(null, JSOps.castValue(value, field.getType()));
					return;
				}
			} else {
				FastAccessor.setFieldDirect(target, field, value);
				return;
			}
		} catch (Throwable ignored) {
		}

		String capName = Character.toUpperCase(propName.charAt(0)) + (propName.length() > 1 ? propName.substring(1) : "");
		for (Method m : targetClass.getMethods()) {
			if (m.getName().equals("set" + capName) && m.getParameterCount() == 1) {
				try {
					m.setAccessible(true);
					Object casted = JSOps.castValue(value, m.getParameterTypes()[0]);
					if (isStatic) {
						if (Modifier.isStatic(m.getModifiers())) {
							m.invoke(null, casted);
							return;
						}
					} else {
						m.invoke(target, casted);
						return;
					}
				} catch (Throwable ignored) {
				}
			}
		}

		if (target instanceof JSBridgedObject bridged) {
			JSObject jsObj = bridged.getJSObject();
			if (jsObj != null) {
				jsObj.put(propName, value);
			}
		}
	}

	public static Object getAccessorProp(int offset, Object target) {
		if (target instanceof JSObject jsObj) {
			Object raw = jsObj.getRawObjectSlot(offset);
			if (raw instanceof PropertyAccessor acc) {
				return acc.callGetter(null, target);
			}
		}
		return JSUndefined.INSTANCE;
	}

	public static Object getPrototypeAccessorProp(PropertyAccessor acc, Object target) {
		if (acc != null) {
			return acc.callGetter(null, target);
		}
		return JSUndefined.INSTANCE;
	}

	public static Object getJSArrayLengthObj(Object target) {
		return (double) ((JSArray) target).length();
	}

	public static double getJSArrayLengthDouble(Object target) {
		return (double) ((JSArray) target).length();
	}

	public static long getJSArrayLengthLong(Object target) {
		return ((JSArray) target).length();
	}

	public static int getJSArrayLengthInt(Object target) {
		return (int) ((JSArray) target).length();
	}

	public static Object jsArrayFastPush0(Object target) {
		return (double) ((JSArray) target).length();
	}

	public static Object jsArrayFastPush1(Object target, Object val) {
		JSArray arr = (JSArray) target;
		arr.push(val);
		return (double) arr.length();
	}

	public static Object jsArrayFastPush2(Object target, Object v1, Object v2) {
		JSArray arr = (JSArray) target;
		arr.push(v1);
		arr.push(v2);
		return (double) arr.length();
	}

	public static Object jsArrayFastPop0(Object target) {
		return ((JSArray) target).pop();
	}

	public static void setAccessorProp(int offset, Object target, Object value) {
		if (target instanceof JSObject jsObj) {
			Object raw = jsObj.getRawObjectSlot(offset);
			if (raw instanceof PropertyAccessor acc) {
				acc.callSetter(null, target, value);
			}
		}
	}

	public static void setNoopProp(Object target, Object value) {
		// 只读属性静默忽略
	}

	public static Object getPropFallback(ChainedCallSite site, Object target, String propName) {
		if (site.getPropId() < 0) site.setPropId(SymbolTable.id(propName));
		if (target == null || target == JSUndefined.INSTANCE) {
			return JSUndefined.INSTANCE;
		}

		if (target instanceof JSObject jsObj) {
			if (target instanceof JSContext.JSGlobalThis globalThis) {
				return globalThis.get(propName);
			}
			if (target instanceof JSArray jsArr && "length".equals(propName)) {
				MethodHandle test = MH_IS_EXACT_CLASS.bindTo(JSArray.class);
				site.installGuardOrSwitchMegamorphic(test, MH_JS_ARRAY_LENGTH_OBJ.asType(site.type()));
				return (double) jsArr.length();
			}
			JSShape shape  = jsObj.shape;
			int     propId = site.getPropId();
			int     offset = (propId >= 0) ? shape.getOffset(propId) : shape.getOffset(propName);
			if (offset >= 0) {
				byte type = shape.getSlotType(offset);
				if ((type & JSShape.FLAG_ACCESSOR) != 0) {
					MethodHandle test         = MH_IS_EXACT_SHAPE.bindTo(shape);
					MethodHandle getterTarget = MethodHandles.insertArguments(MH_GET_ACCESSOR_PROP, 0, offset);
					site.installGuardOrSwitchMegamorphic(test, getterTarget.asType(site.type()));
					return getAccessorProp(offset, target);
				}
				site.recordShape(shape, offset, type);
				if (site.isMegamorphic()) {
					return jsObj.getSlot(offset);
				}

				if (site.isOffsetEquivalent()) {
					int          commonOff  = site.getCommonOffset();
					byte         commonType = site.getCommonType();
					MethodHandle test       = buildMultiShapeGuard(site.getRecordedShapesArray(), site.getPropId(), commonOff);

					MethodHandle directSlotGetter;
					if ((commonType & JSShape.TYPE_MASK) == JSShape.TYPE_DOUBLE) {
						// 全为 Double 槽位：直接读 double 并装箱
						directSlotGetter = (commonOff < 8)
						 ? MH_GET_SLOT_DOUBLE_AS_OBJ[commonOff]
						 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_DOUBLE_AS_OBJ, 0, commonOff);
					} else if ((commonType & JSShape.TYPE_MASK) == JSShape.TYPE_OBJECT) {
						// 全为 Object 槽位：零掩码检查直接读引用
						directSlotGetter = (commonOff < 8)
						 ? MH_GET_SLOT_PURE_OBJECT[commonOff]
						 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, commonOff);
					} else {
						// 槽位类型存在混合 (既有 double 也有 object)：回退使用带 doubleFieldMask 动态判断的安全读
						directSlotGetter = (commonOff < 8)
						 ? MH_GET_SLOT_OBJECT[commonOff]
						 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, commonOff);
					}

					MethodHandle fallbackTarget = getAdaptiveFallback(site);
					site.setTarget(MethodHandles.guardWithTest(test, directSlotGetter.asType(site.type()), fallbackTarget.asType(site.type())));
					return jsObj.getSlot(commonOff);
				}

				// 异槽多态：一旦观测到 >= 2 个异槽 Shape，挂载扁平 switch，避免继续堆叠 guardWithTest 层
				if (site.getPolyCount() >= 2) {
					MethodHandle fb   = getAdaptiveFallback(site);
					PolySnapshot snap = site.snapshotPoly();
					site.installFlatPolyGuard(buildFlatPolySwitchObject(snap, fb));
				} else {
					MethodHandle test         = MH_IS_EXACT_SHAPE.bindTo(shape);
					boolean      isDoubleSlot = (type & JSShape.TYPE_MASK) == JSShape.TYPE_DOUBLE;
					MethodHandle directSlotGetter;
					if (offset < 8) {
						directSlotGetter = isDoubleSlot ? MH_GET_SLOT_DOUBLE_AS_OBJ[offset] : MH_GET_SLOT_PURE_OBJECT[offset];
					} else {
						directSlotGetter = isDoubleSlot
						 ? MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_DOUBLE_AS_OBJ, 0, offset)
						 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, offset);
					}
					site.installGuardOrSwitchMegamorphic(test, directSlotGetter);
				}
				return jsObj.getSlot(offset);
			}
			// 原型链属性快速查找与 SwitchPoint 守卫挂载
			JSObject proto = jsObj.getPrototype();
			if (proto != null) {
				JSObject       current      = proto;
				JSObject       holder       = null;
				int            holderOffset = -1;
				List<JSObject> chain        = null;

				while (current != null) {
					int pOff = (propId >= 0) ? current.shape.getOffset(propId) : current.shape.getOffset(propName);
					if (pOff >= 0 && (current.isDoubleSlot(pOff) || current.getRawObjectSlot(pOff) != JSObject.NOT_FOUND)) {
						holder = current;
						holderOffset = pOff;
						break;
					}
					if (chain == null) chain = new ArrayList<>(2);
					chain.add(current);
					current = current.getPrototype();
				}

				if (holder != null) {
					byte         slotType = holder.shape.getSlotType(holderOffset);
					MethodHandle test     = MH_IS_EXACT_SHAPE_AND_PROTO.bindTo(shape).bindTo(proto);
					MethodHandle fb       = site.getInitialFallback();
					MethodHandle fbTyped  = (fb != null) ? fb.asType(site.type()) : null;

					if ((slotType & JSShape.FLAG_ACCESSOR) != 0) {
						Object raw = holder.getRawObjectSlot(holderOffset);
						if (raw instanceof PropertyAccessor acc) {
							MethodHandle      getterTarget = MethodHandles.insertArguments(MH_GET_PROTO_ACCESSOR_PROP, 0, acc).asType(site.type());
							List<SwitchPoint> allSps       = new ArrayList<>(chain != null ? chain.size() + 1 : 1);
							if (chain != null && fbTyped != null) {
								for (JSObject p : chain) {
									SwitchPoint pSp = p.getOrCreateProtoSwitchPoint();
									getterTarget = pSp.guardWithTest(getterTarget, fbTyped);
									allSps.add(pSp);
								}
							}
							SwitchPoint holderSp = holder.getOrCreateProtoSwitchPoint();
							allSps.add(holderSp);
							site.installProtoGuard(holderSp, allSps, test, getterTarget);
							return acc.callGetter(null, target);
						}
					} else {
						Object val = holder.getSlot(holderOffset);
						// 原型属性作为静态常量绑定 (包括函数、基础包装类型及普通对象常量)
						MethodHandle constTarget = MethodHandles.dropArguments(
						 MethodHandles.constant(Object.class, val), 0, site.type().parameterList()
						).asType(site.type());
						List<SwitchPoint> allSps = new ArrayList<>(chain != null ? chain.size() + 1 : 1);
						if (chain != null && fbTyped != null) {
							for (JSObject p : chain) {
								SwitchPoint pSp = p.getOrCreateProtoSwitchPoint();
								constTarget = pSp.guardWithTest(constTarget, fbTyped);
								allSps.add(pSp);
							}
						}
						SwitchPoint holderSp = holder.getOrCreateProtoSwitchPoint();
						allSps.add(holderSp);
						site.installProtoGuard(holderSp, allSps, test, constTarget);
						return val;
					}
				}
			}

			return jsObj.get(propName);
		}

		if (target instanceof JSBridgedObject bridged) {
			JSObject jsObj = bridged.getJSObject();
			if (jsObj != null) {
				Object v = jsObj.get(propName, target);
				if (v != JSUndefined.INSTANCE) {
					return v;
				}
			}
		}

		if (target instanceof Map) {
			Object v = ((Map<?, ?>) target).get(propName);
			return v == null ? JSUndefined.INSTANCE : v;
		}

		if (target instanceof Map.Entry<?, ?> entry) {
			if ("length".equals(propName) || "size".equals(propName)) return 2.0;
			if ("0".equals(propName) || "key".equals(propName)) return entry.getKey();
			if ("1".equals(propName) || "value".equals(propName)) return entry.getValue();
		}

		if (target.getClass().isArray()) {
			if ("length".equals(propName)) {
				try {
					MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
					site.installGuardOrSwitchMegamorphic(test, MH_ARRAY_LENGTH_DOUBLE.asType(site.type()));
				} catch (Throwable ignored) { }
				return (double) Array.getLength(target);
			}
		}

		boolean  isStatic = false;
		Class<?> targetClass;
		if (target instanceof Class<?> c) {
			targetClass = c;
			isStatic = true;
		} else {
			targetClass = target.getClass();
		}

		MethodHandle test = isStatic ? MH_IS_SAME_OBJECT.bindTo(target) : MH_IS_EXACT_CLASS.bindTo(targetClass);

		if (isStatic) {
			Field field = MagicJIT.getDeclaredFieldRecursive(targetClass, propName);
			if (field != null && Modifier.isStatic(field.getModifiers())) {
				try {
					field.setAccessible(true);
					MethodHandle mh           = Magic.lookup.unreflectGetter(field);
					MethodHandle directGetter = MethodHandles.dropArguments(mh, 0, Object.class).asType(site.type());
					site.installGuardOrSwitchMegamorphic(test, directGetter);
					return field.get(null);
				} catch (Throwable ignored) {
				}
			}

			Method getterMethod = MethodResolver.findGetterMethod(targetClass, propName);
			if (getterMethod != null && Modifier.isStatic(getterMethod.getModifiers())) {
				try {
					getterMethod.setAccessible(true);
					MethodHandle mh           = Magic.lookup.unreflect(getterMethod);
					MethodHandle directGetter = MethodHandles.dropArguments(mh, 0, Object.class).asType(site.type());
					site.installGuardOrSwitchMegamorphic(test, directGetter);
					return directGetter.invoke(target);
				} catch (Throwable ignored) {
				}
			}

			List<Method> candidates = MethodResolver.findCandidateMethods(targetClass, propName);
			if (!candidates.isEmpty() && candidates.stream().anyMatch(m -> Modifier.isStatic(m.getModifiers()))) {
				int             arity = candidates.stream().filter(m -> Modifier.isStatic(m.getModifiers())).mapToInt(Method::getParameterCount).min().orElse(0);
				BoundJavaMethod bound = getOrCreateStaticBoundMethod(targetClass, propName, arity);
				try {
					MethodHandle directGetter = MethodHandles.dropArguments(MethodHandles.constant(Object.class, bound), 0, Object.class).asType(site.type());
					site.installGuardOrSwitchMegamorphic(test, directGetter);
				} catch (Throwable ignored) {
				}
				return bound;
			}

			return JSUndefined.INSTANCE;
		}

		// 1. 尝试匹配 Java 字段 (私有/公有字段通过 MAGICIMPL 字节码直读或 Unsafe 偏移直读)
		if (STRATEGY != InvocationStrategy.SPREADER) {
			try {
				MethodHandle exactGetter = MagicJIT.getFieldGetterStub(targetClass, propName);
				if (exactGetter != null) {
					site.installJavaGuard(targetClass, test, exactGetter);
					return exactGetter.invokeExact(target);
				}
			} catch (Throwable ignored) {
			}
		}

		l:
		try {
			Field field = MagicJIT.getDeclaredFieldRecursive(targetClass, propName);
			if (field == null) break l;
			long         offset       = LinkerHelper.getFieldOffset(field);
			MethodHandle directGetter = buildDirectFieldGetter(targetClass, field, offset);

			// 构造单态/多态内联缓存
			site.installJavaGuard(targetClass, test, directGetter);
			return directGetter.invoke(target);
		} catch (Throwable ignored) {
		}

		// 2. 尝试匹配 getter 方法 (getFoo / isFoo)
		Method getterMethod = MethodResolver.findGetterMethod(targetClass, propName);
		if (getterMethod != null) {
			try {
				getterMethod.setAccessible(true);
				MethodHandle mh           = Magic.lookup.unreflect(getterMethod);
				MethodHandle directGetter = mh.asType(site.type());
				site.installJavaGuard(targetClass, test, directGetter);
				return directGetter.invoke(target);
			} catch (Throwable ignored) {
			}
		}

		// 3. 尝试匹配方法名并返回绑定的 JS 方法函数
		List<Method> candidates = MethodResolver.findCandidateMethods(targetClass, propName);
		if (!candidates.isEmpty()) {
			int arity = candidates.stream().mapToInt(Method::getParameterCount).min().orElse(0);
			try {
				MethodHandle factory = MethodHandles.insertArguments(MH_CREATE_BOUND_INSTANCE_METHOD, 1, targetClass, propName, arity);
				site.installJavaGuard(targetClass, test, factory.asType(site.type()));
			} catch (Throwable ignored) {
			}
			return new BoundJavaMethod(target, targetClass, propName, arity, false);
		}

		return JSUndefined.INSTANCE;
	}

	private static final ClassValue<ConcurrentHashMap<String, BoundJavaMethod>> STATIC_METHOD_CACHE = new ClassValue<>() {
		@Override
		protected ConcurrentHashMap<String, BoundJavaMethod> computeValue(Class<?> type) {
			return new ConcurrentHashMap<>();
		}
	};

	public static BoundJavaMethod getOrCreateStaticBoundMethod(Class<?> targetClass, String propName, int arity) {
		return STATIC_METHOD_CACHE
		 .get(targetClass)
		 .computeIfAbsent(propName, k -> new BoundJavaMethod(targetClass, targetClass, propName, arity, true));
	}

	public static void invalidateClass(Class<?> clazz) {
		if (clazz == null) return;
		JSJavaInterop.invalidateClass(clazz);
		STATIC_METHOD_CACHE.remove(clazz);
	}

	public static Object createBoundInstanceMethod(Object target, Class<?> targetClass, String propName, int arity) {
		return new BoundJavaMethod(target, targetClass, propName, arity, false);
	}

	public static void setPropFallback(ChainedCallSite site, Object target, Object value, String propName) {
		if (site.getPropId() < 0) site.setPropId(SymbolTable.id(propName));
		if (target == null || target == JSUndefined.INSTANCE) return;

		if (target instanceof JSObject jsObj) {
			boolean isPrototype = jsObj.getProtoSwitchPoint() != null || jsObj.isArrayPrototype();
			if (isPrototype) {
				jsObj.put(propName, value);
				return;
			}
			if (target instanceof JSArray jsArr) {
				jsArr.put(propName, value);
				return;
			}
			if (target instanceof JSContext.JSGlobalThis globalThis) {
				globalThis.put(propName, value);
				return;
			}
			JSShape shape  = jsObj.shape;
			int     propId = site.getPropId();
			int     offset = (propId >= 0) ? shape.getOffset(propId) : shape.getOffset(propName);
			if (offset >= 0) {
				byte type = shape.getSlotType(offset);
				if ((type & JSShape.FLAG_ACCESSOR) != 0) {
					MethodHandle test         = MH_IS_EXACT_SHAPE_SETTER_OBJECT.bindTo(shape);
					MethodHandle setterTarget = MethodHandles.insertArguments(MH_SET_ACCESSOR_PROP, 0, offset);
					site.installGuardOrSwitchMegamorphic(test, setterTarget.asType(site.type()));
					setAccessorProp(offset, target, value);
					return;
				}
				if ((type & JSShape.FLAG_NOT_WRITABLE) != 0) {
					MethodHandle test = MH_IS_EXACT_SHAPE_SETTER_OBJECT.bindTo(shape);
					site.installGuardOrSwitchMegamorphic(test, MH_SET_NOOP_PROP.asType(site.type()));
					return;
				}

				byte currentBaseType = (byte) (type & JSShape.TYPE_MASK);
				byte newBaseType     = (value instanceof Number) ? JSShape.TYPE_DOUBLE : JSShape.TYPE_OBJECT;
				if (currentBaseType != newBaseType) {
					jsObj.shape = shape.updatePropertyType(offset, newBaseType);
					if (newBaseType == JSShape.TYPE_DOUBLE) {
						jsObj.setDoubleSlot(offset, JSOps.toDouble(value));
					} else {
						jsObj.setSlot(offset, value);
					}
					return;
				}

				boolean isDouble = (type & JSShape.TYPE_MASK) == JSShape.TYPE_DOUBLE && (value instanceof Number);
				site.recordShape(shape, offset, type);
				if (site.isMegamorphic()) {
					if (isDouble) {
						jsObj.setDoubleSlot(offset, JSOps.toDouble(value));
					} else {
						jsObj.setSlot(offset, value);
					}
					return;
				}
				if (site.isOffsetEquivalent()) {
					int          commonOff      = site.getCommonOffset();
					byte         commonType     = site.getCommonType();
					boolean      isCommonDouble = (commonType & JSShape.TYPE_MASK) == JSShape.TYPE_DOUBLE && (value instanceof Number);
					MethodHandle test           = buildMultiShapeGuardSetterObject(site.getRecordedShapesArray());
					MethodHandle baseSetter = (commonOff >= 0 && commonOff < 8)
					 ? (isCommonDouble ? MH_SET_SLOT_DOUBLE[commonOff] : MH_SET_SLOT_OBJECT[commonOff])
					 : (isCommonDouble ? MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT_DOUBLE, 0, commonOff) : MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT, 0, commonOff));
					MethodHandle directSlotSetter;
					if (offset < 8) {
						// 0~7 In-Object：纯静态特化方法，内联深度 = 1
						directSlotSetter = isDouble ? MH_SET_SLOT_DOUBLE_AS_OBJ[offset] : MH_SET_SLOT_OBJECT[offset];
					} else {
						// >=8 溢出槽：直接绑定 offset，内联深度 = 1
						directSlotSetter = isDouble
						 ? MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT_DOUBLE_AS_OBJ, 0, offset)
						 : MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT, 0, offset);
					}
					MethodHandle fallbackTarget = site.getMegamorphicTarget() != null ? site.getMegamorphicTarget() : (site.getInitialFallback() != null ? site.getInitialFallback() : site.getTarget());
					site.setTarget(MethodHandles.guardWithTest(test, directSlotSetter.asType(site.type()), fallbackTarget.asType(site.type())));
					if (isCommonDouble) {
						jsObj.setDoubleSlot(commonOff, JSOps.toDouble(value));
					} else {
						jsObj.setSlot(commonOff, value);
					}
					return;
				}

				// 异槽多态：一旦观测到 >= 2 个异槽 Shape，挂载扁平 switch
				if (site.getPolyCount() >= 2) {
					MethodHandle fb = getAdaptiveFallback(site);
					site.installFlatPolyGuard(buildFlatPolySwitchSetterObject(site.snapshotPoly(), fb));
				} else {
					MethodHandle test = MH_IS_EXACT_SHAPE_SETTER_OBJECT.bindTo(shape);
					MethodHandle baseSetter = offset < 8
					 ? (isDouble ? MH_SET_SLOT_DOUBLE[offset] : MH_SET_SLOT_OBJECT[offset])
					 : (isDouble ? MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT_DOUBLE, 0, offset) : MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT, 0, offset));
					MethodHandle directSlotSetter;
					if (offset < 8) {
						// 0~7 In-Object：纯静态特化方法，内联深度 = 1
						directSlotSetter = isDouble ? MH_SET_SLOT_DOUBLE_AS_OBJ[offset] : MH_SET_SLOT_OBJECT[offset];
					} else {
						// >=8 溢出槽：直接绑定 offset，内联深度 = 1
						directSlotSetter = isDouble
						 ? MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT_DOUBLE_AS_OBJ, 0, offset)
						 : MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT, 0, offset);
					}
					site.installGuardOrSwitchMegamorphic(test, directSlotSetter.asType(site.type()));
				}
				if (isDouble) {
					jsObj.setDoubleSlot(offset, JSOps.toDouble(value));
				} else {
					jsObj.setSlot(offset, value);
				}
				return;
			}
			if (propId < 0) {
				propId = SymbolTable.id(propName);
				site.setPropId(propId);
			}
			if (jsObj.getPrototype() != null && jsObj.getPrototype().handlePrototypePut(propId, target, value)) {
				return;
			}
			JSShape oldShape  = jsObj.shape;
			byte    valType   = (value instanceof Number) ? JSShape.TYPE_DOUBLE : JSShape.TYPE_OBJECT;
			JSShape newShape  = oldShape.addProperty(propId, valType);
			int     newOffset = newShape.propertyCount - 1;

			MethodHandle test = MH_IS_EXACT_SHAPE_SETTER_OBJECT.bindTo(oldShape);
			MethodHandle directSetter = (valType == JSShape.TYPE_DOUBLE)
			 ? MethodHandles.insertArguments(MH_TRANSITION_SET_OBJECT_DOUBLE, 0, newShape, newOffset)
			 : MethodHandles.insertArguments(MH_TRANSITION_SET_OBJECT, 0, newShape, newOffset);
			site.installGuardOrSwitchMegamorphic(test, directSetter);

			jsObj.shape = newShape;
			if (valType == JSShape.TYPE_DOUBLE) {
				jsObj.setDoubleSlot(newOffset, JSOps.toDouble(value));
			} else {
				jsObj.setSlot(newOffset, value);
			}
			return;
		}

		if (target instanceof JSBridgedObject bridged) {
			JSObject jsObj = bridged.getJSObject();
			if (jsObj != null) {
				int propId = site.getPropId() >= 0 ? site.getPropId() : SymbolTable.id(propName);
				if (jsObj.getPrototype() != null && jsObj.getPrototype().handlePrototypePut(propId, target, value)) {
					return;
				}
				jsObj.put(propName, value);
				return;
			}
		}

		if (target instanceof Map) {
			((Map<Object, Object>) target).put(propName, value);
			return;
		}

		boolean  isStatic = false;
		Class<?> targetClass;
		if (target instanceof Class<?> c) {
			targetClass = c;
			isStatic = true;
		} else {
			targetClass = target.getClass();
		}

		MethodHandle test = isStatic ? MH_IS_SAME_OBJECT.bindTo(target) : MH_IS_EXACT_CLASS.bindTo(targetClass);
		if (site.type().parameterCount() > 1) {
			test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
		}

		if (isStatic) {
			Field field = MagicJIT.getDeclaredFieldRecursive(targetClass, propName);
			if (field != null && Modifier.isStatic(field.getModifiers())) {
				try {
					field.setAccessible(true);
					MethodHandle unreflectSetter = Magic.lookup.unreflectSetter(field);
					Class<?>     fType           = field.getType();
					MethodHandle filter          = getArgumentFilter(fType);
					if (filter != null) {
						unreflectSetter = MethodHandles.filterArguments(unreflectSetter, 0, filter);
					}
					MethodHandle directSetter = MethodHandles.dropArguments(unreflectSetter, 0, Object.class).asType(site.type());
					site.installGuardOrSwitchMegamorphic(test, directSetter);
					field.set(null, JSOps.castValue(value, fType));
					return;
				} catch (Throwable ignored) {
				}
			}
			Method setterMethod = MethodResolver.findSetterMethod(targetClass, propName);
			if (setterMethod != null && Modifier.isStatic(setterMethod.getModifiers())) {
				try {
					setterMethod.setAccessible(true);
					MethodHandle unreflectSetter = Magic.lookup.unreflect(setterMethod);
					Class<?>     pType           = setterMethod.getParameterTypes()[0];
					MethodHandle filter          = getArgumentFilter(pType);
					if (filter != null) {
						unreflectSetter = MethodHandles.filterArguments(unreflectSetter, 0, filter);
					}
					MethodHandle directSetter = MethodHandles.dropArguments(unreflectSetter, 0, Object.class).asType(site.type());
					site.installGuardOrSwitchMegamorphic(test, directSetter);
					setterMethod.invoke(null, JSOps.castValue(value, pType));
					return;
				} catch (Throwable ignored) {
				}
			}
			setPropGeneric(target, value, propName);
			return;
		}

		// 1. 尝试通过 MAGICIMPL 直写字段或 Unsafe 偏移直写
		if (STRATEGY != InvocationStrategy.SPREADER) {
			try {
				MethodHandle exactSetter = MagicJIT.getFieldSetterStub(targetClass, propName);
				if (exactSetter != null) {
					site.installJavaGuard(targetClass, test, exactSetter);
					exactSetter.invokeExact(target, value);
					return;
				}
			} catch (Throwable ignored) {
			}
		}

		l:
		try {
			Field field = MagicJIT.getDeclaredFieldRecursive(targetClass, propName);
			if (field == null) break l;
			long         offset       = LinkerHelper.getFieldOffset(field);
			MethodHandle directSetter = buildDirectFieldSetter(targetClass, field, offset);

			site.installJavaGuard(targetClass, test, directSetter);
			directSetter.invoke(target, value);
			return;
		} catch (Throwable ignored) {
		}

		// 2. 尝试匹配 setter 方法 (setFoo)
		Method setterMethod = MethodResolver.findSetterMethod(targetClass, propName);
		if (setterMethod != null) {
			try {
				setterMethod.setAccessible(true);
				MethodHandle mh        = Magic.lookup.unreflect(setterMethod);
				Class<?>     paramType = setterMethod.getParameterTypes()[0];
				MethodHandle filter    = getArgumentFilter(paramType);
				if (filter != null) {
					mh = MethodHandles.filterArguments(mh, 1, filter);
				}
				MethodHandle directSetter = mh.asType(site.type());
				site.installJavaGuard(targetClass, test, directSetter);
				directSetter.invoke(target, value);
				return;
			} catch (Throwable ignored) {
			}
		}

		if (target instanceof JSBridgedObject bridged) {
			JSObject jsObj = bridged.getJSObject();
			if (jsObj != null) {
				jsObj.put(propName, value);
				return;
			}
		}
	}

	public static void setPropDoubleFallback(ChainedCallSite site, Object target, double value, String propName) {
		if (site.getPropId() < 0) site.setPropId(SymbolTable.id(propName));
		if (target == null || target == JSUndefined.INSTANCE) return;

		if (target instanceof JSObject jsObj) {
			boolean isPrototype = jsObj.getProtoSwitchPoint() != null || jsObj.isArrayPrototype();
			if (isPrototype) {
				jsObj.putDouble(propName, value);
				return;
			}
			if (target instanceof JSArray jsArr) {
				jsArr.put(propName, value);
				return;
			}
			if (target instanceof JSContext.JSGlobalThis globalThis) {
				globalThis.put(propName, value);
				return;
			}
			JSShape shape  = jsObj.shape;
			int     propId = site.getPropId();
			int     offset = (propId >= 0) ? shape.getOffset(propId) : shape.getOffset(propName);
			if (offset >= 0) {
				byte type = shape.getSlotType(offset);
				if ((type & JSShape.FLAG_ACCESSOR) != 0) {
					MethodHandle test         = MH_IS_EXACT_SHAPE_SETTER_DOUBLE.bindTo(shape);
					MethodHandle setterTarget = MethodHandles.insertArguments(MH_SET_ACCESSOR_PROP, 0, offset);
					site.installGuardOrSwitchMegamorphic(test, setterTarget.asType(site.type()));
					setAccessorProp(offset, target, value);
					return;
				}
				if ((type & JSShape.FLAG_NOT_WRITABLE) != 0) {
					MethodHandle test = MH_IS_EXACT_SHAPE_SETTER_DOUBLE.bindTo(shape);
					site.installGuardOrSwitchMegamorphic(test, MH_SET_NOOP_PROP.asType(site.type()));
					return;
				}

				byte currentBaseType = (byte) (type & JSShape.TYPE_MASK);
				if (currentBaseType != JSShape.TYPE_DOUBLE) {
					jsObj.shape = shape.updatePropertyType(offset, JSShape.TYPE_DOUBLE);
					jsObj.setDoubleSlot(offset, value);
					return;
				}

				site.recordShape(shape, offset, JSShape.TYPE_DOUBLE);
				if (site.isMegamorphic()) {
					jsObj.setDoubleSlot(offset, value);
					return;
				}

				if (site.isOffsetEquivalent()) {
					int          commonOff = site.getCommonOffset();
					MethodHandle test      = buildMultiShapeGuardSetterDouble(site.getRecordedShapesArray());
					MethodHandle directSlotSetter = (commonOff >= 0 && commonOff < 8)
					 ? MH_SET_SLOT_DOUBLE[commonOff]
					 : MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT_DOUBLE, 0, commonOff);
					MethodHandle fallbackTarget = site.getMegamorphicTarget() != null ? site.getMegamorphicTarget() : (site.getInitialFallback() != null ? site.getInitialFallback() : site.getTarget());
					site.setTarget(MethodHandles.guardWithTest(test, directSlotSetter.asType(site.type()), fallbackTarget.asType(site.type())));
					jsObj.setDoubleSlot(commonOff, value);
					return;
				}

				// 异槽多态：一旦观测到 >= 2 个异槽 Shape，挂载扁平 switch
				if (site.getPolyCount() >= 2) {
					MethodHandle fb = getAdaptiveFallback(site);
					site.installFlatPolyGuard(buildFlatPolySwitchSetterDouble(site.snapshotPoly(), fb));
				} else {
					MethodHandle test = MH_IS_EXACT_SHAPE_SETTER_DOUBLE.bindTo(shape);
					MethodHandle directSlotSetter;
					if (offset < 8) {
						// 0~7 In-Object：纯静态特化方法，内联深度 = 1
						directSlotSetter = MH_SET_SLOT_DOUBLE[offset];
					} else {
						// >=8 溢出槽：直接绑定 offset，内联深度 = 1
						directSlotSetter = MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT_DOUBLE, 0, offset);
					}
					site.installGuardOrSwitchMegamorphic(test, directSlotSetter);
				}
				jsObj.setDoubleSlot(offset, value);
				return;
			}
			if (propId < 0) {
				propId = SymbolTable.id(propName);
				site.setPropId(propId);
			}
			if (jsObj.getPrototype() != null && jsObj.getPrototype().handlePrototypePut(propId, target, value)) {
				return;
			}
			JSShape oldShape  = jsObj.shape;
			JSShape newShape  = oldShape.addProperty(propId, JSShape.TYPE_DOUBLE);
			int     newOffset = newShape.propertyCount - 1;

			MethodHandle test         = MH_IS_EXACT_SHAPE_SETTER_DOUBLE.bindTo(oldShape);
			MethodHandle directSetter = MethodHandles.insertArguments(MH_TRANSITION_SET_DOUBLE, 0, newShape, newOffset);
			site.installGuardOrSwitchMegamorphic(test, directSetter);

			jsObj.shape = newShape;
			jsObj.setDoubleSlot(newOffset, value);
			return;
		}

		if (target instanceof JSBridgedObject bridged) {
			JSObject jsObj = bridged.getJSObject();
			if (jsObj != null) {
				int propId = site.getPropId() >= 0 ? site.getPropId() : SymbolTable.id(propName);
				if (jsObj.getPrototype() != null && jsObj.getPrototype().handlePrototypePut(propId, target, value)) {
					return;
				}
				jsObj.put(propName, value);
				return;
			}
		}

		if (target instanceof Map) {
			((Map<Object, Object>) target).put(propName, value);
			return;
		}

		boolean  isStatic = false;
		Class<?> targetClass;
		if (target instanceof Class<?> c) {
			targetClass = c;
			isStatic = true;
		} else {
			targetClass = target.getClass();
		}

		MethodHandle test = isStatic ? MH_IS_SAME_OBJECT.bindTo(target) : MH_IS_EXACT_CLASS.bindTo(targetClass);
		if (site.type().parameterCount() > 1) {
			test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
		}

		if (isStatic) {
			Field field = MagicJIT.getDeclaredFieldRecursive(targetClass, propName);
			if (field != null && Modifier.isStatic(field.getModifiers())) {
				try {
					field.setAccessible(true);
					MethodHandle unreflectSetter = Magic.lookup.unreflectSetter(field);
					Class<?>     fType           = field.getType();
					MethodHandle filter          = getArgumentFilter(fType);
					if (filter != null) {
						unreflectSetter = MethodHandles.filterArguments(unreflectSetter, 0, filter);
					}
					MethodHandle directSetter = MethodHandles.dropArguments(unreflectSetter, 0, Object.class).asType(site.type());
					site.installGuardOrSwitchMegamorphic(test, directSetter);
					field.set(null, JSOps.castValue(value, fType));
					return;
				} catch (Throwable ignored) {
				}
			}
			Method setterMethod = MethodResolver.findSetterMethod(targetClass, propName);
			if (setterMethod != null && Modifier.isStatic(setterMethod.getModifiers())) {
				try {
					setterMethod.setAccessible(true);
					MethodHandle unreflectSetter = Magic.lookup.unreflect(setterMethod);
					Class<?>     pType           = setterMethod.getParameterTypes()[0];
					MethodHandle filter          = getArgumentFilter(pType);
					if (filter != null) {
						unreflectSetter = MethodHandles.filterArguments(unreflectSetter, 0, filter);
					}
					MethodHandle directSetter = MethodHandles.dropArguments(unreflectSetter, 0, Object.class).asType(site.type());
					site.installGuardOrSwitchMegamorphic(test, directSetter);
					setterMethod.invoke(null, JSOps.castValue(value, pType));
					return;
				} catch (Throwable ignored) {
				}
			}
			setPropDoubleGeneric(target, value, propName);
			return;
		}

		// 1. 尝试通过 MAGICIMPL 直写字段或 Unsafe 偏移直写
		if (STRATEGY != InvocationStrategy.SPREADER) {
			try {
				MethodHandle exactSetter = MagicJIT.getFieldSetterStub(targetClass, propName);
				if (exactSetter != null) {
					MethodHandle directSetter = exactSetter.asType(site.type());
					site.installJavaGuard(targetClass, test, directSetter);
					directSetter.invoke(target, value);
					return;
				}
			} catch (Throwable ignored) {
			}
		}

		l:
		try {
			Field field = MagicJIT.getDeclaredFieldRecursive(targetClass, propName);
			if (field == null) break l;
			long         offset    = LinkerHelper.getFieldOffset(field);
			MethodHandle rawSetter = buildDirectFieldSetter(targetClass, field, offset);
			if (field.getType() == boolean.class) {
				rawSetter = MethodHandles.filterArguments(rawSetter, 1, MH_TO_BOOLEAN);
			}
			MethodHandle directSetter = rawSetter.asType(site.type());

			site.installJavaGuard(targetClass, test, directSetter);
			directSetter.invoke(target, value);
			return;
		} catch (Throwable ignored) {
		}

		// 2. 尝试匹配 setter 方法 (setFoo)
		Method setterMethod = MethodResolver.findSetterMethod(targetClass, propName);
		if (setterMethod != null) {
			try {
				setterMethod.setAccessible(true);
				MethodHandle mh        = Magic.lookup.unreflect(setterMethod);
				Class<?>     paramType = setterMethod.getParameterTypes()[0];
				if (!paramType.isPrimitive() || paramType == boolean.class) {
					MethodHandle filter = getArgumentFilter(paramType);
					if (filter != null) {
						mh = MethodHandles.filterArguments(mh, 1, filter);
					}
				}
				MethodHandle directSetter = mh.asType(site.type());
				site.installJavaGuard(targetClass, test, directSetter);
				directSetter.invoke(target, value);
				return;
			} catch (Throwable ignored) {
			}
		}

		setPropDoubleGeneric(target, value, propName);
	}

	public static void setPropDoubleGeneric(Object target, double value, String propName) {
		setPropGeneric(target, value, propName);
	}
	//endregion

	//region Dynalink / JLS 重载决议与 Java 互操作 (委托至 JSJavaInterop)

	public static int computeConversionCost(Object arg, Class<?> targetType) {
		return JSJavaInterop.computeConversionCost(arg, targetType);
	}

	public static boolean isMoreSpecific(Method m1, Method m2) {
		return JSJavaInterop.isMoreSpecific(m1, m2);
	}

	public static Object invokeIndex(Object target, Object index, Object[] args) throws Throwable {
		return JSJavaInterop.invokeIndex(target, index, args);
	}

	public static double invokeDoubleGeneric(Object target, Object[] args, String methodName) throws Throwable {
		return JSJavaInterop.invokeDoubleGeneric(target, args, methodName);
	}

	public static Object invokeGeneric(Object target, Object[] args, String methodName) throws Throwable {
		return JSJavaInterop.invokeGeneric(target, args, methodName);
	}

	public static Object[] packVarArgs(Class<?>[] paramTypes, Object[] args) {
		return JSJavaInterop.packVarArgs(paramTypes, args);
	}
	//endregion

	private static Object slowOwnMethod(JSObject obj, int offset, Object[] args) throws Throwable {
		int    propId   = obj.shape.getPropertyId(offset);
		String propName = propId >= 0 ? SymbolTable.name(propId) : "method";
		Object member   = obj.get(propName);
		if (member instanceof JSFunction fn) {
			return fn.call(null, obj, args != null ? args : new Object[0]);
		}
		// 前面知道obj != null
		throw new RuntimeException("TypeError: " + obj.toString() + "." + propName + " is not a function");
	}

	public static Object callOwnMethod0(int offset, Object target) throws Throwable {
		JSObject obj = (JSObject) target;
		Object   raw = (offset < 8) ? obj.getRawObjectSlot(offset) : obj.getSlot(offset);
		if (raw instanceof JSFunction fn) {
			return fn.call0(null, obj);
		}
		return slowOwnMethod(obj, offset, null);
	}

	public static Object callOwnMethod1(int offset, Object target, Object a0) throws Throwable {
		JSObject obj = (JSObject) target;
		Object   raw = (offset < 8) ? obj.getRawObjectSlot(offset) : obj.getSlot(offset);
		if (raw instanceof JSFunction fn) {
			return fn.call1(null, obj, a0);
		}
		return slowOwnMethod(obj, offset, new Object[]{a0});
	}

	public static Object callOwnMethod2(int offset, Object target, Object a0, Object a1) throws Throwable {
		JSObject obj = (JSObject) target;
		Object   raw = (offset < 8) ? obj.getRawObjectSlot(offset) : obj.getSlot(offset);
		if (raw instanceof JSFunction fn) {
			return fn.call2(null, obj, a0, a1);
		}
		return slowOwnMethod(obj, offset, new Object[]{a0, a1});
	}

	public static Object callOwnMethod3(int offset, Object target, Object a0, Object a1, Object a2) throws Throwable {
		JSObject obj = (JSObject) target;
		Object   raw = (offset < 8) ? obj.getRawObjectSlot(offset) : obj.getSlot(offset);
		if (raw instanceof JSFunction fn) {
			return fn.call3(null, obj, a0, a1, a2);
		}
		return slowOwnMethod(obj, offset, new Object[]{a0, a1, a2});
	}

	public static Object callOwnMethod4(int offset, Object target, Object a0, Object a1, Object a2, Object a3)
	 throws Throwable {
		JSObject obj = (JSObject) target;
		Object   raw = (offset < 8) ? obj.getRawObjectSlot(offset) : obj.getSlot(offset);
		if (raw instanceof JSFunction fn) {
			return fn.call4(null, obj, a0, a1, a2, a3);
		}
		return slowOwnMethod(obj, offset, new Object[]{a0, a1, a2, a3});
	}

	public static Object callOwnMethodN(int offset, Object target, Object[] args) throws Throwable {
		JSObject obj = (JSObject) target;
		Object   raw = (offset < 8) ? obj.getRawObjectSlot(offset) : obj.getSlot(offset);
		if (raw instanceof JSFunction fn) {
			return fn.call(null, obj, args);
		}
		return slowOwnMethod(obj, offset, args);
	}

	public static double invokeDoubleFallback(ChainedCallSite site, Object target, Object[] args, String methodName)
	 throws Throwable {
		if (target == null || target == JSUndefined.INSTANCE) {
			throw new NullPointerException("Cannot invoke method '" + methodName + "' on null/undefined");
		}

		if (target instanceof JSObject jsObj) {
			Object member = jsObj.get(methodName);
			if (member instanceof JSFunction func) {
				int      arity     = args.length;
				int      ownOffset = jsObj.shape.getOffset(methodName);
				JSObject proto     = (ownOffset < 0) ? jsObj.getPrototype() : null;

				// 构造守卫条件 (精确 Shape 或 Shape+Proto)
				MethodHandle test;
				if (ownOffset < 0) {
					boolean hasProtoKeyedShape = (jsObj.shape != JSShape.ROOT);
					test = hasProtoKeyedShape
					 ? MH_IS_EXACT_SHAPE.bindTo(jsObj.shape)
					 : (proto != null ? MH_IS_EXACT_SHAPE_AND_PROTO.bindTo(jsObj.shape).bindTo(proto) : null);
				} else {
					test = (site.getChainDepth() == 0) ? MH_IS_SAME_OBJECT.bindTo(jsObj) : MH_IS_EXACT_SHAPE.bindTo(jsObj.shape);
				}

				if (test != null) {
					if (site.type().parameterCount() > 1) {
						test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
					}

					JSContext    cx        = JSContext.current();
					MethodHandle exactCall = getDirectFuncDoubleMH(func, arity, cx);

					// MethodHandles.explicitCastArguments 能够自动处理 Object 到 double 的转换
					exactCall = MethodHandles.explicitCastArguments(exactCall, site.type());

					// 处理原型链上的 SwitchPoint 保护
					if (ownOffset < 0 && proto != null) {
						JSObject       current = proto;
						JSObject       holder  = null;
						List<JSObject> chain   = null;
						while (current != null) {
							int mOff = current.shape.getOffset(methodName);
							if (mOff >= 0 && (current.isDoubleSlot(mOff) || current.getRawObjectSlot(mOff) != JSObject.NOT_FOUND)) {
								holder = current;
								break;
							}
							if (chain == null) chain = new ArrayList<>(2);
							chain.add(current);
							current = current.getPrototype();
						}

						if (holder != null) {
							List<SwitchPoint> allSps = new ArrayList<>(chain != null ? chain.size() + 1 : 1);
							if (chain != null) {
								for (JSObject p : chain) {
									allSps.add(p.getOrCreateProtoSwitchPoint());
								}
							}
							SwitchPoint holderSp = holder.getOrCreateProtoSwitchPoint();
							allSps.add(holderSp);
							site.installProtoGuard(holderSp, allSps, test, exactCall);
						} else {
							site.installGuardOrSwitchMegamorphic(test, exactCall);
						}
					} else {
						SwitchPoint sp = jsObj.getOrCreateProtoSwitchPoint();
						site.installProtoGuard(sp, test, exactCall);
					}
				}

				// 本次 Fallback 的即时执行
				return executeDirectDoubleCall(func, jsObj, args);
			}
		}

		return invokeDoubleGeneric(target, args, methodName);
	}

	// 辅助方法：即时执行
	private static double executeDirectDoubleCall(JSFunction func, Object thisObj, Object[] args) throws Throwable {
		JSContext cx = JSContext.current();
		if (args.length == 0) {
			return func.call0Double(cx, thisObj);
		}
		return JSOps.toDouble(func.call(cx, thisObj, args));
	}

	private static MethodHandle getDirectFuncMH(JSFunction func, int arity) {
		try {
			Class<?> clazz = func.getClass();
			return switch (arity) {
				case 0 ->
				 LOOKUP.findVirtual(clazz, "call0", MethodType.methodType(Object.class, JSContext.class, Object.class)).bindTo(func).bindTo(null);
				case 1 ->
				 LOOKUP.findVirtual(clazz, "call1", MethodType.methodType(Object.class, JSContext.class, Object.class, Object.class)).bindTo(func).bindTo(null);
				case 2 ->
				 LOOKUP.findVirtual(clazz, "call2", MethodType.methodType(Object.class, JSContext.class, Object.class, Object.class, Object.class)).bindTo(func).bindTo(null);
				case 3 ->
				 LOOKUP.findVirtual(clazz, "call3", MethodType.methodType(Object.class, JSContext.class, Object.class, Object.class, Object.class, Object.class)).bindTo(func).bindTo(null);
				case 4 ->
				 LOOKUP.findVirtual(clazz, "call4", MethodType.methodType(Object.class, JSContext.class, Object.class, Object.class, Object.class, Object.class, Object.class)).bindTo(func).bindTo(null);
				default ->
				 LOOKUP.findVirtual(clazz, "call", MethodType.methodType(Object.class, JSContext.class, Object.class, Object[].class)).bindTo(func).bindTo(null).asCollector(1, Object[].class, arity);
			};
		} catch (Throwable ignored) {
			return switch (arity) {
				case 0 -> JSFuncMH.CALL0_NULL.bindTo(func);
				case 1 -> JSFuncMH.CALL1_NULL.bindTo(func);
				case 2 -> JSFuncMH.CALL2_NULL.bindTo(func);
				case 3 -> JSFuncMH.CALL3_NULL.bindTo(func);
				case 4 -> JSFuncMH.CALL4_NULL.bindTo(func);
				default -> JSFuncMH.CALL_NULL.bindTo(func).asCollector(1, Object[].class, arity);
			};
		}
	}

	private static MethodHandle getDirectFuncDoubleMH(JSFunction func, int arity, JSContext cx) {
		try {
			Class<?> clazz = func.getClass();
			return switch (arity) {
				case 0 ->
				 LOOKUP.findVirtual(clazz, "call0Double", MethodType.methodType(double.class, JSContext.class, Object.class)).bindTo(func).bindTo(cx);
				case 1 ->
				 LOOKUP.findVirtual(clazz, "call1Double", MethodType.methodType(double.class, JSContext.class, Object.class, double.class)).bindTo(func).bindTo(cx);
				case 2 ->
				 LOOKUP.findVirtual(clazz, "call2Double", MethodType.methodType(double.class, JSContext.class, Object.class, double.class, double.class)).bindTo(func).bindTo(cx);
				case 3 ->
				 LOOKUP.findVirtual(clazz, "call3Double", MethodType.methodType(double.class, JSContext.class, Object.class, double.class, double.class, double.class)).bindTo(func).bindTo(cx);
				case 4 ->
				 LOOKUP.findVirtual(clazz, "call4Double", MethodType.methodType(double.class, JSContext.class, Object.class, double.class, double.class, double.class, double.class)).bindTo(func).bindTo(cx);
				default ->
				 MethodHandles.filterReturnValue(LOOKUP.findVirtual(clazz, "call", MethodType.methodType(Object.class, JSContext.class, Object.class, Object[].class)).bindTo(func).bindTo(cx).asCollector(1, Object[].class, arity), MH_TO_DOUBLE);
			};
		} catch (Throwable ignored) {
			MethodHandle exact = switch (arity) {
				case 0 -> JSFuncMH.CALL0_DOUBLE.bindTo(func);
				case 1 -> JSFuncMH.CALL1_DOUBLE.bindTo(func);
				case 2 -> JSFuncMH.CALL2_DOUBLE.bindTo(func);
				case 3 -> JSFuncMH.CALL3_DOUBLE.bindTo(func);
				case 4 -> JSFuncMH.CALL4_DOUBLE.bindTo(func);
				default ->
				 MethodHandles.filterReturnValue(JSFuncMH.CALL_NULL.bindTo(func).asCollector(1, Object[].class, arity), MH_TO_DOUBLE);
			};
			return exact.bindTo(cx);
		}
	}

	/** 返回第一次调用结果，并设置site的target */
	public static Object invokeFallback(ChainedCallSite site, Object target, Object[] args, String methodName)
	 throws Throwable {
		if (target == null || target == JSUndefined.INSTANCE) {
			throw new NullPointerException("Cannot invoke method '" + methodName + "' on null/undefined");
		}

		if (target instanceof JSFunction func && "$invoke$".equals(methodName)) {
			int arity = args.length;
			MethodHandle directMh = switch (arity) {
				case 0 -> JSFuncMH.CALL0_UNDEFINED;
				case 1 -> JSFuncMH.CALL1_UNDEFINED;
				case 2 -> JSFuncMH.CALL2_UNDEFINED;
				case 3 -> JSFuncMH.CALL3_UNDEFINED;
				case 4 -> JSFuncMH.CALL4_UNDEFINED;
				default -> JSFuncMH.CALL_UNDEFINED.asCollector(1, Object[].class, arity);
			};

			if (site.getChainDepth() == 0) {
				MethodHandle test = MH_IS_SAME_OBJECT.bindTo(target);
				if (site.type().parameterCount() > 1) {
					test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
				}
				MethodHandle monomorphicTarget = MethodHandles.dropArguments(directMh.bindTo(func), 0, Object.class);
				site.installGuardOrSwitchMegamorphic(test, monomorphicTarget.asType(site.type()));
			} else {
				MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
				if (site.type().parameterCount() > 1) {
					test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
				}
				site.installGuardOrSwitchMegamorphic(test, directMh.asType(site.type()));
			}

			JSContext cx = JSContext.current();
			return switch (arity) {
				case 0 -> func.call0(cx, JSUndefined.INSTANCE);
				case 1 -> func.call1(cx, JSUndefined.INSTANCE, args[0]);
				case 2 -> func.call2(cx, JSUndefined.INSTANCE, args[0], args[1]);
				case 3 -> func.call3(cx, JSUndefined.INSTANCE, args[0], args[1], args[2]);
				case 4 -> func.call4(cx, JSUndefined.INSTANCE, args[0], args[1], args[2], args[3]);
				default -> func.call(cx, JSUndefined.INSTANCE, args);
			};
		}

		if (target instanceof Class<?> clazz && clazz.isInterface() && "$invoke$".equals(methodName)) {
			int arity = args.length;
			if (arity == 1) {
				MethodHandle test = MH_IS_SAME_OBJECT.bindTo(clazz);
				if (site.type().parameterCount() > 1) {
					test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
				}
				try {
					site.installJavaGuard(clazz, test, MH_INVOKE_INTERFACE_1.asType(site.type()));
				} catch (Throwable ignored) { }
				return invokeInterfaceAdapter1(clazz, args[0]);
			}
			throw new NoSuchMethodException("Interface " + clazz.getName() + " cannot be invoked with " + arity + " args");
		}

		if (target instanceof JSFunction func && "call".equals(methodName)) {
			int arity = args.length;
			MethodHandle directMh = switch (arity) {
				case 0 -> JSFuncMH.CALL0_UNDEFINED;
				case 1 -> JSFuncMH.CALL0_NULL;
				case 2 -> JSFuncMH.CALL1_NULL;
				case 3 -> JSFuncMH.CALL2_NULL;
				case 4 -> JSFuncMH.CALL3_NULL;
				case 5 -> JSFuncMH.CALL4_NULL;
				default -> JSFuncMH.CALL_NULL.asCollector(2, Object[].class, arity - 1);
			};

			if (site.getChainDepth() == 0) {
				MethodHandle test = MH_IS_SAME_OBJECT.bindTo(target);
				if (site.type().parameterCount() > 1) {
					test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
				}
				MethodHandle monomorphicTarget = MethodHandles.dropArguments(directMh.bindTo(func), 0, Object.class);
				site.installGuardOrSwitchMegamorphic(test, monomorphicTarget.asType(site.type()));
			} else {
				MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
				if (site.type().parameterCount() > 1) {
					test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
				}
				site.installGuardOrSwitchMegamorphic(test, directMh.asType(site.type()));
			}

			JSContext cx      = JSContext.current();
			Object    thisArg = arity > 0 && args[0] != null ? args[0] : JSUndefined.INSTANCE;
			return switch (arity) {
				case 0, 1 -> func.call0(cx, thisArg);
				case 2 -> func.call1(cx, thisArg, args[1]);
				case 3 -> func.call2(cx, thisArg, args[1], args[2]);
				case 4 -> func.call3(cx, thisArg, args[1], args[2], args[3]);
				case 5 -> func.call4(cx, thisArg, args[1], args[2], args[3], args[4]);
				default -> func.call(cx, thisArg, Arrays.copyOfRange(args, 1, arity));
			};
		}

		if (target instanceof JSObject jsObj) {
			Object member = jsObj.get(methodName);
			if (member instanceof JSFunction func) {
				int ownOffset = jsObj.shape.getOffset(methodName);
				// 当方法不在自身槽位上（offset < 0，即来自原型链），或为内置单例对象（如 JSObjectConstructor / JSArrayConstructor / Math / console 等）时，函数实例恒定，方可绑定常量
				boolean isProtectedSingleton = (jsObj.getProtoSwitchPoint() != null
				                                || jsObj.isArrayPrototype()
				                                || jsObj instanceof JSContext.JSObjectConstructor
				                                || jsObj instanceof JSContext.JSArrayConstructor
				                                || jsObj == JSContext.LazyMath.MATH
				                                || jsObj == JSContext.LazyConsole.CONSOLE
				                                || jsObj == JSContext.LazyReflect.REFLECT
				                                || jsObj == JSContext.LazyMisc.JAVA
				                                || jsObj == JSContext.LazyMisc.PRINT);
				if (ownOffset < 0 || isProtectedSingleton) {
					MethodHandle test;
					JSObject     proto = (ownOffset < 0) ? jsObj.getPrototype() : null;
					if (ownOffset < 0) {
						// 若实例持有 proto-keyed 专属 shape（由 new Foo() 分配），则 shape 已唯一标识原型链，
						// 无需再做 getPrototype() 比较（protoSwitchPoint 负责失效保护）。
						// 否则（如 {} 对象 shape = ROOT），多个不同 prototype 共享同一 shape，
						// 必须保留 proto 比较。
						boolean hasProtoKeyedShape = (jsObj.shape != JSShape.ROOT);
						if (hasProtoKeyedShape) {
							test = MH_IS_EXACT_SHAPE.bindTo(jsObj.shape);
						} else {
							test = (proto != null) ? MH_IS_EXACT_SHAPE_AND_PROTO.bindTo(jsObj.shape).bindTo(proto) : null;
						}
					} else {
						test = (site.getChainDepth() == 0) ? MH_IS_SAME_OBJECT.bindTo(jsObj) : MH_IS_EXACT_SHAPE.bindTo(jsObj.shape);
					}
					if (test != null) {
						if (site.type().parameterCount() > 1) {
							test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
						}
						int          arity         = args.length;
						MethodHandle exactFuncCall = null;
						if (jsObj instanceof JSArray && ownOffset < 0 && BuiltinProtector.isArrayProtoValid()) {
							if ("push".equals(methodName)) {
								if (arity == 0) {
									exactFuncCall = MH_JS_ARRAY_FAST_PUSH0;
								} else if (arity == 1) {
									exactFuncCall = MH_JS_ARRAY_FAST_PUSH1;
								} else if (arity == 2) {
									exactFuncCall = MH_JS_ARRAY_FAST_PUSH2;
								}
							} else if ("pop".equals(methodName) && arity == 0) {
								exactFuncCall = MH_JS_ARRAY_FAST_POP0;
							}
						}
						if (exactFuncCall == null) {
							exactFuncCall = getDirectFuncMH(func, arity);
						}
						if (ownOffset < 0 && proto != null) {
							JSObject       current = proto;
							JSObject       holder  = null;
							List<JSObject> chain   = null;
							while (current != null) {
								int mOff = current.shape.getOffset(methodName);
								if (mOff >= 0 && (current.isDoubleSlot(mOff) || current.getRawObjectSlot(mOff) != JSObject.NOT_FOUND)) {
									holder = current;
									break;
								}
								if (chain == null) chain = new ArrayList<>(2);
								chain.add(current);
								current = current.getPrototype();
							}
							if (holder != null) {
								List<SwitchPoint> allSps = new ArrayList<>(chain != null ? chain.size() + 1 : 1);
								if (chain != null) {
									for (JSObject p : chain) {
										allSps.add(p.getOrCreateProtoSwitchPoint());
									}
								}
								SwitchPoint holderSp = holder.getOrCreateProtoSwitchPoint();
								allSps.add(holderSp);
								site.installProtoGuard(holderSp, allSps, test, exactFuncCall);
							} else {
								site.installGuardOrSwitchMegamorphic(test, exactFuncCall.asType(site.type()));
							}
						} else {
							SwitchPoint sp = jsObj.getOrCreateProtoSwitchPoint();
							site.installProtoGuard(sp, test, exactFuncCall.asType(site.type()));
						}
					}
				} else if (/* ownOffset >= 0 &&  */(jsObj.shape.getSlotType(ownOffset) & JSShape.FLAG_ACCESSOR) == 0 && site.getChainDepth() < 3) {
					int arity = args.length;
					MethodHandle callMh = switch (arity) {
						case 0 -> MethodHandles.insertArguments(MH_CALL_OWN_METHOD0, 0, ownOffset);
						case 1 -> MethodHandles.insertArguments(MH_CALL_OWN_METHOD1, 0, ownOffset);
						case 2 -> MethodHandles.insertArguments(MH_CALL_OWN_METHOD2, 0, ownOffset);
						case 3 -> MethodHandles.insertArguments(MH_CALL_OWN_METHOD3, 0, ownOffset);
						case 4 -> MethodHandles.insertArguments(MH_CALL_OWN_METHOD4, 0, ownOffset);
						default -> MethodHandles.insertArguments(MH_CALL_OWN_METHOD_N, 0, ownOffset)
						 .asCollector(1, Object[].class, arity);
					};
					MethodHandle test = MH_IS_EXACT_SHAPE.bindTo(jsObj.shape);
					if (site.type().parameterCount() > 1) {
						test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
					}
					site.installGuardOrSwitchMegamorphic(test, callMh.asType(site.type()));
				}
				if (jsObj instanceof JSArray jsArr && ownOffset < 0 && BuiltinProtector.isArrayProtoValid()) {
					if ("push".equals(methodName)) {
						if (args.length == 0) return jsArrayFastPush0(jsArr);
						if (args.length == 1) return jsArrayFastPush1(jsArr, args[0]);
						if (args.length == 2) return jsArrayFastPush2(jsArr, args[0], args[1]);
					} else if ("pop".equals(methodName) && args.length == 0) {
						return jsArrayFastPop0(jsArr);
					}
				}
				int arity = args.length;
				return switch (arity) {
					case 0 -> func.call0(null, jsObj);
					case 1 -> func.call1(null, jsObj, args[0]);
					case 2 -> func.call2(null, jsObj, args[0], args[1]);
					case 3 -> func.call3(null, jsObj, args[0], args[1], args[2]);
					case 4 -> func.call4(null, jsObj, args[0], args[1], args[2], args[3]);
					default -> func.call(null, jsObj, args);
				};
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

		return invokeFallbackSlow(site, target, args, methodName);
	}

	public static Object invokeFallbackSlow(ChainedCallSite site, Object target, Object[] args, String methodName)
	 throws Throwable {
		if (target instanceof CharSequence seq) {
			Object strRes = JSJavaInterop.invokeStringMethod(seq.toString(), methodName, args);
			if (strRes != null || "search".equals(methodName) || "match".equals(methodName)) {
				return strRes;
			}
			Object member = JSContext.LazyPrimitiveConstructors.STRING_PROTOTYPE.get(methodName);
			if (member instanceof JSFunction func) {
				return func.call(null, target, args);
			}
		}

		if (target instanceof Number) {
			Object member = JSContext.LazyPrimitiveConstructors.NUMBER_PROTOTYPE.get(methodName);
			if (member instanceof JSFunction func) {
				return func.call(null, target, args);
			}
		}

		if (target instanceof Boolean) {
			Object member = JSContext.LazyPrimitiveConstructors.BOOLEAN_PROTOTYPE.get(methodName);
			if (member instanceof JSFunction func) {
				return func.call(null, target, args);
			}
		}


		Class<?> clazz    = (target instanceof Class<?>) ? (Class<?>) target : target.getClass();
		boolean  isStatic = (target instanceof Class<?>);

		if (isStatic && clazz.isInterface() && "$invoke$".equals(methodName)) {
			if (args.length == 1) {
				return invokeInterfaceAdapter1(clazz, args[0]);
			}
			throw new NoSuchMethodException("Interface " + clazz.getName() + " cannot be invoked with " + args.length + " args");
		}

		// 查找最匹配的重载方法
		Method targetMethod = MethodResolver.findBestMatchingMethod(clazz, methodName, args);

		if (targetMethod != null) {
			try {
				targetMethod.setAccessible(true);
			} catch (Throwable ignored) {
			}

			int sameArityCandidates = 0;
			for (Method m : MethodResolver.findCandidateMethods(clazz, methodName)) {
				if (m.getParameterCount() == args.length && Modifier.isStatic(m.getModifiers()) == isStatic) {
					sameArityCandidates++;
				}
			}

			MethodHandle test;
			if (isStatic) {
				if (sameArityCandidates > 1 && args.length > 0) {
					Class<?>[] argClasses = new Class<?>[args.length];
					for (int i = 0; i < args.length; i++) {
						argClasses[i] = (args[i] == null) ? null : args[i].getClass();
					}
					test = MH_IS_SAME_OBJECT_AND_ARGS.bindTo(clazz).bindTo(argClasses)
					 .asCollector(1, Object[].class, site.type().parameterCount() - 1);
				} else {
					test = MH_IS_SAME_OBJECT.bindTo(clazz);
					if (site.type().parameterCount() > 1) {
						test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
					}
				}
			} else {
				if (sameArityCandidates > 1 && args.length > 0) {
					Class<?>[] argClasses = new Class<?>[args.length];
					for (int i = 0; i < args.length; i++) {
						argClasses[i] = (args[i] == null) ? null : args[i].getClass();
					}
					test = MH_IS_EXACT_CLASS_AND_ARGS.bindTo(clazz).bindTo(argClasses)
					 .asCollector(1, Object[].class, site.type().parameterCount() - 1);
				} else {
					test = MH_IS_EXACT_CLASS.bindTo(clazz);
					if (site.type().parameterCount() > 1) {
						test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
					}
				}
			}

			if (targetMethod.isVarArgs()) {
				try {
					MethodHandle mh              = Magic.lookup.unreflect(targetMethod);
					MethodHandle adapted         = isStatic ? MethodHandles.dropArguments(mh, 0, Object.class) : mh;
					int          paramCount      = targetMethod.getParameterCount();
					Class<?>     varargArrayType = targetMethod.getParameterTypes()[paramCount - 1];
					int          argOffset       = 1; // index 0 is receiver or dropped target
					int          varargCount     = args.length - (paramCount - 1);
					if (varargCount >= 0) {
						MethodHandle collector = adapted.asCollector(argOffset + paramCount - 1, varargArrayType, varargCount);
						if (targetMethod.getReturnType() == void.class) {
							collector = MethodHandles.filterReturnValue(collector, MethodHandles.constant(Object.class, JSUndefined.INSTANCE));
						}
						MethodHandle genericMh = collector.asType(site.type());
						site.installJavaGuard(clazz, test, genericMh);
						MethodHandle spreader = genericMh.asSpreader(Object[].class, args.length);
						return spreader.invokeExact(target, args);
					}
				} catch (Throwable ignored) {
				}
				return JSJavaInterop.invokeMatchedMethod(target, targetMethod, args, clazz, methodName);
			}

			boolean preferMagicAccessor = (STRATEGY != InvocationStrategy.SPREADER);

			if (preferMagicAccessor) {
				try {
					MethodHandle exactMh = MagicJIT.createExactMethodStub(clazz, targetMethod);
					if (exactMh != null) {
						MethodHandle genericMh = exactMh.asType(site.type());
						site.installJavaGuard(clazz, test, genericMh);

						MethodHandle spreader = genericMh.asSpreader(Object[].class, args.length);
						return spreader.invokeExact(target, args);
					}
				} catch (Throwable ignored) {
				}
			}

			try {
				MethodHandle mh      = Magic.lookup.unreflect(targetMethod);
				MethodHandle adapted = isStatic ? MethodHandles.dropArguments(mh, 0, Object.class) : mh;

				Class<?>[] paramTypes = targetMethod.getParameterTypes();
				int        argOffset  = 1; // index 0 is receiver or dropped target
				for (int i = 0; i < paramTypes.length; i++) {
					Class<?>     pType  = paramTypes[i];
					MethodHandle filter = getArgumentFilter(pType);
					if (filter != null) {
						adapted = MethodHandles.filterArguments(adapted, argOffset + i, filter);
					}
				}

				if (targetMethod.getReturnType() == void.class) {
					adapted = MethodHandles.filterReturnValue(adapted, MethodHandles.constant(Object.class, JSUndefined.INSTANCE));
				}

				MethodHandle genericMh = adapted.asType(site.type());
				site.installJavaGuard(clazz, test, genericMh);

				// 首次调用使用 asSpreader 极速展开
				MethodHandle spreader = genericMh.asSpreader(Object[].class, args.length);
				return spreader.invokeExact(target, args);
			} catch (Throwable ignored) {
			}
		}

		return invokeGeneric(target, args, methodName);
	}

	public static boolean hasComplexParameters(Method method) {
		return JSJavaInterop.hasComplexParameters(method);
	}

	public static MethodHandle getArgumentFilter(Class<?> targetType) {
		return JSJavaInterop.getArgumentFilter(targetType);
	}

	public static JSObject getPrototypeFromConstructor(JSContext cx, Object constructor, String intrinsicDefaultProto) {
		return JSJavaInterop.getPrototypeFromConstructor(cx, constructor, intrinsicDefaultProto);
	}

	public static Object newGeneric(Object ctor, Object[] args) throws Throwable {
		return JSJavaInterop.newGeneric(ctor, args);
	}

	public static Object newGeneric(Object ctor, Object[] args, Object newTarget) throws Throwable {
		return JSJavaInterop.newGeneric(ctor, args, newTarget);
	}

	public static boolean isConstructor(Object ctor) {
		return JSJavaInterop.isConstructor(ctor);
	}

	public static boolean isSameObject(Object expected, Object actual) {
		return expected == actual;
	}

	public static void initUserFunction(JSObject func, String name, int length) {
		try {
			func.setPrototype(JSContext.LazyFunction.FUNCTION_PROTOTYPE);
		} catch (Throwable ignored) { }
		if (func.realm == null) {
			func.realm = JSContext.current();
		}
		func.put("name", name != null ? name : "");
		func.put("length", length);
		JSObject proto = new JSObject();
		proto.put("constructor", func);
		func.put("prototype", proto);
	}

	public static JSContext.JSArguments createArguments(JSFunction callee, Object[] args) {
		return new JSContext.JSArguments(callee, args);
	}

	public static JSObject createScope(JSObject parentScope) {
		return new JSObject(parentScope);
	}

	public static Object getScopeOrGlobal(JSObject scope, JSContext cx, String name, int slot) {
		if (scope != null && scope.has(name)) {
			return scope.get(name);
		}
		if (BuiltinProtector.isGlobalSlotValid(slot)) {
			Object constant = BuiltinProtector.getGlobalConstant(slot);
			if (constant != null) {
				return constant;
			}
		}
		if (cx != null) {
			return cx.getSlotFast(slot);
		}
		JSContext current = JSContext.current();
		if (current != null) {
			return current.getSlotFast(slot);
		}
		return JSUndefined.INSTANCE;
	}

	public static void setScopeOrGlobal(JSObject scope, JSContext cx, String name, int slot, Object value) {
		if (scope != null && scope.has(name)) {
			scope.setScopeVar(name, value);
		} else if (cx != null) {
			cx.setSlot(slot, value);
		} else {
			JSContext current = JSContext.current();
			if (current != null) {
				current.setSlot(slot, value);
			}
		}
	}

	@FunctionalInterface
	public interface AsyncAction {
		Object run() throws Throwable;
	}

	@FunctionalInterface
	public interface AsyncJSFunction {
		Object callAsync(JSContext cx, Object thisObj, Object[] args) throws Throwable;
	}

	public static JSPromise startAsync(JSContext cx, AsyncAction action) throws Throwable {
		JSPromise               returnPromise      = new JSPromise(cx);
		CompletableFuture<Void> firstSuspendOrDone = new CompletableFuture<>();
		AsyncExecutionState     state              = new AsyncExecutionState(cx, returnPromise, firstSuspendOrDone);

		Thread.ofVirtual().name("MagicJS-Async").start(() -> {
			JSContext.CURRENT.set(cx);
			AsyncExecutionState.CURRENT.set(state);
			try {
				Object res = action.run();
				returnPromise.resolve(res);
			} catch (Throwable t) {
				Object reason = t instanceof JSOps.JSException je ? je.value : t;
				returnPromise.reject(reason);
			} finally {
				JSContext.CURRENT.remove();
				AsyncExecutionState.CURRENT.remove();
				state.onDone();
			}
		});

		firstSuspendOrDone.join();
		return returnPromise;
	}

	public static JSPromise runAsync(AsyncJSFunction target, JSContext cx, Object thisObj, Object[] args)
	 throws Throwable {
		JSPromise               returnPromise      = new JSPromise(cx);
		CompletableFuture<Void> firstSuspendOrDone = new CompletableFuture<>();
		AsyncExecutionState     state              = new AsyncExecutionState(cx, returnPromise, firstSuspendOrDone);

		Thread.ofVirtual().name("MagicJS-Async").start(() -> {
			JSContext.CURRENT.set(cx);
			AsyncExecutionState.CURRENT.set(state);
			try {
				Object res = target.callAsync(cx, thisObj, args);
				returnPromise.resolve(res);
			} catch (Throwable t) {
				Object reason = t instanceof JSOps.JSException je ? je.value : t;
				returnPromise.reject(reason);
			} finally {
				JSContext.CURRENT.remove();
				AsyncExecutionState.CURRENT.remove();
				state.onDone();
			}
		});

		firstSuspendOrDone.join();
		return returnPromise;
	}

	public static void transitionSetDouble(JSShape newShape, int slot, Object target, double val) {
		JSObject obj = (JSObject) target;
		obj.shape = newShape;
		obj.setDoubleSlot(slot, val);
	}

	public static void transitionSetObject(JSShape newShape, int slot, Object target, Object val) {
		JSObject obj = (JSObject) target;
		obj.shape = newShape;
		obj.setSlot(slot, val);
	}

	public static void transitionSetObjectDouble(JSShape newShape, int slot, Object target, Object val) {
		JSObject obj = (JSObject) target;
		obj.shape = newShape;
		obj.setDoubleSlot(slot, JSOps.toDouble(val));
	}

	public static Object newJSFunction0(JSFunction ctor, JSObject cachedProto) throws Throwable {
		JSObject newObj = (cachedProto != null) ? new JSObject(cachedProto.getOrCreateInstanceInitShape(), cachedProto) : new JSObject();
		Object   res    = ctor.call0(null, newObj);
		if (res instanceof JSBridgedObject || (res != null && res != JSUndefined.INSTANCE && !(res instanceof Number || res instanceof Boolean || res instanceof String || res instanceof Character))) {
			return res;
		}
		return newObj;
	}

	public static Object newJSFunction1(JSFunction ctor, Object a0, JSObject cachedProto) throws Throwable {
		JSObject newObj = (cachedProto != null) ? new JSObject(cachedProto.getOrCreateInstanceInitShape(), cachedProto) : new JSObject();
		Object   res    = ctor.call1(null, newObj, a0);
		if (res instanceof JSBridgedObject || (res != null && res != JSUndefined.INSTANCE && !(res instanceof Number || res instanceof Boolean || res instanceof String || res instanceof Character))) {
			return res;
		}
		return newObj;
	}

	public static Object newJSFunction2(JSFunction ctor, Object a0, Object a1, JSObject cachedProto) throws Throwable {
		JSObject newObj = (cachedProto != null) ? new JSObject(cachedProto.getOrCreateInstanceInitShape(), cachedProto) : new JSObject();
		Object   res    = ctor.call2(null, newObj, a0, a1);
		if (res instanceof JSBridgedObject || (res != null && res != JSUndefined.INSTANCE && !(res instanceof Number || res instanceof Boolean || res instanceof String || res instanceof Character))) {
			return res;
		}
		return newObj;
	}

	public static Object newJSFunction3(JSFunction ctor, Object a0, Object a1, Object a2, JSObject cachedProto)
	 throws Throwable {
		JSObject newObj = (cachedProto != null) ? new JSObject(cachedProto.getOrCreateInstanceInitShape(), cachedProto) : new JSObject();
		Object   res    = ctor.call3(null, newObj, a0, a1, a2);
		if (res instanceof JSBridgedObject || (res != null && res != JSUndefined.INSTANCE && !(res instanceof Number || res instanceof Boolean || res instanceof String || res instanceof Character))) {
			return res;
		}
		return newObj;
	}

	public static Object newJSFunction4(JSFunction ctor, Object a0, Object a1, Object a2, Object a3, JSObject cachedProto)
	 throws Throwable {
		JSObject newObj = (cachedProto != null) ? new JSObject(cachedProto.getOrCreateInstanceInitShape(), cachedProto) : new JSObject();
		Object   res    = ctor.call4(null, newObj, a0, a1, a2, a3);
		if (res instanceof JSBridgedObject || (res != null && res != JSUndefined.INSTANCE && !(res instanceof Number || res instanceof Boolean || res instanceof String || res instanceof Character))) {
			return res;
		}
		return newObj;
	}

	public static Object newJSFunctionN(JSFunction ctor, Object[] args, JSObject cachedProto) throws Throwable {
		JSObject newObj = (cachedProto != null) ? new JSObject(cachedProto.getOrCreateInstanceInitShape(), cachedProto) : new JSObject();
		Object   res    = ctor.call(null, newObj, args);
		if (res instanceof JSBridgedObject || (res != null && res != JSUndefined.INSTANCE && !(res instanceof Number || res instanceof Boolean || res instanceof String || res instanceof Character))) {
			return res;
		}
		return newObj;
	}

	public static Object newArrayInstance0(Class<?> componentType) {
		return JSJavaInterop.newArrayInstance0(componentType);
	}

	public static Object newArrayInstance1(Class<?> componentType, Object lenOrInit) {
		return JSJavaInterop.newArrayInstance1(componentType, lenOrInit);
	}

	public static Object newArrayInstanceN(Class<?> componentType, Object[] args) {
		return JSJavaInterop.newArrayInstanceN(componentType, args);
	}

	public static Object invokeInterfaceAdapter1(Object target, Object arg) {
		return JSJavaInterop.invokeInterfaceAdapter1(target, arg);
	}

	public static Object newFallback(ChainedCallSite site, Object ctor, Object[] args) throws Throwable {
		int arity = args.length;
		if (ctor instanceof Class<?> clazz) {
			if (clazz.isArray()) {
				Class<?>     componentType = clazz.getComponentType();
				MethodHandle test          = MH_IS_SAME_OBJECT.bindTo(clazz);
				if (site.type().parameterCount() > 1) {
					test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
				}
				try {
					if (arity == 1) {
						site.installGuardOrSwitchMegamorphic(test, MH_NEW_ARRAY_1.bindTo(componentType).asType(site.type()));
					} else if (arity == 0) {
						site.installGuardOrSwitchMegamorphic(test, MH_NEW_ARRAY_0.bindTo(componentType).asType(site.type()));
					} else {
						site.installGuardOrSwitchMegamorphic(test, MH_NEW_ARRAY_N.bindTo(componentType).asCollector(1, Object[].class, arity).asType(site.type()));
					}
				} catch (Throwable ignored) { }

				if (arity == 1) {
					return newArrayInstance1(componentType, args[0]);
				} else if (arity == 0) {
					return newArrayInstance0(componentType);
				} else {
					return newArrayInstanceN(componentType, args);
				}
			}

			if (clazz.isInterface()) {
				if (arity == 1) {
					MethodHandle test = MH_IS_SAME_OBJECT.bindTo(clazz);
					if (site.type().parameterCount() > 1) {
						test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
					}
					try {
						site.installGuardOrSwitchMegamorphic(test, MH_INVOKE_INTERFACE_1.asType(site.type()));
					} catch (Throwable ignored) { }
					return invokeInterfaceAdapter1(clazz, args[0]);
				}
				throw new NoSuchMethodException("Interface " + clazz.getName() + " cannot be instantiated with " + arity + " arguments");
			}

			Constructor<?> targetCtor = MethodResolver.findBestMatchingConstructor(clazz, args);
			if (targetCtor != null) {
				targetCtor.setAccessible(true);
				int sameArityCandidates = 0;
				for (Constructor<?> c : MethodResolver.findCandidateConstructors(clazz)) {
					if (c.getParameterCount() == arity) sameArityCandidates++;
				}
				MethodHandle test;
				if (sameArityCandidates > 1 && arity > 0) {
					Class<?>[] argClasses = new Class<?>[arity];
					for (int i = 0; i < arity; i++) {
						argClasses[i] = (args[i] == null) ? null : args[i].getClass();
					}
					test = MH_IS_SAME_OBJECT_AND_ARGS.bindTo(clazz).bindTo(argClasses)
					 .asCollector(1, Object[].class, site.type().parameterCount() - 1);
				} else {
					test = MH_IS_SAME_OBJECT.bindTo(clazz);
					if (site.type().parameterCount() > 1) {
						test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
					}
				}

				if (targetCtor.isVarArgs()) {
					try {
						MethodHandle mh              = Magic.lookup.unreflectConstructor(targetCtor);
						int          paramCount      = targetCtor.getParameterCount();
						Class<?>     varargArrayType = targetCtor.getParameterTypes()[paramCount - 1];
						int          varargCount     = arity - (paramCount - 1);
						if (varargCount >= 0) {
							MethodHandle collector  = mh.asCollector(paramCount - 1, varargArrayType, varargCount);
							MethodHandle directCtor = MethodHandles.dropArguments(collector, 0, Object.class);
							site.installGuardOrSwitchMegamorphic(test, directCtor.asType(site.type()));
						}
					} catch (Throwable ignored) { }

					Object[] packed = packVarArgs(targetCtor.getParameterTypes(), args);
					return targetCtor.newInstance(packed);
				}

				try {
					MethodHandle directCtor = MagicJIT.createExactConstructorStub(clazz, targetCtor);
					if (directCtor != null) {
						site.installJavaGuard(clazz, test, directCtor.asType(site.type()));
					}
				} catch (Throwable ignored) { }

				if (STRATEGY != InvocationStrategy.SPREADER) {
					try {
						var ctorInvoker = MagicJIT.getConstructorInvoker(clazz, targetCtor);
						if (ctorInvoker != null) {
							switch (arity) {
								case 0:
									return ctorInvoker.newInstance0();
								case 1:
									return ctorInvoker.newInstance1(args[0]);
								case 2:
									return ctorInvoker.newInstance2(args[0], args[1]);
								case 3:
									return ctorInvoker.newInstance3(args[0], args[1], args[2]);
								default:
									return ctorInvoker.newInstance(args);
							}
						}
					} catch (Throwable ignored) {
					}
				}

				MethodHandle ctorSpreader = JSJavaInterop.getConstructorSpreader(clazz, targetCtor);
				if (ctorSpreader != null) {
					return ctorSpreader.invokeExact(args);
				}
				Object[]   casted = new Object[arity];
				Class<?>[] pTypes = targetCtor.getParameterTypes();
				for (int i = 0; i < arity; i++) {
					casted[i] = JSOps.castValue(args[i], pTypes[i]);
				}
				return targetCtor.newInstance(casted);
			}
			throw new NoSuchMethodException("No matching constructor for " + clazz.getName() + " with " + arity + " args");
		}

		if (ctor instanceof JSContext.JSBuiltinMethod bm) {
			throw JSContext.makeTypeError(bm.getMethodName() + " is not a constructor");
		}

		// 防止提前加载
		if (JSContext.lazyDateLoaded && ctor == JSContext.LazyDate.DATE) {
			return ((JSFunction) ctor).call(null, new JSContext.JSDate(0, JSContext.LazyDate.DATE_PROTOTYPE), args);
		}

		if (ctor instanceof JSFunction func) {
			Object   proto       = (ctor instanceof JSObject jsObj) ? jsObj.get("prototype") : JSUndefined.INSTANCE;
			JSObject cachedProto = (proto instanceof JSObject sp) ? sp : null;

			MethodHandle fastTarget = switch (arity) {
				case 0 -> MethodHandles.insertArguments(NewMH.NEW_JS_FUNC0, 1, cachedProto);
				case 1 -> MethodHandles.insertArguments(NewMH.NEW_JS_FUNC1, 2, cachedProto);
				case 2 -> MethodHandles.insertArguments(NewMH.NEW_JS_FUNC2, 3, cachedProto);
				case 3 -> MethodHandles.insertArguments(NewMH.NEW_JS_FUNC3, 4, cachedProto);
				case 4 -> MethodHandles.insertArguments(NewMH.NEW_JS_FUNC4, 5, cachedProto);
				default -> MethodHandles.insertArguments(NewMH.NEW_JS_FUNC_N, 2, cachedProto)
				 .asCollector(1, Object[].class, arity);
			};

			if (fastTarget != null) {
				MethodHandle test = MH_IS_SAME_OBJECT.bindTo(ctor);
				if (site.type().parameterCount() > 1) {
					test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
				}
				MethodHandle guarded = fastTarget.asType(site.type());
				if (ctor instanceof JSObject jsObj) {
					MethodHandle fb = site.getInitialFallback();
					if (fb != null) {
						guarded = jsObj.getOrCreateProtoSwitchPoint().guardWithTest(guarded, fb.asType(site.type()));
					}
				}
				site.installGuardOrSwitchMegamorphic(test, guarded);
			}

			JSObject newObj = (cachedProto != null) ? new JSObject(cachedProto.getOrCreateInstanceInitShape(), cachedProto) : new JSObject();
			Object res = switch (arity) {
				case 0 -> func.call0(null, newObj);
				case 1 -> func.call1(null, newObj, args[0]);
				case 2 -> func.call2(null, newObj, args[0], args[1]);
				case 3 -> func.call3(null, newObj, args[0], args[1], args[2]);
				case 4 -> func.call4(null, newObj, args[0], args[1], args[2], args[3]);
				default -> func.call(null, newObj, args);
			};

			if (res instanceof JSBridgedObject || (res != null && res != JSUndefined.INSTANCE && !(res instanceof Number || res instanceof Boolean || res instanceof String || res instanceof Character))) {
				return res;
			}
			return newObj;
		}

		return newGeneric(ctor, args);
	}

	public static Long toValidArrayLongIndex(Object index) {
		return JSIndexOps.toValidArrayLongIndex(index);
	}

	public static Integer toValidArrayIndex(Object index) {
		return JSIndexOps.toValidArrayIndex(index);
	}

	public static String toPropertyKey(Object index) {
		return JSOps.toPropertyKey(index);
	}

	public static Object getArrayElement(Object target, int idx) {
		return JSIndexOps.getArrayElement(target, idx);
	}

	public static void setArrayElement(Object target, int idx, Object value) {
		JSIndexOps.setArrayElement(target, idx, value);
	}

	public static final int SMALL_INT_SIZE = JSOps.SMALL_INT_SIZE;

	public static String fastIntToString(int i) {
		return JSOps.fastIntToString(i);
	}

	public static Object getIndex(Object target, int index) {
		return JSIndexOps.getIndex(target, index);
	}

	public static void setIndex(Object target, int index, Object value) {
		JSIndexOps.setIndex(target, index, value);
	}

	public static Object getIndex(Object target, Object index) {
		return JSIndexOps.getIndex(target, index);
	}

	public static void setIndex(Object target, Object index, Object value) {
		JSIndexOps.setIndex(target, index, value);
	}
	//endregion

	//region ֱ MethodHandle 

	public static boolean isExactClass(Class<?> expected, Object target) {
		return target != null && target.getClass() == expected;
	}
	public static boolean isExactClassAndArgs(Class<?> expected, Class<?>[] expectedArgs, Object target, Object[] args) {
		if (target == null || target.getClass() != expected) return false;
		if (args.length != expectedArgs.length) return false;
		for (int i = 0; i < expectedArgs.length; i++) {
			Class<?> exp = expectedArgs[i];
			Object   act = args[i];
			if (exp == null) {
				if (act != null) return false;
			} else {
				if (act == null || act.getClass() != exp) return false;
			}
		}
		return true;
	}
	public static boolean isSameObjectAndArgs(Object expected, Class<?>[] expectedArgs, Object target, Object[] args) {
		if (target != expected) return false;
		if (args.length != expectedArgs.length) return false;
		for (int i = 0; i < expectedArgs.length; i++) {
			Class<?> exp = expectedArgs[i];
			Object   act = args[i];
			if (exp == null) {
				if (act != null) return false;
			} else {
				if (act == null || act.getClass() != exp) return false;
			}
		}
		return true;
	}

	public static int getArrayLengthInt(Object target) {
		return JSIndexOps.getArrayLengthInt(target);
	}

	public static double getArrayLengthDouble(Object target) {
		return JSIndexOps.getArrayLengthDouble(target);
	}

	public static Object getIndexJSArray(Object target, Object index) {
		return JSIndexOps.getIndexJSArray(target, index);
	}

	public static Object getIndexList(Object target, Object index) {
		return JSIndexOps.getIndexList(target, index);
	}

	public static Object getIndexObjectArray(Object target, Object index) {
		return JSIndexOps.getIndexObjectArray(target, index);
	}

	public static Object getIndexPrimitiveArray(Object target, Object index) {
		return JSIndexOps.getIndexPrimitiveArray(target, index);
	}

	public static Object getIndexIntArray(Object target, Object index) {
		return JSIndexOps.getIndexIntArray(target, index);
	}

	public static Object getIndexDoubleArray(Object target, Object index) {
		return JSIndexOps.getIndexDoubleArray(target, index);
	}

	public static Object getIndexLongArray(Object target, Object index) {
		return JSIndexOps.getIndexLongArray(target, index);
	}

	public static Object getIndexMap(Object target, Object index) {
		return JSIndexOps.getIndexMap(target, index);
	}

	public static void setIndexJSArray(Object target, Object index, Object value) {
		JSIndexOps.setIndexJSArray(target, index, value);
	}

	public static void setIndexList(Object target, Object index, Object value) {
		JSIndexOps.setIndexList(target, index, value);
	}

	public static void setIndexObjectArray(Object target, Object index, Object value) {
		JSIndexOps.setIndexObjectArray(target, index, value);
	}

	public static void setIndexIntArray(Object target, Object index, Object value) {
		JSIndexOps.setIndexIntArray(target, index, value);
	}

	public static void setIndexDoubleArray(Object target, Object index, Object value) {
		JSIndexOps.setIndexDoubleArray(target, index, value);
	}

	public static void setIndexLongArray(Object target, Object index, Object value) {
		JSIndexOps.setIndexLongArray(target, index, value);
	}

	public static void setIndexPrimitiveArray(Object target, Object index, Object value) {
		JSIndexOps.setIndexPrimitiveArray(target, index, value);
	}

	public static void setIndexMap(Object target, Object index, Object value) {
		JSIndexOps.setIndexMap(target, index, value);
	}

	@SuppressWarnings("RedundantIfStatement")
	public static boolean isExactShape(JSShape expected, Object target) {
		if (target instanceof JSObject && ((JSObject) target).shape == expected) return true;
		return false;
	}
	public static boolean isExactShapeAndProto(JSShape expectedShape, JSObject expectedProto, Object target) {
		return target instanceof JSObject jsObj && jsObj.shape == expectedShape && jsObj.getPrototype() == expectedProto;
	}
	public static boolean isExactShapeSetterDouble(JSShape expected, Object target, double val) {
		return target instanceof JSObject && ((JSObject) target).shape == expected;
	}
	public static boolean isExactShapeSetterObject(JSShape expected, Object target, Object val) {
		return target instanceof JSObject && ((JSObject) target).shape == expected;
	}


	private static MethodHandle buildPrimFieldGetter(Class<?> targetClass, Field field, long offset,
	                                                 Class<?> requestedPrim) {
		Class<?>     fType = field.getType();
		MethodHandle mh;
		if (requestedPrim == int.class) {
			if (fType == int.class) {
				mh = FieldMH.GET_INT_PRIM;
			} else if (fType == double.class) {
				mh = FieldMH.GET_DOUBLE_AS_INT;
			} else if (fType == long.class) {
				mh = FieldMH.GET_LONG_AS_INT;
			} else if (fType == float.class) {
				mh = FieldMH.GET_FLOAT_AS_INT;
			} else if (fType == short.class) {
				mh = FieldMH.GET_SHORT_AS_INT;
			} else if (fType == byte.class) {
				mh = FieldMH.GET_BYTE_AS_INT;
			} else if (fType == char.class) {
				mh = FieldMH.GET_CHAR_AS_INT;
			} else if (fType == boolean.class) {
				mh = FieldMH.GET_BOOLEAN_AS_INT;
			} else { mh = FieldMH.GET_OBJECT_AS_INT; }
		} else if (requestedPrim == double.class) {
			if (fType == double.class) {
				mh = FieldMH.GET_DOUBLE_PRIM;
			} else if (fType == int.class) {
				mh = FieldMH.GET_INT_AS_DOUBLE;
			} else if (fType == long.class) {
				mh = FieldMH.GET_LONG_AS_DOUBLE;
			} else if (fType == float.class) {
				mh = FieldMH.GET_FLOAT_AS_DOUBLE;
			} else if (fType == short.class) {
				mh = FieldMH.GET_SHORT_AS_DOUBLE;
			} else if (fType == byte.class) {
				mh = FieldMH.GET_BYTE_AS_DOUBLE;
			} else if (fType == char.class) {
				mh = FieldMH.GET_CHAR_AS_DOUBLE;
			} else if (fType == boolean.class) {
				mh = FieldMH.GET_BOOLEAN_AS_DOUBLE;
			} else { mh = FieldMH.GET_OBJECT_AS_DOUBLE; }
		} else {
			if (fType == long.class) { mh = FieldMH.GET_LONG_PRIM; } else if (fType == int.class) {
				mh = FieldMH.GET_INT_AS_LONG;
			} else if (fType == double.class) {
				mh = FieldMH.GET_DOUBLE_AS_LONG;
			} else if (fType == float.class) {
				mh = FieldMH.GET_FLOAT_AS_LONG;
			} else if (fType == short.class) {
				mh = FieldMH.GET_SHORT_AS_LONG;
			} else if (fType == byte.class) {
				mh = FieldMH.GET_BYTE_AS_LONG;
			} else if (fType == char.class) {
				mh = FieldMH.GET_CHAR_AS_LONG;
			} else if (fType == boolean.class) {
				mh = FieldMH.GET_BOOLEAN_AS_LONG;
			} else { mh = FieldMH.GET_OBJECT_AS_LONG; }
		}
		return MethodHandles.insertArguments(mh, 0, offset);
	}

	public static int getPropIntFallback(ChainedCallSite site, Object target, String propName) {
		if (site.getPropId() < 0) site.setPropId(SymbolTable.id(propName));
		if (target == null || target == JSUndefined.INSTANCE) return 0;

		if (target instanceof JSObject jsObj) {
			if (target instanceof JSContext.JSGlobalThis globalThis) {
				return JSOps.toInt(globalThis.get(propName));
			}
			if (target instanceof JSArray jsArr && "length".equals(propName)) {
				MethodHandle test = MH_IS_EXACT_CLASS.bindTo(JSArray.class);
				site.installGuardOrSwitchMegamorphic(test, MH_JS_ARRAY_LENGTH_INT.asType(site.type()));
				return (int) jsArr.length();
			}
			JSShape shape  = jsObj.shape;
			int     propId = site.getPropId();
			int     offset = (propId >= 0) ? shape.getOffset(propId) : shape.getOffset(propName);
			if (offset >= 0) {
				byte type = shape.getSlotType(offset);
				site.recordShape(shape, offset, type);
				if (site.isMegamorphic()) {
					return (type == JSShape.TYPE_DOUBLE) ? (int) jsObj.getDoubleSlot(offset) : JSOps.toInt(jsObj.getSlot(offset));
				}

				if (site.isOffsetEquivalent()) {
					int          commonOff        = site.getCommonOffset();
					MethodHandle test             = buildMultiShapeGuard(site.getRecordedShapesArray(), site.getPropId(), commonOff);
					MethodHandle directSlotGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_INT, 0, commonOff);
					MethodHandle fallbackTarget   = getAdaptiveFallback(site);
					site.setTarget(MethodHandles.guardWithTest(test, directSlotGetter.asType(site.type()), fallbackTarget.asType(site.type())));
					return JSOps.toInt(jsObj.getSlot(commonOff));
				}

				// 异槽多态：一旦观测到 >= 2 个异槽 Shape，挂载扁平 switch，消除 LambdaForm 嵌套深度
				if (site.getPolyCount() >= 2) {
					MethodHandle fb = getAdaptiveFallback(site);
					site.installFlatPolyGuard(buildFlatPolySwitchInt(site.snapshotPoly(), fb));
				} else {
					MethodHandle test             = MH_IS_EXACT_SHAPE.bindTo(shape);
					MethodHandle directSlotGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_INT, 0, offset);
					site.installGuardOrSwitchMegamorphic(test, directSlotGetter);
				}
				return JSOps.toInt(jsObj.getSlot(offset));
			} else {
				// 原型链属性快速查找与 SwitchPoint 守卫挂载 (Int 专用快速路径)
				JSObject proto = jsObj.getPrototype();
				if (proto != null) {
					JSObject       current      = proto;
					JSObject       holder       = null;
					int            holderOffset = -1;
					List<JSObject> chain        = null;

					while (current != null) {
						int pOff = (propId >= 0) ? current.shape.getOffset(propId) : current.shape.getOffset(propName);
						if (pOff >= 0 && (current.isDoubleSlot(pOff) || current.getRawObjectSlot(pOff) != JSObject.NOT_FOUND)) {
							holder = current;
							holderOffset = pOff;
							break;
						}
						if (chain == null) chain = new ArrayList<>(2);
						chain.add(current);
						current = current.getPrototype();
					}

					if (holder != null) {
						byte         slotType = holder.shape.getSlotType(holderOffset);
						MethodHandle test     = MH_IS_EXACT_SHAPE_AND_PROTO.bindTo(shape).bindTo(proto);
						MethodHandle fb       = site.getInitialFallback();
						MethodHandle fbTyped  = (fb != null) ? fb.asType(site.type()) : null;

						if ((slotType & JSShape.FLAG_ACCESSOR) != 0) {
							Object raw = holder.getRawObjectSlot(holderOffset);
							if (raw instanceof PropertyAccessor acc) {
								MethodHandle getterTarget = MethodHandles.filterReturnValue(
								 MethodHandles.insertArguments(MH_GET_PROTO_ACCESSOR_PROP, 0, acc),
								 MH_TO_INT
								).asType(site.type());
								if (chain != null && fbTyped != null) {
									for (JSObject p : chain) {
										getterTarget = p.getOrCreateProtoSwitchPoint().guardWithTest(getterTarget, fbTyped);
									}
								}
								site.installProtoGuard(holder.getOrCreateProtoSwitchPoint(), test, getterTarget);
								return JSOps.toInt(acc.callGetter(null, target));
							}
						} else {
							int iVal = (slotType == JSShape.TYPE_DOUBLE || holder.isDoubleSlot(holderOffset))
							 ? (int) holder.getDoubleSlot(holderOffset)
							 : JSOps.toInt(holder.getSlot(holderOffset));

							MethodHandle constTarget = MethodHandles.dropArguments(
							 MethodHandles.constant(int.class, iVal), 0, site.type().parameterList()
							).asType(site.type());
							if (chain != null && fbTyped != null) {
								for (JSObject p : chain) {
									constTarget = p.getOrCreateProtoSwitchPoint().guardWithTest(constTarget, fbTyped);
								}
							}
							site.installProtoGuard(holder.getOrCreateProtoSwitchPoint(), test, constTarget);
							return iVal;
						}
					}
				}
			}
		}

		if (target.getClass().isArray() && "length".equals(propName)) {
			try {
				MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
				site.installGuardOrSwitchMegamorphic(test, MH_ARRAY_LENGTH_INT.asType(site.type()));
			} catch (Throwable ignored) { }
			return Array.getLength(target);
		}
		boolean  isStatic = false;
		Class<?> targetClass;
		if (target instanceof Class<?> c) {
			targetClass = c;
			isStatic = true;
		} else {
			targetClass = target.getClass();
		}

		MethodHandle test = isStatic ? MH_IS_SAME_OBJECT.bindTo(target) : MH_IS_EXACT_CLASS.bindTo(targetClass);

		if (isStatic) {
			Field field = MagicJIT.getDeclaredFieldRecursive(targetClass, propName);
			if (field != null && Modifier.isStatic(field.getModifiers())) {
				try {
					field.setAccessible(true);
					MethodHandle mh = Magic.lookup.unreflectGetter(field);
					if (field.getType() != int.class) {
						mh = MethodHandles.filterReturnValue(mh, MH_TO_INT);
					}
					MethodHandle directGetter = MethodHandles.dropArguments(mh, 0, Object.class).asType(site.type());
					site.installGuardOrSwitchMegamorphic(test, directGetter);
					return ((Number) field.get(null)).intValue();
				} catch (Throwable ignored) {
				}
			}
			Method getterMethod = MethodResolver.findGetterMethod(targetClass, propName);
			if (getterMethod != null && Modifier.isStatic(getterMethod.getModifiers())) {
				try {
					getterMethod.setAccessible(true);
					MethodHandle mh = Magic.lookup.unreflect(getterMethod);
					if (getterMethod.getReturnType() != int.class) {
						mh = MethodHandles.filterReturnValue(mh, MH_TO_INT);
					}
					MethodHandle directGetter = MethodHandles.dropArguments(mh, 0, Object.class).asType(site.type());
					site.installGuardOrSwitchMegamorphic(test, directGetter);
					return (int) mh.invoke();
				} catch (Throwable ignored) {
				}
			}
			return getPropIntGeneric(target, propName);
		}

		l:
		try {
			Field field = MagicJIT.getDeclaredFieldRecursive(targetClass, propName);
			if (field == null) break l;
			long         offset       = LinkerHelper.getFieldOffset(field);
			MethodHandle directGetter = buildPrimFieldGetter(targetClass, field, offset, int.class);
			site.installGuardOrSwitchMegamorphic(test, directGetter);
			return (int) directGetter.invokeExact(target);
		} catch (Throwable ignored) {
		}

		Method getterMethod = MethodResolver.findGetterMethod(targetClass, propName);
		if (getterMethod != null) {
			try {
				MethodHandle mh = Magic.lookup.unreflect(getterMethod);
				if (getterMethod.getReturnType() != int.class) {
					mh = MethodHandles.filterReturnValue(mh, MH_TO_INT);
				}
				site.installGuardOrSwitchMegamorphic(test, mh.asType(site.type()));
				return (int) mh.invoke(target);
			} catch (Throwable ignored) {
			}
		}

		return getPropIntGeneric(target, propName);
	}

	public static double getPropDoubleFallback(ChainedCallSite site, Object target, String propName) {
		if (site.getPropId() < 0) site.setPropId(SymbolTable.id(propName));
		if (target == null || target == JSUndefined.INSTANCE) return Double.NaN;

		// JSObject Fast路径：Shape 守护 + In-Object 裸双精度槽直读
		if (target instanceof JSObject jsObj) {
			if (target instanceof JSContext.JSGlobalThis globalThis) {
				return JSOps.toDouble(globalThis.get(propName));
			}
			if (target instanceof JSArray jsArr && "length".equals(propName)) {
				MethodHandle test = MH_IS_EXACT_CLASS.bindTo(JSArray.class);
				site.installGuardOrSwitchMegamorphic(test, MH_JS_ARRAY_LENGTH_DOUBLE.asType(site.type()));
				return (double) jsArr.length();
			}
			JSShape shape  = jsObj.shape;
			int     propId = site.getPropId();
			int     offset = (propId >= 0) ? shape.getOffset(propId) : shape.getOffset(propName);
			if (offset >= 0) {
				byte type = shape.getSlotType(offset);
				site.recordShape(shape, offset, type);
				if (site.isMegamorphic()) {
					return (type == JSShape.TYPE_DOUBLE) ? jsObj.getDoubleSlot(offset) : JSOps.toDouble(jsObj.getSlot(offset));
				}

				// 根据槽位实际类型选择 Getter (纯 double 走 Unsafe 汇编直读，Object 槽走安全解包)
				MethodHandle directSlotGetter;
				if (type == JSShape.TYPE_DOUBLE && offset < 8) {
					directSlotGetter = MH_GET_SLOT_DOUBLE[offset];
				} else {
					directSlotGetter = MethodHandles.insertArguments(MH_GET_JS_DOUBLE_SLOT_DOUBLE, 0, offset);
				}

				// A. 同偏移多态坍缩 (Offset-Equivalent Polymorphism)
				if (site.isOffsetEquivalent()) {
					int          commonOff  = site.getCommonOffset();
					byte         commonType = site.getCommonType();
					MethodHandle test       = buildMultiShapeGuard(site.getRecordedShapesArray(), site.getPropId(), commonOff);
					MethodHandle fastGetter = (commonType == JSShape.TYPE_DOUBLE && commonOff < 8)
					 ? MH_GET_SLOT_DOUBLE[commonOff]
					 : MethodHandles.insertArguments(MH_GET_JS_DOUBLE_SLOT_DOUBLE, 0, commonOff);

					MethodHandle fallbackTarget = getAdaptiveFallback(site);
					site.setTarget(MethodHandles.guardWithTest(test, fastGetter.asType(site.type()), fallbackTarget.asType(site.type())));
					return (commonType == JSShape.TYPE_DOUBLE) ? jsObj.getDoubleSlot(commonOff) : JSOps.toDouble(jsObj.getSlot(commonOff));
				}

				// B. 异槽多态：一旦观测到 >= 2 个异槽 Shape，挂载扁平 Jump-Table / 掩码分发
				if (site.getPolyCount() >= 2) {
					MethodHandle fb = getAdaptiveFallback(site);
					site.installFlatPolyGuard(buildFlatPolySwitchDouble(site.snapshotPoly(), fb));
				} else {
					// C. 单态 / 双态 GWT 链
					MethodHandle test = MH_IS_EXACT_SHAPE.bindTo(shape);
					site.installGuardOrSwitchMegamorphic(test, directSlotGetter);
				}

				return (type == JSShape.TYPE_DOUBLE) ? jsObj.getDoubleSlot(offset) : JSOps.toDouble(jsObj.getSlot(offset));
			} else {
				// 原型链属性快速查找与 SwitchPoint 守卫挂载 (Double 专用零装箱快速路径)
				JSObject proto = jsObj.getPrototype();
				if (proto != null) {
					JSObject       current      = proto;
					JSObject       holder       = null;
					int            holderOffset = -1;
					List<JSObject> chain        = null;

					while (current != null) {
						int pOff = (propId >= 0) ? current.shape.getOffset(propId) : current.shape.getOffset(propName);
						if (pOff >= 0 && (current.isDoubleSlot(pOff) || current.getRawObjectSlot(pOff) != JSObject.NOT_FOUND)) {
							holder = current;
							holderOffset = pOff;
							break;
						}
						if (chain == null) chain = new ArrayList<>(2);
						chain.add(current);
						current = current.getPrototype();
					}

					if (holder != null) {
						byte         slotType = holder.shape.getSlotType(holderOffset);
						MethodHandle test     = MH_IS_EXACT_SHAPE_AND_PROTO.bindTo(shape).bindTo(proto);
						MethodHandle fb       = site.getInitialFallback();
						MethodHandle fbTyped  = (fb != null) ? fb.asType(site.type()) : null;

						if ((slotType & JSShape.FLAG_ACCESSOR) != 0) {
							Object raw = holder.getRawObjectSlot(holderOffset);
							if (raw instanceof PropertyAccessor acc) {
								MethodHandle getterTarget = MethodHandles.filterReturnValue(
								 MethodHandles.insertArguments(MH_GET_PROTO_ACCESSOR_PROP, 0, acc),
								 MH_TO_DOUBLE
								).asType(site.type());
								if (chain != null && fbTyped != null) {
									for (JSObject p : chain) {
										getterTarget = p.getOrCreateProtoSwitchPoint().guardWithTest(getterTarget, fbTyped);
									}
								}
								site.installProtoGuard(holder.getOrCreateProtoSwitchPoint(), test, getterTarget);
								return JSOps.toDouble(acc.callGetter(null, target));
							}
						} else {
							double dVal = (slotType == JSShape.TYPE_DOUBLE || holder.isDoubleSlot(holderOffset))
							 ? holder.getDoubleSlot(holderOffset)
							 : JSOps.toDouble(holder.getSlot(holderOffset));

							MethodHandle constTarget = MethodHandles.dropArguments(
							 MethodHandles.constant(double.class, dVal), 0, site.type().parameterList()
							).asType(site.type());
							if (chain != null && fbTyped != null) {
								for (JSObject p : chain) {
									constTarget = p.getOrCreateProtoSwitchPoint().guardWithTest(constTarget, fbTyped);
								}
							}
							site.installProtoGuard(holder.getOrCreateProtoSwitchPoint(), test, constTarget);
							return dVal;
						}
					}
				}
			}
		}

		if (target.getClass().isArray() && "length".equals(propName)) {
			try {
				MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
				site.installGuardOrSwitchMegamorphic(test, MH_ARRAY_LENGTH_DOUBLE.asType(site.type()));
			} catch (Throwable ignored) { }
			return (double) Array.getLength(target);
		}

		boolean  isStatic = false;
		Class<?> targetClass;
		if (target instanceof Class<?> c) {
			targetClass = c;
			isStatic = true;
		} else {
			targetClass = target.getClass();
		}

		MethodHandle test = isStatic ? MH_IS_SAME_OBJECT.bindTo(target) : MH_IS_EXACT_CLASS.bindTo(targetClass);

		if (isStatic) {
			Field field = MagicJIT.getDeclaredFieldRecursive(targetClass, propName);
			if (field != null && Modifier.isStatic(field.getModifiers())) {
				try {
					field.setAccessible(true);
					MethodHandle mh = Magic.lookup.unreflectGetter(field);
					if (field.getType() != double.class) {
						mh = MethodHandles.filterReturnValue(mh, MH_TO_DOUBLE);
					}
					MethodHandle directGetter = MethodHandles.dropArguments(mh, 0, Object.class).asType(site.type());
					site.installGuardOrSwitchMegamorphic(test, directGetter);
					return ((Number) field.get(null)).doubleValue();
				} catch (Throwable ignored) {
				}
			}
			Method getterMethod = MethodResolver.findGetterMethod(targetClass, propName);
			if (getterMethod != null && Modifier.isStatic(getterMethod.getModifiers())) {
				try {
					getterMethod.setAccessible(true);
					MethodHandle mh = Magic.lookup.unreflect(getterMethod);
					if (getterMethod.getReturnType() != double.class) {
						mh = MethodHandles.filterReturnValue(mh, MH_TO_DOUBLE);
					}
					MethodHandle directGetter = MethodHandles.dropArguments(mh, 0, Object.class).asType(site.type());
					site.installGuardOrSwitchMegamorphic(test, directGetter);
					return (double) mh.invoke();
				} catch (Throwable ignored) {
				}
			}
			return getPropDoubleGeneric(target, propName);
		}

		l:
		try {
			Field field = MagicJIT.getDeclaredFieldRecursive(targetClass, propName);
			if (field == null) break l;
			long         offset       = LinkerHelper.getFieldOffset(field);
			MethodHandle directGetter = buildPrimFieldGetter(targetClass, field, offset, double.class);
			site.installGuardOrSwitchMegamorphic(test, directGetter);
			return (double) directGetter.invokeExact(target);
		} catch (Throwable ignored) {
		}

		Method getterMethod = MethodResolver.findGetterMethod(targetClass, propName);
		if (getterMethod != null) {
			try {
				MethodHandle mh = Magic.lookup.unreflect(getterMethod);
				// 若 getter 返回类型不是 double，注入自动拓宽/收窄转换 Filter
				if (getterMethod.getReturnType() != double.class) {
					mh = MethodHandles.filterReturnValue(mh, MH_TO_DOUBLE);
				}
				site.installGuardOrSwitchMegamorphic(test, mh.asType(site.type()));
				return (double) mh.invoke(target);
			} catch (Throwable ignored) {
			}
		}

		// 兜底通用反射读取
		return getPropDoubleGeneric(target, propName);
	}

	public static long getPropLongFallback(ChainedCallSite site, Object target, String propName) {
		if (site.getPropId() < 0) site.setPropId(SymbolTable.id(propName));
		if (target == null || target == JSUndefined.INSTANCE) return 0L;

		if (target instanceof JSObject jsObj) {
			if (target instanceof JSContext.JSGlobalThis globalThis) {
				return JSOps.toLong(globalThis.get(propName));
			}
			if (target instanceof JSArray jsArr && "length".equals(propName)) {
				MethodHandle test = MH_IS_EXACT_CLASS.bindTo(JSArray.class);
				site.installGuardOrSwitchMegamorphic(test, MH_JS_ARRAY_LENGTH_LONG.asType(site.type()));
				return jsArr.length();
			}
			JSShape shape  = jsObj.shape;
			int     propId = site.getPropId();
			int     offset = (propId >= 0) ? shape.getOffset(propId) : shape.getOffset(propName);
			if (offset >= 0) {
				byte type = shape.getSlotType(offset);
				site.recordShape(shape, offset, type);
				if (site.isMegamorphic()) {
					return (type == JSShape.TYPE_DOUBLE) ? (long) jsObj.getDoubleSlot(offset) : JSOps.toLong(jsObj.getSlot(offset));
				}

				if (site.isOffsetEquivalent()) {
					int          commonOff        = site.getCommonOffset();
					MethodHandle test             = buildMultiShapeGuard(site.getRecordedShapesArray(), site.getPropId(), commonOff);
					MethodHandle directSlotGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_LONG, 0, commonOff);
					MethodHandle fallbackTarget   = getAdaptiveFallback(site);
					site.setTarget(MethodHandles.guardWithTest(test, directSlotGetter.asType(site.type()), fallbackTarget.asType(site.type())));
					return JSOps.toLong(jsObj.getSlot(commonOff));
				}

				// 异槽多态：一旦观测到 >= 2 个异槽 Shape，挂载扁平 switch
				if (site.getPolyCount() >= 2) {
					MethodHandle fb = getAdaptiveFallback(site);
					site.installFlatPolyGuard(buildFlatPolySwitchLong(site.snapshotPoly(), fb));
				} else {
					MethodHandle test             = MH_IS_EXACT_SHAPE.bindTo(shape);
					MethodHandle directSlotGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_LONG, 0, offset);
					site.installGuardOrSwitchMegamorphic(test, directSlotGetter);
				}
				return JSOps.toLong(jsObj.getSlot(offset));
			} else {
				// 原型链属性快速查找与 SwitchPoint 守卫挂载 (Long 专用快速路径)
				JSObject proto = jsObj.getPrototype();
				if (proto != null) {
					JSObject       current      = proto;
					JSObject       holder       = null;
					int            holderOffset = -1;
					List<JSObject> chain        = null;

					while (current != null) {
						int pOff = (propId >= 0) ? current.shape.getOffset(propId) : current.shape.getOffset(propName);
						if (pOff >= 0 && (current.isDoubleSlot(pOff) || current.getRawObjectSlot(pOff) != JSObject.NOT_FOUND)) {
							holder = current;
							holderOffset = pOff;
							break;
						}
						if (chain == null) chain = new ArrayList<>(2);
						chain.add(current);
						current = current.getPrototype();
					}

					if (holder != null) {
						byte         slotType = holder.shape.getSlotType(holderOffset);
						MethodHandle test     = MH_IS_EXACT_SHAPE_AND_PROTO.bindTo(shape).bindTo(proto);
						MethodHandle fb       = site.getInitialFallback();
						MethodHandle fbTyped  = (fb != null) ? fb.asType(site.type()) : null;

						if ((slotType & JSShape.FLAG_ACCESSOR) != 0) {
							Object raw = holder.getRawObjectSlot(holderOffset);
							if (raw instanceof PropertyAccessor acc) {
								MethodHandle getterTarget = MethodHandles.filterReturnValue(
								 MethodHandles.insertArguments(MH_GET_PROTO_ACCESSOR_PROP, 0, acc),
								 MH_TO_LONG
								).asType(site.type());
								if (chain != null && fbTyped != null) {
									for (JSObject p : chain) {
										getterTarget = p.getOrCreateProtoSwitchPoint().guardWithTest(getterTarget, fbTyped);
									}
								}
								site.installProtoGuard(holder.getOrCreateProtoSwitchPoint(), test, getterTarget);
								return JSOps.toLong(acc.callGetter(null, target));
							}
						} else {
							long lVal = (slotType == JSShape.TYPE_DOUBLE || holder.isDoubleSlot(holderOffset))
							 ? (long) holder.getDoubleSlot(holderOffset)
							 : JSOps.toLong(holder.getSlot(holderOffset));

							MethodHandle constTarget = MethodHandles.dropArguments(
							 MethodHandles.constant(long.class, lVal), 0, site.type().parameterList()
							).asType(site.type());
							if (chain != null && fbTyped != null) {
								for (JSObject p : chain) {
									constTarget = p.getOrCreateProtoSwitchPoint().guardWithTest(constTarget, fbTyped);
								}
							}
							site.installProtoGuard(holder.getOrCreateProtoSwitchPoint(), test, constTarget);
							return lVal;
						}
					}
				}
			}
		}

		if (target.getClass().isArray() && "length".equals(propName)) {
			try {
				MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
				site.installGuardOrSwitchMegamorphic(test, MH_ARRAY_LENGTH_INT.asType(site.type()));
			} catch (Throwable ignored) { }
			return Array.getLength(target);
		}
		boolean  isStatic = false;
		Class<?> targetClass;
		if (target instanceof Class<?> c) {
			targetClass = c;
			isStatic = true;
		} else {
			targetClass = target.getClass();
		}

		MethodHandle test = isStatic ? MH_IS_SAME_OBJECT.bindTo(target) : MH_IS_EXACT_CLASS.bindTo(targetClass);

		if (isStatic) {
			Field field = MagicJIT.getDeclaredFieldRecursive(targetClass, propName);
			if (field != null && Modifier.isStatic(field.getModifiers())) {
				try {
					field.setAccessible(true);
					MethodHandle mh = Magic.lookup.unreflectGetter(field);
					if (field.getType() != long.class) {
						mh = MethodHandles.filterReturnValue(mh, MH_TO_LONG);
					}
					MethodHandle directGetter = MethodHandles.dropArguments(mh, 0, Object.class).asType(site.type());
					site.installGuardOrSwitchMegamorphic(test, directGetter);
					return ((Number) field.get(null)).longValue();
				} catch (Throwable ignored) {
				}
			}
			Method getterMethod = MethodResolver.findGetterMethod(targetClass, propName);
			if (getterMethod != null && Modifier.isStatic(getterMethod.getModifiers())) {
				try {
					getterMethod.setAccessible(true);
					MethodHandle mh = Magic.lookup.unreflect(getterMethod);
					if (getterMethod.getReturnType() != long.class) {
						mh = MethodHandles.filterReturnValue(mh, MH_TO_LONG);
					}
					MethodHandle directGetter = MethodHandles.dropArguments(mh, 0, Object.class).asType(site.type());
					site.installGuardOrSwitchMegamorphic(test, directGetter);
					return (long) mh.invoke();
				} catch (Throwable ignored) {
				}
			}
			return getPropLongGeneric(target, propName);
		}

		l:
		try {
			Field field = MagicJIT.getDeclaredFieldRecursive(targetClass, propName);
			if (field == null) break l;
			long         offset       = LinkerHelper.getFieldOffset(field);
			MethodHandle directGetter = buildPrimFieldGetter(targetClass, field, offset, long.class);
			site.installGuardOrSwitchMegamorphic(test, directGetter);
			return (long) directGetter.invokeExact(target);
		} catch (Throwable ignored) {
		}

		Method getterMethod = MethodResolver.findGetterMethod(targetClass, propName);
		if (getterMethod != null) {
			try {
				MethodHandle mh = Magic.lookup.unreflect(getterMethod);
				if (getterMethod.getReturnType() != long.class) {
					mh = MethodHandles.filterReturnValue(mh, MH_TO_LONG);
				}
				site.installGuardOrSwitchMegamorphic(test, mh.asType(site.type()));
				return (long) mh.invoke(target);
			} catch (Throwable ignored) {
			}
		}

		return getPropLongGeneric(target, propName);
	}

	static Object invokeJavaMethod(Object target, String methodName, Object[] args) throws Throwable {
		return JSJavaInterop.invokeJavaMethod(target, methodName, args);
	}

	public static final  ThreadLocal<JSObject> CURRENT_SUPER_PROTO      = JSJavaInterop.CURRENT_SUPER_PROTO;
	private static final ThreadLocal<JSObject> CURRENT_SUPER_CTOR_PROTO = new ThreadLocal<>();

	public static Object callSuperConstructor(JSContext cx, Object thisObj, Object[] args) throws Throwable {
		JSObject jsObj = null;
		if (thisObj instanceof JSBridgedObject bridged) {
			jsObj = bridged.getJSObject();
		} else if (thisObj instanceof JSObject obj) {
			jsObj = obj;
		}
		if (jsObj == null) return thisObj;

		JSObject currentProto = CURRENT_SUPER_CTOR_PROTO.get();
		JSObject nextProto;
		if (currentProto != null) {
			nextProto = currentProto.getPrototype();
		} else {
			JSObject proto = jsObj.getPrototype();
			nextProto = proto != null ? proto.getPrototype() : null;
		}
		if (nextProto != null && nextProto != JSContext.LazyObject.OBJECT_PROTOTYPE) {
			Object superCtor = nextProto.get("constructor");
			if (superCtor instanceof JSFunction func && !(func instanceof JSContext.JSObjectConstructor)) {
				JSObject prev = currentProto;
				CURRENT_SUPER_CTOR_PROTO.set(nextProto);
				try {
					func.call(cx, thisObj, args);
				} finally {
					CURRENT_SUPER_CTOR_PROTO.set(prev);
				}
				return thisObj;
			}
		}
		return thisObj;
	}

	private static MethodHandle buildDirectFieldGetter(Class<?> clazz, Field field, long offset) {
		Class<?>     type = field.getType();
		MethodHandle mh;
		if (type == int.class) { mh = FieldMH.GET_INT; } else if (type == double.class) {
			mh = FieldMH.GET_DOUBLE;
		} else if (type == long.class) {
			mh = FieldMH.GET_LONG;
		} else if (type == float.class) {
			mh = FieldMH.GET_FLOAT;
		} else if (type == short.class) {
			mh = FieldMH.GET_SHORT;
		} else if (type == byte.class) {
			mh = FieldMH.GET_BYTE;
		} else if (type == char.class) {
			mh = FieldMH.GET_CHAR;
		} else if (type == boolean.class) {
			mh = FieldMH.GET_BOOLEAN;
		} else { mh = FieldMH.GET_OBJECT; }
		return MethodHandles.insertArguments(mh, 0, offset);
	}

	private static MethodHandle buildDirectFieldSetter(Class<?> clazz, Field field, long offset) {
		Class<?>     type = field.getType();
		MethodHandle mh;
		if (type == int.class) { mh = FieldMH.PUT_INT; } else if (type == double.class) {
			mh = FieldMH.PUT_DOUBLE;
		} else if (type == long.class) {
			mh = FieldMH.PUT_LONG;
		} else if (type == float.class) {
			mh = FieldMH.PUT_FLOAT;
		} else if (type == short.class) {
			mh = FieldMH.PUT_SHORT;
		} else if (type == byte.class) {
			mh = FieldMH.PUT_BYTE;
		} else if (type == char.class) {
			mh = FieldMH.PUT_CHAR;
		} else if (type == boolean.class) {
			mh = FieldMH.PUT_BOOLEAN;
		} else { mh = FieldMH.PUT_OBJECT; }
		return MethodHandles.insertArguments(mh, 0, offset);
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

	public static JSPromise importDynamic(JSContext cx, Object specifierObj, Object currentDirOrModule) {
		if (cx == null) cx = JSContext.current();
		if (specifierObj == null || specifierObj == JSUndefined.INSTANCE) {
			JSPromise p = new JSPromise(cx);
			p.reject(JSContext.makeTypeError("Invalid module specifier"));
			return p;
		}
		String          specifier = JSOps.toStr(specifierObj);
		JSModuleManager mgr       = cx.getModuleManager();
		JSModule        parent;
		if (currentDirOrModule instanceof JSModule m) {
			parent = m;
		} else if (currentDirOrModule instanceof String dirname) {
			parent = new JSModule("temp", "", dirname, null);
		} else {
			parent = JSModuleManager.getCurrentModule();
		}
		return mgr.importDynamic(specifier, parent);
	}

	public static void exportAll(Object targetExports, Object sourceMod) {
		if (targetExports instanceof JSObject target && sourceMod instanceof JSObject source) {
			for (String key : source.keys()) {
				if ("default".equals(key) || "__esModule".equals(key)) continue;
				if (!JSSymbol.isSymbolKey(key)) {
					target.put(key, source.get(key));
				}
			}
		}
	}
	//endregion
}
