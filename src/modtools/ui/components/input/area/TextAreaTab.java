
package modtools.ui.components.input.area;

import arc.Core;
import arc.func.Boolf2;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Rect;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextField.TextFieldStyle;
import arc.scene.ui.layout.*;
import arc.struct.IntSeq;
import arc.util.*;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.*;
import modtools.ui.*;
import modtools.ui.components.input.highlight.*;
import modtools.utils.Tools;

import java.util.regex.*;

import static modtools.utils.Tools.Sr;


/**
 * 一个文本域（多行文本输入框）(优化很烂，不建议使用)
 * 支持显示行数
 * 支持高亮显示
 * 支持滚动
 * @author I hope...
 **/
public class TextAreaTab extends Table implements SyntaxDrawable {
	private final MyTextArea   area;
	public final  MyScrollPane pane;
	private       LinesShow    linesShow;
	// public final  CodeTooltip  tooltip  = new CodeTooltip();
	/** 编辑器是否只可读 */
	public        boolean      readOnly = false,
	/** 编辑器是否显示行数 */
	showLine;
	public Syntax syntax;

	public static float numWidth = Sr(0f).setOpt(__ -> {
		var layout = new GlyphLayout();
		layout.setText(Fonts.def, "0");
		return layout.width;
	}).get();

	public MyTextArea getArea() {
		return area;
	}
	public String getText() {
		return area.getText();
	}
	public void setText(String text) {
		area.setText(text);
	}

	public TextAreaTab(String text) {
		this(text, true);
	}
	public TextAreaTab(String text, boolean showLines0) {
		super(Tex.underline);
		this.showLine = showLines0;

		area = new MyTextArea(text);
		area.setStyle(MOMO_STYLE);
		pane = new MyScrollPane();
		area.trackCursor = pane::trackCursor;
		Cell<?> cell = add(showLine ? getLinesShow() : null).growY().left();
		add(pane).grow();
		area.setPrefRows(8);
		area.x += pane.x;
		area.y += pane.y;
		area.changed(() -> {
			pane.trackCursor();
			// 刷新Area
			pane.invalidate();
			// invalidateHierarchy();
			Core.app.post(this::invalidate);
			cell.setElement(showLine ? linesShow : null);
			// if (last == area) focus();
		});
		Rect rect = new Rect();
		margin(0);
		update(() -> {
			if (!showLine) cell.clearElement();
			rect.set(x, y, width, height);

			area.parentHeight = getHeight();
			area.setFirstLineShowing(0);
		});
		Time.runTask(2, area::updateDisplayText);
	}
	public VirtualString virtualString;
	public static class VirtualString {
		public String text;
		public Color  color = Color.gray;
		public int    index;
		public VirtualString() {}
	}
	private LinesShow getLinesShow() {
		linesShow = new LinesShow(area);
		return linesShow;
	}

	/* 返回true，则cancel事件 */
	public Boolf2<InputEvent, KeyCode>   keyDownB  = null;
	public Boolf2<InputEvent, Character> keyTypedB = null;
	public Boolf2<InputEvent, KeyCode>   keyUpB    = null;

	public float alpha() {
		return parentAlpha;
	}
	public Font font() {
		return area.font;
	}
	public void drawMultiText(CharSequence displayText, int start, int max) {
		area.drawMultiText(displayText, start, max);
	}

	public class MyScrollPane extends ScrollPane {
		public MyScrollPane() {
			super(area);
		}

		public void trackCursor() {
			// Time.runTask(0f, () -> {
			int cursorLine       = area.getCursorLine();
			int firstLineShowing = area.getRealFirstLineShowing();
			int lines            = area.getRealLinesShowing();
			int max              = firstLineShowing + lines;
			if (cursorLine <= firstLineShowing) {
				setScrollY(cursorLine * area.lineHeight());
				act(10);
			}
			if (cursorLine > max) {
				setScrollY((cursorLine - lines) * area.lineHeight());
				act(10);
			}
		}

