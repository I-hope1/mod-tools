package modtools.utils;

import arc.Core;
import arc.util.*;
import modtools.Constants.STRING;

import static ihope_lib.MyReflect.unsafe;

@SuppressWarnings("removal")
public interface StringUtils {

	/**
	 * 通过内存设置，直接复制字符串：让 {@code from} 这个<b>已有实例</b>持有 {@code to} 的内容，
	 * 这样所有已经拿到 {@code from} 引用的地方（界面标签等）不用重建就会看到新文本。
	 */
	static void copyByte(String from, String to) {
		if (from == null || to == null || from == to) return;
		// 空串是全局共享的实例（"" 会被 intern，桌面还共用同一个空数组），
		// 改写它等于改写全进程的空串；而把内容写成空串也没有收益，两边都直接跳过。
		if (from.isEmpty() || to.isEmpty()) return;
		// 改写是「内容 + 长度/编码 + hash 缓存」的非原子多字段写入，必须和逐帧读文本、
		// 随时算 hashCode 的其它线程串行，否则会读到长度和内容对不上的中间态。
		if (!Core.app.isOnMainThread()) {
			Log.warn("copyByte: called off the main thread, skipped.");
			return;
		}
		if (OS.isAndroid) copyByteAndroid(from, to);
		else copyByteDesktop(from, to);
	}
	/** For Desktop：value 是堆上独立的 byte[]，换个引用即可，长度由新数组本身决定。 */
	private static void copyByteDesktop(String from, String to) {
		// JDK 8 的 String 是 char[] value、没有 coder，按 byte[] + coder 去写会把内容写坏。
		if (STRING.VALUE < 0 || STRING.CODER < 0) {
			Log.warn("copyByte: unsupported String layout, skipped.");
			return;
		}
		unsafe.putObject(from, STRING.VALUE, unsafe.getObject(to, STRING.VALUE));
		unsafe.putByte(from, STRING.CODER, unsafe.getByte(to, STRING.CODER));
		resetHash(from);
	}
	/**
	 * For Android：ART 把字符<b>内联</b>在 String 对象尾部（{@code count_}、{@code hash_code_}、
	 * {@code union{uint16_t value_[0]; uint8_t value_compressed_[0]}}），没有 value 数组可以换引用，
	 * 只能在对象内那块定长字符区上改写。
	 */
	private static void copyByteAndroid(String from, String to) {
		// 老布局（Android 5.x 及更早）有独立的 char[] value 字段，字符不在对象里，放弃。
		if (STRING.COUNT < 0 || STRING.HASH < 0 || STRING.VALUE >= 0) {
			Log.warn("copyByte: unsupported String layout, skipped.");
			return;
		}
		// 字符区紧跟最后一个 32 位字段（union 对齐只要 2 字节，中间不会有填充）。
		// 用字段偏移推导而不是写死 16：对象头宽度随引用宽度变，写死等于把两个常量焊死。
		long data = Math.max(STRING.COUNT, STRING.HASH) + Integer.BYTES;

		int fromCount = unsafe.getInt(from, STRING.COUNT);
		int toCount   = unsafe.getInt(to, STRING.COUNT);
		// 8.0 起 count = (length << 1) | 压缩标志（0 = Latin-1 1 字节/字符，1 = UTF-16 2 字节/字符）；
		// 7.1 及更早没有压缩，count 就是字符数、恒为 UTF-16。用「count 是否等于 length 左移一位」
		// 现场判断，而不是按版本号猜。
		boolean packed = (fromCount >>> 1) == from.length();
		int capacity = packed ? from.length() << (fromCount & 1) : from.length() << 1;
		int needed   = packed ? to.length() << (toCount & 1) : to.length() << 1;
		// 字符区和对象一起分配（SizeOf = sizeof(String) + 字符字节数），塞不下就只能放弃。
		if (needed > capacity) return;

		for (int i = 0; i < needed; i++) {
			unsafe.putByte(from, data + i, unsafe.getByte(to, data + i));
		}
		// 变短时把剩下的旧字符清零：equals 的 intrinsic 按对齐后的整块比较，依赖对象尾部的
		// 填充是 0（见 ART string.h 里 RoundUp 到 kObjectAlignment 的注释），残留字符会让它
		// 和内容相同的串判成不相等。
		for (int i = needed; i < capacity; i++) {
			unsafe.putByte(from, data + i, (byte) 0);
		}
		// 内容已经和 to 逐字节一致，长度/编码标志和 hash 缓存直接照抄（Android 没有 hashIsZero）。
		unsafe.putInt(from, STRING.COUNT, toCount);
		unsafe.putInt(from, STRING.HASH, unsafe.getInt(to, STRING.HASH));
	}
	/** 内容换了，缓存的 hashCode 就是错的，必须让它下次重算。 */
	private static void resetHash(String str) {
		// hashIsZero 和 hash 是同一个懒计算协议的两半：只清 hash 而留着 hashIsZero=true，
		// hashCode() 会直接返回 0、根本不重算。先撤标记再清值。
		if (STRING.HASH_IS_ZERO >= 0) unsafe.putBoolean(str, STRING.HASH_IS_ZERO, false);
		if (STRING.HASH >= 0) unsafe.putInt(str, STRING.HASH, 0);
	}

