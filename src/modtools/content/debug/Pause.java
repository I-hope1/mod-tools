package modtools.content.debug;

import arc.scene.ui.ImageButton;
import mindustry.gen.Icon;
import modtools.content.Content;
import modtools.content.SettingsUI.SettingsBuilder;
import modtools.override.HScene;
import modtools.ui.HopeStyles;
import modtools.ui.comp.Window;
import modtools.utils.Tools;
import modtools.utils.search.FilterTable;
import modtools.utils.ui.CellTools;

import static modtools.override.HScene.pauseMap;

/** 应该用于暂停游戏的Content
 * 包括 UI，Logic，Renderer，Timer等 */
public class Pause extends Content {
	public Pause() {
		super("pause", Icon.pauseSmall);
		defLoadable = false;
		experimental = true;
	}

	public void load() {
		super.load();
		Tools.runLoggedException("HScene", () -> HScene.load(this));
	}

	Window ui;
	public void lazyLoad() {
		ui = new IconWindow(120, 300, true);
		FilterTable<Class<?>> table = new FilterTable<>();
		table.top().defaults().top().left();
		SettingsBuilder.build(table);
		var data = data();
		for (var entry : pauseMap) {
			Class<?> key = entry.key;
			table.add(key.getName());
			table.defaults().size(42);
			ImageButton button = table.button(Icon.playSmall, HopeStyles.hope_flati, () -> { }).get();
			button.clicked(() -> {
				boolean paused = pauseMap.get(key, 0) > 0;
				boolean next   = !paused;
				pauseMap.put(key, next ? Float.POSITIVE_INFINITY : 0);
				// Log.info("Pause: " + key.getName() + " " + pauseMap);
				button.getStyle().imageUp = next ? Icon.pauseSmall : Icon.playSmall;
			});
			button.update(() -> button.getStyle().imageUp = pauseMap.get(key, 0) > 0 ? Icon.pauseSmall : Icon.playSmall);
			table.button("1t", HopeStyles.flatBordert, () -> pauseMap.put(key, 1));
			table.button("10t", HopeStyles.flatBordert, () -> pauseMap.put(key, 10));
			table.button("60t", HopeStyles.flatBordert, () -> pauseMap.put(key, 60));
			table.button("10s", HopeStyles.flatBordert, () -> pauseMap.put(key, 10 * 60));
			table.defaults().size(CellTools.unset);
			table.row();
		}
		SettingsBuilder.clearBuild();
		ui.cont.add(table);
	}
	public void build() {
		ui.show();
	}
}
