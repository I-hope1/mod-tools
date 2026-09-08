package hope.magic.js.runtime;

import hope.magic.runtime.Magic;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

@SuppressWarnings("JavaExistingMethodCanBeUsed")
public class FastAccessor {
	private static final Unsafe UNSAFE = Magic.unsafe;

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
	// instanceof XXX xx 模式匹配的字节码会多一些
	public static void putIntDirect(long offset, Object target, Object val) {
		UNSAFE.putInt(target, offset, val instanceof Number ? ((Number) val).intValue() : JSOps.toInt(val));
	}
	public static void putDoubleDirect(long offset, Object target, Object val) {
		UNSAFE.putDouble(target, offset, val instanceof Number ? ((Number) val).doubleValue() : JSOps.toDouble(val));
	}
	public static void putLongDirect(long offset, Object target, Object val) {
		UNSAFE.putLong(target, offset, val instanceof Number ? ((Number) val).longValue() : JSOps.toLong(val));
	}
	public static void putFloatDirect(long offset, Object target, Object val) {
		UNSAFE.putFloat(target, offset, val instanceof Number ? ((Number) val).floatValue() : (float) JSOps.toDouble(val));
	}
	public static void putShortDirect(long offset, Object target, Object val) {
		UNSAFE.putShort(target, offset, val instanceof Number ? ((Number) val).shortValue() : (short) JSOps.toInt(val));
	}
	public static void putByteDirect(long offset, Object target, Object val) {
		UNSAFE.putByte(target, offset, val instanceof Number ? ((Number) val).byteValue() : (byte) JSOps.toInt(val));
	}
	public static void putCharDirect(long offset, Object target, Object val) {
		UNSAFE.putChar(target, offset, tc(val));
	}
	public static void putBooleanDirect(long offset, Object target, Object val) {
		UNSAFE.putBoolean(target, offset, JSOps.isTruthy(val));
	}
	public static void putObjectDirect(long offset, Object target, Object val) {
		UNSAFE.putObject(target, offset, val);
	}
	static void setFieldDirect(Object target, Field field, Object value) throws IllegalAccessException {
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
	public static Object getJSObjSlot(int slot, Object target) {
		return ((JSObject) target).getSlot(slot);
	}
	public static Object getJSObjSlotDoubleAsObject(int slot, Object target) {
		return ((JSObject) target).getDoubleSlot(slot);
	}
	public static double getJSDoubleSlotDouble(int slot, Object target) {
		return ((JSObject) target).getDoubleSlot(slot);
	}
	public static void setJSObjSlot(int slot, Object target, Object val) {
		((JSObject) target).setSlot(slot, val);
	}
	public static void setJSObjSlotDouble(int slot, Object target, double val) {
		((JSObject) target).setDoubleSlot(slot, val);
	}
	public static void setJSObjSlotDoubleAsObject(int slot, Object target, Object val) {
		((JSObject) target).setDoubleSlot(slot, JSOps.toDouble(val));
	}
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
	public static Object getSlot0PureObject(JSObject obj) {
		Object val;
		if ((val = obj.obj0) == JSObject.DELETED) return JSUndefined.INSTANCE;
		return val;
	}
	public static Object getSlot1PureObject(JSObject obj) {
		Object val;
		if ((val = obj.obj1) == JSObject.DELETED) return JSUndefined.INSTANCE;
		return val;
	}
	public static Object getSlot2PureObject(JSObject obj) {
		Object val;
		if ((val = obj.obj2) == JSObject.DELETED) return JSUndefined.INSTANCE;
		return val;
	}
	public static Object getSlot3PureObject(JSObject obj) {
		Object val;
		if ((val = obj.obj3) == JSObject.DELETED) return JSUndefined.INSTANCE;
		return val;
	}
	public static Object getSlot4PureObject(JSObject obj) {
		Object val;
		if ((val = obj.obj4) == JSObject.DELETED) return JSUndefined.INSTANCE;
		return val;
	}
	public static Object getSlot5PureObject(JSObject obj) {
		Object val;
		if ((val = obj.obj5) == JSObject.DELETED) return JSUndefined.INSTANCE;
		return val;
	}
	public static Object getSlot6PureObject(JSObject obj) {
		Object val;
		if ((val = obj.obj6) == JSObject.DELETED) return JSUndefined.INSTANCE;
		return val;
	}
	public static Object getSlot7PureObject(JSObject obj) {
		Object val;
		if ((val = obj.obj7) == JSObject.DELETED) return JSUndefined.INSTANCE;
		return val;
	}
	public static void setSlot0PureObject(JSObject obj, Object val) { obj.obj0 = val; }
	public static void setSlot1PureObject(JSObject obj, Object val) { obj.obj1 = val; }
	public static void setSlot2PureObject(JSObject obj, Object val) { obj.obj2 = val; }
	public static void setSlot3PureObject(JSObject obj, Object val) { obj.obj3 = val; }
	public static void setSlot4PureObject(JSObject obj, Object val) { obj.obj4 = val; }
	public static void setSlot5PureObject(JSObject obj, Object val) { obj.obj5 = val; }
	public static void setSlot6PureObject(JSObject obj, Object val) { obj.obj6 = val; }
	public static void setSlot7PureObject(JSObject obj, Object val) { obj.obj7 = val; }
	public static Object getSlot0DoubleAsObject(JSObject obj) {
		return Double.longBitsToDouble(obj.prim0);
	}
	public static Object getSlot1DoubleAsObject(JSObject obj) {
		return Double.longBitsToDouble(obj.prim1);
	}
	public static Object getSlot2DoubleAsObject(JSObject obj) {
		return Double.longBitsToDouble(obj.prim2);
	}
	public static Object getSlot3DoubleAsObject(JSObject obj) {
		return Double.longBitsToDouble(obj.prim3);
	}
	public static Object getSlot4DoubleAsObject(JSObject obj) {
		return Double.longBitsToDouble(obj.prim4);
	}
	public static Object getSlot5DoubleAsObject(JSObject obj) {
		return Double.longBitsToDouble(obj.prim5);
	}
	public static Object getSlot6DoubleAsObject(JSObject obj) {
		return Double.longBitsToDouble(obj.prim6);
	}
	public static Object getSlot7DoubleAsObject(JSObject obj) {
		return Double.longBitsToDouble(obj.prim7);
	}
	public static void setSlot0DoubleAsObject(JSObject target, Object val) {
		target.prim0 = Double.doubleToRawLongBits(JSOps.toDouble(val));
	}
	public static void setSlot1DoubleAsObject(JSObject target, Object val) {
		target.prim1 = Double.doubleToRawLongBits(JSOps.toDouble(val));
	}
	public static void setSlot2DoubleAsObject(JSObject target, Object val) {
		target.prim2 = Double.doubleToRawLongBits(JSOps.toDouble(val));
	}
	public static void setSlot3DoubleAsObject(JSObject target, Object val) {
		target.prim3 = Double.doubleToRawLongBits(JSOps.toDouble(val));
	}
	public static void setSlot4DoubleAsObject(JSObject target, Object val) {
		target.prim4 = Double.doubleToRawLongBits(JSOps.toDouble(val));
	}
	public static void setSlot5DoubleAsObject(JSObject target, Object val) {
		target.prim5 = Double.doubleToRawLongBits(JSOps.toDouble(val));
	}
	public static void setSlot6DoubleAsObject(JSObject target, Object val) {
		target.prim6 = Double.doubleToRawLongBits(JSOps.toDouble(val));
	}
	public static void setSlot7DoubleAsObject(JSObject target, Object val) {
		target.prim7 = Double.doubleToRawLongBits(JSOps.toDouble(val));
	}
	private static Object boxDoubleBits(long bits) {
		return Double.longBitsToDouble(bits); // Double.valueOf(Double.longBitsToDouble(bits))
	}
	public static Object getSlot0Object(JSObject obj) {
		if (((int) obj.doubleFieldMask & 1) != 0) return boxDoubleBits(obj.prim0);
		Object val;
		if ((val = obj.obj0) == JSObject.DELETED) return JSUndefined.INSTANCE;
		return val;
	}
	public static Object getSlot1Object(JSObject obj) {
		if (((int) obj.doubleFieldMask & 2) != 0) return boxDoubleBits(obj.prim1);
		Object val;
		if ((val = obj.obj1) == JSObject.DELETED) return JSUndefined.INSTANCE;
		return val;
	}
	public static Object getSlot2Object(JSObject obj) {
		if (((int) obj.doubleFieldMask & 4) != 0) return boxDoubleBits(obj.prim2);
		Object val;
		if ((val = obj.obj2) == JSObject.DELETED) return JSUndefined.INSTANCE;
		return val;
	}
	public static Object getSlot3Object(JSObject obj) {
		if (((int) obj.doubleFieldMask & 8) != 0) return boxDoubleBits(obj.prim3);
		Object val;
		if ((val = obj.obj3) == JSObject.DELETED) return JSUndefined.INSTANCE;
		return val;
	}
	public static Object getSlot4Object(JSObject obj) {
		if (((int) obj.doubleFieldMask & 16) != 0) return boxDoubleBits(obj.prim4);
		Object val;
		if ((val = obj.obj4) == JSObject.DELETED) return JSUndefined.INSTANCE;
		return val;
	}
	public static Object getSlot5Object(JSObject obj) {
		if (((int) obj.doubleFieldMask & 32) != 0) return boxDoubleBits(obj.prim5);
		Object val;
		if ((val = obj.obj5) == JSObject.DELETED) return JSUndefined.INSTANCE;
		return val;
	}
	public static Object getSlot6Object(JSObject obj) {
		if (((int) obj.doubleFieldMask & 64) != 0) return boxDoubleBits(obj.prim6);
		Object val;
		if ((val = obj.obj6) == JSObject.DELETED) return JSUndefined.INSTANCE;
		return val;
	}
	public static Object getSlot7Object(JSObject obj) {
		if (((int) obj.doubleFieldMask & 128) != 0) return boxDoubleBits(obj.prim7);
		Object val;
		if ((val = obj.obj7) == JSObject.DELETED) return JSUndefined.INSTANCE;
		return val;
	}
	public static void setSlot0Object(JSObject target, Object val) {
		target.doubleFieldMask &= ~1L;
		target.obj0 = val;
	}
	public static void setSlot1Object(JSObject target, Object val) {
		target.doubleFieldMask &= ~2L;
		target.obj1 = val;
	}
	public static void setSlot2Object(JSObject target, Object val) {
		target.doubleFieldMask &= ~4L;
		target.obj2 = val;
	}
	public static void setSlot3Object(JSObject target, Object val) {
		target.doubleFieldMask &= ~8L;
		target.obj3 = val;
	}
	public static void setSlot4Object(JSObject target, Object val) {
		target.doubleFieldMask &= ~16L;
		target.obj4 = val;
	}
	public static void setSlot5Object(JSObject target, Object val) {
		target.doubleFieldMask &= ~32L;
		target.obj5 = val;
	}
	public static void setSlot6Object(JSObject target, Object val) {
		target.doubleFieldMask &= ~64L;
		target.obj6 = val;
	}
	public static void setSlot7Object(JSObject target, Object val) {
		target.doubleFieldMask &= ~128L;
		target.obj7 = val;
	}
	public static int getJSObjSlotAsInt(int slot, Object target) {
		JSObject obj = (JSObject) target;
		if (obj.isDoubleSlot(slot)) {
			return (int) obj.getDoubleSlot(slot); // 单条机器指令直转，0 堆分配！
		}
		return JSOps.toInt(obj.getSlot(slot));
	}
	public static double getJSObjSlotAsDouble(int slot, Object target) {
		JSObject obj = (JSObject) target;
		if (obj.isDoubleSlot(slot)) {
			return obj.getDoubleSlot(slot);
		}
		return JSOps.toDouble(obj.getSlot(slot));
	}
	public static long getJSObjSlotAsLong(int slot, Object target) {
		JSObject obj = (JSObject) target;
		if (obj.isDoubleSlot(slot)) {
			return (long) obj.getDoubleSlot(slot);
		}
		return JSOps.toLong(obj.getSlot(slot));
	}

	public static int getIntDirectPrim(long offset, Object target) { return UNSAFE.getInt(target, offset); }
	public static int getDoubleAsIntPrim(long offset,
	                                     Object target) { return JSOps.toInt(UNSAFE.getDouble(target, offset)); }
	public static int getLongAsIntPrim(long offset, Object target) { return (int) UNSAFE.getLong(target, offset); }
	public static int getFloatAsIntPrim(long offset, Object target) { return (int) UNSAFE.getFloat(target, offset); }
	public static int getShortAsIntPrim(long offset, Object target) { return UNSAFE.getShort(target, offset); }
	public static int getByteAsIntPrim(long offset, Object target) { return UNSAFE.getByte(target, offset); }
	public static int getCharAsIntPrim(long offset, Object target) { return UNSAFE.getChar(target, offset); }
	// 在字节码层面就是 1 个字节（0x00 或 0x01）
	public static int getBooleanAsIntPrim(long offset,
	                                      Object target) { return UNSAFE.getByte(target, offset); }
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
	// 在字节码层面就是 1 个字节（0x00 或 0x01）
	public static double getBooleanAsDoublePrim(long offset,
	                                            Object target) { return (double) UNSAFE.getByte(target, offset); }
	public static double getObjectAsDoublePrim(long offset,
	                                           Object target) { return JSOps.toDouble(UNSAFE.getObject(target, offset)); }
	public static long getLongDirectPrim(long offset, Object target) { return UNSAFE.getLong(target, offset); }
	public static long getIntAsLongPrim(long offset, Object target) { return (long) UNSAFE.getInt(target, offset); }
	public static long getDoubleAsLongPrim(long offset, Object target) { return (long) UNSAFE.getDouble(target, offset); }
	public static long getFloatAsLongPrim(long offset, Object target) { return (long) UNSAFE.getFloat(target, offset); }
	public static long getShortAsLongPrim(long offset, Object target) { return (long) UNSAFE.getShort(target, offset); }
	public static long getByteAsLongPrim(long offset, Object target) { return (long) UNSAFE.getByte(target, offset); }
	public static long getCharAsLongPrim(long offset, Object target) { return (long) UNSAFE.getChar(target, offset); }
	// 在字节码层面就是 1 个字节（0x00 或 0x01）
	public static long getBooleanAsLongPrim(long offset,
	                                        Object target) { return (long) UNSAFE.getByte(target, offset); }
	public static long getObjectAsLongPrim(long offset,
	                                       Object target) { return JSOps.toLong(UNSAFE.getObject(target, offset)); }
}
