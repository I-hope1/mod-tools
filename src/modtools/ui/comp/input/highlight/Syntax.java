package modtools.ui.comp.input.highlight;

import arc.graphics.Color;
import arc.struct.*;
import arc.util.Tmp;
import mindustry.graphics.Pal;

import java.util.Objects;

/**
 * 用于控制渲染，当然你也可以解析文本
 */
public class Syntax {
	public static final Color
	 c_string      = new Color(0xCE9178_FF),
	 c_keyword     = new Color(0x569CD6_FF),
	 c_number      = new Color(0xB5CEA8_FF),
	 c_comment     = new Color(0x6A9955_FF),
	 c_brackets    = new Color(0xFFD704_FF),
	 c_operateChar = Pal.accentBack,
	 c_functions   = Color.sky,
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

	/**
	 * 判断指定index对应的char，是否为单词边界
	 */
	public boolean isWordBreak(int i) {
		return isWordBreak(displayText.charAt(i));
	}

	/**
	 * 判断指定char，是否为单词边界
	 * IMPROVEMENT: 使用 Character 类的方法和 Unicode 表示法以提高可读性。
	 * 一个字符如果不是字母、数字、CJK表意文字、下划线或美元符号，则被视作单词边界。
	 */
	public boolean isWordBreak(char c) {
		if (Character.isLetterOrDigit(c) || c == '$' || c == '_') {
			return false;
		}
		// CJK Unified Ideographs range (中日韩统一表意文字)
		if (c >= '\u4E00' && c <= '\u9FFF') {
			return false;
		}
		return true;
	}


	/**
	 * 用于文本解析
	 */
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

	boolean hasChanged;
	boolean newLine;

