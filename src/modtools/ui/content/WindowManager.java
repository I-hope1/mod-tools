package modtools.ui.content;

import arc.scene.ui.ImageButton;
import arc.scene.ui.layout.Table;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.ui.Styles;
import modtools.ui.IntStyles;
import modtools.ui.components.Window;

import static modtools.ui.IntUI.icons;

public class WindowManager extends Content {
	public WindowManager() {
		super("windowManager");
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
				top.button(icons.get("sticky"), IntStyles.clearNoneTogglei, 32, () -> {
					window.sticky = !window.sticky;
				}).checked(b -> window.sticky).padLeft(4f);
				ImageButton button = top.button(Tex.whiteui, IntStyles.clearNonei, 32, () -> {
					window.show();
					window.toggleMaximize();
				}).padLeft(4f).get();
				button.update(() -> {
					button.getStyle().imageUp = window.isMaximize ? icons.get("normal") : icons.get("toggleMaximize");
				});
			}
			top.button(Icon.cancel, IntStyles.clearNonei, 32, window::hide).padLeft(4f).padRight(4f);
			// Tools.clone(window.top, top, Table.class, null);
		});
	}

	@Override
	public void build() {
		rebuild();
		ui.show();
	}
}
