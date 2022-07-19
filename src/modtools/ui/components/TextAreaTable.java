
package modtools.ui.components;

import arc.Core;
import arc.func.Boolf2;
import arc.graphics.Color;
import arc.graphics.g2d.Font;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.event.ChangeListener.ChangeEvent;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.style.Drawable;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextArea;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.struct.IntSeq;
import arc.util.Time;
import arc.util.pooling.Pools;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;

import java.util.regex.Pattern;

public class TextAreaTable extends Table {
	private final MyTextArea area;
	public static int numWidth = 13;

	public MyTextArea getArea() {
		return area;
	}

	public TextAreaTable(String text) {
		super();

		area = new MyTextArea(text);
		MyScrollPane pane = new MyScrollPane(area);
		area.trackCursor = pane::trackCursor;
		LinesShow linesShow = new LinesShow(area);
		Cell<?> cell = add(linesShow).growY();
		add(pane).grow();
		area.changed(() -> {
			pane.trackCursor();
			Element last = Core.scene.getKeyboardFocus();
			pane.setWidget(area);
			cell.setElement(linesShow);
			if (last == area) Core.scene.setKeyboardFocus(area);
		});
		update(() -> {
			Element focus = Core.scene.getKeyboardFocus();
			if (focus == area) Core.scene.setScrollFocus(pane);
			if ((focus != null && isAscendantOf(focus)) || Core.scene.getScrollFocus() == pane)
				Core.scene.setKeyboardFocus(area);

			area.parentHeight = getHeight();
			area.setFirstLineShowing(0);
		});
	}

	public Boolf2<InputEvent, KeyCode> keyDonwB = null;
	public Boolf2<InputEvent, Character> keyTypedB = null;
	public Boolf2<InputEvent, KeyCode> keyUpB = null;

	public static class MyScrollPane extends ScrollPane {
		MyTextArea area;

		public MyScrollPane(MyTextArea area) {
			super(area);
			this.area = area;
		}

		public void trackCursor() {
			Time.runTask(0f, () -> {
				int cursorLine = area.getCursorLine();
				int firstLineShowing = area.getRealFirstLineShowing();
				int lines = area.getRealLinesShowing();
				int max = firstLineShowing + lines;
				float fontHeight = area.getFontLineHeight();
				if (cursorLine <= firstLineShowing) {
					setScrollY(cursorLine * fontHeight);
				}
				if (cursorLine > max) {
					setScrollY((cursorLine - lines) * fontHeight);
				}
			});
		}

		@Override
		protected void scrollY(float pixelsY) {
			area.scrollY = pixelsY;
//			Log.info(pixelsY);
			super.scrollY(pixelsY);
		}
	}

	public class MyTextArea extends TextArea {
		public float parentHeight = 0;
		public float scrollY = 0;
		public Runnable trackCursor = null;

		public MyTextArea(String text) {
			super(text);
		}

		@Override
		public void setText(String str) {
			super.setText(str);
			fire(new ChangeEvent());
		}

		public float getFontLineHeight() {
			return style.font.getLineHeight();
		}

		@Override
		public float getPrefHeight() {
			float prefHeight = getFontLineHeight() * getLines();
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
			Font font = style.font;
			Drawable background = style.background;
			float availableHeight = parentHeight - (background == null ? 0 : background.getBottomHeight() + background.getTopHeight());
			return (int) Math.floor(availableHeight / font.getLineHeight());
		}

		public int getRealFirstLineShowing() {
			if (true) return (int) Math.floor(scrollY / getFontLineHeight());

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
			font.getData().markupEnabled = false;

			int firstLineShowing = getRealFirstLineShowing();
			float offsetY = -firstLineShowing * font.getLineHeight();
			int linesShowing = getRealLinesShowing() + 1;

			for (int i = firstLineShowing * 2; i < (firstLineShowing + linesShowing) * 2 && i < linesBreak.size; i += 2) {
				font.draw(displayText, x, y + offsetY, linesBreak.items[i], linesBreak.items[i + 1], 0, 8, false);
				offsetY -= font.getLineHeight();
			}


			font.getData().markupEnabled = had;
		}

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
			return to.length() == 0 ? text.toString() : to.substring(0, position) + text + to.substring(position);
		}

		void changeText(String oldText, String newText) {
			if (!newText.equals(oldText)) {
				this.text = newText;
				ChangeEvent changeEvent = (ChangeEvent) Pools.obtain(ChangeEvent.class, ChangeEvent::new);
				boolean cancelled = this.fire(changeEvent);
				this.text = cancelled ? oldText : newText;
				Pools.free(changeEvent);
			}
		}

		public void trackCursor() {
			if (trackCursor != null) trackCursor.run();
		}

		@Override
		protected void moveCursor(boolean forward, boolean jump) {
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
				int start = Math.min(cursor, selectionStart);
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
				if (Pattern.compile("\\s*//").matcher(text.substring(home, end)).find()) {
					int start = home + text.substring(home, end).indexOf("//");
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

	public static class LinesShow extends Table {
		public MyTextArea area;

		public LinesShow(MyTextArea area) {
			super(Tex.buttonRight);
			this.area = area;
		}

		@Override
		public float getPrefWidth() {
			// （行数+1）*数字宽度
			return (area.getLines() + "1").length() * numWidth;
		}


		@Override
		public void draw() {
			super.draw();
			int firstLineShowing = area.getRealFirstLineShowing();
			float lineHeight = area.getFontLineHeight();
			float scrollOffsetY = area.scrollY - (int) (area.scrollY / lineHeight) * lineHeight;
			float y2 = getTop() - getBackground().getTopHeight() + scrollOffsetY;
//			Log.info(scrollOffsetY);
			final Font font = area.getStyle().font;

			boolean had = font.getData().markupEnabled;
			font.getData().markupEnabled = false;
			float[] offsetY = {0};
			int[] cline = {firstLineShowing + 1};
			int linesShowing = area.getRealLinesShowing() + 1;
			IntSeq linesBreak = area.getLinesBreak();
			int cursorLine = area.getCursorLine() + 1;
			Runnable drawLine = () -> {
				font.setColor(cursorLine == cline[0] ? Pal.accent : Color.lightGray);
				font.draw("" + cline[0], x, y2 + offsetY[0]);
			};
			for (int i = firstLineShowing * 2; i < (firstLineShowing + linesShowing) * 2 && i < linesBreak.size; i += 2) {
				drawLine.run();
				cline[0]++;
				offsetY[0] -= font.getLineHeight();
			}
			if (area.newLineAtEnd()) {
				drawLine.run();
			}
			font.getData().markupEnabled = had;
		}
	}
}
