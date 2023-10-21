package modtools.ui.components.limit;

import arc.Core;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.ui.ScrollPane;
import arc.util.Log;

import java.util.Locale;

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
		Element elem = actor.parent;
		while (!(elem instanceof ScrollPane)) {
			if (elem == null) return false;
			elem = elem.parent;
		}

		absPos(elem, v1);
		boolean computeIfOverStage = v1.x + elem.getWidth() > Core.graphics.getWidth()
																 || v1.y + elem.getHeight() < Core.graphics.getHeight()
																 || v1.x < 0 || v1.y < 0;

		/* w, h > 0 */
		float w = actor.getWidth(), h = actor.getHeight();
		if (computeIfOverStage) {
			absPos(elem, v2);
			if (v2.x < -w || v2.y < -h || v2.x > Core.graphics.getWidth() || v2.y > Core.graphics.getHeight()) {
				return false;
			}
		}
		actor.localToAscendantCoordinates(elem, v1.set(0, 0));
		return v1.x > -w && v1.y > -h && v1.x < elem.getWidth() && v1.y < elem.getHeight();
		// return v1.x > -w && v1.y > -h && v1.x < w + elem.getWidth() && v1.y < h + elem.getHeight();
	}
	static void absPos(Element elem, Vec2 vec2) {
		// if (elem.parent instanceof LimitTable l) {
		// 	elem.localToParentCoordinates(vec2.set(l.absPos));
		// } else {
		elem.localToStageCoordinates(vec2.set(0, 0));
		// }
	}
}
