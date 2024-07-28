package modtools.utils;

import mindustry.ctype.UnlockableContent;

import java.util.regex.Pattern;

/**
 * 一个用于处理正则表达式的工具类。
 */
public class PatternUtils {
	public static final Pattern ANY = Pattern.compile(".");
	/**
	 * 尝试编译给定的字符串为正则表达式，如果发生异常则返回 null。
	 * @param text 要编译的正则表达式字符串
	 * @return 编译后的 Pattern 对象，如果发生异常则返回 null
	 */
	public static Pattern compileRegExpOrNull(String text) {
		try {
			return compileRegExp(text);
		} catch (Throwable e) {
			return null;
		}
	}

	/**
	 * 编译给定的字符串为正则表达式。
	 * @param text 要编译的正则表达式字符串
	 * @return 编译后的 Pattern 对象，如果 text 为 null 或空字符串则返回 null
	 */
	public static Pattern compileRegExp(String text) {
		return text == null || text.isEmpty() ? ANY : Pattern.compile(text, Pattern.CASE_INSENSITIVE);
	}

	/**
	 * 测试给定的文本是否匹配给定的正则表达式。
	 * @param pattern 正则表达式的 Pattern 对象
	 * @param text    要测试的文本
	 * @return 如果 pattern 为 null 或者文本匹配正则表达式则返回 true
	 */
	public static boolean test(Pattern pattern, String text) {
		return pattern == ANY || (pattern != null && pattern.matcher(text).find());
	}

	/**
	 * 测试给定的对象是否与给定的正则表达式匹配。<br>
	 * 如果 text 为 null 或空字符串返回 true.<br>
	 * 如果 pattern 为 null 返回 false.<br>
	 * 如果 item 是 {@link UnlockableContent} 实例，则测试其{@link UnlockableContent#name name}和{@link UnlockableContent#localizedName localizedName}是否匹配,<br>
	 * @param text    要测试的文本
	 * @param pattern 正则表达式的 Pattern 对象
	 * @param item    要测试的对象
	 * @return boolean；
	 */
	public static <T> boolean testAny(Pattern pattern, T item) {
		if (pattern == null) return false;
		if (pattern == ANY) return true;
		if (item instanceof UnlockableContent unlock) {
			return test(pattern, unlock.name) || test(pattern, unlock.localizedName);
		}
		return test(pattern, "" + item);
	}
}
