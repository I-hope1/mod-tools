package modtools.utils.ui;

import arc.Core;
import arc.func.Cons;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import arc.util.*;
import modtools.ui.components.limit.LimitTable;
import modtools.utils.ElementUtils;

/**
 * hover就显示（alpha 1）否则alpha 0
 * 向右对齐，不随pane左右移动而左右移动
 */
public class HoverTable extends LimitTable {
	public boolean stickX, stickY;

	boolean hovered = false;

	{
		hovered(() -> hovered = true);
		exited(() -> hovered = false);
		Time.runTask(0, this::toFront);
	}

	// float paneLastX = Float.NaN, paneLastY = Float.NaN;
	public void updateVisibility() {
		super.updateVisibility();
		ScrollPane pane = ElementUtils.findParentPane(this);
		if (pane != null) {
			if (stickX) {
				translation.x = -getX(Align.right) + pane.getVisualScrollX() + pane.getScrollWidth();
				localToStageCoordinates(Tmp.v1.set(width, 0));
				translation.x += Math.min(0, Core.graphics.getWidth() - Tmp.v1.x);
			}
		}
		// if (stickY) translation.y = -getX(Align.right) + pane.getVisualScrollX() + pane.getScrollWidth();
	}
	public void draw() {
		parentAlpha *= hovered ? 1 : 0.3f;
		super.draw();
	}
	public HoverTable(Cons<Table> cons) {
		super(cons);
	}
}
