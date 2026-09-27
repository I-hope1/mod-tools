package modtools.utils;

public final class PrimPack {
	// ================= 打包：转为 64 位原始 Bits =================
	public static long pack(double v) { return Double.doubleToRawLongBits(v); }
	public static long pack(float v) { return (long) Float.floatToRawIntBits(v) & 0xFFFFFFFFL; }
	public static long pack(long v) { return v; }
	public static long pack(int v) { return (long) v; }         // 保留有符号负数
	public static long pack(short v) { return (long) v; }         // 保留有符号负数
	public static long pack(byte v) { return (long) v; }         // 保留有符号负数
	public static long pack(char v) { return (long) v & 0xFFFFL; } // 无符号 16 位
	public static long pack(boolean v) { return v ? 1L : 0L; }

	/** 将未知包装对象解构并打包为原始 bits（安全防截断） */
	public static long packObject(Object val) {
		if (val instanceof Double d) return pack(d.doubleValue());
		if (val instanceof Float f) return pack(f.floatValue());
		if (val instanceof Boolean b) return pack(b.booleanValue());
		if (val instanceof Character c) return pack(c.charValue());
		if (val instanceof Number n) return n.longValue(); // int, long, short, byte 直接走 longValue 保留符号
		return 0L;
	}

	// ================= 解包：从 64 位原始 Bits 还原 =================
	public static double unpackDouble(long bits) { return Double.longBitsToDouble(bits); }
	public static float unpackFloat(long bits) { return Float.intBitsToFloat((int) bits); }
	public static long unpackLong(long bits) { return bits; }
	public static int unpackInt(long bits) { return (int) bits; }
	public static short unpackShort(long bits) { return (short) bits; }
	public static byte unpackByte(long bits) { return (byte) bits; }
	public static char unpackChar(long bits) { return (char) bits; }
	public static boolean unpackBoolean(long bits) { return bits != 0L; }
}