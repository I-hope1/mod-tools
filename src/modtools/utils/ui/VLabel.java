package modtools.utils.ui;

import arc.graphics.Color;
import modtools.ui.comp.input.NoMarkupLabel;

public class VLabel extends NoMarkupLabel {
	public VLabel(float scale, Color color) {
		super(scale);
		setColor(color);
	}
	public VLabel(CharSequence text, Color color) {
		super(text);
		setColor(color);
	}
}
