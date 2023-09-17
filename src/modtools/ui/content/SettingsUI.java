
package modtools.ui.content;

import arc.Core;
import arc.files.Fi;
import arc.func.*;
import arc.graphics.Color;
import arc.scene.Element;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.Seq;
import arc.util.*;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.mod.Mods;
import mindustry.ui.Styles;
import modtools.events.*;
import modtools.ui.*;
import modtools.ui.components.*;
import modtools.ui.components.Window.DisWindow;
import modtools.ui.components.limit.LimitTable;
import modtools.utils.ElementUtils;

import static modtools.ui.IntUI.*;
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
		add("jsfunc", new LimitTable() {{
			left().defaults().left();
			bool(this, "@settings.jsfunc.auto_refresh", D_JSFUNC, "auto_refresh");
		}});
		add("毛玻璃", new LimitTable() {{
			left().defaults().left();
			bool(this, "启用", D_BLUR, "enable");
			SettingsUI.slideri(this, D_BLUR, "缩放级别", 1, 8, 4, 1, null);
		}});
		/* add("Window", new LimitTable() {{
			left().defaults().left();
			// add("", );
		}}); */
		add("@mod-tools.others", new LimitTable() {{
			left().defaults().left();
			bool(this, "@settings.mainmenubackground", SETTINGS, "ShowMainMenuBackground");
			bool(this, "@settings.checkuicount", SETTINGS, "checkuicount", topGroup.checkUI, b -> topGroup.checkUI = b);
			bool(this, "@settings.debugbounds", SETTINGS, "debugbounds", topGroup.debugBounds, b -> SETTINGS.put("debugbounds", topGroup.debugBounds = b));
			bool(this, "@setting.showhiddenbounds", SETTINGS, "showHiddenBounds", TopGroup.drawHiddenPad, b -> SETTINGS.put("drawHiddenPad", b), () -> topGroup.debugBounds);
			addValueLabel(this, "Bound Element", () -> topGroup.drawPadElem, () -> topGroup.debugBounds);
			bool(this, "@settings.select_invisible", SETTINGS, "select_invisible", topGroup.selectInvisible, b -> SETTINGS.put("select_invisible", topGroup.selectInvisible = b));
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
	public static void addValueLabel(Table table, String text, Prov<Object> prov, Boolp condition) {
		ValueLabel vl = new ValueLabel(prov.get(), Element.class, null, null);
		vl.setAlignment(Align.right);
		Label l = new Label(text);
		table.stack(l, vl)
		 .update(t -> {
			 vl.setVal(prov.get());
			 Color color = condition.get() ? Color.white : Color.gray;
			 vl.setColor(color);
			 l.setColor(color);
		 }).growX().row();
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
		bool(table, text, data, key, def, boolc, null);
	}
	public static void bool(Table table, String text, Data data, String key, boolean def, Boolc boolc, Boolp condition) {
		table.check(text, key == null ? def : data.getBool(key, def), b -> {
			if (key != null) data.put(key, b);
			if (boolc != null) boolc.get(b);
		}).with(t -> {
			if (condition != null) t.setDisabled(() -> !condition.get());
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
	/** @param name 为{@code null}就无背景，无name，为{@code ""}但有背景 */
	public static <T extends Enum<T>> void addSettingsTable(
	 Table p, String name, Func<String, String> keyProvider,
	 Data data, T[] values, boolean fireAll) {
		p.table(name == null ? null : Tex.pane, dis -> {
			dis.left().defaults().left();
			if (name != null && !name.isEmpty()) dis.add(name).color(Pal.accent).row();
			for (T value : values) {
				SettingsUI.bool(dis, "@settings." + keyProvider.get(value.name()),
				 data, value.name(), true, b -> MyEvents.fire(value));
				if (fireAll) MyEvents.fire(value);
			}
		}).grow().left().row();
	}


	/** @see mindustry.ui.dialogs.CustomRulesDialog */
	public static class SettingsBuilder {
		public static Table main;
		public SettingsBuilder(Table main) {
			build(main);
		}
		public static void build(Table main) {SettingsBuilder.main = main;}

		public static <T> void list(String text, Cons<T> cons, Prov<T> prov, Seq<T> list, Func<T, String> stringify) {
			list(text, cons, prov, list, stringify, () -> true);
		}
		public static <T> void list(String text, Cons<T> cons, Prov<T> prov, Seq<T> list, Func<T, String> stringify,
																Boolp condition) {
			main.table(t -> {
				t.left();
				t.add(text).left().padRight(10)
				 .update(a -> a.setColor(condition.get() ? Color.white : Color.gray));
				t.button(b -> {
					 b.label(() -> stringify.get(prov.get())).grow()
						.update(a -> a.setColor(condition.get() ? Color.white : Color.gray));
					 b.clicked(() -> IntUI.showSelectListTable(b, list,
						prov, cons, stringify, 220, 42, true));
				 }, Styles.defaultb, () -> {})
				 .size(220, 42)
				 .update(a -> a.setDisabled(!condition.get()))
				 .padRight(100f);
			}).padTop(0).row();
		}

		public static void number(String text, Floatc cons, Floatp prov) {
			number(text, false, cons, prov, () -> true, 0, Float.MAX_VALUE);
		}

		public static void number(String text, Floatc cons, Floatp prov, float min, float max) {
			number(text, false, cons, prov, () -> true, min, max);
		}

		public static void number(String text, boolean integer, Floatc cons, Floatp prov, Boolp condition) {
			number(text, integer, cons, prov, condition, 0, Float.MAX_VALUE);
		}

		public static void number(String text, Floatc cons, Floatp prov, Boolp condition) {
			number(text, false, cons, prov, condition, 0, Float.MAX_VALUE);
		}

		public static void numberi(String text, Intc cons, Intp prov, int min, int max) {
			numberi(text, cons, prov, () -> true, min, max);
		}

		public static void numberi(String text, Intc cons, Intp prov, Boolp condition, int min, int max) {
			main.table(t -> {
				t.left();
				t.add(text).left().padRight(5)
				 .update(a -> a.setColor(condition.get() ? Color.white : Color.gray));
				t.field((prov.get()) + "", s -> cons.get(Strings.parseInt(s)))
				 .update(a -> a.setDisabled(!condition.get()))
				 .padRight(100f)
				 .valid(f -> Strings.parseInt(f) >= min && Strings.parseInt(f) <= max).width(120f).left();
			}).padTop(0).row();
		}
		public static void numberi(String text, Data data, String key, int defaultValue, Boolp condition, int min,
															 int max) {
			if (defaultValue < min || defaultValue > max) {
				throw new IllegalArgumentException("defaultValue 必须在 " + min + " 和 " + max + " 之间。当前值为: " + defaultValue);
			}
			numberi(text, val -> data.put(key, val), () -> data.getInt(key, defaultValue), condition, min, max);
		}

		public static void number(String text, boolean integer, Floatc cons, Floatp prov, Boolp condition, float min,
															float max) {
			main.table(t -> {
				t.left();
				t.add(text).left().padRight(5)
				 .update(a -> a.setColor(condition.get() ? Color.white : Color.gray));
				t.field((integer ? (int) prov.get() : prov.get()) + "", s -> cons.get(Strings.parseFloat(s)))
				 .padRight(100f)
				 .update(a -> a.setDisabled(!condition.get()))
				 .valid(f -> Strings.canParsePositiveFloat(f) && Strings.parseFloat(f) >= min && Strings.parseFloat(f) <= max).width(120f).left();
			}).padTop(0);
			main.row();
		}
		public static void number(String text, Data data, String key, float defaultValue, Boolp condition, float min,
															float max) {
			if (defaultValue < min || defaultValue > max) {
				throw new IllegalArgumentException("defaultValue 必须在 " + min + " 和 " + max + " 之间。当前值为: " + defaultValue);
			}
			number(text, false, val -> data.put(key, val), () -> data.getFloat(key, defaultValue), condition, min, max);
		}

		public static void check(String text, Boolc cons, Boolp prov) {
			check(text, cons, prov, () -> true);
		}

		public static void check(String text, Data data, String key, Boolp condition) {
			check(text, data, key, false, condition);
		}

		public static void check(String text, Data data, String key, boolean defaultValue, Boolp condition) {
			check(text, val -> data.put(key, val), () -> data.getBool(key, defaultValue), condition);
		}


		public static void check(String text, Boolc cons, Boolp prov, Boolp condition) {
			main.check(text, cons).checked(prov.get()).update(a -> a.setDisabled(!condition.get())).padRight(100f).get().left();
			main.row();
		}

		public static void title(String text) {
			main.add(text).color(Pal.accent).padTop(20).padRight(100f).padBottom(-3);
			main.row();
			main.image().color(Pal.accent).height(3f).padRight(100f).padBottom(20);
			main.row();
		}

		public Cell<TextField> field(Table table, float value, Floatc setter) {
			return table.field(Strings.autoFixed(value, 2), v -> setter.get(Strings.parseFloat(v)))
			 .valid(Strings::canParsePositiveFloat)
			 .size(90f, 40f).pad(2f);
		}
	}
}