package hope.magic.js.runtime;

import java.lang.invoke.*;

public final class SlotMH {
	public static final MethodHandle[] MH_GET_SLOT_DOUBLE = new MethodHandle[8];
	public static final MethodHandle[] MH_SET_SLOT_DOUBLE = new MethodHandle[8];
	public static final MethodHandle[] MH_GET_SLOT_OBJECT = new MethodHandle[8];
	public static final MethodHandle[] MH_SET_SLOT_OBJECT = new MethodHandle[8];
	public static final MethodHandle   MH_GET_JS_OBJ_SLOT;
	public static final MethodHandle   MH_SET_JS_OBJ_SLOT;
	public static final MethodHandle   MH_GET_JS_OBJ_SLOT_INT;
	public static final MethodHandle   MH_GET_JS_OBJ_SLOT_DOUBLE;
	public static final MethodHandle   MH_GET_JS_OBJ_SLOT_LONG;
	public static final MethodHandle   MH_SET_JS_OBJ_SLOT_DOUBLE;
	public static final MethodHandle   MH_IS_EXACT_SHAPE_SETTER_DOUBLE;
	public static final MethodHandle   MH_IS_EXACT_SHAPE_SETTER_OBJECT;
	public static final MethodHandle   MH_IS_MATCH_MASK;
	public static final MethodHandle   MH_IS_MATCH_PROP;

	static {
		try {
			MH_GET_JS_OBJ_SLOT = JSLinker.LOOKUP.findStatic(JSLinker.class, "getJSObjSlot", MethodType.methodType(Object.class, int.class, Object.class));
			MH_SET_JS_OBJ_SLOT = JSLinker.LOOKUP.findStatic(JSLinker.class, "setJSObjSlot", MethodType.methodType(void.class, int.class, Object.class, Object.class));
			MH_GET_JS_OBJ_SLOT_INT = JSLinker.LOOKUP.findStatic(JSLinker.class, "getJSObjSlotAsInt", MethodType.methodType(int.class, int.class, Object.class));
			MH_GET_JS_OBJ_SLOT_DOUBLE = JSLinker.LOOKUP.findStatic(JSLinker.class, "getJSObjSlotAsDouble", MethodType.methodType(double.class, int.class, Object.class));
			MH_GET_JS_OBJ_SLOT_LONG = JSLinker.LOOKUP.findStatic(JSLinker.class, "getJSObjSlotAsLong", MethodType.methodType(long.class, int.class, Object.class));
			MH_SET_JS_OBJ_SLOT_DOUBLE = JSLinker.LOOKUP.findStatic(JSLinker.class, "setJSObjSlotDouble", MethodType.methodType(void.class, int.class, Object.class, double.class));

			for (int i = 0; i < 8; i++) {
				MH_GET_SLOT_DOUBLE[i] = JSLinker.LOOKUP.findStatic(JSLinker.class, "getSlot" + i + "Double", MethodType.methodType(double.class, JSObject.class));
				MH_SET_SLOT_DOUBLE[i] = JSLinker.LOOKUP.findStatic(JSLinker.class, "setSlot" + i + "Double", MethodType.methodType(void.class, JSObject.class, double.class));
				MH_GET_SLOT_OBJECT[i] = JSLinker.LOOKUP.findStatic(JSLinker.class, "getSlot" + i + "Object", MethodType.methodType(Object.class, JSObject.class));
				MH_SET_SLOT_OBJECT[i] = JSLinker.LOOKUP.findStatic(JSLinker.class, "setSlot" + i + "Object", MethodType.methodType(void.class, JSObject.class, Object.class));
			}
			MH_IS_EXACT_SHAPE_SETTER_DOUBLE = JSLinker.LOOKUP.findStatic(JSLinker.class, "isExactShapeSetterDouble", MethodType.methodType(boolean.class, JSShape.class, Object.class, double.class));
			MH_IS_EXACT_SHAPE_SETTER_OBJECT = JSLinker.LOOKUP.findStatic(JSLinker.class, "isExactShapeSetterObject", MethodType.methodType(boolean.class, JSShape.class, Object.class, Object.class));
			MH_IS_MATCH_MASK = JSLinker.LOOKUP.findStatic(JSLinker.class, "isMatchMask", MethodType.methodType(boolean.class, long.class, Object.class));
			MH_IS_MATCH_PROP = JSLinker.LOOKUP.findStatic(JSLinker.class, "isMatchPropAt", MethodType.methodType(boolean.class, int.class, int.class, Object.class));
		} catch (Throwable e) {
			throw new ExceptionInInitializerError(e);
		}
	}
}
