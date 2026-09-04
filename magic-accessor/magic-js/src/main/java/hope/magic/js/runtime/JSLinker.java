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
	static final Unsafe               UNSAFE = Magic.unsafe;
	static final MethodHandles.Lookup LOOKUP = Magic.lookup;

	public enum InvocationStrategy {
		MAGIC_ACCESSOR, // 基于 MagicAccessorImpl (MAGICIMPL) 的原生字节码 JIT 直调 (1.95ns)
		SPREADER,       // 基于 MethodHandle.asSpreader 的数组自适应展开 (5.15ns)
		HYBRID          // 混合自适应策略：简单基础参数方法极速轻量展开，复杂接口/SAM回调走平铺字节码 Stub
	}

	public static volatile InvocationStrategy STRATEGY = InvocationStrategy.HYBRID;


	//region 基础类型转换与快路径 MethodHandle 常量 (核心加载)
	public static final MethodHandle   MH_TO_INT;
	public static final MethodHandle   MH_TO_LONG;
	public static final MethodHandle   MH_TO_DOUBLE;
	public static final MethodHandle   MH_TO_FLOAT;
	public static final MethodHandle   MH_TO_SHORT;
	public static final MethodHandle   MH_TO_BYTE;
	public static final MethodHandle   MH_TO_CHAR;
	public static final MethodHandle   MH_TO_BOOLEAN;
	public static final MethodHandle   MH_TO_STRING;
	public static final MethodHandle   MH_TO_INTERFACE;
	public static final MethodHandle   MH_IS_EXACT_CLASS;
	public static final MethodHandle   MH_IS_EXACT_SHAPE;
	public static final MethodHandle   MH_GET_JS_OBJ_SLOT;
	public static final MethodHandle   MH_SET_JS_OBJ_SLOT;
	public static final MethodHandle   MH_GET_JS_OBJ_SLOT_INT;
	public static final MethodHandle   MH_GET_JS_OBJ_SLOT_DOUBLE;
	public static final MethodHandle   MH_GET_JS_OBJ_SLOT_LONG;
	public static final MethodHandle   MH_SET_JS_OBJ_SLOT_DOUBLE;
	public static final MethodHandle[] MH_GET_SLOT_DOUBLE = new MethodHandle[8];
	public static final MethodHandle[] MH_SET_SLOT_DOUBLE = new MethodHandle[8];
	public static final MethodHandle[] MH_GET_SLOT_OBJECT = new MethodHandle[8];
	public static final MethodHandle[] MH_SET_SLOT_OBJECT = new MethodHandle[8];
	public static final MethodHandle   MH_IS_EXACT_SHAPE_SETTER_DOUBLE;
	public static final MethodHandle   MH_IS_EXACT_SHAPE_SETTER_OBJECT;
	public static final MethodHandle   MH_IS_MATCH_MASK;
	public static final MethodHandle   MH_IS_MATCH_MASK_AND_PROP;

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
			MH_SET_JS_OBJ_SLOT_DOUBLE = LOOKUP.findStatic(JSLinker.class, "setJSObjSlotDouble", MethodType.methodType(void.class, int.class, Object.class, double.class));

			for (int i = 0; i < 8; i++) {
				MH_GET_SLOT_DOUBLE[i] = LOOKUP.findStatic(JSLinker.class, "getSlot" + i + "Double", MethodType.methodType(double.class, JSObject.class));
				MH_SET_SLOT_DOUBLE[i] = LOOKUP.findStatic(JSLinker.class, "setSlot" + i + "Double", MethodType.methodType(void.class, JSObject.class, double.class));
				MH_GET_SLOT_OBJECT[i] = LOOKUP.findStatic(JSLinker.class, "getSlot" + i + "Object", MethodType.methodType(Object.class, JSObject.class));
				MH_SET_SLOT_OBJECT[i] = LOOKUP.findStatic(JSLinker.class, "setSlot" + i + "Object", MethodType.methodType(void.class, JSObject.class, Object.class));
			}
			MH_IS_EXACT_SHAPE_SETTER_DOUBLE = LOOKUP.findStatic(JSLinker.class, "isExactShapeSetterDouble", MethodType.methodType(boolean.class, JSShape.class, Object.class, double.class));
			MH_IS_EXACT_SHAPE_SETTER_OBJECT = LOOKUP.findStatic(JSLinker.class, "isExactShapeSetterObject", MethodType.methodType(boolean.class, JSShape.class, Object.class, Object.class));
			MH_IS_MATCH_MASK = LOOKUP.findStatic(JSLinker.class, "isMatchMask", MethodType.methodType(boolean.class, long.class, Object.class));
			MH_IS_MATCH_MASK_AND_PROP = LOOKUP.findStatic(JSLinker.class, "isMatchMaskAndPropAt", MethodType.methodType(boolean.class, long.class, int.class, int.class, Object.class));
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
		public static final MethodHandle
		 GET_GENERIC            = findMH(JSLinker.class, "getPropGeneric", MethodType.methodType(Object.class, Object.class, String.class)),
		 GET_FALLBACK           = findMH(JSLinker.class, "getPropFallback", MethodType.methodType(Object.class, ChainedCallSite.class, Object.class, String.class)),
		 GET_MEGAMORPHIC        = findMH(JSLinker.class, "getPropMegamorphic", MethodType.methodType(Object.class, ChainedCallSite.class, Object.class, String.class)),
		 GET_INT_GENERIC        = findMH(JSLinker.class, "getPropIntGeneric", MethodType.methodType(int.class, Object.class, String.class)),
		 GET_INT_FALLBACK       = findMH(JSLinker.class, "getPropIntFallback", MethodType.methodType(int.class, ChainedCallSite.class, Object.class, String.class)),
		 GET_INT_MEGAMORPHIC    = findMH(JSLinker.class, "getPropIntMegamorphic", MethodType.methodType(int.class, ChainedCallSite.class, Object.class, String.class)),
		 GET_DOUBLE_GENERIC     = findMH(JSLinker.class, "getPropDoubleGeneric", MethodType.methodType(double.class, Object.class, String.class)),
		 GET_DOUBLE_FALLBACK    = findMH(JSLinker.class, "getPropDoubleFallback", MethodType.methodType(double.class, ChainedCallSite.class, Object.class, String.class)),
		 GET_DOUBLE_MEGAMORPHIC = findMH(JSLinker.class, "getPropDoubleMegamorphic", MethodType.methodType(double.class, ChainedCallSite.class, Object.class, String.class)),
		 GET_DOUBLE_SLOT        = findMH(JSLinker.class, "getJSObjDoubleSlot", MethodType.methodType(double.class, int.class, Object.class)),
		 GET_LONG_GENERIC       = findMH(JSLinker.class, "getPropLongGeneric", MethodType.methodType(long.class, Object.class, String.class)),
		 GET_LONG_FALLBACK      = findMH(JSLinker.class, "getPropLongFallback", MethodType.methodType(long.class, ChainedCallSite.class, Object.class, String.class)),
		 GET_LONG_MEGAMORPHIC   = findMH(JSLinker.class, "getPropLongMegamorphic", MethodType.methodType(long.class, ChainedCallSite.class, Object.class, String.class)),
		 SET_GENERIC            = findMH(JSLinker.class, "setPropGeneric", MethodType.methodType(void.class, Object.class, Object.class, String.class)),
		 SET_FALLBACK           = findMH(JSLinker.class, "setPropFallback", MethodType.methodType(void.class, ChainedCallSite.class, Object.class, Object.class, String.class)),
		 SET_MEGAMORPHIC        = findMH(JSLinker.class, "setPropMegamorphic", MethodType.methodType(void.class, ChainedCallSite.class, Object.class, Object.class, String.class)),
		 SET_DOUBLE_GENERIC     = findMH(JSLinker.class, "setPropDoubleGeneric", MethodType.methodType(void.class, Object.class, double.class, String.class)),
		 SET_DOUBLE_FALLBACK    = findMH(JSLinker.class, "setPropDoubleFallback", MethodType.methodType(void.class, ChainedCallSite.class, Object.class, double.class, String.class)),
		 SET_DOUBLE_MEGAMORPHIC = findMH(JSLinker.class, "setPropDoubleMegamorphic", MethodType.methodType(void.class, ChainedCallSite.class, Object.class, double.class, String.class));
	}

	public static final class InvokeMH {
		public static final MethodHandle
		 INVOKE_GENERIC  = findMH(JSLinker.class, "invokeGeneric", MethodType.methodType(Object.class, Object.class, Object[].class, String.class)),
		 INVOKE_FALLBACK = findMH(JSLinker.class, "invokeFallback", MethodType.methodType(Object.class, ChainedCallSite.class, Object.class, Object[].class, String.class)),
		 NEW_GENERIC     = findMH(JSLinker.class, "newGeneric", MethodType.methodType(Object.class, Object.class, Object[].class)),
		 NEW_FALLBACK    = findMH(JSLinker.class, "newFallback", MethodType.methodType(Object.class, ChainedCallSite.class, Object.class, Object[].class));
	}

	public static final class JSFuncMH {
		public static final MethodHandle
		 CALL  = findVirtualMH(JSFunction.class, "call", MethodType.methodType(Object.class, JSContext.class, Object.class, Object[].class)),
		 CALL0 = findVirtualMH(JSFunction.class, "call0", MethodType.methodType(Object.class, JSContext.class, Object.class)),
		 CALL1 = findVirtualMH(JSFunction.class, "call1", MethodType.methodType(Object.class, JSContext.class, Object.class, Object.class)),
		 CALL2 = findVirtualMH(JSFunction.class, "call2", MethodType.methodType(Object.class, JSContext.class, Object.class, Object.class, Object.class)),
		 CALL3 = findVirtualMH(JSFunction.class, "call3", MethodType.methodType(Object.class, JSContext.class, Object.class, Object.class, Object.class, Object.class));
	}

	public static final class OpMH {
		private static final MethodType
		 BIN_TYPE = MethodType.methodType(Object.class, Object.class, Object.class),
		 BIN_DD_D = MethodType.methodType(double.class, double.class, double.class),
		 BIN_II_I = MethodType.methodType(int.class, int.class, int.class),
		 BIN_ID_D = MethodType.methodType(double.class, int.class, double.class),
		 BIN_DI_D = MethodType.methodType(double.class, double.class, int.class);

		private static final MethodType
		 BIN_OD_O = MethodType.methodType(Object.class, Object.class, double.class),
		 BIN_DO_O = MethodType.methodType(Object.class, double.class, Object.class),
		 BIN_OI_O = MethodType.methodType(Object.class, Object.class, int.class),
		 BIN_IO_O = MethodType.methodType(Object.class, int.class, Object.class);

		private static final MethodType
		 BIN_SS_S = MethodType.methodType(String.class, String.class, String.class),
		 BIN_SO_S = MethodType.methodType(String.class, String.class, Object.class),
		 BIN_OS_S = MethodType.methodType(String.class, Object.class, String.class);

		// Generic (Object, Object) -> Object
		public static final MethodHandle
		 ADD       = findMH(JSOps.class, "add", BIN_TYPE),
		 SUB       = findMH(JSOps.class, "sub", BIN_TYPE),
		 MUL       = findMH(JSOps.class, "mul", BIN_TYPE),
		 DIV       = findMH(JSOps.class, "div", BIN_TYPE),
		 MOD       = findMH(JSOps.class, "mod", BIN_TYPE),
		 EQ        = findMH(JSOps.class, "eq", BIN_TYPE),
		 STRICT_EQ = findMH(JSOps.class, "strictEq", BIN_TYPE),
		 NE        = findMH(JSOps.class, "ne", BIN_TYPE),
		 STRICT_NE = findMH(JSOps.class, "strictNe", BIN_TYPE),
		 LT        = findMH(JSOps.class, "lt", BIN_TYPE),
		 LTE       = findMH(JSOps.class, "lte", BIN_TYPE),
		 GT        = findMH(JSOps.class, "gt", BIN_TYPE),
		 GTE       = findMH(JSOps.class, "gte", BIN_TYPE),
		 AND       = findMH(JSOps.class, "and", BIN_TYPE),
		 OR        = findMH(JSOps.class, "or", BIN_TYPE),
		 BIT_AND   = findMH(JSOps.class, "bitAnd", BIN_TYPE),
		 BIT_OR    = findMH(JSOps.class, "bitOr", BIN_TYPE),
		 BIT_XOR   = findMH(JSOps.class, "bitXor", BIN_TYPE),
		 SHL       = findMH(JSOps.class, "shl", BIN_TYPE),
		 SHR       = findMH(JSOps.class, "shr", BIN_TYPE),
		 USHR      = findMH(JSOps.class, "ushr", BIN_TYPE);

		// Primitive & Specialized ADD
		public static final MethodHandle
		 ADD_DD_D = findMH(JSOps.class, "add", BIN_DD_D),
		 ADD_II_I = findMH(JSOps.class, "add", BIN_II_I),
		 ADD_ID_D = findMH(JSOps.class, "add", BIN_ID_D),
		 ADD_DI_D = findMH(JSOps.class, "add", BIN_DI_D),
		 ADD_OD_O = findMH(JSOps.class, "add", BIN_OD_O),
		 ADD_DO_O = findMH(JSOps.class, "add", BIN_DO_O),
		 ADD_OI_O = findMH(JSOps.class, "add", BIN_OI_O),
		 ADD_IO_O = findMH(JSOps.class, "add", BIN_IO_O),
		 ADD_SS_S = findMH(JSOps.class, "add", BIN_SS_S),
		 ADD_SO_S = findMH(JSOps.class, "add", BIN_SO_S),
		 ADD_OS_S = findMH(JSOps.class, "add", BIN_OS_S);

		// Primitive SUB
		public static final MethodHandle
		 SUB_DD_D = findMH(JSOps.class, "sub", BIN_DD_D),
		 SUB_II_I = findMH(JSOps.class, "sub", BIN_II_I),
		 SUB_ID_D = findMH(JSOps.class, "sub", BIN_ID_D),
		 SUB_DI_D = findMH(JSOps.class, "sub", BIN_DI_D),
		 SUB_OD_D = findMH(JSOps.class, "sub", MethodType.methodType(double.class, Object.class, double.class)),
		 SUB_DO_D = findMH(JSOps.class, "sub", MethodType.methodType(double.class, double.class, Object.class));

		// Primitive MUL
		public static final MethodHandle
		 MUL_DD_D = findMH(JSOps.class, "mul", BIN_DD_D),
		 MUL_II_I = findMH(JSOps.class, "mul", BIN_II_I),
		 MUL_ID_D = findMH(JSOps.class, "mul", BIN_ID_D),
		 MUL_DI_D = findMH(JSOps.class, "mul", BIN_DI_D),
		 MUL_OD_D = findMH(JSOps.class, "mul", MethodType.methodType(double.class, Object.class, double.class)),
		 MUL_DO_D = findMH(JSOps.class, "mul", MethodType.methodType(double.class, double.class, Object.class));

		// Primitive DIV
		public static final MethodHandle
		 DIV_DD_D = findMH(JSOps.class, "div", BIN_DD_D),
		 DIV_II_D = findMH(JSOps.class, "div", MethodType.methodType(double.class, int.class, int.class)),
		 DIV_ID_D = findMH(JSOps.class, "div", BIN_ID_D),
		 DIV_DI_D = findMH(JSOps.class, "div", BIN_DI_D),
		 DIV_OD_D = findMH(JSOps.class, "div", MethodType.methodType(double.class, Object.class, double.class)),
		 DIV_DO_D = findMH(JSOps.class, "div", MethodType.methodType(double.class, double.class, Object.class));

		// Primitive MOD
		public static final MethodHandle
		 MOD_DD_D = findMH(JSOps.class, "mod", BIN_DD_D),
		 MOD_II_I = findMH(JSOps.class, "mod", BIN_II_I),
		 MOD_ID_D = findMH(JSOps.class, "mod", BIN_ID_D),
		 MOD_DI_D = findMH(JSOps.class, "mod", BIN_DI_D),
		 MOD_OD_D = findMH(JSOps.class, "mod", MethodType.methodType(double.class, Object.class, double.class)),
		 MOD_DO_D = findMH(JSOps.class, "mod", MethodType.methodType(double.class, double.class, Object.class));

		// Equality Specializations with Primitive
		public static final MethodHandle
		 EQ_OI_Z = findMH(JSOps.class, "isEqInt", MethodType.methodType(boolean.class, Object.class, int.class)),
		 EQ_OD_Z = findMH(JSOps.class, "isEqDouble", MethodType.methodType(boolean.class, Object.class, double.class)),
		 EQ_OB_Z = findMH(JSOps.class, "isEqBool", MethodType.methodType(boolean.class, Object.class, boolean.class)),
		 EQ_OS_Z = findMH(JSOps.class, "isEqString", MethodType.methodType(boolean.class, Object.class, String.class));

		public static final MethodHandle
		 STRICT_EQ_OI_Z = findMH(JSOps.class, "isStrictEqInt", MethodType.methodType(boolean.class, Object.class, int.class)),
		 STRICT_EQ_OD_Z = findMH(JSOps.class, "isStrictEqDouble", MethodType.methodType(boolean.class, Object.class, double.class)),
		 STRICT_EQ_OB_Z = findMH(JSOps.class, "isStrictEqBool", MethodType.methodType(boolean.class, Object.class, boolean.class)),
		 STRICT_EQ_OS_Z = findMH(JSOps.class, "isStrictEqString", MethodType.methodType(boolean.class, Object.class, String.class));
	}

	public static final class IndexMH {
		public static final MethodHandle
		 GET          = findMH(JSLinker.class, "getIndex", MethodType.methodType(Object.class, Object.class, Object.class)),
		 SET          = findMH(JSLinker.class, "setIndex", MethodType.methodType(void.class, Object.class, Object.class, Object.class)),
		 GET_FALLBACK = findMH(JSLinker.class, "getIndexDynamicFallback", MethodType.methodType(Object.class, ChainedCallSite.class, Object.class, Object.class));
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

	//region PolySnapshot & Flat Polymorphic Jump-Table Guard (扁平多态 Switch 守卫)

	/** 多态 IC 快照：shape 数组（插入顺序）+ 每个 shape 对应的槽位 offset + 目标属性 propId。 */
	public record PolySnapshot(JSShape[] shapes, int[] offsets, int propId) {
		public PolySnapshot(JSShape[] shapes, int[] offsets) {
			this(shapes, offsets, -1);
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
		JSShape[] shapes  = snap.shapes();
		int[]     offsets = snap.offsets();
		int       n       = shapes.length;
		if (n == 0) return fallback;

		// ── 优化 ①：小规模多态 (n <= 4) 展开式级联 GWT (纯指针比较，零掩码与归属校验开销) ──
		if (n <= 4) {
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

		// ── 优化 ②：多态/巨态按 Offset 分组聚合位掩码 (Offset-Class Mask Dispatch) ──
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
						int idx = shapes[i].id - minId;
						int off = offsets[i];
						MethodHandle fastGetter = off < 8
						 ? MH_GET_SLOT_OBJECT[off]
						 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, off);

						MethodHandle exactTest     = MH_IS_EXACT_SHAPE.bindTo(shapes[i]);
						MethodHandle guardedGetter = MethodHandles.guardWithTest(exactTest, fastGetter.asType(fallback.type()), fallback);

						targets[idx] = MethodHandles.dropArguments(guardedGetter, 0, int.class);
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
		JSShape[] shapes  = snap.shapes();
		int[]     offsets = snap.offsets();
		int       n       = shapes.length;
		if (n == 0) return fallback;

		// ── 优化 ①：小规模多态 (n <= 4) 展开式级联 GWT (纯指针比较，零掩码与归属校验开销) ──
		if (n <= 4) {
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

		// ── 优化 ②：多态/巨态按 Offset 分组聚合位掩码 (Offset-Class Mask Dispatch) ──
		MethodHandle maskChain = tryBuildOffsetMaskDispatchDouble(shapes, offsets, n, snap.propId(), fallback);
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
						int idx = shapes[i].id - minId;
						int off = offsets[i];
						MethodHandle fastGetter = off < 8
						 ? MH_GET_SLOT_DOUBLE[off]
						 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_DOUBLE, 0, off);

						MethodHandle exactTest     = MH_IS_EXACT_SHAPE.bindTo(shapes[i]);
						MethodHandle guardedGetter = MethodHandles.guardWithTest(exactTest, fastGetter.asType(fallback.type()), fallback);

						targets[idx] = MethodHandles.dropArguments(guardedGetter, 0, int.class);
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
		JSShape[] shapes  = snap.shapes();
		int[]     offsets = snap.offsets();
		int       n       = shapes.length;
		if (n == 0) return fallback;

		// ── 优化 ①：小规模多态 (n <= 4) 展开式级联 GWT (纯指针比较，零掩码与归属校验开销) ──
		if (n <= 4) {
			MethodHandle chain = fallback;
			for (int i = n - 1; i >= 0; i--) {
				int          off        = offsets[i];
				MethodHandle fastGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_INT, 0, off);
				MethodHandle exactTest  = MH_IS_EXACT_SHAPE.bindTo(shapes[i]);
				chain = MethodHandles.guardWithTest(exactTest, fastGetter.asType(fallback.type()), chain);
			}
			return chain;
		}

		// ── 优化 ②：多态/巨态按 Offset 分组聚合位掩码 (Offset-Class Mask Dispatch) ──
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
						int          idx        = shapes[i].id - minId;
						int          off        = offsets[i];
						MethodHandle fastGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_INT, 0, off);

						MethodHandle exactTest     = MH_IS_EXACT_SHAPE.bindTo(shapes[i]);
						MethodHandle guardedGetter = MethodHandles.guardWithTest(exactTest, fastGetter.asType(fallback.type()), fallback);

						targets[idx] = MethodHandles.dropArguments(guardedGetter, 0, int.class);
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
		JSShape[] shapes  = snap.shapes();
		int[]     offsets = snap.offsets();
		int       n       = shapes.length;
		if (n == 0) return fallback;

		// ── 优化 ①：小规模多态 (n <= 4) 展开式级联 GWT (纯指针比较，零掩码与归属校验开销) ──
		if (n <= 4) {
			MethodHandle chain = fallback;
			for (int i = n - 1; i >= 0; i--) {
				int          off        = offsets[i];
				MethodHandle fastGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_LONG, 0, off);
				MethodHandle exactTest  = MH_IS_EXACT_SHAPE.bindTo(shapes[i]);
				chain = MethodHandles.guardWithTest(exactTest, fastGetter.asType(fallback.type()), chain);
			}
			return chain;
		}

		// ── 优化 ②：多态/巨态按 Offset 分组聚合位掩码 (Offset-Class Mask Dispatch) ──
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
						int          idx        = shapes[i].id - minId;
						int          off        = offsets[i];
						MethodHandle fastGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_LONG, 0, off);

						MethodHandle exactTest     = MH_IS_EXACT_SHAPE.bindTo(shapes[i]);
						MethodHandle guardedGetter = MethodHandles.guardWithTest(exactTest, fastGetter.asType(fallback.type()), fallback);

						targets[idx] = MethodHandles.dropArguments(guardedGetter, 0, int.class);
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

	private static MethodHandle tryBuildOffsetMaskDispatchDouble(JSShape[] shapes, int[] offsets, int n, int propId, MethodHandle fallback) {
		if (propId < 0) return null; // 缺少 propId 时无法进行严格属性归属验证，安全回落
		Map<Integer, Long> offsetMaskMap = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) {
			JSShape s = shapes[i];
			if (s.mask == 0L) return null; // 存在 id >= 64 的 Shape，掩码溢出，安全回落
			offsetMaskMap.merge(offsets[i], s.mask, (m1, m2) -> m1 | m2);
		}

		if (offsetMaskMap.size() > 8) return null; // offset 种类过多时回落

		MethodHandle chain = fallback;
		List<Map.Entry<Integer, Long>> entries = new ArrayList<>(offsetMaskMap.entrySet());
		for (int i = entries.size() - 1; i >= 0; i--) {
			int off = entries.get(i).getKey();
			long mask = entries.get(i).getValue();
			MethodHandle fastGetter = off < 8
			 ? MH_GET_SLOT_DOUBLE[off]
			 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_DOUBLE, 0, off);
			MethodHandle test = MethodHandles.insertArguments(MH_IS_MATCH_MASK_AND_PROP, 0, mask, propId, off);
			chain = MethodHandles.guardWithTest(test, fastGetter.asType(fallback.type()), chain);
		}
		return chain;
	}

	private static MethodHandle tryBuildOffsetMaskDispatchObject(JSShape[] shapes, int[] offsets, int n, int propId, MethodHandle fallback) {
		if (propId < 0) return null;
		Map<Integer, Long> offsetMaskMap = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) {
			JSShape s = shapes[i];
			if (s.mask == 0L) return null;
			offsetMaskMap.merge(offsets[i], s.mask, (m1, m2) -> m1 | m2);
		}

		if (offsetMaskMap.size() > 8) return null;

		MethodHandle chain = fallback;
		List<Map.Entry<Integer, Long>> entries = new ArrayList<>(offsetMaskMap.entrySet());
		for (int i = entries.size() - 1; i >= 0; i--) {
			int off = entries.get(i).getKey();
			long mask = entries.get(i).getValue();
			MethodHandle fastGetter = off < 8
			 ? MH_GET_SLOT_OBJECT[off]
			 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, off);
			MethodHandle test = MethodHandles.insertArguments(MH_IS_MATCH_MASK_AND_PROP, 0, mask, propId, off);
			chain = MethodHandles.guardWithTest(test, fastGetter.asType(fallback.type()), chain);
		}
		return chain;
	}

	private static MethodHandle tryBuildOffsetMaskDispatchInt(JSShape[] shapes, int[] offsets, int n, int propId, MethodHandle fallback) {
		if (propId < 0) return null;
		Map<Integer, Long> offsetMaskMap = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) {
			JSShape s = shapes[i];
			if (s.mask == 0L) return null;
			offsetMaskMap.merge(offsets[i], s.mask, (m1, m2) -> m1 | m2);
		}

		if (offsetMaskMap.size() > 8) return null;

		MethodHandle chain = fallback;
		List<Map.Entry<Integer, Long>> entries = new ArrayList<>(offsetMaskMap.entrySet());
		for (int i = entries.size() - 1; i >= 0; i--) {
			int off = entries.get(i).getKey();
			long mask = entries.get(i).getValue();
			MethodHandle fastGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_INT, 0, off);
			MethodHandle test = MethodHandles.insertArguments(MH_IS_MATCH_MASK_AND_PROP, 0, mask, propId, off);
			chain = MethodHandles.guardWithTest(test, fastGetter.asType(fallback.type()), chain);
		}
		return chain;
	}

	private static MethodHandle tryBuildOffsetMaskDispatchLong(JSShape[] shapes, int[] offsets, int n, int propId, MethodHandle fallback) {
		if (propId < 0) return null;
		Map<Integer, Long> offsetMaskMap = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) {
			JSShape s = shapes[i];
			if (s.mask == 0L) return null;
			offsetMaskMap.merge(offsets[i], s.mask, (m1, m2) -> m1 | m2);
		}

		if (offsetMaskMap.size() > 8) return null;

		MethodHandle chain = fallback;
		List<Map.Entry<Integer, Long>> entries = new ArrayList<>(offsetMaskMap.entrySet());
		for (int i = entries.size() - 1; i >= 0; i--) {
			int off = entries.get(i).getKey();
			long mask = entries.get(i).getValue();
			MethodHandle fastGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_LONG, 0, off);
			MethodHandle test = MethodHandles.insertArguments(MH_IS_MATCH_MASK_AND_PROP, 0, mask, propId, off);
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

					MethodHandle exactTest     = MH_IS_EXACT_SHAPE_SETTER_OBJECT.bindTo(shapes[i]);
					MethodHandle guardedSetter = MethodHandles.guardWithTest(exactTest, fastSetter.asType(fallback.type()), fallback);

					targets[idx] = MethodHandles.dropArguments(guardedSetter, 0, int.class);
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

					MethodHandle exactTest     = MH_IS_EXACT_SHAPE_SETTER_DOUBLE.bindTo(shapes[i]);
					MethodHandle guardedSetter = MethodHandles.guardWithTest(exactTest, fastSetter.asType(fallback.type()), fallback);

					targets[idx] = MethodHandles.dropArguments(guardedSetter, 0, int.class);
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
		 findMH(JSLinker.class, "shapeIdSelector", MethodType.methodType(int.class, int.class, int.class, Object.class)),
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

	public static boolean isMatchMask(long expectedMask, Object target) {
		return target instanceof JSObject jsObj && (jsObj.shape.mask & expectedMask) != 0L;
	}

	/**
	 * 安全位掩码分发守卫（包含严格的 Shape 归属验证）：
	 * 1. 快速位掩码初筛（单条 TEST 指令，过滤非本 offset 候选类的绝大部分对象）；
	 * 2. Shape 归属验证：验证该 Shape 在指定 offset 槽位上的属性确为 propId；
	 * 严密防范异构对象因掩码位巧合碰撞导致的未定义属性误读与脏读。
	 */
	public static boolean isMatchMaskAndPropAt(long expectedMask, int propId, int offset, Object target) {
		return target instanceof JSObject jsObj
		 && (jsObj.shape.mask & expectedMask) != 0L
		 && jsObj.shape.hasPropertyAt(propId, offset);
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

	private static MethodHandle buildMultiShapeGuard(List<JSShape> shapes, int propId, int commonOff) {
		int n = shapes.size();
		if (n == 1) return MH_IS_EXACT_SHAPE.bindTo(shapes.get(0));

		// 位掩码多态守卫 (包含严格的属性归属验证)
		long    combinedMask = 0L;
		boolean allHaveMask  = true;
		for (JSShape s : shapes) {
			if (s.mask == 0L) {
				allHaveMask = false;
				break;
			}
			combinedMask |= s.mask;
		}

		if (allHaveMask && combinedMask != 0L && propId >= 0 && commonOff >= 0) {
			return MethodHandles.insertArguments(MH_IS_MATCH_MASK_AND_PROP, 0, combinedMask, propId, commonOff);
		}

		return findMH(JSLinker.class, "isShapeN", MethodType.methodType(boolean.class, JSShape[].class, Object.class)).bindTo(shapes.toArray(new JSShape[0]));
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

	private static MethodHandle buildMultiShapeGuardSetterDouble(List<JSShape> shapes) {
		int n = shapes.size();
		if (n == 1) return MH_IS_EXACT_SHAPE_SETTER_DOUBLE.bindTo(shapes.get(0));
		// Setter 严格沿用精确 Shape 比较，禁止松散位掩码，杜绝类型混淆与原始槽脏写
		return findMH(JSLinker.class, "isShapeNSetterDouble", MethodType.methodType(boolean.class, JSShape[].class, Object.class, double.class)).bindTo(shapes.toArray(new JSShape[0]));
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

	private static MethodHandle buildMultiShapeGuardSetterObject(List<JSShape> shapes) {
		int n = shapes.size();
		if (n == 1) return MH_IS_EXACT_SHAPE_SETTER_OBJECT.bindTo(shapes.get(0));
		// Setter 严格沿用精确 Shape 比较，禁止松散位掩码
		return findMH(JSLinker.class, "isShapeNSetterObject", MethodType.methodType(boolean.class, JSShape[].class, Object.class, Object.class)).bindTo(shapes.toArray(new JSShape[0]));
	}

	/**
	 * 自适应 Fallback 句柄：
	 * 当已观测 Shape 数量 < 64 且 CallSite 未进入超态时，返回 initialFallback（继续捕获新 Shape 并触发动态重新快照/Relink）；
	 * 当 Shape 数量到达 64 阈值后，返回 megamorphicTarget 终结演化。
	 */
	private static MethodHandle getAdaptiveFallback(ChainedCallSite site) {
		return (site.getObservedShapes().size() < 64 && site.getInitialFallback() != null)
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
		String          sym         = SymbolTable.symbol(propName);
		ChainedCallSite site        = new ChainedCallSite(type, null);
		site.setPropId(SymbolTable.id(sym));
		MethodHandle    megamorphic = MethodHandles.insertArguments(PropMH.GET_MEGAMORPHIC, 2, sym).bindTo(site);
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
		String          sym         = SymbolTable.symbol(propName);
		ChainedCallSite site        = new ChainedCallSite(type, null);
		site.setPropId(SymbolTable.id(sym));
		MethodHandle    megamorphic = MethodHandles.insertArguments(PropMH.GET_INT_MEGAMORPHIC, 2, sym).bindTo(site);
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
		String          sym         = SymbolTable.symbol(propName);
		ChainedCallSite site        = new ChainedCallSite(type, null);
		site.setPropId(SymbolTable.id(sym));
		MethodHandle    megamorphic = MethodHandles.insertArguments(PropMH.GET_DOUBLE_MEGAMORPHIC, 2, sym).bindTo(site);
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
		String          sym         = SymbolTable.symbol(propName);
		ChainedCallSite site        = new ChainedCallSite(type, null);
		site.setPropId(SymbolTable.id(sym));
		MethodHandle    megamorphic = MethodHandles.insertArguments(PropMH.GET_LONG_MEGAMORPHIC, 2, sym).bindTo(site);
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
		String          sym         = SymbolTable.symbol(propName);
		ChainedCallSite site        = new ChainedCallSite(type, null);
		site.setPropId(SymbolTable.id(sym));
		MethodHandle    megamorphic = MethodHandles.insertArguments(PropMH.SET_MEGAMORPHIC, 3, sym).bindTo(site);
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
		String          sym         = SymbolTable.symbol(propName);
		ChainedCallSite site        = new ChainedCallSite(type, null);
		site.setPropId(SymbolTable.id(sym));
		MethodHandle    megamorphic = MethodHandles.insertArguments(PropMH.SET_DOUBLE_MEGAMORPHIC, 3, sym).bindTo(site);
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
		return new ConstantCallSite(IndexMH.SET.asType(type));
	}
	//endregion

	//region Fallback 与 Inline Cache 实现

	/** 双重守卫：Shape 相同且 Key 相同（先做引用比较 ==，失败再做 equals） */
	@SuppressWarnings("EqualsReplaceableByObjectsCall")
	public static boolean isExactShapeAndKey(JSShape expectedShape, String expectedKey, Object target, Object key) {
		return target instanceof JSObject jsObj
		       && jsObj.shape == expectedShape
		       && (key == expectedKey || (key != null && key.equals(expectedKey)));
	}

	/** 动态对象索引读取的通用 Fallback 入口 */
	public static Object getIndexDynamicFallback(ChainedCallSite site, Object target, Object index) throws Throwable {
		if (target instanceof JSObject jsObj && index instanceof String strKey) {
			JSShape s      = jsObj.shape;
			int     offset = s.getOffset(strKey);

			// 只有当属性命中且缓存深度 < 3 时挂载 Keyed IC 单态/多态分支
			if (offset >= 0 && site.getChainDepth() < 3) {
				MethodHandle test = LOOKUP.findStatic(
				 JSLinker.class,
				 "isExactShapeAndKey",
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
			}
		}
		// 降级走原有的全量查找
		return getIndex(target, index);
	}

	public static double getJSObjDoubleSlot(int slot, Object target) {
		return ((JSObject) target).getDoubleSlot(slot);
	}

	public static Object getPropMegamorphic(ChainedCallSite site, Object target, String propName) {
		if (target instanceof JSObject jsObj) {
			JSShape s   = jsObj.shape;
			int     idx = ChainedCallSite.cacheIndex(s.id);

			// 64-bit 严格原子读取，防指令重排与 32 位 JVM 字撕裂
			long entry = (long) ChainedCallSite.CACHE_VH.getOpaque(site.directCache, idx);
			if (entry != 0L && (int) (entry >>> 32) == s.id) {
				int    offset = (int) entry;
				Object raw    = jsObj.getRawObjectSlot(offset);
				if (raw != JSObject.DELETED) {
					return jsObj.getSlot(offset);
				}
				return jsObj.get(propName);
			}

			int offset = s.getOffset(propName);
			if (offset >= 0) {
				// 64-bit 原子无锁写入 (高位 shape.id, 低位 offset)
				long newEntry = ((long) s.id << 32) | (offset & 0xFFFFFFFFL);
				ChainedCallSite.CACHE_VH.setOpaque(site.directCache, idx, newEntry);
				Object raw = jsObj.getRawObjectSlot(offset);
				if (raw != JSObject.DELETED) {
					return jsObj.getSlot(offset);
				}
			}
			return jsObj.get(propName);
		}
		if (target == null || target == JSUndefined.INSTANCE) return JSUndefined.INSTANCE;
		return getPropGeneric(target, propName);
	}

	public static double getPropDoubleMegamorphic(ChainedCallSite site, Object target, String propName) {
		if (target instanceof JSObject jsObj) {
			JSShape s   = jsObj.shape;
			int     idx = ChainedCallSite.cacheIndex(s.id);

			// 64-bit 严格原子读取，防指令重排与 32 位 JVM 字撕裂
			long entry = (long) ChainedCallSite.CACHE_VH.getOpaque(site.directCache, idx);
			if (entry != 0L && (int) (entry >>> 32) == s.id) {
				int offset = (int) entry;
				// 必须验证该槽位当前存储的是不是原生 double
				if (jsObj.isDoubleSlot(offset)) {
					return jsObj.getDoubleSlot(offset);
				}
				return jsObj.getAsDouble(propName);
			}

			int offset = s.getOffset(propName);
			if (offset >= 0) {
				ChainedCallSite.CACHE_VH.setOpaque(site.directCache, idx, ((long) s.id << 32) | (offset & 0xFFFFFFFFL));
				if (jsObj.isDoubleSlot(offset)) {
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
			JSShape s   = jsObj.shape;
			int     idx = ChainedCallSite.cacheIndex(s.id);

			// 64-bit 严格原子读取，防指令重排与 32 位 JVM 字撕裂
			long entry = (long) ChainedCallSite.CACHE_VH.getOpaque(site.directCache, idx);
			if (entry != 0L && (int) (entry >>> 32) == s.id) {
				int offset = (int) entry;
				// 必须验证该槽位当前存储的是不是原生 double
				if (jsObj.isDoubleSlot(offset)) {
					return (int) jsObj.getDoubleSlot(offset);
				}
				return (int) jsObj.getAsDouble(propName);
			}

			int offset = s.getOffset(propName);
			if (offset >= 0) {
				ChainedCallSite.CACHE_VH.setOpaque(site.directCache, idx, ((long) s.id << 32) | (offset & 0xFFFFFFFFL));
				if (jsObj.isDoubleSlot(offset)) {
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
			JSShape s   = jsObj.shape;
			int     idx = ChainedCallSite.cacheIndex(s.id);

			// 64-bit 严格原子读取，防指令重排与 32 位 JVM 字撕裂
			long entry = (long) ChainedCallSite.CACHE_VH.getOpaque(site.directCache, idx);
			if (entry != 0L && (int) (entry >>> 32) == s.id) {
				int offset = (int) entry;
				// 必须验证该槽位当前存储的是不是原生 double
				if (jsObj.isDoubleSlot(offset)) {
					return (long) jsObj.getDoubleSlot(offset);
				}
				return (long) jsObj.getAsDouble(propName);
			}

			int offset = s.getOffset(propName);
			if (offset >= 0) {
				ChainedCallSite.CACHE_VH.setOpaque(site.directCache, idx, ((long) s.id << 32) | (offset & 0xFFFFFFFFL));
				if (jsObj.isDoubleSlot(offset)) {
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
			JSShape s   = jsObj.shape;
			int     idx = ChainedCallSite.cacheIndex(s.id);

			// 64-bit 严格原子读取，防指令重排与 32 位 JVM 字撕裂
			long entry = (long) ChainedCallSite.CACHE_VH.getOpaque(site.directCache, idx);
			if (entry != 0L && (int) (entry >>> 32) == s.id) {
				jsObj.setSlot((int) entry, value);
				return;
			}

			int offset = s.getOffset(propName);
			if (offset >= 0) {
				ChainedCallSite.CACHE_VH.setOpaque(site.directCache, idx, ((long) s.id << 32) | (offset & 0xFFFFFFFFL));
				jsObj.setSlot(offset, value);
				return;
			}
			jsObj.put(propName, value);
			return;
		}
		setPropGeneric(target, value, propName);
	}

	public static void setPropDoubleMegamorphic(ChainedCallSite site, Object target, double value, String propName) {
		if (target instanceof JSObject jsObj) {
			JSShape s   = jsObj.shape;
			int     idx = ChainedCallSite.cacheIndex(s.id);

			long entry = (long) ChainedCallSite.CACHE_VH.getOpaque(site.directCache, idx);
			if (entry != 0L && (int) (entry >>> 32) == s.id) {
				jsObj.setDoubleSlot((int) entry, value);
				return;
			}

			int offset = s.getOffset(propName);
			if (offset >= 0) {
				ChainedCallSite.CACHE_VH.setOpaque(site.directCache, idx, ((long) s.id << 32) | (offset & 0xFFFFFFFFL));
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
		if (type == int.class) {
			field.setInt(target, JSOps.toInt(value));
		} else if (type == double.class) {
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
			char c = tc(value);
			field.setChar(target, c);
		} else if (type == boolean.class) {
			field.setBoolean(target, JSOps.isTruthy(value));
		} else {
			field.set(target, value);
		}
	}
	private static char tc(Object value) {
		char c;
		if (value instanceof Character ch) {
			c = ch;
		} else if (value instanceof Number num) {
			c = (char) num.intValue();
		} else if (value != null && !value.toString().isEmpty()) {
			c = value.toString().charAt(0);
		} else {
			c = '\0';
		}
		return c;
	}

	public static Object getPropFallback(ChainedCallSite site, Object target, String propName) {
		if (site.getPropId() < 0) site.setPropId(SymbolTable.id(propName));
		if (target == null || target == JSUndefined.INSTANCE) {
			return JSUndefined.INSTANCE;
		}

		if (target instanceof JSObject jsObj) {
			int offset = jsObj.shape.getOffset(propName);
			if (offset >= 0) {
				byte type = jsObj.shape.getSlotType(offset);
				site.recordShape(jsObj.shape, offset, type);

				if (site.isOffsetEquivalent()) {
					int          commonOff = site.getCommonOffset();
					MethodHandle test      = buildMultiShapeGuard(site.getObservedShapes(), site.getPropId(), commonOff);
					MethodHandle directSlotGetter = (commonOff >= 0 && commonOff < 8)
					 ? MH_GET_SLOT_OBJECT[commonOff]
					 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, commonOff);
					MethodHandle fallbackTarget = getAdaptiveFallback(site);
					site.setTarget(MethodHandles.guardWithTest(test, directSlotGetter.asType(site.type()), fallbackTarget.asType(site.type())));
					return jsObj.getSlot(commonOff);
				}

				// 异槽多态：一旦观测到 >= 2 个异槽 Shape，挂载扁平 switch，避免继续堆叠 guardWithTest 层
				if (site.getObservedShapes().size() >= 2) {
					MethodHandle fb = getAdaptiveFallback(site);
					PolySnapshot snap = site.snapshotPoly();
					site.installFlatPolyGuard(buildFlatPolySwitchObject(snap, fb));
				} else {
					MethodHandle test = MH_IS_EXACT_SHAPE.bindTo(jsObj.shape);
					MethodHandle directSlotGetter = offset < 8
					 ? MH_GET_SLOT_OBJECT[offset]
					 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, offset);
					site.installGuardOrSwitchMegamorphic(test, directSlotGetter);
				}
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

	public static void setPropFallback(ChainedCallSite site, Object target, Object value, String propName) {
		if (site.getPropId() < 0) site.setPropId(SymbolTable.id(propName));
		if (target == null || target == JSUndefined.INSTANCE) return;

		if (target instanceof JSObject jsObj) {
			int offset = jsObj.shape.getOffset(propName);
			if (offset >= 0) {
				byte type = jsObj.shape.getSlotType(offset);
				site.recordShape(jsObj.shape, offset, type);

				if (site.isOffsetEquivalent()) {
					int          commonOff = site.getCommonOffset();
					MethodHandle test      = buildMultiShapeGuardSetterObject(site.getObservedShapes());
					MethodHandle directSlotSetter = (commonOff >= 0 && commonOff < 8)
					 ? MH_SET_SLOT_OBJECT[commonOff]
					 : MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT, 0, commonOff);
					MethodHandle fallbackTarget = site.getMegamorphicTarget() != null ? site.getMegamorphicTarget() : (site.getInitialFallback() != null ? site.getInitialFallback() : site.getTarget());
					site.setTarget(MethodHandles.guardWithTest(test, directSlotSetter.asType(site.type()), fallbackTarget.asType(site.type())));
					jsObj.setSlot(commonOff, value);
					return;
				}

				// 异槽多态：chainDepth >= 2 时挂载扁平 switch
				if (site.getChainDepth() >= 2) {
					MethodHandle fb = site.getMegamorphicTarget() != null ? site.getMegamorphicTarget()
					 : (site.getInitialFallback() != null ? site.getInitialFallback() : site.getTarget());
					site.installFlatPolyGuard(buildFlatPolySwitchSetterObject(site.snapshotPoly(), fb));
				} else {
					MethodHandle test = MH_IS_EXACT_SHAPE_SETTER_OBJECT.bindTo(jsObj.shape);
					MethodHandle directSlotSetter = offset < 8
					 ? MH_SET_SLOT_OBJECT[offset]
					 : MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT, 0, offset);
					site.installGuardOrSwitchMegamorphic(test, directSlotSetter);
				}
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

	public static void setPropDoubleFallback(ChainedCallSite site, Object target, double value, String propName) {
		if (site.getPropId() < 0) site.setPropId(SymbolTable.id(propName));
		if (target == null || target == JSUndefined.INSTANCE) return;

		if (target instanceof JSObject jsObj) {
			int offset = jsObj.shape.getOffset(propName);
			if (offset >= 0) {
				site.recordShape(jsObj.shape, offset, JSShape.TYPE_DOUBLE);

				if (site.isOffsetEquivalent()) {
					int          commonOff = site.getCommonOffset();
					MethodHandle test      = buildMultiShapeGuardSetterDouble(site.getObservedShapes());
					MethodHandle directSlotSetter = (commonOff >= 0 && commonOff < 8)
					 ? MH_SET_SLOT_DOUBLE[commonOff]
					 : MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT_DOUBLE, 0, commonOff);
					MethodHandle fallbackTarget = site.getMegamorphicTarget() != null ? site.getMegamorphicTarget() : (site.getInitialFallback() != null ? site.getInitialFallback() : site.getTarget());
					site.setTarget(MethodHandles.guardWithTest(test, directSlotSetter.asType(site.type()), fallbackTarget.asType(site.type())));
					jsObj.setDoubleSlot(commonOff, value);
					return;
				}

				// 异槽多态：chainDepth >= 2 时挂载扁平 switch
				if (site.getChainDepth() >= 2) {
					MethodHandle fb = site.getMegamorphicTarget() != null ? site.getMegamorphicTarget()
					 : (site.getInitialFallback() != null ? site.getInitialFallback() : site.getTarget());
					site.installFlatPolyGuard(buildFlatPolySwitchSetterDouble(site.snapshotPoly(), fb));
				} else {
					MethodHandle test = MH_IS_EXACT_SHAPE_SETTER_DOUBLE.bindTo(jsObj.shape);
					MethodHandle directSlotSetter = offset < 8
					 ? MH_SET_SLOT_DOUBLE[offset]
					 : MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT_DOUBLE, 0, offset);
					site.installGuardOrSwitchMegamorphic(test, directSlotSetter);
				}
				jsObj.setDoubleSlot(offset, value);
				return;
			}
			jsObj.put(propName, value);
			return;
		}

		if (target instanceof Map) {
			((Map<Object, Object>) target).put(propName, value);
			return;
		}

		setPropDoubleGeneric(target, value, propName);
	}

	public static void setPropDoubleGeneric(Object target, double value, String propName) {
		setPropGeneric(target, value, propName);
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
		if (to.isArray() && from.isArray()) {
			return getInheritanceDistance(from.getComponentType(), to.getComponentType());
		}
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
			int          arity = args.length;
			MethodHandle directMh;
			if (arity == 0) {
				directMh = JSFuncMH.CALL0;
				directMh = MethodHandles.insertArguments(directMh, 1, null, JSUndefined.INSTANCE);
			} else if (arity == 1) {
				directMh = JSFuncMH.CALL1;
				directMh = MethodHandles.insertArguments(directMh, 1, null, JSUndefined.INSTANCE);
			} else if (arity == 2) {
				directMh = JSFuncMH.CALL2;
				directMh = MethodHandles.insertArguments(directMh, 1, null, JSUndefined.INSTANCE);
			} else if (arity == 3) {
				directMh = JSFuncMH.CALL3;
				directMh = MethodHandles.insertArguments(directMh, 1, null, JSUndefined.INSTANCE);
			} else {
				directMh = JSFuncMH.CALL;
				directMh = MethodHandles.insertArguments(directMh, 1, null, JSUndefined.INSTANCE);
				directMh = directMh.asSpreader(Object[].class, arity);
			}

			MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
			if (site.type().parameterCount() > 1) {
				test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
			}
			site.installGuardOrSwitchMegamorphic(test, directMh.asType(site.type()));

			if (arity == 0) return func.call0(null, JSUndefined.INSTANCE);
			if (arity == 1) return func.call1(null, JSUndefined.INSTANCE, args[0]);
			if (arity == 2) return func.call2(null, JSUndefined.INSTANCE, args[0], args[1]);
			if (arity == 3) return func.call3(null, JSUndefined.INSTANCE, args[0], args[1], args[2]);
			return func.call(null, JSUndefined.INSTANCE, args);
		}

		if (target instanceof JSObject jsObj) {
			Object member = jsObj.get(methodName);
			if (member instanceof JSFunction func) {
				int ownOffset = jsObj.shape.getOffset(methodName);
				// 只有当方法不在自身槽位上（offset < 0，即来自原型链）时，函数实例才恒定，方可绑定常量
				if (ownOffset < 0) {
					MethodHandle test = MH_IS_EXACT_SHAPE.bindTo(jsObj.shape);
					if (site.type().parameterCount() > 1) {
						test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
					}
					MethodHandle exactFuncCall = MethodHandles.insertArguments(JSFuncMH.CALL, 1, (Object) null)
					 .bindTo(func)
					 .asCollector(1, Object[].class, args.length)
					 .asType(site.type());
					site.installGuardOrSwitchMegamorphic(test, exactFuncCall);
				}
				// 若为自有闭包属性，则不绑定死常量，保持动态调用
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

	private static final ClassValue<MethodHandle> INTERFACE_FILTER_CACHE = new ClassValue<>() {
		@Override
		protected MethodHandle computeValue(Class<?> type) {
			return MethodHandles.insertArguments(MH_TO_INTERFACE, 0, type);
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
	private static final String[] SMALL_INT_STRINGS = new String[256];

	static {
		for (int i = 0; i < 256; i++) SMALL_INT_STRINGS[i] = String.valueOf(i).intern();
	}

	public static String fastIntToString(int i) {
		if (i >= 0 && i < 256) return SMALL_INT_STRINGS[i];
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
			jsObj.put(fastIntToString(index), value);
			return;
		}
		if (target instanceof Map map) {
			map.put(index, value);
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
		if (target instanceof CharSequence seq) {
			Integer idx = JSArray.toValidJavaArrayIndex(index);
			if (idx != null && idx >= 0 && idx < seq.length()) {
				return String.valueOf(seq.charAt(idx));
			}
			return JSUndefined.INSTANCE;
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
		if (target instanceof Map map) {
			map.put(index, value);
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

	public static void setJSObjSlotDouble(int slot, Object target, double val) {
		((JSObject) target).setDoubleSlot(slot, val);
	}

	public static boolean isExactShapeSetterDouble(JSShape expected, Object target, double val) {
		return target instanceof JSObject && ((JSObject) target).shape == expected;
	}

	public static boolean isExactShapeSetterObject(JSShape expected, Object target, Object val) {
		return target instanceof JSObject && ((JSObject) target).shape == expected;
	}

	public static final long PRIM_0_OFFSET = JSObject.PRIM_FIELD_OFFSETS[0];
	public static final long PRIM_1_OFFSET = JSObject.PRIM_FIELD_OFFSETS[1];
	public static final long PRIM_2_OFFSET = JSObject.PRIM_FIELD_OFFSETS[2];
	public static final long PRIM_3_OFFSET = JSObject.PRIM_FIELD_OFFSETS[3];
	public static final long PRIM_4_OFFSET = JSObject.PRIM_FIELD_OFFSETS[4];
	public static final long PRIM_5_OFFSET = JSObject.PRIM_FIELD_OFFSETS[5];
	public static final long PRIM_6_OFFSET = JSObject.PRIM_FIELD_OFFSETS[6];
	public static final long PRIM_7_OFFSET = JSObject.PRIM_FIELD_OFFSETS[7];

	// ----------------------------------------------------
	// 针对 In-Object Top 8 槽位的单层扁平方法 (内联深度为 1，直接发射单条 vmovsd 汇编指令)
	// ----------------------------------------------------
	public static double getSlot0Double(JSObject target) { return Double.longBitsToDouble(target.prim0); }
	public static double getSlot1Double(JSObject target) { return Double.longBitsToDouble(target.prim1); }
	public static double getSlot2Double(JSObject target) { return Double.longBitsToDouble(target.prim2); }
	public static double getSlot3Double(JSObject target) { return Double.longBitsToDouble(target.prim3); }
	public static double getSlot4Double(JSObject target) { return Double.longBitsToDouble(target.prim4); }
	public static double getSlot5Double(JSObject target) { return Double.longBitsToDouble(target.prim5); }
	public static double getSlot6Double(JSObject target) { return Double.longBitsToDouble(target.prim6); }
	public static double getSlot7Double(JSObject target) { return Double.longBitsToDouble(target.prim7); }

	// 安全性说明：如果该槽位之前存的是 Object，
	// 第一次变 Double 时走的是 setPropDoubleFallback -> jsObj.setDoubleSlot，
	// 在 fallback 里已经执行了 setDoubleMask 和 obj0 = null。因此在缓存命中（Fast Path）的热路径上，
	// 直接裸写 UNSAFE.putDouble 是完全安全的。
	public static void setSlot0Double(JSObject target, double val) { target.prim0 = Double.doubleToRawLongBits(val); }
	public static void setSlot1Double(JSObject target, double val) { target.prim1 = Double.doubleToRawLongBits(val); }
	public static void setSlot2Double(JSObject target, double val) { target.prim2 = Double.doubleToRawLongBits(val); }
	public static void setSlot3Double(JSObject target, double val) { target.prim3 = Double.doubleToRawLongBits(val); }
	public static void setSlot4Double(JSObject target, double val) { target.prim4 = Double.doubleToRawLongBits(val); }
	public static void setSlot5Double(JSObject target, double val) { target.prim5 = Double.doubleToRawLongBits(val); }
	public static void setSlot6Double(JSObject target, double val) { target.prim6 = Double.doubleToRawLongBits(val); }
	public static void setSlot7Double(JSObject target, double val) { target.prim7 = Double.doubleToRawLongBits(val); }

	public static Object getSlot0Object(JSObject obj) {
		if ((obj.doubleFieldMask & 1L) != 0L) return Double.longBitsToDouble(obj.prim0);
		Object val = obj.obj0;
		return val == JSObject.DELETED ? JSUndefined.INSTANCE : val;
	}
	public static Object getSlot1Object(JSObject obj) {
		if ((obj.doubleFieldMask & 2L) != 0L) return Double.longBitsToDouble(obj.prim1);
		Object val = obj.obj1;
		return val == JSObject.DELETED ? JSUndefined.INSTANCE : val;
	}
	public static Object getSlot2Object(JSObject obj) {
		if ((obj.doubleFieldMask & 4L) != 0L) return Double.longBitsToDouble(obj.prim2);
		Object val = obj.obj2;
		return val == JSObject.DELETED ? JSUndefined.INSTANCE : val;
	}
	public static Object getSlot3Object(JSObject obj) {
		if ((obj.doubleFieldMask & 8L) != 0L) return Double.longBitsToDouble(obj.prim3);
		Object val = obj.obj3;
		return val == JSObject.DELETED ? JSUndefined.INSTANCE : val;
	}
	public static Object getSlot4Object(JSObject obj) {
		if ((obj.doubleFieldMask & 16L) != 0L) return Double.longBitsToDouble(obj.prim4);
		Object val = obj.obj4;
		return val == JSObject.DELETED ? JSUndefined.INSTANCE : val;
	}
	public static Object getSlot5Object(JSObject obj) {
		if ((obj.doubleFieldMask & 32L) != 0L) return Double.longBitsToDouble(obj.prim5);
		Object val = obj.obj5;
		return val == JSObject.DELETED ? JSUndefined.INSTANCE : val;
	}
	public static Object getSlot6Object(JSObject obj) {
		if ((obj.doubleFieldMask & 64L) != 0L) return Double.longBitsToDouble(obj.prim6);
		Object val = obj.obj6;
		return val == JSObject.DELETED ? JSUndefined.INSTANCE : val;
	}
	public static Object getSlot7Object(JSObject obj) {
		if ((obj.doubleFieldMask & 128L) != 0L) return Double.longBitsToDouble(obj.prim7);
		Object val = obj.obj7;
		return val == JSObject.DELETED ? JSUndefined.INSTANCE : val;
	}

	public static void setSlot0Object(JSObject target, Object val) { target.setSlot(0, val); }
	public static void setSlot1Object(JSObject target, Object val) { target.setSlot(1, val); }
	public static void setSlot2Object(JSObject target, Object val) { target.setSlot(2, val); }
	public static void setSlot3Object(JSObject target, Object val) { target.setSlot(3, val); }
	public static void setSlot4Object(JSObject target, Object val) { target.setSlot(4, val); }
	public static void setSlot5Object(JSObject target, Object val) { target.setSlot(5, val); }
	public static void setSlot6Object(JSObject target, Object val) { target.setSlot(6, val); }
	public static void setSlot7Object(JSObject target, Object val) { target.setSlot(7, val); }

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

	public static int getJSObjSlotAsInt(int slot, Object target) {
		return JSOps.toInt(((JSObject) target).getSlot(slot));
	}

	public static double getJSObjSlotAsDouble(int slot, Object target) {
		return ((JSObject) target).getDoubleSlot(slot);
	}

	public static long getJSObjSlotAsLong(int slot, Object target) {
		return JSOps.toLong(((JSObject) target).getSlot(slot));
	}

	public static int getPropIntFallback(ChainedCallSite site, Object target, String propName) {
		if (site.getPropId() < 0) site.setPropId(SymbolTable.id(propName));
		if (target == null || target == JSUndefined.INSTANCE) return 0;

		if (target instanceof JSObject jsObj) {
			int offset = jsObj.shape.getOffset(propName);
			if (offset >= 0) {
				byte type = jsObj.shape.getSlotType(offset);
				site.recordShape(jsObj.shape, offset, type);

				if (site.isOffsetEquivalent()) {
					int          commonOff        = site.getCommonOffset();
					MethodHandle test             = buildMultiShapeGuard(site.getObservedShapes(), site.getPropId(), commonOff);
					MethodHandle directSlotGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_INT, 0, commonOff);
					MethodHandle fallbackTarget   = getAdaptiveFallback(site);
					site.setTarget(MethodHandles.guardWithTest(test, directSlotGetter.asType(site.type()), fallbackTarget.asType(site.type())));
					return JSOps.toInt(jsObj.getSlot(commonOff));
				}

				// 异槽多态：一旦观测到 >= 2 个异槽 Shape，挂载扁平 switch，消除 LambdaForm 嵌套深度
				if (site.getObservedShapes().size() >= 2) {
					MethodHandle fb = getAdaptiveFallback(site);
					site.installFlatPolyGuard(buildFlatPolySwitchInt(site.snapshotPoly(), fb));
				} else {
					MethodHandle test             = MH_IS_EXACT_SHAPE.bindTo(jsObj.shape);
					MethodHandle directSlotGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_INT, 0, offset);
					site.installGuardOrSwitchMegamorphic(test, directSlotGetter);
				}
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

	public static double getPropDoubleFallback(ChainedCallSite site, Object target, String propName) {
		if (site.getPropId() < 0) site.setPropId(SymbolTable.id(propName));
		if (target == null || target == JSUndefined.INSTANCE) return Double.NaN;

		// JSObject Fast路径：Shape 守护 + In-Object 裸双精度槽直读
		if (target instanceof JSObject jsObj) {
			int offset = jsObj.shape.getOffset(propName);
			if (offset >= 0) {
				byte type = jsObj.shape.getSlotType(offset);
				site.recordShape(jsObj.shape, offset, type);

				// 根据槽位实际类型选择 Getter (纯 double 走 Unsafe 汇编直读，Object 槽走安全解包)
				MethodHandle directSlotGetter;
				if (type == JSShape.TYPE_DOUBLE && offset < 8) {
					directSlotGetter = MH_GET_SLOT_DOUBLE[offset];
				} else {
					directSlotGetter = MethodHandles.insertArguments(PropMH.GET_DOUBLE_SLOT, 0, offset);
				}

				// A. 同偏移多态坍缩 (Offset-Equivalent Polymorphism)
				if (site.isOffsetEquivalent()) {
					int          commonOff  = site.getCommonOffset();
					byte         commonType = site.getCommonType();
					MethodHandle test       = buildMultiShapeGuard(site.getObservedShapes(), site.getPropId(), commonOff);
					MethodHandle fastGetter = (commonType == JSShape.TYPE_DOUBLE && commonOff < 8)
					 ? MH_GET_SLOT_DOUBLE[commonOff]
					 : MethodHandles.insertArguments(PropMH.GET_DOUBLE_SLOT, 0, commonOff);

					MethodHandle fallbackTarget = getAdaptiveFallback(site);
					site.setTarget(MethodHandles.guardWithTest(test, fastGetter.asType(site.type()), fallbackTarget.asType(site.type())));
					return (commonType == JSShape.TYPE_DOUBLE) ? jsObj.getDoubleSlot(commonOff) : JSOps.toDouble(jsObj.getSlot(commonOff));
				}

				// B. 异槽多态：一旦观测到 >= 2 个异槽 Shape，挂载扁平 Jump-Table / 掩码分发
				if (site.getObservedShapes().size() >= 2) {
					MethodHandle fb = getAdaptiveFallback(site);
					site.installFlatPolyGuard(buildFlatPolySwitchDouble(site.snapshotPoly(), fb));
				} else {
					// C. 单态 / 双态 GWT 链
					MethodHandle test = MH_IS_EXACT_SHAPE.bindTo(jsObj.shape);
					site.installGuardOrSwitchMegamorphic(test, directSlotGetter);
				}

				return (type == JSShape.TYPE_DOUBLE) ? jsObj.getDoubleSlot(offset) : JSOps.toDouble(jsObj.getSlot(offset));
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

		Method getterMethod = MethodResolver.findGetterMethod(targetClass, propName);
		if (getterMethod != null) {
			try {
				MethodHandle mh = Magic.lookup.unreflect(getterMethod);
				// 若 getter 返回类型不是 double，注入自动拓宽/收窄转换 Filter
				if (getterMethod.getReturnType() != double.class) {
					mh = MethodHandles.filterReturnValue(mh, MH_TO_DOUBLE);
				}
				MethodHandle test = MH_IS_EXACT_CLASS.bindTo(targetClass);
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
			int offset = jsObj.shape.getOffset(propName);
			if (offset >= 0) {
				byte type = jsObj.shape.getSlotType(offset);
				site.recordShape(jsObj.shape, offset, type);

				if (site.isOffsetEquivalent()) {
					int          commonOff        = site.getCommonOffset();
					MethodHandle test             = buildMultiShapeGuard(site.getObservedShapes(), site.getPropId(), commonOff);
					MethodHandle directSlotGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_LONG, 0, commonOff);
					MethodHandle fallbackTarget   = getAdaptiveFallback(site);
					site.setTarget(MethodHandles.guardWithTest(test, directSlotGetter.asType(site.type()), fallbackTarget.asType(site.type())));
					return JSOps.toLong(jsObj.getSlot(commonOff));
				}

				// 异槽多态：一旦观测到 >= 2 个异槽 Shape，挂载扁平 switch
				if (site.getObservedShapes().size() >= 2) {
					MethodHandle fb = getAdaptiveFallback(site);
					site.installFlatPolyGuard(buildFlatPolySwitchLong(site.snapshotPoly(), fb));
				} else {
					MethodHandle test             = MH_IS_EXACT_SHAPE.bindTo(jsObj.shape);
					MethodHandle directSlotGetter = MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT_LONG, 0, offset);
					site.installGuardOrSwitchMegamorphic(test, directSlotGetter);
				}
				return JSOps.toLong(jsObj.getSlot(offset));
			}
		}

		if (target.getClass().isArray() && "length".equals(propName)) {
			return Array.getLength(target);
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
			return spreader.invoke(target, args);
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
		char c = tc(val);
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
