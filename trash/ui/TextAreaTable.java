
package modtools.ui.comp;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.*;
import arc.scene.Element;
import arc.scene.Scene;
import arc.scene.event.ChangeListener.ChangeEvent;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.style.Drawable;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextArea;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import arc.util.*;
import arc.util.pooling.Pools;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.ui.MyFonts;

import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * 一个文本域（多行文本输入框）(优化很烂，不建议使用)
 * 支持显示行数
 * 支持高亮显示
 * 支持滚动
 *
 * @author I hope...
 **/
public class TextAreaTable extends Table {
	private final MyTextArea area;
	public boolean readOnly = false;
	public static int numWidth = 13;

	public MyTextArea getArea() {
		return area;
	}

	public TextAreaTable(String text) {
		super(Tex.underline);

		area = new MyTextArea(text);
		area.setStyle(style);
		MyScrollPane pane = new MyScrollPane(area);
		area.trackCursor = pane::trackCursor;
		LinesShow linesShow = new LinesShow(area);
		Cell<?> cell = add(linesShow).growY().left();
		// fill().add(area);
		add(pane).grow();
		area.changed(() -> {
			// pane.trackCursor();
			Element last = Core.scene.getKeyboardFocus();
			cell.setElement(linesShow);
			if (last == area) Core.scene.setKeyboardFocus(area);
		});
		final String[] last = {area.getText()};
		Rect rect = new Rect();
		margin(0);
		update(() -> {
			if (last[0].equals(area.getText())) {
				last[0] = area.getText();
				area.fire(new ChangeEvent());
			}
			Element focus = Core.scene.getKeyboardFocus();
			if (focus == area) Core.scene.setScrollFocus(pane);
			rect.set(x, y, width, height);
			if (rect.contains(Core.input.mouse()) && visible && ((focus != null && isAscendantOf(focus)) || Core.scene.getScrollFocus() == pane))
				Core.scene.setKeyboardFocus(area);

			area.parentHeight = getHeight();
			area.setFirstLineShowing(0);
		});
	}

	public Boolf2<InputEvent, KeyCode> keyDonwB = null;
	public Boolf2<InputEvent, Character> keyTypedB = null;
	public Boolf2<InputEvent, KeyCode> keyUpB = null;

	public class MyScrollPane extends ScrollPane {
		public MyScrollPane(Element widget) {
			super(widget);
		}

		public void trackCursor() {
			Time.runTask(0f, () -> {
				int cursorLine = area.getCursorLine();
				int firstLineShowing = area.getRealFirstLineShowing();
				int lines = area.getRealLinesShowing();
				int max = firstLineShowing + lines;
				if (cursorLine <= firstLineShowing) {
					setScrollY(cursorLine * area.textHeight());
				}
				if (cursorLine > max) {
					setScrollY((cursorLine - lines) * area.textHeight());
				}
			});
		}

		@Override
		public void visualScrollY(float pixelsY) {
			area.scrollY = pixelsY;
			//			Log.info(pixelsY);
			super.visualScrollY(pixelsY);
		}
	}


	private static final Pattern
			strPattern = Pattern.compile("(([\"'`]).*?(?<!\\\\)\\2)"),
			keyword = Pattern.compile("\\b(if|else|while|for|break|continue|var|let|const|return|function|class|try|catch|throw|finally|in|this|of|switch|new)\\b"),
	// 数字和true|false
	numberP = Pattern.compile("(?<![\\w$])([+-]?\\d+(\\.\\d*)?([Ee]\\d+)?|true|false)(?![\\w$])"),
			comment = Pattern.compile("(//.*|/\\*[\\s\\S]*?\\*/|/\\*[^(*/)]*$)"),
			operatChar = Pattern.compile("([~|,+=*/\\-<>!]+)"),
			brackets = Pattern.compile("([\\[{()}\\]])"),
			functions = Pattern.compile("([a-zA-Z_$][\\w$]*)(?=\\()"),
	// 内置对象
	objects = Pattern.compile("\\b(null|undefined)\\b")/*,
			others = Pattern.compile("([\\s\\S])")*/
			// ,whitespace = Pattern.compile("(\\s+)")
			;

