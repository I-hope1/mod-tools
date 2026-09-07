package hope.magic.js.runtime;

import java.lang.invoke.*;

public final class OpMH {
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
	 ADD       = JSLinker.findStaticMH(JSOps.class, "add", BIN_TYPE),
	 SUB       = JSLinker.findStaticMH(JSOps.class, "sub", BIN_TYPE),
	 MUL       = JSLinker.findStaticMH(JSOps.class, "mul", BIN_TYPE),
	 DIV       = JSLinker.findStaticMH(JSOps.class, "div", BIN_TYPE),
	 MOD       = JSLinker.findStaticMH(JSOps.class, "mod", BIN_TYPE),
	 EQ        = JSLinker.findStaticMH(JSOps.class, "eq", BIN_TYPE),
	 STRICT_EQ = JSLinker.findStaticMH(JSOps.class, "strictEq", BIN_TYPE),
	 NE        = JSLinker.findStaticMH(JSOps.class, "ne", BIN_TYPE),
	 STRICT_NE = JSLinker.findStaticMH(JSOps.class, "strictNe", BIN_TYPE),
	 LT        = JSLinker.findStaticMH(JSOps.class, "lt", BIN_TYPE),
	 LTE       = JSLinker.findStaticMH(JSOps.class, "lte", BIN_TYPE),
	 GT        = JSLinker.findStaticMH(JSOps.class, "gt", BIN_TYPE),
	 GTE       = JSLinker.findStaticMH(JSOps.class, "gte", BIN_TYPE),
	 AND       = JSLinker.findStaticMH(JSOps.class, "and", BIN_TYPE),
	 OR        = JSLinker.findStaticMH(JSOps.class, "or", BIN_TYPE),
	 BIT_AND   = JSLinker.findStaticMH(JSOps.class, "bitAnd", BIN_TYPE),
	 BIT_OR    = JSLinker.findStaticMH(JSOps.class, "bitOr", BIN_TYPE),
	 BIT_XOR   = JSLinker.findStaticMH(JSOps.class, "bitXor", BIN_TYPE),
	 SHL       = JSLinker.findStaticMH(JSOps.class, "shl", BIN_TYPE),
	 SHR       = JSLinker.findStaticMH(JSOps.class, "shr", BIN_TYPE),
	 USHR      = JSLinker.findStaticMH(JSOps.class, "ushr", BIN_TYPE);

	// Primitive & Specialized ADD
	public static final MethodHandle
	 ADD_DD_D = JSLinker.findStaticMH(JSOps.class, "add", BIN_DD_D),
	 ADD_II_I = JSLinker.findStaticMH(JSOps.class, "add", BIN_II_I),
	 ADD_ID_D = JSLinker.findStaticMH(JSOps.class, "add", BIN_ID_D),
	 ADD_DI_D = JSLinker.findStaticMH(JSOps.class, "add", BIN_DI_D),
	 ADD_OD_O = JSLinker.findStaticMH(JSOps.class, "add", BIN_OD_O),
	 ADD_DO_O = JSLinker.findStaticMH(JSOps.class, "add", BIN_DO_O),
	 ADD_OI_O = JSLinker.findStaticMH(JSOps.class, "add", BIN_OI_O),
	 ADD_IO_O = JSLinker.findStaticMH(JSOps.class, "add", BIN_IO_O),
	 ADD_SS_S = JSLinker.findStaticMH(JSOps.class, "add", BIN_SS_S),
	 ADD_SO_S = JSLinker.findStaticMH(JSOps.class, "add", BIN_SO_S),
	 ADD_OS_S = JSLinker.findStaticMH(JSOps.class, "add", BIN_OS_S);

	// Primitive SUB
	public static final MethodHandle
	 SUB_DD_D = JSLinker.findStaticMH(JSOps.class, "sub", BIN_DD_D),
	 SUB_II_I = JSLinker.findStaticMH(JSOps.class, "sub", BIN_II_I),
	 SUB_ID_D = JSLinker.findStaticMH(JSOps.class, "sub", BIN_ID_D),
	 SUB_DI_D = JSLinker.findStaticMH(JSOps.class, "sub", BIN_DI_D),
	 SUB_OD_D = JSLinker.findStaticMH(JSOps.class, "sub", MethodType.methodType(double.class, Object.class, double.class)),
	 SUB_DO_D = JSLinker.findStaticMH(JSOps.class, "sub", MethodType.methodType(double.class, double.class, Object.class));

