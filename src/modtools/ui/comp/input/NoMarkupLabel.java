package modtools.ui.comp.input;

import arc.func.Prov;
import arc.scene.ui.Label;

public class NoMarkupLabel extends Label {
	public NoMarkupLabel(Prov<CharSequence> sup) {
		super(sup);
	}
	public NoMarkupLabel(CharSequence text) {
		super(text);
	}
	public NoMarkupLabel(CharSequence text, LabelStyle style) {
		super(text, style);
	}
	public NoMarkupLabel(float scale) {
		super((CharSequence) null);
		setFontScale(scale);
	}
}
