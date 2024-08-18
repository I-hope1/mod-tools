package modtools.ui.comp.limit;

import arc.Core;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.ui.ScrollPane;
import modtools.utils.ElementUtils;

public interface Limit {
	Vec2 v1 = new Vec2(), v2 = new Vec2();

	static boolean isVisible(Element actor) {
		ScrollPane pane = ElementUtils.findClosestPane(actor);
		if (pane == null) return true;


		float w = actor.getWidth(), h = actor.getHeight();

		actor.localToAscendantCoordinates(pane, v1.set(0, 0));

		/* 获取pane的绝对坐标 */
		boolean computeIfOverStage = v1.x + pane.getWidth() > Core.graphics.getWidth()
		                             || v1.y + pane.getHeight() < Core.graphics.getHeight()
		                             || v1.x < 0 || v1.y < 0;

		/* w, h > 0 */
		if (computeIfOverStage) {
			actor.localToStageCoordinates(v2.set(0, 0));
			if (v2.x < -w || v2.y < -h || v2.x > Core.graphics.getWidth() || v2.y > Core.graphics.getHeight()) {
				return false;
			}
		}

		/* 获取actor相对于pane的坐标 */
		return v1.x > -w && v1.y > -h &&
		       v1.x < pane.getWidth() && v1.y < pane.getHeight();
		// return v1.x > -w && v1.y > -h && v1.x < w + elem.getWidth() && v1.y < h + elem.getHeight();
	}
}
