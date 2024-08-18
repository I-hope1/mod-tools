package modtools.ui.comp.limit;

import arc.func.Prov;
import arc.scene.ui.Label;

import static modtools.ui.comp.limit.Limit.isVisible;

public class LimitLabel extends Label implements Limit {
	public LimitLabel(Prov<CharSequence> sup) {
		super(sup);
	}
	public LimitLabel(CharSequence text) {
		super(text);
	}
	public LimitLabel(CharSequence text, LabelStyle style) {
		super(text, style);
	}

	@Override
	public void updateVisibility() {
		super.updateVisibility();
		visible = isVisible(this);
	}
}
