package modtools.ui.components;

import arc.func.Prov;
import arc.graphics.g2d.Font;
import arc.scene.ui.Label;

public class MyLabel extends Label {
	public MyLabel(Prov<CharSequence> sup) {
		super(sup);
	}

	public MyLabel(CharSequence text) {
		super(text);
	}

	public MyLabel(CharSequence text, LabelStyle style) {
		super(text, style);
	}

	@Override
	public void layout() {
		Font.FontData fontData = style.font.getData();
		boolean had = fontData.markupEnabled;
		fontData.markupEnabled = false;
		super.layout();
		fontData.markupEnabled = had;
	}
}
