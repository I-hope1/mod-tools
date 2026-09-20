package hope.magic.js.runtime;

import hope.magic.runtime.*;
import sun.misc.Unsafe;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

import static hope.magic.js.runtime.SlotMH.*;

@SuppressWarnings({"unused", "unchecked", "rawtypes", "RedundantCast"})
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
	public static final MethodHandle MH_INVOKE_INTERFACE_1;
	public static final MethodHandle MH_TRANSITION_SET_DOUBLE;
	public static final MethodHandle MH_TRANSITION_SET_OBJECT;
	public static final MethodHandle MH_TRANSITION_SET_OBJECT_DOUBLE;
	public static final MethodHandle MH_GET_ACCESSOR_PROP;
	public static final MethodHandle MH_GET_PROTO_ACCESSOR_PROP;
	public static final MethodHandle MH_SET_ACCESSOR_PROP;
	public static final MethodHandle MH_SET_NOOP_PROP;
	public static final MethodHandle MH_ARRAY_LENGTH_INT;
	public static final MethodHandle MH_ARRAY_LENGTH_DOUBLE;
	public static final MethodHandle MH_GET_INDEX_JS_ARRAY;
	public static final MethodHandle MH_GET_INDEX_LIST;
	public static final MethodHandle MH_GET_INDEX_OBJECT_ARRAY;
	public static final MethodHandle MH_GET_INDEX_PRIMITIVE_ARRAY;
	public static final MethodHandle MH_GET_INDEX_INT_ARRAY;
	public static final MethodHandle MH_GET_INDEX_DOUBLE_ARRAY;
	public static final MethodHandle MH_GET_INDEX_LONG_ARRAY;
	public static final MethodHandle MH_GET_INDEX_MAP;

	public static final MethodHandle MH_SET_INDEX_JS_ARRAY;
	public static final MethodHandle MH_SET_INDEX_LIST;
	public static final MethodHandle MH_SET_INDEX_OBJECT_ARRAY;
	public static final MethodHandle MH_SET_INDEX_INT_ARRAY;
	public static final MethodHandle MH_SET_INDEX_DOUBLE_ARRAY;
	public static final MethodHandle MH_SET_INDEX_LONG_ARRAY;
	public static final MethodHandle MH_SET_INDEX_PRIMITIVE_ARRAY;
	public static final MethodHandle MH_SET_INDEX_MAP;
	public static final MethodHandle MH_NEW_ARRAY_0;
	public static final MethodHandle MH_NEW_ARRAY_1;
	public static final MethodHandle MH_NEW_ARRAY_N;
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
			MH_GET_INDEX_JS_ARRAY = LOOKUP.findStatic(JSLinker.class, "getIndexJSArray", MethodType.methodType(Object.class, Object.class, Object.class));
			MH_GET_INDEX_LIST = LOOKUP.findStatic(JSLinker.class, "getIndexList", MethodType.methodType(Object.class, Object.class, Object.class));
			MH_GET_INDEX_OBJECT_ARRAY = LOOKUP.findStatic(JSLinker.class, "getIndexObjectArray", MethodType.methodType(Object.class, Object.class, Object.class));
			MH_GET_INDEX_PRIMITIVE_ARRAY = LOOKUP.findStatic(JSLinker.class, "getIndexPrimitiveArray", MethodType.methodType(Object.class, Object.class, Object.class));
			MH_GET_INDEX_INT_ARRAY = LOOKUP.findStatic(JSLinker.class, "getIndexIntArray", MethodType.methodType(Object.class, Object.class, Object.class));
			MH_GET_INDEX_DOUBLE_ARRAY = LOOKUP.findStatic(JSLinker.class, "getIndexDoubleArray", MethodType.methodType(Object.class, Object.class, Object.class));
			MH_GET_INDEX_LONG_ARRAY = LOOKUP.findStatic(JSLinker.class, "getIndexLongArray", MethodType.methodType(Object.class, Object.class, Object.class));
			MH_GET_INDEX_MAP = LOOKUP.findStatic(JSLinker.class, "getIndexMap", MethodType.methodType(Object.class, Object.class, Object.class));

			MH_SET_INDEX_JS_ARRAY = LOOKUP.findStatic(JSLinker.class, "setIndexJSArray", MethodType.methodType(void.class, Object.class, Object.class, Object.class));
			MH_SET_INDEX_LIST = LOOKUP.findStatic(JSLinker.class, "setIndexList", MethodType.methodType(void.class, Object.class, Object.class, Object.class));
			MH_SET_INDEX_OBJECT_ARRAY = LOOKUP.findStatic(JSLinker.class, "setIndexObjectArray", MethodType.methodType(void.class, Object.class, Object.class, Object.class));
			MH_SET_INDEX_INT_ARRAY = LOOKUP.findStatic(JSLinker.class, "setIndexIntArray", MethodType.methodType(void.class, Object.class, Object.class, Object.class));
			MH_SET_INDEX_DOUBLE_ARRAY = LOOKUP.findStatic(JSLinker.class, "setIndexDoubleArray", MethodType.methodType(void.class, Object.class, Object.class, Object.class));
			MH_SET_INDEX_LONG_ARRAY = LOOKUP.findStatic(JSLinker.class, "setIndexLongArray", MethodType.methodType(void.class, Object.class, Object.class, Object.class));
			MH_SET_INDEX_PRIMITIVE_ARRAY = LOOKUP.findStatic(JSLinker.class, "setIndexPrimitiveArray", MethodType.methodType(void.class, Object.class, Object.class, Object.class));
			MH_SET_INDEX_MAP = LOOKUP.findStatic(JSLinker.class, "setIndexMap", MethodType.methodType(void.class, Object.class, Object.class, Object.class));
			MH_NEW_ARRAY_0 = LOOKUP.findStatic(JSLinker.class, "newArrayInstance0", MethodType.methodType(Object.class, Class.class));
			MH_NEW_ARRAY_1 = LOOKUP.findStatic(JSLinker.class, "newArrayInstance1", MethodType.methodType(Object.class, Class.class, Object.class));
			MH_NEW_ARRAY_N = LOOKUP.findStatic(JSLinker.class, "newArrayInstanceN", MethodType.methodType(Object.class, Class.class, Object[].class));
			MH_INVOKE_INTERFACE_1 = LOOKUP.findStatic(JSLinker.class, "invokeInterfaceAdapter1", MethodType.methodType(Object.class, Object.class, Object.class));
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

	/** JDK 17+ 是否可用 MethodHandles.tableSwitch（反射探测，类加载时确定）。 */
	private static final boolean SUPPORTS_TABLE_SWITCH;
	private static final Method  MTH_TABLE_SWITCH;

	static {
		boolean ok = false;
		Method  m  = null;
		try {
			m = MethodHandles.class.getMethod("tableSwitch", MethodHandle.class, MethodHandle[].class);
			ok = true;
		} catch (NoSuchMethodException ignored) { }
		SUPPORTS_TABLE_SWITCH = ok;
		MTH_TABLE_SWITCH = m;
	}

	/**
	 * 统一 tableSwitch 构造分发（兼容直接调用与反射调用）。
	 * @param defaultCase 当 selector 不在 [0, targets.length) 区间时的降级 Handle（首参数必须为 int）
	 * @param targets     各 index 对应的目标 Handle 数组（首参数必须为 int）
	 * @return 签名为 {@code (int selector, TrailingArgs...) -> ReturnType} 的 switch Handle
	 */
	private static MethodHandle invokeTableSwitch(MethodHandle defaultCase, MethodHandle[] targets) throws Throwable {
		if (MTH_TABLE_SWITCH != null) {
			return (MethodHandle) MTH_TABLE_SWITCH.invoke(null, new Object[]{defaultCase, targets});
		}
		throw new UnsupportedOperationException("MethodHandles.tableSwitch is not supported on current JVM");
	}

	/**
	 * 构建异槽多态扁平 Switch 守卫（Object getter 版）。
	 *
	 * <p>路线 A（JDK 17+）：用 {@code MethodHandles.tableSwitch} 生成硬件跳转表，
	 * 配合 {@code foldArguments} 消除 selector 参数，C2 编译后内联深度恒为 1。<br>
	 * 路线 B（低版本）：生成单个 {@code polyGetObject} 静态方法调用（线性循环扫描）。
	 * @param snap     多态快照（shape + offset 数组）
	 * @param fallback 超态/未命中时的降级 handle，签名 {@code (Object) -> Object}
	 * @return 签名为 {@code (Object) -> Object} 的扁平 Switch 守卫
	 */
	public static MethodHandle buildFlatPolySwitchObject(PolySnapshot snap, MethodHandle fallback) {
		JSShape[] shapes = snap.shapes();
		int       n      = shapes.length;
		if (n == 0) return fallback;
		int[]  offsets = snap.offsets();
		byte[] types   = snap.types();

		// 小规模多态 (n <= 2) 展开式级联 GWT (纯指针比较，零掩码与归属校验开销) ──
		if (n <= 2) {
			MethodHandle chain = fallback;
			for (int i = n - 1; i >= 0; i--) {
				int off = offsets[i];
				MethodHandle fastGetter = off < 8
				 ? MH_GET_SLOT_OBJECT[off]
				 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, off);
				MethodHandle exactTest = MH_IS_EXACT_SHAPE.bindTo(shapes[i]);
				chain = MethodHandles.guardWithTest(exactTest, fastGetter.asType(fallback.type()), chain);
			}
			return chain;
		}

		// 多态/巨态按 Offset 分组聚合位掩码 (Offset-Class Mask Dispatch) ──
		MethodHandle maskChain = tryBuildOffsetMaskDispatchObject(shapes, offsets, n, snap.propId(), fallback);
		if (maskChain != null) {
			return maskChain;
		}

		// 路线 A：JDK 17+ 原生硬件跳转表
		if (SUPPORTS_TABLE_SWITCH) {
			try {
				int minId = Integer.MAX_VALUE, maxId = Integer.MIN_VALUE;
				for (JSShape s : shapes) {
					minId = Math.min(minId, s.id);
					maxId = Math.max(maxId, s.id);
				}
				int span = maxId - minId + 1;

				if (span <= n * 4 && span <= 64) {
					MethodHandle fallbackWithSel = MethodHandles.dropArguments(fallback, 0, int.class);

					MethodHandle[] targets = new MethodHandle[span];
					Arrays.fill(targets, fallbackWithSel);

					for (int i = 0; i < n; i++) {
						int  idx  = shapes[i].id - minId;
						int  off  = offsets[i];
						byte type = types[i];

						MethodHandle fastGetter;
						if ((type & JSShape.TYPE_MASK) == JSShape.TYPE_DOUBLE) {
							fastGetter = (off < 8)
							 ? MH_GET_SLOT_DOUBLE_AS_OBJ[off] // 读出来直接转为 Double 对象，与其他原始类型不同
							 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_DOUBLE_AS_OBJ, 0, off);
						} else {
							fastGetter = (off < 8)
							 ? MH_GET_SLOT_OBJECT[off]
							 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, off);
						}

						// JSShape.id 是通过 AtomicInteger 生成的全局唯一、不可变 ID。
						// 进入 targets[idx] 说明当前对象的 shape.id - minId 精确命中了该下标；如果不命中或为未记录的 Shape，早在 Selector 处就会返回 -1 跳入 defaultCase，或命中空洞槽位的 fallbackWithSel。
						// 因此，能跳转到 targets[idx]，其 Shape 数学上必然等于 shapes[i]
						// MethodHandle exactTest     = MH_IS_EXACT_SHAPE.bindTo(shapes[i]);
						// MethodHandle guardedGetter = MethodHandles.guardWithTest(exactTest, fastGetter.asType(fallback.type()), fallback);
						// targets[idx] = MethodHandles.dropArguments(guardedGetter, 0, int.class);
						targets[idx] = MethodHandles.dropArguments(fastGetter.asType(fallback.type()), 0, int.class);
					}

					MethodHandle ts       = invokeTableSwitch(fallbackWithSel, targets);
					MethodHandle selector = buildShapeIdSelector(minId, span);
					return MethodHandles.foldArguments(ts, selector);
				}
			} catch (Throwable ignored) { }
		}

		return buildFlatPolySwitchObjectLinear(shapes, offsets, n, fallback);
	}

	/** 构建异槽多态扁平 Switch 守卫（double getter 版）。签名 {@code (Object) -> double}。 */
	public static MethodHandle buildFlatPolySwitchDouble(PolySnapshot snap, MethodHandle fallback) {
		JSShape[] shapes = snap.shapes();
		int       n      = shapes.length;
		if (n == 0) return fallback;
		int[]  offsets = snap.offsets();
		byte[] types   = snap.types();

		// 小规模多态 (n <= 2) 展开式级联 GWT (纯指针比较，零掩码与归属校验开销) ──
		if (n <= 2) {
			MethodHandle chain = fallback;
			for (int i = n - 1; i >= 0; i--) {
				int off = offsets[i];
				MethodHandle fastGetter = off < 8
				 ? MH_GET_SLOT_DOUBLE[off]
				 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_DOUBLE, 0, off);
				MethodHandle exactTest = MH_IS_EXACT_SHAPE.bindTo(shapes[i]);
				chain = MethodHandles.guardWithTest(exactTest, fastGetter.asType(fallback.type()), chain);
			}
			return chain;
		}

		// 多态/巨态按 Offset 分组聚合位掩码 (Offset-Class Mask Dispatch) ──
		MethodHandle maskChain = tryBuildOffsetMaskDispatchDouble(shapes, offsets, types, n, snap.propId(), fallback);
		if (maskChain != null) {
			return maskChain;
		}

		if (SUPPORTS_TABLE_SWITCH) {
			try {
				int minId = Integer.MAX_VALUE, maxId = Integer.MIN_VALUE;
				for (JSShape s : shapes) {
					minId = Math.min(minId, s.id);
					maxId = Math.max(maxId, s.id);
				}
				int span = maxId - minId + 1;

				if (span <= n * 4 && span <= 64) {
					MethodHandle fallbackWithSel = MethodHandles.dropArguments(fallback, 0, int.class);

					MethodHandle[] targets = new MethodHandle[span];
					Arrays.fill(targets, fallbackWithSel);

					for (int i = 0; i < n; i++) {
						int  idx  = shapes[i].id - minId;
						int  off  = offsets[i];
						byte type = types[i];

						MethodHandle fastGetter;
						if ((type & JSShape.TYPE_MASK) == JSShape.TYPE_DOUBLE) {
							fastGetter = (off < 8)
							 ? MH_GET_SLOT_DOUBLE[off]
							 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_DOUBLE, 0, off);
						} else {
							fastGetter = (off < 8)
							 ? MH_GET_SLOT_OBJECT[off]
							 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, off);
						}

						// JSShape.id 是通过 AtomicInteger 生成的全局唯一、不可变 ID。
						// 进入 targets[idx] 说明当前对象的 shape.id - minId 精确命中了该下标；如果不命中或为未记录的 Shape，早在 Selector 处就会返回 -1 跳入 defaultCase，或命中空洞槽位的 fallbackWithSel。
						// 因此，能跳转到 targets[idx]，其 Shape 数学上必然等于 shapes[i]
						// MethodHandle exactTest     = MH_IS_EXACT_SHAPE.bindTo(shapes[i]);
						// MethodHandle guardedGetter = MethodHandles.guardWithTest(exactTest, fastGetter.asType(fallback.type()), fallback);
						// targets[idx] = MethodHandles.dropArguments(guardedGetter, 0, int.class);
						targets[idx] = MethodHandles.dropArguments(fastGetter.asType(fallback.type()), 0, int.class);
					}

					MethodHandle ts       = invokeTableSwitch(fallbackWithSel, targets);
					MethodHandle selector = buildShapeIdSelector(minId, span);
					return MethodHandles.foldArguments(ts, selector);
				}
			} catch (Throwable ignored) { }
		}

		return buildFlatPolySwitchDoubleLinear(shapes, offsets, n, fallback);
	}

	/** 构建异槽多态扁平 Switch 守卫（int getter 版）。签名 {@code (Object) -> int}。 */
	public static MethodHandle buildFlatPolySwitchInt(PolySnapshot snap, MethodHandle fallback) {
		JSShape[] shapes = snap.shapes();
		int       n      = shapes.length;
		if (n == 0) return fallback;
		int[]  offsets = snap.offsets();
		byte[] types   = snap.types();

		// 小规模多态 (n <= 2) 展开式级联 GWT (纯指针比较，零掩码与归属校验开销) ──
		if (n <= 2) {
			MethodHandle chain = fallback;
			for (int i = n - 1; i >= 0; i--) {
				int          off        = offsets[i];
				MethodHandle fastGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_INT, 0, off);
				MethodHandle exactTest  = MH_IS_EXACT_SHAPE.bindTo(shapes[i]);
				chain = MethodHandles.guardWithTest(exactTest, fastGetter.asType(fallback.type()), chain);
			}
			return chain;
		}

		// 多态/巨态按 Offset 分组聚合位掩码 (Offset-Class Mask Dispatch) ──
		MethodHandle maskChain = tryBuildOffsetMaskDispatchInt(shapes, offsets, n, snap.propId(), fallback);
		if (maskChain != null) {
			return maskChain;
		}

		if (SUPPORTS_TABLE_SWITCH) {
			try {
				int minId = Integer.MAX_VALUE, maxId = Integer.MIN_VALUE;
				for (JSShape s : shapes) {
					minId = Math.min(minId, s.id);
					maxId = Math.max(maxId, s.id);
				}
				int span = maxId - minId + 1;

				if (span <= n * 4 && span <= 64) {
					MethodHandle fallbackWithSel = MethodHandles.dropArguments(fallback, 0, int.class);

					MethodHandle[] targets = new MethodHandle[span];
					Arrays.fill(targets, fallbackWithSel);

					for (int i = 0; i < n; i++) {
						int  idx  = shapes[i].id - minId;
						int  off  = offsets[i];
						byte type = types[i];

						MethodHandle fastGetter;
						if ((type & JSShape.TYPE_MASK) == JSShape.TYPE_DOUBLE) {
							fastGetter = (off < 8)
							 ? MH_GET_SLOT_DOUBLE[off]
							 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_DOUBLE, 0, off);
						} else {
							fastGetter = (off < 8)
							 ? MH_GET_SLOT_OBJECT[off]
							 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, off);
						}

						// JSShape.id 是通过 AtomicInteger 生成的全局唯一、不可变 ID。
						// 进入 targets[idx] 说明当前对象的 shape.id - minId 精确命中了该下标；如果不命中或为未记录的 Shape，早在 Selector 处就会返回 -1 跳入 defaultCase，或命中空洞槽位的 fallbackWithSel。
						// 因此，能跳转到 targets[idx]，其 Shape 数学上必然等于 shapes[i]
						// MethodHandle exactTest     = MH_IS_EXACT_SHAPE.bindTo(shapes[i]);
						// MethodHandle guardedGetter = MethodHandles.guardWithTest(exactTest, fastGetter.asType(fallback.type()), fallback);
						// targets[idx] = MethodHandles.dropArguments(guardedGetter, 0, int.class);
						targets[idx] = MethodHandles.dropArguments(fastGetter.asType(fallback.type()), 0, int.class);
					}

					MethodHandle ts       = invokeTableSwitch(fallbackWithSel, targets);
					MethodHandle selector = buildShapeIdSelector(minId, span);
					return MethodHandles.foldArguments(ts, selector);
				}
			} catch (Throwable ignored) { }
		}

		return buildFlatPolySwitchIntLinear(shapes, offsets, n, fallback);
	}

	/** 构建异槽多态扁平 Switch 守卫（long getter 版）。签名 {@code (Object) -> long}。 */
	public static MethodHandle buildFlatPolySwitchLong(PolySnapshot snap, MethodHandle fallback) {
		JSShape[] shapes = snap.shapes();
		int       n      = shapes.length;
		if (n == 0) return fallback;
		int[]  offsets = snap.offsets();
		byte[] types   = snap.types();

		// 小规模多态 (n <= 2) 展开式级联 GWT (纯指针比较，零掩码与归属校验开销) ──
		if (n <= 2) {
			MethodHandle chain = fallback;
			for (int i = n - 1; i >= 0; i--) {
				int          off        = offsets[i];
				MethodHandle fastGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_LONG, 0, off);
				MethodHandle exactTest  = MH_IS_EXACT_SHAPE.bindTo(shapes[i]);
				chain = MethodHandles.guardWithTest(exactTest, fastGetter.asType(fallback.type()), chain);
			}
			return chain;
		}

		// 多态/巨态按 Offset 分组聚合位掩码 (Offset-Class Mask Dispatch) ──
		MethodHandle maskChain = tryBuildOffsetMaskDispatchLong(shapes, offsets, n, snap.propId(), fallback);
		if (maskChain != null) {
			return maskChain;
		}

		if (SUPPORTS_TABLE_SWITCH) {
			try {
				int minId = Integer.MAX_VALUE, maxId = Integer.MIN_VALUE;
				for (JSShape s : shapes) {
					minId = Math.min(minId, s.id);
					maxId = Math.max(maxId, s.id);
				}
				int span = maxId - minId + 1;

				if (span <= n * 4 && span <= 64) {
					MethodHandle fallbackWithSel = MethodHandles.dropArguments(fallback, 0, int.class);

					MethodHandle[] targets = new MethodHandle[span];
					Arrays.fill(targets, fallbackWithSel);

					for (int i = 0; i < n; i++) {
						int  idx  = shapes[i].id - minId;
						int  off  = offsets[i];
						byte type = types[i];

						MethodHandle fastGetter;
						if ((type & JSShape.TYPE_MASK) == JSShape.TYPE_DOUBLE) {
							fastGetter = (off < 8)
							 ? MH_GET_SLOT_DOUBLE[off]
							 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_DOUBLE, 0, off);
						} else {
							fastGetter = (off < 8)
							 ? MH_GET_SLOT_OBJECT[off]
							 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, off);
						}

						// JSShape.id 是通过 AtomicInteger 生成的全局唯一、不可变 ID。
						// 进入 targets[idx] 说明当前对象的 shape.id - minId 精确命中了该下标；如果不命中或为未记录的 Shape，早在 Selector 处就会返回 -1 跳入 defaultCase，或命中空洞槽位的 fallbackWithSel。
						// 因此，能跳转到 targets[idx]，其 Shape 数学上必然等于 shapes[i]
						// MethodHandle exactTest     = MH_IS_EXACT_SHAPE.bindTo(shapes[i]);
						// MethodHandle guardedGetter = MethodHandles.guardWithTest(exactTest, fastGetter.asType(fallback.type()), fallback);
						// targets[idx] = MethodHandles.dropArguments(guardedGetter, 0, int.class);
						targets[idx] = MethodHandles.dropArguments(fastGetter.asType(fallback.type()), 0, int.class);
					}

					MethodHandle ts       = invokeTableSwitch(fallbackWithSel, targets);
					MethodHandle selector = buildShapeIdSelector(minId, span);
					return MethodHandles.foldArguments(ts, selector);
				}
			} catch (Throwable ignored) { }
		}

		return buildFlatPolySwitchLongLinear(shapes, offsets, n, fallback);
	}

	// ── 偏移类聚合位掩码辅助函数 (Offset-Class Mask Dispatch Helpers) ──────────

	private static int[] collectDistinctOffsets(int[] offsets, int n) {
		int[] distinctOffsets = new int[8];
		int   count           = 0;
		for (int i = 0; i < n; i++) {
			int     off   = offsets[i];
			boolean found = false;
			for (int j = 0; j < count; j++) {
				if (distinctOffsets[j] == off) {
					found = true;
					break;
				}
			}
			if (!found) {
				if (count >= 8) return null; // offset 种类过多时回落
				distinctOffsets[count++] = off;
			}
		}
		return Arrays.copyOf(distinctOffsets, count);
	}

	private static MethodHandle tryBuildOffsetMaskDispatchDouble(JSShape[] shapes, int[] offsets, byte[] types, int n, int propId,
	                                                             MethodHandle fallback) {
		if (propId < 0) return null;
		for (int i = 0; i < n; i++) {
			if ((types[i] & JSShape.TYPE_MASK) != JSShape.TYPE_DOUBLE) {
				return null;
			}
		}
		int[] distinctOffsets = collectDistinctOffsets(offsets, n);
		if (distinctOffsets == null) return null;

		MethodHandle chain = fallback;
		for (int i = distinctOffsets.length - 1; i >= 0; i--) {
			int off = distinctOffsets[i];
			MethodHandle fastGetter = off < 8
			 ? MH_GET_SLOT_DOUBLE[off]
			 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_DOUBLE, 0, off);
			MethodHandle test = MethodHandles.insertArguments(MH_IS_MATCH_PROP, 0, propId, off);
			chain = MethodHandles.guardWithTest(test, fastGetter.asType(fallback.type()), chain);
		}
		return chain;
	}

	private static MethodHandle tryBuildOffsetMaskDispatchObject(JSShape[] shapes, int[] offsets, int n, int propId,
	                                                             MethodHandle fallback) {
		if (propId < 0) return null;
		int[] distinctOffsets = collectDistinctOffsets(offsets, n);
		if (distinctOffsets == null) return null;

		MethodHandle chain = fallback;
		for (int i = distinctOffsets.length - 1; i >= 0; i--) {
			int off = distinctOffsets[i];
			MethodHandle fastGetter = off < 8
			 ? MH_GET_SLOT_OBJECT[off]
			 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, off);
			MethodHandle test = MethodHandles.insertArguments(MH_IS_MATCH_PROP, 0, propId, off);
			chain = MethodHandles.guardWithTest(test, fastGetter.asType(fallback.type()), chain);
		}
		return chain;
	}

	private static MethodHandle tryBuildOffsetMaskDispatchInt(JSShape[] shapes, int[] offsets, int n, int propId,
	                                                          MethodHandle fallback) {
		if (propId < 0) return null;
		int[] distinctOffsets = collectDistinctOffsets(offsets, n);
		if (distinctOffsets == null) return null;

		MethodHandle chain = fallback;
		for (int i = distinctOffsets.length - 1; i >= 0; i--) {
			int          off        = distinctOffsets[i];
			MethodHandle fastGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_INT, 0, off);
			MethodHandle test       = MethodHandles.insertArguments(MH_IS_MATCH_PROP, 0, propId, off);
			chain = MethodHandles.guardWithTest(test, fastGetter.asType(fallback.type()), chain);
		}
		return chain;
	}

	private static MethodHandle tryBuildOffsetMaskDispatchLong(JSShape[] shapes, int[] offsets, int n, int propId,
	                                                           MethodHandle fallback) {
		if (propId < 0) return null;
		int[] distinctOffsets = collectDistinctOffsets(offsets, n);
		if (distinctOffsets == null) return null;

		MethodHandle chain = fallback;
		for (int i = distinctOffsets.length - 1; i >= 0; i--) {
			int          off        = distinctOffsets[i];
			MethodHandle fastGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_LONG, 0, off);
			MethodHandle test       = MethodHandles.insertArguments(MH_IS_MATCH_PROP, 0, propId, off);
			chain = MethodHandles.guardWithTest(test, fastGetter.asType(fallback.type()), chain);
		}
		return chain;
	}

	/** 构建异槽多态扁平 Switch 守卫（Object setter 版）。签名 {@code (Object, Object) -> void}。 */
	public static MethodHandle buildFlatPolySwitchSetterObject(PolySnapshot snap, MethodHandle fallback) {
		JSShape[] shapes  = snap.shapes();
		int[]     offsets = snap.offsets();
		int       n       = shapes.length;

		if (!SUPPORTS_TABLE_SWITCH || n == 0) return buildFlatPolySwitchSetterObjectLinear(shapes, offsets, n, fallback);

		try {
			int minId = Integer.MAX_VALUE, maxId = Integer.MIN_VALUE;
			for (JSShape s : shapes) {
				minId = Math.min(minId, s.id);
				maxId = Math.max(maxId, s.id);
			}
			int span = maxId - minId + 1;

			if (span <= n * 4 && span <= 64) {
				// Fallback: (Object, Object) -> void  ==>  (int, Object, Object) -> void
				MethodHandle fallbackWithSel = MethodHandles.dropArguments(fallback, 0, int.class);

				MethodHandle[] targets = new MethodHandle[span];
				Arrays.fill(targets, fallbackWithSel);

				for (int i = 0; i < n; i++) {
					int idx = shapes[i].id - minId;
					int off = offsets[i];
					MethodHandle fastSetter = off < 8
					 ? MH_SET_SLOT_OBJECT[off]
					 : MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT, 0, off);


					// JSShape.id 是通过 AtomicInteger 生成的全局唯一、不可变 ID。
					// 进入 targets[idx] 说明当前对象的 shape.id - minId 精确命中了该下标；如果不命中或为未记录的 Shape，早在 Selector 处就会返回 -1 跳入 defaultCase，或命中空洞槽位的 fallbackWithSel。
					// 因此，能跳转到 targets[idx]，其 Shape 数学上必然等于 shapes[i]
					// MethodHandle exactTest     = MH_IS_EXACT_SHAPE_SETTER_OBJECT.bindTo(shapes[i]);
					// MethodHandle guardedSetter = MethodHandles.guardWithTest(exactTest, fastSetter.asType(fallback.type()), fallback);
					// targets[idx] = MethodHandles.dropArguments(guardedSetter, 0, int.class);

					targets[idx] = MethodHandles.dropArguments(fastSetter.asType(fallback.type()), 0, int.class);
				}

				// ts 签名: (int, Object, Object) -> void
				MethodHandle ts = invokeTableSwitch(fallbackWithSel, targets);
				// selector 签名: (Object target) -> int
				MethodHandle selector = buildShapeIdSelector(minId, span);

				// foldArguments 将 selector(arg0) 的结果注入给 ts 的第 0 个参数，
				// 剩余 (Object, Object) 保持不变传递，最终输出 (Object, Object) -> void
				return MethodHandles.foldArguments(ts, selector);
			}
		} catch (Throwable ignored) { }

		return buildFlatPolySwitchSetterObjectLinear(shapes, offsets, n, fallback);
	}

	/** 构建异槽多态扁平 Switch 守卫（double setter 版）。签名 {@code (Object, double) -> void}。 */
	public static MethodHandle buildFlatPolySwitchSetterDouble(PolySnapshot snap, MethodHandle fallback) {
		JSShape[] shapes  = snap.shapes();
		int[]     offsets = snap.offsets();
		int       n       = shapes.length;

		if (!SUPPORTS_TABLE_SWITCH || n <= 0) return buildFlatPolySwitchSetterDoubleLinear(shapes, offsets, n, fallback);

		try {
			int minId = Integer.MAX_VALUE, maxId = Integer.MIN_VALUE;
			for (JSShape s : shapes) {
				minId = Math.min(minId, s.id);
				maxId = Math.max(maxId, s.id);
			}
			int span = maxId - minId + 1;

			if (span <= n * 4 && span <= 64) {
				// Fallback: (Object, double) -> void  ==>  (int, Object, double) -> void
				MethodHandle fallbackWithSel = MethodHandles.dropArguments(fallback, 0, int.class);

				MethodHandle[] targets = new MethodHandle[span];
				Arrays.fill(targets, fallbackWithSel);

				for (int i = 0; i < n; i++) {
					int idx = shapes[i].id - minId;
					int off = offsets[i];
					MethodHandle fastSetter = off < 8
					 ? MH_SET_SLOT_DOUBLE[off]
					 : MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT_DOUBLE, 0, off);

					// JSShape.id 是通过 AtomicInteger 生成的全局唯一、不可变 ID。
					// 进入 targets[idx] 说明当前对象的 shape.id - minId 精确命中了该下标；如果不命中或为未记录的 Shape，早在 Selector 处就会返回 -1 跳入 defaultCase，或命中空洞槽位的 fallbackWithSel。
					// 因此，能跳转到 targets[idx]，其 Shape 数学上必然等于 shapes[i]
					// MethodHandle exactTest     = MH_IS_EXACT_SHAPE_SETTER_OBJECT.bindTo(shapes[i]);
					// MethodHandle guardedSetter = MethodHandles.guardWithTest(exactTest, fastSetter.asType(fallback.type()), fallback);

					targets[idx] = MethodHandles.dropArguments(fastSetter.asType(fallback.type()), 0, int.class);
				}

				MethodHandle ts       = invokeTableSwitch(fallbackWithSel, targets);
				MethodHandle selector = buildShapeIdSelector(minId, span);

				return MethodHandles.foldArguments(ts, selector);
			}
		} catch (Throwable ignored) { }

		return buildFlatPolySwitchSetterDoubleLinear(shapes, offsets, n, fallback);
	}

	// ── Selector builders ──────────────────────────────────────────────────────

	/**
	 * 构建通用 Getter/Setter 选择器：{@code (Object target) -> int}。
	 * 读取 {@code jsObj.shape.id - minId}，若越界或非 JSObject 返回 -1 触发 fallback。
	 */
	private static MethodHandle buildShapeIdSelector(int minId, int span) {
		return MethodHandles.insertArguments(
		 findStaticMH(JSLinker.class, "shapeIdSelector", MethodType.methodType(int.class, int.class, int.class, Object.class)),
		 0, minId, span
		);
	}

	/** 运行时 shape.id 选择器实现：(minId, span, Object target) -> int */
	public static int shapeIdSelector(int minId, int span, Object target) {
		if (target instanceof JSObject jsObj) {
			int idx = jsObj.shape.id - minId;
			// 无符号比较：若 idx < 0，转为无符号将是巨大的正数，自然 >= span
			if (Integer.compareUnsigned(idx, span) < 0) return idx;
		}
		return -1; // 负数强制命中 tableSwitch 的 defaultCase (fallback)
	}

	// ── 路线 B：线性扫描（低版本 JDK 兼容）──────────────────────────────────────

	/** 路线 B：Object getter 线性扫描静态代理。 */
	public static Object polyGetObject(JSShape[] shapes, int[] offsets, MethodHandle fallback, Object target)
	 throws Throwable {
		if (target instanceof JSObject jsObj) {
			JSShape s = jsObj.shape;
			for (int i = 0, n = shapes.length; i < n; i++) {
				if (s == shapes[i]) return jsObj.getSlot(offsets[i]);
			}
		}
		return fallback.invoke(target);
	}

	/** 路线 B：double getter 线性扫描静态代理。 */
	public static double polyGetDouble(JSShape[] shapes, int[] offsets, MethodHandle fallback, Object target)
	 throws Throwable {
		if (target instanceof JSObject jsObj) {
			JSShape s = jsObj.shape;
			for (int i = 0, n = shapes.length; i < n; i++) {
				if (s == shapes[i]) return jsObj.getDoubleSlot(offsets[i]);
			}
		}
		return (double) fallback.invoke(target);
	}

	/** 路线 B：int getter 线性扫描静态代理。 */
	public static int polyGetInt(JSShape[] shapes, int[] offsets, MethodHandle fallback, Object target)
	 throws Throwable {
		if (target instanceof JSObject jsObj) {
			JSShape s = jsObj.shape;
			for (int i = 0, n = shapes.length; i < n; i++) {
				if (s == shapes[i]) return JSOps.toInt(jsObj.getSlot(offsets[i]));
			}
		}
		return (int) fallback.invoke(target);
	}

	/** 路线 B：long getter 线性扫描静态代理。 */
	public static long polyGetLong(JSShape[] shapes, int[] offsets, MethodHandle fallback, Object target)
	 throws Throwable {
		if (target instanceof JSObject jsObj) {
			JSShape s = jsObj.shape;
			for (int i = 0, n = shapes.length; i < n; i++) {
				if (s == shapes[i]) return JSOps.toLong(jsObj.getSlot(offsets[i]));
			}
		}
		return (long) fallback.invoke(target);
	}
	private static MethodHandle buildFlatPolySwitchIntLinear(JSShape[] shapes, int[] offsets, int n,
	                                                         MethodHandle fallback) {
		try {
			MethodHandle base = LOOKUP.findStatic(JSLinker.class, "polyGetInt",
			 MethodType.methodType(int.class, JSShape[].class, int[].class, MethodHandle.class, Object.class));
			return MethodHandles.insertArguments(base, 0, shapes, offsets, fallback);
		} catch (Throwable t) { throw new RuntimeException(t); }
	}

	private static MethodHandle buildFlatPolySwitchLongLinear(JSShape[] shapes, int[] offsets, int n,
	                                                          MethodHandle fallback) {
		try {
			MethodHandle base = LOOKUP.findStatic(JSLinker.class, "polyGetLong",
			 MethodType.methodType(long.class, JSShape[].class, int[].class, MethodHandle.class, Object.class));
			return MethodHandles.insertArguments(base, 0, shapes, offsets, fallback);
		} catch (Throwable t) { throw new RuntimeException(t); }
	}

	/** 路线 B：Object setter 线性扫描静态代理。 */
	public static void polySetObject(JSShape[] shapes, int[] offsets, MethodHandle fallback, Object target, Object value)
	 throws Throwable {
		if (target instanceof JSObject jsObj) {
			JSShape s = jsObj.shape;
			for (int i = 0, n = shapes.length; i < n; i++) {
				if (s == shapes[i]) {
					jsObj.setSlot(offsets[i], value);
					return;
				}
			}
		}
		fallback.invoke(target, value);
	}

	/** 路线 B：double setter 线性扫描静态代理。 */
	public static void polySetDouble(JSShape[] shapes, int[] offsets, MethodHandle fallback, Object target, double value)
	 throws Throwable {
		if (target instanceof JSObject jsObj) {
			JSShape s = jsObj.shape;
			for (int i = 0, n = shapes.length; i < n; i++) {
				if (s == shapes[i]) {
					jsObj.setDoubleSlot(offsets[i], value);
					return;
				}
			}
		}
		fallback.invoke(target, value);
	}

	private static MethodHandle buildFlatPolySwitchObjectLinear(JSShape[] shapes, int[] offsets, int n,
	                                                            MethodHandle fallback) {
		try {
			MethodHandle base = LOOKUP.findStatic(JSLinker.class, "polyGetObject",
			 MethodType.methodType(Object.class, JSShape[].class, int[].class, MethodHandle.class, Object.class));
			return MethodHandles.insertArguments(base, 0, shapes, offsets, fallback);
		} catch (Throwable t) { throw new RuntimeException(t); }
	}

	private static MethodHandle buildFlatPolySwitchDoubleLinear(JSShape[] shapes, int[] offsets, int n,
	                                                            MethodHandle fallback) {
		try {
			MethodHandle base = LOOKUP.findStatic(JSLinker.class, "polyGetDouble",
			 MethodType.methodType(double.class, JSShape[].class, int[].class, MethodHandle.class, Object.class));
			return MethodHandles.insertArguments(base, 0, shapes, offsets, fallback);
		} catch (Throwable t) { throw new RuntimeException(t); }
	}

	private static MethodHandle buildFlatPolySwitchSetterObjectLinear(JSShape[] shapes, int[] offsets, int n,
	                                                                  MethodHandle fallback) {
		try {
			MethodHandle base = LOOKUP.findStatic(JSLinker.class, "polySetObject",
			 MethodType.methodType(void.class, JSShape[].class, int[].class, MethodHandle.class, Object.class, Object.class));
			return MethodHandles.insertArguments(base, 0, shapes, offsets, fallback);
		} catch (Throwable t) { throw new RuntimeException(t); }
	}

	private static MethodHandle buildFlatPolySwitchSetterDoubleLinear(JSShape[] shapes, int[] offsets, int n,
	                                                                  MethodHandle fallback) {
		try {
			MethodHandle base = LOOKUP.findStatic(JSLinker.class, "polySetDouble",
			 MethodType.methodType(void.class, JSShape[].class, int[].class, MethodHandle.class, Object.class, double.class));
			return MethodHandles.insertArguments(base, 0, shapes, offsets, fallback);
		} catch (Throwable t) { throw new RuntimeException(t); }
	}

	//endregion

	//region Multi-Shape Guard Stubs (同偏移多态坍缩快速守卫)

	/**
	 * O(1) 槽位属性归属验证：
	 * 验证当前对象在指定 offset 槽位上的属性确为 propId。
	 * 彻底摆脱 64 掩码上限约束，即使在万级 Shape 场景下依然提供硬件单内存读取 + 比较的极速验证。
	 */
	public static boolean isMatchPropAt(int propId, int offset, Object target) {
		return target instanceof JSObject jsObj && jsObj.shape.hasPropertyAt(propId, offset);
	}

	public static boolean isShapeN(JSShape[] shapes, Object target) {
		if (target instanceof JSObject jsObj) {
			JSShape s = jsObj.shape;
			for (JSShape shape : shapes) {
				if (s == shape) return true;
			}
		}
		return false;
	}

	private static MethodHandle buildMultiShapeGuard(JSShape[] shapes, int propId, int commonOff) {
		int n = shapes.length;
		if (n == 1) return MH_IS_EXACT_SHAPE.bindTo(shapes[0]);

		if (propId >= 0 && commonOff >= 0) {
			return MethodHandles.insertArguments(MH_IS_MATCH_PROP, 0, propId, commonOff);
		}

		return findStaticMH(JSLinker.class, "isShapeN", MethodType.methodType(boolean.class, JSShape[].class, Object.class)).bindTo(shapes);
	}

	public static boolean isShapeNSetterDouble(JSShape[] shapes, Object target, double val) {
		if (target instanceof JSObject jsObj) {
			JSShape s = jsObj.shape;
			for (JSShape shape : shapes) {
				if (s == shape) return true;
			}
		}
		return false;
	}

	private static MethodHandle buildMultiShapeGuardSetterDouble(JSShape[] shapes) {
		int n = shapes.length;
		if (n == 1) return MH_IS_EXACT_SHAPE_SETTER_DOUBLE.bindTo(shapes[0]);
		// Setter 严格沿用精确 Shape 比较，禁止松散位掩码，杜绝类型混淆与原始槽脏写
		return findStaticMH(JSLinker.class, "isShapeNSetterDouble", MethodType.methodType(boolean.class, JSShape[].class, Object.class, double.class)).bindTo(shapes);
	}

	public static boolean isShapeNSetterObject(JSShape[] shapes, Object target, Object val) {
		if (target instanceof JSObject jsObj) {
			JSShape s = jsObj.shape;
			for (JSShape shape : shapes) {
				if (s == shape) return true;
			}
		}
		return false;
	}

	private static MethodHandle buildMultiShapeGuardSetterObject(JSShape[] shapes) {
		int n = shapes.length;
		if (n == 1) return MH_IS_EXACT_SHAPE_SETTER_OBJECT.bindTo(shapes[0]);
		// Setter 严格沿用精确 Shape 比较，禁止松散位掩码
		return findStaticMH(JSLinker.class, "isShapeNSetterObject", MethodType.methodType(boolean.class, JSShape[].class, Object.class, Object.class)).bindTo(shapes);
	}

	/**
	 * 自适应 Fallback 句柄：
	 * 当已观测 Shape 数量 < 64 且 CallSite 未进入超态时，返回 initialFallback（继续捕获新 Shape 并触发动态重新快照/Relink）；
	 * 当 Shape 数量到达 64 阈值后，返回 megamorphicTarget 终结演化。
	 */
	private static MethodHandle getAdaptiveFallback(ChainedCallSite site) {
		return (site.getPolyCount() < 64 && site.getInitialFallback() != null)
		 ? site.getInitialFallback()
		 : (site.getMegamorphicTarget() != null ? site.getMegamorphicTarget() : site.getTarget());
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
		MethodHandle mh = findSpecializedBinaryOp(op, type);
		if (mh != null) {
			return new ConstantCallSite(mh.asType(type));
		}
		mh = switch (op) {
			case "+" -> OpMH.ADD;
			case "-" -> OpMH.SUB;
			case "*" -> OpMH.MUL;
			case "/" -> OpMH.DIV;
			case "%" -> OpMH.MOD;
			case "==" -> OpMH.EQ;
			case "===" -> OpMH.STRICT_EQ;
			case "!=" -> OpMH.NE;
			case "!==" -> OpMH.STRICT_NE;
			case "<" -> OpMH.LT;
			case "<=" -> OpMH.LTE;
			case ">" -> OpMH.GT;
			case ">=" -> OpMH.GTE;
			case "&&" -> OpMH.AND;
			case "||" -> OpMH.OR;
			case "&" -> OpMH.BIT_AND;
			case "|" -> OpMH.BIT_OR;
			case "^" -> OpMH.BIT_XOR;
			case "<<" -> OpMH.SHL;
			case ">>" -> OpMH.SHR;
			case ">>>" -> OpMH.USHR;
			default -> throw new IllegalArgumentException("Unknown binary operator: " + op);
		};
		return new ConstantCallSite(mh.asType(type));
	}

	private static MethodHandle findSpecializedBinaryOp(String op, MethodType type) {
		if (type.parameterCount() != 2) return null;
		Class<?> p0  = type.parameterType(0);
		Class<?> p1  = type.parameterType(1);
		Class<?> ret = type.returnType();

		if ("+".equals(op)) {
			if (p0 == double.class && p1 == double.class && ret == double.class) return OpMH.ADD_DD_D;
			if (p0 == int.class && p1 == int.class && ret == int.class) return OpMH.ADD_II_I;
			if (p0 == int.class && p1 == double.class && ret == double.class) return OpMH.ADD_ID_D;
			if (p0 == double.class && p1 == int.class && ret == double.class) return OpMH.ADD_DI_D;
			if (p0 == Object.class && p1 == double.class) return OpMH.ADD_OD_O;
			if (p0 == double.class && p1 == Object.class) return OpMH.ADD_DO_O;
			if (p0 == Object.class && p1 == int.class) return OpMH.ADD_OI_O;
			if (p0 == int.class && p1 == Object.class) return OpMH.ADD_IO_O;
			if (p0 == String.class && p1 == String.class) return OpMH.ADD_SS_S;
			if (p0 == String.class && p1 == Object.class) return OpMH.ADD_SO_S;
			if (p0 == Object.class && p1 == String.class) return OpMH.ADD_OS_S;
		} else if ("-".equals(op)) {
			if (p0 == double.class && p1 == double.class && ret == double.class) return OpMH.SUB_DD_D;
			if (p0 == int.class && p1 == int.class && ret == int.class) return OpMH.SUB_II_I;
			if (p0 == int.class && p1 == double.class && ret == double.class) return OpMH.SUB_ID_D;
			if (p0 == double.class && p1 == int.class && ret == double.class) return OpMH.SUB_DI_D;
			if (p0 == Object.class && p1 == double.class) return OpMH.SUB_OD_D;
			if (p0 == double.class && p1 == Object.class) return OpMH.SUB_DO_D;
		} else if ("*".equals(op)) {
			if (p0 == double.class && p1 == double.class && ret == double.class) return OpMH.MUL_DD_D;
			if (p0 == int.class && p1 == int.class && ret == int.class) return OpMH.MUL_II_I;
			if (p0 == int.class && p1 == double.class && ret == double.class) return OpMH.MUL_ID_D;
			if (p0 == double.class && p1 == int.class && ret == double.class) return OpMH.MUL_DI_D;
			if (p0 == Object.class && p1 == double.class) return OpMH.MUL_OD_D;
			if (p0 == double.class && p1 == Object.class) return OpMH.MUL_DO_D;
		} else if ("/".equals(op)) {
			if (p0 == double.class && p1 == double.class && ret == double.class) return OpMH.DIV_DD_D;
			if (p0 == int.class && p1 == int.class) return OpMH.DIV_II_D;
			if (p0 == int.class && p1 == double.class && ret == double.class) return OpMH.DIV_ID_D;
			if (p0 == double.class && p1 == int.class && ret == double.class) return OpMH.DIV_DI_D;
			if (p0 == Object.class && p1 == double.class) return OpMH.DIV_OD_D;
			if (p0 == double.class && p1 == Object.class) return OpMH.DIV_DO_D;
		} else if ("%".equals(op)) {
			if (p0 == double.class && p1 == double.class && ret == double.class) return OpMH.MOD_DD_D;
			if (p0 == int.class && p1 == int.class && ret == int.class) return OpMH.MOD_II_I;
			if (p0 == int.class && p1 == double.class && ret == double.class) return OpMH.MOD_ID_D;
			if (p0 == double.class && p1 == int.class && ret == double.class) return OpMH.MOD_DI_D;
			if (p0 == Object.class && p1 == double.class) return OpMH.MOD_OD_D;
			if (p0 == double.class && p1 == Object.class) return OpMH.MOD_DO_D;
		} else if ("==".equals(op)) {
			if (p0 == Object.class && p1 == int.class) return OpMH.EQ_OI_Z;
			if (p0 == Object.class && p1 == double.class) return OpMH.EQ_OD_Z;
			if (p0 == Object.class && p1 == boolean.class) return OpMH.EQ_OB_Z;
			if (p0 == Object.class && p1 == String.class) return OpMH.EQ_OS_Z;
		} else if ("===".equals(op)) {
			if (p0 == Object.class && p1 == int.class) return OpMH.STRICT_EQ_OI_Z;
			if (p0 == Object.class && p1 == double.class) return OpMH.STRICT_EQ_OD_Z;
			if (p0 == Object.class && p1 == boolean.class) return OpMH.STRICT_EQ_OB_Z;
			if (p0 == Object.class && p1 == String.class) return OpMH.STRICT_EQ_OS_Z;
		}
		return null;
	}

	public static CallSite bootstrapGetIndex(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type
	) {
		ChainedCallSite site = new ChainedCallSite(type, IndexMH.GET.asType(type));

		// 绑定 Fallback 处理器
		MethodHandle fallback = IndexMH.GET_FALLBACK.bindTo(site).asType(type);

		site.setInitialFallback(fallback);
		site.setTarget(fallback);
		return site;
	}

	public static CallSite bootstrapSetIndex(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type
	) {
		ChainedCallSite site = new ChainedCallSite(type, IndexMH.SET.asType(type));

		// 绑定 Fallback 处理器
		MethodHandle fallback = IndexMH.SET_FALLBACK.bindTo(site).asType(type);

		site.setInitialFallback(fallback);
		site.setTarget(fallback);
		return site;
	}
	//endregion

	//region Fallback 与 Inline Cache 实现

	/** 双重守卫：Shape 相同且 Key 相同（先做引用比较 ==，失败再做 equals，同时支持 JSSymbol） */
	@SuppressWarnings("EqualsReplaceableByObjectsCall")
	public static boolean isExactShapeAndKey(JSShape expectedShape, String expectedKey, Object target, Object key) {
		return target instanceof JSObject jsObj
		       && jsObj.shape == expectedShape
		       && (key == expectedKey || (key != null && (key.equals(expectedKey) || (key instanceof JSSymbol sym && sym.getKey().equals(expectedKey)))));
	}

	/** 字符串属性专用高速守卫：Shape 相同且 String Key 相同（先做引用比较 ==，失败再做 String.equals，无 JSSymbol 任何开销） */
	public static boolean isExactShapeAndStringKey(JSShape expectedShape, String expectedKey, Object target, Object key) {
		return target instanceof JSObject jsObj
		       && jsObj.shape == expectedShape
		       && (key == expectedKey || expectedKey.equals(key));
	}

	/** 符号属性专用单指令守卫：Shape 相同且 Symbol 引用指针完全一致（纯 == 比较） */
	public static boolean isExactShapeAndSymbol(JSShape expectedShape, JSSymbol expectedSymbol, Object target, Object key) {
		return target instanceof JSObject jsObj
		       && jsObj.shape == expectedShape
		       && key == expectedSymbol;
	}

	/** 原型链字符串属性守卫：Shape 相同、原型对象一致且 String Key 相同 */
	public static boolean isExactShapeAndProtoAndStringKey(JSShape expectedShape, JSObject expectedProto, String expectedKey, Object target, Object key) {
		return target instanceof JSObject jsObj
		       && jsObj.shape == expectedShape
		       && jsObj.getPrototype() == expectedProto
		       && (key == expectedKey || (key instanceof String s && expectedKey.equals(s)));
	}

	/** 原型链符号属性守卫：Shape 相同、原型对象一致且 Symbol 引用指针完全一致 */
	public static boolean isExactShapeAndProtoAndSymbol(JSShape expectedShape, JSObject expectedProto, JSSymbol expectedSymbol, Object target, Object key) {
		return target instanceof JSObject jsObj
		       && jsObj.shape == expectedShape
		       && jsObj.getPrototype() == expectedProto
		       && key == expectedSymbol;
	}

	/** 动态对象索引读取的通用 Fallback 入口 */
	public static Object getIndexDynamicFallback(ChainedCallSite site, Object target, Object index) throws Throwable {
		if (target instanceof JSContext.JSGlobalThis globalThis) {
			return globalThis.get(toPropertyKey(index));
		}
		if (target instanceof JSObject jsObj) {
			if (index instanceof String strKey) {
				JSShape s      = jsObj.shape;
				int     offset = s.getOffset(strKey);

				// 只有当属性命中且非 accessor 且缓存深度 < 3 时挂载 String Keyed IC 单态/多态分支
				if (offset >= 0 && (!s.hasAccessors || !s.isAccessor(offset)) && site.getChainDepth() < 3) {
					MethodHandle test = LOOKUP.findStatic(
					 JSLinker.class,
					 "isExactShapeAndStringKey",
					 MethodType.methodType(boolean.class, JSShape.class, String.class, Object.class, Object.class)
					).bindTo(s).bindTo(strKey);

					// 构造极速直读 Handle：(target, key) -> target.getSlot(offset)
					MethodHandle getter = offset < 8
					 ? MH_GET_SLOT_OBJECT[offset]
					 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, offset);
					// 丢弃第 1 个参数 key，适配签名 (Object, Object) -> Object
					MethodHandle directTarget = MethodHandles.dropArguments(getter, 1, Object.class);

					site.installGuardOrSwitchMegamorphic(test, directTarget.asType(site.type()));
					return jsObj.getSlot(offset);
				} else if (offset < 0 && site.getChainDepth() < 3) {
					JSObject proto = jsObj.getPrototype();
					if (proto != null) {
						JSObject current = proto;
						JSObject holder = null;
						int holderOffset = -1;
						List<JSObject> chain = null;

						while (current != null) {
							int pOff = current.shape.getOffset(strKey);
							if (pOff >= 0 && (current.isDoubleSlot(pOff) || current.getRawObjectSlot(pOff) != JSObject.NOT_FOUND)) {
								holder = current;
								holderOffset = pOff;
								break;
							}
							if (chain == null) chain = new ArrayList<>(2);
							chain.add(current);
							current = current.getPrototype();
						}

						if (holder != null && (!holder.shape.hasAccessors || !holder.shape.isAccessor(holderOffset))) {
							Object val = holder.getSlot(holderOffset);
							MethodHandle test = LOOKUP.findStatic(
							 JSLinker.class,
							 "isExactShapeAndProtoAndStringKey",
							 MethodType.methodType(boolean.class, JSShape.class, JSObject.class, String.class, Object.class, Object.class)
							).bindTo(s).bindTo(proto).bindTo(strKey);

							MethodHandle fb = site.getInitialFallback();
							MethodHandle fbTyped = (fb != null) ? fb.asType(site.type()) : null;
							MethodHandle constTarget = MethodHandles.dropArguments(
								MethodHandles.constant(Object.class, val), 0, site.type().parameterList()
							).asType(site.type());
							if (chain != null && fbTyped != null) {
								for (JSObject p : chain) {
									constTarget = p.getOrCreateProtoSwitchPoint().guardWithTest(constTarget, fbTyped);
								}
							}
							site.installProtoGuard(s, holder.getOrCreateProtoSwitchPoint(), test, constTarget);
							return val;
						}
					}
				}
			} else if (index instanceof JSSymbol symKey) {
				JSShape s      = jsObj.shape;
				int     offset = s.getOffset(symKey.getSymbolId());

				// 针对 Symbol 进行引用恒等比较的单指令 IC 守卫（非 accessor）
				if (offset >= 0 && (!s.hasAccessors || !s.isAccessor(offset)) && site.getChainDepth() < 3) {
					MethodHandle test = LOOKUP.findStatic(
					 JSLinker.class,
					 "isExactShapeAndSymbol",
					 MethodType.methodType(boolean.class, JSShape.class, JSSymbol.class, Object.class, Object.class)
					).bindTo(s).bindTo(symKey);

					MethodHandle getter = offset < 8
					 ? MH_GET_SLOT_OBJECT[offset]
					 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, offset);
					MethodHandle directTarget = MethodHandles.dropArguments(getter, 1, Object.class);

					site.installGuardOrSwitchMegamorphic(test, directTarget.asType(site.type()));
					return jsObj.getSlot(offset);
				} else if (offset < 0 && site.getChainDepth() < 3) {
					JSObject proto = jsObj.getPrototype();
					if (proto != null) {
						JSObject current = proto;
						JSObject holder = null;
						int holderOffset = -1;
						List<JSObject> chain = null;

						while (current != null) {
							int pOff = current.shape.getOffset(symKey.getSymbolId());
							if (pOff >= 0 && (current.isDoubleSlot(pOff) || current.getRawObjectSlot(pOff) != JSObject.NOT_FOUND)) {
								holder = current;
								holderOffset = pOff;
								break;
							}
							if (chain == null) chain = new ArrayList<>(2);
							chain.add(current);
							current = current.getPrototype();
						}

						if (holder != null && (!holder.shape.hasAccessors || !holder.shape.isAccessor(holderOffset))) {
							Object val = holder.getSlot(holderOffset);
							MethodHandle test = LOOKUP.findStatic(
							 JSLinker.class,
							 "isExactShapeAndProtoAndSymbol",
							 MethodType.methodType(boolean.class, JSShape.class, JSObject.class, JSSymbol.class, Object.class, Object.class)
							).bindTo(s).bindTo(proto).bindTo(symKey);

							MethodHandle fb = site.getInitialFallback();
							MethodHandle fbTyped = (fb != null) ? fb.asType(site.type()) : null;
							MethodHandle constTarget = MethodHandles.dropArguments(
								MethodHandles.constant(Object.class, val), 0, site.type().parameterList()
							).asType(site.type());
							if (chain != null && fbTyped != null) {
								for (JSObject p : chain) {
									constTarget = p.getOrCreateProtoSwitchPoint().guardWithTest(constTarget, fbTyped);
								}
							}
							site.installProtoGuard(s, holder.getOrCreateProtoSwitchPoint(), test, constTarget);
							return val;
						}
					}
				}
			}
		}
		if (target instanceof JSArray) {
			MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
			if (site.type().parameterCount() > 1) {
				test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
			}
			site.installGuardOrSwitchMegamorphic(test, MH_GET_INDEX_JS_ARRAY.asType(site.type()));
			return getIndexJSArray(target, index);
		}
		if (target instanceof List) {
			MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
			if (site.type().parameterCount() > 1) {
				test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
			}
			site.installGuardOrSwitchMegamorphic(test, MH_GET_INDEX_LIST.asType(site.type()));
			return getIndexList(target, index);
		}
		if (target != null && target.getClass().isArray()) {
			MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
			if (site.type().parameterCount() > 1) {
				test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
			}
			MethodHandle directTarget;
			if (target instanceof Object[]) directTarget = MH_GET_INDEX_OBJECT_ARRAY;
			else if (target instanceof int[]) directTarget = MH_GET_INDEX_INT_ARRAY;
			else if (target instanceof double[]) directTarget = MH_GET_INDEX_DOUBLE_ARRAY;
			else if (target instanceof long[]) directTarget = MH_GET_INDEX_LONG_ARRAY;
			else directTarget = MH_GET_INDEX_PRIMITIVE_ARRAY;

			site.installGuardOrSwitchMegamorphic(test, directTarget.asType(site.type()));
			if (target instanceof Object[]) return getIndexObjectArray(target, index);
			if (target instanceof int[]) return getIndexIntArray(target, index);
			if (target instanceof double[]) return getIndexDoubleArray(target, index);
			if (target instanceof long[]) return getIndexLongArray(target, index);
			return getIndexPrimitiveArray(target, index);
		}
		if (target instanceof Map) {
			MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
			if (site.type().parameterCount() > 1) {
				test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
			}
			site.installGuardOrSwitchMegamorphic(test, MH_GET_INDEX_MAP.asType(site.type()));
			return getIndexMap(target, index);
		}
		// 降级走原有的全量查找
		return getIndex(target, index);
	}

	/** 动态对象索引写入的通用 Fallback 入口 */
	public static void setIndexDynamicFallback(ChainedCallSite site, Object target, Object index, Object value) throws Throwable {
		if (target == null || target == JSUndefined.INSTANCE) return;
		if (target instanceof JSContext.JSGlobalThis globalThis) {
			globalThis.put(toPropertyKey(index), value);
			return;
		}
		if (target instanceof JSObject jsObj) {
			boolean isPrototype = jsObj.getProtoSwitchPoint() != null || jsObj == JSContext.LazyArray.ARRAY_PROTOTYPE;
			if (index instanceof String strKey) {
				JSShape s      = jsObj.shape;
				int     offset = s.getOffset(strKey);

				if (offset >= 0 && (!s.hasAccessors || !s.isAccessor(offset)) && s.isWritable(offset) && site.getChainDepth() < 3) {
					byte type = s.getSlotType(offset);
					byte currentBaseType = (byte) (type & JSShape.TYPE_MASK);
					byte newBaseType = (value instanceof Number) ? JSShape.TYPE_DOUBLE : JSShape.TYPE_OBJECT;
					if (currentBaseType == newBaseType) {
						boolean isDouble = currentBaseType == JSShape.TYPE_DOUBLE;
						if (!isPrototype) {
							MethodHandle test = LOOKUP.findStatic(
								JSLinker.class,
								"isExactShapeAndStringKey",
								MethodType.methodType(boolean.class, JSShape.class, String.class, Object.class, Object.class)
							).bindTo(s).bindTo(strKey);
							test = MethodHandles.dropArguments(test, 2, Object.class);

							MethodHandle directSlotSetter;
							if (offset < 8) {
								directSlotSetter = isDouble ? MH_SET_SLOT_DOUBLE_AS_OBJ[offset] : MH_SET_SLOT_OBJECT[offset];
							} else {
								directSlotSetter = isDouble
									? MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT_DOUBLE_AS_OBJ, 0, offset)
									: MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT, 0, offset);
							}
							MethodHandle directTarget = MethodHandles.dropArguments(directSlotSetter, 1, Object.class);
							site.installGuardOrSwitchMegamorphic(test, directTarget.asType(site.type()));
						}
						if (isDouble) {
							jsObj.setDoubleSlot(offset, JSOps.toDouble(value));
						} else {
							jsObj.setSlot(offset, value);
						}
						jsObj.onStructuralOrPropertyChange();
						return;
					}
				}
			} else if (index instanceof JSSymbol symKey) {
				JSShape s      = jsObj.shape;
				int     offset = s.getOffset(symKey.getSymbolId());

				if (offset >= 0 && (!s.hasAccessors || !s.isAccessor(offset)) && s.isWritable(offset) && site.getChainDepth() < 3) {
					byte type = s.getSlotType(offset);
					byte currentBaseType = (byte) (type & JSShape.TYPE_MASK);
					byte newBaseType = (value instanceof Number) ? JSShape.TYPE_DOUBLE : JSShape.TYPE_OBJECT;
					if (currentBaseType == newBaseType) {
						boolean isDouble = currentBaseType == JSShape.TYPE_DOUBLE;
						if (!isPrototype) {
							MethodHandle test = LOOKUP.findStatic(
								JSLinker.class,
								"isExactShapeAndSymbol",
								MethodType.methodType(boolean.class, JSShape.class, JSSymbol.class, Object.class, Object.class)
							).bindTo(s).bindTo(symKey);
							test = MethodHandles.dropArguments(test, 2, Object.class);

							MethodHandle directSlotSetter;
							if (offset < 8) {
								directSlotSetter = isDouble ? MH_SET_SLOT_DOUBLE_AS_OBJ[offset] : MH_SET_SLOT_OBJECT[offset];
							} else {
								directSlotSetter = isDouble
									? MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT_DOUBLE_AS_OBJ, 0, offset)
									: MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT, 0, offset);
							}
							MethodHandle directTarget = MethodHandles.dropArguments(directSlotSetter, 1, Object.class);
							site.installGuardOrSwitchMegamorphic(test, directTarget.asType(site.type()));
						}
						if (isDouble) {
							jsObj.setDoubleSlot(offset, JSOps.toDouble(value));
						} else {
							jsObj.setSlot(offset, value);
						}
						jsObj.onStructuralOrPropertyChange();
						return;
					}
				}
			}
		}
		if (target instanceof JSArray) {
			MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
			if (site.type().parameterCount() > 1) {
				test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
			}
			site.installGuardOrSwitchMegamorphic(test, MH_SET_INDEX_JS_ARRAY.asType(site.type()));
			setIndexJSArray(target, index, value);
			return;
		}
		if (target instanceof List) {
			MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
			if (site.type().parameterCount() > 1) {
				test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
			}
			site.installGuardOrSwitchMegamorphic(test, MH_SET_INDEX_LIST.asType(site.type()));
			setIndexList(target, index, value);
			return;
		}
		if (target != null && target.getClass().isArray()) {
			MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
			if (site.type().parameterCount() > 1) {
				test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
			}
			MethodHandle directTarget;
			if (target instanceof Object[]) directTarget = MH_SET_INDEX_OBJECT_ARRAY;
			else if (target instanceof int[]) directTarget = MH_SET_INDEX_INT_ARRAY;
			else if (target instanceof double[]) directTarget = MH_SET_INDEX_DOUBLE_ARRAY;
			else if (target instanceof long[]) directTarget = MH_SET_INDEX_LONG_ARRAY;
			else directTarget = MH_SET_INDEX_PRIMITIVE_ARRAY;

			site.installGuardOrSwitchMegamorphic(test, directTarget.asType(site.type()));
			if (target instanceof Object[]) setIndexObjectArray(target, index, value);
			else if (target instanceof int[]) setIndexIntArray(target, index, value);
			else if (target instanceof double[]) setIndexDoubleArray(target, index, value);
			else if (target instanceof long[]) setIndexLongArray(target, index, value);
			else setIndexPrimitiveArray(target, index, value);
			return;
		}
		if (target instanceof Map) {
			MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
			if (site.type().parameterCount() > 1) {
				test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
			}
			site.installGuardOrSwitchMegamorphic(test, MH_SET_INDEX_MAP.asType(site.type()));
			setIndexMap(target, index, value);
			return;
		}
		setIndex(target, index, value);
	}

	public static Object getPropMegamorphic(ChainedCallSite site, Object target, String propName) {
		if (target instanceof JSObject jsObj) {
			JSShape s     = jsObj.shape;
			long[]  cache = site.directCache;
			if (cache == null) cache = site.getOrCreateDirectCache();
			int     idx   = ChainedCallSite.cacheIndex(s.id);

			// 64-bit 严格原子读取，防指令重排与 32 位 JVM 字撕裂
			long entry = (long) ChainedCallSite.CACHE_VH.getOpaque(cache, idx);
			if (entry != 0L && (int) (entry >>> 32) == s.id) {
				if (ChainedCallSite.ENABLE_STATS) ChainedCallSite.STATS_HITS.increment();
				int offset = (int) entry;
				Object raw = jsObj.getRawObjectSlot(offset);
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
			int     idx   = ChainedCallSite.cacheIndex(s.id);

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
			int     idx   = ChainedCallSite.cacheIndex(s.id);

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
			int     idx   = ChainedCallSite.cacheIndex(s.id);

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
			int     idx   = ChainedCallSite.cacheIndex(s.id);

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
			int     idx   = ChainedCallSite.cacheIndex(s.id);

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
			return (double) java.lang.reflect.Array.getLength(target);
		}

		boolean isStatic = false;
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
				boolean hasStatic = candidates.stream().anyMatch(m -> Modifier.isStatic(m.getModifiers()));
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

		boolean isStatic = false;
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
					int          commonOff    = site.getCommonOffset();
					byte         commonType   = site.getCommonType();
					MethodHandle test         = buildMultiShapeGuard(site.getRecordedShapesArray(), site.getPropId(), commonOff);

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
					MethodHandle test = MH_IS_EXACT_SHAPE.bindTo(shape);
					boolean isDoubleSlot = (type & JSShape.TYPE_MASK) == JSShape.TYPE_DOUBLE;
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
				JSObject current = proto;
				JSObject holder = null;
				int holderOffset = -1;
				List<JSObject> chain = null;

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
					byte slotType = holder.shape.getSlotType(holderOffset);
					MethodHandle test = MH_IS_EXACT_SHAPE_AND_PROTO.bindTo(shape).bindTo(proto);
					MethodHandle fb = site.getInitialFallback();
					MethodHandle fbTyped = (fb != null) ? fb.asType(site.type()) : null;

					if ((slotType & JSShape.FLAG_ACCESSOR) != 0) {
						Object raw = holder.getRawObjectSlot(holderOffset);
						if (raw instanceof PropertyAccessor acc) {
							MethodHandle getterTarget = MethodHandles.insertArguments(MH_GET_PROTO_ACCESSOR_PROP, 0, acc).asType(site.type());
							List<SwitchPoint> allSps = new ArrayList<>(chain != null ? chain.size() + 1 : 1);
							if (chain != null && fbTyped != null) {
								for (JSObject p : chain) {
									SwitchPoint pSp = p.getOrCreateProtoSwitchPoint();
									getterTarget = pSp.guardWithTest(getterTarget, fbTyped);
									allSps.add(pSp);
								}
							}
							SwitchPoint holderSp = holder.getOrCreateProtoSwitchPoint();
							allSps.add(holderSp);
							site.installProtoGuard(shape, holderSp, allSps, test, getterTarget);
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
						site.installProtoGuard(shape, holderSp, allSps, test, constTarget);
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
				return (double) java.lang.reflect.Array.getLength(target);
			}
		}

		boolean isStatic = false;
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
					MethodHandle mh = Magic.lookup.unreflect(getterMethod);
					MethodHandle directGetter = MethodHandles.dropArguments(mh, 0, Object.class).asType(site.type());
					site.installGuardOrSwitchMegamorphic(test, directGetter);
					return directGetter.invoke(target);
				} catch (Throwable ignored) {
				}
			}

			List<Method> candidates = MethodResolver.findCandidateMethods(targetClass, propName);
			if (!candidates.isEmpty() && candidates.stream().anyMatch(m -> Modifier.isStatic(m.getModifiers()))) {
				int arity = candidates.stream().filter(m -> Modifier.isStatic(m.getModifiers())).mapToInt(Method::getParameterCount).min().orElse(0);
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
		SPREADER_DATA.remove(clazz);
		INTERFACE_FILTER_CACHE.remove(clazz);
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
				byte newBaseType = (value instanceof Number) ? JSShape.TYPE_DOUBLE : JSShape.TYPE_OBJECT;
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

		boolean isStatic = false;
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
					Class<?> fType = field.getType();
					MethodHandle filter = getArgumentFilter(fType);
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
					Class<?> pType = setterMethod.getParameterTypes()[0];
					MethodHandle filter = getArgumentFilter(pType);
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
				MethodHandle mh = Magic.lookup.unreflect(setterMethod);
				Class<?> paramType = setterMethod.getParameterTypes()[0];
				MethodHandle filter = getArgumentFilter(paramType);
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

		boolean isStatic = false;
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
					Class<?> fType = field.getType();
					MethodHandle filter = getArgumentFilter(fType);
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
					Class<?> pType = setterMethod.getParameterTypes()[0];
					MethodHandle filter = getArgumentFilter(pType);
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
				MethodHandle mh = Magic.lookup.unreflect(setterMethod);
				Class<?> paramType = setterMethod.getParameterTypes()[0];
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

	//region Dynalink / JLS 规范级重载决议 (Overload Resolution)

	private static int getPrimitiveTypeIndex(Class<?> c) {
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

	private static int getInheritanceDistance(Class<?> from, Class<?> to) {
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

	private static int getHierarchyDepth(Class<?> clazz) {
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
			// 越具体的类型（继承深度越深）在匹配 null 时优先级越高 (Cost 越低)
			return Math.max(1, 100 - getHierarchyDepth(targetType));
		}

		Class<?> fromType = arg.getClass();
		if (fromType == targetType) return 0;

		// 0. 接口适配 (SAM 函数式接口 / JSObject 动态代理)
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

		// 1. 引用类型子类型继承关系
		if (targetType.isAssignableFrom(fromType)) {
			if (targetType == Object.class) return 50;
			return getInheritanceDistance(fromType, targetType);
		}

		// 2. 基本类型与包装类型转换
		int fromPrim = getPrimitiveTypeIndex(fromType);
		int toPrim   = getPrimitiveTypeIndex(targetType);

		if (fromPrim >= 0 && toPrim >= 0) {
			// boolean 单独处理
			if (fromPrim == 7 || toPrim == 7) {
				return (fromPrim == toPrim) ? 1 : MethodResolver.COST_INCOMPATIBLE;
			}
			// 同一种基本类型的装箱/拆箱 (e.g. Integer -> int, Double -> double)
			if (fromPrim == toPrim) return 1;

			// JLS §5.1.2 基本类型无损拓宽 (Widening Primitive Conversion)
			// byte(0) -> short(1) -> int(3) -> long(4) -> float(5) -> double(6), char(2) -> int(3)
			if (fromPrim == 2 && toPrim >= 3) { // char -> int/long/float/double
				return 2 + (toPrim - 3);
			}
			if (fromPrim < toPrim && toPrim != 2) {
				return 2 + (toPrim - fromPrim);
			}

			// JS 动态数字无损收窄 (JS Double 实际上是整型值，如 10.0 -> int)
			if (arg instanceof Number num) {
				double d = num.doubleValue();
				if (Double.isFinite(d) && d == Math.floor(d)) {
					if (toPrim == 3 && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) return 3;
					if (toPrim == 4 && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) return 3;
					if (toPrim == 1 && d >= Short.MIN_VALUE && d <= Short.MAX_VALUE) return 4;
					if (toPrim == 0 && d >= Byte.MIN_VALUE && d <= Byte.MAX_VALUE) return 5;
				}
				// 浮点转整型的有损收窄
				return 20;
			}
		}

		// 3. String / CharSequence / char
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
				} else if (t1.isPrimitive()/*  && t2.isPrimitive() */) {
					int idx1 = getPrimitiveTypeIndex(t1);
					int idx2 = getPrimitiveTypeIndex(t2);
					if (idx1 >= 0 && idx2 >= 0 && idx1 < idx2) {
						oneMoreSpecific = true;
					} else {
						return false;
					}
				} else {
					// 如果 t1 不能转换为 t2，说明 m1 在此参数上不比 m2 更具体，必须返回 false
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
		Object fn = getIndex(target, index);
		if (fn instanceof JSFunction func) {
			return func.call(JSContext.CURRENT.get(), target, args);
		}
		throw JSContext.makeTypeError(JSArray.toPropertyKey(index) + " is not a function");
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
		int paramCount = paramTypes.length;
		Class<?> varargArrayType = paramTypes[paramCount - 1];
		Class<?> elemType = varargArrayType.getComponentType();

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

		int varargLen = Math.max(0, args.length - (paramCount - 1));
		Object varargArr = Array.newInstance(elemType, varargLen);
		for (int i = 0; i < varargLen; i++) {
			Object raw = args[paramCount - 1 + i];
			Array.set(varargArr, i, JSOps.castValue(raw, elemType));
		}
		packed[paramCount - 1] = varargArr;
		return packed;
	}

	private static Object invokeMatchedMethod(Object target, Method targetMethod, Object[] args, Class<?> clazz, String methodName) throws Throwable {
		try {
			targetMethod.setAccessible(true);
		} catch (Throwable ignored) {
		}
		Class<?>[] paramTypes = targetMethod.getParameterTypes();
		boolean isVoid = (targetMethod.getReturnType() == void.class);

		if (targetMethod.isVarArgs()) {
			Object[] packedArgs = packVarArgs(paramTypes, args);
			Object res = targetMethod.invoke(target, packedArgs);
			return isVoid ? JSUndefined.INSTANCE : res;
		}

		int arity = args.length;
		MagicJIT.MagicInvoker invoker = MagicJIT.getMethodInvoker(clazz, targetMethod);
		if (invoker != null) {
			Object res = switch (arity) {
				case 0 -> invoker.invoke0(target);
				case 1 -> invoker.invoke1(target, JSOps.castValue(args[0], paramTypes[0]));
				case 2 -> invoker.invoke2(target, JSOps.castValue(args[0], paramTypes[0]), JSOps.castValue(args[1], paramTypes[1]));
				case 3 -> invoker.invoke3(target, JSOps.castValue(args[0], paramTypes[0]), JSOps.castValue(args[1], paramTypes[1]), JSOps.castValue(args[2], paramTypes[2]));
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

	private static Object slowOwnMethod(JSObject obj, int offset, Object[] args) throws Throwable {
		int propId = obj.shape.getPropertyId(offset);
		String propName = propId >= 0 ? SymbolTable.name(propId) : "method";
		Object member = obj.get(propName);
		if (member instanceof JSFunction fn) {
			return fn.call(null, obj, args != null ? args : new Object[0]);
		}
		throw new RuntimeException("TypeError: " + (obj != null ? obj.toString() : "object") + "." + propName + " is not a function");
	}

	public static Object callOwnMethod0(int offset, Object target) throws Throwable {
		JSObject obj = (JSObject) target;
		Object raw = (offset < 8) ? obj.getRawObjectSlot(offset) : obj.getSlot(offset);
		if (raw instanceof JSFunction fn) {
			return fn.call0(null, obj);
		}
		return slowOwnMethod(obj, offset, null);
	}

	public static Object callOwnMethod1(int offset, Object target, Object a0) throws Throwable {
		JSObject obj = (JSObject) target;
		Object raw = (offset < 8) ? obj.getRawObjectSlot(offset) : obj.getSlot(offset);
		if (raw instanceof JSFunction fn) {
			return fn.call1(null, obj, a0);
		}
		return slowOwnMethod(obj, offset, new Object[]{ a0 });
	}

	public static Object callOwnMethod2(int offset, Object target, Object a0, Object a1) throws Throwable {
		JSObject obj = (JSObject) target;
		Object raw = (offset < 8) ? obj.getRawObjectSlot(offset) : obj.getSlot(offset);
		if (raw instanceof JSFunction fn) {
			return fn.call2(null, obj, a0, a1);
		}
		return slowOwnMethod(obj, offset, new Object[]{ a0, a1 });
	}

	public static Object callOwnMethod3(int offset, Object target, Object a0, Object a1, Object a2) throws Throwable {
		JSObject obj = (JSObject) target;
		Object raw = (offset < 8) ? obj.getRawObjectSlot(offset) : obj.getSlot(offset);
		if (raw instanceof JSFunction fn) {
			return fn.call3(null, obj, a0, a1, a2);
		}
		return slowOwnMethod(obj, offset, new Object[]{ a0, a1, a2 });
	}

	public static Object callOwnMethod4(int offset, Object target, Object a0, Object a1, Object a2, Object a3) throws Throwable {
		JSObject obj = (JSObject) target;
		Object raw = (offset < 8) ? obj.getRawObjectSlot(offset) : obj.getSlot(offset);
		if (raw instanceof JSFunction fn) {
			return fn.call4(null, obj, a0, a1, a2, a3);
		}
		return slowOwnMethod(obj, offset, new Object[]{ a0, a1, a2, a3 });
	}

	public static Object callOwnMethodN(int offset, Object target, Object[] args) throws Throwable {
		JSObject obj = (JSObject) target;
		Object raw = (offset < 8) ? obj.getRawObjectSlot(offset) : obj.getSlot(offset);
		if (raw instanceof JSFunction fn) {
			return fn.call(null, obj, args);
		}
		return slowOwnMethod(obj, offset, args);
	}

	public static Object invokeFallback(ChainedCallSite site, Object target, Object[] args, String methodName)
	 throws Throwable {
		if (target == null || target == JSUndefined.INSTANCE) {
			throw new NullPointerException("Cannot invoke method '" + methodName + "' on null/undefined");
		}

		if (target instanceof JSFunction func && "$invoke$".equals(methodName)) {
			int          arity = args.length;
			MethodHandle directMh;
			if (arity == 0) {
				directMh = JSFuncMH.CALL0;
				directMh = MethodHandles.insertArguments(directMh, 1, (JSContext) null, JSUndefined.INSTANCE);
			} else if (arity == 1) {
				directMh = JSFuncMH.CALL1;
				directMh = MethodHandles.insertArguments(directMh, 1, (JSContext) null, JSUndefined.INSTANCE);
			} else if (arity == 2) {
				directMh = JSFuncMH.CALL2;
				directMh = MethodHandles.insertArguments(directMh, 1, (JSContext) null, JSUndefined.INSTANCE);
			} else if (arity == 3) {
				directMh = JSFuncMH.CALL3;
				directMh = MethodHandles.insertArguments(directMh, 1, (JSContext) null, JSUndefined.INSTANCE);
			} else if (arity == 4) {
				directMh = JSFuncMH.CALL4;
				directMh = MethodHandles.insertArguments(directMh, 1, (JSContext) null, JSUndefined.INSTANCE);
			} else {
				directMh = JSFuncMH.CALL;
				directMh = MethodHandles.insertArguments(directMh, 1, (JSContext) null, JSUndefined.INSTANCE);
				directMh = directMh.asCollector(1, Object[].class, arity);
			}

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
			if (arity == 0) return func.call0(cx, JSUndefined.INSTANCE);
			if (arity == 1) return func.call1(cx, JSUndefined.INSTANCE, args[0]);
			if (arity == 2) return func.call2(cx, JSUndefined.INSTANCE, args[0], args[1]);
			if (arity == 3) return func.call3(cx, JSUndefined.INSTANCE, args[0], args[1], args[2]);
			if (arity == 4) return func.call4(cx, JSUndefined.INSTANCE, args[0], args[1], args[2], args[3]);
			return func.call(cx, JSUndefined.INSTANCE, args);
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
			int          arity = args.length;
			MethodHandle directMh;
			if (arity == 0) {
				directMh = JSFuncMH.CALL0;
				directMh = MethodHandles.insertArguments(directMh, 1, (JSContext) null, JSUndefined.INSTANCE);
			} else if (arity == 1) {
				directMh = JSFuncMH.CALL0;
				directMh = MethodHandles.insertArguments(directMh, 1, (JSContext) null);
			} else if (arity == 2) {
				directMh = JSFuncMH.CALL1;
				directMh = MethodHandles.insertArguments(directMh, 1, (JSContext) null);
			} else if (arity == 3) {
				directMh = JSFuncMH.CALL2;
				directMh = MethodHandles.insertArguments(directMh, 1, (JSContext) null);
			} else if (arity == 4) {
				directMh = JSFuncMH.CALL3;
				directMh = MethodHandles.insertArguments(directMh, 1, (JSContext) null);
			} else if (arity == 5) {
				directMh = JSFuncMH.CALL4;
				directMh = MethodHandles.insertArguments(directMh, 1, (JSContext) null);
			} else {
				directMh = JSFuncMH.CALL;
				directMh = MethodHandles.insertArguments(directMh, 1, (JSContext) null);
				directMh = directMh.asCollector(2, Object[].class, arity - 1);
			}

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
			Object thisArg = arity > 0 && args[0] != null ? args[0] : JSUndefined.INSTANCE;
			if (arity == 0 || arity == 1) return func.call0(cx, thisArg);
			if (arity == 2) return func.call1(cx, thisArg, args[1]);
			if (arity == 3) return func.call2(cx, thisArg, args[1], args[2]);
			if (arity == 4) return func.call3(cx, thisArg, args[1], args[2], args[3]);
			if (arity == 5) return func.call4(cx, thisArg, args[1], args[2], args[3], args[4]);
			return func.call(cx, thisArg, Arrays.copyOfRange(args, 1, arity));
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
					JSObject proto = (ownOffset < 0) ? jsObj.getPrototype() : null;
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
						int          arity = args.length;
						MethodHandle exactFuncCall = null;
						if (jsObj instanceof JSArray && ownOffset < 0 && BuiltinProtector.isArrayProtoValid()) {
							if ("push".equals(methodName)) {
								if (arity == 0) exactFuncCall = MH_JS_ARRAY_FAST_PUSH0;
								else if (arity == 1) exactFuncCall = MH_JS_ARRAY_FAST_PUSH1;
								else if (arity == 2) exactFuncCall = MH_JS_ARRAY_FAST_PUSH2;
							} else if ("pop".equals(methodName) && arity == 0) {
								exactFuncCall = MH_JS_ARRAY_FAST_POP0;
							}
						}
						if (exactFuncCall == null) {
							if (arity == 0) {
								exactFuncCall = MethodHandles.insertArguments(JSFuncMH.CALL0, 1, (Object) null).bindTo(func);
							} else if (arity == 1) {
								exactFuncCall = MethodHandles.insertArguments(JSFuncMH.CALL1, 1, (Object) null).bindTo(func);
							} else if (arity == 2) {
								exactFuncCall = MethodHandles.insertArguments(JSFuncMH.CALL2, 1, (Object) null).bindTo(func);
							} else if (arity == 3) {
								exactFuncCall = MethodHandles.insertArguments(JSFuncMH.CALL3, 1, (Object) null).bindTo(func);
							} else if (arity == 4) {
								exactFuncCall = MethodHandles.insertArguments(JSFuncMH.CALL4, 1, (Object) null).bindTo(func);
							} else {
								exactFuncCall = MethodHandles.insertArguments(JSFuncMH.CALL, 1, (Object) null)
								 .bindTo(func)
								 .asCollector(1, Object[].class, arity);
							}
						}
						if (ownOffset < 0 && proto != null) {
							JSObject current = proto;
							JSObject holder = null;
							List<JSObject> chain = null;
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
								MethodHandle fb = site.getInitialFallback();
								MethodHandle fbTyped = (fb != null) ? fb.asType(site.type()) : null;
								MethodHandle guardedCall = exactFuncCall.asType(site.type());
								List<SwitchPoint> allSps = new ArrayList<>(chain != null ? chain.size() + 1 : 1);
								if (chain != null && fbTyped != null) {
									for (JSObject p : chain) {
										SwitchPoint pSp = p.getOrCreateProtoSwitchPoint();
										guardedCall = pSp.guardWithTest(guardedCall, fbTyped);
										allSps.add(pSp);
									}
								}
								SwitchPoint holderSp = holder.getOrCreateProtoSwitchPoint();
								allSps.add(holderSp);
								site.installProtoGuard(jsObj.shape, holderSp, allSps, test, guardedCall);
							} else {
								site.installGuardOrSwitchMegamorphic(test, exactFuncCall.asType(site.type()));
							}
						} else {
							SwitchPoint sp = jsObj.getOrCreateProtoSwitchPoint();
							site.installProtoGuard(jsObj.shape, sp, test, exactFuncCall.asType(site.type()));
						}
					}
				} else if (ownOffset >= 0 && (jsObj.shape.getSlotType(ownOffset) & JSShape.FLAG_ACCESSOR) == 0 && site.getChainDepth() < 3) {
					int arity = args.length;
					MethodHandle callMh;
					if (arity == 0) {
						callMh = MethodHandles.insertArguments(MH_CALL_OWN_METHOD0, 0, ownOffset);
					} else if (arity == 1) {
						callMh = MethodHandles.insertArguments(MH_CALL_OWN_METHOD1, 0, ownOffset);
					} else if (arity == 2) {
						callMh = MethodHandles.insertArguments(MH_CALL_OWN_METHOD2, 0, ownOffset);
					} else if (arity == 3) {
						callMh = MethodHandles.insertArguments(MH_CALL_OWN_METHOD3, 0, ownOffset);
					} else if (arity == 4) {
						callMh = MethodHandles.insertArguments(MH_CALL_OWN_METHOD4, 0, ownOffset);
					} else {
						callMh = MethodHandles.insertArguments(MH_CALL_OWN_METHOD_N, 0, ownOffset)
						 .asCollector(1, Object[].class, arity);
					}
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
				if (arity == 0) return func.call0(null, jsObj);
				if (arity == 1) return func.call1(null, jsObj, args[0]);
				if (arity == 2) return func.call2(null, jsObj, args[0], args[1]);
				if (arity == 3) return func.call3(null, jsObj, args[0], args[1], args[2]);
				if (arity == 4) return func.call4(null, jsObj, args[0], args[1], args[2], args[3]);
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

		return invokeFallbackSlow(site, target, args, methodName);
	}

	public static Object invokeFallbackSlow(ChainedCallSite site, Object target, Object[] args, String methodName)
	 throws Throwable {
		if (target instanceof CharSequence seq) {
			Object strRes = invokeStringMethod(seq.toString(), methodName, args);
			if (strRes != null || "search".equals(methodName) || "match".equals(methodName)) {
				return strRes;
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
					MethodHandle mh      = Magic.lookup.unreflect(targetMethod);
					MethodHandle adapted = isStatic ? MethodHandles.dropArguments(mh, 0, Object.class) : mh;
					int paramCount = targetMethod.getParameterCount();
					Class<?> varargArrayType = targetMethod.getParameterTypes()[paramCount - 1];
					int argOffset = 1; // index 0 is receiver or dropped target
					int varargCount = args.length - (paramCount - 1);
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
				return invokeMatchedMethod(target, targetMethod, args, clazz, methodName);
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

	private static boolean hasComplexParameters(Method method) {
		for (Class<?> pType : method.getParameterTypes()) {
			if (pType.isInterface() && pType != JSFunction.class && pType != JSObject.class) {
				return true;
			}
		}
		return false;
	}

	/** @see JSOps#castValue(Object, Class) */
	private static final ClassValue<MethodHandle> INTERFACE_FILTER_CACHE = new ClassValue<>() {
		@Override
		protected MethodHandle computeValue(Class<?> type) {
			return MethodHandles.insertArguments(MH_TO_INTERFACE, 1, type);
		}
	};
	public static MethodHandle getArgumentFilter(Class<?> targetType) {
		if (targetType == int.class) return MH_TO_INT;
		if (targetType == long.class) return MH_TO_LONG;
		if (targetType == double.class) return MH_TO_DOUBLE;
		if (targetType == float.class) return MH_TO_FLOAT;
		if (targetType == short.class) return MH_TO_SHORT;
		if (targetType == byte.class) return MH_TO_BYTE;
		if (targetType == char.class) return MH_TO_CHAR;
		if (targetType == boolean.class) return MH_TO_BOOLEAN;
		if (targetType == String.class) return MH_TO_STRING;
		if (targetType.isInterface() && targetType != JSFunction.class && targetType != JSObject.class) {
			return INTERFACE_FILTER_CACHE.get(targetType);
		}
		return null;
	}

	private static final class MethodLookupKey {
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

	private static final class ClassSpreaderData {
		final Map<Integer, MethodHandle> ctorSpreaderCache = new ConcurrentHashMap<>();
		final Map<Constructor<?>, MethodHandle> exactCtorSpreaderCache = new ConcurrentHashMap<>();
		final Map<MethodLookupKey, MethodHandle> methodSpreaderCache = new ConcurrentHashMap<>();
	}

	private static final ClassValue<ClassSpreaderData> SPREADER_DATA = new ClassValue<>() {
		@Override
		protected ClassSpreaderData computeValue(Class<?> type) {
			return new ClassSpreaderData();
		}
	};

	private static MethodHandle getConstructorSpreader(Class<?> clazz, Constructor<?> c) {
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

	private static MethodHandle getConstructorSpreader(Class<?> clazz, int arity) {
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
				int arity = args.length;
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
			return cx.getSlot(slot);
		}
		JSContext current = JSContext.current();
		if (current != null) {
			return current.getSlot(slot);
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
		JSPromise                                    returnPromise      = new JSPromise(cx);
		java.util.concurrent.CompletableFuture<Void> firstSuspendOrDone = new java.util.concurrent.CompletableFuture<>();
		AsyncExecutionState                          state              = new AsyncExecutionState(cx, returnPromise, firstSuspendOrDone);

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
		JSPromise                                    returnPromise      = new JSPromise(cx);
		java.util.concurrent.CompletableFuture<Void> firstSuspendOrDone = new java.util.concurrent.CompletableFuture<>();
		AsyncExecutionState                          state              = new AsyncExecutionState(cx, returnPromise, firstSuspendOrDone);

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
		return Array.newInstance(componentType, 0);
	}

	public static Object newArrayInstance1(Class<?> componentType, Object lenOrInit) {
		if (lenOrInit instanceof Number num) {
			return Array.newInstance(componentType, num.intValue());
		}
		if (lenOrInit instanceof JSArray jsArr) {
			int len = (int) jsArr.length();
			Object arr = Array.newInstance(componentType, len);
			for (int i = 0; i < len; i++) {
				Array.set(arr, i, JSOps.castValue(jsArr.getElement(i), componentType));
			}
			return arr;
		}
		if (lenOrInit instanceof List<?> list) {
			int len = list.size();
			Object arr = Array.newInstance(componentType, len);
			for (int i = 0; i < len; i++) {
				Array.set(arr, i, JSOps.castValue(list.get(i), componentType));
			}
			return arr;
		}
		if (lenOrInit != null && lenOrInit.getClass().isArray()) {
			int len = Array.getLength(lenOrInit);
			Object arr = Array.newInstance(componentType, len);
			for (int i = 0; i < len; i++) {
				Array.set(arr, i, JSOps.castValue(Array.get(lenOrInit, i), componentType));
			}
			return arr;
		}
		return Array.newInstance(componentType, JSOps.toInt(lenOrInit));
	}

	public static Object newArrayInstanceN(Class<?> componentType, Object[] args) {
		int len = args.length;
		Object arr = Array.newInstance(componentType, len);
		for (int i = 0; i < len; i++) {
			Array.set(arr, i, JSOps.castValue(args[i], componentType));
		}
		return arr;
	}

	public static Object invokeInterfaceAdapter1(Object target, Object arg) {
		Class<?> clazz = (Class<?>) target;
		if (arg instanceof JSFunction fn) {
			return MagicJIT.getFunctionAdapter(clazz, fn);
		}
		if (arg instanceof JSObject jsObj) {
			return MagicJIT.getObjectAdapter(clazz, jsObj);
		}
		throw JSContext.makeTypeError("Cannot adapt " + arg + " to interface " + clazz.getName());
	}

	public static Object newFallback(ChainedCallSite site, Object ctor, Object[] args) throws Throwable {
		int arity = args.length;
		if (ctor instanceof Class<?> clazz) {
			if (clazz.isArray()) {
				Class<?> componentType = clazz.getComponentType();
				MethodHandle test = MH_IS_SAME_OBJECT.bindTo(clazz);
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
						MethodHandle mh = Magic.lookup.unreflectConstructor(targetCtor);
						int paramCount = targetCtor.getParameterCount();
						Class<?> varargArrayType = targetCtor.getParameterTypes()[paramCount - 1];
						int varargCount = arity - (paramCount - 1);
						if (varargCount >= 0) {
							MethodHandle collector = mh.asCollector(paramCount - 1, varargArrayType, varargCount);
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
						MagicJIT.MagicConstructorInvoker ctorInvoker = MagicJIT.getConstructorInvoker(clazz, targetCtor);
						if (ctorInvoker != null) {
							switch (arity) {
								case 0: return ctorInvoker.newInstance0();
								case 1: return ctorInvoker.newInstance1(args[0]);
								case 2: return ctorInvoker.newInstance2(args[0], args[1]);
								case 3: return ctorInvoker.newInstance3(args[0], args[1], args[2]);
								default: return ctorInvoker.newInstance(args);
							}
						}
					} catch (Throwable ignored) {
					}
				}

				MethodHandle ctorSpreader = getConstructorSpreader(clazz, targetCtor);
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

		if (ctor == JSContext.LazyDate.DATE) {
			return ((JSFunction) ctor).call(null, new JSContext.JSDate(0, JSContext.LazyDate.DATE_PROTOTYPE), args);
		}

		if (ctor instanceof JSFunction func) {
			Object   proto       = (ctor instanceof JSObject jsObj) ? jsObj.get("prototype") : JSUndefined.INSTANCE;
			JSObject cachedProto = (proto instanceof JSObject sp) ? sp : null;

			MethodHandle fastTarget = null;
			if (arity == 0) {
				fastTarget = MethodHandles.insertArguments(NewMH.NEW_JS_FUNC0, 1, cachedProto);
			} else if (arity == 1) {
				fastTarget = MethodHandles.insertArguments(NewMH.NEW_JS_FUNC1, 2, cachedProto);
			} else if (arity == 2) {
				fastTarget = MethodHandles.insertArguments(NewMH.NEW_JS_FUNC2, 3, cachedProto);
			} else if (arity == 3) {
				fastTarget = MethodHandles.insertArguments(NewMH.NEW_JS_FUNC3, 4, cachedProto);
			} else if (arity == 4) {
				fastTarget = MethodHandles.insertArguments(NewMH.NEW_JS_FUNC4, 5, cachedProto);
			} else {
				fastTarget = MethodHandles.insertArguments(NewMH.NEW_JS_FUNC_N, 2, cachedProto)
				 .asCollector(1, Object[].class, arity);
			}

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

			JSObject newObj = (cachedProto != null) ? new JSObject(cachedProto) : new JSObject();
			Object   res;
			if (arity == 0) { res = func.call0(null, newObj); } else if (arity == 1) {
				res = func.call1(null, newObj, args[0]);
			} else if (arity == 2) {
				res = func.call2(null, newObj, args[0], args[1]);
			} else if (arity == 3) {
				res = func.call3(null, newObj, args[0], args[1], args[2]);
			} else if (arity == 4) {
				res = func.call4(null, newObj, args[0], args[1], args[2], args[3]);
			} else {
				res = func.call(null, newObj, args);
			}

			if (res instanceof JSBridgedObject || (res != null && res != JSUndefined.INSTANCE && !(res instanceof Number || res instanceof Boolean || res instanceof String || res instanceof Character))) {
				return res;
			}
			return newObj;
		}

		return newGeneric(ctor, args);
	}

	public static Long toValidArrayLongIndex(Object index) {
		return JSArray.toValidArrayIndex(index);
	}

	public static Integer toValidArrayIndex(Object index) {
		return JSArray.toValidJavaArrayIndex(index);
	}

	public static String toPropertyKey(Object index) {
		return JSArray.toPropertyKey(index);
	}

	public static Object getArrayElement(Object target, int idx) {
		if (idx < 0) return JSUndefined.INSTANCE;
		if (target instanceof Object[] a) return idx < a.length ? a[idx] : JSUndefined.INSTANCE;
		if (target instanceof int[] a) return idx < a.length ? (double) a[idx] : JSUndefined.INSTANCE;
		if (target instanceof double[] a) return idx < a.length ? a[idx] : JSUndefined.INSTANCE;
		if (target instanceof long[] a) return idx < a.length ? (double) a[idx] : JSUndefined.INSTANCE;
		if (target instanceof float[] a) return idx < a.length ? (double) a[idx] : JSUndefined.INSTANCE;
		if (target instanceof short[] a) return idx < a.length ? (double) a[idx] : JSUndefined.INSTANCE;
		if (target instanceof byte[] a) return idx < a.length ? (double) a[idx] : JSUndefined.INSTANCE;
		if (target instanceof boolean[] a) return idx < a.length ? a[idx] : JSUndefined.INSTANCE;
		if (target instanceof char[] a) return idx < a.length ? String.valueOf(a[idx]) : JSUndefined.INSTANCE;
		return JSUndefined.INSTANCE;
	}

	public static void setArrayElement(Object target, int idx, Object value) {
		if (idx < 0) return;
		if (target instanceof Object[] a) {
			if (idx < a.length) a[idx] = value;
			return;
		}
		if (target instanceof int[] a) {
			if (idx < a.length) a[idx] = JSOps.toInt(value);
			return;
		}
		if (target instanceof double[] a) {
			if (idx < a.length) a[idx] = JSOps.toDouble(value);
			return;
		}
		if (target instanceof long[] a) {
			if (idx < a.length) a[idx] = JSOps.toLong(value);
			return;
		}
		if (target instanceof float[] a) {
			if (idx < a.length) a[idx] = JSOps.toFloat(value);
			return;
		}
		if (target instanceof short[] a) {
			if (idx < a.length) a[idx] = JSOps.toShort(value);
			return;
		}
		if (target instanceof byte[] a) {
			if (idx < a.length) a[idx] = JSOps.toByte(value);
			return;
		}
		if (target instanceof boolean[] a) {
			if (idx < a.length) a[idx] = JSOps.toBoolean(value);
			return;
		}
		if (target instanceof char[] a) {
			if (idx < a.length) a[idx] = JSOps.toChar(value);
			return;
		}
	}
	public static final  int      SMALL_INT_SIZE    = 1024;
	private static final String[] SMALL_INT_STRINGS = new String[SMALL_INT_SIZE];

	static {
		for (int i = 0; i < SMALL_INT_SIZE; i++) SMALL_INT_STRINGS[i] = String.valueOf(i).intern();
	}

	public static String fastIntToString(int i) {
		if (i >= 0 && i < SMALL_INT_SIZE) return SMALL_INT_STRINGS[i];
		return String.valueOf(i);
	}
	public static Object getIndex(Object target, int index) {
		if (target instanceof JSArray jsArr) return jsArr.getElement(index);
		if (target instanceof Object[] a) {
			return (index >= 0 && index < a.length) ? a[index] : JSUndefined.INSTANCE;
		}
		if (target instanceof List list) {
			return (index >= 0 && index < list.size()) ? list.get(index) : JSUndefined.INSTANCE;
		}
		if (target != null && target.getClass().isArray()) {
			return getArrayElement(target, index);
		}
		if (target instanceof JSObject jsObj) {
			return jsObj.get(fastIntToString(index));
		}
		if (target instanceof CharSequence seq) {
			return (index >= 0 && index < seq.length()) ? String.valueOf(seq.charAt(index)) : JSUndefined.INSTANCE;
		}
		if (target instanceof Map map) {
			return map.get(index);
		}
		if (target instanceof Map.Entry<?, ?> entry) {
			if (index == 0) return entry.getKey();
			if (index == 1) return entry.getValue();
			return JSUndefined.INSTANCE;
		}
		return getPropGeneric(target, fastIntToString(index));
	}

	public static void setIndex(Object target, int index, Object value) {
		if (target instanceof JSArray jsArr) {
			jsArr.setElement(index, value);
			return;
		}
		if (target instanceof Object[] a) {
			if (index >= 0 && index < a.length) a[index] = value;
			return;
		}
		if (target instanceof List list) {
			if (index >= 0 && index < list.size()) {
				list.set(index, value);
			} else if (index >= 0 && index <= list.size() + 1024 && index < 65536) {
				while (list.size() <= index) list.add(null);
				list.set(index, value);
			}
			return;
		}
		if (target != null && target.getClass().isArray()) {
			setArrayElement(target, index, value);
			return;
		}
		if (target instanceof JSObject jsObj) {
			jsObj.put(fastIntToString(index), value);
			return;
		}
		if (target instanceof Map map) {
			map.put(index, value);
			return;
		}
		setPropGeneric(target, value, fastIntToString(index));
	}

	public static Object getIndex(Object target, Object index) {
		if (target == null || target == JSUndefined.INSTANCE) return JSUndefined.INSTANCE;
		if (index instanceof Integer i) {
			return getIndex(target, i.intValue());
		}
		if (index instanceof Double d) {
			double val = d;
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				return getIndex(target, (int) val);
			}
		}
		if (target instanceof JSArray jsArr) {
			Long idx = JSArray.toValidArrayIndex(index);
			if (idx != null) {
				return jsArr.getElement(idx);
			}
			if (index instanceof JSSymbol sym) {
				return jsArr.get(sym);
			}
			return jsArr.get(JSArray.toPropertyKey(index));
		}
		if (target instanceof JSObject jsObj) {
			if (index instanceof JSSymbol sym) {
				return jsObj.get(sym);
			}
			return jsObj.get(JSArray.toPropertyKey(index));
		}
		if (target.getClass().isArray()) {
			Integer idx = JSArray.toValidJavaArrayIndex(index);
			if (idx != null) {
				return getArrayElement(target, idx);
			}
			return JSUndefined.INSTANCE;
		}
		if (target instanceof List list) {
			Integer idx = JSArray.toValidJavaArrayIndex(index);
			if (idx != null && idx >= 0 && idx < list.size()) {
				return list.get(idx);
			}
			return JSUndefined.INSTANCE;
		}
		if (target instanceof CharSequence seq) {
			Integer idx = JSArray.toValidJavaArrayIndex(index);
			if (idx != null && idx >= 0 && idx < seq.length()) {
				return String.valueOf(seq.charAt(idx));
			}
			return JSUndefined.INSTANCE;
		}
		if (target instanceof Map map) {
			Object val = map.get(index);
			if (val == null && !map.containsKey(index)) {
				val = map.get(toPropertyKey(index));
			}
			return val == null && !map.containsKey(index) ? JSUndefined.INSTANCE : val;
		}
		return getPropGeneric(target, toPropertyKey(index));
	}

	public static void setIndex(Object target, Object index, Object value) {
		if (target == null || target == JSUndefined.INSTANCE) return;
		if (index instanceof Integer i) {
			setIndex(target, i.intValue(), value);
			return;
		}
		if (index instanceof Double d) {
			double val = d;
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				setIndex(target, (int) val, value);
				return;
			}
		}
		if (target instanceof JSArray jsArr) {
			Long idx = JSArray.toValidArrayIndex(index);
			if (idx != null) {
				jsArr.setElement(idx, value);
				return;
			}
			if (index instanceof JSSymbol sym) {
				jsArr.put(sym, value);
				return;
			}
			jsArr.put(JSArray.toPropertyKey(index), value);
			return;
		}
		if (target instanceof JSObject jsObj) {
			if (index instanceof JSSymbol sym) {
				jsObj.put(sym, value);
				return;
			}
			jsObj.put(JSArray.toPropertyKey(index), value);
			return;
		}
		if (target.getClass().isArray()) {
			Integer idx = JSArray.toValidJavaArrayIndex(index);
			if (idx != null) {
				setArrayElement(target, idx, value);
			}
			return;
		}
		if (target instanceof List list) {
			Integer idx = JSArray.toValidJavaArrayIndex(index);
			if (idx != null) {
				if (idx >= 0 && idx < list.size()) {
					list.set(idx, value);
				} else if (idx >= 0 && idx <= list.size() + 1024 && idx < 65536) {
					while (list.size() <= idx) list.add(null);
					list.set(idx, value);
				}
			}
			return;
		}
		if (target instanceof Map map) {
			map.put(index, value);
			return;
		}
		setPropGeneric(target, value, toPropertyKey(index));
	}
	//endregion

	//region 辅助方法与直接 MethodHandle 构建

	public static boolean isExactClass(Class<?> expected, Object target) {
		return target != null && target.getClass() == expected;
	}
	public static boolean isExactClassAndArgs(Class<?> expected, Class<?>[] expectedArgs, Object target, Object[] args) {
		if (target == null || target.getClass() != expected) return false;
		if (args.length != expectedArgs.length) return false;
		for (int i = 0; i < expectedArgs.length; i++) {
			Class<?> exp = expectedArgs[i];
			Object act = args[i];
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
			Object act = args[i];
			if (exp == null) {
				if (act != null) return false;
			} else {
				if (act == null || act.getClass() != exp) return false;
			}
		}
		return true;
	}

	public static int getArrayLengthInt(Object target) {
		return target != null && target.getClass().isArray() ? java.lang.reflect.Array.getLength(target) : 0;
	}

	public static double getArrayLengthDouble(Object target) {
		return target != null && target.getClass().isArray() ? (double) java.lang.reflect.Array.getLength(target) : Double.NaN;
	}

	public static Object getIndexJSArray(Object target, Object index) {
		JSArray jsArr = (JSArray) target;
		if (index instanceof Integer i) {
			int val = i.intValue();
			return (val >= 0 && val < jsArr.length()) ? jsArr.getElement(val) : jsArr.get(fastIntToString(val));
		}
		if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				int idx = (int) val;
				return (idx < jsArr.length()) ? jsArr.getElement(idx) : jsArr.get(fastIntToString(idx));
			}
		}
		Long idx = JSArray.toValidArrayIndex(index);
		if (idx != null) {
			return jsArr.getElement(idx);
		}
		if (index instanceof JSSymbol sym) {
			return jsArr.get(sym);
		}
		return jsArr.get(JSArray.toPropertyKey(index));
	}

	public static Object getIndexList(Object target, Object index) {
		List<?> list = (List<?>) target;
		if (index instanceof Integer i) {
			int idx = i.intValue();
			return (idx >= 0 && idx < list.size()) ? list.get(idx) : JSUndefined.INSTANCE;
		}
		if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				int idx = (int) val;
				return (idx >= 0 && idx < list.size()) ? list.get(idx) : JSUndefined.INSTANCE;
			}
		}
		Integer idx = JSArray.toValidJavaArrayIndex(index);
		if (idx != null && idx >= 0 && idx < list.size()) {
			return list.get(idx);
		}
		return JSUndefined.INSTANCE;
	}

	public static Object getIndexObjectArray(Object target, Object index) {
		Object[] a = (Object[]) target;
		if (index instanceof Integer i) {
			int idx = i.intValue();
			return (idx >= 0 && idx < a.length) ? a[idx] : JSUndefined.INSTANCE;
		}
		if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				int idx = (int) val;
				return (idx >= 0 && idx < a.length) ? a[idx] : JSUndefined.INSTANCE;
			}
		}
		Integer idx = JSArray.toValidJavaArrayIndex(index);
		if (idx != null && idx >= 0 && idx < a.length) {
			return a[idx];
		}
		return JSUndefined.INSTANCE;
	}

	public static Object getIndexPrimitiveArray(Object target, Object index) {
		if (index instanceof Integer i) {
			return getArrayElement(target, i.intValue());
		}
		if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				return getArrayElement(target, (int) val);
			}
		}
		Integer idx = JSArray.toValidJavaArrayIndex(index);
		if (idx != null) {
			return getArrayElement(target, idx);
		}
		return JSUndefined.INSTANCE;
	}

	public static Object getIndexIntArray(Object target, Object index) {
		int[] a = (int[]) target;
		int idx = -1;
		if (index instanceof Integer i) {
			idx = i.intValue();
		} else if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				idx = (int) val;
			}
		} else {
			Integer validIdx = JSArray.toValidJavaArrayIndex(index);
			if (validIdx != null) idx = validIdx;
		}
		return (idx >= 0 && idx < a.length) ? (double) a[idx] : JSUndefined.INSTANCE;
	}

	public static Object getIndexDoubleArray(Object target, Object index) {
		double[] a = (double[]) target;
		int idx = -1;
		if (index instanceof Integer i) {
			idx = i.intValue();
		} else if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				idx = (int) val;
			}
		} else {
			Integer validIdx = JSArray.toValidJavaArrayIndex(index);
			if (validIdx != null) idx = validIdx;
		}
		return (idx >= 0 && idx < a.length) ? a[idx] : JSUndefined.INSTANCE;
	}

	public static Object getIndexLongArray(Object target, Object index) {
		long[] a = (long[]) target;
		int idx = -1;
		if (index instanceof Integer i) {
			idx = i.intValue();
		} else if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				idx = (int) val;
			}
		} else {
			Integer validIdx = JSArray.toValidJavaArrayIndex(index);
			if (validIdx != null) idx = validIdx;
		}
		return (idx >= 0 && idx < a.length) ? (double) a[idx] : JSUndefined.INSTANCE;
	}

	public static Object getIndexMap(Object target, Object index) {
		Map<?, ?> map = (Map<?, ?>) target;
		Object val = map.get(index);
		if (val == null && !map.containsKey(index)) {
			val = map.get(toPropertyKey(index));
		}
		return val == null && !map.containsKey(index) ? JSUndefined.INSTANCE : val;
	}

	public static void setIndexJSArray(Object target, Object index, Object value) {
		JSArray jsArr = (JSArray) target;
		if (index instanceof Integer i) {
			int val = i.intValue();
			if (val >= 0) {
				jsArr.setElement(val, value);
				return;
			}
		} else if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				jsArr.setElement((int) val, value);
				return;
			}
		}
		Long idx = JSArray.toValidArrayIndex(index);
		if (idx != null) {
			jsArr.setElement(idx, value);
			return;
		}
		if (index instanceof JSSymbol sym) {
			jsArr.put(sym, value);
			return;
		}
		jsArr.put(JSArray.toPropertyKey(index), value);
	}

	public static void setIndexList(Object target, Object index, Object value) {
		List<Object> list = (List<Object>) target;
		int idx = -1;
		if (index instanceof Integer i) {
			idx = i.intValue();
		} else if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				idx = (int) val;
			}
		} else {
			Integer validIdx = JSArray.toValidJavaArrayIndex(index);
			if (validIdx != null) idx = validIdx;
		}
		if (idx >= 0) {
			if (idx < list.size()) {
				list.set(idx, value);
			} else if (idx <= list.size() + 1024 && idx < 65536) {
				while (list.size() <= idx) list.add(null);
				list.set(idx, value);
			}
		}
	}

	public static void setIndexObjectArray(Object target, Object index, Object value) {
		Object[] a = (Object[]) target;
		int idx = -1;
		if (index instanceof Integer i) {
			idx = i.intValue();
		} else if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				idx = (int) val;
			}
		} else {
			Integer validIdx = JSArray.toValidJavaArrayIndex(index);
			if (validIdx != null) idx = validIdx;
		}
		if (idx >= 0 && idx < a.length) {
			a[idx] = value;
		}
	}

	public static void setIndexIntArray(Object target, Object index, Object value) {
		int[] a = (int[]) target;
		int idx = -1;
		if (index instanceof Integer i) {
			idx = i.intValue();
		} else if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				idx = (int) val;
			}
		} else {
			Integer validIdx = JSArray.toValidJavaArrayIndex(index);
			if (validIdx != null) idx = validIdx;
		}
		if (idx >= 0 && idx < a.length) {
			a[idx] = JSOps.toInt(value);
		}
	}

	public static void setIndexDoubleArray(Object target, Object index, Object value) {
		double[] a = (double[]) target;
		int idx = -1;
		if (index instanceof Integer i) {
			idx = i.intValue();
		} else if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				idx = (int) val;
			}
		} else {
			Integer validIdx = JSArray.toValidJavaArrayIndex(index);
			if (validIdx != null) idx = validIdx;
		}
		if (idx >= 0 && idx < a.length) {
			a[idx] = JSOps.toDouble(value);
		}
	}

	public static void setIndexLongArray(Object target, Object index, Object value) {
		long[] a = (long[]) target;
		int idx = -1;
		if (index instanceof Integer i) {
			idx = i.intValue();
		} else if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				idx = (int) val;
			}
		} else {
			Integer validIdx = JSArray.toValidJavaArrayIndex(index);
			if (validIdx != null) idx = validIdx;
		}
		if (idx >= 0 && idx < a.length) {
			a[idx] = JSOps.toLong(value);
		}
	}

	public static void setIndexPrimitiveArray(Object target, Object index, Object value) {
		int idx = -1;
		if (index instanceof Integer i) {
			idx = i.intValue();
		} else if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				idx = (int) val;
			}
		} else {
			Integer validIdx = JSArray.toValidJavaArrayIndex(index);
			if (validIdx != null) idx = validIdx;
		}
		if (idx >= 0) {
			setArrayElement(target, idx, value);
		}
	}

	public static void setIndexMap(Object target, Object index, Object value) {
		Map<Object, Object> map = (Map<Object, Object>) target;
		if (map.containsKey(index)) {
			map.put(index, value);
			return;
		}
		String strKey = toPropertyKey(index);
		if (map.containsKey(strKey)) {
			map.put(strKey, value);
			return;
		}
		map.put(index, value);
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
					JSObject current = proto;
					JSObject holder = null;
					int holderOffset = -1;
					List<JSObject> chain = null;

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
						byte slotType = holder.shape.getSlotType(holderOffset);
						MethodHandle test = MH_IS_EXACT_SHAPE_AND_PROTO.bindTo(shape).bindTo(proto);
						MethodHandle fb = site.getInitialFallback();
						MethodHandle fbTyped = (fb != null) ? fb.asType(site.type()) : null;

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
								site.installProtoGuard(shape, holder.getOrCreateProtoSwitchPoint(), test, getterTarget);
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
							site.installProtoGuard(shape, holder.getOrCreateProtoSwitchPoint(), test, constTarget);
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
			return java.lang.reflect.Array.getLength(target);
		}
		boolean isStatic = false;
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
					JSObject current = proto;
					JSObject holder = null;
					int holderOffset = -1;
					List<JSObject> chain = null;

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
						byte slotType = holder.shape.getSlotType(holderOffset);
						MethodHandle test = MH_IS_EXACT_SHAPE_AND_PROTO.bindTo(shape).bindTo(proto);
						MethodHandle fb = site.getInitialFallback();
						MethodHandle fbTyped = (fb != null) ? fb.asType(site.type()) : null;

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
								site.installProtoGuard(shape, holder.getOrCreateProtoSwitchPoint(), test, getterTarget);
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
							site.installProtoGuard(shape, holder.getOrCreateProtoSwitchPoint(), test, constTarget);
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

		boolean isStatic = false;
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
					JSObject current = proto;
					JSObject holder = null;
					int holderOffset = -1;
					List<JSObject> chain = null;

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
						byte slotType = holder.shape.getSlotType(holderOffset);
						MethodHandle test = MH_IS_EXACT_SHAPE_AND_PROTO.bindTo(shape).bindTo(proto);
						MethodHandle fb = site.getInitialFallback();
						MethodHandle fbTyped = (fb != null) ? fb.asType(site.type()) : null;

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
								site.installProtoGuard(shape, holder.getOrCreateProtoSwitchPoint(), test, getterTarget);
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
							site.installProtoGuard(shape, holder.getOrCreateProtoSwitchPoint(), test, constTarget);
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
		boolean isStatic = false;
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

	private static MethodHandle getMethodSpreader(Class<?> clazz, String methodName, int arity, boolean isStatic) {
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

	private static Object invokeStringMethod(String str, String methodName, Object[] args) throws Throwable {
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

	private static String replaceWithRegExp(String str, JSRegExp reg, Object repArg, boolean forceAll) throws Throwable {
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

	private static String toJavaReplacement(String jsRep) {
		if (jsRep == null || !jsRep.contains("$")) return jsRep != null ? jsRep.replace("\\", "\\\\") : "";
		StringBuilder sb = new StringBuilder(jsRep.length() * 2);
		for (int i = 0; i < jsRep.length(); i++) {
			char c = jsRep.charAt(i);
			if (c == '$') {
				if (i + 1 < jsRep.length()) {
					char next = jsRep.charAt(i + 1);
					if (next == '&') {
						sb.append("$0"); // $& 在 JS 中是全匹配，对应 Java Matcher 的 $0
					} else if (next == '$') {
						sb.append("\\$"); // $$ 在 JS 中代表单个 $ 字面量
						i++;
					} else if (Character.isDigit(next)) {
						if (next == '0') {
							sb.append("\\$0"); // JS 中的 $0 是普通字面量，必须转义为 \$0，防止 Java 误当整串
						} else {
							sb.append("$").append(next);
						}
						i++;
					} else {
						sb.append("\\$"); // 单个无效 $ 作为字面量转义
					}
				} else {
					sb.append("\\$");     // 末尾单个 $ 必须转义为 \$，防止 Java Matcher 抛出异常
				}
			} else if (c == '\\') {
				sb.append("\\\\");
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	static Object invokeJavaMethod(Object target, String methodName, Object[] args) throws Throwable {
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

	private static final ThreadLocal<JSObject> CURRENT_SUPER_PROTO      = new ThreadLocal<>();
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
		String specifier = JSOps.toStr(specifierObj);
		hope.magic.js.module.JSModuleManager mgr = cx.getModuleManager();
		hope.magic.js.module.JSModule parent = null;
		if (currentDirOrModule instanceof hope.magic.js.module.JSModule m) {
			parent = m;
		} else if (currentDirOrModule instanceof String dirname) {
			parent = new hope.magic.js.module.JSModule("temp", "", dirname, null);
		} else {
			parent = hope.magic.js.module.JSModuleManager.getCurrentModule();
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