	public static Seq<Pattern> patternSeq = Seq.with(strPattern, keyword, numberP, comment,
			brackets, operatChar, functions, objects/*, whitespace*/);
	public static Seq<MyMatcher> matcherSeq = new Seq<>();
	public static Seq<Color> colorSeq = Seq.with(
			Color.valueOf("#ce9178"), // str
			Color.valueOf("#569cd6"), // keyword
			Color.valueOf("#b5cea8"), // number
			Color.valueOf("#6a9955"), // comment
			Color.valueOf("#ffd704"), // brackets
			Pal.accentBack, // operat
			Color.sky,// functions
			Color.valueOf("#66d9ef") // objects
			// Color.clear // whitespace
			// Color.valueOf("#ae81ff"), // functions
	);
	public boolean enableHighlighting = true;

	private static final Pattern startComment = Pattern.compile("\\s*//");

	public class MyTextArea extends TextArea {
		public float parentHeight = 0;
		public float scrollY = 0;
		public Runnable trackCursor = null;
		public float textHeight() {
			return textHeight;
		}

		public MyTextArea(String text) {
			super(text);
		}

		@Override
		public void setText(String str) {
			super.setText(str);
			fire(new ChangeEvent());
		}

		@Override
		public float getPrefHeight() {
			float prefHeight = textHeight * getLines();
			var style = getStyle();
			if (style.background != null) {
				prefHeight = Math.max(prefHeight + style.background.getBottomHeight() + style.background.getTopHeight(),
						style.background.getMinHeight());
			}
			return prefHeight + parentHeight / 2f;
		}

		public void setFirstLineShowing(int v) {
			firstLineShowing = v;
		}

		public IntSeq getLinesBreak() {
			return linesBreak;
		}

		public int getRealLinesShowing() {
			Drawable background = style.background;
			float availableHeight = parentHeight - (background == null ? 0 : background.getBottomHeight() + background.getTopHeight());
			return (int) Math.floor(availableHeight / textHeight);
		}

		public int getRealFirstLineShowing() {
			if (true) return (int) Math.floor(scrollY / textHeight);

			int firstLineShowing = 0;
			if (cursorLine != firstLineShowing) {
				int step = cursorLine >= firstLineShowing ? 1 : -1;
				while (firstLineShowing > cursorLine || firstLineShowing + linesShowing - 1 < cursorLine) {
					firstLineShowing += step;
				}
			}
			return firstLineShowing;
		}

		@Override
		protected void drawText(Font font, float x, float y) {
			boolean had = font.getData().markupEnabled;
			Color lastColor = font.getColor();
			font.getData().markupEnabled = false;

			if (enableHighlighting) {
				try {
					highlightingDraw(font, x, y);
				} catch (Exception e) {
					Log.err(e);
				}
			} else {
				font.setColor(Color.white);
				int firstLineShowing = getRealFirstLineShowing();
				int linesShowing = getRealLinesShowing() + 1;
				float offsetY = -firstLineShowing * textHeight;
				int max = (firstLineShowing + linesShowing) * 2;
				for (int i = firstLineShowing * 2; i < max && i < linesBreak.size; i += 2) {
					font.draw(displayText, x, y + offsetY, linesBreak.get(i), linesBreak.get(i + 1),
							0, Align.left, false).free();
					offsetY -= textHeight();
				}
			}
			font.setColor(lastColor);
			font.getData().markupEnabled = had;
		}

