package modtools.utils.ui;

import arc.Core;
import arc.func.Cons;
import arc.math.Mathf;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import arc.util.Tmp;
import modtools.ui.IntUI;
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
		localToStageCoordinates(Tmp.v1.set(-getX(align), -getY(align)));
		if (stickX) {
			translation.x = -getX(align) + pane.getVisualScrollX() + pane.getScrollWidth();
			/* expect: rx = tx + x(scene) ∈ [0, gw]
			tx = rx - x(scene) ∈ [-x, gw - x] */
			float rx = Mathf.clamp(Tmp.v1.x, 0, Core.graphics.getWidth() - getX(align));
			translation.x = Math.min(0, rx - Tmp.v1.x);
		}
		if (stickY) {
			translation.y = -getY(align) + pane.getVisualScrollY() + pane.getScrollHeight();
			/* expect: ry = ty + y(scene) ∈ [0, gh]
			ty = ry - y(scene) ∈ [-y, gh - y] */
			float ry = Mathf.clamp(Tmp.v1.y, 0, Core.graphics.getHeight() - getY(align));
			translation.y = Math.min(0, ry - Tmp.v1.y);
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
