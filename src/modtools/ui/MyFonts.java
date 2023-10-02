package modtools.ui;

import arc.files.Fi;
import arc.freetype.FreeType.Stroker;
import arc.freetype.FreeTypeFontGenerator;
import arc.freetype.FreeTypeFontGenerator.*;
import arc.graphics.g2d.*;
import arc.graphics.g2d.Font.Glyph;
import arc.struct.Seq;
import arc.util.Reflect;
import mindustry.ui.Fonts;
import modtools.utils.Tools;

import java.lang.reflect.Method;

import static modtools.utils.MySettings.*;

public class MyFonts {
	public static       Font def;
	public static final Fi   fontDirectory = dataDirectory.child("fonts");

	static {
		fontDirectory.mkdirs();
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
		// FreeTypeFontGenerator other = Reflect.get(FreeTypeFontData.class, Fonts.def.getData(), "generator");
		Method                method;
		try {
			method = FreeTypeFontGenerator.class.getDeclaredMethod("createGlyph", char.class, FreeTypeFontData.class, FreeTypeFontParameter.class, Stroker.class, float.class, PixmapPacker.class);
			method.setAccessible(true);
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
		GlyphLayout layout = new GlyphLayout(Fonts.def, "");
		class MyFontData extends FreeTypeFontData {
			public Glyph getGlyph(char ch) {
				Glyph glyph = super.getGlyph(ch);
				if (glyph != missingGlyph) return glyph;
				Glyph glyph1 = Fonts.def.getData().getGlyph(ch);
				if (glyph == null) return null;
				Glyph newGlyph = new Glyph();
				Tools.clone(glyph1, newGlyph, Glyph.class, null);
				newGlyph.page = 1;
				glyph = newGlyph;
				// float baseLine = ((flipped ? -ascent : ascent) + capHeight) / scaleY;
				glyph.yoffset = glyph.yoffset - 4;
				layout.setText(Fonts.def, String.valueOf(ch));
				return glyph;
			}
			{
				markupEnabled = true;
			}
		}
		Font               font    = generator.generateFont(parameter, new MyFontData());
		Seq<TextureRegion> regions = Reflect.get(FreeTypeFontData.class, font.getData(), "regions");
		regions.add(Fonts.def.getRegion());
		return font;
	}
}
