
package modtools.ui.content;

import arc.Core;
import arc.files.Fi;
import arc.func.*;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.event.Touchable;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.Seq;
import arc.util.*;
import arc.util.Log.LogLevel;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.mod.Mods;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.annotations.builder.DataBoolSetting;
import modtools.events.*;
import modtools.ui.*;
import modtools.ui.HopeIcons;
import modtools.ui.components.Window;
import modtools.ui.components.Window.DisWindow;
import modtools.ui.components.limit.LimitTable;
import modtools.ui.components.utils.ClearValueLabel;
import modtools.utils.*;
import modtools.utils.JSFunc.JColor;
import modtools.utils.MySettings.Data;

import static modtools.ui.IntUI.topGroup;

public class SettingsUI extends Content {
	Window ui;
	Table  cont = new Table();
	final Table loadTable = new Table(t -> t.left().defaults().left());

	public void build() {
		ui.show();
	}

	public SettingsUI() {
		super("settings");
	}

	public <T extends Content> void addLoad(T cont) {
		loadTable.check(cont.localizedName(), 28, cont.loadable(), b -> {
			MySettings.SETTINGS.put("load-" + cont.name, b);
		}).with(b -> b.setStyle(HopeStyles.hope_defaultCheck)).row();
	}

	public Table add(String title, Table t) {
		return add(title, null, t);
	}
	public Table add(String title, Drawable icon, Table t) {
		Table table = new LimitTable();
		if (icon != null) table.image(icon).size(24).padRight(4f);
		else table.add(); /* 占位符 */
		table.add(title).color(Pal.accent).growX().left().row();
		table.image().color(Pal.accent).growX().colspan(2).left().row();
		t.left().defaults().left();
		table.add(t).growX().colspan(2).left().padLeft(16);
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
		add("JSFunc", new LimitTable() {{
			left().defaults().left();
			bool(this, "@settings.jsfunc.auto_refresh", MySettings.D_JSFUNC, "auto_refresh");
			new SettingsBuilder(this) {{
				list("settings.jsfunc", "arrayDelimiter", MySettings.D_JSFUNC,
				 Seq.with(JSFunc.defaultDelimiter, "\n", "\n\n", "\n▶▶▶▶", "\n★★★"),
				 s -> s.replaceAll("\\n", "\\\\n")).colspan(2);
			}};
			row();
			JColor.settingColor(this);
		}});
		add("Effects", Icon.effectSmall, new LimitTable() {{
			left().defaults().left();
			bool(this, "@enabled", MySettings.D_BLUR, "enable");
			SettingsUI.slideri(this, MySettings.D_BLUR, "缩放级别", 1, 8, 4, 1, null);
		}});
		/* add("Window", new LimitTable() {{
			left().defaults().left();
			// add("", );
		}}); */
		Core.app.post(() -> add("@mod-tools.others", Icon.listSmall,
		 new LimitTable() {{
			 left().defaults().left();
			 bool(this, "@settings.mainmenubackground", MySettings.SETTINGS, "ShowMainMenuBackground");
			 settingBool(this);
			 addElemValueLabel(this, "Bound Element",
				() -> topGroup.drawPadElem,
				() -> topGroup.setDrawPadElem(null),
				topGroup::setDrawPadElem,
				() -> topGroup.debugBounds);
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
			 button("clear mods restart", Styles.flatBordert, SettingsUI::disabledRestart).growX().height(42).row();

			 button("FONT", Styles.flatBordert, () -> {
				 new DisWindow("FONTS") {{
					 for (Fi fi : MyFonts.fontDirectory.findAll(fi -> fi.extEquals("ttf"))) {
						 cont.button(fi.nameWithoutExtension(), Styles.flatToggleMenut, () -> {
								MySettings.SETTINGS.put("font", fi.name());
							}).height(42).growX()
							.checked(__ -> fi.name().equals(MySettings.SETTINGS.getString("font")))
							.row();
					 }
					 cont.image().color(Color.gray).growX().padTop(6f).row();
					 cont.button("DIRECTORY", Styles.flatBordert, () -> {
						 Core.app.openFolder(MyFonts.fontDirectory.path());
					 }).growX().height(45);
					 show();
				 }};
			 }).growX().height(42);

			 row();
			 table(Tex.pane, t -> {
				 t.add("@mod-tools.functions").row();
				 if (OS.isAndroid || OS.isWindows) t.button("Switch Language", Icon.chatSmall, Styles.flatt, () -> {
					 IntVars.async(LanguageSwitcher::switchLanguage, () -> IntUI.showInfoFade("Language changed!"));
				 }).height(42);
				 t.button("Enable Debug Parma", Icon.chatSmall, Styles.flatt, () -> {
					 Log.level = LogLevel.debug;
				 }).height(42);
			 }).growX();
			 row();
			 table(Tex.pane, t -> {
				 t.add("@editor.author");
				 t.add(IntVars.meta.author).row();
				 t.button("Github", Icon.githubSmall, Styles.flatt, () -> {
					 Core.app.openURI("https://github.com/" + IntVars.meta.repo);
				 }).height(42).growX();
				 t.button("QQ", HopeIcons.QQ, Styles.flatt, () -> {
					 Core.app.openURI(IntVars.QQ);
				 }).height(42).growX().row();
				 /* t.button("@mod-tools.check", Icon.androidSmall, Styles.flatt, () -> {
					 Updater.checkUpdate(b -> {});
				 }).height(42).growX().colspan(2); */
			 }).growX();
		 }}));
		Content.all.forEach(cont -> {
			if (!(cont instanceof SettingsUI)) {
				addLoad(cont);
			}
		});
		// ui.addCloseButton();
	}

