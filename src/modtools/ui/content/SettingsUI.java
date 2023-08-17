
package modtools.ui.content;

import arc.Core;
import arc.files.Fi;
import arc.func.*;
import arc.graphics.Color;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.Reflect;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.mod.Mods;
import mindustry.ui.Styles;
import modtools.events.*;
import modtools.ui.MyFonts;
import modtools.ui.components.Window;
import modtools.ui.components.Window.DisWindow;

import static modtools.ui.IntUI.topGroup;
import static modtools.utils.MySettings.*;

public class SettingsUI extends Content {
	Window ui;
	Table  cont = new Table();
	final Table loadTable = new Table(t -> {
		t.left().defaults().left();
	});

	public void build() {
		ui.show();
	}

	public SettingsUI() {
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
		table.image().color(Pal.accent).growX().left().row();
		t.left().defaults().left();
		table.add(t).growX().left().padLeft(16);
		cont.add(table).row();
		return table;
	}
	public void add(Table t) {
		cont.add(t).growX().padTop(6).row();
	}

	public void load() {
		ui = new Window(localizedName(), 425, 90, true);
		cont = new Table();
		ui.cont.pane(cont).grow();
		cont.defaults().minWidth(400).padTop(20);
		add("Load", loadTable);
		add("jsfunc", new Table() {{
			left().defaults().left();
			bool(this, "@settings.jsfunc.auto_refresh", D_JSFUNC, "auto_refresh");
		}});
		add("毛玻璃", new Table() {{
			left().defaults().left();
			bool(this, "启用", D_BLUR, "enable");
			SettingsUI.slideri(this, D_BLUR, "缩放级别", 1, 8, 4, 1, null);
		}});
		add("@mod-tools.others", new Table() {{
			left().defaults().left();
			bool(this, "@settings.mainmenubackground", SETTINGS, "ShowMainMenuBackground");
			bool(this, "@settings.checkuicount", SETTINGS, "checkuicount", topGroup.checkUI, b -> topGroup.checkUI = b);
			bool(this, "@settings.debugbounds", SETTINGS, "debugbounds", topGroup.debugBounds, b -> SETTINGS.put("debugbounds", topGroup.debugBounds = b));
			bool(this, "@settings.select_unvisible", SETTINGS, "select_unvisible", topGroup.selectUnvisible, b -> SETTINGS.put("select_unvisible", topGroup.selectUnvisible = b));
			float minZoom = Vars.renderer.minZoom;
			float maxZoom = Vars.renderer.maxZoom;
			SettingsUI.slider(this, "rendererMinZoom", Math.min(0.1f, minZoom), minZoom, minZoom, 0.1f, val -> {
				Vars.renderer.minZoom = val;
			}).change();
			SettingsUI.slider(this, "rendererMaxZoom", maxZoom, Math.max(14f, maxZoom), maxZoom, 0.1f, val -> {
				Vars.renderer.maxZoom = val;
			}).change();
			if (Version.number >= 136) {
				SettingsUI.slideri(this, "maxSchematicSize", Vars.maxSchematicSize, 500, Vars.maxSchematicSize, 1, val -> {
					Vars.maxSchematicSize = val;
				});
			}
			button("clear mods restart", Styles.flatBordert, () -> {
				Reflect.set(Mods.class, Vars.mods, "requiresReload", false);
			}).growX().height(42).row();

			button("FONT", Styles.flatBordert, () -> {
				new DisWindow("FONTS") {{
					for (Fi fi : MyFonts.fontDirectory.findAll(fi -> fi.extEquals("ttf"))) {
						cont.button(fi.nameWithoutExtension(), Styles.flatToggleMenut, () -> {
							 SETTINGS.put("font", fi.name());
						 }).height(42).growX()
						 .checked(__ -> fi.name().equals(SETTINGS.getString("font")))
						 .row();
					}
					cont.image().color(Color.gray).growX().row();
					cont.button("DIRECTORY", Styles.flatBordert, () -> {
						Core.app.openFolder(MyFonts.fontDirectory.path());
					}).growX().height(45);
					show();
				}};
			}).growX().height(42);
		}});
		Content.all.forEach(cont -> {
			if (!(cont instanceof SettingsUI)) {
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
	public static void bool(Table table, String text, Data data, boolean def, Boolc boolc) {
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


	public static Cell<CheckBox> checkboxWithEnum(Table t, String text, E_DataInterface _enum) {
		return t.check(text, _enum.enabled(), _enum::set);
	}

	public static <T extends Enum<T>> void addSettingsTable(
	 Table p, String name, Func<String, String> keyProvider,
	 Data data, T[] values) {
		addSettingsTable(p, name, keyProvider, data, values, false);
	}
	public static <T extends Enum<T>> void addSettingsTable(
	 Table p, String name, Func<String, String> keyProvider,
	 Data data, T[] values, boolean fireAll) {
		p.table(Tex.pane, dis -> {
			dis.left().defaults().left();
			if (name != null) dis.add(name).color(Pal.accent).row();
			for (T value : values) {
				SettingsUI.bool(dis, "@settings." + keyProvider.get(value.name()),
				 data, value.name(), true, b -> MyEvents.fire(value));
				if (fireAll) MyEvents.fire(value);
			}
		}).grow().left().row();
	}
}
