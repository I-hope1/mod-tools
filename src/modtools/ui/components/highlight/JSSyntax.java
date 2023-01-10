package modtools.ui.components.highlight;

import arc.graphics.Color;
import arc.struct.*;
import modtools.ui.components.area.TextAreaTable;

import java.util.Objects;
import java.util.regex.Pattern;

public class JSSyntax extends Syntax {
	public static Pattern
			// keywordP = Pattern.compile("\\b(break|c(?:ase|atch|onst|ontinue)|d(?:efault|elete|o)|else|f(?:inally|or|unction)|i[fn]|instranceof|let|new|return|switch|this|t(?:hrow|ry|ypeof)|v(?:ar|oid)|w(?:hile|ith)|yield)\\b", Pattern.COMMENTS),
			// 数字和true|false
			numberP = Pattern.compile("\\b([+-]?\\d+(?:\\.\\d*)?(?:[Ee]\\d+)?)\\b"),
			commentP = Pattern.compile("(//.*|/\\*[\\s\\S]*?\\*/|/\\*[^(*/)]*$)"),
	/**
	 * 我也不知道为什么这么慢
	 **/
	functionsP = Pattern.compile("([a-z_$][\\w$]*)\\s*\\(", Pattern.CASE_INSENSITIVE),

	objectsP = Pattern.compile("\\b(null|undefined|true|false|arguments)\\b", Pattern.COMMENTS)
			// others = Pattern.compile("([a-z$]+)", Pattern.CASE_INSENSITIVE)
			;
	public static Seq<Pattern> patternSeq = Seq.with(
			// whiteSpaceP, stringP, keywordP, numberP, commentP,
			// bracketsP, operatCharP/*, functionsP*/, objectsP
	);
	public static Seq<Color> colorSeq = Seq.with(
			Color.clear, stringC, keywordC, numberC, commentC,
			bracketsC, operatCharC/*, functionsC*/, objectsC
	);

	public JSSyntax(TextAreaTable table) {
		super(table);
	}

	/*public JSSyntax() {

	}*/

	/*static JsonValue
			keywords = reader.parse("{\"b\":{\"r\":{\"e\":{\"a\":{\"k\":false}}}},\"c\":{\"a\":{\"s\":{\"e\":false},\"t\":{\"c\":{\"h\":false}}},\"o\":{\"n\":{\"s\":{\"t\":false},\"t\":{\"i\":{\"n\":{\"u\":{\"e\":false}}}}}}},\"d\":{\"e\":{\"f\":{\"a\":{\"u\":{\"l\":{\"t\":false}}}},\"l\":{\"e\":{\"t\":{\"e\":false}}}},\"o\":false},\"e\":{\"l\":{\"s\":{\"e\":false}}},\"f\":{\"i\":{\"n\":{\"a\":{\"l\":{\"l\":{\"y\":false}}}}},\"o\":{\"r\":false},\"u\":{\"n\":{\"c\":{\"t\":{\"i\":{\"o\":{\"n\":false}}}}}}},\"i\":{\"f\":false,\"n\":{\"s\":{\"t\":{\"r\":{\"a\":{\"n\":{\"c\":{\"e\":{\"o\":{\"f\":false}}}}}}}}}},\"l\":{\"e\":{\"t\":false}},\"n\":{\"e\":{\"w\":false}},\"r\":{\"e\":{\"t\":{\"u\":{\"r\":{\"n\":false}}}}},\"s\":{\"w\":{\"i\":{\"t\":{\"c\":{\"h\":false}}}}},\"t\":{\"h\":{\"i\":{\"s\":false},\"r\":{\"o\":{\"w\":false}}},\"r\":{\"y\":false},\"y\":{\"p\":{\"e\":{\"o\":{\"f\":false}}}}},\"v\":{\"a\":{\"r\":false},\"o\":{\"i\":{\"d\":false}}},\"w\":{\"h\":{\"i\":{\"l\":{\"e\":false}}},\"i\":{\"t\":{\"h\":false}}},\"y\":{\"i\":{\"e\":{\"l\":{\"d\":false}}}}}"),
			objects = reader.parse("{\"n\":{\"u\":{\"l\":{\"l\":false}}},\"u\":{\"n\":{\"d\":{\"e\":{\"f\":{\"i\":{\"n\":{\"e\":{\"d\":false}}}}}}}},\"t\":{\"r\":{\"u\":{\"e\":false}}},\"f\":{\"a\":{\"l\":{\"s\":{\"e\":false}}}},\"a\":{\"r\":{\"g\":{\"u\":{\"m\":{\"e\":{\"n\":{\"t\":{\"s\":false}}}}}}}}}");*/

