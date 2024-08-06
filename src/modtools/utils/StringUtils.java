package modtools.utils;

import arc.util.OS;
import dalvik.system.VMRuntime;
import modtools.Constants.STRING;
import modtools.android.HiddenApi;

import static ihope_lib.MyReflect.unsafe;

public interface StringUtils {

	/** 通过内存设置，直接修复字符串 */
	static void copyByte(String from, String to) {
		if (OS.isAndroid) copyByteAndroid(from, to);
		else copyByteDesktop(from, to);
	}
	/** For Desktop */
	private static void copyByteDesktop(String from, String to) {
		unsafe.putObject(from, STRING.VALUE, unsafe.getObject(to, STRING.VALUE));
		unsafe.putByte(from, STRING.CODER, unsafe.getByte(to, STRING.CODER));
	}
	/** For Android */
	private static void copyByteAndroid(String from, String to) {
		String[] tmp     = {from, to};
		String[] strings = (String[]) VMRuntime.getRuntime().newNonMovableArray(String.class, 2);
		System.arraycopy(tmp, 0, strings, 0, 2);

		// Log.info("prev count: " + from.length());
		// unsafe.putInt(from, STRING_COUNT, unsafe.getInt(to, STRING_COUNT));
		// Log.info("now count: " + from.length());
		// Log.info("COUNT_OFF: @", STRING_COUNT);
		long src_address  = HiddenApi.addressOf(strings);
		long dest_address = src_address + HiddenApi.IBYTES;
		// unsafe.putLong(unsafe.getInt(src_address) + 12, unsafe.getLong(
		// 	 unsafe.getInt(dest_address) + 12));
		/* unsafe.copyMemory(unsafe.getInt(address2) + 8,
			 unsafe.getInt(address) + 8, 2); */
		for (int i = 8; i < 100; i += 2) {
			// unsafe.putLong(from, i, unsafe.getLong(to, i));
			// Log.info("res[@]: @ -> @", i, from, to);
			if (to.equals(from)) break;
			unsafe.copyMemory(unsafe.getInt(dest_address) + i,
			 unsafe.getInt(src_address) + i, 2);
		}
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
