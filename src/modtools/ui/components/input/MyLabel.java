package modtools.ui.components.input;

import arc.func.Prov;
import arc.graphics.g2d.*;
import arc.graphics.g2d.Font.FontData;
import arc.struct.ObjectMap;
import arc.util.*;
import modtools.ui.components.limit.LimitLabel;

/**
 * a label that disables markup ( [color]文字颜色 )
 */
public class MyLabel extends LimitLabel {
	public static ObjectMap<Font, ObjectMap<String, FontCache>> fontObjectMapObjectMap;
	public MyLabel(Prov<CharSequence> sup) {
		super(sup);
	}
	public MyLabel(Prov<CharSequence> sup, float interval) {
		super(sup);
		this.interval = interval;
	}
	public MyLabel(CharSequence text) {
		super(text);
	}
	public MyLabel(CharSequence text, LabelStyle style) {
		super(null, style);
		/* for (int i = 0, len = text.length(); i < len; i++) {
			style.font.getData().hasGlyph(text.charAt(i));
		}*/
		/* 异步，防止不显示 */
		Time.runTask(0, () -> setText(text));
	}

	public  float interval = 0;
	private float timer    = 0;

	public void setText(Prov<CharSequence> sup) {
		// super.setText(sup);
		update(() -> {
			if ((timer += Time.delta) > interval) {
				timer = 0;
				setText(sup.get());
			}
		});
	}

	public boolean markupEnabled = false;

	public void layout() {
		if (markupEnabled) {
			super.layout();
			return;
		}
		// invalidate();
		FontData fontData = style.font.getData();

		boolean had = fontData.markupEnabled;
		fontData.markupEnabled = false;
		super.layout();
		getPrefWidth();
		fontData.markupEnabled = had;
	}
}

