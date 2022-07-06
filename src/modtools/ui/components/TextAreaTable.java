
package modtools.ui.components;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Font;
import arc.input.KeyCode;
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
import arc.util.Log;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;

import java.util.Objects;

public class TextAreaTable extends Table {
	private final MyTextArea area;
	public static int numWidth = 13;

	public TextArea getArea() {
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

	public static class MyScrollPane extends ScrollPane {
		MyTextArea area;

		public MyScrollPane(MyTextArea area) {
			super(area);
			this.area = area;
		}

		public void trackCursor() {
			int cursorLine = area.getCursorLine();
			int firstLineShowing = area.getRealFirstLineShowing();
			int max = firstLineShowing + area.getRealLinesShowing();
			float fontHeight = area.getStyle().font.getLineHeight();
			if (cursorLine <= firstLineShowing) {
				setScrollY(cursorLine * fontHeight);
			}
			if (cursorLine > max) {
				setScrollY(cursorLine * fontHeight);
			}
		}

		@Override
		protected void scrollY(float pixelsY) {
			area.scrollY = pixelsY;
//			Log.info(pixelsY);
			super.scrollY(pixelsY);
		}
	}

	public static class MyTextArea extends TextArea {
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

		@Override
		public float getPrefHeight() {
			float prefHeight = style.font.getLineHeight() * getLines();
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
			if (true) return (int) Math.floor(scrollY / style.font.getLineHeight());

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
		public void addInputDialog() {
		}

		protected InputListener createInputListener() {
			return new MyTextAreaListener();
		}


		public class MyTextAreaListener extends TextAreaListener {
			@Override
			public boolean keyDown(InputEvent event, KeyCode keycode) {
				boolean jump = Core.input.ctrl();
				if (event != null) {
					char character = event.character;
					Log.info(keycode);
					// 排除NumLk时，输出数字
					boolean valid = character == '\0' || !Objects.equals(keycode.value, "" + character);
					if (valid) {
						// end
						if (keycode == KeyCode.num1) goEnd(jump);
						// home
						if (keycode == KeyCode.num7) goHome(jump);
						// left
						if (keycode == KeyCode.num4) cursor -= 1;
						// right
						if (keycode == KeyCode.num6) cursor += 1;
						// down
						if (keycode == KeyCode.num2) moveCursorLine(cursorLine - 1);
						// up
						if (keycode == KeyCode.num8) moveCursorLine(cursorLine + 1);
					}
				}
				return super.keyDown(event, keycode);
			}

			@Override
			public boolean keyUp(InputEvent event, KeyCode keycode) {
				if (trackCursor != null) {
					switch (keycode) {
						case up:
						case down:
							trackCursor.run();
					}
				}

				// 修复
				boolean res = super.keyUp(event, keycode);
				if (event != null) event.character = '\0';
				return res;
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
			float scrollOffsetY = area.scrollY / area.getStyle().font.getLineHeight() - firstLineShowing;
			float y2 = getTop() - getBackground().getTopHeight() + scrollOffsetY * 2;
//			Log.info(scrollOffsetY);
			final Font font = area.getStyle().font;

			boolean had = font.getData().markupEnabled;
			font.getData().markupEnabled = false;
			float[] offsetY = {0};
			int[] cline = {firstLineShowing + 1};
			int linesShowing = area.getRealLinesShowing();
			IntSeq linesBreak = area.getLinesBreak();
			int cursorLine = area.getCursorLine() + 1;
			Runnable drawLine = () -> {
				if (cursorLine == cline[0]) font.setColor(Pal.accent);
				font.draw("" + cline[0], x, y2 + offsetY[0]);
				font.setColor(Color.white);
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
