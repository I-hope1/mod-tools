package nipx.util;

/**
 * 高性能 CRC64 实现，零对象分配。
 */
public final class CRC64 {
	private static final long   POLY   = 0xC96C5795D7870F42L; // ECMA-182
	private static final long[] LOOKUP = new long[256];

	static {
		for (int i = 0; i < 256; i++) {
			long res = i;
			for (int j = 0; j < 8; j++) {
				if ((res & 1) == 1) { res = (res >>> 1) ^ POLY; } else res >>>= 1;
			}
			LOOKUP[i] = res;
		}
	}

	/**
	 * 计算字节数组的 CRC64 值
	 */
	public static long update(byte[] b) {
		long crc = -1; // 初始值
		for (byte value : b) {
			crc = LOOKUP[(int) ((crc ^ value) & 0xff)] ^ (crc >>> 8);
		}
		return ~crc;
	}

	/**
	 * 计算字符串（如类名）的 CRC64 值，用于作为 LongLongMap 的 Key
	 * <p>注意: hashString 使用 UTF-16 序列级 CRC，不等同于 UTF-8 字节 CRC</p>
	 */
	public static long hashString(String s) {
		long crc = -1;
		int  len = s.length();
		for (int i = 0; i < len; i++) {
			char c = s.charAt(i);
			// 处理低位字节
			crc = LOOKUP[(int) ((crc ^ (c & 0xff)) & 0xff)] ^ (crc >>> 8);
			// 处理高位字节（Java char 是双字节）
			crc = LOOKUP[(int) ((crc ^ (c >>> 8)) & 0xff)] ^ (crc >>> 8);
		}
		return ~crc;
	}


	// ***************外部多个值运算************
	/* ========== 生命周期 ========== */

	public static long init() {
		return -1L;
	}

	public static long finish(long crc) {
		return ~crc;
	}

	/* ========== 基础 update ========== */
	public static long updateByte(long crc, int b) {
		return LOOKUP[(int) ((crc ^ b) & 0xFF)] ^ (crc >>> 8);
	}
	public static long updateBytes(long crc, byte[] data) {
		for (byte b : data) {
			crc = updateByte(crc, b);
		}
		return crc;
	}

	/* ========== 原始类型支持（避免装箱） ========== */
	public static long updateInt(long crc, int value) {
		crc = updateByte(crc, value);
		crc = updateByte(crc, value >>> 8);
		crc = updateByte(crc, value >>> 16);
		crc = updateByte(crc, value >>> 24);
		return crc;
	}

	public static long updateLong(long crc, long value) {
		crc = updateByte(crc, (int) value);
		crc = updateByte(crc, (int) (value >>> 8));
		crc = updateByte(crc, (int) (value >>> 16));
		crc = updateByte(crc, (int) (value >>> 24));
		crc = updateByte(crc, (int) (value >>> 32));
		crc = updateByte(crc, (int) (value >>> 40));
		crc = updateByte(crc, (int) (value >>> 48));
		crc = updateByte(crc, (int) (value >>> 56));
		return crc;
	}

	/* ========== 字符串（推荐 UTF-8 统一编码） ========== */
	public static long updateStringUTF16(long crc, String s) {
		int len = s.length();
		for (int i = 0; i < len; i++) {
			char c = s.charAt(i);
			crc = updateByte(crc, c);
			crc = updateByte(crc, c >>> 8);
		}
		return crc;
	}
}