	// static IntMap<?>
	// keywordMap = IntMap.of('b', IntMap.of('r', IntMap.of('e', IntMap.of('a', IntMap.of('k', null)))), 'c', IntMap.of('a', IntMap.of('s', IntMap.of('e', null), 't', IntMap.of('c', IntMap.of('h', null))), 'o', IntMap.of('n', IntMap.of('s', IntMap.of('t', null), 't', IntMap.of('i', IntMap.of('n', IntMap.of('u', IntMap.of('e', null))))))), 'd', IntMap.of('e', IntMap.of('f', IntMap.of('a', IntMap.of('u', IntMap.of('l', IntMap.of('t', null)))), 'l', IntMap.of('e', IntMap.of('t', IntMap.of('e', null)))), 'o', null), 'e', IntMap.of('l', IntMap.of('s', IntMap.of('e', null))), 'f', IntMap.of('i', IntMap.of('n', IntMap.of('a', IntMap.of('l', IntMap.of('l', IntMap.of('y', null))))), 'o', IntMap.of('r', null), 'u', IntMap.of('n', IntMap.of('c', IntMap.of('t', IntMap.of('i', IntMap.of('o', IntMap.of('n', null))))))), 'i', IntMap.of('f', null, 'n', IntMap.of('s', IntMap.of('t', IntMap.of('r', IntMap.of('a', IntMap.of('n', IntMap.of('c', IntMap.of('e', IntMap.of('o', IntMap.of('f', null)))))))))), 'l', IntMap.of('e', IntMap.of('t', null)), 'n', IntMap.of('e', IntMap.of('w', null)), 'r', IntMap.of('e', IntMap.of('t', IntMap.of('u', IntMap.of('r', IntMap.of('n', null))))), 's', IntMap.of('w', IntMap.of('i', IntMap.of('t', IntMap.of('c', IntMap.of('h', null))))), 't', IntMap.of('h', IntMap.of('i', IntMap.of('s', null), 'r', IntMap.of('o', IntMap.of('w', null))), 'r', IntMap.of('y', null), 'y', IntMap.of('p', IntMap.of('e', IntMap.of('o', IntMap.of('f', null))))), 'v', IntMap.of('a', IntMap.of('r', null), 'o', IntMap.of('i', IntMap.of('d', null))), 'w', IntMap.of('h', IntMap.of('i', IntMap.of('l', IntMap.of('e', null))), 'i', IntMap.of('t', IntMap.of('h', null))), 'y', IntMap.of('i', IntMap.of('e', IntMap.of('l', IntMap.of('d', null))))),
	// objectMap = IntMap.of('n', IntMap.of('u', IntMap.of('l', IntMap.of('l', null))), 'u', IntMap.of('n', IntMap.of('d', IntMap.of('e', IntMap.of('f', IntMap.of('i', IntMap.of('n', IntMap.of('e', IntMap.of('d', null)))))))), 't', IntMap.of('r', IntMap.of('u', IntMap.of('e', null))), 'f', IntMap.of('a', IntMap.of('l', IntMap.of('s', IntMap.of('e', null)))), 'a', IntMap.of('r', IntMap.of('g', IntMap.of('u', IntMap.of('m', IntMap.of('e', IntMap.of('n', IntMap.of('t', IntMap.of('s', null)))))))));


	/*static {
		StringBuffer sb = new StringBuffer();
		append(sb, objects);
		Log.info(sb);
	}

	static void append(StringBuffer sb, JsonValue value) {
		sb.append("IntMap.of(");
		StringJoiner sj = new StringJoiner(",");
		for (JsonValue entry = value; entry != null; entry = entry.next) {
			sj.add("'" + entry.name + "'");
			if (entry.child == null) sj.add("null");
			else {
				StringBuffer sb2 = new StringBuffer();
				append(sb2, entry.child);
				sj.add(sb2);
			}
		}
		sb.append(sj);
		sb.append(")");
	}*/


	Color defalutColor = Color.white;
	char c, lastChar;
	int len;

	public DrawTask task = null;
	public DrawTask[] taskArr = new DrawTask[]{
			new DrawString(stringC),
			new DrawSymol(brackets, bracketsC),
			new DrawSymol(operats, operatCharC),
			new DrawComment(commentC),
			// new DrawWord(keywordMap, keywordC),
			// new DrawWord(objectMap, objectsC),
			new DrawToken(),
			new DrawNumber(numberC),
	};


