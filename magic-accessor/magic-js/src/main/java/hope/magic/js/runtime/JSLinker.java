package hope.magic.js.runtime;

import hope.magic.runtime.*;
import sun.misc.Unsafe;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

@SuppressWarnings({"unused", "unchecked", "rawtypes"})
public class JSLinker {
	static final Unsafe UNSAFE = Magic.unsafe;
	static final MethodHandles.Lookup LOOKUP = Magic.lookup;

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
	public static final MethodHandle MH_IS_EXACT_SHAPE;
	public static final MethodHandle MH_GET_JS_OBJ_SLOT;
	public static final MethodHandle MH_SET_JS_OBJ_SLOT;
	public static final MethodHandle MH_GET_JS_OBJ_SLOT_INT;
	public static final MethodHandle MH_GET_JS_OBJ_SLOT_DOUBLE;
	public static final MethodHandle MH_GET_JS_OBJ_SLOT_LONG;

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
			MH_TO_INTERFACE = LOOKUP.findStatic(JSLinker.class, "toInterface", MethodType.methodType(Object.class, Class.class, Object.class));
			MH_IS_EXACT_CLASS = LOOKUP.findStatic(JSLinker.class, "isExactClass", MethodType.methodType(boolean.class, Class.class, Object.class));
			MH_IS_EXACT_SHAPE = LOOKUP.findStatic(JSLinker.class, "isExactShape", MethodType.methodType(boolean.class, JSShape.class, Object.class));
			MH_GET_JS_OBJ_SLOT = LOOKUP.findStatic(JSLinker.class, "getJSObjSlot", MethodType.methodType(Object.class, int.class, Object.class));
			MH_SET_JS_OBJ_SLOT = LOOKUP.findStatic(JSLinker.class, "setJSObjSlot", MethodType.methodType(void.class, int.class, Object.class, Object.class));
			MH_GET_JS_OBJ_SLOT_INT = LOOKUP.findStatic(JSLinker.class, "getJSObjSlotAsInt", MethodType.methodType(int.class, int.class, Object.class));
			MH_GET_JS_OBJ_SLOT_DOUBLE = LOOKUP.findStatic(JSLinker.class, "getJSObjSlotAsDouble", MethodType.methodType(double.class, int.class, Object.class));
			MH_GET_JS_OBJ_SLOT_LONG = LOOKUP.findStatic(JSLinker.class, "getJSObjSlotAsLong", MethodType.methodType(long.class, int.class, Object.class));
		} catch (Throwable e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private static MethodHandle findMH(Class<?> clazz, String name, MethodType type) {
		try {
			return LOOKUP.findStatic(clazz, name, type);
		} catch (Throwable e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private static MethodHandle findVirtualMH(Class<?> clazz, String name, MethodType type) {
		try {
			return LOOKUP.findVirtual(clazz, name, type);
		} catch (Throwable e) {
			throw new ExceptionInInitializerError(e);
		}
	}
	//endregion

	//region Nestmate Lazy Holders (按领域按需懒加载)

	public static final class PropMH {
		public static final MethodHandle GET_GENERIC            = findMH(JSLinker.class, "getPropGeneric", MethodType.methodType(Object.class, Object.class, String.class));
		public static final MethodHandle GET_FALLBACK           = findMH(JSLinker.class, "getPropFallback", MethodType.methodType(Object.class, ChainedCallSite.class, Object.class, String.class));
		public static final MethodHandle GET_MEGAMORPHIC        = findMH(JSLinker.class, "getPropMegamorphic", MethodType.methodType(Object.class, ChainedCallSite.class, Object.class, String.class));
		public static final MethodHandle GET_INT_GENERIC        = findMH(JSLinker.class, "getPropIntGeneric", MethodType.methodType(int.class, Object.class, String.class));
		public static final MethodHandle GET_INT_FALLBACK       = findMH(JSLinker.class, "getPropIntFallback", MethodType.methodType(int.class, ChainedCallSite.class, Object.class, String.class));
		public static final MethodHandle GET_INT_MEGAMORPHIC    = findMH(JSLinker.class, "getPropIntMegamorphic", MethodType.methodType(int.class, ChainedCallSite.class, Object.class, String.class));
		public static final MethodHandle GET_DOUBLE_GENERIC     = findMH(JSLinker.class, "getPropDoubleGeneric", MethodType.methodType(double.class, Object.class, String.class));
		public static final MethodHandle GET_DOUBLE_FALLBACK    = findMH(JSLinker.class, "getPropDoubleFallback", MethodType.methodType(double.class, ChainedCallSite.class, Object.class, String.class));
		public static final MethodHandle GET_DOUBLE_MEGAMORPHIC = findMH(JSLinker.class, "getPropDoubleMegamorphic", MethodType.methodType(double.class, ChainedCallSite.class, Object.class, String.class));
		public static final MethodHandle GET_DOUBLE_SLOT        = findMH(JSLinker.class, "getJSObjDoubleSlot", MethodType.methodType(double.class, int.class, Object.class));
		public static final MethodHandle GET_LONG_GENERIC       = findMH(JSLinker.class, "getPropLongGeneric", MethodType.methodType(long.class, Object.class, String.class));
		public static final MethodHandle GET_LONG_FALLBACK      = findMH(JSLinker.class, "getPropLongFallback", MethodType.methodType(long.class, ChainedCallSite.class, Object.class, String.class));
		public static final MethodHandle GET_LONG_MEGAMORPHIC   = findMH(JSLinker.class, "getPropLongMegamorphic", MethodType.methodType(long.class, ChainedCallSite.class, Object.class, String.class));
		public static final MethodHandle SET_GENERIC            = findMH(JSLinker.class, "setPropGeneric", MethodType.methodType(void.class, Object.class, Object.class, String.class));
		public static final MethodHandle SET_FALLBACK           = findMH(JSLinker.class, "setPropFallback", MethodType.methodType(void.class, ChainedCallSite.class, Object.class, Object.class, String.class));
		public static final MethodHandle SET_MEGAMORPHIC        = findMH(JSLinker.class, "setPropMegamorphic", MethodType.methodType(void.class, ChainedCallSite.class, Object.class, Object.class, String.class));
	}

	public static final class InvokeMH {
		public static final MethodHandle INVOKE_GENERIC  = findMH(JSLinker.class, "invokeGeneric", MethodType.methodType(Object.class, Object.class, Object[].class, String.class));
		public static final MethodHandle INVOKE_FALLBACK = findMH(JSLinker.class, "invokeFallback", MethodType.methodType(Object.class, ChainedCallSite.class, Object.class, Object[].class, String.class));
		public static final MethodHandle NEW_GENERIC     = findMH(JSLinker.class, "newGeneric", MethodType.methodType(Object.class, Object.class, Object[].class));
		public static final MethodHandle NEW_FALLBACK    = findMH(JSLinker.class, "newFallback", MethodType.methodType(Object.class, ChainedCallSite.class, Object.class, Object[].class));
	}

	public static final class JSFuncMH {
		public static final MethodHandle CALL  = findVirtualMH(JSFunction.class, "call", MethodType.methodType(Object.class, JSContext.class, Object.class, Object[].class));
		public static final MethodHandle CALL0 = findVirtualMH(JSFunction.class, "call0", MethodType.methodType(Object.class, JSContext.class, Object.class));
		public static final MethodHandle CALL1 = findVirtualMH(JSFunction.class, "call1", MethodType.methodType(Object.class, JSContext.class, Object.class, Object.class));
		public static final MethodHandle CALL2 = findVirtualMH(JSFunction.class, "call2", MethodType.methodType(Object.class, JSContext.class, Object.class, Object.class, Object.class));
		public static final MethodHandle CALL3 = findVirtualMH(JSFunction.class, "call3", MethodType.methodType(Object.class, JSContext.class, Object.class, Object.class, Object.class, Object.class));
	}

	public static final class OpMH {
		private static final MethodType BIN_TYPE = MethodType.methodType(Object.class, Object.class, Object.class);
		private static final MethodType BIN_DD_D = MethodType.methodType(double.class, double.class, double.class);
		private static final MethodType BIN_II_I = MethodType.methodType(int.class, int.class, int.class);
		private static final MethodType BIN_ID_D = MethodType.methodType(double.class, int.class, double.class);
		private static final MethodType BIN_DI_D = MethodType.methodType(double.class, double.class, int.class);

		private static final MethodType BIN_OD_O = MethodType.methodType(Object.class, Object.class, double.class);
		private static final MethodType BIN_DO_O = MethodType.methodType(Object.class, double.class, Object.class);
		private static final MethodType BIN_OI_O = MethodType.methodType(Object.class, Object.class, int.class);
		private static final MethodType BIN_IO_O = MethodType.methodType(Object.class, int.class, Object.class);

		private static final MethodType BIN_SS_S = MethodType.methodType(String.class, String.class, String.class);
		private static final MethodType BIN_SO_S = MethodType.methodType(String.class, String.class, Object.class);
		private static final MethodType BIN_OS_S = MethodType.methodType(String.class, Object.class, String.class);

		// Generic (Object, Object) -> Object
		public static final MethodHandle ADD       = findMH(JSOps.class, "add", BIN_TYPE);
		public static final MethodHandle SUB       = findMH(JSOps.class, "sub", BIN_TYPE);
		public static final MethodHandle MUL       = findMH(JSOps.class, "mul", BIN_TYPE);
		public static final MethodHandle DIV       = findMH(JSOps.class, "div", BIN_TYPE);
		public static final MethodHandle MOD       = findMH(JSOps.class, "mod", BIN_TYPE);
		public static final MethodHandle EQ        = findMH(JSOps.class, "eq", BIN_TYPE);
		public static final MethodHandle STRICT_EQ = findMH(JSOps.class, "strictEq", BIN_TYPE);
		public static final MethodHandle NE        = findMH(JSOps.class, "ne", BIN_TYPE);
		public static final MethodHandle STRICT_NE = findMH(JSOps.class, "strictNe", BIN_TYPE);
		public static final MethodHandle LT        = findMH(JSOps.class, "lt", BIN_TYPE);
		public static final MethodHandle LTE       = findMH(JSOps.class, "lte", BIN_TYPE);
		public static final MethodHandle GT        = findMH(JSOps.class, "gt", BIN_TYPE);
		public static final MethodHandle GTE       = findMH(JSOps.class, "gte", BIN_TYPE);
		public static final MethodHandle AND       = findMH(JSOps.class, "and", BIN_TYPE);
		public static final MethodHandle OR        = findMH(JSOps.class, "or", BIN_TYPE);

		// Primitive & Specialized ADD
		public static final MethodHandle ADD_DD_D = findMH(JSOps.class, "add", BIN_DD_D);
		public static final MethodHandle ADD_II_I = findMH(JSOps.class, "add", BIN_II_I);
		public static final MethodHandle ADD_ID_D = findMH(JSOps.class, "add", BIN_ID_D);
		public static final MethodHandle ADD_DI_D = findMH(JSOps.class, "add", BIN_DI_D);
		public static final MethodHandle ADD_OD_O = findMH(JSOps.class, "add", BIN_OD_O);
		public static final MethodHandle ADD_DO_O = findMH(JSOps.class, "add", BIN_DO_O);
		public static final MethodHandle ADD_OI_O = findMH(JSOps.class, "add", BIN_OI_O);
		public static final MethodHandle ADD_IO_O = findMH(JSOps.class, "add", BIN_IO_O);
		public static final MethodHandle ADD_SS_S = findMH(JSOps.class, "add", BIN_SS_S);
		public static final MethodHandle ADD_SO_S = findMH(JSOps.class, "add", BIN_SO_S);
		public static final MethodHandle ADD_OS_S = findMH(JSOps.class, "add", BIN_OS_S);

		// Primitive SUB
		public static final MethodHandle SUB_DD_D = findMH(JSOps.class, "sub", BIN_DD_D);
		public static final MethodHandle SUB_II_I = findMH(JSOps.class, "sub", BIN_II_I);
		public static final MethodHandle SUB_ID_D = findMH(JSOps.class, "sub", BIN_ID_D);
		public static final MethodHandle SUB_DI_D = findMH(JSOps.class, "sub", BIN_DI_D);
		public static final MethodHandle SUB_OD_D = findMH(JSOps.class, "sub", MethodType.methodType(double.class, Object.class, double.class));
		public static final MethodHandle SUB_DO_D = findMH(JSOps.class, "sub", MethodType.methodType(double.class, double.class, Object.class));

		// Primitive MUL
		public static final MethodHandle MUL_DD_D = findMH(JSOps.class, "mul", BIN_DD_D);
		public static final MethodHandle MUL_II_I = findMH(JSOps.class, "mul", BIN_II_I);
		public static final MethodHandle MUL_ID_D = findMH(JSOps.class, "mul", BIN_ID_D);
		public static final MethodHandle MUL_DI_D = findMH(JSOps.class, "mul", BIN_DI_D);
		public static final MethodHandle MUL_OD_D = findMH(JSOps.class, "mul", MethodType.methodType(double.class, Object.class, double.class));
		public static final MethodHandle MUL_DO_D = findMH(JSOps.class, "mul", MethodType.methodType(double.class, double.class, Object.class));

		// Primitive DIV
		public static final MethodHandle DIV_DD_D = findMH(JSOps.class, "div", BIN_DD_D);
		public static final MethodHandle DIV_II_D = findMH(JSOps.class, "div", MethodType.methodType(double.class, int.class, int.class));
		public static final MethodHandle DIV_ID_D = findMH(JSOps.class, "div", BIN_ID_D);
		public static final MethodHandle DIV_DI_D = findMH(JSOps.class, "div", BIN_DI_D);
		public static final MethodHandle DIV_OD_D = findMH(JSOps.class, "div", MethodType.methodType(double.class, Object.class, double.class));
		public static final MethodHandle DIV_DO_D = findMH(JSOps.class, "div", MethodType.methodType(double.class, double.class, Object.class));

		// Primitive MOD
		public static final MethodHandle MOD_DD_D = findMH(JSOps.class, "mod", BIN_DD_D);
		public static final MethodHandle MOD_II_I = findMH(JSOps.class, "mod", BIN_II_I);
		public static final MethodHandle MOD_ID_D = findMH(JSOps.class, "mod", BIN_ID_D);
		public static final MethodHandle MOD_DI_D = findMH(JSOps.class, "mod", BIN_DI_D);
		public static final MethodHandle MOD_OD_D = findMH(JSOps.class, "mod", MethodType.methodType(double.class, Object.class, double.class));
		public static final MethodHandle MOD_DO_D = findMH(JSOps.class, "mod", MethodType.methodType(double.class, double.class, Object.class));

		// Equality Specializations with Primitive
		public static final MethodHandle EQ_OI_Z = findMH(JSOps.class, "isEqInt", MethodType.methodType(boolean.class, Object.class, int.class));
		public static final MethodHandle EQ_OD_Z = findMH(JSOps.class, "isEqDouble", MethodType.methodType(boolean.class, Object.class, double.class));
		public static final MethodHandle EQ_OB_Z = findMH(JSOps.class, "isEqBool", MethodType.methodType(boolean.class, Object.class, boolean.class));
		public static final MethodHandle EQ_OS_Z = findMH(JSOps.class, "isEqString", MethodType.methodType(boolean.class, Object.class, String.class));

		public static final MethodHandle STRICT_EQ_OI_Z = findMH(JSOps.class, "isStrictEqInt", MethodType.methodType(boolean.class, Object.class, int.class));
		public static final MethodHandle STRICT_EQ_OD_Z = findMH(JSOps.class, "isStrictEqDouble", MethodType.methodType(boolean.class, Object.class, double.class));
		public static final MethodHandle STRICT_EQ_OB_Z = findMH(JSOps.class, "isStrictEqBool", MethodType.methodType(boolean.class, Object.class, boolean.class));
		public static final MethodHandle STRICT_EQ_OS_Z = findMH(JSOps.class, "isStrictEqString", MethodType.methodType(boolean.class, Object.class, String.class));
	}

	public static final class IndexMH {
		public static final MethodHandle GET = findMH(JSLinker.class, "getIndex", MethodType.methodType(Object.class, Object.class, Object.class));
		public static final MethodHandle SET = findMH(JSLinker.class, "setIndex", MethodType.methodType(void.class, Object.class, Object.class, Object.class));
	}

	public static final class FieldMH {
		private static final MethodType GET_DIR_TYPE = MethodType.methodType(Object.class, long.class, Object.class);

		public static final MethodHandle
		 GET_INT     = findMH(JSLinker.class, "getIntDirect", GET_DIR_TYPE),
		 GET_DOUBLE  = findMH(JSLinker.class, "getDoubleDirect", GET_DIR_TYPE),
		 GET_LONG    = findMH(JSLinker.class, "getLongDirect", GET_DIR_TYPE),
		 GET_FLOAT   = findMH(JSLinker.class, "getFloatDirect", GET_DIR_TYPE),
		 GET_SHORT   = findMH(JSLinker.class, "getShortDirect", GET_DIR_TYPE),
		 GET_BYTE    = findMH(JSLinker.class, "getByteDirect", GET_DIR_TYPE),
		 GET_CHAR    = findMH(JSLinker.class, "getCharDirect", GET_DIR_TYPE),
		 GET_BOOLEAN = findMH(JSLinker.class, "getBooleanDirect", GET_DIR_TYPE),
		 GET_OBJECT  = findMH(JSLinker.class, "getObjectDirect", GET_DIR_TYPE);

		private static final MethodType PUT_DIR_TYPE = MethodType.methodType(void.class, long.class, Object.class, Object.class);

		public static final MethodHandle
		 PUT_INT     = findMH(JSLinker.class, "putIntDirect", PUT_DIR_TYPE),
		 PUT_DOUBLE  = findMH(JSLinker.class, "putDoubleDirect", PUT_DIR_TYPE),
		 PUT_LONG    = findMH(JSLinker.class, "putLongDirect", PUT_DIR_TYPE),
		 PUT_FLOAT   = findMH(JSLinker.class, "putFloatDirect", PUT_DIR_TYPE),
		 PUT_SHORT   = findMH(JSLinker.class, "putShortDirect", PUT_DIR_TYPE),
		 PUT_BYTE    = findMH(JSLinker.class, "putByteDirect", PUT_DIR_TYPE),
		 PUT_CHAR    = findMH(JSLinker.class, "putCharDirect", PUT_DIR_TYPE),
		 PUT_BOOLEAN = findMH(JSLinker.class, "putBooleanDirect", PUT_DIR_TYPE),
		 PUT_OBJECT  = findMH(JSLinker.class, "putObjectDirect", PUT_DIR_TYPE);

		private static final MethodType PRIM_INT_TYPE = MethodType.methodType(int.class, long.class, Object.class);

		public static final MethodHandle
		 GET_INT_PRIM       = findMH(JSLinker.class, "getIntDirectPrim", PRIM_INT_TYPE),
		 GET_DOUBLE_AS_INT  = findMH(JSLinker.class, "getDoubleAsIntPrim", PRIM_INT_TYPE),
		 GET_LONG_AS_INT    = findMH(JSLinker.class, "getLongAsIntPrim", PRIM_INT_TYPE),
		 GET_FLOAT_AS_INT   = findMH(JSLinker.class, "getFloatAsIntPrim", PRIM_INT_TYPE),
		 GET_SHORT_AS_INT   = findMH(JSLinker.class, "getShortAsIntPrim", PRIM_INT_TYPE),
		 GET_BYTE_AS_INT    = findMH(JSLinker.class, "getByteAsIntPrim", PRIM_INT_TYPE),
		 GET_CHAR_AS_INT    = findMH(JSLinker.class, "getCharAsIntPrim", PRIM_INT_TYPE),
		 GET_BOOLEAN_AS_INT = findMH(JSLinker.class, "getBooleanAsIntPrim", PRIM_INT_TYPE),
		 GET_OBJECT_AS_INT  = findMH(JSLinker.class, "getObjectAsIntPrim", PRIM_INT_TYPE);

		private static final MethodType PRIM_DOUBLE_TYPE = MethodType.methodType(double.class, long.class, Object.class);

		public static final MethodHandle
		 GET_DOUBLE_PRIM       = findMH(JSLinker.class, "getDoubleDirectPrim", PRIM_DOUBLE_TYPE),
		 GET_INT_AS_DOUBLE     = findMH(JSLinker.class, "getIntAsDoublePrim", PRIM_DOUBLE_TYPE),
		 GET_LONG_AS_DOUBLE    = findMH(JSLinker.class, "getLongAsDoublePrim", PRIM_DOUBLE_TYPE),
		 GET_FLOAT_AS_DOUBLE   = findMH(JSLinker.class, "getFloatAsDoublePrim", PRIM_DOUBLE_TYPE),
		 GET_SHORT_AS_DOUBLE   = findMH(JSLinker.class, "getShortAsDoublePrim", PRIM_DOUBLE_TYPE),
		 GET_BYTE_AS_DOUBLE    = findMH(JSLinker.class, "getByteAsDoublePrim", PRIM_DOUBLE_TYPE),
		 GET_CHAR_AS_DOUBLE    = findMH(JSLinker.class, "getCharAsDoublePrim", PRIM_DOUBLE_TYPE),
		 GET_BOOLEAN_AS_DOUBLE = findMH(JSLinker.class, "getBooleanAsDoublePrim", PRIM_DOUBLE_TYPE),
		 GET_OBJECT_AS_DOUBLE  = findMH(JSLinker.class, "getObjectAsDoublePrim", PRIM_DOUBLE_TYPE);

		private static final MethodType PRIM_LONG_TYPE = MethodType.methodType(long.class, long.class, Object.class);

		public static final MethodHandle
		 GET_LONG_PRIM       = findMH(JSLinker.class, "getLongDirectPrim", PRIM_LONG_TYPE),
		 GET_INT_AS_LONG     = findMH(JSLinker.class, "getIntAsLongPrim", PRIM_LONG_TYPE),
		 GET_DOUBLE_AS_LONG  = findMH(JSLinker.class, "getDoubleAsLongPrim", PRIM_LONG_TYPE),
		 GET_FLOAT_AS_LONG   = findMH(JSLinker.class, "getFloatAsLongPrim", PRIM_LONG_TYPE),
		 GET_SHORT_AS_LONG   = findMH(JSLinker.class, "getShortAsLongPrim", PRIM_LONG_TYPE),
		 GET_BYTE_AS_LONG    = findMH(JSLinker.class, "getByteAsLongPrim", PRIM_LONG_TYPE),
		 GET_CHAR_AS_LONG    = findMH(JSLinker.class, "getCharAsLongPrim", PRIM_LONG_TYPE),
		 GET_BOOLEAN_AS_LONG = findMH(JSLinker.class, "getBooleanAsLongPrim", PRIM_LONG_TYPE),
		 GET_OBJECT_AS_LONG  = findMH(JSLinker.class, "getObjectAsLongPrim", PRIM_LONG_TYPE);
	}
	//endregion

	//region ChainedCallSite (IC 状态机与 Megamorphic 防护)

	public static class ChainedCallSite extends MutableCallSite {
		public static final int          MAX_CHAIN_DEPTH = 3; // Shape 种类 <= 3 时使用链式 Guard, >= 4 时自动演化为 Megamorphic 缓存表
		private             int          chainDepth      = 0;
		private volatile    boolean      megamorphic     = false;
		private             MethodHandle megamorphicTarget;

		// Offset-Equivalent IC (同偏移多态状态)
		private       int           commonOffset     = -1;
		private       byte          commonType       = -1;
		private       boolean       offsetEquivalent = true;
		private final List<JSShape> observedShapes   = new ArrayList<>(4);

		// Megamorphic 多槽直接映射表 (Direct Mapped Fast Shape->Offset Cache)
		public static final int       CACHE_SIZE  = 8;
		public static final int       CACHE_MASK  = CACHE_SIZE - 1;
		public final        JSShape[] shapeCache  = new JSShape[CACHE_SIZE];
		public final        int[]     offsetCache = new int[CACHE_SIZE];

		public ChainedCallSite(MethodType type, MethodHandle megamorphicTarget) {
			super(type);
			this.megamorphicTarget = megamorphicTarget;
		}

		public void setMegamorphicTarget(MethodHandle target) {
			this.megamorphicTarget = target;
		}

		public MethodHandle getMegamorphicTarget() {
			return this.megamorphicTarget;
		}

		public boolean isMegamorphic() {
			return megamorphic;
		}

		public int getChainDepth() {
			return chainDepth;
		}

		public synchronized void recordShape(JSShape shape, int offset, byte type) {
			if (!observedShapes.contains(shape)) {
				observedShapes.add(shape);
				if (commonOffset == -1) {
					commonOffset = offset;
					commonType = type;
				} else if (commonOffset != offset || commonType != type) {
					offsetEquivalent = false;
				}
			}
		}

		public boolean isOffsetEquivalent() {
			return offsetEquivalent && commonOffset >= 0 && observedShapes.size() >= 2;
		}

		public int getCommonOffset() {
			return commonOffset;
		}

		public byte getCommonType() {
			return commonType;
		}

		public List<JSShape> getObservedShapes() {
			return observedShapes;
		}

		public synchronized boolean installGuardOrSwitchMegamorphic(MethodHandle test, MethodHandle fastTarget) {
			if (megamorphic) {
				return false;
			}
			chainDepth++;
			if (chainDepth > MAX_CHAIN_DEPTH) {
				megamorphic = true;
				if (megamorphicTarget != null) {
					setTarget(megamorphicTarget.asType(type()));
				}
				return false;
			}
			MethodHandle currentFallback = getTarget();
			MethodHandle guard           = MethodHandles.guardWithTest(test, fastTarget.asType(type()), currentFallback);
			setTarget(guard);
			return true;
		}
	}
	//endregion

	//region Multi-Shape Guard Stubs (同偏移多态坍缩快速守卫)

	public static boolean isMatchMask(long expectedMask, Object target) {
		return target instanceof JSObject jsObj && (jsObj.shape.mask & expectedMask) != 0L;
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

	private static MethodHandle buildMultiShapeGuard(List<JSShape> shapes) {
		int n = shapes.size();
		if (n == 1) return MH_IS_EXACT_SHAPE.bindTo(shapes.get(0));

		// 位掩码多态守卫 (Bitmask-Based Multi-Shape Guard): 汇编级单条 TEST 指令，无分支比对
		long    combinedMask = 0L;
		boolean allHaveMask  = true;
		for (JSShape s : shapes) {
			if (s.mask == 0L) {
				allHaveMask = false;
				break;
			}
			combinedMask |= s.mask;
		}

		if (allHaveMask && combinedMask != 0L) {
			MethodHandle mh = findMH(JSLinker.class, "isMatchMask", MethodType.methodType(boolean.class, long.class, Object.class));
			return MethodHandles.insertArguments(mh, 0, combinedMask);
		}

		return findMH(JSLinker.class, "isShapeN", MethodType.methodType(boolean.class, JSShape[].class, Object.class)).bindTo(shapes.toArray(new JSShape[0]));
	}
	//endregion

	//region BSM 引导方法

	public static CallSite bootstrapGetProp(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type,
	 String propName
	) {
		String          sym         = SymbolTable.symbol(propName);
		ChainedCallSite site        = new ChainedCallSite(type, null);
		MethodHandle    megamorphic = MethodHandles.insertArguments(PropMH.GET_MEGAMORPHIC, 2, sym).bindTo(site);
		site.setMegamorphicTarget(megamorphic);
		MethodHandle fallback = MethodHandles.insertArguments(PropMH.GET_FALLBACK, 2, sym).bindTo(site);
		site.setTarget(fallback.asType(type));
		return site;
	}

	public static CallSite bootstrapGetPropInt(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type,
	 String propName
	) {
		String          sym         = SymbolTable.symbol(propName);
		ChainedCallSite site        = new ChainedCallSite(type, null);
		MethodHandle    megamorphic = MethodHandles.insertArguments(PropMH.GET_INT_MEGAMORPHIC, 2, sym).bindTo(site);
		site.setMegamorphicTarget(megamorphic);
		MethodHandle fallback = MethodHandles.insertArguments(PropMH.GET_INT_FALLBACK, 2, sym).bindTo(site);
		site.setTarget(fallback.asType(type));
		return site;
	}

	public static CallSite bootstrapGetPropDouble(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type,
	 String propName
	) {
		String          sym         = SymbolTable.symbol(propName);
		ChainedCallSite site        = new ChainedCallSite(type, null);
		MethodHandle    megamorphic = MethodHandles.insertArguments(PropMH.GET_DOUBLE_MEGAMORPHIC, 2, sym).bindTo(site);
		site.setMegamorphicTarget(megamorphic);
		MethodHandle fallback = MethodHandles.insertArguments(PropMH.GET_DOUBLE_FALLBACK, 2, sym).bindTo(site);
		site.setTarget(fallback.asType(type));
		return site;
	}

	public static CallSite bootstrapGetPropLong(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type,
	 String propName
	) {
		String          sym         = SymbolTable.symbol(propName);
		ChainedCallSite site        = new ChainedCallSite(type, null);
		MethodHandle    megamorphic = MethodHandles.insertArguments(PropMH.GET_LONG_MEGAMORPHIC, 2, sym).bindTo(site);
		site.setMegamorphicTarget(megamorphic);
		MethodHandle fallback = MethodHandles.insertArguments(PropMH.GET_LONG_FALLBACK, 2, sym).bindTo(site);
		site.setTarget(fallback.asType(type));
		return site;
	}

	public static CallSite bootstrapSetProp(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type,
	 String propName
	) {
		String          sym         = SymbolTable.symbol(propName);
		ChainedCallSite site        = new ChainedCallSite(type, null);
		MethodHandle    megamorphic = MethodHandles.insertArguments(PropMH.SET_MEGAMORPHIC, 3, sym).bindTo(site);
		site.setMegamorphicTarget(megamorphic);
		MethodHandle fallback = MethodHandles.insertArguments(PropMH.SET_FALLBACK, 3, sym).bindTo(site);
		site.setTarget(fallback.asType(type));
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
		site.setTarget(fallback.asType(type));
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
		site.setTarget(fallback.asType(type));
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
		return new ConstantCallSite(IndexMH.GET.asType(type));
	}

	public static CallSite bootstrapSetIndex(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type
	) {
		return new ConstantCallSite(IndexMH.SET.asType(type));
	}
	//endregion

	//region Fallback 与 Inline Cache 实现

	public static double getJSObjDoubleSlot(int slot, Object target) {
		return ((JSObject) target).getDoubleSlot(slot);
	}

	public static Object getPropMegamorphic(ChainedCallSite site, Object target, String propName) {
		if (target instanceof JSObject jsObj) {
			if (site.isOffsetEquivalent()) {
				return jsObj.getSlot(site.getCommonOffset());
			}
			JSShape s   = jsObj.shape;
			int     idx = s.id & ChainedCallSite.CACHE_MASK;
			if (site.shapeCache[idx] == s) {
				return jsObj.getSlot(site.offsetCache[idx]);
			}
			int offset = s.getOffset(propName);
			if (offset >= 0) {
				site.shapeCache[idx] = s;
				site.offsetCache[idx] = offset;
				return jsObj.getSlot(offset);
			}
			return jsObj.get(propName);
		}
		if (target == null || target == JSUndefined.INSTANCE) return JSUndefined.INSTANCE;
		return getPropGeneric(target, propName);
	}

	public static double getPropDoubleMegamorphic(ChainedCallSite site, Object target, String propName) {
		if (target instanceof JSObject jsObj) {
			if (site.isOffsetEquivalent()) {
				return jsObj.getDoubleSlot(site.getCommonOffset());
			}
			JSShape s   = jsObj.shape;
			int     idx = s.id & ChainedCallSite.CACHE_MASK;
			if (site.shapeCache[idx] == s) {
				return jsObj.getDoubleSlot(site.offsetCache[idx]);
			}
			int offset = s.getOffset(propName);
			if (offset >= 0) {
				site.shapeCache[idx] = s;
				site.offsetCache[idx] = offset;
				return jsObj.getDoubleSlot(offset);
			}
			return jsObj.getAsDouble(propName);
		}
		if (target == null || target == JSUndefined.INSTANCE) return Double.NaN;
		return getPropDoubleGeneric(target, propName);
	}

	public static int getPropIntMegamorphic(ChainedCallSite site, Object target, String propName) {
		if (target instanceof JSObject jsObj) {
			if (site.isOffsetEquivalent()) {
				return (int) jsObj.getDoubleSlot(site.getCommonOffset());
			}
			JSShape s   = jsObj.shape;
			int     idx = s.id & ChainedCallSite.CACHE_MASK;
			if (site.shapeCache[idx] == s) {
				return (int) jsObj.getDoubleSlot(site.offsetCache[idx]);
			}
			int offset = s.getOffset(propName);
			if (offset >= 0) {
				site.shapeCache[idx] = s;
				site.offsetCache[idx] = offset;
				return (int) jsObj.getDoubleSlot(offset);
			}
			return JSOps.toInt(jsObj.get(propName));
		}
		if (target == null || target == JSUndefined.INSTANCE) return 0;
		return getPropIntGeneric(target, propName);
	}

	public static long getPropLongMegamorphic(ChainedCallSite site, Object target, String propName) {
		if (target instanceof JSObject jsObj) {
			if (site.isOffsetEquivalent()) {
				return (long) jsObj.getDoubleSlot(site.getCommonOffset());
			}
			JSShape s   = jsObj.shape;
			int     idx = s.id & ChainedCallSite.CACHE_MASK;
			if (site.shapeCache[idx] == s) {
				return (long) jsObj.getDoubleSlot(site.offsetCache[idx]);
			}
			int offset = s.getOffset(propName);
			if (offset >= 0) {
				site.shapeCache[idx] = s;
				site.offsetCache[idx] = offset;
				return (long) jsObj.getDoubleSlot(offset);
			}
			return JSOps.toLong(jsObj.get(propName));
		}
		if (target == null || target == JSUndefined.INSTANCE) return 0L;
		return getPropLongGeneric(target, propName);
	}

	public static void setPropMegamorphic(ChainedCallSite site, Object target, Object value, String propName) {
		if (target instanceof JSObject jsObj) {
			if (site.isOffsetEquivalent()) {
				jsObj.setSlot(site.getCommonOffset(), value);
				return;
			}
			JSShape s   = jsObj.shape;
			int     idx = s.id & ChainedCallSite.CACHE_MASK;
			if (site.shapeCache[idx] == s) {
				jsObj.setSlot(site.offsetCache[idx], value);
				return;
			}
			int offset = s.getOffset(propName);
			if (offset >= 0) {
				site.shapeCache[idx] = s;
				site.offsetCache[idx] = offset;
				jsObj.setSlot(offset, value);
				return;
			}
			jsObj.put(propName, value);
			return;
		}
		setPropGeneric(target, value, propName);
	}

	public static Object getPropGeneric(Object target, String propName) {
		if (target == null || target == JSUndefined.INSTANCE) {
			return JSUndefined.INSTANCE;
		}

		if (target instanceof JSObject jsObj) {
			return jsObj.get(propName);
		}

		if (target instanceof Map) {
			Object v = ((Map<?, ?>) target).get(propName);
			return v == null ? JSUndefined.INSTANCE : v;
		}

		if (target.getClass().isArray() && "length".equals(propName)) {
			return (double) java.lang.reflect.Array.getLength(target);
		}

		Class<?> targetClass = target.getClass();

		try {
			Field field = getDeclaredFieldRecursive(targetClass, propName);
			field.setAccessible(true);
			return field.get(target);
		} catch (Throwable ignored) {
		}

		String   capName          = Character.toUpperCase(propName.charAt(0)) + (propName.length() > 1 ? propName.substring(1) : "");
		String[] getterCandidates = new String[]{"get" + capName, "is" + capName, propName};
		for (String candidate : getterCandidates) {
			try {
				Method method = targetClass.getMethod(candidate);
				method.setAccessible(true);
				return method.invoke(target);
			} catch (Throwable ignored) {
			}
		}

		try {
			for (Method m : targetClass.getMethods()) {
				if (m.getName().equals(propName)) {
					return (JSFunction) (cx, thisObj, args) -> invokeJavaMethod(target, propName, args);
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

		Class<?> targetClass = target.getClass();

		try {
			Field field = getDeclaredFieldRecursive(targetClass, propName);
			field.setAccessible(true);
			setFieldDirect(target, field, value);
			return;
		} catch (Throwable ignored) {
		}

		String capName = Character.toUpperCase(propName.charAt(0)) + (propName.length() > 1 ? propName.substring(1) : "");
		for (Method m : targetClass.getMethods()) {
			if (m.getName().equals("set" + capName) && m.getParameterCount() == 1) {
				try {
					m.setAccessible(true);
					Object casted = castValue(value, m.getParameterTypes()[0]);
					m.invoke(target, casted);
					return;
				} catch (Throwable ignored) {
				}
			}
		}
	}

	private static void setFieldDirect(Object target, Field field, Object value) throws IllegalAccessException {
		Class<?> type = field.getType();
		if (type == int.class) { field.setInt(target, JSOps.toInt(value)); } else if (type == double.class) {
			field.setDouble(target, JSOps.toDouble(value));
		} else if (type == long.class) {
			field.setLong(target, JSOps.toLong(value));
		} else if (type == float.class) {
			field.setFloat(target, (float) JSOps.toDouble(value));
		} else if (type == short.class) {
			field.setShort(target, (short) JSOps.toInt(value));
		} else if (type == byte.class) {
			field.setByte(target, (byte) JSOps.toInt(value));
		} else if (type == char.class) {
			field.setChar(target, (value instanceof Character ch) ? ch : (value != null && !value.toString().isEmpty()) ? value.toString().charAt(0) : '\0');
		} else if (type == boolean.class) { field.setBoolean(target, JSOps.isTruthy(value)); } else {
			field.set(target, value);
		}
	}

	public static Object getPropFallback(ChainedCallSite site, Object target, String propName) throws Throwable {
		if (target == null || target == JSUndefined.INSTANCE) {
			return JSUndefined.INSTANCE;
		}

		if (target instanceof JSObject jsObj) {
			int offset = jsObj.shape.getOffset(propName);
			if (offset >= 0) {
				byte type = jsObj.shape.getSlotType(offset);
				site.recordShape(jsObj.shape, offset, type);

				if (site.isOffsetEquivalent()) {
					int commonOff = site.getCommonOffset();
					MethodHandle test = buildMultiShapeGuard(site.getObservedShapes());
					MethodHandle directSlotGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, commonOff);
					MethodHandle fallbackTarget = site.getMegamorphicTarget() != null ? site.getMegamorphicTarget() : site.getTarget();
					site.setTarget(MethodHandles.guardWithTest(test, directSlotGetter.asType(site.type()), fallbackTarget.asType(site.type())));
					return jsObj.getSlot(commonOff);
				}

				MethodHandle test = MH_IS_EXACT_SHAPE.bindTo(jsObj.shape);
				MethodHandle directSlotGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, offset);
				site.installGuardOrSwitchMegamorphic(test, directSlotGetter);
				return jsObj.getSlot(offset);
			}
			return jsObj.get(propName);
		}

		if (target instanceof Map) {
			Object v = ((Map<?, ?>) target).get(propName);
			return v == null ? JSUndefined.INSTANCE : v;
		}

		if (target.getClass().isArray()) {
			if ("length".equals(propName)) {
				return (double) java.lang.reflect.Array.getLength(target);
			}
		}

		Class<?> targetClass = target.getClass();

		// 1. 尝试匹配 Java 字段 (私有/公有字段通过 MAGICIMPL 字节码直读或 Unsafe 偏移直读)
		if (STRATEGY == InvocationStrategy.MAGIC_ACCESSOR) {
			try {
				MethodHandle exactGetter = MagicJIT.getFieldGetterStub(targetClass, propName);
				if (exactGetter != null) {
					MethodHandle test = MH_IS_EXACT_CLASS.bindTo(targetClass);
					site.installGuardOrSwitchMegamorphic(test, exactGetter);
					return exactGetter.invokeExact(target);
				}
			} catch (Throwable ignored) {
			}
		}

		try {
			Field        field        = getDeclaredFieldRecursive(targetClass, propName);
			long         offset       = LinkerHelper.getFieldOffset(field);
			MethodHandle directGetter = buildDirectFieldGetter(targetClass, field, offset);

			// 构造单态/多态内联缓存
			MethodHandle test = MH_IS_EXACT_CLASS.bindTo(targetClass);
			site.installGuardOrSwitchMegamorphic(test, directGetter);
			return directGetter.invoke(target);
		} catch (Throwable ignored) {
		}

		// 2. 尝试匹配 getter 方法 (getFoo / isFoo)
		Method getterMethod = MethodResolver.findGetterMethod(targetClass, propName);
		if (getterMethod != null) {
			try {
				MethodHandle mh = Magic.lookup.unreflect(getterMethod);
				return mh.invoke(target);
			} catch (Throwable ignored) {
			}
		}

		// 3. 尝试匹配方法名并返回绑定的 JS 方法函数
		if (!MethodResolver.findCandidateMethods(targetClass, propName).isEmpty()) {
			return (JSFunction) (cx, thisObj, args) -> invokeJavaMethod(target, propName, args);
		}

		return JSUndefined.INSTANCE;
	}

	public static void setPropFallback(ChainedCallSite site, Object target, Object value, String propName)
	 throws Throwable {
		if (target == null || target == JSUndefined.INSTANCE) return;

		if (target instanceof JSObject jsObj) {
			int offset = jsObj.shape.getOffset(propName);
			if (offset >= 0) {
				byte type = jsObj.shape.getSlotType(offset);
				site.recordShape(jsObj.shape, offset, type);

				if (site.isOffsetEquivalent()) {
					int          commonOff = site.getCommonOffset();
					MethodHandle test      = MethodHandles.dropArguments(buildMultiShapeGuard(site.getObservedShapes()), 1, Object.class);
					MethodHandle directSlotSetter;
					if (STRATEGY == InvocationStrategy.MAGIC_ACCESSOR && commonOff < JSObject.IN_OBJECT_SLOTS) {
						directSlotSetter = MagicJIT.createExactFieldSetterStub(JSObject.class, "slot" + commonOff);
					} else {
						directSlotSetter = MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT, 0, commonOff);
					}
					MethodHandle fallbackTarget = site.getMegamorphicTarget() != null ? site.getMegamorphicTarget() : site.getTarget();
					site.setTarget(MethodHandles.guardWithTest(test, directSlotSetter.asType(site.type()), fallbackTarget.asType(site.type())));
					jsObj.setSlot(commonOff, value);
					return;
				}

				MethodHandle test = MethodHandles.dropArguments(MH_IS_EXACT_SHAPE.bindTo(jsObj.shape), 1, Object.class);
				MethodHandle directSlotSetter;
				if (STRATEGY == InvocationStrategy.MAGIC_ACCESSOR && offset < JSObject.IN_OBJECT_SLOTS) {
					directSlotSetter = MagicJIT.createExactFieldSetterStub(JSObject.class, "slot" + offset);
				} else {
					directSlotSetter = MethodHandles.insertArguments(
					 MH_SET_JS_OBJ_SLOT,
					 0,
					 offset
					);
				}
				site.installGuardOrSwitchMegamorphic(test, directSlotSetter);
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
					site.installGuardOrSwitchMegamorphic(test, exactSetter);
					exactSetter.invokeExact(target, value);
					return;
				}
			} catch (Throwable ignored) {
			}
		}

		try {
			Field        field        = getDeclaredFieldRecursive(targetClass, propName);
			long         offset       = LinkerHelper.getFieldOffset(field);
			MethodHandle directSetter = buildDirectFieldSetter(targetClass, field, offset);

			MethodHandle test = findStatic("isExactClass", MethodType.methodType(boolean.class, Class.class, Object.class))
			 .bindTo(targetClass);
			test = MethodHandles.dropArguments(test, 1, Object.class);
			site.installGuardOrSwitchMegamorphic(test, directSetter);
			directSetter.invoke(target, value);
			return;
		} catch (Throwable ignored) {
		}

		// 2. 尝试匹配 setter 方法 (setFoo)
		Method setterMethod = MethodResolver.findSetterMethod(targetClass, propName);
		if (setterMethod != null) {
			try {
				Object casted = castValue(value, setterMethod.getParameterTypes()[0]);
				setterMethod.invoke(target, casted);
				return;
			} catch (Throwable ignored) {
			}
		}
	}
	//endregion

	//region Dynalink / JLS 规范级重载决议 (Overload Resolution)

	private static final int COST_INCOMPATIBLE = 1_000_000;

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

	public static Method getSingleAbstractMethod(Class<?> iface) {
		if (!iface.isInterface()) return null;
		Method sam = null;
		for (Method m : iface.getMethods()) {
			if (Modifier.isAbstract(m.getModifiers()) && !isObjectMethod(m)) {
				if (sam != null && !isSameSignature(sam, m)) {
					return null;
				}
				sam = m;
			}
		}
		return sam;
	}

	private static boolean isObjectMethod(Method m) {
		String     name   = m.getName();
		Class<?>[] params = m.getParameterTypes();
		if ("equals".equals(name) && params.length == 1 && params[0] == Object.class) return true;
		if ("hashCode".equals(name) && params.length == 0) return true;
		if ("toString".equals(name) && params.length == 0) return true;
		return false;
	}

	private static boolean isSameSignature(Method m1, Method m2) {
		if (!m1.getName().equals(m2.getName())) return false;
		if (m1.getParameterCount() != m2.getParameterCount()) return false;
		Class<?>[] p1 = m1.getParameterTypes();
		Class<?>[] p2 = m2.getParameterTypes();
		for (int i = 0; i < p1.length; i++) {
			if (p1[i] != p2[i]) return false;
		}
		return true;
	}

	public static Object createInterfaceAdapter(Class<?> targetType, JSFunction fn) {
		return MagicJIT.getFunctionAdapter(targetType, fn);
	}

	public static Object createInterfaceAdapter(Class<?> targetType, JSObject jsObj) {
		return MagicJIT.getObjectAdapter(targetType, jsObj);
	}

	public static Object toInterface(Class<?> targetType, Object val) {
		return castValue(val, targetType);
	}

	private static int getInheritanceDistance(Class<?> from, Class<?> to) {
		if (from == to) return 0;
		if (to.isInterface()) {
			int minDistance = COST_INCOMPATIBLE;
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
			return minDistance != COST_INCOMPATIBLE ? minDistance : 10;
		}
		int      distance = 0;
		Class<?> curr     = from;
		while (curr != null && curr != to) {
			curr = curr.getSuperclass();
			distance++;
		}
		return curr == to ? distance : COST_INCOMPATIBLE;
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
			if (targetType.isPrimitive()) return COST_INCOMPATIBLE;
			if (targetType == Object.class) return 100;
			// 越具体的类型（继承深度越深）在匹配 null 时优先级越高 (Cost 越低)
			return Math.max(1, 100 - getHierarchyDepth(targetType));
		}

		Class<?> fromType = arg.getClass();
		if (fromType == targetType) return 0;

		// 0. 接口适配 (SAM 函数式接口 / JSObject 动态代理)
		if (targetType.isInterface()) {
			if (arg instanceof JSFunction) {
				if (getSingleAbstractMethod(targetType) != null) return 2;
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
				return (fromPrim == toPrim) ? 1 : COST_INCOMPATIBLE;
			}
			// 同一种基本类型的装箱/拆箱 (e.g. Integer -> int, Double -> double)
			if (fromPrim == toPrim) return 1;

			// JLS §5.1.2 基本类型无损拓宽 (Widening Primitive Conversion)
			// byte(0) -> short(1) -> int(3) -> long(4) -> float(5) -> double(6), char(2) -> int(3)
			if (fromPrim == 2 && toPrim >= 3) { // char -> int/long/float/double
				return 2 + (toPrim - 3);
			}
			if (fromPrim < toPrim && fromPrim != 2) {
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

		return COST_INCOMPATIBLE;
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
				} else if (t1.isPrimitive() && t2.isPrimitive()) {
					int idx1 = getPrimitiveTypeIndex(t1);
					int idx2 = getPrimitiveTypeIndex(t2);
					if (idx1 >= 0 && idx2 >= 0 && idx1 < idx2) {
						oneMoreSpecific = true;
					} else {
						return false;
					}
				} else if (!t1.isAssignableFrom(t2)) {
					return false;
				}
			}
		}
		return oneMoreSpecific;
	}

	public static Method findBestMatchingMethod(Class<?> clazz, String methodName, Object[] args) {
		List<Method> candidates = MethodResolver.findCandidateMethods(clazz, methodName);
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
				int c = computeConversionCost(args[i], params[i]);
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
				for (int i = 0; i < args.length; i++) cost += computeConversionCost(args[i], params[i]);
				if (cost == minCost) bestCandidates.add(m);
			}
			if (bestCandidates.size() == 1) return bestCandidates.get(0);
			// 挑选最具体的方法
			Method mostSpecific = bestCandidates.get(0);
			for (int i = 1; i < bestCandidates.size(); i++) {
				Method curr = bestCandidates.get(i);
				if (isMoreSpecific(curr, mostSpecific)) {
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
				int c = computeConversionCost(args[i], params[i]);
				if (c >= COST_INCOMPATIBLE) {
					ok = false;
					break;
				}
				totalCost += c;
			}
			if (ok) {
				for (int i = paramCount - 1; i < args.length; i++) {
					int c = computeConversionCost(args[i], varargElemType);
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

	public static Object invokeGeneric(Object target, Object[] args, String methodName) throws Throwable {
		if (target == null || target == JSUndefined.INSTANCE) {
			throw new NullPointerException("Cannot invoke method '" + methodName + "' on null/undefined");
		}

		if (target instanceof JSFunction) {
			return ((JSFunction) target).call(null, JSUndefined.INSTANCE, args);
		}

		if (target instanceof JSObject jsObj) {
			Object member = jsObj.get(methodName);
			if (member instanceof JSFunction func) {
				return func.call(null, jsObj, args);
			}
		}

		if (target instanceof CharSequence seq) {
			Object strRes = invokeStringMethod(seq.toString(), methodName, args);
			if (strRes != null || "search".equals(methodName) || "match".equals(methodName)) {
				return strRes;
			}
		}

		Class<?> clazz        = (target instanceof Class<?>) ? (Class<?>) target : target.getClass();
		Method   targetMethod = findBestMatchingMethod(clazz, methodName, args);
		if (targetMethod != null) {
			targetMethod.setAccessible(true);
			Class<?>[] paramTypes = targetMethod.getParameterTypes();
			Object[]   castedArgs = new Object[args.length];
			for (int i = 0; i < args.length; i++) {
				castedArgs[i] = castValue(args[i], paramTypes[i]);
			}
			MagicJIT.MagicInvoker invoker = MagicJIT.getMethodInvoker(clazz, methodName, args.length, Modifier.isStatic(targetMethod.getModifiers()));
			if (invoker != null) {
				return invoker.invoke(target, castedArgs);
			}
			return targetMethod.invoke(target, castedArgs);
		}

		return invokeJavaMethod(target, methodName, args);
	}

	public static Object invokeFallback(ChainedCallSite site, Object target, Object[] args, String methodName)
	 throws Throwable {
		if (target == null || target == JSUndefined.INSTANCE) {
			throw new NullPointerException("Cannot invoke method '" + methodName + "' on null/undefined");
		}

		if (target instanceof JSFunction func) {
			int arity = args.length;
			MethodHandle directMh;
			if (arity == 0) {
				directMh = JSFuncMH.CALL0;
				directMh = MethodHandles.insertArguments(directMh, 1, (Object) null);
				directMh = directMh.asType(MethodType.genericMethodType(2));
				directMh = MethodHandles.permuteArguments(directMh, MethodType.genericMethodType(1), 0, 0);
			} else if (arity == 1) {
				directMh = JSFuncMH.CALL1;
				directMh = MethodHandles.insertArguments(directMh, 1, (Object) null);
				directMh = directMh.asType(MethodType.genericMethodType(3));
				directMh = MethodHandles.permuteArguments(directMh, MethodType.genericMethodType(2), 0, 0, 1);
			} else if (arity == 2) {
				directMh = JSFuncMH.CALL2;
				directMh = MethodHandles.insertArguments(directMh, 1, (Object) null);
				directMh = directMh.asType(MethodType.genericMethodType(4));
				directMh = MethodHandles.permuteArguments(directMh, MethodType.genericMethodType(3), 0, 0, 1, 2);
			} else if (arity == 3) {
				directMh = JSFuncMH.CALL3;
				directMh = MethodHandles.insertArguments(directMh, 1, (Object) null);
				directMh = directMh.asType(MethodType.genericMethodType(5));
				directMh = MethodHandles.permuteArguments(directMh, MethodType.genericMethodType(4), 0, 0, 1, 2, 3);
			} else {
				directMh = JSFuncMH.CALL;
				directMh = MethodHandles.insertArguments(directMh, 1, (Object) null);
				directMh = directMh.asType(MethodType.methodType(Object.class, Object.class, Object.class, Object[].class));
				directMh = MethodHandles.permuteArguments(directMh, MethodType.methodType(Object.class, Object.class, Object[].class), 0, 0, 1);
				directMh = directMh.asSpreader(Object[].class, arity);
			}

			MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
			if (site.type().parameterCount() > 1) {
				test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
			}
			site.installGuardOrSwitchMegamorphic(test, directMh.asType(site.type()));

			if (arity == 0) return func.call0(null, target);
			if (arity == 1) return func.call1(null, target, args[0]);
			if (arity == 2) return func.call2(null, target, args[0], args[1]);
			if (arity == 3) return func.call3(null, target, args[0], args[1], args[2]);
			return func.call(null, target, args);
		}

		if (target instanceof JSObject jsObj) {
			Object member = jsObj.get(methodName);
			if (member instanceof JSFunction func) {
				return func.call(null, jsObj, args);
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

		// 查找最匹配的重载方法
		Method targetMethod = findBestMatchingMethod(clazz, methodName, args);

		if (targetMethod != null) {
			targetMethod.setAccessible(true);

			boolean preferMagicAccessor = (STRATEGY == InvocationStrategy.MAGIC_ACCESSOR)
			                              || (STRATEGY == InvocationStrategy.HYBRID && hasComplexParameters(targetMethod));

			if (preferMagicAccessor) {
				try {
					MethodHandle exactMh = MagicJIT.createExactMethodStub(clazz, targetMethod);
					if (exactMh != null) {
						MethodHandle test = MH_IS_EXACT_CLASS.bindTo(clazz);
						if (site.type().parameterCount() > 1) {
							test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
						}
						MethodHandle genericMh = exactMh.asType(site.type());
						site.installGuardOrSwitchMegamorphic(test, genericMh);

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

				MethodHandle test = MH_IS_EXACT_CLASS.bindTo(clazz);
				if (site.type().parameterCount() > 1) {
					test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
				}
				MethodHandle genericMh = adapted.asType(site.type());
				site.installGuardOrSwitchMegamorphic(test, genericMh);

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
			return MethodHandles.insertArguments(MH_TO_INTERFACE, 0, targetType);
		}
		return null;
	}

	public static int toInt(Object val) { return JSOps.toInt(val); }
	public static long toLong(Object val) { return JSOps.toLong(val); }
	public static double toDoubleVal(Object val) { return JSOps.toDouble(val); }
	public static float toFloat(Object val) { return JSOps.toFloat(val); }
	public static short toShort(Object val) { return JSOps.toShort(val); }
	public static byte toByte(Object val) { return JSOps.toByte(val); }
	public static char toChar(Object val) { return JSOps.toChar(val); }
	public static boolean toBoolean(Object val) { return JSOps.toBoolean(val); }
	public static String toStringVal(Object val) { return JSOps.toStr(val); }

	private record CtorKey(Class<?> clazz, int arity) { }
	private record MethodKey(Class<?> clazz, String methodName, int arity, boolean isStatic) { }

	private static final Map<CtorKey, MethodHandle> CTOR_SPREADER_CACHE = new ConcurrentHashMap<>();

	private static MethodHandle getConstructorSpreader(Class<?> clazz, int arity) {
		CtorKey key = new CtorKey(clazz, arity);
		return CTOR_SPREADER_CACHE.computeIfAbsent(key, k -> {
			Constructor<?> c = MethodResolver.findConstructor(clazz, arity);
			if (c == null) return null;
			try {
				MethodHandle mh         = Magic.lookup.unreflectConstructor(c);
				Class<?>[]   paramTypes = c.getParameterTypes();
				MethodHandle adapted    = mh;
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
		});
	}

	public static Object newGeneric(Object ctor, Object[] args) throws Throwable {
		if (ctor instanceof Class<?> clazz) {
			Constructor<?> c = MethodResolver.findConstructor(clazz, args.length);
			if (c != null) {
				Object[]   castedArgs = new Object[args.length];
				Class<?>[] paramTypes = c.getParameterTypes();
				for (int i = 0; i < args.length; i++) {
					castedArgs[i] = castValue(args[i], paramTypes[i]);
				}
				return c.newInstance(castedArgs);
			}
			throw new NoSuchMethodException("No matching constructor for " + clazz.getName() + " with " + args.length + " args");
		}

		if (ctor instanceof JSFunction) {
			JSObject newObj = new JSObject();
			Object   res    = ((JSFunction) ctor).call(null, newObj, args);
			if (res instanceof JSObject) return res;
			return newObj;
		}

		throw new IllegalArgumentException("Cannot instantiate non-constructor: " + ctor);
	}

	public static Object newFallback(ChainedCallSite site, Object ctor, Object[] args) throws Throwable {
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

	public static Object getIndex(Object target, int index) {
		if (target instanceof JSArray jsArr) {
			return jsArr.getElement(index);
		}
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
			return jsObj.get(String.valueOf(index));
		}
		return getIndex(target, Integer.valueOf(index));
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
			jsObj.put(String.valueOf(index), value);
			return;
		}
		setIndex(target, Integer.valueOf(index), value);
	}

	public static Object getIndex(Object target, Object index) {
		if (target == null || target == JSUndefined.INSTANCE) return JSUndefined.INSTANCE;
		if (index instanceof Integer i) {
			return getIndex(target, i.intValue());
		}
		if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				return getIndex(target, (int) val);
			}
		}
		if (target instanceof JSArray jsArr) {
			Long idx = JSArray.toValidArrayIndex(index);
			if (idx != null) {
				return jsArr.getElement(idx.longValue());
			}
			return jsArr.get(JSArray.toPropertyKey(index));
		}
		if (target instanceof JSObject jsObj) {
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
		if (target instanceof Map) {
			return ((Map<?, ?>) target).get(index);
		}
		return JSUndefined.INSTANCE;
	}

	public static void setIndex(Object target, Object index, Object value) {
		if (target == null || target == JSUndefined.INSTANCE) return;
		if (index instanceof Integer i) {
			setIndex(target, i.intValue(), value);
			return;
		}
		if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				setIndex(target, (int) val, value);
				return;
			}
		}
		if (target instanceof JSArray jsArr) {
			Long idx = JSArray.toValidArrayIndex(index);
			if (idx != null) {
				jsArr.setElement(idx.longValue(), value);
				return;
			}
			jsArr.put(JSArray.toPropertyKey(index), value);
			return;
		}
		if (target instanceof JSObject jsObj) {
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
		if (target instanceof Map) {
			((Map<Object, Object>) target).put(index, value);
		}
	}
	//endregion

	//region 辅助方法与直接 MethodHandle 构建

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

	private static MethodHandle buildPrimFieldGetter(Class<?> targetClass, Field field, long offset,
	                                                 Class<?> requestedPrim) {
		Class<?>     fType = field.getType();
		MethodHandle mh;
		if (requestedPrim == int.class) {
			if (fType == int.class) { mh = FieldMH.GET_INT_PRIM; } else if (fType == double.class) {
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
			if (fType == double.class) { mh = FieldMH.GET_DOUBLE_PRIM; } else if (fType == int.class) {
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

	public static int getJSObjSlotAsInt(int slot, Object target) {
		return JSOps.toInt(((JSObject) target).getSlot(slot));
	}

	public static double getJSObjSlotAsDouble(int slot, Object target) {
		return ((JSObject) target).getDoubleSlot(slot);
	}

	public static long getJSObjSlotAsLong(int slot, Object target) {
		return JSOps.toLong(((JSObject) target).getSlot(slot));
	}

	public static int getPropIntFallback(ChainedCallSite site, Object target, String propName) throws Throwable {
		if (target == null || target == JSUndefined.INSTANCE) return 0;

		if (target instanceof JSObject jsObj) {
			int offset = jsObj.shape.getOffset(propName);
			if (offset >= 0) {
				byte type = jsObj.shape.getSlotType(offset);
				site.recordShape(jsObj.shape, offset, type);

				if (site.isOffsetEquivalent()) {
					int          commonOff        = site.getCommonOffset();
					MethodHandle test             = buildMultiShapeGuard(site.getObservedShapes());
					MethodHandle directSlotGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_INT, 0, commonOff);
					MethodHandle fallbackTarget   = site.getMegamorphicTarget() != null ? site.getMegamorphicTarget() : site.getTarget();
					site.setTarget(MethodHandles.guardWithTest(test, directSlotGetter.asType(site.type()), fallbackTarget.asType(site.type())));
					return JSOps.toInt(jsObj.getSlot(commonOff));
				}

				MethodHandle test             = MH_IS_EXACT_SHAPE.bindTo(jsObj.shape);
				MethodHandle directSlotGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_INT, 0, offset);
				site.installGuardOrSwitchMegamorphic(test, directSlotGetter);
				return JSOps.toInt(jsObj.getSlot(offset));
			}
		}

		if (target.getClass().isArray() && "length".equals(propName)) {
			return java.lang.reflect.Array.getLength(target);
		}
		Class<?> targetClass = target.getClass();

		try {
			Field        field        = getDeclaredFieldRecursive(targetClass, propName);
			long         offset       = LinkerHelper.getFieldOffset(field);
			MethodHandle directGetter = buildPrimFieldGetter(targetClass, field, offset, int.class);
			MethodHandle test         = MH_IS_EXACT_CLASS.bindTo(targetClass);
			site.installGuardOrSwitchMegamorphic(test, directGetter);
			return (int) directGetter.invokeExact(target);
		} catch (Throwable ignored) {
		}

		return getPropIntGeneric(target, propName);
	}

	public static double getPropDoubleFallback(ChainedCallSite site, Object target, String propName) throws Throwable {
		if (target == null || target == JSUndefined.INSTANCE) return Double.NaN;

		if (target instanceof JSObject jsObj) {
			int offset = jsObj.shape.getOffset(propName);
			if (offset >= 0) {
				byte type = jsObj.shape.getSlotType(offset);
				site.recordShape(jsObj.shape, offset, type);

				if (site.isOffsetEquivalent()) {
					int          commonOff        = site.getCommonOffset();
					MethodHandle test             = buildMultiShapeGuard(site.getObservedShapes());
					MethodHandle directSlotGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_DOUBLE, 0, commonOff);
					MethodHandle fallbackTarget   = site.getMegamorphicTarget() != null ? site.getMegamorphicTarget() : site.getTarget();
					site.setTarget(MethodHandles.guardWithTest(test, directSlotGetter.asType(site.type()), fallbackTarget.asType(site.type())));
					return jsObj.getDoubleSlot(commonOff);
				}

				MethodHandle test             = MH_IS_EXACT_SHAPE.bindTo(jsObj.shape);
				MethodHandle directSlotGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_DOUBLE, 0, offset);
				site.installGuardOrSwitchMegamorphic(test, directSlotGetter);
				return jsObj.getDoubleSlot(offset);
			}
		}

		if (target.getClass().isArray() && "length".equals(propName)) {
			return Array.getLength(target);
		}
		Class<?> targetClass = target.getClass();

		try {
			Field        field        = getDeclaredFieldRecursive(targetClass, propName);
			long         offset       = LinkerHelper.getFieldOffset(field);
			MethodHandle directGetter = buildPrimFieldGetter(targetClass, field, offset, double.class);
			MethodHandle test         = MH_IS_EXACT_CLASS.bindTo(targetClass);
			site.installGuardOrSwitchMegamorphic(test, directGetter);
			return (double) directGetter.invokeExact(target);
		} catch (Throwable ignored) {
		}

		return getPropDoubleGeneric(target, propName);
	}

	public static long getPropLongFallback(ChainedCallSite site, Object target, String propName) throws Throwable {
		if (target == null || target == JSUndefined.INSTANCE) return 0L;

		if (target instanceof JSObject jsObj) {
			int offset = jsObj.shape.getOffset(propName);
			if (offset >= 0) {
				byte type = jsObj.shape.getSlotType(offset);
				site.recordShape(jsObj.shape, offset, type);

				if (site.isOffsetEquivalent()) {
					int          commonOff        = site.getCommonOffset();
					MethodHandle test             = buildMultiShapeGuard(site.getObservedShapes());
					MethodHandle directSlotGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_LONG, 0, commonOff);
					MethodHandle fallbackTarget   = site.getMegamorphicTarget() != null ? site.getMegamorphicTarget() : site.getTarget();
					site.setTarget(MethodHandles.guardWithTest(test, directSlotGetter.asType(site.type()), fallbackTarget.asType(site.type())));
					return JSOps.toLong(jsObj.getSlot(commonOff));
				}

				MethodHandle test             = MH_IS_EXACT_SHAPE.bindTo(jsObj.shape);
				MethodHandle directSlotGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_LONG, 0, offset);
				site.installGuardOrSwitchMegamorphic(test, directSlotGetter);
				return JSOps.toLong(jsObj.getSlot(offset));
			}
		}

		if (target.getClass().isArray() && "length".equals(propName)) {
			return (long) java.lang.reflect.Array.getLength(target);
		}
		Class<?> targetClass = target.getClass();

		try {
			Field        field        = getDeclaredFieldRecursive(targetClass, propName);
			long         offset       = LinkerHelper.getFieldOffset(field);
			MethodHandle directGetter = buildPrimFieldGetter(targetClass, field, offset, long.class);
			MethodHandle test         = MH_IS_EXACT_CLASS.bindTo(targetClass);
			site.installGuardOrSwitchMegamorphic(test, directGetter);
			return (long) directGetter.invokeExact(target);
		} catch (Throwable ignored) {
		}

		return getPropLongGeneric(target, propName);
	}

	public static int getIntDirectPrim(long offset, Object target) { return UNSAFE.getInt(target, offset); }
	public static int getDoubleAsIntPrim(long offset, Object target) { return (int) UNSAFE.getDouble(target, offset); }
	public static int getLongAsIntPrim(long offset, Object target) { return (int) UNSAFE.getLong(target, offset); }
	public static int getFloatAsIntPrim(long offset, Object target) { return (int) UNSAFE.getFloat(target, offset); }
	public static int getShortAsIntPrim(long offset, Object target) { return UNSAFE.getShort(target, offset); }
	public static int getByteAsIntPrim(long offset, Object target) { return UNSAFE.getByte(target, offset); }
	public static int getCharAsIntPrim(long offset, Object target) { return UNSAFE.getChar(target, offset); }
	public static int getBooleanAsIntPrim(long offset,
	                                      Object target) { return UNSAFE.getBoolean(target, offset) ? 1 : 0; }
	public static int getObjectAsIntPrim(long offset,
	                                     Object target) { return JSOps.toInt(UNSAFE.getObject(target, offset)); }

	public static double getDoubleDirectPrim(long offset, Object target) { return UNSAFE.getDouble(target, offset); }
	public static double getIntAsDoublePrim(long offset, Object target) { return (double) UNSAFE.getInt(target, offset); }
	public static double getLongAsDoublePrim(long offset,
	                                         Object target) { return (double) UNSAFE.getLong(target, offset); }
	public static double getFloatAsDoublePrim(long offset,
	                                          Object target) { return (double) UNSAFE.getFloat(target, offset); }
	public static double getShortAsDoublePrim(long offset,
	                                          Object target) { return (double) UNSAFE.getShort(target, offset); }
	public static double getByteAsDoublePrim(long offset,
	                                         Object target) { return (double) UNSAFE.getByte(target, offset); }
	public static double getCharAsDoublePrim(long offset,
	                                         Object target) { return (double) UNSAFE.getChar(target, offset); }
	public static double getBooleanAsDoublePrim(long offset,
	                                            Object target) { return UNSAFE.getBoolean(target, offset) ? 1.0 : 0.0; }
	public static double getObjectAsDoublePrim(long offset,
	                                           Object target) { return JSOps.toDouble(UNSAFE.getObject(target, offset)); }

	public static long getLongDirectPrim(long offset, Object target) { return UNSAFE.getLong(target, offset); }
	public static long getIntAsLongPrim(long offset, Object target) { return (long) UNSAFE.getInt(target, offset); }
	public static long getDoubleAsLongPrim(long offset, Object target) { return (long) UNSAFE.getDouble(target, offset); }
	public static long getFloatAsLongPrim(long offset, Object target) { return (long) UNSAFE.getFloat(target, offset); }
	public static long getShortAsLongPrim(long offset, Object target) { return (long) UNSAFE.getShort(target, offset); }
	public static long getByteAsLongPrim(long offset, Object target) { return (long) UNSAFE.getByte(target, offset); }
	public static long getCharAsLongPrim(long offset, Object target) { return (long) UNSAFE.getChar(target, offset); }
	public static long getBooleanAsLongPrim(long offset,
	                                        Object target) { return UNSAFE.getBoolean(target, offset) ? 1L : 0L; }
	public static long getObjectAsLongPrim(long offset,
	                                       Object target) { return JSOps.toLong(UNSAFE.getObject(target, offset)); }

	private static final Map<MethodKey, MethodHandle> METHOD_SPREADER_CACHE = new ConcurrentHashMap<>();

	private static MethodHandle getMethodSpreader(Class<?> clazz, String methodName, int arity, boolean isStatic) {
		MethodKey key = new MethodKey(clazz, methodName, arity, isStatic);
		return METHOD_SPREADER_CACHE.computeIfAbsent(key, k -> {
			Method targetMethod = MethodResolver.findMethod(clazz, methodName, arity, isStatic);
			if (targetMethod == null) return null;
			targetMethod.setAccessible(true);
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
				MethodType   genericType = MethodType.genericMethodType(1 + arity);
				MethodHandle genericMh   = adapted.asType(genericType);
				return genericMh.asSpreader(Object[].class, arity);
			} catch (Throwable e) {
				throw new RuntimeException(e);
			}
		});
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
				if (repArg instanceof JSFunction func) {
					Object[] funcArgs = new Object[]{searchStr, (double) idx, str};
					String   replStr  = JSOps.toStr(func.call(null, null, funcArgs));
					return str.substring(0, idx) + replStr + str.substring(idx + searchStr.length());
				} else {
					String repStr = JSOps.toStr(repArg);
					if (repStr.contains("$")) {
						repStr = repStr.replace("$$", "\0")
						 .replace("$&", searchStr)
						 .replace("\0", "$");
					}
					return str.substring(0, idx) + repStr + str.substring(idx + searchStr.length());
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
		StringBuilder sb = new StringBuilder(jsRep.length());
		for (int i = 0; i < jsRep.length(); i++) {
			char c = jsRep.charAt(i);
			if (c == '$' && i + 1 < jsRep.length()) {
				char next = jsRep.charAt(i + 1);
				if (next == '&') {
					sb.append("$0");
					i++;
				} else if (next == '$') {
					sb.append("\\$");
					i++;
				} else if (Character.isDigit(next)) {
					sb.append("$").append(next);
					i++;
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

	private static Object invokeJavaMethod(Object target, String methodName, Object[] args) throws Throwable {
		Class<?> clazz    = (target instanceof Class<?>) ? (Class<?>) target : target.getClass();
		boolean  isStatic = (target instanceof Class<?>);

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
		if (val == null) return castNull(targetType);
		if (targetType == Object.class || targetType.isInstance(val)) return val;
		return castValueSlow(val, targetType);
	}

	public static Object castNull(Class<?> targetType) {
		if (!targetType.isPrimitive()) return null;
		if (targetType == int.class) return 0;
		if (targetType == double.class) return 0.0;
		if (targetType == boolean.class) return false;
		if (targetType == long.class) return 0L;
		if (targetType == float.class) return 0.0f;
		if (targetType == short.class) return (short) 0;
		if (targetType == byte.class) return (byte) 0;
		if (targetType == char.class) return '\0';
		return null;
	}

	public static Object castValueSlow(Object val, Class<?> targetType) {
		if (targetType == void.class || targetType == Void.class) return null;
		if (targetType == int.class || targetType == Integer.class) return JSOps.toInt(val);
		if (targetType == double.class || targetType == Double.class) return JSOps.toDouble(val);
		if (targetType == long.class || targetType == Long.class) return JSOps.toLong(val);
		if (targetType == boolean.class || targetType == Boolean.class) return JSOps.toBoolean(val);
		if (targetType == String.class || targetType == CharSequence.class) return JSOps.toStr(val);
		if (targetType == float.class || targetType == Float.class) return JSOps.toFloat(val);
		if (targetType == short.class || targetType == Short.class) return JSOps.toShort(val);
		if (targetType == byte.class || targetType == Byte.class) return JSOps.toByte(val);
		if (targetType == char.class || targetType == Character.class) return JSOps.toChar(val);
		if (targetType.isInterface()) {
			if (val instanceof JSFunction fn && getSingleAbstractMethod(targetType) != null) {
				return createInterfaceAdapter(targetType, fn);
			}
			if (val instanceof JSObject jsObj) {
				return createInterfaceAdapter(targetType, jsObj);
			}
		}
		return val;
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

	public static Object getIntDirect(long offset, Object target) {
		return (double) UNSAFE.getInt(target, offset);
	}

	public static Object getDoubleDirect(long offset, Object target) {
		return UNSAFE.getDouble(target, offset);
	}

	public static Object getLongDirect(long offset, Object target) {
		return (double) UNSAFE.getLong(target, offset);
	}

	public static Object getFloatDirect(long offset, Object target) {
		return (double) UNSAFE.getFloat(target, offset);
	}

	public static Object getShortDirect(long offset, Object target) {
		return (double) UNSAFE.getShort(target, offset);
	}

	public static Object getByteDirect(long offset, Object target) {
		return (double) UNSAFE.getByte(target, offset);
	}

	public static Object getCharDirect(long offset, Object target) {
		return String.valueOf(UNSAFE.getChar(target, offset));
	}

	public static Object getBooleanDirect(long offset, Object target) {
		return UNSAFE.getBoolean(target, offset);
	}

	public static Object getObjectDirect(long offset, Object target) {
		return UNSAFE.getObject(target, offset);
	}

	public static void putIntDirect(long offset, Object target, Object val) {
		UNSAFE.putInt(target, offset, val instanceof Number n ? n.intValue() : JSOps.toInt(val));
	}

	public static void putDoubleDirect(long offset, Object target, Object val) {
		UNSAFE.putDouble(target, offset, val instanceof Number n ? n.doubleValue() : JSOps.toDouble(val));
	}

	public static void putLongDirect(long offset, Object target, Object val) {
		UNSAFE.putLong(target, offset, val instanceof Number n ? n.longValue() : JSOps.toLong(val));
	}

	public static void putFloatDirect(long offset, Object target, Object val) {
		UNSAFE.putFloat(target, offset, val instanceof Number n ? n.floatValue() : (float) JSOps.toDouble(val));
	}

	public static void putShortDirect(long offset, Object target, Object val) {
		UNSAFE.putShort(target, offset, val instanceof Number n ? n.shortValue() : (short) JSOps.toInt(val));
	}

	public static void putByteDirect(long offset, Object target, Object val) {
		UNSAFE.putByte(target, offset, val instanceof Number n ? n.byteValue() : (byte) JSOps.toInt(val));
	}

	public static void putCharDirect(long offset, Object target, Object val) {
		char c = (val instanceof Character ch) ? ch : (val != null && !val.toString().isEmpty()) ? val.toString().charAt(0) : '\0';
		UNSAFE.putChar(target, offset, c);
	}

	public static void putBooleanDirect(long offset, Object target, Object val) {
		UNSAFE.putBoolean(target, offset, JSOps.isTruthy(val));
	}

	public static void putObjectDirect(long offset, Object target, Object val) {
		UNSAFE.putObject(target, offset, val);
	}

	private static Field getDeclaredFieldRecursive(Class<?> clazz, String name) throws NoSuchFieldException {
		Class<?> curr = clazz;
		while (curr != null && curr != Object.class) {
			try {
				return curr.getDeclaredField(name);
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
	//endregion
}
