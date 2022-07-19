package modtools.utils;

public class Tools {
	public static boolean validPosInt(String text) {
		return text.matches("\\d+(.\\d*)?([Ee]\\d+)?$");
	}

	public static int asInt(String text) {
		return (int) Float.parseFloat(text);
	}

	// 去除颜色
	public static String format(String s) {
		return s.replaceAll("\\[(\\w+?)\\]", "[\u0001$1]");
	}


	public static int len(String s) {
		return s.split("").length - 1;
	}
}
