package modtools.utils.doubleconv;

public final class FastFormat {

	private static final double[] POW10 = {
	 1.0, 10.0, 100.0, 1000.0, 10000.0, 100000.0, 1000000.0, 10000000.0
	};

	/**
	 * 高效 autoFixed，格式化为字符串
	 */
	public static String autoFixed(float value, int max) {
		StringBuilder sb = new StringBuilder(16);
		autoFixed(sb, value, max);
		return sb.toString();
	}

	/** 高效 autoFixed，直接追加到 StringBuilder，避免额外 String 对象分配 */
	public static void autoFixed(StringBuilder sb, float value, int max) {
		// 1. 特殊值处理
		if (Float.isNaN(value)) {
			sb.append("NaN");
			return;
		}
		if (value == Float.POSITIVE_INFINITY) {
			sb.append("Infinity");
			return;
		}
		if (value == Float.NEGATIVE_INFINITY) {
			sb.append("-Infinity");
			return;
		}
		if (value == 0.0f) {
			sb.append('0');
			return;
		}

		boolean negative = value < 0;
		float   abs      = Math.abs(value);

		// float 的有效数字仅约 7 位。
		// 当 abs >= 10^7 时，步长 ULP >= 1.0，浮点数已无法表达小数，直接作为整数输出
		if (abs >= 10_000_000f) {
			if (negative) sb.append('-');
			sb.append((long) abs);
			return;
		}

		// float 精度限制，max 限制在 0~7 之间
		if (max <= 0) {
			long rounded = (long) (abs + 0.5);
			if (negative && rounded != 0) sb.append('-');
			sb.append(rounded);
			return;
		}
		if (max > 7) max = 7;

		// 2. 利用 double 转为 long 整数并四舍五入
		double scaled = (double) abs * POW10[max] + 0.5;
		long   n      = (long) scaled;

		// 3. 去除末尾无意义的 0（Trim trailing zeroes）
		int scale = max;
		while (scale > 0 && (n % 10 == 0)) {
			n /= 10;
			scale--;
		}

		// 检查舍入后是否归零（避免出现 "-0" 的情况）
		if (n == 0) {
			sb.append('0');
			return;
		}

		// 4. 倒序填入局部数组（在 JVM 中可触发栈上分配/标量替换，速度极快）
		char[] buf    = new char[24];
		int    cursor = 23;

		if (scale == 0) {
			// 没有小数部分，直接输出纯整数
			while (n > 0) {
				buf[cursor--] = (char) ('0' + (n % 10));
				n /= 10;
			}
		} else {
			// 写入小数部分（刚好写入 scale 位，高位自动补 0，如 0.05 里的 0）
			for (int i = 0; i < scale; i++) {
				buf[cursor--] = (char) ('0' + (n % 10));
				n /= 10;
			}
			buf[cursor--] = '.';
			// 写入整数部分
			if (n == 0) {
				buf[cursor--] = '0';
			} else {
				while (n > 0) {
					buf[cursor--] = (char) ('0' + (n % 10));
					n /= 10;
				}
			}
		}

		if (negative) {
			buf[cursor--] = '-';
		}

		int offset = cursor + 1;
		int len    = 24 - offset;
		sb.append(buf, offset, len);
	}
}