	public void drawDefText(int start, int max) {
		area.font.setColor(defalutColor);
		area.drawMultiText(displayText, start, max);
	}

	void reset() {
		if (task != null) {
			task.reset();
		}
		task = null;
	}


	public void highlightingDraw(String displayText) {
		this.displayText = displayText;
		reset();
		// String result;
		for (DrawTask drawTask : taskArr) {
			drawTask.init();
		}
		int lastIndex = 0;
		len = displayText.length();
		lastChar = '\n';
		out:
		for (int i = 0; i < len; i++, lastChar = c) {
			c = displayText.charAt(i);

			if (task == null) {
				for (DrawTask drawTask : taskArr) {
					if (drawTask.draw(i)) {
						task = drawTask;
						if (task.isFinished()) {
							task.drawText(i);
							reset();
							lastIndex = i + 1;
							continue out;
						}
						break;
					}
					drawTask.reset();
				}
			} else if (task.draw(i)) {
				if (task.isFinished()) {
					task.drawText(i);
					reset();
					lastIndex = i + 1;
				}
			} else {
				reset();
			}
			if (task == null) {
				if (lastIndex < i + 1) {
					drawDefText(lastIndex, i + 1);
					lastIndex = i + 1;
				}
			}
		}
		if (task != null && task.crazy) {
			task.drawText(len - 1);
			reset();
		} else if (lastIndex < len) {
			drawDefText(lastIndex, len);
		}
	}

	public abstract class DrawTask {
		final Color color;
		boolean crazy;
		int lastIndex;

		public DrawTask(Color color, boolean crazy) {
			this.color = color;
			this.crazy = crazy;
		}

		public DrawTask(Color color) {
			this(color, false);
		}

		/**
		 * 循环开始时，执行
		 */
		void init() {}

		/**
		 * 渲染结束（包括失败）时，执行
		 */
		void reset() {
			lastIndex = -1;
		}

		abstract boolean isFinished();

		abstract boolean draw(int i);

		public void drawText(int i) {
			if (lastIndex == -1) return;
			area.font.setColor(color);
			area.drawMultiText(displayText, lastIndex, i + 1);
		}

		public boolean nextIs(int i, char c) {
			return i + 1 < len && c == displayText.charAt(i + 1);
		}
	}

	ObjectMap<Color, ObjectSet<String>> TOKEN_MAP = ObjectMap.of(
			keywordC, ObjectSet.with(
					"break", "case", "catch", "const", "continue",
					"default", "delete", "do", "else",
					"finally", "for", "function", "if",
					"instranceof", "let", "new", "return", "switch",
					"this", "throw", "try", "typeof", "var",
					"void", "while", "with",
					"yield"
			),
			objectsC, ObjectSet.with(
					"null", "undefined", "true", "false", "arguments"
			)
	);

	class DrawToken extends DrawTask {
		// IntMap<?>[] total;
		// IntMap<?>[] current;
		boolean begin = false, finished;

		public DrawToken() {
			super(new Color());
		}

		void reset() {
			super.reset();
			// System.arraycopy(total, 0, current, 0, total.length);
			finished = false;
			begin = false;
		}

		void init() {
			lastToken = null;
		}

		boolean isFinished() {
			return finished;
		}

		String lastToken;

		void draw(String token) {
			color.set(defalutColor);

			// Log.info(token);
			TOKEN_MAP.each((c, m) -> {
				if (!finished && m.contains(token)) {
					color.set(c);
					finished = true;
				}
			});

			if (Objects.equals(lastToken, "function")) {
				color.set(functionsC);
				finished = true;
			}
			lastToken = token;
		}

		boolean draw(int i) {
			// if (!current.containsKey(c)) return false;
			if (!begin && !(isWordBreak(lastChar) && !isWordBreak(c))) return false;
			if (!begin) begin = true;
			if (lastIndex == -1) lastIndex = i;

			if (i + 1 < len) {
				char nextC = displayText.charAt(i + 1);
				boolean valid = !isWordBreak(nextC);
				if (!valid) {
					draw(displayText.substring(lastIndex, i + 1));
					return finished;
				}
				return true;
			} else {
				draw(displayText.substring(lastIndex, i + 1));
				return finished;
			}
		}
	}

