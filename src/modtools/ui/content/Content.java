
package modtools.ui.content;

import arc.Core;
import arc.scene.ui.TextButton;
import modtools.utils.MySettings.Data;

import java.util.ArrayList;

import static modtools.IntVars.modName;
import static modtools.utils.MySettings.SETTINGS;

public abstract class Content {
	public static final ArrayList<Content> all = new ArrayList<>();

	public final String     name;
	public       TextButton btn;

	/** 显示名称 */
	public String localizedName() {
		return Core.bundle.get(modName + "." + name, name);
	}
	public String getSettingName() {
		return name;
	}

	protected boolean defLoadable = true;
	public final boolean loadable() {
		return SETTINGS.getBool("load-" + name, defLoadable);
	}
	public Content(String name) {
		this.name = name;
		all.add(this);
	}

	public final void loadSettings() {
		loadSettings(data());
	}
	public void loadSettings(Data SETTINGS) {
	}

	private Data data;
	/** 设置 */
	public final Data data() {
		return data == null ? data = SETTINGS.child(getSettingName()) : data;
	}
	public void load() {
	}

	/** 点击按钮触发的事件 */
	public void build() {
	}
}
