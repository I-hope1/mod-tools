package modtools.ui.comp.completion;

import arc.struct.ObjectSet;
import arc.struct.Seq;

import java.util.Comparator;

/**
 * 补全的纯逻辑：切出正在输入的标识符、判断是否为成员访问、给候选名打分排序。
 * <p>
 * 这里不碰 UI 也不碰 Rhino：渲染在 {@link CompletionPopup}，触发时机在
 * {@code Tester.ComplementListener}。分开是为了能脱离编辑器状态单独验证。
 */
public final class Completion {
	/** 候选档位：数字越小越靠前 */
	public static final int RANK_PREFIX_CASE        = 0;
	/** 只有大小写不同。比驼峰缩写更接近用户敲的内容，所以排在驼峰之前 */
	public static final int RANK_PREFIX_IGNORE_CASE = 1;
	public static final int RANK_HUMP               = 2;
	public static final int NO_MATCH                = -1;

	private Completion() { }

	/**
	 * 光标处的补全上下文：正在输入的词、是否成员访问、是否落在代码里。
	 * <p>
	 * 三者来自同一次判定，调用方不必分别计算——分开算容易出现
	 * 「按词边界补全、但那个位置其实是字符串」这类自相矛盾的组合。
	 */
	public static final class Context {
		/** 正在输入的词的起点 */
		public final int     start;
		/** 光标左侧的词，可能是空串（刚敲完 '.'） */
		public final String  prefix;
		/** 前缀是否紧跟在一个 '.' 之后 */
		public final boolean member;
		/** 成员访问的那个 '.' 的下标；不是成员访问时为 -1 */
		public final int     dot;
		/** 光标是否在代码里；字符串、注释、正则里的内容不是代码 */
		public final boolean inCode;

		Context(int start, String prefix, int dot, boolean inCode) {
			this.start = start;
			this.prefix = prefix;
			this.dot = dot;
			this.member = dot >= 0;
			this.inCode = inCode;
		}
	}

	public static Context contextAt(CharSequence text, int cursor) {
		int caret = Math.max(0, Math.min(cursor, text.length()));
		int start = prefixStart(text, caret);
		boolean inCode = isInCode(text, caret);
		// 光标在代码里时，前缀左边的空白和 '.' 也一定在同一段代码里，无需重复扫描
		int dot = inCode ? memberDot(text, start) : -1;
		return new Context(start, text.subSequence(start, caret).toString(), dot, inCode);
	}

	/**
	 * 光标左侧连续标识符字符的起点，即「正在输入的词」在文本里的位置。
	 * <p>
	 * 不用编辑器的选区来切词：选区会随用户点击、拖拽变化，
	 * 而补全只关心光标左边紧邻的那一段。
	 */
	public static int prefixStart(CharSequence text, int cursor) {
		int i = Math.min(cursor, text.length());
		while (i > 0 && Character.isJavaIdentifierPart(text.charAt(i - 1))) i--;
		return i;
	}

	/**
	 * 前缀前一个非空白字符是 '.' 才算成员访问。
	 * <p>
	 * 不向前搜索更远的点：{@code foo(bar} 或 {@code a + b} 之前出现过的 '.' 属于别的表达式，
	 * 拿它当基对象会补出完全无关的成员。只跳过空白是因为 {@code a . b} 和换行续写的
	 * {@code a.\n b} 在 JS 里都是合法的成员访问。
	 * <p>
	 * 字符串、注释、正则里的 '.' 不算：{@code "a.b"} 里的点是文本的一部分，
	 * 拿它当成员访问会把字符串里的内容当成对象来补。
	 */
	public static boolean isMemberAccess(CharSequence text, int prefixStart) {
		return memberDot(text, prefixStart) >= 0;
	}

	/**
	 * 成员访问的那个 '.' 的下标，不是成员访问时返回 -1。
	 * <p>
	 * 调用方需要点号的位置而不只是布尔值：编辑器的高亮要截止到点号之前，
	 * 用 {@code prefixStart - 1} 代替在 {@code a . b} 这类带空格的写法里会指到空格上。
	 */
	public static int memberDot(CharSequence text, int prefixStart) {
		int i = prefixStart;
		while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) i--;
		if (i == 0 || text.charAt(i - 1) != '.') return -1;

