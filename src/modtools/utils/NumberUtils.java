package modtools.utils;

public class NumberUtils {
	public static boolean validPosInt(String text) {
		return text.matches("\\d+(.\\d*)?([Ee]\\d+)?$");
	}

	public static int asInt(String text) {
		return (int) Float.parseFloat(text);
	}
}
