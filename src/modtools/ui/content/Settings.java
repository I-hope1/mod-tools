
package modtools.ui.content;

import arc.Core;
import arc.func.*;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
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
		add("Load", loadTable);
		add("@mod-tools.others", new Table() {{
			left().defaults().left();
			check("@settings.mainmenubackground", settings.getBool("ShowMainMenuBackground"), b -> settings.put("ShowMainMenuBackground", b)).row();
			check("@settings.checkuicount", topGroup.checkUI, b -> topGroup.checkUI = b).row();
			check("@settings.fragtofront", frag.keepFrag, b -> settings.put("ShowMainMenuBackground", frag.keepFrag = b)).row();
			Settings.slider(this, "rendererMinZoom", 0.1f, Vars.renderer.minZoom, Vars.renderer.minZoom, 0.1f, val -> {
				Vars.renderer.minZoom = val;
			}).change();
			Settings.slider(this, "maxSchematicSize", Vars.maxSchematicSize, 500, Vars.maxSchematicSize, 1f, val -> {
				Vars.maxSchematicSize = (int) val;
			}).change();
		}});
		Content.all.forEach(cont -> {
			if (!(cont instanceof Settings)) {
				addLoad(cont);
			}
		});
		// ui.addCloseButton();
	}

	public static Slider slider(Table table, String name, float min, float max, float def, float step, Floatc floatc) {
		Slider slider = new Slider(min, max, step, false);
		slider.setValue(settings.getFloat(name, def));
		Label value = new Label("", Styles.outlineLabel);
		slider.moved(val -> {
			settings.put(name, val);
			value.setText(String.valueOf(val));
			floatc.get(val);
		});
		Table content = new Table();
		content.add(Core.bundle.get("settings." + name, name), Styles.outlineLabel).left().growX().wrap();
		content.add(value).padLeft(10f).right();
		content.margin(3f, 33f, 3f, 33f);
		content.touchable = Touchable.disabled;
		table.stack(slider, content).growX().padTop(4f).row();
		return slider;
	}
}
