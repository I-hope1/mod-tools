package nipx.util;

public class Utils {
	/**
	 * 计算复合 Key：将逻辑名 Hash 和描述符 Hash 压缩进一个 long
	 * 解决了 String 拼接产生的 char[] 拷贝和 StringBuilder 垃圾
	 */
	public static long computeCompositeHash(String logicalName, String desc) {
		long crc = CRC64.init();
		crc = CRC64.updateStringUTF16(crc, logicalName);
		crc = CRC64.updateStringUTF16(crc, desc);
		return CRC64.finish(crc);
	}
	public static long hash(String itf) {
		return CRC64.hashString(itf);
	}
}
