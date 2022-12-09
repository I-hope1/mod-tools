package modtools.ui;

import arc.graphics.*;
import arc.graphics.g2d.TextureRegion;
import arc.scene.style.Drawable;
import arc.scene.style.TextureRegionDrawable;
import arc.struct.ObjectMap;
import mindustry.Vars;
import modtools.ModTools;

import java.util.HashMap;

public class MyIcons extends HashMap<String, Drawable> {
	public ObjectMap<String, Pixmap> pixmaps = new ObjectMap<>();

	{
		Vars.mods.getMod(ModTools.class).root.child("icons").findAll().each(f -> {
			Pixmap pixmap = new Pixmap(f);
			pixmaps.put(f.nameWithoutExtension(), pixmap);
			put(f.nameWithoutExtension(), new TextureRegionDrawable(
					new TextureRegion(new Texture(pixmap))));
		});
	}
}
