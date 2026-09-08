package hope.magic.js.runtime;

import java.lang.invoke.*;

public final class FieldMH {
	private static final MethodType GET_DIR_TYPE = MethodType.methodType(Object.class, long.class, Object.class);


	public static final MethodHandle
	 GET_INT     = JSLinker.findStaticMH(FastAccessor.class, "getIntDirect", GET_DIR_TYPE),
	 GET_DOUBLE  = JSLinker.findStaticMH(FastAccessor.class, "getDoubleDirect", GET_DIR_TYPE),
	 GET_LONG    = JSLinker.findStaticMH(FastAccessor.class, "getLongDirect", GET_DIR_TYPE),
	 GET_FLOAT   = JSLinker.findStaticMH(FastAccessor.class, "getFloatDirect", GET_DIR_TYPE),
	 GET_SHORT   = JSLinker.findStaticMH(FastAccessor.class, "getShortDirect", GET_DIR_TYPE),
	 GET_BYTE    = JSLinker.findStaticMH(FastAccessor.class, "getByteDirect", GET_DIR_TYPE),
	 GET_CHAR    = JSLinker.findStaticMH(FastAccessor.class, "getCharDirect", GET_DIR_TYPE),
	 GET_BOOLEAN = JSLinker.findStaticMH(FastAccessor.class, "getBooleanDirect", GET_DIR_TYPE),
	 GET_OBJECT  = JSLinker.findStaticMH(FastAccessor.class, "getObjectDirect", GET_DIR_TYPE);

	private static final MethodType PUT_DIR_TYPE = MethodType.methodType(void.class, long.class, Object.class, Object.class);

	public static final MethodHandle
	 PUT_INT     = JSLinker.findStaticMH(FastAccessor.class, "putIntDirect", PUT_DIR_TYPE),
	 PUT_DOUBLE  = JSLinker.findStaticMH(FastAccessor.class, "putDoubleDirect", PUT_DIR_TYPE),
	 PUT_LONG    = JSLinker.findStaticMH(FastAccessor.class, "putLongDirect", PUT_DIR_TYPE),
	 PUT_FLOAT   = JSLinker.findStaticMH(FastAccessor.class, "putFloatDirect", PUT_DIR_TYPE),
	 PUT_SHORT   = JSLinker.findStaticMH(FastAccessor.class, "putShortDirect", PUT_DIR_TYPE),
	 PUT_BYTE    = JSLinker.findStaticMH(FastAccessor.class, "putByteDirect", PUT_DIR_TYPE),
	 PUT_CHAR    = JSLinker.findStaticMH(FastAccessor.class, "putCharDirect", PUT_DIR_TYPE),
	 PUT_BOOLEAN = JSLinker.findStaticMH(FastAccessor.class, "putBooleanDirect", PUT_DIR_TYPE),
	 PUT_OBJECT  = JSLinker.findStaticMH(FastAccessor.class, "putObjectDirect", PUT_DIR_TYPE);

	private static final MethodType PRIM_INT_TYPE = MethodType.methodType(int.class, long.class, Object.class);

	public static final MethodHandle
	 GET_INT_PRIM       = JSLinker.findStaticMH(FastAccessor.class, "getIntDirectPrim", PRIM_INT_TYPE),
	 GET_DOUBLE_AS_INT  = JSLinker.findStaticMH(FastAccessor.class, "getDoubleAsIntPrim", PRIM_INT_TYPE),
	 GET_LONG_AS_INT    = JSLinker.findStaticMH(FastAccessor.class, "getLongAsIntPrim", PRIM_INT_TYPE),
	 GET_FLOAT_AS_INT   = JSLinker.findStaticMH(FastAccessor.class, "getFloatAsIntPrim", PRIM_INT_TYPE),
	 GET_SHORT_AS_INT   = JSLinker.findStaticMH(FastAccessor.class, "getShortAsIntPrim", PRIM_INT_TYPE),
	 GET_BYTE_AS_INT    = JSLinker.findStaticMH(FastAccessor.class, "getByteAsIntPrim", PRIM_INT_TYPE),
	 GET_CHAR_AS_INT    = JSLinker.findStaticMH(FastAccessor.class, "getCharAsIntPrim", PRIM_INT_TYPE),
	 GET_BOOLEAN_AS_INT = JSLinker.findStaticMH(FastAccessor.class, "getBooleanAsIntPrim", PRIM_INT_TYPE),
	 GET_OBJECT_AS_INT  = JSLinker.findStaticMH(FastAccessor.class, "getObjectAsIntPrim", PRIM_INT_TYPE);

