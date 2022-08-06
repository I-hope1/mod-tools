package modtools.ui;

import arc.files.Fi;
import arc.freetype.FreeTypeFontGenerator;
import arc.freetype.FreeTypeFontGenerator.FreeTypeFontData;
import arc.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import arc.graphics.Color;
import arc.graphics.g2d.Font;
import mindustry.Vars;
import mindustry.ui.Fonts;
import modtools.ModTools;

public class MyFonts {
	public static Font MSYHMONO;

	public static void load() {
		Fi fontFi = Vars.mods.getMod(ModTools.class).root.child("MSYHMONO.ttf");
		if (!fontFi.exists()) {
			MSYHMONO = Fonts.def;
			return;
		}
		FreeTypeFontGenerator generator = new FreeTypeFontGenerator(fontFi);
		FreeTypeFontParameter parameter = new FreeTypeFontParameter() {{
			size = 22;
			shadowColor = Color.darkGray;
			shadowOffsetY = 2;
			incremental = true;
		}};
		// final boolean[] generating = {false};
		MSYHMONO = generator.generateFont(parameter, new FreeTypeFontData() {
			{
				markupEnabled = true;
			}
		});
		// MSYHMONO.getData().markupEnabled = false;
		// generating[0] = false;
		// Tools.clone(MSYHMONO, Fonts.def, Font.class, null);
	}
}