		/*public void highlightingDraw(Font font, float x, float y) {
			if (needsLayout()) return;
			int firstLineShowing = getRealFirstLineShowing();
			final float[] offsetY = {-firstLineShowing * font.getLineHeight()};
			int linesShowing = getRealLinesShowing() + 1;
			StringBuilder builder = new StringBuilder(displayText);
			for (int i = firstLineShowing * 2; i < (firstLineShowing + linesShowing) * 2 && i < linesBreak.size; i += 2) {
				try {
					int end = linesBreak.get(i + 1);
					if (displayText.charAt(end) == ' ')
						builder.replace(end, end + 1, "\n");
					// Log.debug(displayText.charAt(linesBreak.get(i + 1)) == 32);
				} catch (Exception ignored) {}
			}
			// Log.debug(displayText);

			Matcher m;
			String group;
			final float[] offsetX = new float[1];
			int lastIndex;
			matcherSeq.clear();
			// for (int i = firstLineShowing * 2; i < (firstLineShowing + linesShowing) * 2 && i < linesBreak.size; i += 2) {
			final int[] i = {firstLineShowing * 2};
			final int[] home = {linesBreak.get(i[0])};
			final int[] end = {linesBreak.get(i[0] + 1)};
			// String line = displayText.substring(home, end);
			matcherSeq.clear();
			for (var p : patternSeq) {
				matcherSeq.add(p.matcher(builder));
			}
			lastIndex = 0;
			offsetX[0] = x;

			Cons2<Integer, Integer> drawMultiText = (start, max) -> {
				// Log.debug("s: @, m: @, t: @", start, max, displayText.substring(start, max));
				// Log.debug("full: @", displayText.substring(start, max));
				for (end[0] = linesBreak.get(i[0] + 1); max > end[0]; end[0] = linesBreak.get(i[0] + 1)) {
					if (i[0] + 4 > linesBreak.size) {
						// offsetY[0] += font.getLineHeight();
						break;
					}
					i[0] += 2;
					font.draw(builder.substring(start, end[0]), offsetX[0], y + offsetY[0], 0, Align.left, false).free();
					offsetX[0] = x;
					offsetY[0] -= font.getLineHeight();
					start = end[0];
					// if (i + 2 > linesBreak.size) break main;
				}
				offsetX[0] = x + glyphPositions.get(start) - glyphPositions.get(linesBreak.get(i[0]));
				if (start < builder.length() && start < max && builder.charAt(start) == '\n') start++;
				font.draw(builder.substring(start, max), offsetX[0], y + offsetY[0], 0, Align.left, false).free();
			};
			while (true) {
				currentMatcher[0] = null;
				int res = -1;
				for (int j = 0, size = patternSeq.size; j < size; j++) {
					m = matcherSeq.get(j);
					if (m.find(lastIndex) && (currentMatcher[0] == null || m.start(1) < currentIndex[0])) {
						currentMatcher[0] = m;
						currentIndex[0] = m.start(1);
						res = j;
					}
				}
				if (currentMatcher[0] == null) break;

				currentColor[0] = colorSeq.get(res);
				group = currentMatcher[0].group(1);
				int index = currentIndex[0];
				font.setColor(Color.white);
				drawMultiText.get(lastIndex, index);
				lastIndex = index + group.length();

				font.setColor(currentColor[0]);
				drawMultiText.get(index, lastIndex);
				// currentMatcher[0] = null;
				// if (lastIndex > linesBreak.items[i]) offsetY -= font.getLineHeight();
			}
			font.setColor(Color.white);
			int max = builder.length();
			drawMultiText.get(lastIndex, max);
			// offsetY[0] -= font.getLineHeight();
			// }

			// glyphPositions.get(cursor) - glyphPositions.get(0) + fontOffset + style.font.getData().cursorX
			// font.draw(, x, y + offsetY, linesBreak.items[i], linesBreak.items[i + 1], 0, 8, false);
			Draw.color();
		}*/

		float offsetX, offsetY, baseOffsetX;
		int row, displayTextStart, displayTextEnd;
		Font font = null;

