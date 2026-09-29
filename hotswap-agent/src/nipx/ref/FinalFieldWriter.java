package nipx.ref;

import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * Final field writer
 * <p>用于处理final字段的写入操作：实例字段用 {@code putXxx}，静态字段用 {@code putStaticXxx}</p>
 */
@SuppressWarnings("removal")
public final class FinalFieldWriter {
	private static final Unsafe U; // 反射拿 theUnsafe

	static {
		try {
			Field f = Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			U = (Unsafe) f.get(null);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}


	public static void putObject(Object o, Object v, Class<?> c, String name) {
		U.putObjectVolatile(o, offset(c, name), v);
	}
	public static void putInt(Object o, int v, Class<?> c, String name) {
		U.putIntVolatile(o, offset(c, name), v);
	}
	public static void putLong(Object o, long v, Class<?> c, String name) {
		U.putLongVolatile(o, offset(c, name), v);
	}
	public static void putFloat(Object o, float v, Class<?> c, String name) {
		U.putFloatVolatile(o, offset(c, name), v);
	}
	public static void putDouble(Object o, double v, Class<?> c, String name) {
		U.putDoubleVolatile(o, offset(c, name), v);
	}
	public static void putBoolean(Object o, boolean v, Class<?> c, String name) {
		U.putBooleanVolatile(o, offset(c, name), v);
	}
	public static void putByte(Object o, byte v, Class<?> c, String name) {
		U.putByteVolatile(o, offset(c, name), v);
	}
	public static void putChar(Object o, char v, Class<?> c, String name) {
		U.putCharVolatile(o, offset(c, name), v);
	}
	public static void putShort(Object o, short v, Class<?> c, String name) {
		U.putShortVolatile(o, offset(c, name), v);
	}

	// ==================== 静态字段 ====================
	// 静态字段没有 receiver，故参数顺序为 (值, 声明类, 字段名)：
	// 字节码里 [value] + ldc class + ldc name 恰好构成 invokestatic 的实参顺序。
	// 基址直接传声明类本身，等价于 Unsafe.staticFieldBase(field)。

	public static void putStaticObject(Object v, Class<?> c, String name) {
		Field f = field(c, name);
		U.putObjectVolatile(U.staticFieldBase(f), U.staticFieldOffset(f), v);
	}
	public static void putStaticInt(int v, Class<?> c, String name) {
		Field f = field(c, name);
		U.putIntVolatile(U.staticFieldBase(f), U.staticFieldOffset(f), v);
	}
	public static void putStaticLong(long v, Class<?> c, String name) {
		Field f = field(c, name);
		U.putLongVolatile(U.staticFieldBase(f), U.staticFieldOffset(f), v);
	}
	public static void putStaticFloat(float v, Class<?> c, String name) {
		Field f = field(c, name);
		U.putFloatVolatile(U.staticFieldBase(f), U.staticFieldOffset(f), v);
	}
	public static void putStaticDouble(double v, Class<?> c, String name) {
		Field f = field(c, name);
		U.putDoubleVolatile(U.staticFieldBase(f), U.staticFieldOffset(f), v);
	}
	public static void putStaticBoolean(boolean v, Class<?> c, String name) {
		Field f = field(c, name);
		U.putBooleanVolatile(U.staticFieldBase(f), U.staticFieldOffset(f), v);
	}
	public static void putStaticByte(byte v, Class<?> c, String name) {
		Field f = field(c, name);
		U.putByteVolatile(U.staticFieldBase(f), U.staticFieldOffset(f), v);
	}
	public static void putStaticChar(char v, Class<?> c, String name) {
		Field f = field(c, name);
		U.putCharVolatile(U.staticFieldBase(f), U.staticFieldOffset(f), v);
	}
	public static void putStaticShort(short v, Class<?> c, String name) {
		Field f = field(c, name);
		U.putShortVolatile(U.staticFieldBase(f), U.staticFieldOffset(f), v);
	}

	/** 实例字段偏移 */
	public static long offset(Class<?> c, String name) {
		return U.objectFieldOffset(field(c, name));
	}

	private static Field field(Class<?> c, String name) {
		try {
			return c.getDeclaredField(name);
		} catch (NoSuchFieldException e) { throw new Error(e); }
	}
}