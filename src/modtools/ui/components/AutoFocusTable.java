package modtools.ui.components;

import arc.scene.Element;
import arc.scene.ui.layout.Table;
import mindustry.Vars;

public class AutoFocusTable extends Table {
	private final Element focus;
	public AutoFocusTable(Element focus) {this.focus = focus;}
	public Element hit(float x, float y, boolean touchable) {
		Element element = super.hit(x, y, touchable);
		if (element == null) return null;
		if (Vars.mobile && element.isDescendantOf(this)) focus.requestKeyboard();
		return element;
	}
}
