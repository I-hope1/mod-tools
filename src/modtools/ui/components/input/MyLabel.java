package modtools.ui.components.input;

import arc.Core;
import arc.func.Prov;
import arc.graphics.g2d.Font.FontData;
import arc.scene.ui.Label;
import arc.util.*;
import modtools.utils.JSFunc.MyProv;

import java.io.*;

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
	public static class CacheProv implements Prov<Object> {
		public MyProv<Object> prov;
		public Object         value;
		public CacheProv(MyProv<Object> prov) {
			this.prov = prov;
		}
		public Object get() {
			try {
				return value = prov.get();
			} catch (Throwable e) {
				StringWriter sw = new StringWriter();
				PrintWriter  pw = new PrintWriter(sw);
				e.printStackTrace(pw);
				return sw.toString();
			}
		}
		public Prov<CharSequence> getStringProv() {
			return () -> prov.stringify(get());
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
		FontData fontData = style.font.getData();

		boolean had = fontData.markupEnabled;
		fontData.markupEnabled = markupEnabled;
		super.layout();
		/* 重新计算pref width  */
		prefSizeInvalid = true;
		getPrefWidth();
		fontData.markupEnabled = had;
	}
}

