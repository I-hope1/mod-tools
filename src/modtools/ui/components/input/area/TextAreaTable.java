
package modtools.ui.components.input.area;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.*;
import arc.scene.Scene;
import arc.scene.event.ChangeListener.ChangeEvent;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.TextField.TextFieldStyle;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import arc.util.*;
import arc.util.pooling.Pools;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.ui.MyFonts;
import modtools.ui.components.input.highlight.*;

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
	private final MyTextArea   area;
	public final  MyScrollPane pane;
	/** 编辑器是否只可读 */
	public        boolean      readOnly = false,
	/** 编辑器是否显示行数 */
	showLine = true;
	public        Syntax syntax;
	public static int    numWidth = 13;

	public MyTextArea getArea() {
		return area;
	}

	public TextAreaTable(String text) {
		super(Tex.underline);

		area = new MyTextArea(text);
		area.setStyle(style);
		pane = new MyScrollPane();
		area.trackCursor = pane::trackCursor;
		LinesShow linesShow = new LinesShow(area);
		Cell<?>   cell      = add(linesShow).growY().left();
		// fill().add(area);
		add(pane).grow();
		area.setPrefRows(10);
		area.x += pane.x;
		area.y += pane.y;
		area.changed(() -> {
			pane.trackCursor();
			// Element last = Core.scene.getKeyboardFocus();
			// 刷新Area
			pane.invalidate();
			// invalidateHierarchy();
			linesShow.invalidate();
			invalidate();
			cell.setElement(showLine ? linesShow : null);
			// if (last == area) focus();
		});
		Rect rect = new Rect();
		margin(0);
		update(() -> {
			// Element focus = Core.scene.getKeyboardFocus();
			// if (focus == area) Core.scene.setScrollFocus(pane);
			if (!showLine) cell.clearElement();
			rect.set(x, y, width, height);
			/*if (rect.contains(Core.input.mouse()) && visible && ((focus != null && isAscendantOf(focus)) || Core.scene.getScrollFocus() == pane))
				Core.scene.setKeyboardFocus(area);*/

			area.parentHeight = getHeight();
			area.setFirstLineShowing(0);
		});
		Time.runTask(2, area::updateDisplayText);

		syntax = new JSSyntax(this);
	}

	public Boolf2<InputEvent, KeyCode>   keyDonwB  = null;
	public Boolf2<InputEvent, Character> keyTypedB = null;
	public Boolf2<InputEvent, KeyCode>   keyUpB    = null;

	public void focus() {
		Core.scene.setKeyboardFocus(area);
	}

	public class MyScrollPane extends ScrollPane {
		public MyScrollPane() {
			super(area);
			setVelocityY(100);
		}

		public void trackCursor() {
			// Time.runTask(0f, () -> {
			int cursorLine       = area.getCursorLine();
			int firstLineShowing = area.getRealFirstLineShowing();
			int lines            = area.getRealLinesShowing();
			int max              = firstLineShowing + lines;
			if (cursorLine <= firstLineShowing) {
				setScrollY(cursorLine * area.lineHeight());
			}
			if (cursorLine > max) {
				setScrollY((cursorLine - lines) * area.lineHeight());
			}
			// });
		}

		@Override
		public void visualScrollY(float pixelsY) {
			area.scrollY = pixelsY;
			//			Log.info(pixelsY);
			super.visualScrollY(pixelsY);
		}

		/*@Override
		protected void drawChildren() {
		}*/
	}

	public boolean enableHighlighting = true;

	private static final Pattern startComment = Pattern.compile("\\s*//");
	public static final  float   fontWidth    = 12;

	public class MyTextArea extends TextArea {
		public  float    parentHeight = 0;
		private float    scrollY      = 0;
		public  Runnable trackCursor  = null;

		public MyTextArea(String text) {
			super(text);
		}
		public float lineHeight() {
			return style.font.getLineHeight();
		}
		public void setText(String str) {
			super.setText(str);
		}
		public float getPrefHeight() {
			float prefHeight = textHeight * getLines();
			var   style      = getStyle();
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
			Drawable background      = style.background;
			float    availableHeight = parentHeight - (background == null ? 0 : background.getBottomHeight() + background.getTopHeight());
			return (int) Math.floor(availableHeight / lineHeight());
		}
		public int getRealFirstLineShowing() {
			if (true) return (int) Math.floor(scrollY / lineHeight());

			int firstLineShowing = 0;
			if (cursorLine != firstLineShowing) {
				int step = cursorLine >= firstLineShowing ? 1 : -1;
				while (firstLineShowing > cursorLine || firstLineShowing + linesShowing - 1 < cursorLine) {
					firstLineShowing += step;
				}
			}
			return firstLineShowing;
		}

		protected void drawText(Font font, float x, float y) {
			boolean had       = font.getData().markupEnabled;
			Color   lastColor = font.getColor();
			font.getData().markupEnabled = false;

			if (enableHighlighting) {
				try {
					highlightingDraw(font, x, y);
				} catch (Exception e) {
					Log.err(e);
				}
			} else {
				font.setColor(Color.white);
				int   firstLineShowing = getRealFirstLineShowing();
				int   linesShowing     = getRealLinesShowing() + 1;
				float offsetY          = -firstLineShowing * lineHeight();
				int   max              = (firstLineShowing + linesShowing) * 2;
				for (int i = firstLineShowing * 2; i < max && i < linesBreak.size; i += 2) {
					font.draw(displayText, x, y + offsetY, linesBreak.get(i), linesBreak.get(i + 1),
					          0, Align.left, false).free();
					offsetY -= lineHeight();
				}
			}
			font.setColor(lastColor);
			font.getData().markupEnabled = had;
		}

		float offsetX, offsetY, baseOffsetX;
		int row, displayTextStart, displayTextEnd;
		public Font font = null;

		public void highlightingDraw(Font font, float x, float y) {
			if (needsLayout()) return;
			this.font = font;
			baseOffsetX = offsetX = x;
			int firstLineShowing = getRealFirstLineShowing();
			offsetY = -firstLineShowing * lineHeight() + y;
			int linesShowing = getRealLinesShowing() + 1;
			row = firstLineShowing;

			displayTextStart = linesBreak.get(Math.min(firstLineShowing * 2, linesBreak.size - 1));
			displayTextEnd = linesBreak.get(Math.min((firstLineShowing + linesShowing) * 2, linesBreak.size) - 1);
			if (displayTextStart == displayTextEnd) return;

			syntax.highlightingDraw(text.substring(displayTextStart, displayTextEnd));
		}
		public void drawMultiText(String text, int start, int max) {
			if (start >= max) return;
			if (font.getColor().a == 0) return;
			/*start -= displayTextStart;
			max -= displayTextEnd;*/
			// StringBuffer sb = new StringBuffer();
			for (int cursor = start; cursor < max; cursor++) {
				if (text.charAt(cursor) == '\n' || cursor + displayTextStart == linesBreak.get(row * 2 + 1)) {
					font.draw(text, offsetX, offsetY, start, cursor, 0, Align.left, false).free();
					start = text.charAt(cursor) == '\n' ? cursor + 1 : cursor;
					offsetX = baseOffsetX;
					offsetY -= area.lineHeight();
					row++;
				}
				// Log.info(cursor + "," + linesBreak.get(row * 2 + 1));
			}
			if (start < max) {
				font.draw(text, offsetX, offsetY, start, max, 0, Align.left, false).free();
				offsetX += glyphPositions.get(max) - glyphPositions.get(start);
			}
		}

		public void updateDisplayText() {
			super.updateDisplayText();
		}
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
			if (readOnly) return;
			changeText(text, insert(cursor, itext, text));
		}
		String insert(int position, CharSequence text, String to) {
			if (to.length() == 0) return text.toString();
			return to.substring(0, position) + text + to.substring(position);
		}

		boolean changeText(String oldText, String newText) {
			if (readOnly || newText.equals(oldText)) return false;
			text = newText;
			ChangeEvent changeEvent = Pools.obtain(ChangeEvent.class, ChangeEvent::new);
			boolean     cancelled   = fire(changeEvent);
			text = cancelled ? oldText : newText;
			Pools.free(changeEvent);
			return !cancelled;
		}
		public void trackCursor() {
			if (trackCursor != null) trackCursor.run();
		}

		public void moveCursor(boolean forward, boolean jump) {
			super.moveCursor(forward, jump);
			trackCursor();
		}

		public int clamp(int index) {
			return Mathf.clamp(index, 0, text.length());
		}

		// 注释
		public void comment(boolean shift) {
			String selection = getSelection();
			if (shift) {
				int start        = hasSelection ? Math.min(cursor, selectionStart) : cursor;
				int len          = selection.length(), maxLen = text.length();
				int selectionEnd = start + len;
				int startIndex, endIndex;
				int offset       = 2;
				if (((startIndex = text.substring(Math.max(0, start - offset), Math.min(start + offset, maxLen)).indexOf("/*")) >= 0)
				    && ((endIndex = text.substring(Math.max(0, selectionEnd - offset), Math.min(selectionEnd + offset, maxLen)).indexOf("*/")) >= 0)) {
					startIndex += Math.max(0, start - offset);
					endIndex += Math.max(0, selectionEnd - offset);
					// text.delete(startIndex, 2);
					// text.delete(Math.min(endIndex + 2, maxLen), text.length());
					changeText(text, text.substring(0, startIndex) + text.substring(startIndex + 2, endIndex) + text.substring(Math.min(endIndex + 2, maxLen)));
					selectionStart = clamp(selectionStart - 2);
					cursor = clamp(cursor - 2);
				} else {
					// text.insert(start, "/*");
					// text.insert(selectionEnd + 2, "*/");
					changeText(text, text.substring(0, start) + "/*" + selection + "*/"
					                 + text.substring(selectionEnd));
					selectionStart = clamp(selectionStart + 2);
					cursor = clamp(cursor + 2);
				}
			} else {
				int home = linesBreak.get(cursorLine * 2);
				int end  = linesBreak.get(Math.min(linesBreak.size - 1, (cursorLine + 1) * 2));
				if (startComment.matcher(text.substring(home, end)).find()) {
					int start = home + text.substring(home, end).indexOf("//");
					// text.delete(start, 2);
					changeText(text, text.substring(0, start) + text.substring(start + 2));
					cursor = clamp(cursor - 2);
				} else {
					changeText(text, insert(home, "//", text));
					cursor = clamp(cursor + 2);
				}
			}
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

				// 修复笔记本上不能使用的问题
				int     oldCursor = cursor;
				boolean shift     = Core.input.shift();
				boolean jump      = Core.input.ctrl();
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
				trackCursor();
				return super.keyTyped(event, character);
			}

			public boolean keyUp(InputEvent event, KeyCode keycode) {
				if (keyUpB != null && !keyUpB.get(event, keycode)) return false;
				trackCursor();
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
		// public boolean multi = false;

		private MyMatcher(Matcher matcher) {
			this.matcher = matcher;
		}

		// boolean notFound;
		int lastStart = -1;
		// int minStart = -1;

		private boolean found(int index) {
			return index < lastStart;
		}

		private boolean find() {
			if (matcher.find()) {
				lastStart = matcher.start();
				return true;
			}
			return false;
			/*if (*//*notFound || start <= lastStart || *//*start > matcher.regionEnd()) return false;
			// if (start <= lastStart) return true;
			if (matcher.find(start)) {
				// lastStart = matcher.start();
				return true;
			}
			// notFound = true;
			// minStart = matcher.regionEnd();
			return false;*/
		}

		// 只获取第一个组
		private int start() {
			return matcher.start(1);
		}

		// 只获取第一个组
		private String group() {
			return matcher.group(1);
		}

		@Override
		public String toString() {
			return matcher.toString();
		}
	}

	// 用于显示行数
	public static class LinesShow extends Table {
		public MyTextArea area;

		public LinesShow(MyTextArea area) {
			super(Tex.paneRight);
			this.area = area;
		}

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
			int linesShowing     = area.getRealLinesShowing();
			font = area.getStyle().font;
			boolean had           = font.getData().markupEnabled;
			String  text          = area.getText();
			float   scrollOffsetY = area.scrollY - (int) (area.scrollY / area.lineHeight()) * area.lineHeight();
			float   offsetY       = getTop() - getBackground().getTopHeight() + scrollOffsetY;
			IntSeq  linesBreak    = area.getLinesBreak();
			int     row           = 1;
			font.getData().markupEnabled = false;
			realCurrorLine = 0;
			int      cursorLine = area.getCursorLine() * 2;
			Runnable task       = getTask(offsetY, row);
			int      i          = 0;
			int start = firstLineShowing * 2,
					end = start + linesShowing * 2;
			for (; i <= end && i < linesBreak.size; i += 2) {
				if (i == start) {
					if (i == 0) task.run();
					task = getTask(offsetY, row);
				}
				if (i >= start) offsetY -= area.lineHeight();
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
					offsetY -= area.lineHeight();
					if (i >= start) task = getTask(offsetY, row);
				}
			}
			if (area.newLineAtEnd()) {
				if (linesBreak.size == cursorLine) realCurrorLine = row;
				if (i >= linesBreak.size) task.run();
			}
			/* else {
				drawLine(offsetY + area.lineHeight(), row);
			}*/

			font.getData().markupEnabled = had;
		}

		private Runnable getTask(float offsetY, int row) {
			return () -> {
				drawLine(offsetY, row);
			};
		}
	}


	// 等宽字体样式（没有等宽字体默认样式）
	public static TextFieldStyle style = new TextFieldStyle(Styles.defaultField) {{
		font = messageFont = MyFonts.MSYHMONO;
		// background = null;
	}};
}
