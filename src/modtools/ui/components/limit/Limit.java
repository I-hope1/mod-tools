package modtools.ui.components.limit;

import arc.Core;
import arc.scene.Element;
import arc.scene.ui.ScrollPane;
import arc.util.Tmp;

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
	static boolean isVisible(Element actor) {
		Element elem = actor.parent;
		while (!(elem instanceof ScrollPane)) {
			elem = elem.parent;
			if (elem == null) return false;
		}
		elem.localToStageCoordinates(Tmp.v1.set(0, 0));
		boolean computeIfOverStage = Tmp.v1.x + elem.getWidth() > Core.graphics.getWidth()
				|| Tmp.v1.y + elem.getHeight() < Core.graphics.getHeight()
				|| Tmp.v1.x < 0 || Tmp.v1.y < 0;
		float w = actor.getWidth(), h = actor.getHeight();
		if (computeIfOverStage) {
			actor.localToStageCoordinates(Tmp.v1.set(0, 0));
			if (Tmp.v1.x < -w || Tmp.v1.y < -h || Tmp.v1.x > Core.graphics.getWidth() || Tmp.v1.y > Core.graphics.getHeight()) {
				return false;
			}
		}
		// localToStageCoordinates(Tmp.v1.set(0, 0));
		actor.localToAscendantCoordinates(elem, Tmp.v1.set(0, 0));
		return Tmp.v1.x >= -w && Tmp.v1.y >= -h && Tmp.v1.x <= elem.getWidth() && Tmp.v1.y <= elem.getHeight();
		// actor.localToAscendantCoordinates(elem, Tmp.v1.set(w, h));
		// localToStageCoordinates(Tmp.v1.set(w, h));
	}
}
