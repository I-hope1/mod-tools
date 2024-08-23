package modtools.content.debug;

import arc.struct.ObjectIntMap.Entry;
import mindustry.gen.Icon;
import modtools.content.Content;
import modtools.content.SettingsUI.SettingsBuilder;
import modtools.override.HScene;
import modtools.ui.comp.Window;
import modtools.utils.Tools;
import modtools.utils.search.FilterTable;

import static modtools.override.HScene.pauseMap;

/** 应该用于暂停游戏的Content  */
public class Pause extends Content {
	public Pause() {
		super("pause", Icon.pauseSmall);
	}

	public void load() {
		super.load();
		Tools.runLoggedException("HScene", () -> HScene.load(this));
	}

	Window ui;
	public void lazyLoad() {
		ui = new Window("Pause", 120, 300, true);
		FilterTable<Class<?>> table = new FilterTable<>();
		table.top().defaults().top().left();
		SettingsBuilder.build(table);
		var data = data();
		for (Entry<Class<?>> entry : pauseMap) {
			Class<?> key = entry.key;
			SettingsBuilder.check(key.getName(), b -> {
				pauseMap.put(key, b ? 1 : 0);
				data.put(key.getName(), b);
			}, () -> pauseMap.get(key) == 1);
		}
		SettingsBuilder.clearBuild();
		ui.cont.add(table);
	}
	public void build() {
		ui.show();
	}
}
