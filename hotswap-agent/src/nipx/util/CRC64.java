package nipx.util;

/**
 * 高性能 CRC64 实现，零对象分配。
 */
public final class CRC64 {
    private static final long POLY = 0xC96C5795D7870F42L; // ECMA-182
    private static final long[] LOOKUP = new long[256];

    static {
        for (int i = 0; i < 256; i++) {
            long res = i;
            for (int j = 0; j < 8; j++) {
                if ((res & 1) == 1) res = (res >>> 1) ^ POLY;
                else res >>>= 1;
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
     */
    public static long hashString(String s) {
        long crc = -1;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            // 处理低位字节
            crc = LOOKUP[(int) ((crc ^ (c & 0xff)) & 0xff)] ^ (crc >>> 8);
            // 处理高位字节（Java char 是双字节）
            crc = LOOKUP[(int) ((crc ^ (c >>> 8)) & 0xff)] ^ (crc >>> 8);
        }
        return ~crc;
    }

}