		public void visualScrollY(float pixelsY) {
			area.scrollY = pixelsY;
			super.visualScrollY(pixelsY);
		}
	}

	public float getRelativeX(int pos) {
		return area.getRelativeX(pos);
	}
	public float getRelativeY(int pos) {
		return area.getRelativeY(pos);
	}
	public boolean enableHighlighting = true;

	private static final Pattern startComment = Pattern.compile("\\s*//");

	public class MyTextArea extends modtools.ui.components.input.area.MyTextArea {
		public  float    parentHeight = 0;
		private float    scrollY      = 0;
		public  Runnable trackCursor  = null;

		public void paste(StringBuilder content, boolean fireChangeEvent) {
			if (readOnly) return;
			super.paste(content, fireChangeEvent);
		}
		public MyTextArea(String text) {
			super("", HopeStyles.defaultMultiArea);
			Tools.runIgnoredException(() -> {
				focusTraversal = false;
				onlyFontChars = false;
			});
			setText(text);
		}
		public float lineHeight() {
			return style.font.getLineHeight();
		}
		public float getPrefHeight() {
			float prefHeight = textHeight * getLines();
			var   style      = getStyle();
			if (style.background != null) {
				prefHeight = Math.max(prefHeight + style.background.getBottomHeight() + style.background.getTopHeight(),
				 style.background.getMinHeight());
			}
			return Math.max(super.getPrefHeight(), prefHeight + parentHeight / 2f);
		}

