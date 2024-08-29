package modtools.utils.reflect;


import arc.func.*;
import arc.util.*;
import jdk.internal.misc.Unsafe;
import modtools.IntVars;
import modtools.utils.Tools;

import java.lang.reflect.*;

import static ihope_lib.MyReflect.unsafe;

public class FieldUtils {
	/** 获取字段，并设置override */
	public static @Nullable Field getFieldAccess(Class<?> cls, String name) {
		try {
			return getFieldAccessOrThrow(cls, name);
		} catch (RuntimeException e) {
			return null;
		}
	}
	/**
	 * 获取字段，并设置override
	 * @throws RuntimeException if class has no such field.
	 **/
	public static Field getFieldAccessOrThrow(Class<?> cls, String name) {
		try {
			Field field = cls.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (NoSuchFieldException e) {
			throw new RuntimeException(e);
		}
	}
	/** 查找包括子类的字段 */
	public static @Nullable Field getFieldAccessAll(Class<?> cls, String name) {
		Field field;
		while (cls != null) {
			field = getFieldAccess(cls, name);
			if (field != null) return field;
			cls = cls.getSuperclass();
		}
		return null;
	}


	public static void walkAllConstOf(Class<?> cls, Cons2<Field, ?> cons, Boolf<?> boolf,
	                                  Object object) {
		for (Field field : cls.getDeclaredFields()) {
			if (!Modifier.isStatic(field.getModifiers())) continue;
			Object o = getOrNull(field, object);
			if (boolf.get(Tools.as(o))) cons.get(field, Tools.as(o));
		}
	}
	public static <T> void walkAllConstOf(Class<?> cls, Cons2<Field, T> cons, Class<T> filterClass,
	                                      Object object) {
		walkAllConstOf(cls, cons, filterClass::isInstance, object);
	}
	public static <T> void walkAllConstOf(Class<?> cls, Cons2<Field, T> cons, Class<T> filterClass) {
		walkAllConstOf(cls, cons, filterClass::isInstance, null);
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
			return Modifier.isStatic(field.getModifiers()) ? Tools.as(field.get(null)) : null;
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

	/** 如果字段不存在可能会导致JVM崩溃，谨慎使用 */
	public static long fieldOffset(Class<?> cls, String name) {
		return $OffsetGetter2.impl.fieldOffset(cls, name);
	}
	public static long fieldOffset(Field f) {
		return fieldOffset(Modifier.isStatic(f.getModifiers()), f);
	}
	public static long fieldOffset(boolean isStatic, Field f) {
		return $OffsetGetter.impl.fieldOffset(isStatic, f);
	}

	/**
	 * 不检查对象的合理性，但是不正确的参数会导致<b>JVM崩溃</b>
	 * @param o   field.get(obj); obj不能为null，静态字段时，obj是类.
	 * @param off 不能为负数
	 */
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
		boolean  isStatic = isStatic(f);
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

	public static boolean isStatic(Field f) {
		return Modifier.isStatic(f.getModifiers());
	}
}

interface $OffsetGetter {
	long fieldOffset(boolean isStatic, Field f);

	// @SuppressWarnings("deprecation")
	$OffsetGetter DefaultImpl = (isStatic, f) ->
	 isStatic ? Unsafe.getUnsafe().staticFieldOffset(f) : Unsafe.getUnsafe().objectFieldOffset(f);

	@SuppressWarnings("deprecation")
	$OffsetGetter Java8Impl = (isStatic, f) ->
	 isStatic ? unsafe.staticFieldOffset(f) : unsafe.objectFieldOffset(f);

	$OffsetGetter AndroidImpl = (isStatic, f) -> hope_android.FieldUtils.getFieldOffset(f);

	$OffsetGetter impl = OS.isAndroid ? AndroidImpl :
	 IntVars.javaVersion <= 8 ? Java8Impl : DefaultImpl;
}

interface $OffsetGetter2 {
	long fieldOffset(Class<?> clazz, String fieldName);
	$OffsetGetter2 DesktopImpl = (cls, name) -> Unsafe.getUnsafe().objectFieldOffset(cls, name);
	$OffsetGetter2 AndroidImpl = (cls, name) -> hope_android.FieldUtils.getFieldOffset(FieldUtils.getFieldAccessOrThrow(cls, name));

	$OffsetGetter2 Java8Impl = (cls, name) -> {
		Field field = FieldUtils.getFieldAccess(cls, name);
		assert field != null;
		return $OffsetGetter.impl.fieldOffset(Modifier.isStatic(field.getModifiers()), field);
	};
	$OffsetGetter2 impl      = OS.isAndroid ? AndroidImpl :
	 IntVars.javaVersion <= 8 ? Java8Impl : DesktopImpl;
}