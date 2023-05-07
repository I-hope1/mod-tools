package modtools.ui.components.input;

import arc.Core;
import arc.func.Prov;
import arc.graphics.g2d.Font.FontData;
import arc.scene.ui.Label;
import arc.util.*;
import modtools.utils.JSFunc.MyProv;

import java.io.*;

import static arc.Core.graphics;

/**
 * a label that disables markup ( [color]文字颜色 )
 */
public class MyLabel extends Label {
	// public static ObjectMap<Font, ObjectMap<String, FontCache>> fontObjectMapObjectMap;
	public @Nullable CacheProv prov;
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
		super(text, style);
		/* for (int i = 0, len = text.length(); i < len; i++) {
			style.font.getData().hasGlyph(text.charAt(i));
		}*/
		/* 有主程序运行，防止不显示 */
		Core.app.post(() -> setText(text));
	}

	public  float interval = 0;
	private float timer    = 0;

	/* 会缓存value的prov */
	public static class CacheProv implements Prov<CharSequence> {
		public Object value;
		public MyProv<Object> prov;
		public CacheProv(MyProv<Object> prov) {
			this.prov = prov;
		}
		public CharSequence get() {
			try {
				value = prov.get();
				return prov.stringify(value);
			} catch (Throwable e) {
				StringWriter sw = new StringWriter();
				PrintWriter  pw = new PrintWriter(sw);
				e.printStackTrace(pw);
				return sw.toString();
			}
		}
	}

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
			layout.height = Math.min(graphics.getHeight(), layout.height);
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

