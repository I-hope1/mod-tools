package modtools.ui;

import arc.Events;
import arc.files.Fi;
import arc.freetype.FreeTypeFontGenerator;
import arc.freetype.FreeTypeFontGenerator.FreeTypeFontData;
import arc.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.graphics.g2d.Font.Glyph;
import arc.scene.style.TextureRegionDrawable;
import arc.util.*;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.ui.Fonts;
import modtools.ModTools;

import static modtools.ui.IntUI.icons;

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
