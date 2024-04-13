package modtools.ui.components.limit;

import arc.Core;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.ui.ScrollPane;
import modtools.utils.ElementUtils;

public interface Limit {
	/*static boolean isVisible(Element actor) {
		Element elem = actor.parent;
		while (!(elem instanceof ScrollPane)) {
			elem = elem.parent;
			if (elem == null) return false;
		}
		float w = actor.getWidth(), h = actor.getHeight();

		actor.localToAscendantCoordinates(elem, Tmp.v1.set(0, 0));
		if (Tmp.v1.x >= -w || Tmp.v1.y >= -h) return true;
		actor.localToAscendantCoordinates(elem, Tmp.v2.set(w, h));
		return Tmp.v1.x + Tmp.v2.x <= elem.getWidth() || Tmp.v2.x + Tmp.v2.y <= elem.getHeight();
	}*/
	Vec2 v1 = new Vec2(), v2 = new Vec2();

	static boolean isVisible(Element actor) {
		ScrollPane pane = ElementUtils.findParentPane(actor);
		if (pane == null) return false;

		/* 获取pane的绝对坐标 */
		pane.localToStageCoordinates(v1.set(0, 0));
		boolean computeIfOverStage = v1.x + pane.getWidth() > Core.graphics.getWidth()
																 || v1.y + pane.getHeight() < Core.graphics.getHeight()
																 || v1.x < 0 || v1.y < 0;

		/* w, h > 0 */
		float w = actor.getWidth(), h = actor.getHeight();
		if (computeIfOverStage) {
			actor.localToStageCoordinates(v2.set(pane.getVelocityX(), pane.getVelocityY()));
			if (v2.x < -w || v2.y < -h || v2.x > Core.graphics.getWidth() || v2.y > Core.graphics.getHeight()) {
				return false;
			}
		}
		/* 获取actor相对于pane的坐标 */
		actor.localToAscendantCoordinates(pane, v1.set(pane.getVelocityX(), pane.getVelocityY()));
		return v1.x > -w && v1.y > -h && v1.x < pane.getWidth() && v1.y < pane.getHeight();
		// return v1.x > -w && v1.y > -h && v1.x < w + elem.getWidth() && v1.y < h + elem.getHeight();
	}
}