	// Primitive MUL
	public static final MethodHandle
	 MUL_DD_D = JSLinker.findStaticMH(JSOps.class, "mul", BIN_DD_D),
	 MUL_II_I = JSLinker.findStaticMH(JSOps.class, "mul", BIN_II_I),
	 MUL_ID_D = JSLinker.findStaticMH(JSOps.class, "mul", BIN_ID_D),
	 MUL_DI_D = JSLinker.findStaticMH(JSOps.class, "mul", BIN_DI_D),
	 MUL_OD_D = JSLinker.findStaticMH(JSOps.class, "mul", MethodType.methodType(double.class, Object.class, double.class)),
	 MUL_DO_D = JSLinker.findStaticMH(JSOps.class, "mul", MethodType.methodType(double.class, double.class, Object.class));

	// Primitive DIV
	public static final MethodHandle
	 DIV_DD_D = JSLinker.findStaticMH(JSOps.class, "div", BIN_DD_D),
	 DIV_II_D = JSLinker.findStaticMH(JSOps.class, "div", MethodType.methodType(double.class, int.class, int.class)),
	 DIV_ID_D = JSLinker.findStaticMH(JSOps.class, "div", BIN_ID_D),
	 DIV_DI_D = JSLinker.findStaticMH(JSOps.class, "div", BIN_DI_D),
	 DIV_OD_D = JSLinker.findStaticMH(JSOps.class, "div", MethodType.methodType(double.class, Object.class, double.class)),
	 DIV_DO_D = JSLinker.findStaticMH(JSOps.class, "div", MethodType.methodType(double.class, double.class, Object.class));

	// Primitive MOD
	public static final MethodHandle
	 MOD_DD_D = JSLinker.findStaticMH(JSOps.class, "mod", BIN_DD_D),
	 MOD_II_I = JSLinker.findStaticMH(JSOps.class, "mod", BIN_II_I),
	 MOD_ID_D = JSLinker.findStaticMH(JSOps.class, "mod", BIN_ID_D),
	 MOD_DI_D = JSLinker.findStaticMH(JSOps.class, "mod", BIN_DI_D),
	 MOD_OD_D = JSLinker.findStaticMH(JSOps.class, "mod", MethodType.methodType(double.class, Object.class, double.class)),
	 MOD_DO_D = JSLinker.findStaticMH(JSOps.class, "mod", MethodType.methodType(double.class, double.class, Object.class));

	// Equality Specializations with Primitive
	public static final MethodHandle
	 EQ_OI_Z = JSLinker.findStaticMH(JSOps.class, "isEqInt", MethodType.methodType(boolean.class, Object.class, int.class)),
	 EQ_OD_Z = JSLinker.findStaticMH(JSOps.class, "isEqDouble", MethodType.methodType(boolean.class, Object.class, double.class)),
	 EQ_OB_Z = JSLinker.findStaticMH(JSOps.class, "isEqBool", MethodType.methodType(boolean.class, Object.class, boolean.class)),
	 EQ_OS_Z = JSLinker.findStaticMH(JSOps.class, "isEqString", MethodType.methodType(boolean.class, Object.class, String.class));

	public static final MethodHandle
	 STRICT_EQ_OI_Z = JSLinker.findStaticMH(JSOps.class, "isStrictEqInt", MethodType.methodType(boolean.class, Object.class, int.class)),
	 STRICT_EQ_OD_Z = JSLinker.findStaticMH(JSOps.class, "isStrictEqDouble", MethodType.methodType(boolean.class, Object.class, double.class)),
	 STRICT_EQ_OB_Z = JSLinker.findStaticMH(JSOps.class, "isStrictEqBool", MethodType.methodType(boolean.class, Object.class, boolean.class)),
	 STRICT_EQ_OS_Z = JSLinker.findStaticMH(JSOps.class, "isStrictEqString", MethodType.methodType(boolean.class, Object.class, String.class));
}