		public void highlightingDraw(Font font, float x, float y) {
			if (needsLayout()) return;
			this.font = font;
			baseOffsetX = offsetX = x;
			int firstLineShowing = getRealFirstLineShowing();
			offsetY = -firstLineShowing * textHeight + y;
			int linesShowing = getRealLinesShowing() + 1;
			row = firstLineShowing;

			displayTextStart = linesBreak.get(Math.min(firstLineShowing * 2, linesBreak.size - 1));
			displayTextEnd = linesBreak.get(Math.min((firstLineShowing + linesShowing) * 2, linesBreak.size) - 1);
			if (displayTextStart == displayTextEnd) return;
			String displayText = text.substring(displayTextStart, displayTextEnd);

			/*int lastIndex;
			Seq<RegExpResult> results = new Seq<>();
			// 匹配所有正则表达式
			for (int j = 0; j < patternSeq.size; j++) {
				m = patternSeq.get(j).matcher(displayText);
				Color color1 = colorSeq.get(j);

				while (m.find()) {
					var res = new RegExpResult();
					res.priority = j;
					res.color = color1;
					res.start = m.start(1);
					res.text = m.group(1);
					res.len = res.text.length();
					res.end = res.start + res.len;
					results.add(res);
				}
			}
			// 根据优先级排序
			results.sort((a, b) -> {
				if (a.start > b.start) return 1;
				else if (a.start < b.start) return -1;
				else return Integer.compare(a.priority, b.priority);
			});

			lastIndex = 0;
			for (var res : results) {
				if (lastIndex > res.start) continue;
				currentColor[0] = res.color;
				int index = currentIndex[0] = res.start;
				font.setColor(Color.white);
				drawMultiText(displayText, lastIndex, index);
				lastIndex = res.end;

				font.setColor(currentColor[0]);
				drawMultiText(displayText, index, lastIndex);
			}
			font.setColor(Color.white);
			drawMultiText(displayText, lastIndex, displayText.length());

			Draw.color();*/
			// for (int i = firstLineShowing * 2; i < (firstLineShowing + linesShowing) * 2 && i < linesBreak.size; i += 2) {
			// String line = displayText.substring(home, end);
			MyMatcher m;
			matcherSeq.clear();
			for (var p : patternSeq) {
				matcherSeq.add(new MyMatcher(p.matcher(displayText)));
			}
			String group;
			int lastIndex = 0;
			MyMatcher currentMatcher;
			int currentIndex = 0;
			Color currentColor;
			while (true) {
				currentMatcher = null;
				int res = -1;
				for (int j = 0, size = patternSeq.size; j < size; j++) {
					m = matcherSeq.get(j);
					if (m.find(lastIndex) && (currentMatcher == null || m.start() < currentIndex)) {
						currentMatcher = m;
						currentIndex = m.start();
						res = j;
					}
				}
				if (currentMatcher == null) break;

				currentColor = colorSeq.get(res);
				group = currentMatcher.group();
				font.setColor(Color.white);
				drawMultiText(displayText, lastIndex, currentIndex);
				lastIndex = currentIndex + group.length();

				font.setColor(currentColor);
				drawMultiText(displayText, currentIndex, lastIndex);
				// currentMatcher[0] = null;
				// if (lastIndex > linesBreak.items[i]) offsetY -= font.getLineHeight();
			}
			font.setColor(Color.white);
			drawMultiText(displayText, lastIndex, displayText.length());
			// offsetY[0] -= font.getLineHeight();
			// }

			// glyphPositions.get(cursor) - glyphPositions.get(0) + fontOffset + style.font.getData().cursorX
			// font.draw(, x, y + offsetY, linesBreak.items[i], linesBreak.items[i + 1], 0, 8, false);
			Draw.color();
		}

		public void drawMultiText(String text, int start, int max) {
			/*start -= displayTextStart;
			max -= displayTextEnd;*/
			// StringBuffer sb = new StringBuffer();
			for (int cursor = start; cursor < max; cursor++) {
				if (text.charAt(cursor) == '\n' || cursor + displayTextStart == linesBreak.get(row * 2 + 1)) {
					font.draw(text.substring(start, cursor), offsetX, offsetY, 0, Align.left, false).free();
					start = text.charAt(cursor) == '\n' ? cursor + 1 : cursor;
					offsetX = baseOffsetX;
					offsetY -= area.textHeight();
					row++;
				}
				// Log.info(cursor + "," + linesBreak.get(row * 2 + 1));
			}
			font.draw(text.substring(start, max), offsetX, offsetY, 0, Align.left, false).free();
			offsetX += glyphPositions.get(max) - glyphPositions.get(start);
		}

