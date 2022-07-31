
package modtools.ui.content;

import arc.scene.ui.layout.Table;
import mindustry.graphics.Pal;
import modtools.ui.components.Window;

import static modtools.IntVars.frag;
import static modtools.IntVars.topGroup;
import static modtools.utils.MySettings.settings;

public class Settings extends Content {
	Window ui;
	Table cont = new Table();
	final Table loadTable = new Table(t -> {
		t.left().defaults().left();
	});

	public void build() {
		ui.show();
	}

	public Settings() {
		super("settings");
	}

	public <T extends Content> void addLoad(T cont) {
		loadTable.check(cont.localizedName(), cont.loadable(), b -> {
			settings.put("load-" + cont.name, b);
		}).row();
	}

	public Table add(String title, Table t) {
		Table table = new Table();
		table.add(title).color(Pal.accent).growX().left().row();
		t.left().defaults().left();
		table.add(t).growX().left().padLeft(16);
		add(table);
		return table;
	}

	public void add(Table t) {
		cont.add(t).growX().padTop(6).row();
	}

	public void load() {
		ui = new Window(localizedName(), 425, 90, true);
		cont = new Table();
		ui.cont.pane(cont).fillX().fillY();
		cont.defaults().width(400);
		add("load", loadTable);
		add("其他", new Table() {{
			left().defaults().left();
			check("显示主菜单背景", settings.getBool("ShowMainMenuBackground"), b -> settings.put("ShowMainMenuBackground", b)).row();
			check("ui过多检查", topGroup.checkUI, b -> topGroup.checkUI = b);
			check("frag置于顶层", frag.keepFrag, b -> settings.put("ShowMainMenuBackground", frag.keepFrag = b));
		}});
		Content.all.forEach(cont -> {
			if (!(cont instanceof Settings)) {
				addLoad(cont);
			}
		});
//		ui.addCloseButton();
	}
}
