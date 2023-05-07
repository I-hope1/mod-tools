
package modtools.ui.content;

import arc.Core;
import arc.func.*;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.ui.components.Window;
import modtools.utils.MySettings.Data;

import static modtools.ui.IntUI.topGroup;
import static modtools.utils.MySettings.*;

public class SettingsContent extends Content {
	Window ui;
	Table  cont = new Table();
	final Table loadTable = new Table(t -> {
		t.left().defaults().left();
	});

	public void build() {
		ui.show();
	}

	public SettingsContent() {
		super("settings");
	}

	public <T extends Content> void addLoad(T cont) {
		loadTable.check(cont.localizedName(), cont.loadable(), b -> {
			SETTINGS.put("load-" + cont.name, b);
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
		add("jsfunc", new Table() {{
			left().defaults().left();
			bool(this, "@settings.jsfunc.number.edit", D_JSFUNC_EDIT, "number");
			bool(this, "@settings.jsfunc.string.edit", D_JSFUNC_EDIT, "string");
			bool(this, "@settings.jsfunc.boolean.edit", D_JSFUNC_EDIT, "boolean");
			bool(this, "@settings.jsfunc.auto_refresh", D_JSFUNC, "auto_refresh");
		}});
		add("毛玻璃", new Table() {{
			left().defaults().left();
			bool(this, "启用", D_BLUR, "enable");
			SettingsContent.slideri(this, D_BLUR, "缩放级别", 1, 8, 4, 1, null);
		}});
		add("@mod-tools.others", new Table() {{
			left().defaults().left();
			bool(this, "@settings.mainmenubackground", SETTINGS, "ShowMainMenuBackground");
			bool(this, "@settings.checkuicount", SETTINGS, "checkuicount", topGroup.checkUI, b -> topGroup.checkUI = b);
			bool(this, "@settings.debugbounds", SETTINGS, "debugbounds", topGroup.debugBounds, b -> SETTINGS.put("debugbounds", topGroup.debugBounds = b));
			SettingsContent.slider(this, "rendererMinZoom", 0.1f, Vars.renderer.minZoom, Vars.renderer.minZoom, 0.1f, val -> {
				Vars.renderer.minZoom = val;
			}).change();
			if (Version.number >= 136) {
				SettingsContent.slideri(this, "maxSchematicSize", Vars.maxSchematicSize, 500, Vars.maxSchematicSize, 1, val -> {
					Vars.maxSchematicSize = val;
				});
			}
		}});
		Content.all.forEach(cont -> {
			if (!(cont instanceof SettingsContent)) {
				addLoad(cont);
			}
		});
		// ui.addCloseButton();
	}

	public static Slider slider(Table table, String name, float min, float max, float def, float step, Floatc floatc) {
		return slider(table, SETTINGS, name, min, max, def, step, floatc);
	}
	public static Slider slider(Table table, Data data, String name, float min, float max, float def, float step,
	                            Floatc floatc) {
		Slider slider = new Slider(min, max, step, false);
		slider.setValue(data.getFloat(name, def));
		Label value = new Label(slider.getValue() + "", Styles.outlineLabel);
		slider.moved(val -> {
			data.put(name, val);
			value.setText(String.valueOf(val));
			if (floatc != null) floatc.get(val);
		});
		Table content = new Table();
		content.add(Core.bundle.get("settings." + name, name), Styles.outlineLabel).left().growX().wrap();
		content.add(value).padLeft(10f).right();
		content.margin(3f, 33f, 3f, 33f);
		content.touchable = Touchable.disabled;
		table.stack(slider, content).growX().padTop(4f).row();
		return slider;
	}
	public static Slider slideri(Table table, String name, int min, int max, int def, int step, Intc intc) {
		return slideri(table, SETTINGS, name, min, max, def, step, intc);
	}
	public static Slider slideri(Table table, Data data, String name, int min, int max, int def, int step, Intc intc) {
		Slider slider = new Slider(min, max, step, false);
		int    tmp    = data.getInt(name, def);
		slider.setValue(tmp);
		Label value = new Label(tmp + "", Styles.outlineLabel);
		slider.moved(val0 -> {
			int val = (int) val0;
			data.put(name, val);
			value.setText(String.valueOf(val));
			if (intc != null) intc.get(val);
		});
		Table content = new Table();
		content.add(Core.bundle.get("settings." + name, name), Styles.outlineLabel).left().growX().wrap();
		content.add(value).padLeft(10f).right();
		content.margin(3f, 33f, 3f, 33f);
		content.touchable = Touchable.disabled;
		table.stack(slider, content).growX().padTop(4f).row();
		return slider;
	}
	public void bool(Table table, String text, Data data, boolean def, Boolc boolc) {
		bool(table, text, data, null, def, boolc);
	}
	public static void bool(Table table, String text, Data data, String key, boolean def) {
		bool(table, text, data, key, def, null);
	}
	public static void bool(Table table, String text, Data data, String key) {
		bool(table, text, data, key, false, null);
	}
	public static void bool(Table table, String text, Data data, String key, boolean def, Boolc boolc) {
		table.check(text, key == null ? def : data.getBool(key, def), b -> {
			if (key != null) data.put(key, b);
			if (boolc != null) boolc.get(b);
		}).row();
	}
}
