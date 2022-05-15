package modmake.ui.change;

import arc.files.Fi;
import arc.graphics.Texture;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.TextureRegion;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.mod.Mods;
import mindustry.ui.Fonts;
import modmake.ModMake;

public class cFont {
	public static void main() {
		TextureRegion region = Fonts.def.getRegion();
		Mods.LoadedMod mod = Vars.mods.locateMod(ModMake.name);
		Fi fi = mod.root.child("test.png");
		region.set(new Texture(fi));

		int ch = 63744;
		int size = (int) (Fonts.def.getData().lineHeight / Fonts.def.getData().scaleY);

		Font.Glyph glyph = new Font.Glyph();
		glyph.id = ch;
		glyph.srcX = 0;
		glyph.srcY = 0;
		glyph.width = size;
		glyph.height = size;
		glyph.u = 1;
		glyph.v = 1;
		glyph.u2 = 1;
		glyph.v2 = 1;
		glyph.xoffset = 0;
		glyph.yoffset = -size;
		glyph.xadvance = size;
		glyph.kerning = null;
		glyph.fixedWidth = true;
		glyph.page = 0;
		Seq<Font> fonts = Seq.with(Fonts.def, Fonts.outline);
		fonts.each(f -> f.getData().setGlyph(ch, glyph));
	}
}
