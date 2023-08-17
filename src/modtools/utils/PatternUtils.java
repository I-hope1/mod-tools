package modtools.utils;

import java.util.regex.Pattern;

public class PatternUtils {
	public static Pattern compileRegExpCatch(String text) {
		try {
			return compileRegExp(text);
		} catch (Throwable e) {
			return null;
		}
	}
	public static Pattern compileRegExp(String text) {
		return text == null || text.isEmpty() ? null : Pattern.compile(text, Pattern.CASE_INSENSITIVE);
	}
	public static boolean test(Pattern pattern, String text) {
		return pattern == null || pattern.matcher(text).find();
	}
}
