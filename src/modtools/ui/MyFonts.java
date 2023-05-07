package modtools.ui;

import arc.files.Fi;
import arc.freetype.FreeTypeFontGenerator;
import arc.freetype.FreeTypeFontGenerator.*;
import arc.graphics.g2d.Font;
import mindustry.ui.Fonts;

import static modtools.utils.MySettings.*;

public class MyFonts {
	public static       Font def;
	public static final Fi   fontDirectory = dataDirectory.child("fonts");

	static {
		load();
	}

	public static void load() {
		def = acquireFont();
	}

	private static Font acquireFont() {
		if (def != null) return Fonts.def;
		if (!SETTINGS.containsKey("font")) return Fonts.def;

		Fi fontFi = fontDirectory.child(SETTINGS.getString("font"));
		if (!fontFi.exists()) {
			if (Fonts.def == null) throw new RuntimeException("you can't load it before font load");
			return Fonts.def;
		}
		FreeTypeFontGenerator generator = new FreeTypeFontGenerator(fontFi);
		FreeTypeFontParameter parameter = new FreeTypeFontParameter() {{
			size = 20;
			incremental = true;
		}};
		return generator.generateFont(parameter, new FreeTypeFontData() {{
			markupEnabled = true;
		}});
	}
}
