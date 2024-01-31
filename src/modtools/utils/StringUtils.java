package modtools.utils;

import arc.Core;
import arc.util.*;
import dalvik.system.VMRuntime;
import modtools.HopeConstant.*;
import modtools.android.HiddenApi;

import java.lang.reflect.Field;

import static ihope_lib.MyReflect.unsafe;

public interface StringUtils {
	static void changeByte(String from, String to) {
		if (OS.isAndroid) changeByteAndroid(from, to);
		else changeByteDesktop(from, to);
	}
	/** For Desktop  */
	private static void changeByteDesktop(String from, String to) {
		unsafe.putObject(from, STRING.VALUE, unsafe.getObject(to, STRING.VALUE));
		unsafe.putByte(from, STRING.CODER, unsafe.getByte(to, STRING.CODER));
	}
	/** For Android  */
	private static void changeByteAndroid(String from, String to) {
		String[] tmp     = {from, to};
		String[] strings = (String[]) VMRuntime.getRuntime().newNonMovableArray(String.class, 2);
		System.arraycopy(tmp, 0, strings, 0, 2);

		unsafe.putInt(from, ANDROID.STRING_COUNT, unsafe.getInt(to, ANDROID.STRING_COUNT));
		long  address  = HiddenApi.addressOf(strings);
		long  address2 = address + HiddenApi.IBYTES;
		/* unsafe.copyMemory(unsafe.getInt(address2) + 8,
			 unsafe.getInt(address) + 8, 2); */
		for (int i = 0; i < 100; i++) {
			unsafe.putLong(from,i, unsafe.getLong(to, i));
			/* unsafe.copyMemory(unsafe.getInt(address2) + i,
			 unsafe.getInt(address) + i, 3); */
			Log.info("res[@]: @ -> @", i, from, to);
			if (to.equals(from)) return;
		}
	}

	static String substring(String str, int start, int end) {
		if (start < 0) start += str.length();
		if (end < 0) end += str.length();
		return str.substring(start, end);
	}
}
