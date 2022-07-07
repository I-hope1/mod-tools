
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
import arc.util.Time;
import arc.util.pooling.Pools;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;

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

	public static class MyScrollPane extends ScrollPane {
		MyTextArea area;

		public MyScrollPane(MyTextArea area) {
			super(area);
			this.area = area;
		}

		public void trackCursor() {
			int cursorLine = area.getCursorLine();
			int firstLineShowing = area.getRealFirstLineShowing();
			int lines = area.getRealLinesShowing();
			int max = firstLineShowing + lines;
			float fontHeight = area.getStyle().font.getLineHeight();
			if (cursorLine <= firstLineShowing) {
				setScrollY(cursorLine * fontHeight);
			}
			if (area.newLineAtEnd()) cursorLine++;
			if (cursorLine > max) {
				setScrollY((cursorLine - lines) * fontHeight);
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

		public void left() {
			moveCursor(false, false);
		}

		public void right() {
			moveCursor(true, false);
		}

		String insert(int position, CharSequence text, String to) {
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
				int oldCursor = cursor;
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
					int home = linesBreak.get(cursorLine * 2);
					if (text.startsWith("//", home))
						changeText(text, text.substring(0, home) + text.substring(home + 2));
					else changeText(text, insert(home, "//", text));
					updateDisplayText();
				}

				return super.keyDown(event, keycode);
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