	/*class DrawWord extends DrawTask {
		IntMap<?> total;
		IntMap<?> current;
		boolean begin = false;

		DrawWord(IntMap<?> total, Color color) {
			super(color);
			this.total = current = total;
		}

		void reset() {
			super.reset();
			current = total;
			lastIndex = -1;
			begin = false;
		}

		boolean isFinished() {
			return current == total;
		}

		boolean draw(int i) {
			if (!current.containsKey(c)) return false;
			if (!begin && !isWordBreak(lastChar)) return false;
			begin = true;
			if (lastIndex == -1) lastIndex = i;
			current = (IntMap<?>) current.get(c);
			if (current == null) {
				if (i + 1 < len && !isWordBreak(displayText.charAt(i + 1))) return false;
				// result = displayText.substring(lastIndex, i);
				current = total;
				// Log.info("ok");
				return true;
			}

			if (i + 1 < len) {
				char nextC = displayText.charAt(i + 1);
				return 'A' <= nextC && nextC <= 'z';
			}
			return true;
		}
	}*/

	class DrawComment extends DrawTask {

		public DrawComment(Color color) {
			super(color, true);
		}

		@Override
		void reset() {
			super.reset();
			body = false;
			finished = false;
		}

		@Override
		boolean isFinished() {
			return finished;
		}

		private boolean finished, body, multi;

		@Override
		boolean draw(int i) {
			if (body) {
				if (multi ? lastChar == '*' && c == '/' : c == '\n' || i + 1 >= len) {
					finished = true;
				}
				return true;
			}
			if (c == '/' && i + 1 < len) {
				char next = displayText.charAt(i + 1);
				if (next == '*' || next == '/') {
					lastIndex = i;
					multi = next == '*';
					body = true;
					return true;
				}
			}
			return false;
		}
	}

	class DrawString extends DrawTask {

		public DrawString(Color color) {
			super(color, true);
		}

		@Override
		void reset() {
			super.reset();
			leftQuote = rightQuote = false;
		}

		@Override
		boolean isFinished() {
			return rightQuote;
		}

		boolean leftQuote, rightQuote;
		char quote;

		@Override
		boolean draw(int i) {
			if (!leftQuote) {
				if (c == '\'' || c == '"' || c == '`') {
					quote = c;
					leftQuote = true;
					lastIndex = i;
					return true;
				} else {
					return false;
				}
			}
			if (quote == c || (c == '\n' && quote != '`')) {
				rightQuote = true;
				leftQuote = false;
				return true;
			}
			return true;
		}
	}

	class DrawNumber extends DrawTask {
		public DrawNumber(Color color) {
			super(color);
		}

		void reset() {
			super.reset();
			hasSign = false;
			hasE = false;
			hasPoint = false;
			finished = false;
			crazy = false;
			// signIndex = -1;
		}

		boolean isFinished() {
			return finished;
		}

		boolean finished = false;
		boolean hasSign, hasE, hasPoint;
		// int signIndex;

		boolean isNumber(char c) {
			return 48 <= c && c <= 57;
		}

		@Override
		boolean draw(int i) {
			if (!hasSign && (c == '+' || c == '-')) {
				if (!hasE) lastIndex = i;
				hasSign = true;
				// signIndex = i;
				return true;
			}
			if (!hasE && c == '.') {
				if (hasPoint) {
					finished = true;
					return true;
				}
				hasPoint = true;
				if (!hasSign) {
					hasSign = true;
					lastIndex = i;
				}
				return true;
			}
			if (!hasE && (c == 'E' || c == 'e')) {
				hasE = true;
				hasSign = false;
				return true;
			}
			if (isNumber(c)) {
				if (!hasE && !isWordBreak(lastChar) && !hasSign) {
					return false;
				}
				crazy = true;
				if (!hasSign && !hasE) {
					hasSign = true;
					lastIndex = i;
				}
				if (i + 1 >= len || isWordBreak(displayText.charAt(i + 1))) {
					finished = true;
				}
				return true;
			}

			return false;
		}
	}

	class DrawSymol extends DrawTask {
		final IntMap<Object> symbols;

		public DrawSymol(IntMap<Object> map, Color color) {
			super(color);
			symbols = map;
		}

		@Override
		boolean isFinished() {
			return true;
		}

		@Override
		boolean draw(int i) {
			if (symbols.containsKey(c)) {
				lastIndex = i;
				return true;
			}
			return false;
		}
	}

	static IntMap<Object> operats = new IntMap<>();
	static IntMap<Object> brackets = new IntMap<>();

	static {
		String s;
		s = "~|,+-=*/<>!%^&;.";
		for (int i = 0, len = s.length(); i < len; i++) {
			operats.put(s.charAt(i), null);
		}
		s = "()[]{}";
		for (int i = 0, len = s.length(); i < len; i++) {
			brackets.put(s.charAt(i), null);
		}
	}
}
