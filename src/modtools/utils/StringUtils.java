package modtools.utils;

import modtools.HopeConstant.STRING;

import static ihope_lib.MyReflect.unsafe;

public class StringUtils {
	public static void changeByte(String from, String to) {
		unsafe.putObject(from, STRING.VALUE, unsafe.getObject(to, STRING.VALUE));
		unsafe.putByte(from, STRING.CODER, unsafe.getByte(to, STRING.CODER));
	}
	public static String substring(String str, int start, int end) {
		if (start < 0) start += str.length();
		if (end < 0) end += str.length();
		return str.substring(start, end);
	}
}