		float getRelativeX(int cursor) {
			int prev = this.cursor;
			super.setCursorPosition(cursor);
			float textOffset = cursor >= glyphPositions.size || cursorLine * 2 >= linesBreak.size ? 0
			 : glyphPositions.get(cursor) - glyphPositions.get(linesBreak.items[cursorLine * 2]);
			float bgLeft = getBackground() == null ? 0 : getBackground().getLeftWidth();
			float val    = x + bgLeft + textOffset + fontOffset + font.getData().cursorX;
			super.setCursorPosition(prev);
			return val;
		}
		/** @see arc.scene.ui.TextArea#drawCursor(Drawable, Font, float, float)   */
		float getRelativeY(int cursor) {
			int prev = this.cursor;
			super.setCursorPosition(cursor);
			float textY = getTextY(font, getBackground());
			float val   = y + textY - (cursorLine - firstLineShowing + 1) * font.getLineHeight();
			super.setCursorPosition(prev);
			return val;
		}
		public void setCursorPosition(int cursorPosition) {
			super.setCursorPosition(cursorPosition);
			trackCursor();
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

		public void drawText(Font font, float x, float y) {
			boolean had       = font.getData().markupEnabled;
			Color   lastColor = font.getColor();
			font.getData().markupEnabled = false;

			this.font = font;
			l:
			if (virtualString != null && virtualString.text != null) {
				font.getColor().set(virtualString.color).mulA(alpha());
				if (font.getColor().a == 0) break l;
				float x1 = getRelativeX(virtualString.index);
				float y1 = getRelativeY(virtualString.index) + font.getLineHeight();
				// Log.info("(@, @)", x1, y1);
				font.draw(virtualString.text, x1, y1);
			}

			if (enableHighlighting && syntax != null) {
				highlightingDraw(x, y);
			} else {
				font.getColor().set(Color.white).mulA(alpha());
				int   firstLineShowing = getRealFirstLineShowing();
				int   linesShowing     = getRealLinesShowing() + 1;
				float offsetY          = -firstLineShowing * lineHeight();
				int   max              = (firstLineShowing + linesShowing) * 2;
				for (int i = firstLineShowing * 2; i < max && i < linesBreak.size; i += 2) {
					font.draw(text, x, y + offsetY, linesBreak.get(i), linesBreak.get(i + 1),
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
		public int displayTextStart0() {
			return displayTextStart;
		}
		public int displayTextEnd0() {
			return displayTextEnd;
		}
		public void highlightingDraw(float x, float y) {
			if (needsLayout()) return;
			baseOffsetX = offsetX = x;
			updateTextIndex();
			int firstLineShowing = getRealFirstLineShowing();
			offsetY = -firstLineShowing * lineHeight() + y;
			row = firstLineShowing;
			if (displayTextStart == displayTextEnd) return;
			/* if (linesBreak.peek() < firstLineShowing + linesShowing) {
				displayTextEnd = linesBreak.peek();
			} */

			syntax.highlightingDraw(text.substring(displayTextStart, displayTextEnd));
		}
		/** 渲染多文本 */
		public void drawMultiText(CharSequence text, int start, int max) {
			if (start == max) return;
			if (start > max) throw new IllegalArgumentException("start: " + start + " > max:" + max);
			if (font.getColor().a == 0) return;
			/*start -= displayTextStart;
			max -= displayTextEnd;*/
			// StringBuilder sb = new StringBuilder();
			for (int cursor = start; cursor < max; cursor++) {
				// 判断是否为换行（包括自动换行）
				if (text.charAt(cursor) == '\n' || cursor + displayTextStart == linesBreak.get(row * 2 + 1)) {
					drawText(text, start, cursor);
					start = text.charAt(cursor) == '\n' ? cursor + 1 : cursor;
					offsetX = baseOffsetX;
					offsetY -= area.lineHeight();
					row++;
				}
			}
			if (start < max) {
				drawText(text, start, max);
				offsetX += glyphPositions.get(displayTextStart + max) - glyphPositions.get(displayTextStart + start);
				// Log.info(glyphPositions);
				// Log.info(font.getData().cursorX);
			}
		}
		private void drawText(CharSequence text, int start, int cursor) {
			font.draw(text, offsetX, offsetY, start, cursor, 0f, Align.left, false);
		}

		public void updateDisplayText() {
			updateTextIndex();
			super.updateDisplayText();
		}
		protected void drawSelection(Drawable selection, Font font, float x, float y) {
			int   firstLineShowing = getRealFirstLineShowing();
			int   linesShowing     = getRealLinesShowing();
			int   i                = firstLineShowing * 2;
			float offsetY          = lineHeight() * firstLineShowing;
			int   minIndex         = Math.min(cursor, selectionStart);
			int   maxIndex         = Math.max(cursor, selectionStart);
			while (i + 1 < linesBreak.size && i < (firstLineShowing + linesShowing) * 2) {

				int lineStart = linesBreak.get(i);
				int lineEnd   = linesBreak.get(i + 1);

				if (!((minIndex < lineStart && minIndex < lineEnd && maxIndex < lineStart && maxIndex < lineEnd)
							|| (minIndex > lineStart && minIndex > lineEnd && maxIndex > lineStart && maxIndex > lineEnd))) {

					int start = Math.min(Math.max(linesBreak.get(i), minIndex), glyphPositions.size - 1);
					int end   = Math.min(Math.min(linesBreak.get(i + 1), maxIndex), glyphPositions.size - 1);

					float selectionX     = glyphPositions.get(start) - glyphPositions.get(Math.min(linesBreak.get(i), glyphPositions.size));
					float selectionWidth = glyphPositions.get(end) - glyphPositions.get(start);

					selection.draw(x + selectionX + fontOffset, y - textHeight - font.getDescent() - offsetY, selectionWidth,
					 font.getLineHeight());
				}

				offsetY += font.getLineHeight();
				i += 2;
			}
		}
		private void updateTextIndex() {
			int firstLineShowing = getRealFirstLineShowing();
			int linesShowing     = getRealLinesShowing() + 1;

			if (linesBreak.size > 0) {
				displayTextStart = linesBreak.get(Mathf.clamp(firstLineShowing * 2, 0, linesBreak.size - 1));
				displayTextEnd = linesBreak.get(Mathf.clamp((firstLineShowing + linesShowing) * 2 - 1, 0, linesBreak.size - 1));
			} else {
				int length = text.length();
				displayTextStart = Mathf.clamp(displayTextStart, 0, length);
				displayTextEnd = Mathf.clamp(displayTextEnd, 0, length);
			}
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

		public StringBuilder insert(int position, CharSequence text, StringBuilder to) {
			if (readOnly) return to;
			return super.insert(position, text, to);
		}
		public StringBuilder insert(int position, char c, StringBuilder to) {
			if (readOnly) return to;
			return super.insert(position, c, to);
		}
		boolean changeText() {
			return !readOnly && super.changeText();
		}

		boolean changeText(StringBuilder oldText, StringBuilder newText) {
			return !readOnly && super.changeText(oldText, newText);
		}

		public void trackCursor() {
			if (trackCursor != null) {
				Time.runTask(1f, trackCursor);
			}
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
					changeText(text, new StringBuilder(text).delete(startIndex, startIndex + 2)
					 .delete(endIndex - 2, endIndex));
					selectionStart = clamp(selectionStart - 2);
					cursor = clamp(cursor - 2);
				} else {
					// text.insert(start, "/*");
					// text.insert(selectionEnd + 2, "*/");
					changeText(text, new StringBuilder(text)
					 .insert(selectionEnd, "*/")
					 .insert(start, "/*"));
					selectionStart = clamp(selectionStart + 2);
					cursor = clamp(cursor + 2);
				}
			} else {
				int home = linesBreak.get(cursorLine * 2);
				int end  = linesBreak.get(Math.min(linesBreak.size - 1, (cursorLine + 1) * 2));
				if (startComment.matcher(text.substring(home, end)).find()) {
					int start = home + text.substring(home, end).indexOf("//");
					// text.delete(start, 2);
					changeText(text, new StringBuilder(text).delete(start, start + 2));
					cursor = clamp(cursor - 2);
				} else {
					changeText(text, insert(home, "//", text));
					cursor = clamp(cursor + 2);
				}
			}
		}
		public boolean checkIndex(int i) {
			return 0 <= i && i < text.length();
		}

		public class MyTextAreaListener extends TextAreaListener {
			protected void goHome(boolean jump) {
				super.goHome(jump);
				trackCursor();
			}
			protected void goEnd(boolean jump) {
				super.goEnd(jump);
				trackCursor();
			}
			public boolean keyDown(InputEvent event, KeyCode keycode) {
				if (keyDownB != null && keyDownB.get(event, keycode)) {
					return false;
				}
				Scene stage = getScene();
				if (stage != null && stage.getKeyboardFocus() == MyTextArea.this) {
					trackCursor();
				}

				boolean shift = Core.input.shift();
				boolean jump  = Core.input.ctrl();
				fixNumLk(event, keycode);

				if (jump && keycode == KeyCode.d) selectNearWord();
				if (jump && keycode == KeyCode.slash) {
					comment(shift);
					updateDisplayText();
				}

				/* 我也不知道为什么会报错 */
				try {
					return super.keyDown(event, keycode);
				} catch (IndexOutOfBoundsException e) {
					return false;
				}
			}
			private void fixNumLk(InputEvent event, KeyCode keycode) {
				if (OS.isAndroid) return;
				// 修复笔记本上不能使用的问题
				int oldCursor = cursor;
				Time.runTask(1, () -> {
					// 判断是否一样
					if (oldCursor != cursor) return;

					// end: goEnd(jump);
					if (keycode == KeyCode.num1) keyDown(event, KeyCode.end);
					// home: goHome(jump);
					if (keycode == KeyCode.num7) keyDown(event, KeyCode.home);
					// left: moveCursor(false, jump);
					if (keycode == KeyCode.num4) keyDown(event, KeyCode.left);
					// right: moveCursor(true, jump);
					if (keycode == KeyCode.num6) keyDown(event, KeyCode.right);
					// down: moveCursorLine(cursorLine - 1);
					if (keycode == KeyCode.num2) keyDown(event, KeyCode.down);
					// up: moveCursorLine(cursorLine + 1);
					if (keycode == KeyCode.num8) keyDown(event, KeyCode.up);
					// delete: delete();
					if (keycode == KeyCode.period) keyDown(event, KeyCode.del);
				});
			}
			public boolean keyTyped(InputEvent event, char character) {
				if (keyTypedB != null && keyTypedB.get(event, character)) {
					event.cancel();
					return false;
				}
				trackCursor();
				// tooltip.show(TextAreaTable.this, getCursorX(), getCursorY() - getRealFirstLineShowing() * lineHeight());
				return super.keyTyped(event, character);
			}

			public boolean keyUp(InputEvent event, KeyCode keycode) {
				if (keyUpB != null && keyUpB.get(event, keycode)) {
					event.cancel();
					return false;
				}
				trackCursor();
				return super.keyUp(event, keycode);
			}
		}
		public void selectNearWord() {
			int i = hasSelection ? selectionStart : cursor - 1;
			while (isWordCharacterCheck(i)) --i;
			selectionStart = i + 1;
			i = cursor;
			while (isWordCharacterCheck(i)) ++i;
			cursor = i;
			hasSelection = selectionStart != cursor;
		}

		public char charAtUncheck(int i) {
			return text.charAt(i);
		}

		/* 这会判断是否越界，绕过越界就返回false */
		public boolean isWordCharacterCheck(int i) {
			if (i < 0 || i >= text.length()) return false;
			return isWordCharacter(text.charAt(i));
		}
		public boolean isCharCheck(int i, char c) {
			if (i < 0 || i >= text.length()) return false;
			return text.charAt(i) == c;
		}
		public boolean isWordCharacter(char c) {
			return super.isWordCharacter(c) || c == '_';
		}
	}

	// 用于显示行数
	public static class LinesShow extends Table {
		public MyTextArea area;

		public LinesShow(MyTextArea area) {
			super(HopeTex.paneRight);
			image().color(Color.gray).marginRight(6f);
			this.area = area;
		}

		public float getPrefWidth() {
			// （行数+1）* 数字宽度
			return (float) Math.ceil(Math.log10(area.getLines() + 1)) * numWidth;
		}

		Font font;
		/**
		 * 光标实际行（每行以\n分隔）
		 **/
		public int realCursorLine;

		/** 渲染行号 */
		void drawLine(float offsetY, int row) {
			// Log.debug(cursorLine[0] + "," + cline[0]);
			font.setColor(realCursorLine == row ? Pal.accent : Color.lightGray);
			MyFonts.underline = realCursorLine == row;
			font.draw(String.valueOf(row), x, offsetY);
			MyFonts.underline = false;
		}
		public void draw() {
			super.draw();

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
			realCursorLine = 0;
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
				if (i == cursorLine) realCursorLine = row;
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
				if (linesBreak.size == cursorLine) realCursorLine = row;
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

	/* public static class CodeTooltip extends IntUI.Tooltip {
		public Table p;
		public CodeTooltip() {
			super(t -> {});
			always = true;
			container.background(Tex.pane).pane(p = new InsideTable(p -> {
				p.left().defaults().left();
				p.update(() -> {
					int w = Core.graphics.getWidth();
					int h = Core.graphics.getHeight();
					container.setBounds(
					 Mathf.clamp(container.x, 0, w),
					 Mathf.clamp(container.y, 0, h),
					 Math.min(w, p.parent.getPrefWidth()),
					 Math.min(h, p.parent.getPrefHeight())
					);
				});
			})).pad(0);
			show.run();
		}
		public void hide() {
			super.hide();
			container.act(30);
			p.parent.act(30);
		}
		public void show(Element element, float x, float y) {
			super.show(element, x, y);
			container.act(30);
			p.parent.act(30);
			container.touchable(() -> Touchable.enabled);
		}
	} */


	// 等宽字体样式（没有等宽字体默认样式）
	public static TextFieldStyle MOMO_STYLE = new TextFieldStyle(Styles.defaultField) {{
		font = messageFont = MyFonts.def;
		selection = ((TextureRegionDrawable) Tex.selection).tint(Tmp.c1.set(0x4763FFFF));
	}};
}
