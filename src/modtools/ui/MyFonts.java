package modtools.ui;

import arc.files.Fi;
import arc.freetype.FreeTypeFontGenerator;
import arc.freetype.FreeTypeFontGenerator.*;
import arc.graphics.g2d.*;
import arc.graphics.g2d.Font.Glyph;
import arc.scene.ui.layout.Scl;
import arc.struct.Seq;
import arc.util.Reflect;
import mindustry.ui.Fonts;
import modtools.IntVars;
import modtools.utils.Tools;

import static modtools.utils.MySettings.SETTINGS;

public class MyFonts {
	public static       Font def;
	public static final Fi   fontDirectory = IntVars.dataDirectory.child("fonts");

	static {
		fontDirectory.mkdirs();
		Tools.runLoggedException(MyFonts::load, () -> def = Fonts.def);
	}

	public static void load() {
		def = acquireFont();
	}

	// public static boolean italic = true;
	public static boolean
	 underline     = false,
	 strikethrough = false;
	private static Font acquireFont() {
		if (def != null) return def;
		if (!SETTINGS.containsKey("font")) return Fonts.def;

		Fi fontFi = fontDirectory.child(SETTINGS.getString("font"));
		if (!fontFi.exists()) {
			if (Fonts.def == null) throw new RuntimeException("you can't load it before font load");
			return Fonts.def;
		}
		FreeTypeFontGenerator generator = new FreeTypeFontGenerator(fontFi);
		FreeTypeFontParameter parameter = new FreeTypeFontParameter() {{
			size = 20;
			genMipMaps = true;
			// 可以添加Fonts.def的glyph
			incremental = true;
		}};
		// FreeTypeFontGenerator other = Reflect.get(FreeTypeFontData.class, Fonts.def.getData(), "generator");
		class MyFontData extends FreeTypeFontData {
			final GlyphLayout layout = new GlyphLayout(Fonts.def, "");
			public Glyph getGlyph(char ch) {
				Glyph glyph = super.getGlyph(ch);
				if (glyph != missingGlyph) return glyph;
				Glyph glyph1 = Fonts.def.getData().getGlyph(ch);
				if (glyph == null) return null;
				Glyph newGlyph = new Glyph();
				Tools.clone(glyph1, newGlyph, Glyph.class, (Seq<String>) null);
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
		FreeTypeFontData data = new MyFontData();
		generator.generateData(parameter, data);

		Seq<TextureRegion> regions = Reflect.get(FreeTypeFontData.class, data, "regions");
		Font font = new Font(data, regions, false) {
			public FontCache newFontCache() {
				return new MyFontCache(this);
			}
		};
		font.setOwnsTexture(parameter.packer == null);
		// 添加默认字体，如果font没有就去def里找
		font.getRegions().addAll(Fonts.def.getRegion());
		return font;
	}

	private static class MyFontCache extends FontCache {
		public MyFontCache(Font font) {super(font, font.usesIntegerPositions());}
		// boolean underline_ = underline;
		// boolean strikethrough_ = strikethrough;
		public void draw() {
			super.draw();

			// Log.info(HopeReflect.getCaller() + ": " + underline);
			if (!(underline || strikethrough)) return;
			GlyphLayout layout = getLayouts().firstOpt();
			// Log.info(layout);
			if (layout == null) return;

			Draw.color();
			float[] vertices = getVertices();
			Fill.crect(getX() + vertices[0] - 1, getY() + vertices[1] - 4 + (strikethrough ? layout.height / 2f : 0),
			 Math.max(4, getLayouts().sumf(l -> l.width) + 1), 2);
		}
	}
}
