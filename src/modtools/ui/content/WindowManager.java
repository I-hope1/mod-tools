package modtools.ui.content;

import modtools.ui.components.Window;

import static modtools.utils.MySettings.settings;

public class WindowManager extends Content {
	public WindowManager() {
		super("窗口管理");
	}

	Window ui;

	@Override
	public void load() {
		ui = new Window(localizedName(), 400, 400, true);
		ui.cont.check("最小化窗口隐藏", settings.getBool("minimize_window_hiding", "true"), b -> {
			settings.put("minimize_window_hiding", b);
		});
	}
}
