
package modtools.ui.components.area;

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
import modtools.ui.components.highlight.*;

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
	public final MyScrollPane pane;
	/**
	 * 编辑器是否只可读
	 **/
	public boolean readOnly = false,
	/**
	 * 编辑器是否显示行数
	 */
	showLine = true;
	public Syntax syntax;
	public static int numWidth = 13;

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
		Cell<?> cell = add(linesShow).growY().left();
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
	/*@Override
	public void draw() {
		super.draw();
		area.draw();
	}*/

	public Boolf2<InputEvent, KeyCode> keyDonwB = null;
	public Boolf2<InputEvent, Character> keyTypedB = null;
	public Boolf2<InputEvent, KeyCode> keyUpB = null;

	public void focus() {
		Core.scene.setKeyboardFocus(area);
	}

	public class MyScrollPane extends ScrollPane {
		public MyScrollPane() {
			super(area);
		}

		public void trackCursor() {
			// Time.runTask(0f, () -> {
			int cursorLine = area.getCursorLine();
			int firstLineShowing = area.getRealFirstLineShowing();
			int lines = area.getRealLinesShowing();
			int max = firstLineShowing + lines;
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

	/**
	 * 用于语法高亮<br>
	 * patternSeq匹配字符，colorSeq用于绘制颜色
	 **//*
	public Seq<Pattern> patternSeq = null;
	public Seq<MyMatcher> matcherSeq = new Seq<>();
	public Seq<Color> colorSeq = null;*/
	public boolean enableHighlighting = true;

	private static final Pattern startComment = Pattern.compile("\\s*//");

	public static final float fontWidth = 12;

	public class MyTextArea extends TextArea {
		public float parentHeight = 0;
		private float scrollY = 0;
		public Runnable trackCursor = null;

		public float lineHeight() {
			return style.font.getLineHeight();
		}

		public MyTextArea(String text) {
			super(text);
		}

		@Override
		public void setText(String str) {
			super.setText(str);
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
				float offsetY = -firstLineShowing * lineHeight();
				int max = (firstLineShowing + linesShowing) * 2;
				for (int i = firstLineShowing * 2; i < max && i < linesBreak.size; i += 2) {
					font.draw(displayText, x, y + offsetY, linesBreak.get(i), linesBreak.get(i + 1),
							0, Align.left, false).free();
					offsetY -= lineHeight();
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
		public Font font = null;

		/*public void highlightingDraw(Font font, float x, float y) {
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
			String displayText = text.substring(displayTextStart, displayTextEnd);

			*//*int lastIndex;
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

			Draw.color();*//*
			// for (int i = firstLineShowing * 2; i < (firstLineShowing + linesShowing) * 2 && i < linesBreak.size; i += 2) {
			// String line = displayText.substring(home, end);
			MyMatcher m;
			matcherSeq.clear();
			for (var p : patternSeq) {
				m = new MyMatcher(p.matcher(displayText));
				// if (p == JSSyntax.commentP) m.multi = true;
				matcherSeq.add(m);
			}
			String group;
			int lastIndex = 0;
			int length = displayText.length();
			MyMatcher currentMatcher;
			int currentIndex = 0;
			Color currentColor;
			while (lastIndex < length) {
				currentMatcher = null;
				int res = -1;
				// int end = displayText.indexOf(lastIndex);
				currentIndex = -1;
				// int rowEnd = -2;
				for (int j = 0, size = patternSeq.size; j < size; j++) {
					m = matcherSeq.get(j);
					// if (!m.multi && rowEnd == -2) rowEnd = displayText.indexOf('\n', lastIndex);
					if (m.found(lastIndex))
						m.matcher.region(lastIndex, currentIndex != -1 ? currentIndex - 1 : *//*m.multi || rowEnd == -1 ? length : rowEnd*//*length);
					if (m.find()) {
						// Log.info("old:" + currentMatcher + "," + currentIndex);
						currentMatcher = m;
						// Log.info("now:" + currentMatcher + "," + (currentIndex - 1));
						currentIndex = m.start();
						res = j;
						if (currentIndex == lastIndex) break;
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
			if (lastIndex < displayText.length()) {
				font.setColor(Color.white);
				drawMultiText(displayText, lastIndex, displayText.length());
			}
			// offsetY[0] -= font.getLineHeight();
			// }

			// glyphPositions.get(cursor) - glyphPositions.get(0) + fontOffset + style.font.getData().cursorX
			// font.draw(, x, y + offsetY, linesBreak.items[i], linesBreak.items[i + 1], 0, 8, false);
			Draw.color();
		}*/

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


		/*@Override
		protected void drawSelection(Drawable selection, Font font, float x, float y) {

		}*/

		@Override
		public void updateDisplayText() {
			super.updateDisplayText();
		}

		/*@Override
		public void updateDisplayText() {
			Font font = style.font;
			FontData data = font.getData();
			String text = this.text;
			int textLength = text.length();

			*//*StringBuilder buffer = new StringBuilder();
			for (int i = 0; i < textLength; i++) {
				char c = text.charAt(i);
				buffer.append(data.hasGlyph(c) ? c : ' ');
			}
			String newDisplayText = buffer.toString();*//*
			String newDisplayText = text;

			if (passwordMode && data.hasGlyph(passwordCharacter)) {
				if (passwordBuffer == null) passwordBuffer = new StringBuilder(newDisplayText.length());
				if (passwordBuffer.length() > textLength)
					passwordBuffer.setLength(textLength);
				else {
					passwordBuffer.append(String.valueOf(passwordCharacter).repeat(Math.max(0, textLength - passwordBuffer.length())));
				}
				displayText = passwordBuffer;
			} else
				displayText = newDisplayText;

			// layout.setText(font, displayText);
			glyphPositions.clear();
			for (int i = 0; i <= textLength; i++) {
				glyphPositions.add(i * fontWidth);
			}

			visibleTextStart = Math.min(visibleTextStart, glyphPositions.size);
			visibleTextEnd = Mathf.clamp(visibleTextEnd, visibleTextStart, glyphPositions.size);

			if (selectionStart > newDisplayText.length()) selectionStart = textLength;
		}*/

		/*protected void calculateOffsets() {
			float visibleWidth = getWidth();
			Drawable background = getBackgroundDrawable();
			if (background != null) visibleWidth -= background.getLeftWidth() + background.getRightWidth();

			int glyphCount = glyphPositions.size;
			float[] glyphPositions = this.glyphPositions.items;

			// Check if the cursor has gone out the left or right side of the visible area and adjust renderOffset.
			cursor = Mathf.clamp(cursor, 0, glyphPositions.length - 1);
			float distance = glyphPositions[Math.max(0, cursor - 1)] + renderOffset;
			if (distance <= 0)
				renderOffset -= distance;
			else {
				int index = Math.min(glyphCount - 1, cursor + 1);
				float minX = glyphPositions[index] - visibleWidth;
				if (-renderOffset < minX) renderOffset = -minX;
			}

			// Prevent renderOffset from starting too close to the end, eg after text was deleted.
			float maxOffset = 0;
			float width = glyphPositions[Mathf.clamp(glyphCount - 1, 0, glyphPositions.length - 1)];
			for (int i = glyphCount - 2; i >= 0; i--) {
				float x = glyphPositions[i];
				if (width - x > visibleWidth) break;
				maxOffset = x;
			}
			if (-renderOffset > maxOffset) renderOffset = -maxOffset;

			// calculate first visible char based on render offset
			visibleTextStart = 0;
			float startX = 0;
			for (int i = 0; i < glyphCount; i++) {
				if (glyphPositions[i] >= -renderOffset) {
					visibleTextStart = i;
					startX = glyphPositions[i];
					break;
				}
			}

			// calculate last visible char based on visible width and render offset
			int length = Math.min(displayText.length(), glyphPositions.length - 1);
			visibleTextEnd = Math.min(length, cursor + 1);
			for (; visibleTextEnd <= length; visibleTextEnd++)
				if (glyphPositions[visibleTextEnd] > startX + visibleWidth) break;
			visibleTextEnd = Math.max(0, visibleTextEnd - 1);

			if ((textHAlign & Align.left) == 0) {
				textOffset = visibleWidth - (glyphPositions[visibleTextEnd] - startX);
				if ((textHAlign & Align.center) != 0) textOffset = Math.round(textOffset * 0.5f);
			} else
				textOffset = startX + renderOffset;

			// calculate selection x position and width
			if (hasSelection) {
				int minIndex = Math.min(cursor, selectionStart);
				int maxIndex = Math.max(cursor, selectionStart);
				float minX = Math.max(glyphPositions[minIndex] - glyphPositions[visibleTextStart], -textOffset);
				float maxX = Math.min(glyphPositions[maxIndex] - glyphPositions[visibleTextStart], visibleWidth - textOffset);
				selectionX = minX;
				selectionWidth = maxX - minX - style.font.getData().cursorX;
			}

			if (!this.text.equals(lastText)) {
				this.lastText = text;
				Font font = style.font;
				float maxWidthLine = this.getWidth()
						- (style.background != null ? style.background.getLeftWidth() + style.background.getRightWidth() : 0);
				linesBreak.clear();
				int lineStart = 0;
				int lastSpace = 0;
				char lastCharacter;
				GlyphLayout layout = Pools.obtain(GlyphLayout.class, GlyphLayout::new);
				layout.ignoreMarkup = true;
				for (int i = 0; i < text.length(); i++) {
					lastCharacter = text.charAt(i);
					if (lastCharacter == '\n' || lastCharacter == '\r') {
						linesBreak.add(lineStart);
						linesBreak.add(i);
						lineStart = i + 1;
					} else {
						lastSpace = continueCursor(i, 0) ? lastSpace : i;
						layout.setText(font, text.subSequence(lineStart, i + 1));
						if (layout.width > maxWidthLine) {
							if (lineStart >= lastSpace) {
								lastSpace = i - 1;
							}
							linesBreak.add(lineStart);
							linesBreak.add(lastSpace + 1);
							lineStart = lastSpace + 1;
							lastSpace = lineStart;
						}
					}
				}
				Pools.free(layout);
				// Add last line
				if (lineStart < text.length()) {
					linesBreak.add(lineStart);
					linesBreak.add(text.length());
				}
				trackCursor();
			}
		}
		private Drawable getBackgroundDrawable() {
			Scene stage = getScene();
			boolean focused = stage != null && stage.getKeyboardFocus() == this;
			return (disabled && style.disabledBackground != null) ? style.disabledBackground
					: (!isValid() && style.invalidBackground != null) ? style.invalidBackground
					: ((focused && style.focusedBackground != null) ? style.focusedBackground : style.background);
		}*/

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
			boolean cancelled = fire(changeEvent);
			text = cancelled ? oldText : newText;
			Pools.free(changeEvent);
			return !cancelled;
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
				int end = linesBreak.get(Math.min(linesBreak.size - 1, (cursorLine + 1) * 2));
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
			float scrollOffsetY = area.scrollY - (int) (area.scrollY / area.lineHeight()) * area.lineHeight();
			float offsetY = getTop() - getBackground().getTopHeight() + scrollOffsetY;
			IntSeq linesBreak = area.getLinesBreak();
			int row = 1;
			font.getData().markupEnabled = false;
			realCurrorLine = 0;
			int cursorLine = area.getCursorLine() * 2;
			Runnable task = getTask(offsetY, row);
			int i = 0;
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
