package modtools.ui.content;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.ImageButton;
import arc.scene.ui.layout.Table;
import mindustry.gen.*;
import modtools.ui.*;
import modtools.ui.HopeIcons;
import modtools.ui.components.Window;
import modtools.ui.components.limit.LimitTable;
import modtools.utils.TaskManager;

public class WindowManager extends Content {
	public WindowManager() {
		super("windowManager");
	}

	public Window ui;
	Table cont;

	public void load() {
		ui = new Window(localizedName(), 400, 400, true);
		// 强制置顶
		ui.titleTable.find("sticky").remove();
		ui.sticky = true;

		ui.cont.pane(cont = new LimitTable());
	}
	Runnable run = this::rebuild0;
	public void rebuild() {
		Core.app.post(() -> TaskManager.acquireTask(15, run));
	}

	private void rebuild0() {
		cont.clearChildren();
		cont.add("Dclick to force to display", Color.lightGray).row();
		Window.all.each(window -> {
			if (window == ui) return;
			Table top = new Table(Tex.pane);
			cont.add(top).growX().with(t -> IntUI.doubleClick(t, null, () -> {
				window.invalidateHierarchy();
				window.display();
			})).row();
			top.label(() -> window.title.getText()).grow().padLeft(10f).padRight(10f).get();
			if (window.full) {
				top.button(HopeIcons.sticky, IntStyles.clearNoneTogglei, 32, () -> {
					window.sticky = !window.sticky;
				}).checked(b -> window.sticky).padLeft(4f);
				ImageButton button = top.button(Tex.whiteui, IntStyles.clearNonei, 32, () -> {
					window.show();
					window.toggleMaximize();
				}).padLeft(4f).get();
				button.update(() -> {
					button.getStyle().imageUp = window.isMaximize ? HopeIcons.normal : HopeIcons.maximize;
				});
			}
			top.button(Icon.cancel, IntStyles.clearNonei, 32, window::hide).padLeft(4f).padRight(4f);
			top.button(Icon.trash, IntStyles.clearNonei, 32, () -> {
				Window.all.remove(window);
				window.hide();
			}).padLeft(4f).padRight(4f);
			// Tools.clone(window.top, top, Table.class, null);
		});
	}
	public void build() {
		rebuild0();
		ui.show();
	}
}
