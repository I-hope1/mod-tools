package modtools.utils;

import modtools.HopeConstant.STRING;

import static ihope_lib.MyReflect.unsafe;

public class StringUtils {

	public static void changeByte(String from, String to) {
		unsafe.putObject(from, STRING.VALUE, unsafe.getObject(to, STRING.VALUE));
		unsafe.putByte(from, STRING.CODER, unsafe.getByte(to, STRING.CODER));
	}
}
