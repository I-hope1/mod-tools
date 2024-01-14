package modtools.utils;

import mindustry.ctype.UnlockableContent;

import java.util.regex.Pattern;

public class PatternUtils {
	public static Pattern compileRegExpOrNull(String text) {
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

	public static <T> boolean testContent(String text, Pattern pattern, T item) {
		if (text == null || text.isEmpty()) return false;
		if (pattern == null) return true;
		if (item instanceof UnlockableContent unlock) {
			return !pattern.matcher(unlock.name).find() && !pattern.matcher(unlock.localizedName).find();
		}
		return !pattern.matcher("" + item).find();
	}
}
