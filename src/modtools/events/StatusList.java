package modtools.events;

import arc.func.Prov;
import arc.graphics.Color;
import arc.scene.style.*;
import mindustry.gen.Icon;
import modtools.events.ExecuteTree.StatusInterface;

public enum StatusList implements StatusInterface {
	/* 因为Icon(可能)还为赋值 */
	noTask(() -> Icon.none, Color.lightGray),
	paused(() -> Icon.bookOpen, Color.orange),
	running(() -> Icon.rotate, Color.pink),
	ok(() -> Icon.ok, Color.green),
	error(() -> Icon.cancel, Color.red);
	final Prov<TextureRegionDrawable> drawable;
	final Color                       color;
	StatusList(Prov<TextureRegionDrawable> drawable, Color color) {
		this.drawable = drawable;
		this.color = color;
	}
	private Drawable cache;
	public Drawable icon() {
		if (cache == null) cache = drawable.get().tint(color());
		return cache;
	}
	public Color color() {
		return color;
	}

	public int code() {
		return ordinal();
	}
}
