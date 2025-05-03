package modtools.utils;

import arc.util.Strings;
import modtools.jsfunc.type.CAST;
import rhino.ScriptRuntime;

import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;

/**
 * Positive: {@code num >= 0}<br>
 * ∞: {@link Float#POSITIVE_INFINITY}<br>
 * @see Strings
 */
public class NumberHelper {
	public static boolean notNegativeInt(String text) {
		if (asInt(text) >= 0) return true;
		float res = asFloat(text);
		return 0 <= res && res < (float) Integer.MAX_VALUE;
		// return text.matches("^\\d+(\\.\\d*)?([Ee]\\d+)?$");
	}
	public static boolean isNumber(String text) {
		try {
			return !ScriptRuntime.isNaN(ScriptRuntime.toNumber(text.replace("∞", "Infinity")));
		} catch (Throwable ignored) {
			return false;
		}
	}
	public static boolean isFloat(String text) {
		try {
			Float.parseFloat(text.replace("∞", "Infinity"));
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}
	public static boolean isPositiveFloat(String text) {
		try {
			return asFloat(text) >= 0;
		} catch (Throwable ignored) {
			return false;
		}
	}
	public static float asFloat(String text) {
		try {
			return Strings.parseFloat(text.replace("∞", "Infinity"), Float.NaN);
		} catch (Throwable e) {
			return Float.NaN;
		}
	}
	public static int asInt(String text) {
		return CatchSR.apply(() ->
		 CatchSR.of(() -> Strings.parseInt(text))
			.get(() -> (int) asFloat(text))
			.get(() -> 0)
		);
	}

	public static Number parse(String text, Class<?> type0) {
		Class<?> type = CAST.box(type0);
		if (type == Float.class) return asFloat(text);
		if (type == Integer.class) return asInt(text);
		if (type == Double.class) return Double.parseDouble(text);
		if (type == Long.class) return Long.parseLong(text);
		if (type == Short.class) return Short.parseShort(text);
		if (type == Byte.class) return Byte.parseByte(text);
		if (type == BigDecimal.class) return new BigDecimal(text);
		try {
			return (Number) type.getDeclaredMethod("valueOf", String.class).invoke(null, text);
		} catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
			throw new NumberParseException(STR."\{text} cannot be cast to \{type0}", e);
		}
	}

	public static boolean isDigital(String key, int start, int end) {
		boolean isIndex = true;
		for (int i = start; i < end; i++) {
			if (!Character.isDigit(key.charAt(i))) {
				isIndex = false;
				break;
			}
		}
		return isIndex;
	}
	public static int parseDigital(String key, int start, int end) {
		int index = 0;
		for (int i = start; i < end; i++) {
			index = (index * 10) + (key.charAt(i) - '0');
		}
		return index;
	}
	public static class NumberParseException extends RuntimeException {
		public NumberParseException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