		/*public void highlightingDraw(Font font, float x, float y) {
			int firstLineShowing = getRealFirstLineShowing();
			int linesShowing = getRealLinesShowing() + 1;
			float offsetY = -firstLineShowing * font.getLineHeight();
			// StringBuilder builder = new StringBuilder(displayText);
			int displayTextStart = linesBreak.get(firstLineShowing * 2);
			int displayTextEnd = linesBreak.get(Math.min((firstLineShowing + linesShowing) * 2, linesBreak.size) - 1);
			// Log.debug(displayTextStart + "," + displayTextEnd);
			String displayText = text.substring(displayTextStart, displayTextEnd);
			// String displayText = String.valueOf(this.displayText);

			Matcher m;
			// String group;
			float offsetX;
			int lastIndex, home;
			String line;
			for (int i = firstLineShowing * 2; i < (firstLineShowing + linesShowing) * 2 && i < linesBreak.size; i += 2) {
				home = linesBreak.items[i];
				line = displayText.substring(home - displayTextStart, linesBreak.items[i + 1] - displayTextStart);
				lastIndex = 0;
				offsetX = x;
				Seq<RegExpResult> results = new Seq<>();
				for (int j = 0; j < patternSeq.size; j++) {
					m = patternSeq.get(j).matcher(line);
					Color color1 = colorSeq.get(j);
					while (m.find()) {
						var res = new RegExpResult();
						res.priority = j;
						res.color = color1;
						res.start = m.start(1);
						res.text = m.group(1);
						res.len = res.text.length();
						res.end = res.start + res.len;
						results.add(res);
					}
				}
				results.sort((a, b) -> {
					if (a.start != b.start) return Integer.compare(a.start, b.start);
					else return Integer.compare(a.priority, b.priority);
				});

				for (var res : results) {
					if (lastIndex > res.start) continue;
					int index = res.start;
					font.setColor(Color.white);
					font.draw(line.substring(lastIndex, index), offsetX, y + offsetY, 0, 8, false).free();
					offsetX = x + glyphPositions.get(home + index) - glyphPositions.get(home);
					lastIndex = res.end;
					font.setColor(res.color);
					font.draw(res.text, offsetX, y + offsetY, 0, 8, false).free();
					offsetX = x + glyphPositions.get(home + lastIndex) - glyphPositions.get(home);
				}
				font.setColor(Color.white);
				font.draw(line.substring(lastIndex), offsetX, y + offsetY, 0, 8, false).free();

				// glyphPositions.get(cursor) - glyphPositions.get(0) + fontOffset + style.font.getData().cursorX
				// font.draw(, x, y + offsetY, linesBreak.items[i], linesBreak.items[i + 1], 0, 8, false);
				offsetY -= font.getLineHeight();
			}
			Draw.color();
		}*/


		@Override
		public void updateDisplayText() {
			super.updateDisplayText();
		}

		@Override
		public void addInputDialog() {
		}

		public InputListener createInputListener() {
			return new MyTextAreaListener();
		}

		public void left() {
			moveCursor(false, false);
		}

		public void right() {
			moveCursor(true, false);
		}

		public void insert(CharSequence itext) {
			changeText(text, insert(cursor, itext, text));
		}

		public String insert(int position, CharSequence text, String to) {
			if (readOnly) return text.toString();
			// Time.mark();
			// Log.debug(Time.elapsed());
			return to.length() == 0 ? text.toString() : new StringBuffer().append(to, 0, position).append(text).append(to.substring(position)).toString();
		}

		void changeText(String oldText, String newText) {
			if (readOnly) return;
			if (newText.equals(oldText)) return;
			this.text = newText;
			ChangeEvent changeEvent = Pools.obtain(ChangeEvent.class, ChangeEvent::new);
			boolean cancelled = fire(changeEvent);
			this.text = cancelled ? oldText : newText;
			Pools.free(changeEvent);
		}

		public void trackCursor() {
			if (trackCursor != null) trackCursor.run();
		}

		/*@Override
		public void moveCursor(boolean forward, boolean jump) {
			super.moveCursor(forward, jump);
			trackCursor();
		}*/

		public int clamp(int index) {
			return Mathf.clamp(index, 0, text.length());
		}

