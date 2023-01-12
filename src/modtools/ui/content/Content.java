
package modtools.ui.content;

import arc.Core;
import arc.scene.ui.TextButton;

import java.util.ArrayList;

import static modtools.IntVars.modName;
import static modtools.utils.MySettings.settings;

public abstract class Content {
	public static final ArrayList<Content> all = new ArrayList<>();
	public final String name;
	public TextButton btn;

	public String localizedName() {
		return Core.bundle.get(modName + "." + name, name);
	}

	public String getSettingName() {
		return name;
	}

	public boolean loadable() {
		return settings.getBool("load-" + name, true);
	}

	public Content(String name) {
		this.name = name;
		all.add(this);
	}

	public void loadSettings() {
	}

	public void load() {
	}

	public void build() {
	}
}