	@SuppressWarnings("StringRepeatCanBeUsed")
	static String repeat(String str, int count) {
		try {
			return str.repeat(count);
		} catch (Throwable e) {
			if (count == 1) return str;
			StringBuilder buffer = new StringBuilder();
			for (int i = 0; i < count; i++) {
				buffer.append(str);
			}
			return buffer.toString();
		}
	}
	static String repeat(char c, int count) {
		if (count == 1) return String.valueOf(c);
		char[] array = new char[count];
		return String.copyValueOf(array);
	}

	static String substring(String str, int start, int end) {
		if (start < 0) start += str.length();
		if (end < 0) end += str.length();
		return str.substring(start, end);
	}

	static boolean equals(CharSequence bigText, int start, int end, CharSequence smallText) {
		if (smallText == null) return false;
		if (end - start != smallText.length()) return false;
		for (int j = start; j < end; j++) {
			if (bigText.charAt(j) != smallText.charAt(j - start)) return false;
		}
		return true;
	}

	/**
	 * 使用KCM算法
	 * @param text    被查找的文本
	 * @param pattern 查找文本
	 **/
	static int indexOf(String text, String pattern) {
		int[] next = computePrefixFunction(pattern);
		int   j    = 0; // index for pattern
		int   n    = text.length();
		int   m    = pattern.length();

		for (int i = 0; i < n; i++) { // index for text
			while (j > 0 && text.charAt(i) != pattern.charAt(j)) {
				j = next[j - 1];
			}
			if (text.charAt(i) == pattern.charAt(j)) {
				j++;
			}
			if (j == m) {
				return i - m + 1; // match found, return the start index
			}
		}
		return -1; // no match found
	}

	private static int[] computePrefixFunction(String pattern) {
		int   m    = pattern.length();
		int[] next = new int[m];
		int   j    = 0; // length of previous longest prefix suffix

		for (int i = 1; i < m; i++) {
			while (j > 0 && pattern.charAt(i) != pattern.charAt(j)) {
				j = next[j - 1];
			}
			if (pattern.charAt(i) == pattern.charAt(j)) {
				j++;
			}
			next[i] = j;
		}
		return next;
	}

	static boolean isBlank(CharSequence str, int start, int end) {
		for (; start < end; start++) {
			if (!Character.isWhitespace(str.charAt(start))) return false;
		}
		return true;
	}
	/** 判断是否符合正则表达式 \s*?\(\s*?)\s*? */
	static boolean isParentheses(CharSequence str, int start, int end) {
		// 检查索引是否在有效范围内
		if (start < 0 || end >= str.length() || start > end) {
			return false;
		}

		int index = start;

		// 跳过开头的空白字符
		while (index <= end && Character.isWhitespace(str.charAt(index))) {
			index++;
		}

		// 检查是否遇到左括号
		if (index <= end && str.charAt(index) == '(') {

			// 跳过括号内的空白字符
			do {
				index++;
			} while (index <= end && Character.isWhitespace(str.charAt(index)));

			// 检查是否到达最后一个索引
			return index > end;
		}

		return false;
	}
}
