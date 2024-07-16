package modtools.ui.components.linstener;

import arc.scene.style.Drawable;
import arc.scene.ui.ScrollPane;

/** 自动将widget的width设置为pane的width  */
public class AutoWrapListener implements Runnable {
	ScrollPane pane;
	public AutoWrapListener(ScrollPane pane) {
		this.pane = pane;
	}
	@Override
	public void run() {
		if (pane.getWidget() != null) {
			Drawable background = pane.getStyle().background;
			float sub = 0;
			if (background != null) {
				sub += background.getLeftWidth();
				sub += background.getRightWidth();
			}
			pane.getWidget().setWidth(pane.getWidth() - sub - pane.getScrollBarWidth());
		}
	}
}
