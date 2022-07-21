package modtools.ui;

import arc.graphics.Texture;
import arc.graphics.g2d.TextureRegion;
import arc.scene.style.Drawable;
import arc.scene.style.TextureRegionDrawable;
import mindustry.Vars;
import modtools.ModTools;

import java.util.HashMap;

public class MyIcons extends HashMap<String, Drawable> {
	{
		Vars.mods.getMod(ModTools.class).root.child("icons").findAll().each(f -> {
			put(f.nameWithoutExtension(), new TextureRegionDrawable(new TextureRegion(new Texture(f))));
		});
	}
}
