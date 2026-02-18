package nipx.util;

public class Utils {
	/**
	 * 计算复合 Key：将逻辑名 Hash 和描述符 Hash 压缩进一个 long
	 * 解决了 String 拼接产生的 char[] 拷贝和 StringBuilder 垃圾
	 */
	public static long computeCompositeHash(String logicalName, String desc) {
		// 将两个 32 位哈希值拼成一个 64 位 long
		// 在单个类文件的上下文中，这种碰撞概率在数学上可以忽略不计
		return ((long) logicalName.hashCode() << 32) | (desc.hashCode() & 0xFFFFFFFFL);
	}
}