	public static void disabledRestart() {
		Reflect.set(Mods.class, Vars.mods, "requiresReload", false);
	}
	@DataBoolSetting
	public void settingBool(Table t) {
		boolean[] __ = {topGroup.checkUICount, topGroup.debugBounds, TopGroup.drawHiddenPad};
	}
	public static void addElemValueLabel(
	 Table table, String text, Prov<Element> prov,
	 Runnable clear, Cons<Element> setter,
	 Boolp condition) {
		var vl = new ClearValueLabel<>(Element.class, prov, clear);
		vl.setter = setter;
		vl.setAlignment(Align.right);
		Label l = new Label(text);
		table.stack(l, vl)
		 .update(t -> {
			 vl.setVal(prov.get());
			 Color color = condition.get() ? Color.white : Color.gray;
			 l.setColor(color);
		 }).growX().row();
	}

	public static Slider slider(Table table, String name, float min, float max, float def, float step, Floatc floatc) {
		return slider(table, MySettings.SETTINGS, name, min, max, def, step, floatc);
	}
	public static Slider slider(Table table, Data data, String name, float min, float max, float def, float step,
															Floatc floatc) {
		Slider slider = new Slider(min, max, step, false);
		slider.setValue(data.getFloat(name, def));
		Label value = new Label(slider.getValue() + "", Styles.outlineLabel);
		slider.moved(val -> {
			data.put(name, val);
			value.setText(Strings.fixed(val, -Mathf.floor(Mathf.log(10, step))));
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
		return slideri(table, MySettings.SETTINGS, name, min, max, def, step, intc);
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
	public static void bool(Table table, String text, String key, boolean def, Boolc boolc) {
		bool(table, text, MySettings.SETTINGS, key, def, boolc, null);
	}
	public static void bool(Table table, String text, Data data, String key, boolean def, Boolc boolc) {
		bool(table, text, data, key, def, boolc, null);
	}
	public static void bool(Table table, String text, String key, boolean def, Boolc boolc, Boolp condition) {
		bool(table, text, MySettings.SETTINGS, key, def, boolc, condition);
	}

	public static void bool(Table table, String text, Data data, String key, boolean def, Boolc boolc, Boolp condition) {
		table.check(text, 28, key == null ? def : data.getBool(key, def), b -> {
			if (key != null) data.put(key, b);
			if (boolc != null) boolc.get(b);
		}).with(t -> {
			t.setStyle(HopeStyles.hope_defaultCheck);
			if (condition != null) t.setDisabled(() -> !condition.get());
		}).row();
	}

	public static Color colorBlock(
	 Table table, String text,
	 Data data, String key, int defaultColor,
	 Cons<Color> colorCons) {
		Color color = new Color(data.get0xInt(key, defaultColor)) {
			public Color set(Color color) {
				if (this.equals(color)) return this;
				data.putString(key, color);
				super.set(color);
				if (colorCons != null) colorCons.get(this);
				return this;
			}
		};
		IntUI.doubleClick(table.add(text).growY()
		 .padRight(4f).left().labelAlign(Align.left).get(), null, () -> {
			color.set(Tmp.c2.set(defaultColor));
		});
		IntUI.colorBlock(table.add().right().growX(), color, false);
		table.row();
		return color;
	}

	public static Cell<CheckBox> checkboxWithEnum(Table t, String text, E_DataInterface enum_) {
		return t.check(text, 28, enum_.enabled(), enum_::set)
		 .with(cb -> cb.setStyle(HopeStyles.hope_defaultCheck));
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
		public static Cell<Table> list(String prefix, String key, Data data, Seq<String> list,
																	 Func<String, String> stringify) {
			return list("@" + prefix + "." + key.toLowerCase(), v -> data.put(key, v),
			 () -> data.getString(key, list.get(0)), list,
			 stringify, () -> true);
		}
		public static <T> Cell<Table> list(String text, Cons<T> cons, Prov<T> prov, Seq<T> list, Func<T, String> stringify,
																			 Boolp condition) {
			Table t = new Table();
			t.right();
			t.add(text).left().padRight(10).growX().labelAlign(Align.left)
			 .update(a -> a.setColor(condition.get() ? Color.white : Color.gray));
			t.button(b -> {
				 b.margin(0, 8f, 0, 8f);
				 b.add("").grow().labelAlign(Align.right)
					.update(l -> {
						l.setText(stringify.get(prov.get()));
						l.setColor(condition.get() ? Color.white : Color.gray);
					});
				 b.clicked(() -> IntUI.showSelectListTable(b, list,
					prov, cons, stringify, 100, 42,
					true,
					Align.left));
				 b.update(condition == null ? null : () -> b.setDisabled(!condition.get()));
			 }, HopeStyles.hope_defaultb, () -> {})
			 .height(42).self(c -> c.update(b ->
				c.width(Mathf.clamp(b.getPrefWidth() / Scl.scl(), 64, 220))
			 ));
			Cell<Table> cell = main.add(t).growX().padTop(0);
			cell.row();
			return cell;
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
				t.field((integer ? (int) prov.get() : prov.get()) + "", s -> cons.get(NumberHelper.asFloat(s)))
				 .padRight(100f)
				 .update(a -> a.setDisabled(!condition.get()))
				 .valid(f -> NumberHelper.isFloat(f) && NumberHelper.asFloat(f) >= min && NumberHelper.asFloat(f) <= max).width(120f).left();
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
			return table.field(Strings.autoFixed(value, 2), v -> setter.get(NumberHelper.asFloat(v)))
			 .valid(Strings::canParsePositiveFloat)
			 .size(90f, 40f).pad(2f);
		}
	}
}
