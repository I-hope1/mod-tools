package nipx.ref;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Final field writer
 * <p>用于处理final字段的写入操作</p>
 */
@SuppressWarnings("removal")
public final class FinalFieldWriter {
	private static final Unsafe U = Unsafe.getUnsafe(); // 反射拿 theUnsafe

	// 每次重定义后清空
	private static final Map<Field, Long> OFFSETS = new ConcurrentHashMap<>();

	public static void putObject(Object o, Object v, Class<?> c, String name) {
		U.putObject(o, offset(c, name), v);
	}
	public static void putInt(Object o, int v, Class<?> c, String name) {
		U.putInt(o, offset(c, name), v);
	}
	public static void putLong(Object o, long v, Class<?> c, String name) {
		U.putLong(o, offset(c, name), v);
	}
	public static void putFloat(Object o, float v, Class<?> c, String name) {
		U.putFloat(o, offset(c, name), v);
	}
	public static void putDouble(Object o, double v, Class<?> c, String name) {
		U.putDouble(o, offset(c, name), v);
	}
	public static void putBoolean(Object o, boolean v, Class<?> c, String name) {
		U.putBoolean(o, offset(c, name), v);
	}
	public static void putByte(Object o, byte v, Class<?> c, String name) {
		U.putByte(o, offset(c, name), v);
	}
	public static void putChar(Object o, char v, Class<?> c, String name) {
		U.putChar(o, offset(c, name), v);
	}
	public static void putShort(Object o, short v, Class<?> c, String name) {
		U.putShort(o, offset(c, name), v);
	}

	private static long offset(Class<?> c, String name) {
		try {
			Field f = c.getDeclaredField(name);
			return OFFSETS.computeIfAbsent(f, U::objectFieldOffset);
		} catch (NoSuchFieldException e) { throw new Error(e); }
	}
}