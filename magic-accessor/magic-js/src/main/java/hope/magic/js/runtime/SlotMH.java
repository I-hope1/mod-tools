package hope.magic.js.runtime;

import java.lang.invoke.*;

public final class SlotMH {
	private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

	public static final MethodHandle[] MH_GET_SLOT_DOUBLE        = new MethodHandle[8];
	public static final MethodHandle[] MH_SET_SLOT_DOUBLE        = new MethodHandle[8];
	public static final MethodHandle[] MH_GET_SLOT_OBJECT        = new MethodHandle[8];
	public static final MethodHandle[] MH_SET_SLOT_OBJECT        = new MethodHandle[8];
	public static final MethodHandle[] MH_GET_SLOT_PURE_OBJECT   = new MethodHandle[8];
	public static final MethodHandle[] MH_SET_SLOT_PURE_OBJECT   = new MethodHandle[8];
	public static final MethodHandle[] MH_GET_SLOT_DOUBLE_AS_OBJ = new MethodHandle[8];
	public static final MethodHandle[] MH_SET_SLOT_DOUBLE_AS_OBJ = new MethodHandle[8];
	public static final MethodHandle   MH_GET_JS_OBJ_SLOT;
	public static final MethodHandle   MH_SET_JS_OBJ_SLOT;
	public static final MethodHandle   MH_GET_JS_OBJ_SLOT_INT;
	public static final MethodHandle   MH_GET_JS_OBJ_SLOT_DOUBLE;
	public static final MethodHandle   MH_GET_JS_OBJ_SLOT_LONG;
	public static final MethodHandle   MH_SET_JS_OBJ_SLOT_DOUBLE;
	public static final MethodHandle   MH_GET_JS_OBJ_SLOT_DOUBLE_AS_OBJ;
	public static final MethodHandle   MH_SET_JS_OBJ_SLOT_DOUBLE_AS_OBJ;
	public static final MethodHandle   MH_GET_JS_DOUBLE_SLOT_DOUBLE;
	public static final MethodHandle   MH_IS_EXACT_SHAPE_SETTER_DOUBLE;
	public static final MethodHandle   MH_IS_EXACT_SHAPE_SETTER_OBJECT;
	public static final MethodHandle   MH_IS_MATCH_MASK;
	public static final MethodHandle   MH_IS_MATCH_PROP;

	static {
		try {
			MH_GET_JS_OBJ_SLOT = LOOKUP.findStatic(FastAccessor.class, "getJSObjSlot", MethodType.methodType(Object.class, int.class, Object.class));
			MH_SET_JS_OBJ_SLOT = LOOKUP.findStatic(FastAccessor.class, "setJSObjSlot", MethodType.methodType(void.class, int.class, Object.class, Object.class));
			MH_GET_JS_OBJ_SLOT_INT = LOOKUP.findStatic(FastAccessor.class, "getJSObjSlotAsInt", MethodType.methodType(int.class, int.class, Object.class));
			MH_GET_JS_OBJ_SLOT_DOUBLE = LOOKUP.findStatic(FastAccessor.class, "getJSObjSlotAsDouble", MethodType.methodType(double.class, int.class, Object.class));
			MH_GET_JS_OBJ_SLOT_LONG = LOOKUP.findStatic(FastAccessor.class, "getJSObjSlotAsLong", MethodType.methodType(long.class, int.class, Object.class));
			MH_SET_JS_OBJ_SLOT_DOUBLE = LOOKUP.findStatic(FastAccessor.class, "setJSObjSlotDouble", MethodType.methodType(void.class, int.class, Object.class, double.class));
			MH_GET_JS_OBJ_SLOT_DOUBLE_AS_OBJ = LOOKUP.findStatic(FastAccessor.class, "getJSObjSlotDoubleAsObject", MethodType.methodType(Object.class, int.class, Object.class));
			MH_SET_JS_OBJ_SLOT_DOUBLE_AS_OBJ = LOOKUP.findStatic(FastAccessor.class, "setJSObjSlotDoubleAsObject", MethodType.methodType(void.class, int.class, Object.class, Object.class));
			MH_GET_JS_DOUBLE_SLOT_DOUBLE = LOOKUP.findStatic(FastAccessor.class, "getJSDoubleSlotDouble", MethodType.methodType(double.class, int.class, Object.class));
			for (int i = 0; i < 8; i++) {
				MH_GET_SLOT_DOUBLE[i] = LOOKUP.findStatic(FastAccessor.class, "getSlot" + i + "Double", MethodType.methodType(double.class, JSObject.class));
				MH_SET_SLOT_DOUBLE[i] = LOOKUP.findStatic(FastAccessor.class, "setSlot" + i + "Double", MethodType.methodType(void.class, JSObject.class, double.class));
				MH_GET_SLOT_OBJECT[i] = LOOKUP.findStatic(FastAccessor.class, "getSlot" + i + "Object", MethodType.methodType(Object.class, JSObject.class));
				MH_SET_SLOT_OBJECT[i] = LOOKUP.findStatic(FastAccessor.class, "setSlot" + i + "Object", MethodType.methodType(void.class, JSObject.class, Object.class));
				MH_GET_SLOT_PURE_OBJECT[i] = LOOKUP.findStatic(FastAccessor.class, "getSlot" + i + "PureObject", MethodType.methodType(Object.class, JSObject.class));
				MH_SET_SLOT_PURE_OBJECT[i] = LOOKUP.findStatic(FastAccessor.class, "setSlot" + i + "PureObject", MethodType.methodType(void.class, JSObject.class, Object.class));
				MH_GET_SLOT_DOUBLE_AS_OBJ[i] = LOOKUP.findStatic(FastAccessor.class, "getSlot" + i + "DoubleAsObject", MethodType.methodType(Object.class, JSObject.class));
				MH_SET_SLOT_DOUBLE_AS_OBJ[i] = LOOKUP.findStatic(FastAccessor.class, "setSlot" + i + "DoubleAsObject", MethodType.methodType(void.class, JSObject.class, Object.class));
			}

			MH_IS_EXACT_SHAPE_SETTER_DOUBLE = LOOKUP.findStatic(JSLinker.class, "isExactShapeSetterDouble", MethodType.methodType(boolean.class, JSShape.class, Object.class, double.class));
			MH_IS_EXACT_SHAPE_SETTER_OBJECT = LOOKUP.findStatic(JSLinker.class, "isExactShapeSetterObject", MethodType.methodType(boolean.class, JSShape.class, Object.class, Object.class));
			MH_IS_MATCH_MASK = LOOKUP.findStatic(JSLinker.class, "isMatchMask", MethodType.methodType(boolean.class, long.class, Object.class));
			MH_IS_MATCH_PROP = LOOKUP.findStatic(JSLinker.class, "isMatchPropAt", MethodType.methodType(boolean.class, int.class, int.class, Object.class));
		} catch (Throwable e) {
			throw new ExceptionInInitializerError(e);
		}
	}
}
