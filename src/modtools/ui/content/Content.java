package modtools.ui.content;

import arc.Core;
import arc.scene.ui.TextButton;
import modtools.IntVars;
import modtools.ui.Contents;

public abstract class Content {
	public final String name;

	public TextButton btn;

	public String localizedName() {
		return Core.bundle.get(name, name);
	}

	public String getSettingName() {
		return IntVars.modName + "-" + name;
	}

	public boolean loadable() {
		return (boolean) Core.settings.get(IntVars.modName + "-load-" + name, true);
	}

	public Content(String name) {
		this.name = name;
		Contents.all.add(this);
	}

	public void loadSettings() {
	}

	public void load() {
	}

	public void build() {

	}
}