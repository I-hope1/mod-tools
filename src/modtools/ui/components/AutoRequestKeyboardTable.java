package modtools.ui.components;

import arc.scene.Element;
import mindustry.Vars;
import modtools.ui.components.limit.LimitTable;

public class AutoRequestKeyboardTable extends LimitTable {
	private final Element focus;
	public AutoRequestKeyboardTable(Element focus) {this.focus = focus;}
	public Element hit(float x, float y, boolean touchable) {
		Element element = super.hit(x, y, touchable);
		if (element == null) return null;
		if (Vars.mobile && element.isDescendantOf(this)) focus.requestKeyboard();
		return element;
	}
}