	private static final MethodType PRIM_DOUBLE_TYPE = MethodType.methodType(double.class, long.class, Object.class);

	public static final MethodHandle
	 GET_DOUBLE_PRIM       = JSLinker.findStaticMH(FastAccessor.class, "getDoubleDirectPrim", PRIM_DOUBLE_TYPE),
	 GET_INT_AS_DOUBLE     = JSLinker.findStaticMH(FastAccessor.class, "getIntAsDoublePrim", PRIM_DOUBLE_TYPE),
	 GET_LONG_AS_DOUBLE    = JSLinker.findStaticMH(FastAccessor.class, "getLongAsDoublePrim", PRIM_DOUBLE_TYPE),
	 GET_FLOAT_AS_DOUBLE   = JSLinker.findStaticMH(FastAccessor.class, "getFloatAsDoublePrim", PRIM_DOUBLE_TYPE),
	 GET_SHORT_AS_DOUBLE   = JSLinker.findStaticMH(FastAccessor.class, "getShortAsDoublePrim", PRIM_DOUBLE_TYPE),
	 GET_BYTE_AS_DOUBLE    = JSLinker.findStaticMH(FastAccessor.class, "getByteAsDoublePrim", PRIM_DOUBLE_TYPE),
	 GET_CHAR_AS_DOUBLE    = JSLinker.findStaticMH(FastAccessor.class, "getCharAsDoublePrim", PRIM_DOUBLE_TYPE),
	 GET_BOOLEAN_AS_DOUBLE = JSLinker.findStaticMH(FastAccessor.class, "getBooleanAsDoublePrim", PRIM_DOUBLE_TYPE),
	 GET_OBJECT_AS_DOUBLE  = JSLinker.findStaticMH(FastAccessor.class, "getObjectAsDoublePrim", PRIM_DOUBLE_TYPE);

	private static final MethodType PRIM_LONG_TYPE = MethodType.methodType(long.class, long.class, Object.class);

	public static final MethodHandle
	 GET_LONG_PRIM       = JSLinker.findStaticMH(FastAccessor.class, "getLongDirectPrim", PRIM_LONG_TYPE),
	 GET_INT_AS_LONG     = JSLinker.findStaticMH(FastAccessor.class, "getIntAsLongPrim", PRIM_LONG_TYPE),
	 GET_DOUBLE_AS_LONG  = JSLinker.findStaticMH(FastAccessor.class, "getDoubleAsLongPrim", PRIM_LONG_TYPE),
	 GET_FLOAT_AS_LONG   = JSLinker.findStaticMH(FastAccessor.class, "getFloatAsLongPrim", PRIM_LONG_TYPE),
	 GET_SHORT_AS_LONG   = JSLinker.findStaticMH(FastAccessor.class, "getShortAsLongPrim", PRIM_LONG_TYPE),
	 GET_BYTE_AS_LONG    = JSLinker.findStaticMH(FastAccessor.class, "getByteAsLongPrim", PRIM_LONG_TYPE),
	 GET_CHAR_AS_LONG    = JSLinker.findStaticMH(FastAccessor.class, "getCharAsLongPrim", PRIM_LONG_TYPE),
	 GET_BOOLEAN_AS_LONG = JSLinker.findStaticMH(FastAccessor.class, "getBooleanAsLongPrim", PRIM_LONG_TYPE),
	 GET_OBJECT_AS_LONG  = JSLinker.findStaticMH(FastAccessor.class, "getObjectAsLongPrim", PRIM_LONG_TYPE);
}
