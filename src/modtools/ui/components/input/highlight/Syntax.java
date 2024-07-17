package modtools.ui.components.input.highlight;

import arc.graphics.Color;
import arc.struct.*;
import arc.util.*;
import mindustry.graphics.Pal;

/** 用于控制渲染，当然你也可以解析文本 */
public class Syntax {
	public static final Color
	 c_string      = new Color(0xCE9178_FF),
	 c_keyword     = new Color(0x569CD6_FF),
	 c_number      = new Color(0xB5CEA8_FF),
	 c_comment     = new Color(0x6A9955_FF),
	 c_brackets    = new Color(0xFFD704_FF),
	 c_operateChar = Pal.accentBack,
	 c_functions   = Color.sky,//Color.valueOf("#ae81ff")
	 c_objects     = new Color(0x66D9EF_FF),
	 c_map         = new Color(0xADDE68_FF),
	 c_error       = Color.red;

	public SyntaxDrawable drawable;
	public DrawToken      drawToken;

	public Syntax(SyntaxDrawable drawable) {
		/* 为null时文本解析，不渲染 */
		if (drawable == null) return;
		this.drawable = drawable;
	}

	/** 判断指定index对应的char，是否为单词边界 */
	public boolean isWordBreak(int i) {
		return isWordBreak(displayText.charAt(i));
	}

	/** 判断指定char，是否为单词边界 */
	public boolean isWordBreak(char c) {
		return !((48 <= c && c <= 57) || (65 <= c && c <= 90)
		         || (97 <= c && c <= 122) || (19968 <= c && c <= 40869)
		         || c == '$' || c == '_');
	}

	/** 用于文本解析 */
	public interface DrawDefCons {
		void get(int start, int max);
	}
	public DrawDefCons drawDefCons;
	public void drawDefText(int start, int max) {
		drawText0(drawable == null ? null : defaultColor, start, max);
	}
	public void drawText0(Color color, int start, int max) {
		if (start == -1) return;
		if (drawable == null) {
			if (drawDefCons != null) drawDefCons.get(start, max);
			return;
		}
		if (start == max) return;
		drawable.font().getColor().set(color).mulA(drawable.alpha());
		drawable.drawMultiText(displayText, start, max);
	}


	void reset() {
		if (cTask == null) return;
		cTask.reset();
		lastTask = cTask;
		cTask = null;
	}

	public void highlightingDraw(CharSequence displayText) {
		this.displayText = displayText;
		cursorTask = null;
		cTask = null;
		reset();
		// String result;
		for (DrawTask drawTask : taskArr) {
			drawTask.init();
		}
		int lastIndex = 0;
		len = displayText.length();
		lastChar = '\n';
		outer:
		for (int i = 0; i < len; i++, lastChar = c) {
			c = displayText.charAt(i);

			if (i < drawable.cursor()) cursorTask = cTask;
			if (cTask == null) {
				for (DrawTask drawTask : taskArr) {
					if (!drawTask.draw(i)) {
						drawTask.reset();
						continue;
					}
					cTask = drawTask;
					// 单字符高亮
					if (cTask.isFinished() || cTask.withdraw) {
						lastIndex = drawAndReset(i);
						continue outer;
					}
					break;
				}
			} else /* 继续解析直到结束 */ if (cTask.draw(i)) {
				if (cTask.isFinished() || cTask.withdraw) {
					lastIndex = drawAndReset(i);
				}
			}/* 结束就重置 */ else {
				reset();
			}
			if (cTask == null) {
				if (lastIndex >= i + 1) continue;

				drawDefText(lastIndex, i + 1);
				lastIndex = i + 1;
			}
		}
		if (cTask != null && cTask.crazy) {
			cTask.drawText(len - 1);
			reset();
		} else if (lastIndex < len) {
			drawDefText(lastIndex, len);
		}
	}
	/** 返回下一个index，{@link DrawTask#withdraw}时不{@link #reset()} */
	private int drawAndReset(int i) {
		cTask.drawText(i);
		if (!cTask.withdraw) reset();
		else cTask.withdraw = false;
		return i + 1;
	}

	/* protected Vec2 getCursorPos() {
		return getRelativePos(drawable.cursor());
	}
	protected Vec2 getRelativePos(int pos) {
		return Tmp.v3.set(drawable.getRelativeX(pos), drawable.getRelativeY(pos));
	} */


	public CharSequence displayText;

	public Color defaultColor = Color.white;
	char c, lastChar;
	int len;

	/**
	 * 当前任务
	 */
	public DrawTask cursorTask,
	 cTask, lastTask; // default for null.
	/**
	 * 所有的任务
	 */
	public DrawTask[] taskArr = {};

	public class DrawToken extends DrawTask {
		// IntMap<?>[] total;
		// IntMap<?>[] current;
		public boolean begin = false, finished;
		public TokenDraw[] tokenDraws;
		public int         lastTokenIndex = -1, currentIndex = -1;
		public CharSequence lastToken, token;

		public DrawToken(TokenDraw... tokenDraws) {
			super(new Color());
			this.tokenDraws = tokenDraws;
		}

		void reset() {
			super.reset();
			// System.arraycopy(total, 0, current, 0, total.length);
			lastTokenIndex = -1;
			finished = false;
			begin = false;
		}

		void init() {
			super.init();
			lastToken = null;
			token = null;
		}

		boolean isFinished() {
			return finished;
		}


		void setColor(int from, int to) {
			currentIndex = to;
			setColor(displayText.subSequence(from, to));
		}

