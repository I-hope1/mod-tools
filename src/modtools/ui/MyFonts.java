package modtools.ui;

import arc.Core;
import arc.assets.loaders.*;
import arc.files.Fi;
import arc.freetype.FreeTypeFontGenerator;
import arc.freetype.FreeTypeFontGenerator.*;
import arc.freetype.FreetypeFontLoader.FreeTypeFontLoaderParameter;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.graphics.g2d.Font.Glyph;
import arc.struct.Seq;
import arc.util.*;
import mindustry.Vars;
import mindustry.ui.Fonts;
import modtools.IntVars;
import modtools.utils.Tools;
import modtools.utils.reflect.FieldUtils;

import java.lang.reflect.Field;

import static modtools.utils.MySettings.SETTINGS;

public class MyFonts {
	public static       Font def           = Fonts.def;
	public static final Fi   fontDirectory = IntVars.dataDirectory.child("fonts");
	public static String DEFAULT = "DEFAULT";

	public static void load() {
		fontDirectory.mkdirs();
		Fi readme = fontDirectory.child("README.txt");
		if (!readme.exists()) {
			readme.writeString(
			 """
				You can put your font into the directory. And then you can use them in settings.
				你可以把字体文件放到这里，然后在settings中使用它们。
				""");
		}

		def = acquireFont();
	}

	/* some config */
	// public static boolean italic = true;
	private static Font acquireFont() {
		if (SETTINGS.get("font", DEFAULT).equals(DEFAULT)) return Fonts.def;
		if (def != Fonts.def) return def;

		Fi fontFi = fontDirectory.child(SETTINGS.getString("font"));
		if (!fontFi.exists()) {
			if (Fonts.def == null) throw new RuntimeException("You cannot load it before font load");
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
				Tools.clone(glyph1, newGlyph, Glyph.class, null);
				newGlyph.page = 1; // fonts.def里
				setGlyph(ch, glyph);
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
		Font font = new Font(data, regions, false);

		font.setOwnsTexture(parameter.packer == null);
		// 添加默认字体，如果font没有就去def里找
		font.getRegions().addAll(Fonts.def.getRegions());
		return font;
	}

	/** 常规写法 */
	static void loadFont() {
		Field field = FieldUtils.getFieldAccessOrThrow(AssetLoader.class, "resolver");
		FieldUtils.setValue(field,
		 Core.assets.getLoader(FreeTypeFontGenerator.class),
		 (FileHandleResolver) Vars.tree::get);
		FieldUtils.setValue(field,
		 Core.assets.getLoader(Font.class),
		 (FileHandleResolver) Vars.tree::get);

		String s = SETTINGS.getString("font");
		Vars.tree.addFile("fonts/" + s + ".gen", MyFonts.fontDirectory.child(s));
		FreeTypeFontParameter param = new FreeTypeFontParameter() {{
			borderColor = Color.darkGray;
			incremental = true;
			size = 18;
		}};
		Core.assets.load(s, Font.class, new FreeTypeFontLoaderParameter("fonts/" + s, param))
		 .loaded = font -> Log.info("Loaded @: @", s, font);
	}
	public static void dispose() {
		if (MyFonts.def != Fonts.def) {
			MyFonts.def.getRegions().removeAll(Fonts.def.getRegions());
			MyFonts.def.dispose();
		}
	}
}
