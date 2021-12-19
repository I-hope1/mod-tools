package modmake.ui.Content;

import arc.Core;
import arc.scene.ui.TextButton;
import modmake.IntVars;
import modmake.ui.Contents;

public abstract class Content {
	public final String name;

	public TextButton btn;

	public String localizedName() {
		return Core.bundle.get(name, name);
	}

	public boolean loadable() {
		return (boolean) Core.settings.get(IntVars.modName + "-load-" + name, true);
	}

	public Content(String name) {
		this.name = name;
		Contents.all.add((Content) this);
	}

	public void loadString() {
	}

	public void load() {
	}

	public void build() {

	}
}