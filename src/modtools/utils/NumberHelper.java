package modtools.utils;

import arc.util.Strings;
import rhino.ScriptRuntime;

import java.lang.reflect.InvocationTargetException;

import static modtools.utils.Tools.box;

public class NumberHelper {
	public static boolean isPosInt(String text) {
		return Strings.canParsePositiveInt(text);
		// return text.matches("^\\d+(\\.\\d*)?([Ee]\\d+)?$");
	}
	public static boolean isNumber(String text) {
		try {
			return !ScriptRuntime.isNaN(ScriptRuntime.toNumber(text));
		} catch (Throwable ignored) {
			return false;
		}
	}
	public static boolean isFloat(String text) {
		try {
			Float.parseFloat(text.replaceAll("∞", "Infinity"));
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}
	public static boolean isPositiveFloat(String text) {
		try {
			return asFloat(text) > 0;
		} catch (Throwable ignored) {
			return false;
		}
	}
	public static float asFloat(String text) {
		return Strings.parseFloat(text.replaceAll("∞", "Infinity"), Float.NaN);
	}
	public static int asInt(String text) {
		return Strings.parseInt(text);
	}

	public static Number cast(String text, Class<?> type0) {
		Class<?> type = box(type0);
		if (type == Float.class) return asFloat(text);
		if (type == Integer.class) return asInt(text);
		if (type == Double.class) return Double.parseDouble(text);
		if (type == Long.class) return Long.parseLong(text);
		if (type == Short.class) return Short.parseShort(text);
		if (type == Byte.class) return Byte.parseByte(text);
		try {
			return (Number) type.getDeclaredMethod("valueOf", String.class).invoke(null, text);
		} catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
			throw new ClassCastException(text + " cannot be cast to " + type0);
		}
	}
}
