package modtools.annotations;

/**
 * 命名风格转换。纯字符串运算，不依赖任何 javac 状态，可以在处理器初始化之前单独使用。
 */
public final class NameUtils {
	private NameUtils() { }

	/**
	 * camelCase -> snake_case。
	 * <p>
	 * 词边界规则：当前字符为大写，且满足其一——
	 * <ul>
	 *   <li>前一个字符是小写或数字（{@code fooBar} → {@code foo_bar}）</li>
	 *   <li>前一个是连续大写而下一个是小写（{@code HTTPServer} → {@code http_server}）</li>
	 * </ul>
	 * 末尾大写（{@code fooX}）只按“前一个小写”断一次，不做 i+1 探测。
	 */
	public static String toUnderlineName(String fieldName) {
		StringBuilder sb = new StringBuilder(fieldName.length() + 4);
		if (fieldName.isEmpty()) return "";
		sb.append(Character.toLowerCase(fieldName.charAt(0)));
		for (int i = 1, len = fieldName.length(); i < len; i++) {
			char cur  = fieldName.charAt(i);
			char prev = fieldName.charAt(i - 1);
			boolean boundary = Character.isUpperCase(cur)
			                   && (Character.isLowerCase(prev) || Character.isDigit(prev)
			                       || (Character.isUpperCase(prev) && i + 1 < len && Character.isLowerCase(fieldName.charAt(i + 1))));
			if (boundary) sb.append('_');
			sb.append(Character.toLowerCase(cur));
		}
		return sb.toString();
	}

	/** {@code test-ui} / {@code test_ui} -> {@code testUi} */
	public static String kebabToCamel(String s) {
		StringBuilder result = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '_' || c == '-') continue;
			boolean afterSeparator = i > 0 && (s.charAt(i - 1) == '_' || s.charAt(i - 1) == '-');
			result.append(afterSeparator ? Character.toUpperCase(c) : c);
		}
		return result.toString();
	}

	/** {@code test_ui} -> {@code TestUI} */
	public static String kebabToBigCamel(CharSequence kebab) {
		StringBuilder sb = new StringBuilder(kebab.length());
		if (kebab.isEmpty()) return "";
		sb.append(Character.toUpperCase(kebab.charAt(0)));
		boolean isUpper = false;
		for (int i = 1, len = kebab.length(); i < len; i++) {
			if (kebab.charAt(i) == '_') {
				isUpper = true;
				continue;
			}
			// test_ui_
			if (kebab.charAt(i - 1) != '_' && isUpper && (i + 1 < len && kebab.charAt(i + 1) != '_')) isUpper = false;
			sb.append(isUpper ? Character.toUpperCase(kebab.charAt(i)) : kebab.charAt(i));
		}
		return sb.toString();
	}

	/** {@code test_ui} -> {@code Test Ui} */
	public static String capitalize(String s) {
		StringBuilder result = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '_' || c == '-') {
				result.append(' ');
			} else if (i == 0 || s.charAt(i - 1) == '_' || s.charAt(i - 1) == '-') {
				result.append(Character.toUpperCase(c));
			} else {
				result.append(c);
			}
		}
		return result.toString();
	}
}
