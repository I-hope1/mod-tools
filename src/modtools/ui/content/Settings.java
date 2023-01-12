
package modtools.ui.content;

import arc.Core;
import arc.func.*;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.layout.Table;
import arc.util.serialization.Jval.JsonMap;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.ui.components.Window;
import modtools.utils.MySettings.Data;

import java.util.Date;

import static modtools.IntVars.*;
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

	public Data jsfuncEdit;

	public void load() {
		ui = new Window(localizedName(), 425, 90, true);
		cont = new Table();
		ui.cont.pane(cont).fillX().fillY();
		cont.defaults().width(400);
		add("Load", loadTable);
		add("jsfunc", new Table() {{
			left().defaults().left();
			jsfuncEdit = (Data) settings.get("JsfuncEdit", () -> new Data(settings, new JsonMap()));
			check("@settings.jsfunc.number.edit", jsfuncEdit.getBool("number", false), b -> jsfuncEdit.put("number", b)).row();
			check("@settings.jsfunc.string.edit", jsfuncEdit.getBool("string", false), b -> jsfuncEdit.put("string", b)).row();
			check("@settings.jsfunc.boolean.edit", jsfuncEdit.getBool("boolean", false), b -> jsfuncEdit.put("boolean", b)).row();
		}});
		add("@mod-tools.others", new Table() {{
			left().defaults().left();
			check("@settings.mainmenubackground", settings.getBool("ShowMainMenuBackground"), b -> settings.put("ShowMainMenuBackground", b)).row();
			check("@settings.checkuicount", topGroup.checkUI, b -> topGroup.checkUI = b).row();
			check("@settings.fragtofront", frag.keepFrag, b -> settings.put("ShowMainMenuBackground", frag.keepFrag = b)).row();
			check("@settings.debugbounds", topGroup.debugBounds, b -> settings.put("debugbounds", topGroup.debugBounds = b)).row();
			Settings.slider(this, "rendererMinZoom", 0.1f, Vars.renderer.minZoom, Vars.renderer.minZoom, 0.1f, val -> {
				Vars.renderer.minZoom = val;
			}).change();
			if (Version.number >= 136) {
				Settings.slider(this, "maxSchematicSize", Vars.maxSchematicSize, 500, Vars.maxSchematicSize, 1f, val -> {
					Vars.maxSchematicSize = (int) val;
				});
			}
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
