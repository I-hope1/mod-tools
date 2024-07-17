package modtools.utils.reflect;

import arc.util.OS;
import jdk.internal.misc.Unsafe;

import java.lang.reflect.*;

import static ihope_lib.MyReflect.unsafe;

public class FieldUtils {
	/* 获取字段，并设置override */
	public static Field getFieldAccess(Class<?> cls, String name) {
		try {
			Field field = cls.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (NoSuchFieldException e) {
			return null;
		}
	}
	/** 查找包括自类的字段 */
	public static Field getFieldAccessAll(Class<?> cls, String name) {
		Field field;
		while (cls != null) {
			field = getFieldAccess(cls, name);
			if (field != null) return field;
			cls = cls.getSuperclass();
		}
		return null;
	}


	public static boolean getBoolean(Object obj, Field field) {
		try {
			return field.getBoolean(obj);
		} catch (IllegalAccessException e) {
			return false;
		}
	}
	public static float getFloat(Object obj, Field field) {
		try {
			return field.getFloat(obj);
		} catch (IllegalAccessException e) {
			return 0;
		}
	}
	public static int getInt(Object obj, Field field) {
		try {
			return field.getInt(obj);
		} catch (IllegalAccessException e) {
			return 0;
		}
	}
	public static <T> T get(Object obj, Field field) {
		try {
			return (T) field.get(obj);
		} catch (IllegalAccessException e) {
			return null;
		}
	}


	/** @return {@code null} if field isn't static or getting an exception. */
	public static <T> T getOrNull(Field field) {
		try {
			return Modifier.isStatic(field.getModifiers()) ? (T) field.get(null) : null;
		} catch (Throwable ignored) {
			return null;
		}
	}
	/** @return {@code null} if getting an exception. */
	public static <T> T getOrNull(Field field, Object o) {
		try {
			return (T) field.get(o);
		} catch (Throwable ignored) {
			return null;
		}
	}

	public static long fieldOffset(Class<?> cls, String name) {
		// 尝试使用jdk中的unsafe获取offset
		try { return Unsafe.getUnsafe().objectFieldOffset(cls, name); } catch (Throwable ignored) { }

		try {
			return fieldOffset(cls.getDeclaredField(name));
		} catch (NoSuchFieldException e) {
			throw new RuntimeException(e);
		}
	}
	public static long fieldOffset(Field f) {
		return fieldOffset(Modifier.isStatic(f.getModifiers()), f);
	}
	private interface OffsetGetter {
		long fieldOffset(boolean isStatic, Field f);
		@SuppressWarnings("deprecation")
		class DefaultImpl implements OffsetGetter {
			public long fieldOffset(boolean isStatic, Field f) {
				return isStatic ? unsafe.staticFieldOffset(f) : unsafe.objectFieldOffset(f);
			}
		}
		class AndroidImpl implements OffsetGetter {
			public long fieldOffset(boolean isStatic, Field f) {
				return hope_android.FieldUtils.getFieldOffset(f);
			}
		}
		OffsetGetter impl = OS.isAndroid ? new AndroidImpl() : new DefaultImpl();
	}
	public static long fieldOffset(boolean isStatic, Field f) {
		return OffsetGetter.impl.fieldOffset(isStatic, f);
	}
	public static Object getFieldValue(Object o, long off, Class<?> type) {
		if (int.class.equals(type)) {
			return unsafe.getInt(o, off);
		} else if (float.class.equals(type)) {
			return unsafe.getFloat(o, off);
		} else if (double.class.equals(type)) {
			return unsafe.getDouble(o, off);
		} else if (long.class.equals(type)) {
			return unsafe.getLong(o, off);
		} else if (char.class.equals(type)) {
			return unsafe.getChar(o, off);
		} else if (byte.class.equals(type)) {
			return unsafe.getByte(o, off);
		} else if (short.class.equals(type)) {
			return unsafe.getShort(o, off);
		} else if (boolean.class.equals(type)) {
			return unsafe.getBoolean(o, off);
		} else {
			return unsafe.getObject(o, off);
		}
	}
	public static void setValue(Field f, Object obj, Object value) {
		Class<?> type     = f.getType();
		boolean  isStatic = Modifier.isStatic(f.getModifiers());
		Object   o        = isStatic ? f.getDeclaringClass() : obj;
		long     offset   = fieldOffset(isStatic, f);
		setValue(o, offset, value, type);
	}
	public static void setValue(Object o, long off, Object value, Class<?> valType) {
		if (int.class.equals(valType)) {
			unsafe.putInt(o, off, ((Number) value).intValue());
		} else if (float.class.equals(valType)) {
			unsafe.putFloat(o, off, ((Number) value).floatValue());
		} else if (double.class.equals(valType)) {
			unsafe.putDouble(o, off, ((Number) value).doubleValue());
		} else if (long.class.equals(valType)) {
			unsafe.putLong(o, off, ((Number) value).longValue());
		} else if (char.class.equals(valType)) {
			unsafe.putChar(o, off, (char) value);
		} else if (byte.class.equals(valType)) {
			unsafe.putByte(o, off, ((Number) value).byteValue());
		} else if (short.class.equals(valType)) {
			unsafe.putShort(o, off, ((Number) value).shortValue());
		} else if (boolean.class.equals(valType)) {
			unsafe.putBoolean(o, off, (boolean) value);
		} else {
			unsafe.putObject(o, off, value);
			/*if (f.getType().isArray()) {
				o = Arrays.copyOf((Object[]) o, Array.getLength(o));
			}*/
		}
	}
	public static void setValue(Object obj, Class<?> cls, String name, Object val,
	                            Class<?> valType) {
		setValue(obj, fieldOffset(cls, name), val, valType);
	}


	public static boolean setBoolean(Object o, Field field, boolean val) {
		try {
			field.setBoolean(o, val);
			return true;
		} catch (IllegalAccessException ignored) { }
		return false;
	}
	/** for compiler */
	public static boolean set$$(Class<?> cls, Object obj, String fieldName, Object value) {
		/* nothing to do */
		return false;
	}
	public static boolean isStatic(Field f) {
		return Modifier.isStatic(f.getModifiers());
	}
}
