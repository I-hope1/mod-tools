package modtools.ui.comp;

import arc.Core;
import arc.func.Cons;
import arc.math.Mathf;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import arc.util.*;
import modtools.ui.IntUI;
import modtools.ui.comp.limit.LimitTable;
import modtools.utils.ElementUtils;

/**
 * hover就显示（alpha 1）否则alpha 0
 * 向右对齐，不随pane左右移动而左右移动
 */
public class HoverTable extends LimitTable {
	public boolean stickX, stickY;

	boolean hovered = false;

	{
		IntUI.hoverAndExit(this,
		 () -> hovered = true,
		 () -> hovered = false);
		Core.app.post(this::toFront);
	}

	// float paneLastX = Float.NaN, paneLastY = Float.NaN;
	public void updateVisibility() {
		super.updateVisibility();
		ScrollPane pane = ElementUtils.findClosestPane(this);
		if (pane == null) return;
		int align = getAlign();
		translation.setZero();
		pane.localToStageCoordinates(Tmp.v1.set(pane.getX(align) - pane.x, pane.getY(align) - pane.y));
		/* maxX = min(pane.x(right), scene.x(right) / gw) */
		float maxX = Math.min(Tmp.v1.x, Core.graphics.getWidth()),
		maxY = Math.min(Tmp.v1.y, Core.graphics.getHeight());

		localToStageCoordinates(Tmp.v1.set(getX(align) - x, getY(align) - y));

		if (stickX) {
			/* expectX(scene) = tx + x(scene) ∈ [0, maxX]
			tx = expectX(scene) - x(scene) ∈ [-x, maxX - x] */
			float expectX = Mathf.clamp(translation.x + Tmp.v1.x, 0, maxX);
			translation.x = expectX - Tmp.v1.x;
		}
		if (stickY) {
			float expectY = Mathf.clamp(translation.y + Tmp.v1.y, 0, maxY);
			translation.y = expectY - Tmp.v1.y;
		}
	}
	public void draw() {
		parentAlpha *= hovered ? 1 : 0.3f;
		super.draw();
	}
	public HoverTable(Cons<Table> cons) {
		super(cons);
	}
}