	public void highlightingDraw(String displayText) {
		hasChanged = !Objects.equals(this.displayText, displayText);
		this.displayText = displayText;
		cursorTask = null;
		cTask = null;
		lastTask = null;
		reset();

		outerTask.init();
		for (DrawTask drawTask : taskArr) {
			drawTask.init();
		}
        // MODIFICATION: 确保每次重新绘制时，lastToken 状态被清空
        if (drawToken != null) {
            drawToken.lastToken = null;
        }

		int lastIndex = 0;
		len = displayText.length();
		lastChar = '\n';
		outer:
		for (int i = 0; i < len; i++, lastChar = c) {
			c = displayText.charAt(i);
			newLine = c == '\n';

			if (i <= drawable.cursor()) cursorTask = cTask;
			if (outerTask != null) outerTask.draw(i);

			if (cTask == null) {
				for (DrawTask drawTask : taskArr) {
					if (!drawTask.draw(i)) {
						drawTask.reset();
						continue;
					}
					cTask = drawTask;
					if (cTask.isFinished() || cTask.withdraw) {
						lastIndex = drawAndReset(i);
						continue outer;
					}
					break;
				}
			} else if (cTask.draw(i)) {
				if (cTask.isFinished() || cTask.withdraw) {
					lastIndex = drawAndReset(i);
				}
				continue;
			} else {
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

	private int drawAndReset(int i) {
		cTask.drawText(i);
		if (outerTask != null) outerTask.after(i);
		if (!cTask.withdraw) {
			reset();
		} else cTask.withdraw = false;
		return i + 1;
	}

	public String      displayText;
	public Color       defaultColor = Color.white;
	char  c, lastChar;
	int   len;
	public DrawTask      cursorTask, cTask, lastTask;
	public DrawOuterTask outerTask;
	public DrawTask[]    taskArr      = {};

	public class DrawOuterTask extends DrawTask {
		public DrawOuterTask() { super(defaultColor); }
		final void reset() {}
		final boolean isFinished() { return false; }
		public boolean draw(int i) { return false; }
		public void after(int i) {}
		public final void drawText(int i) {}
	}

	public class DrawToken extends DrawTask {
		public      boolean     begin          = false, finished;
		public      TokenDraw[] tokenDraws;
		public      int         lastTokenIndex = -1;
		public      String      lastToken, token;

		public DrawToken(TokenDraw... tokenDraws) {
			super(new Color());
			this.tokenDraws = tokenDraws;
		}

		void reset() {
			super.reset();
			finished = false;
			begin = false;
		}

		void init() {
			super.init();
			// lastToken will be managed by setColor and highlightingDraw
			token = null;
		}

		boolean isFinished() { return finished; }

		void setColor(int from, int to) {
			setColor(displayText.substring(from, to));
			lastTokenIndex = to;
		}

		void setColor(String token) {
            // --- FIX: CRITICAL LOGIC CHANGE ---
            // First, persist the previous token.
            this.lastToken = this.token;
            // Then, update to the new token.
			this.token = token;
            // ------------------------------------

			color.set(defaultColor);
			Color newColor;
			for (TokenDraw draw : tokenDraws) {
				if ((newColor = draw.draw(this)) == null) continue;
				color.set(newColor);
				break;
			}
			finished = true;
            // The following line is now redundant and incorrect.
			// lastToken = token;
		}

		boolean draw(int i) {
			if (!(begin || (isWordBreak(lastChar) && !isWordBreak(c)))) return false;
			if (!begin) {
				begin = true;
				lastIndex = i;
			}
			boolean isLastCharInText = (i + 1 >= len);
			if (isLastCharInText || isWordBreak(displayText.charAt(i + 1))) {
				setColor(lastIndex, i + 1);
				return finished;
			} else {
				return true;
			}
		}
	}

	public class DrawSymbol extends DrawTask {
		public final IntSet symbols;
		public       char   lastSymbol = '\0';

		public DrawSymbol(IntSet map, Color color) {
			super(color);
			symbols = map;
		}
		boolean isFinished() { return true; }
		void init() {
			super.init();
			lastSymbol = '\0';
			lastIndex = -1;
		}
		boolean draw(int i) {
			if (!symbols.contains(c)) return false;
			lastSymbol = c;
			lastIndex = i;
			return true;
		}
	}

	public class DrawComment extends DrawTask {
		public DrawComment(Color color) { super(color, true); }
		void reset() {
			super.reset();
			body = false;
			finished = false;
		}
		boolean isFinished() { return finished; }
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
		public static final IntMap<Boolean> chars = IntMap.of('\'', false, '"', false, '`', false);
		public static final IntSet escapeSet = IntSet.with('n', 'b', 'c', 'r', 't', 'f', '\\', '"', '\'', '`');
		public              int               textColor, escapeColor;
		public DrawString(Color color) { this(color, chars); }
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
			escape = inUnicode = false;
		}
		@Override
		boolean isFinished() { return rightQuote; }
		boolean leftQuote, rightQuote;
		char    quote;
		boolean escape;
		boolean inUnicode;
		int     lastUnicodeIndex;
		@Override
		boolean draw(int i) {
			if (!leftQuote) return searchQuote(i);
			if (inUnicode) {
				color.set(escapeColor);
				withdraw = true;
				if ("0123456789abcdefABCDEF".indexOf(c) == -1) {
					color.set(c_error);
					inUnicode = false;
				} else if (i - lastUnicodeIndex == 4) {
					inUnicode = false;
					lastUnicodeIndex = -1;
				}
				checkEscape(i);
				return true;
			} else if (escape) {
				if (lastChar == '\\') {
					color.set(escapeColor);
					withdraw = true;
					escape = false;
					if (c == 'u') {
						lastUnicodeIndex = i;
						inUnicode = true;
						return true;
					} else if (!escapeSet.contains(c) && !Character.isDigit(c)) color.set(c_error);
					checkEscape(i);
				}
				return true;
			}
			if (color.rgba() != textColor) color.set(textColor);
			if (checkEscape(i)) return true;
			if ((lastChar != '\\' && quote == c) || (c == '\n' && !map.get(quote))) rightQuote = true;
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
			} else return false;
		}
	}

	public interface TokenDraw {
		Color draw(DrawToken task);
	}

	public abstract class DrawTask {
		public final Color   color;
		public       boolean crazy;
		public       boolean withdraw  = false;
		public       int     lastIndex;
		public DrawTask(Color color, boolean crazy) {
			this.color = color;
			this.crazy = crazy;
		}
		public DrawTask(Color color) { this(color, false); }
		void init() {}
		void reset() { withdraw = false; }
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

	public static class VirtualString {
		public String text;
		public Color  color = Color.lightGray;
		public int    index;
		public VirtualString() {}
	}
}