package modtools.content;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.ImageButton;
import arc.scene.ui.layout.Table;
import mindustry.gen.*;
import modtools.ui.*;
import modtools.ui.gen.HopeIcons;
import modtools.ui.comp.Window;
import modtools.ui.comp.limit.LimitTable;
import modtools.utils.*;

public class WindowManager extends Content {
	public WindowManager() {
		super("windowManager", Icon.adminSmall);
	}

	public Window ui;
	Table cont;

	public void load() {
		ui = new Window(localizedName(), 300, 400, true);
		// 强制置顶
		ui.titleTable.find("sticky").remove();
		ui.sticky = true;

		ui.cont.pane(cont = new LimitTable()).grow();
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
			cont.add(top).growX().with(t -> EventHelper.doubleClick(t, null, () -> {
				window.invalidateHierarchy();
				window.display();
			})).row();
			top.defaults().size(Window.buttonSize);
			top.label(() -> window.title.getText())
			 .grow().left()
			 .padLeft(10f).padRight(10f);
			if (window.full) {
				top.button(HopeIcons.sticky, HopeStyles.hope_clearNoneTogglei, 32, () -> {
					window.sticky = !window.sticky;
				}).checked(b -> window.sticky).padLeft(4f);
				ImageButton button = top.button(Tex.whiteui, HopeStyles.hope_clearNonei, 28, () -> {
					window.show();
					window.toggleMaximize();
				}).padLeft(4f).get();
				button.update(() -> {
					button.getStyle().imageUp = window.isMaximize ? HopeIcons.normal : HopeIcons.maximize;
				});
			}
			top.button(Icon.cancel, Window.cancel_clearNonei, 32, window::hide).padLeft(4f).padRight(4f);
			top.button(Icon.trash, Window.cancel_clearNonei, 32, () -> {
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