		// 注释
		public void comment(boolean shift) {
			String selection = getSelection();
			if (shift) {
				int start = hasSelection ? Math.min(cursor, selectionStart) : cursor;
				int len = selection.length(), maxLen = text.length();
				int selectionEnd = start + len;
				int startIndex, endIndex;
				int offset = 2;
				if (((startIndex = text.substring(Math.max(0, start - offset), Math.min(start + offset, maxLen)).indexOf("/*")) >= 0)
						&& ((endIndex = text.substring(Math.max(0, selectionEnd - offset), Math.min(selectionEnd + offset, maxLen)).indexOf("*/")) >= 0)) {
					startIndex += Math.max(0, start - offset);
					endIndex += Math.max(0, selectionEnd - offset);
					changeText(text, text.substring(0, startIndex) + text.substring(startIndex + 2, endIndex) + text.substring(Math.min(endIndex + 2, maxLen)));
					selectionStart = clamp(selectionStart - 2);
					cursor = clamp(cursor - 2);
				} else {
					changeText(text, text.substring(0, start) + "/*" + selection + "*/"
							+ text.substring(selectionEnd));
					selectionStart = clamp(selectionStart + 2);
					cursor = clamp(cursor + 2);
				}
			} else {
				int home = linesBreak.get(cursorLine * 2);
				int end = linesBreak.get(Math.min(linesBreak.size - 1, (cursorLine + 1) * 2));
				if (startComment.matcher(text.substring(home, end)).find()) {
					int start = home + text.substring(home, end).indexOf("//");
					changeText(text, text.substring(0, start) + text.substring(start + 2));
					cursor = clamp(cursor - 2);
				} else {
					changeText(text, insert(home, "//", text));
					cursor = clamp(cursor + 2);
				}
			}
		}

		public CharSequence getDisplayText() {
			return displayText;
		}

		public class MyTextAreaListener extends TextAreaListener {
			@Override
			protected void goHome(boolean jump) {
				super.goHome(jump);
				trackCursor();
			}

			@Override
			protected void goEnd(boolean jump) {
				super.goEnd(jump);
				trackCursor();
			}

			@Override
			public boolean keyDown(InputEvent event, KeyCode keycode) {
				if (keyDonwB != null && !keyDonwB.get(event, keycode)) return false;
				Scene stage = getScene();
				if (stage != null && stage.getKeyboardFocus() == MyTextArea.this) {
					trackCursor();
				}

				int oldCursor = cursor;
				boolean shift = Core.input.shift();
				boolean jump = Core.input.ctrl();
				Time.runTask(0f, () -> {
					// 判断是否一样
					if (oldCursor == cursor) {
						// end
						if (keycode == KeyCode.num1) keyDown(event, KeyCode.end);//goEnd(jump);
						// home
						if (keycode == KeyCode.num7) keyDown(event, KeyCode.home);//goHome(jump);
						// left
						if (keycode == KeyCode.num4) keyDown(event, KeyCode.left);//moveCursor(false, jump);
						// right
						if (keycode == KeyCode.num6) keyDown(event, KeyCode.right);//moveCursor(true, jump);
						// down
						if (keycode == KeyCode.num2) keyDown(event, KeyCode.down);//moveCursorLine(cursorLine - 1);
						// up
						if (keycode == KeyCode.num8) keyDown(event, KeyCode.up);//moveCursorLine(cursorLine + 1);
					}
				});
				if (jump && keycode == KeyCode.slash) {
					comment(shift);
					updateDisplayText();
				}

				return super.keyDown(event, keycode);
			}

			@Override
			public boolean keyTyped(InputEvent event, char character) {
				if (keyTypedB != null && !keyTypedB.get(event, character)) return false;
				return super.keyTyped(event, character);
			}

			public boolean keyUp(InputEvent event, KeyCode keycode) {
				if (keyUpB != null && !keyUpB.get(event, keycode)) return false;
				return super.keyUp(event, keycode);
			}
		}
	}

	/*public static class RegExpResult {
		public int priority;
		public int start, len, end;
		public String text;
		public Color color;
	}*/

	public static class MyMatcher {
		private final Matcher matcher;

		private MyMatcher(Matcher matcher) {
			this.matcher = matcher;
		}

		int maxStart = -1;

		private boolean find(int start) {
			if (start <= maxStart || start > matcher.regionEnd()) return false;
			boolean find = matcher.find(start);
			if (find) return true;
			maxStart = matcher.regionEnd();
			return false;
		}

		// 只获取第一个组
		private int start() {
			return matcher.start(1);
		}

		// 只获取第一个组
		private String group() {
			return matcher.group(1);
		}
	}

	// 用于显示行数
	public static class LinesShow extends Table {
		public MyTextArea area;