		int dot = i - 1;
		if (!isInCode(text, dot)) return -1;
		return isDecimalPoint(text, dot) ? -1 : dot;
	}

	/**
	 * 小数点与成员访问的点写法相同，靠两侧是否都是数字区分。
	 * <p>
	 * 判定窄是有意的：{@code 1.5.toFixed()} 里第二个点两侧不是纯数字，仍是成员访问；
	 * {@code x1.ma} 的点左边虽是数字，但右边是标识符，也仍是成员访问。
	 */
	private static boolean isDecimalPoint(CharSequence text, int dot) {
		if (dot == 0 || !Character.isDigit(text.charAt(dot - 1))) return false;
		int after = dot + 1;
		return after >= text.length() || Character.isDigit(text.charAt(after));
	}

	/* ---------------------------------------------------------------- 词法上下文 */

	private static final int
	 CODE         = 0,
	 LINE_COMMENT = 1,
	 BLOCK_COMMENT = 2,
	 STRING       = 3,
	 TEMPLATE     = 4,
	 REGEX        = 5,
	 REGEX_CLASS  = 6;

	/**
	 * 光标位置是否在代码里。字符串、注释、正则、模板串的文本部分都不算。
	 * <p>
	 * 编辑器本身没有可查询的词法状态，所以按行首扫到光标处重建一次。文本量在脚本编辑器里
	 * 是几 KB 量级，一次线性扫描远小于一次 Rhino 取 id 的开销。
	 * <p>
	 * 模板串的 {@code ${...}} 内部是代码，按花括号配平回到模板文本；
	 * 正则字面量按前一个有效字符区分除法与正则，判不准时倾向当作代码——
	 * 误判成字符串会彻底禁掉补全，误判成代码最多是补全位置不准。
	 */
	public static boolean isInCode(CharSequence text, int index) {
		int limit = Math.min(Math.max(index, 0), text.length());
		int state = CODE;
		int braceDepth = 0;
		int nestedTemplates = 0;
		int[] outerBraces = new int[8];
		char quote = 0;
		// 上一个有效字符，用来区分 '/' 是除法还是正则开头
		char prev = 0;

		for (int i = 0; i < limit; i++) {
			char c = text.charAt(i);
			char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;

			switch (state) {
				case CODE -> {
					if (c == '/' && next == '/') {
						state = LINE_COMMENT;
						i++;
					} else if (c == '/' && next == '*') {
						state = BLOCK_COMMENT;
						i++;
					} else if (c == '/' && regexAllowed(prev)) {
						state = REGEX;
					} else if (c == '"' || c == '\'') {
						state = STRING;
						quote = c;
					} else if (c == '`') {
						state = TEMPLATE;
					} else if (c == '{') {
						braceDepth++;
					} else if (c == '}') {
						if (braceDepth > 0) braceDepth--;
						else if (nestedTemplates > 0) {
							braceDepth = outerBraces[--nestedTemplates];
							state = TEMPLATE;
						}
					}
					if (state == CODE && !Character.isWhitespace(c)) prev = c;
				}
				case LINE_COMMENT -> {
					if (c == '\n') state = CODE;
				}
				case BLOCK_COMMENT -> {
					if (c == '*' && next == '/') {
						state = CODE;
						i++;
					}
				}
				case STRING -> {
					if (c == '\\') i++;
					else if (c == quote) {
						state = CODE;
						prev = ')'; // 字符串结束后接 '/' 是除法
					}
				}
				case TEMPLATE -> {
					if (c == '\\') {
						i++;
					} else if (c == '`') {
						state = CODE;
						prev = ')';
					} else if (c == '$' && next == '{') {
						if (nestedTemplates == outerBraces.length) {
							outerBraces = java.util.Arrays.copyOf(outerBraces, nestedTemplates * 2);
						}
						outerBraces[nestedTemplates++] = braceDepth;
						braceDepth = 0;
						state = CODE;
						prev = 0;
						i++;
					}
				}
				case REGEX -> {
					if (c == '\\') i++;
					else if (c == '[') state = REGEX_CLASS;
					else if (c == '/') {
						state = CODE;
						prev = ')';
					}
				}
				case REGEX_CLASS -> {
					if (c == '\\') i++;
					else if (c == ']') state = REGEX;
				}
				default -> throw new IllegalStateException("unexpected state " + state);
			}
		}
		return state == CODE;
	}

	/** 这些字符之后出现的 '/' 只可能是正则开头；标识符、')'、']' 之后的 '/' 是除法 */
	private static boolean regexAllowed(char prev) {
		return prev == 0 || "([{,;:=!&|?+-*/%<>~^}".indexOf(prev) >= 0;
	}

	/* ---------------------------------------------------------------- 候选排序 */

	/**
	 * 候选与前缀的匹配档位，{@link #NO_MATCH} 表示不该出现。
	 * <p>
	 * 大小写完全匹配排在最前：Java 成员名区分大小写，用户敲出大写字母时
	 * 期望的是 {@code getX} 而不是 {@code GetX}。
	 */
	public static int rank(String candidate, String prefix) {
		if (prefix.isEmpty()) return RANK_PREFIX_CASE;
		if (candidate.startsWith(prefix)) return RANK_PREFIX_CASE;
		if (candidate.regionMatches(true, 0, prefix, 0, prefix.length())) return RANK_PREFIX_IGNORE_CASE;
		return humpMatch(candidate, prefix) ? RANK_HUMP : NO_MATCH;
	}

	/**
	 * 驼峰缩写匹配：{@code gcp} 命中 {@code getCursorPosition}。
	 * <p>
	 * 前缀的每个字符按顺序匹配候选名的「词首」字符（首字符、大写字母、分隔符之后），
	 * 不匹配词中间的字母，否则 {@code e} 会命中几乎所有名字，失去缩写意义。
	 */
	static boolean humpMatch(String candidate, String prefix) {
		int pi = 0;
		for (int i = 0; i < candidate.length() && pi < prefix.length(); i++) {
			if (!isWordStart(candidate, i)) continue;
			if (Character.toLowerCase(candidate.charAt(i)) == Character.toLowerCase(prefix.charAt(pi))) pi++;
		}
		return pi == prefix.length();
	}

	private static boolean isWordStart(String name, int index) {
		char c = name.charAt(index);
		if (!Character.isLetterOrDigit(c)) return false;
		if (index == 0 || Character.isUpperCase(c)) return true;
		return !Character.isLetterOrDigit(name.charAt(index - 1));
	}

	/** 同档位内按字母序，且保证全序（大小写只差的名字不能互相比较为相等） */
	private static final Comparator<String> ORDER = (a, b) -> {
		int cmp = String.CASE_INSENSITIVE_ORDER.compare(a, b);
		return cmp != 0 ? cmp : a.compareTo(b);
	};

	/**
	 * 从若干候选来源收集结果，去重、按档位分组排序后写入 {@code out}。
	 * <p>
	 * 与 {@code prefix} 完全相同的名字不列出：它已经是用户敲完的样子，
	 * 选中它等于什么都没做。
	 */
	public static void collect(Seq<String> out, String prefix, Iterable<?>... sources) {
		out.clear();
		Seq<Seq<String>> buckets = Seq.with(new Seq<String>(), new Seq<String>(), new Seq<String>());
		ObjectSet<String> seen = new ObjectSet<>();

		for (Iterable<?> source : sources) {
			for (Object item : source) {
				if (item == null) continue;
				String name = item.toString();
				if (name.isEmpty() || name.equals(prefix) || !seen.add(name)) continue;

				int rank = rank(name, prefix);
				if (rank == NO_MATCH) continue;
				buckets.get(rank).add(name);
			}
		}

		for (Seq<String> bucket : buckets) {
			bucket.sort(ORDER);
			out.addAll(bucket);
		}
	}
}
