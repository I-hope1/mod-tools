package nipx.ref;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Final field writer
 * <p>用于处理 final 字段的写入操作：实例字段用 {@code putXxx}，静态字段用 {@code putStaticXxx}。</p>
 * <p>写入走 {@code Unsafe.putXxxVolatile}，是 volatile 写（强于 release）。</p>
 * <p>偏移/基址按 (Class, name) 缓存到 {@link ClassValue}：类卸载时自动失效；
 * redefine 改变了类布局时，框架应调用 {@link #clearCache(Class)} 主动失效。</p>
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

	/** 实例字段偏移缓存：Class -> (fieldName -> offset)。 */
	private static final ClassValue<ConcurrentHashMap<String, Long>> OFFSET_CACHE =
	 new ClassValue<>() {
		 @Override
		 protected ConcurrentHashMap<String, Long> computeValue(Class<?> type) {
			 return new ConcurrentHashMap<>();
		 }
	 };

	/** 静态字段槽位缓存：Class -> (fieldName -> (base, offset))。 */
	private static final ClassValue<ConcurrentHashMap<String, StaticSlot>> STATIC_CACHE =
	 new ClassValue<>() {
		 @Override
		 protected ConcurrentHashMap<String, StaticSlot> computeValue(Class<?> type) {
			 return new ConcurrentHashMap<>();
		 }
	 };

	private record StaticSlot(Object base, long offset) { }

	/**
	 * 失效指定类的偏移缓存，框架在 redefine 改变类布局后调用。
	 * <p>幂等且 null 安全：可重复调用，也可对同一类前后各调用一次
	 * （前清保证补丁用新偏移，后清覆盖补丁期间新填充的条目）。</p>
	 */
	public static void clearCache(Class<?> clazz) {
		if (clazz == null) return;
		OFFSET_CACHE.remove(clazz);
		STATIC_CACHE.remove(clazz);
	}

	// ==================== 实例字段 ====================

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
	// 基址经 Unsafe.staticFieldBase(field) 取（对静态字段等价于声明类本身）。

	public static void putStaticObject(Object v, Class<?> c, String name) {
		StaticSlot s = staticSlot(c, name);
		U.putObjectVolatile(s.base(), s.offset(), v);
	}
	public static void putStaticInt(int v, Class<?> c, String name) {
		StaticSlot s = staticSlot(c, name);
		U.putIntVolatile(s.base(), s.offset(), v);
	}
	public static void putStaticLong(long v, Class<?> c, String name) {
		StaticSlot s = staticSlot(c, name);
		U.putLongVolatile(s.base(), s.offset(), v);
	}
	public static void putStaticFloat(float v, Class<?> c, String name) {
		StaticSlot s = staticSlot(c, name);
		U.putFloatVolatile(s.base(), s.offset(), v);
	}
	public static void putStaticDouble(double v, Class<?> c, String name) {
		StaticSlot s = staticSlot(c, name);
		U.putDoubleVolatile(s.base(), s.offset(), v);
	}
	public static void putStaticBoolean(boolean v, Class<?> c, String name) {
		StaticSlot s = staticSlot(c, name);
		U.putBooleanVolatile(s.base(), s.offset(), v);
	}
	public static void putStaticByte(byte v, Class<?> c, String name) {
		StaticSlot s = staticSlot(c, name);
		U.putByteVolatile(s.base(), s.offset(), v);
	}
	public static void putStaticChar(char v, Class<?> c, String name) {
		StaticSlot s = staticSlot(c, name);
		U.putCharVolatile(s.base(), s.offset(), v);
	}
	public static void putStaticShort(short v, Class<?> c, String name) {
		StaticSlot s = staticSlot(c, name);
		U.putShortVolatile(s.base(), s.offset(), v);
	}

	/** 实例字段偏移（带缓存）。 */
	public static long offset(Class<?> c, String name) {
		return OFFSET_CACHE.get(c)
		 .computeIfAbsent(name, n -> U.objectFieldOffset(field(c, n)));
	}

	private static StaticSlot staticSlot(Class<?> c, String name) {
		return STATIC_CACHE.get(c).computeIfAbsent(name, n -> {
			Field f = field(c, n);
			return new StaticSlot(U.staticFieldBase(f), U.staticFieldOffset(f));
		});
	}

	private static Field field(Class<?> c, String name) {
		try {
			return c.getDeclaredField(name);
		} catch (NoSuchFieldException e) { throw new Error(e); }
	}
}