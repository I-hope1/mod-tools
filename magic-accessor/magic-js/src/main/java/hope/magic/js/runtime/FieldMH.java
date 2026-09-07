package hope.magic.js.runtime;

import java.lang.invoke.*;

public final class FieldMH {
	private static final MethodType GET_DIR_TYPE = MethodType.methodType(Object.class, long.class, Object.class);

	public static final MethodHandle
	 GET_INT     = JSLinker.findStaticMH(JSLinker.class, "getIntDirect", GET_DIR_TYPE),
	 GET_DOUBLE  = JSLinker.findStaticMH(JSLinker.class, "getDoubleDirect", GET_DIR_TYPE),
	 GET_LONG    = JSLinker.findStaticMH(JSLinker.class, "getLongDirect", GET_DIR_TYPE),
	 GET_FLOAT   = JSLinker.findStaticMH(JSLinker.class, "getFloatDirect", GET_DIR_TYPE),
	 GET_SHORT   = JSLinker.findStaticMH(JSLinker.class, "getShortDirect", GET_DIR_TYPE),
	 GET_BYTE    = JSLinker.findStaticMH(JSLinker.class, "getByteDirect", GET_DIR_TYPE),
	 GET_CHAR    = JSLinker.findStaticMH(JSLinker.class, "getCharDirect", GET_DIR_TYPE),
	 GET_BOOLEAN = JSLinker.findStaticMH(JSLinker.class, "getBooleanDirect", GET_DIR_TYPE),
	 GET_OBJECT  = JSLinker.findStaticMH(JSLinker.class, "getObjectDirect", GET_DIR_TYPE);

	private static final MethodType PUT_DIR_TYPE = MethodType.methodType(void.class, long.class, Object.class, Object.class);

	public static final MethodHandle
	 PUT_INT     = JSLinker.findStaticMH(JSLinker.class, "putIntDirect", PUT_DIR_TYPE),
	 PUT_DOUBLE  = JSLinker.findStaticMH(JSLinker.class, "putDoubleDirect", PUT_DIR_TYPE),
	 PUT_LONG    = JSLinker.findStaticMH(JSLinker.class, "putLongDirect", PUT_DIR_TYPE),
	 PUT_FLOAT   = JSLinker.findStaticMH(JSLinker.class, "putFloatDirect", PUT_DIR_TYPE),
	 PUT_SHORT   = JSLinker.findStaticMH(JSLinker.class, "putShortDirect", PUT_DIR_TYPE),
	 PUT_BYTE    = JSLinker.findStaticMH(JSLinker.class, "putByteDirect", PUT_DIR_TYPE),
	 PUT_CHAR    = JSLinker.findStaticMH(JSLinker.class, "putCharDirect", PUT_DIR_TYPE),
	 PUT_BOOLEAN = JSLinker.findStaticMH(JSLinker.class, "putBooleanDirect", PUT_DIR_TYPE),
	 PUT_OBJECT  = JSLinker.findStaticMH(JSLinker.class, "putObjectDirect", PUT_DIR_TYPE);

	private static final MethodType PRIM_INT_TYPE = MethodType.methodType(int.class, long.class, Object.class);

	public static final MethodHandle
	 GET_INT_PRIM       = JSLinker.findStaticMH(JSLinker.class, "getIntDirectPrim", PRIM_INT_TYPE),
	 GET_DOUBLE_AS_INT  = JSLinker.findStaticMH(JSLinker.class, "getDoubleAsIntPrim", PRIM_INT_TYPE),
	 GET_LONG_AS_INT    = JSLinker.findStaticMH(JSLinker.class, "getLongAsIntPrim", PRIM_INT_TYPE),
	 GET_FLOAT_AS_INT   = JSLinker.findStaticMH(JSLinker.class, "getFloatAsIntPrim", PRIM_INT_TYPE),
	 GET_SHORT_AS_INT   = JSLinker.findStaticMH(JSLinker.class, "getShortAsIntPrim", PRIM_INT_TYPE),
	 GET_BYTE_AS_INT    = JSLinker.findStaticMH(JSLinker.class, "getByteAsIntPrim", PRIM_INT_TYPE),
	 GET_CHAR_AS_INT    = JSLinker.findStaticMH(JSLinker.class, "getCharAsIntPrim", PRIM_INT_TYPE),
	 GET_BOOLEAN_AS_INT = JSLinker.findStaticMH(JSLinker.class, "getBooleanAsIntPrim", PRIM_INT_TYPE),
	 GET_OBJECT_AS_INT  = JSLinker.findStaticMH(JSLinker.class, "getObjectAsIntPrim", PRIM_INT_TYPE);

