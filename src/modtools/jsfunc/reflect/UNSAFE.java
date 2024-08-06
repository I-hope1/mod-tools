package modtools.jsfunc.reflect;

import arc.util.OS;
import dalvik.system.VMRuntime;
import ihope_lib.*;
import jdk.internal.misc.Unsafe;

import static ihope_lib.MyReflect.unsafe;

@SuppressWarnings({"unused", "deprecation"})
public interface UNSAFE {
	/* /trust 不安全 */
	Object lookup = MyReflect.lookup;

	/** 开放类的模块的{@code pn} */
	static void openModule(Class<?> cls, String pn) {
		if (OS.isAndroid) return;
		try {
			MyReflect.openModule(cls.getModule(), pn);
		} catch (Throwable ignored) {}
	}
	static void openModule(Object module, String pn) throws Throwable {
		if (OS.isAndroid) return;
		MyReflect.openModule((Module) module, pn);
	}

	// ------------put and get------------
	static void putObject(Object obj, long offset, Object x) {
		unsafe.putObject(obj, offset, x);
	}
	static void putInt(Object obj, long offset, int x) {
		unsafe.putInt(obj, offset, x);
	}
	static void putLong(Object obj, long offset, long x) {
		unsafe.putLong(obj, offset, x);
	}
	static void putFloat(Object obj, long offset, float x) {
		unsafe.putFloat(obj, offset, x);
	}
	static void putDouble(Object obj, long offset, double x) {
		unsafe.putDouble(obj, offset, x);
	}
	static void putByte(Object obj, long offset, byte x) {
		unsafe.putByte(obj, offset, x);
	}
	static void putChar(Object obj, long offset, char x) {
		unsafe.putChar(obj, offset, x);
	}
	static void putShort(Object obj, long offset, short x) {
		unsafe.putShort(obj, offset, x);
	}
	static void putBoolean(Object obj, long offset, boolean x) {
		unsafe.putBoolean(obj, offset, x);
	}
	static Object getObject(Object obj, long offset) {
		return unsafe.getObject(obj, offset);
	}
	static int getInt(Object obj, long offset) {
		return unsafe.getInt(obj, offset);
	}
	static long getLong(Object obj, long offset) {
		return unsafe.getLong(obj, offset);
	}
	static float getFloat(Object obj, long offset) {
		return unsafe.getFloat(obj, offset);
	}
	static double getDouble(Object obj, long offset) {
		return unsafe.getDouble(obj, offset);
	}
	static byte getByte(Object obj, long offset) {
		return unsafe.getByte(obj, offset);
	}
	static char getChar(Object obj, long offset) {
		return unsafe.getChar(obj, offset);
	}
	static short getShort(Object obj, long offset) {
		return unsafe.getShort(obj, offset);
	}
	static boolean getBoolean(Object obj, long offset) {
		return unsafe.getBoolean(obj, offset);
	}
	static long objectFieldOffset(Class<?> cls, String fieldName) {
		try {
			return Unsafe.getUnsafe().objectFieldOffset(cls, fieldName);
		} catch (Throwable ignored) {}
		try {
			return unsafe.objectFieldOffset(cls.getDeclaredField(fieldName));
		} catch (NoSuchFieldException e) {
			return -1;
		}
	}
	static long staticFieldOffset(Class<?> cls, String fieldName) {
		try {
			/* 这个也可以获取static的  */
			return Unsafe.getUnsafe().objectFieldOffset(cls, fieldName);
		} catch (Throwable ignored) {}
		try {
			return unsafe.staticFieldOffset(cls.getDeclaredField(fieldName));
		} catch (NoSuchFieldException e) {
			return -1;
		}
	}

	// ---------Address/Memory Operation---------
	static long vaddressOf(Object o) {
		if (o == null) throw new IllegalArgumentException("o is null.");
		ONE_ARRAY[0] = o;
		long baseOffset = unsafe.arrayBaseOffset(Object[].class);
		return switch (unsafe.arrayIndexScale(Object[].class)) {
			case 4 -> (unsafe.getInt(ONE_ARRAY, baseOffset) & 0xFFFFFFFFL) * (OS.is64Bit ? 8 : 1);
			case 8 -> unsafe.getLong(ONE_ARRAY, baseOffset);
			default -> throw new UnsupportedOperationException("Unsupported address size: " + unsafe.arrayIndexScale(Object[].class));
		};
	}

	Object[] ONE_ARRAY = OS.isAndroid ? (Object[]) VMRuntime.getRuntime().newNonMovableArray(Object.class, 1) : new Object[1];
	static Object getObject(long address) {
		ONE_ARRAY[0] = null;
		long baseOffset = unsafe.arrayBaseOffset(Object[].class);
		switch (unsafe.addressSize()) {
			case 4 -> unsafe.putInt(ONE_ARRAY, baseOffset, (int) address);
			case 8 -> unsafe.putLong(ONE_ARRAY, baseOffset, address);
			default -> throw new UnsupportedOperationException("Unsupported address size: " + unsafe.addressSize());
		}
		return ONE_ARRAY[0];
	}
	static void putObject(long address, Object x) {
		ONE_ARRAY[0] = x;
		long baseOffset = unsafe.arrayBaseOffset(Object[].class);
		switch (unsafe.addressSize()) {
			case 4 -> unsafe.putInt(ONE_ARRAY, baseOffset, (int) address);
			case 8 -> unsafe.putLong(ONE_ARRAY, baseOffset, address);
			default -> throw new UnsupportedOperationException("Unsupported address size: " + unsafe.addressSize());
		}
	}

	static void putInt(long address, int x) {
		unsafe.putInt(address, x);
	}
	static void putLong(long address, long x) {
		unsafe.putLong(address, x);
	}
	static void putFloat(long address, float x) {
		unsafe.putFloat(address, x);
	}
	static void putDouble(long address, double x) {
		unsafe.putDouble(address, x);
	}
	static void putByte(long address, byte x) {
		unsafe.putByte(address, x);
	}
	static void putChar(long address, char x) {
		unsafe.putChar(address, x);
	}
	static void putShort(long address, short x) {
		unsafe.putShort(address, x);
	}

	static int getInt(long address) {
		return unsafe.getInt(address);
	}
	static long getLong(long address) {
		return unsafe.getLong(address);
	}
	static float getFloat(long address) {
		return unsafe.getFloat(address);
	}
	static double getDouble(long address) {
		return unsafe.getDouble(address);
	}
	static byte getByte(long address) {
		return unsafe.getByte(address);
	}
	static char getChar(long address) {
		return unsafe.getChar(address);
	}
	static short getShort(long address) {
		return unsafe.getShort(address);
	}

	static long allocateMemory(long bytes) {
		return unsafe.allocateMemory(bytes);
	}
	static void freeMemory(long address) {
		unsafe.freeMemory(address);
	}

	static void park(boolean isAbsolute, long time) {
		unsafe.park(isAbsolute, time);
	}
	static void unpark(Object thread) {
		unsafe.unpark(thread);
	}

	@SuppressWarnings("unchecked")
	static <T> T allocateInstance(Class<T> cls) {
		try {
			return (T) unsafe.allocateInstance(cls);
		} catch (InstantiationException e) {
			throw new RuntimeException(e);
		}
	}
	static void copyMemory(long src, long dest, int bytes) {
		unsafe.copyMemory(src, dest, bytes);
	}
}