		void setColor(CharSequence token) {
			this.token = token;
			color.set(defaultColor);
			// Log.info(token);
			Color newColor;
			for (TokenDraw draw : tokenDraws) {
				/* 只要draw结果为null，就下一个 */
				if ((newColor = draw.draw(this)) == null) continue;

				color.set(newColor);
				break;
			}
			finished = true;
			lastTokenIndex = lastIndex;
			lastToken = token;
		}

		boolean draw(int i) {
			// if (!current.containsKey(c)) return false;
			if (!(begin || (isWordBreak(lastChar) && !isWordBreak(c)))) return false;
			if (!begin) begin = true;
			if (lastIndex == -1) lastIndex = i;

			/* 判断下一个index是否越界 */
			if (++i < len) {
				/* 判断下一个index是否越界 */
				if (isWordBreak(i)) {
					setColor(lastIndex, i);
					return finished;
				}
				return true;
			} else {
				setColor(lastIndex, displayText.length());
				return finished;
			}
		}
	}

	public class DrawSymbol extends DrawTask {
		public final IntSet symbols;
		public       char   lastSymbol;

		public DrawSymbol(IntSet map, Color color) {
			super(color);
			symbols = map;
		}

		boolean isFinished() {
			return true;
		}
		void init() {
			super.init();
			lastSymbol = '\0';
		}

		boolean draw(int i) {
			if (!symbols.contains(c)) return false;
			lastSymbol = c;
			lastIndex = i;
			return true;
		}
	}

	public class DrawComment extends DrawTask {

		public DrawComment(Color color) {
			super(color, true);
		}

		void reset() {
			super.reset();
			body = false;
			finished = false;
		}

		boolean isFinished() {
			return finished;
		}

		private boolean finished, body, multi;

		@Override
		boolean draw(int i) {
			if (body) {
				if (multi ? (lastChar == '*' && c == '/')
				 : (c == '\n' || i + 1 >= len)) {
					finished = true;
				}
				return true;
			}
			if (c != '/' || i + 1 >= len) return false;
			char next = displayText.charAt(i + 1);
			if (next != '*' && next != '/') return false;
			lastIndex = i;
			multi = next == '*';
			body = true;
			return true;
		}
	}


	public class DrawString extends DrawTask {
		/**
		 * <p>key(字符char，用于判断是否为字符串) -> value(boolean是否为多行)</p>
		 * mindustry目前的rhino都是单行
		 */
		public static final IntMap<Boolean> chars     = IntMap.of(
		 '\'', false,
		 '"', false,
		 '`', false
		);
		/** 可以转义的字符 */
		public static final IntSet          escapeMap = IntSet.with(
		 'n', 'b', 'c', 'r', 't', 'f', '\\',
		 '"', '\'', '`'
		);

		public int textColor, escapeColor;
		public DrawString(Color color) {
			this(color, chars);
		}
		public DrawString(Color color, IntMap<Boolean> chars) {
			super(new Color(), true);
			this.color.set(color);
			textColor = color.rgba();
			escapeColor = Tmp.c1.set(color).saturation(0.75f).rgba();
			map = chars;
		}

		public IntMap<Boolean> map;
		@Override
		void reset() {
			super.reset();
			color.set(textColor);
			leftQuote = rightQuote = false;
			escape = false;
		}

		@Override
		boolean isFinished() {
			return rightQuote /* 表示引号是否结束 */;
		}

		boolean leftQuote, rightQuote;
		char quote;

		boolean escape;
		@Override
		boolean draw(int i) {
			/* left边的引号  */
			if (!leftQuote) return searchQuote(i);

			if (escape) {
				if (lastChar == '\\') {
					color.set(escapeColor);
					withdraw = true;
					escape = false;
					// 判断是否可以转义
					if (!escapeMap.contains(c) && !Character.isDigit(c)) color.set(c_error);
					checkEscape(i);
				}
				return true;
			}
			/* 表示转义结束 */
			if (color.rgba() != textColor) {
				color.set(textColor);
			}
			if (checkEscape(i)) return true;

			if ((lastChar != '\\' && quote == c) || (c == '\n' && !map.get(quote))) {
				rightQuote = true;
			}
			return true;
		}

		private boolean checkEscape(int i) {
			if (nextIs(i, '\\')) {
				escape = true;
				withdraw = true;
				return true;
			}
			return false;
		}
		private boolean searchQuote(int i) {
			if (map.containsKey(c)) {
				quote = c;
				leftQuote = true;
				lastIndex = i;
				checkEscape(i);
				return true;
			} else {
				return false;
			}
		}
	}


	public interface TokenDraw {
		/** @return Color 如果为null，则不渲染 */
		Color draw(DrawToken task);
	}

	public abstract class DrawTask {
		public final Color   color;
		public       boolean crazy;

		/* 运行时变量 */
		/** 是否为暂时退出，draw包含本次字符 */
		public boolean withdraw = false;
		/** 这个为当前char或String的左边 */
		public int     lastIndex;

		public DrawTask(Color color, boolean crazy) {
			this.color = color;
			this.crazy = crazy;
		}

		public DrawTask(Color color) {
			this(color, false);
		}

		/** 循环开始时，执行 */
		void init() {
		}

		/** 渲染结束（包括失败）时，执行 */
		void reset() {
			lastIndex = -1;
			withdraw = false;
		}

		abstract boolean isFinished();

		abstract boolean draw(int i);

		public void drawText(int i) {
			drawText0(color, lastIndex, i + 1);
			lastIndex = i + 1;
		}

		public boolean nextIs(int currentIndex, char expectChar) {
			return currentIndex + 1 < len && expectChar == displayText.charAt(currentIndex + 1);
		}
	}
}
