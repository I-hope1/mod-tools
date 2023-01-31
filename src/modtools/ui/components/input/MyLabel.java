package modtools.ui.components.input;

import arc.*;
import arc.func.Prov;
import arc.graphics.g2d.Font;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.ui.*;
import arc.struct.Seq;
import arc.util.*;
import mindustry.game.EventType.Trigger;
import modtools.ui.components.limit.LimitLabel;

/**
 * a label that disables markup ( [color]文字颜色 )
 */
public class MyLabel extends LimitLabel {
	public MyLabel(Prov<CharSequence> sup) {
		super(sup);
	}
	public MyLabel(CharSequence text) {
		super(text);
	}
	public MyLabel(CharSequence text, LabelStyle style) {
		super(text, style);
	}

	public  float interval = 0;
	private float timer    = 0;

	public void setText(Prov<CharSequence> sup) {
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
		prefSizeInvalid = true;
		Font.FontData fontData = style.font.getData();
		boolean       had      = fontData.markupEnabled;
		fontData.markupEnabled = false;
		super.layout();
		getPrefWidth();
		fontData.markupEnabled = had;
	}
}

