package modtools.ui.comp.input.area;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.graphics.g2d.GlyphLayout.GlyphRun;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Rect;
import arc.scene.Scene;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.TextField.TextFieldStyle;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.*;
import modtools.ui.*;
import modtools.ui.comp.input.highlight.*;
import modtools.utils.SR;

import java.util.regex.Pattern;


/**
 * 一个文本域（多行文本输入框）(优化很烂，不建议使用)
 * 支持显示行数
 * 支持高亮显示
 * 支持滚动
 * @author I hope...
 **/
public class TextAreaTab extends Table implements SyntaxDrawable {
	public static boolean DEBUG = false;

	public static final String INDENT = "  ";

	private final MyTextArea   area;
	public final  MyScrollPane pane;
	private       LinesShow    linesShow;
	// public final  CodeTooltip  tooltip  = new CodeTooltip();
	/** 编辑器是否只可读 */
	public        boolean      readOnly = false,
	/** 编辑器是否显示行数 */
	showLine;
	public Syntax syntax;

	public static float numWidth = SR.of(0f).setOpt(_ -> {
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
		area.changed(() -> {
			pane.trackCursor();
			// 刷新Area
			pane.invalidate();
			// invalidateHierarchy();
			Core.app.post(this::invalidate);
			cell.setElement(showLine ? linesShow : null);
		});
		Rect rect = new Rect();
		margin(0);
		update(() -> {
			if (!showLine) cell.clearElement();
			rect.set(x, y, width, height);

			area.parentHeight = getHeight();
			// 设置原本的area置顶
			area.setFirstLineShowing(0);
		});
		Time.runTask(2, area::updateDisplayText);
	}

	public TextAreaTab(String text, Func<TextAreaTab, Syntax> syntaxFunc) {
		this(text);
		syntax = syntaxFunc.get(this);
	}
	private LinesShow getLinesShow() {
		linesShow = new LinesShow(area);
		return linesShow;
	}

	/** 返回true，则cancel事件 */
	public Boolf2<InputEvent, KeyCode>   keyDownBlock  = null;
	/** 返回true，则cancel事件 */
	public Boolf2<InputEvent, Character> keyTypedBlock = null;
	/** 返回true，则cancel事件 */
	public Boolf2<InputEvent, KeyCode>   keyUpBlock    = null;

	public int cursor() {
		return area.getCursorPosition();
	}
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
			super(area, Styles.smallPane);
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

	public boolean enableHighlight = true;

	private static final Pattern startComment = Pattern.compile("\\s*?//");

	public class MyTextArea extends GenTextArea {
		public  float    parentHeight = 0;
		private float    scrollY      = 0;
		public  Runnable trackCursor  = null;


		public float visualRY() {
			float    scrollOffsetY = scrollY - (int) (scrollY / lineHeight()) * lineHeight();
			Drawable background    = getBackground();
			return (background == null ? 0 : background.getTopHeight())
			       + scrollOffsetY;
		}
		public void setSelectionUncheck(int start, int end) {
			selectionStart = start;
			cursor = end;
			hasSelection = start != end;
		}

		public void paste(String content, boolean fireChangeEvent) {
			if (readOnly) return;
			super.paste(content, fireChangeEvent);
		}
		public MyTextArea(String text) {
			super("", HopeStyles.defaultMultiArea);
			focusTraversal = false;
			onlyFontChars = false;
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
		public Drawable getBackground() {
			return style.background;
		}

		public float getRelativeX(int cursor) {
			int prev = this.cursor;
			this.cursor = cursor;
			float textOffset = cursor >= glyphPositions.size || cursorLine * 2 >= linesBreak.size ? 0
			 : glyphPositions.get(cursor) - glyphPositions.get(linesBreak.items[cursorLine * 2]);
			float bgLeft = getBackground() == null ? 0 : getBackground().getLeftWidth();
			float val    = x + bgLeft + textOffset + fontOffset + font.getData().cursorX;
			this.cursor = prev;
			return val;
		}
		/** @see arc.scene.ui.TextArea#drawCursor(Drawable, Font, float, float) */
		public float getRelativeY(int cursor) {
			int prev = this.cursor;
			this.cursor = cursor;
			float textY = getTextY(font, getBackground());
			float val   = y + textY - (cursorLine - firstLineShowing + 1) * font.getLineHeight();
			this.cursor = prev;
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

			if (enableHighlight && syntax != null) {
				try {
					highlightingDraw(x, y);
				} catch (Throwable e) {
					Log.err(e);
					enableHighlight = false;
				}
			} else {
				font.getColor().set(Color.white).mulA(alpha());

				if (font.getColor().a == 0) return;
				int   firstLineShowing = getRealFirstLineShowing();
				int   linesShowing     = getRealLinesShowing() + 1;
				float offsetY          = -firstLineShowing * lineHeight();
				int   max              = (firstLineShowing + linesShowing) * 2;
				for (int i = firstLineShowing * 2; i < max && i < linesBreak.size; i += 2) {
					font.draw(text, x, y + offsetY, linesBreak.get(i), linesBreak.get(i + 1),
					 0, Align.left, false);
					offsetY -= lineHeight();
				}
			}

			font.setColor(lastColor);
			font.getData().markupEnabled = had;
		}

		float offsetX, offsetY, baseOffsetX;
		int row, displayTextStart, displayTextEnd;
		public Font font = null;
		public void highlightingDraw(float x, float y) {
			if (needsLayout()) return;
			baseOffsetX = offsetX = x;
			updateTextIndex();
			int firstLineShowing = getRealFirstLineShowing();
			offsetY = -firstLineShowing * lineHeight() + y;
			row = firstLineShowing;
			if (displayTextStart == displayTextEnd) return;

			syntax.highlightingDraw(text.substring(displayTextStart, displayTextEnd));
			font.getCache().draw();
			font.getCache().clear();
		}
		/** 渲染多文本 */
		public void drawMultiText(CharSequence text, int start, int max) {
			if (start == max) return;
			if (start > max) throw new IllegalArgumentException("start: " + start + " > max:" + max);
			if (font.getColor().a == 0) return;

			for (int cursor = start; cursor < max; cursor++) {
				char c = text.charAt(cursor);
				if (c == '\n' || c == '\r' || cursor + displayTextStart == linesBreak.get(row * 2 + 1)) {
					drawText(text, start, cursor);
					start = c == '\n' || c == '\r' ? cursor + 1 : cursor;
					offsetX = baseOffsetX;
					offsetY -= area.lineHeight();
					row++;
				}
			}
			if (start < max) {
				drawText(text, start, max);
				if (DEBUG) {
					Draw.color();
					Lines.line(offsetX, offsetY, offsetX, offsetY - lineHeight());
				}
			}
		}

		private void drawText(CharSequence text, int start, int cursor) {
			font.draw(text, offsetX, offsetY, start, cursor, 0f, Align.left, false);
			// --- FIX: Convert relative indices to absolute and add a safety check ---
			// The 'start' and 'cursor' here are relative to the substring being highlighted.
			// We must convert them to absolute indices before accessing glyphPositions.
			int absoluteStart = start + displayTextStart;
			int absoluteCursor = cursor + displayTextStart;

			// Log.info("start: " + start + " cursor: " + cursor + " absoluteStart: " + absoluteStart + " absoluteCursor: " + absoluteCursor);
			// Log.info(text);
			// Safety check to prevent crashes if the layout is momentarily out of sync with the text.
			if (absoluteCursor < glyphPositions.size && absoluteStart < glyphPositions.size) {
				offsetX += Math.max(lastTextWidth(), glyphPositions.get(absoluteCursor) - glyphPositions.get(absoluteStart));
			} else {
				// Fallback to a less accurate but safe width calculation.
				offsetX += lastTextWidth();
			}
		}
		private float lastTextWidth() {
			Seq<GlyphLayout> layouts = font.getCache().getLayouts();
			if (!layouts.isEmpty()) {
				Seq<GlyphRun> runs = layouts.first().runs;
				if (!runs.isEmpty()) {
					return runs.first().xAdvances.peek();
				}
			}
			return 0;
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
				      || (minIndex > lineStart && minIndex > lineEnd))) {

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

		public void fireUp() {
			requestKeyboard();
			if (Core.scene.keyDown(KeyCode.up)) Core.scene.keyUp(KeyCode.up);
		}
		public void fireLeft() {
			requestKeyboard();
			if (Core.scene.keyDown(KeyCode.left)) Core.scene.keyUp(KeyCode.left);
		}
		public void fireRight() {
			requestKeyboard();
			if (Core.scene.keyDown(KeyCode.right)) Core.scene.keyUp(KeyCode.right);
		}
		public void fireDown() {
			requestKeyboard();
			if (Core.scene.keyDown(KeyCode.down)) Core.scene.keyUp(KeyCode.down);
		}

		public String insert(int position, CharSequence text, String to) {
			if (readOnly) return to;
			return super.insert(position, text, to);
		}
		public boolean changeText(String oldText, String newText) {
			return !readOnly && super.changeText(oldText, newText);
		}

		public void trackCursor() {
			if (trackCursor != null) {
				Core.app.post(trackCursor);
			}
		}

		public void moveCursor(boolean forward, boolean jump) {
			int limit      = forward ? text.length() : 0;
			int charOffset = forward ? 0 : -1;
			int index      = cursor + charOffset;
			if (forward == (index >= limit)) return;
			char    c      = text.charAt(index);
			boolean isWord = isWordCharacter(c);
			while ((forward ? ++cursor < limit : --cursor > limit) && jump) {
				c = text.charAt(cursor);
				if (c == '\n' || c == '\r') break;
				if (isWord != continueCursor(cursor, charOffset)) break;
			}
			trackCursor();
		}
		public int clamp(int index) {
			return Mathf.clamp(index, 0, text.length());
		}

		public boolean checkIndex(int i) {
			return 0 <= i && i < text.length();
		}

		// --- NEW: Helper method to get the line number from a character position ---
		/**
		 * Gets the line number for a given character position.
		 * @param pos The character index in the text.
		 * @return The line number (0-indexed).
		 */
		public int getLineFromPos(int pos) {
			// linesBreak is a sequence of [start, end, start, end, ...] indices.
			for (int i = 0; i < linesBreak.size; i += 2) {
				int lineStart = linesBreak.get(i);
				int lineEnd   = linesBreak.get(i + 1);
				if (pos >= lineStart && pos <= lineEnd) {
					// If cursor is at the very end of a non-empty line,
					// it belongs to that line. If it's at the start of the next line,
					// it belongs to the next line. This logic handles it.
					// A special case: if the position is the end of the line, but also the start of the next one
					// (which happens with newlines), prefer the current line.
					if (pos == lineEnd && pos > lineStart && (i + 2) < linesBreak.size && pos == linesBreak.get(i + 2)) {
						continue;
					}
					return i / 2;
				}
			}
			return Math.max(0, getLines() - 1); // Fallback to the last line.
		}

		// --- NEW: Core logic for handling indentation ---
		/**
		 * Handles Tab and Shift+Tab indentation.
		 * @param unindent True if Shift+Tab was pressed (un-indent), false for Tab (indent).
		 */
		public void handleTab(boolean unindent) {
			if (readOnly) return;

			if (!hasSelection) {
				// --- SINGLE LINE INDENTATION ---
				if (unindent) {
					// Un-indent the current line
					int lineStart = linesBreak.get(cursorLine * 2);
					if (lineStart + INDENT.length() <= text.length() && text.startsWith(INDENT, lineStart)) {
						// Safe to remove the tab
						changeText(text, new StringBuilder(text).delete(lineStart, lineStart + INDENT.length()).toString());
						cursor = Math.max(lineStart, cursor - INDENT.length());
					}
				} else {
					// Insert a tab
					String newText = insert(cursor, INDENT, text);
					if (changeText(text, newText)) {
						cursor += INDENT.length();
						clearSelection();
					}
				}
				trackCursor();
				return;
			}

			// --- MULTI-LINE INDENTATION ---
			int selectionStartPos = Math.min(selectionStart, cursor);
			int selectionEndPos   = Math.max(selectionStart, cursor);

			int startLine = getLineFromPos(selectionStartPos);
			int endLine   = getLineFromPos(selectionEndPos);

			// If selection ends exactly at the start of a new line, don't include that new line.
			if (endLine > startLine && selectionEndPos == linesBreak.get(endLine * 2)) {
				endLine--;
			}

			StringBuilder s                    = new StringBuilder(text);
			int           charsChanged         = 0;
			int           selectionStartOffset = 0;

			// Iterate backwards from the last line to the first to prevent messing up indices.
			for (int i = endLine; i >= startLine; i--) {
				int lineStart = linesBreak.get(i * 2);
				if (unindent) {
					if (lineStart + INDENT.length() <= s.length() && s.substring(lineStart, lineStart + INDENT.length()).equals(INDENT)) {
						s.delete(lineStart, lineStart + INDENT.length());
						if (i == startLine) selectionStartOffset = -INDENT.length();
						charsChanged -= INDENT.length();
					}
				} else {
					s.insert(lineStart, INDENT);
					if (i == startLine) selectionStartOffset = INDENT.length();
					charsChanged += INDENT.length();
				}
			}

			// Manually adjust selection points *before* calling changeText, as it can reset them.
			this.selectionStart = Math.max(0, this.selectionStart + selectionStartOffset);
			this.cursor += charsChanged;

			changeText(text, s.toString());
			updateDisplayText();

			// Re-apply the selection to the modified block.
			setSelection(this.selectionStart, this.cursor);
			trackCursor();
		}

		public void comment(boolean shift) {
			// This method can be left as is, but for consistency, you might want to refactor
			// it to use the new getLineFromPos() helper method as well. For now, we leave it untouched.
			if (shift) {
				String selection    = getSelection();
				int    start        = hasSelection ? Math.min(cursor, selectionStart) : cursor;
				int    len          = selection.length(), maxLen = text.length();
				int    selectionEnd = start + len;
				int    startIndex, endIndex;
				int    offset       = 2;
				if (((startIndex = text.substring(Math.max(0, start - offset), Math.min(start + offset, maxLen)).indexOf("/*")) >= 0)
				    && ((endIndex = text.substring(Math.max(0, selectionEnd - offset), Math.min(selectionEnd + offset, maxLen)).indexOf("*/")) >= 0)) {
					startIndex += Math.max(0, start - offset);
					endIndex += Math.max(0, selectionEnd - offset);
					changeText(text, new StringBuilder(text).delete(startIndex, startIndex + 2)
					 .delete(endIndex - 2, endIndex).toString());
					selectionStart = clamp(selectionStart - 2);
					cursor = clamp(cursor - 2);
				} else {
					changeText(text, new StringBuilder(text)
					 .insert(selectionEnd, "*/")
					 .insert(start, "/*").toString());
					selectionStart = clamp(selectionStart + 2);
					cursor = clamp(cursor + 2);
				}
				return;
			}

			final String commentPrefix     = "//";
			int          selectionStartPos = Math.min(this.selectionStart, this.cursor);
			int          selectionEndPos   = Math.max(this.selectionStart, this.cursor);
			int          startLine         = getLineFromPos(selectionStartPos);
			int          endLine           = getLineFromPos(selectionEndPos);

			if (endLine > startLine && selectionEndPos == linesBreak.get(endLine * 2)) {
				endLine--;
			}

			boolean allCommented = true;
			for (int i = startLine; i <= endLine; i++) {
				int lineStart = linesBreak.get(i * 2);
				if (!text.substring(lineStart, linesBreak.get(i * 2 + 1)).trim().startsWith(commentPrefix)) {
					allCommented = false;
					break;
				}
			}

			StringBuilder s                    = new StringBuilder(text);
			int           charsChanged         = 0;
			int           selectionStartOffset = 0;

			for (int i = endLine; i >= startLine; i--) {
				int lineStart = linesBreak.get(i * 2);
				if (allCommented) {
					int commentIndex = s.substring(lineStart).indexOf(commentPrefix);
					if (commentIndex != -1) {
						s.delete(lineStart + commentIndex, lineStart + commentIndex + commentPrefix.length());
						if (i == startLine) selectionStartOffset = -commentPrefix.length();
						charsChanged -= commentPrefix.length();
					}
				} else {
					s.insert(lineStart, commentPrefix);
					if (i == startLine) selectionStartOffset = commentPrefix.length();
					charsChanged += commentPrefix.length();
				}
			}

			String newText = s.toString();
			this.selectionStart += selectionStartOffset;
			this.cursor += charsChanged;

			changeText(text, newText);
			setSelection(this.selectionStart, this.cursor);
			trackCursor();
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
			boolean stoppedEvent;
			public boolean keyDown(InputEvent event, KeyCode keycode) {
				stoppedEvent = false;
				if (keyDownBlock != null && keyDownBlock.get(event, keycode)) {
					if (event != null) event.cancel();
					return false;
				}
				Scene stage = getScene();
				if (stage != null && stage.getKeyboardFocus() == MyTextArea.this) {
					trackCursor();
				}

				boolean shift = Core.input.shift();
				boolean jump  = Core.input.ctrl();
				fixNumLk(event, keycode);

				// --- MODIFICATION: Handle Tab/Shift+Tab before other keys ---
				if (keycode == KeyCode.tab) {
					stoppedEvent = true;
					handleTab(shift);
					event.cancel(); // Prevent default tab behavior (focus traversal)
					return true;    // Consume the event
				}

				if (jump && keycode == KeyCode.d) selectNearWord();
				if (jump && keycode == KeyCode.slash) {
					comment(shift);
					event.cancel();
					updateDisplayText();
					return true;
				}

				try {
					return super.keyDown(event, keycode);
				} catch (IndexOutOfBoundsException e) {
					return false;
				}
			}
			private void fixNumLk(InputEvent event, KeyCode keycode) {
				int oldCursor = cursor;
				Time.runTask(1, () -> {
					if (oldCursor != cursor) return;
					if (keycode == KeyCode.num1) keyDown(event, KeyCode.end);
					if (keycode == KeyCode.num7) keyDown(event, KeyCode.home);
					if (keycode == KeyCode.num4) keyDown(event, KeyCode.left);
					if (keycode == KeyCode.num6) keyDown(event, KeyCode.right);
					if (keycode == KeyCode.num2) keyDown(event, KeyCode.down);
					if (keycode == KeyCode.num8) keyDown(event, KeyCode.up);
					if (keycode == KeyCode.period) keyDown(event, KeyCode.del);
				});
			}
			public boolean keyTyped(InputEvent event, char character) {
				if (
				 stoppedEvent ||
				 (keyTypedBlock != null && keyTypedBlock.get(event, character))) {
					event.cancel();
					return false;
				}
				trackCursor();
				if (character == BACKSPACE && cursor > 0 && Core.input.ctrl()) {
					int lastCursor = cursor;
					moveCursor(false, true);
					if (cursor < lastCursor) {
						changeText(text, new StringBuilder(text).delete(cursor, lastCursor).toString());
					}
					event.cancel();
					return true;
				}
				return super.keyTyped(event, character);
			}

			public boolean keyUp(InputEvent event, KeyCode keycode) {
				if (keyUpBlock != null && keyUpBlock.get(event, keycode)) {
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

		public void selectForward() {
			int i = hasSelection ? selectionStart : cursor - 1;
			while (isWordCharacterCheck(i)) --i;
			selectionStart = i + 1;
			hasSelection = selectionStart != cursor;
		}
		public void selectBackward() {
			int i = selectionStart = cursor;
			while (isWordCharacterCheck(i)) ++i;
			cursor = i;
			hasSelection = selectionStart != cursor;
		}

		public char charAtUncheck(int i) {
			return text.charAt(i);
		}

		public boolean isWordCharacterCheck(int i) {
			if (i < 0 || i >= text.length()) return false;
			return isWordCharacter(text.charAt(i));
		}
		public boolean isCharCheck(int i, char c) {
			if (i < 0 || i >= text.length()) return false;
			return text.charAt(i) == c;
		}
		public boolean isWordCharacter(char c) {
			return Character.isJavaIdentifierPart(c) || c == '$';
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
			return (float) Mathf.digits(area.getLines()) * numWidth;
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
			font.getColor().a *= parentAlpha * color.a;
			GlyphLayout layout = font.draw(String.valueOf(row), x, offsetY);
			if (realCursorLine == row) {
				float y = offsetY - area.lineHeight();
				Draw.color(Pal.accent);
				Lines.stroke(2);
				Lines.line(x, y, x + layout.width, y);
			}
		}
		public void draw() {
			super.draw();

			int firstLineShowing = area.getRealFirstLineShowing();
			int linesShowing     = area.getRealLinesShowing();
			font = area.getStyle().font;
			boolean had     = font.getData().markupEnabled;
			String  text    = area.getText();
			float   offsetY = getTop() - area.visualRY();

			IntSeq linesBreak = area.getLinesBreak();
			int    row        = 1;
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
			return () -> drawLine(offsetY, row);
		}
	}

	/* public static class CodeTooltip extends IntUI.Tooltip {
		public Table p = new SelectTable(new Table(p -> {
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
		}));
		public CodeTooltip() {
			super(_ -> { });
			always = true;
			container.background(Tex.pane).pane(p).pad(0);
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


	// 等宽字体样式（没有等宽字体就默认字体）
	public static TextFieldStyle MOMO_STYLE = new TextFieldStyle(Styles.defaultField) {{
		font = messageFont = MyFonts.def;
		background = IntUI.emptyui;
		selection = ((TextureRegionDrawable) Tex.selection).tint(Tmp.c1.set(0x4763FFFF));
	}};
}
