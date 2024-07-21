package modtools.ui.comp.input;

import arc.func.Prov;
import arc.graphics.g2d.Font.FontData;
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
		super("");
		setFontScale(scale);
	}
	public void layout() {
		if (cache == null) return;
		FontData fontData = cache.getFont().getData();

		boolean had = fontData.markupEnabled;
		fontData.markupEnabled = false;
		super.layout();
		/* 重新计算pref width  */
		prefSizeInvalid = true;
		getPrefWidth();
		fontData.markupEnabled = had;
	}
}
