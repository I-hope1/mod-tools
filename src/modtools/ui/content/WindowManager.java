package modtools.ui.content;

import arc.scene.ui.ImageButton;
import arc.scene.ui.layout.Table;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.ui.Styles;
import modtools.ui.components.Window;

import static modtools.ui.IntUI.icons;

public class WindowManager extends Content {
	public WindowManager() {
		super("窗口管理");
	}

	public Window ui;
	Table cont;

	@Override
	public void load() {
		ui = new Window(localizedName(), 400, 400, true);
		// 强制置顶
		ui.top.find("sticky").remove();
		ui.sticky = true;
		ui.cont.pane(cont = new Table());
	}

	public void rebuild() {
		cont.clearChildren();
		Window.all.each(window -> {
			if (window == ui) return;
			Table top = new Table(Tex.pane);
			cont.add(top).growX().row();
			top.label(() -> window.title.getText()).grow().padLeft(10f).padRight(10f).get();
			if (window.full) {
				top.button(icons.get("sticky"), Styles.clearNoneTogglei, 32, () -> {
					window.sticky = !window.sticky;
				}).checked(b -> window.sticky).padLeft(4f);
				ImageButton button = top.button(Tex.whiteui, Styles.clearNonei, 32, () -> {
					window.show();
					window.maximize();
				}).padLeft(4f).get();
				button.update(() -> {
					button.getStyle().imageUp = window.isMaximize ? icons.get("normal") : icons.get("maximize");
				});
			}
			top.button(Icon.cancel, Styles.clearNonei, 32, window::hide).padLeft(4f).padRight(4f);
			// Tools.clone(window.top, top, Table.class, null);
		});
	}

	@Override
	public void build() {
		rebuild();
		ui.show();
	}
}