	private static final MethodType PRIM_DOUBLE_TYPE = MethodType.methodType(double.class, long.class, Object.class);

	public static final MethodHandle
	 GET_DOUBLE_PRIM       = JSLinker.findStaticMH(JSLinker.class, "getDoubleDirectPrim", PRIM_DOUBLE_TYPE),
	 GET_INT_AS_DOUBLE     = JSLinker.findStaticMH(JSLinker.class, "getIntAsDoublePrim", PRIM_DOUBLE_TYPE),
	 GET_LONG_AS_DOUBLE    = JSLinker.findStaticMH(JSLinker.class, "getLongAsDoublePrim", PRIM_DOUBLE_TYPE),
	 GET_FLOAT_AS_DOUBLE   = JSLinker.findStaticMH(JSLinker.class, "getFloatAsDoublePrim", PRIM_DOUBLE_TYPE),
	 GET_SHORT_AS_DOUBLE   = JSLinker.findStaticMH(JSLinker.class, "getShortAsDoublePrim", PRIM_DOUBLE_TYPE),
	 GET_BYTE_AS_DOUBLE    = JSLinker.findStaticMH(JSLinker.class, "getByteAsDoublePrim", PRIM_DOUBLE_TYPE),
	 GET_CHAR_AS_DOUBLE    = JSLinker.findStaticMH(JSLinker.class, "getCharAsDoublePrim", PRIM_DOUBLE_TYPE),
	 GET_BOOLEAN_AS_DOUBLE = JSLinker.findStaticMH(JSLinker.class, "getBooleanAsDoublePrim", PRIM_DOUBLE_TYPE),
	 GET_OBJECT_AS_DOUBLE  = JSLinker.findStaticMH(JSLinker.class, "getObjectAsDoublePrim", PRIM_DOUBLE_TYPE);

	private static final MethodType PRIM_LONG_TYPE = MethodType.methodType(long.class, long.class, Object.class);

	public static final MethodHandle
	 GET_LONG_PRIM       = JSLinker.findStaticMH(JSLinker.class, "getLongDirectPrim", PRIM_LONG_TYPE),
	 GET_INT_AS_LONG     = JSLinker.findStaticMH(JSLinker.class, "getIntAsLongPrim", PRIM_LONG_TYPE),
	 GET_DOUBLE_AS_LONG  = JSLinker.findStaticMH(JSLinker.class, "getDoubleAsLongPrim", PRIM_LONG_TYPE),
	 GET_FLOAT_AS_LONG   = JSLinker.findStaticMH(JSLinker.class, "getFloatAsLongPrim", PRIM_LONG_TYPE),
	 GET_SHORT_AS_LONG   = JSLinker.findStaticMH(JSLinker.class, "getShortAsLongPrim", PRIM_LONG_TYPE),
	 GET_BYTE_AS_LONG    = JSLinker.findStaticMH(JSLinker.class, "getByteAsLongPrim", PRIM_LONG_TYPE),
	 GET_CHAR_AS_LONG    = JSLinker.findStaticMH(JSLinker.class, "getCharAsLongPrim", PRIM_LONG_TYPE),
	 GET_BOOLEAN_AS_LONG = JSLinker.findStaticMH(JSLinker.class, "getBooleanAsLongPrim", PRIM_LONG_TYPE),
	 GET_OBJECT_AS_LONG  = JSLinker.findStaticMH(JSLinker.class, "getObjectAsLongPrim", PRIM_LONG_TYPE);
}
