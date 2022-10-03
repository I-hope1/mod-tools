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
	public void setStyle(LabelStyle style) {
		if (style == null) throw new IllegalArgumentException("style cannot be null.");
		if (style.font == null) throw new IllegalArgumentException("Missing LabelStyle font.");
		Font.FontData fontData = style.font.getData();
		boolean had = fontData.markupEnabled;
		fontData.markupEnabled = false;
		this.style = style;
		cache = style.font.newFontCache();
		invalidateHierarchy();
		fontData.markupEnabled = had;
	}
}