		public LinesShow(MyTextArea area) {
			super(Tex.buttonRight);
			this.area = area;
		}

		@Override
		public float getPrefWidth() {
			// （行数+1）* 数字宽度
			return (area.getLines() + " ").length() * numWidth;
		}


		Font font;
		/**
		 * 光标实际行（每行以\n分隔）
		 **/
		public int realCurrorLine;

		void drawLine(float offsetY, int row) {
			// Log.debug(cursorLine[0] + "," + cline[0]);
			font.setColor(realCurrorLine == row ? Pal.accent : Color.lightGray);
			font.draw(String.valueOf(row), x, offsetY);
		}

		@Override
		public void draw() {
			super.draw();
			/*int firstLineShowing = area.getRealFirstLineShowing();
			font = area.getStyle().font;
			boolean had = font.getData().markupEnabled;
			font.getData().markupEnabled = false;

			String text = area.getText();
			float lineHeight = area.getFontLineHeight();
			float scrollOffsetY = area.scrollY - (int) (area.scrollY / lineHeight) * lineHeight;
			offsetY = getTop() - getBackground().getTopHeight() + scrollOffsetY;
			// Log.info(scrollOffsetY);
			// if (y2 == y) y2 += font.getLineHeight();

			IntSeq linesBreak = area.getLinesBreak();
			int linesShowing = area.getRealLinesShowing() + 1;
			row = Strings.count(text.substring(0, linesBreak.size == 0 ? 0 : linesBreak.get(firstLineShowing * 2)), '\n') + 1;
			cursorLine = area.getCursorLine() + 1;
			if (firstLineShowing == 0) {
				drawLine();
				offsetY += font.getLineHeight();
			}
			// else cline[0]++;
			for (int i = firstLineShowing * 2; i < (firstLineShowing + linesShowing) * 2 && i < linesBreak.size; i += 2) {
				offsetY -= font.getLineHeight();
				try {
					if (text.charAt(linesBreak.get(i - 1)) == '\n') {
						row++;
						drawLine();
					} else cursorLine--;
				} catch (Exception e) {
					// Log.err(e);
				}
			}
			if (area.newLineAtEnd()) {
				offsetY -= font.getLineHeight();
				row++;
				drawLine();
			}

			font.getData().markupEnabled = had;*/
			int firstLineShowing = area.getRealFirstLineShowing();
			int linesShowing = area.getRealLinesShowing();
			font = area.getStyle().font;
			boolean had = font.getData().markupEnabled;
			String text = area.getText();
			float scrollOffsetY = area.scrollY - (int) (area.scrollY / area.textHeight()) * area.textHeight();
			float offsetY = getTop() - getBackground().getTopHeight() + scrollOffsetY;
			IntSeq linesBreak = area.getLinesBreak();
			int row = 1;
			font.getData().markupEnabled = false;
			realCurrorLine = 0;
			int cursorLine = area.getCursorLine() * 2;
			Runnable task = getTask(offsetY, row);
			for (int i = 0,
			     start = firstLineShowing * 2,
			     end = start + linesShowing * 2;
			     i < end && i < linesBreak.size; i += 2) {
				if (i == start) task = getTask(offsetY, row);
				if (i >= start) offsetY -= area.textHeight();
				if (i == cursorLine) realCurrorLine = row;
				try {
					if (text.charAt(linesBreak.get(i + 1)) == '\n') {
						if (i >= start) task.run();
						row++;
						if (i >= start) task = getTask(offsetY, row);
					}
				} catch (Throwable ignored) {
					if (i >= start) task.run();
					row++;
					offsetY -= area.textHeight();
					if (i >= start) task = getTask(offsetY, row);
				}
			}
			if (area.newLineAtEnd()) {
				if (linesBreak.size == cursorLine) realCurrorLine = row;
				task.run();
			}

			font.getData().markupEnabled = had;
		}

		private Runnable getTask(float offsetY, int row) {
			return () -> {
				drawLine(offsetY, row);
			};
		}
	}


	// 等宽字体样式（没有等宽字体默认样式）
	public static TextField.TextFieldStyle style = new TextField.TextFieldStyle(Styles.defaultField) {{
		font = messageFont = MyFonts.MSYHMONO;
		// background = null;
	}};
}
