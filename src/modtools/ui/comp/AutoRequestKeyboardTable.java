package modtools.ui.comp;

import arc.scene.Element;
import modtools.ui.comp.limit.LimitTable;

public class AutoRequestKeyboardTable extends LimitTable {
	private final Element focus;
	public AutoRequestKeyboardTable(Element focus) { this.focus = focus; }

	public Element hit(float x, float y, boolean touchable) {
		Element element = super.hit(x, y, touchable);
		if (element != null && element.isDescendantOf(this)) focus.requestKeyboard();
		return element;
	}

}
