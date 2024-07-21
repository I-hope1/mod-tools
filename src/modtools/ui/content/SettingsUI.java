
package modtools.ui.content;

import arc.Core;
import arc.files.Fi;
import arc.func.*;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.Seq;
import arc.util.*;
import arc.util.Log.LogLevel;
import mindustry.Vars;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.mod.Mods;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.events.*;
import modtools.ui.*;
import modtools.ui.TopGroup.TSettings;
import modtools.ui.comp.Window;
import modtools.ui.comp.Window.DisWindow;
import modtools.ui.comp.limit.LimitTable;
import modtools.ui.comp.utils.ClearValueLabel;
import modtools.ui.gen.HopeIcons;
import modtools.utils.JSFunc.JColor;
import modtools.utils.*;
import modtools.utils.MySettings.Data;

import static modtools.ui.IntUI.topGroup;
import static modtools.utils.MySettings.SETTINGS;
import static modtools.utils.ui.CellTools.rowSelf;

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
			SETTINGS.put("load-" + cont.name, b);
		}).with(b -> b.setStyle(HopeStyles.hope_defaultCheck)).row();
	}

	public Table add(String title, Table t) {
		return add(title, null, t);
	}
	public Table add(String title, Drawable icon, Table t) {
		Table table = new LimitTable();

		// add icon
		if (icon != null) table.image(icon).size(24).padRight(4f);
		else table.add(); /* 占位符 */

		// title
		table.add(title).color(Pal.accent).growX().left().row();
		table.image().color(Pal.accent).growX().colspan(2).left().row();

		// container
		t.left().defaults().left();
		table.add(t).growX().colspan(2).left().padLeft(16);
		cont.add(table).growX().left().row();
		return table;
	}
	public void add(Table t) {
		cont.add(t).growX().padTop(6).row();
	}

	public void load() {
		ui = new Window(localizedName(), 390, 90, true);
		cont = new Table();
		ui.cont.pane(Styles.smallPane, cont).grow().padLeft(6f);
		cont.defaults().minWidth(375).padTop(20);
		add("Load", loadTable);
		add("JSFunc", new LimitTable() {{
			left().defaults().left();
			JColor.settingColor(this);
		}});
		add("Effects", Icon.effectSmall, new LimitTable() {{
			left().defaults().left();
			ISettings.buildAll("blur", this, E_Blur.class);
		}});

		Core.app.post(() -> add("@mod-tools.others", Icon.listSmall,
		 new LimitTable() {{
			 left().defaults().left();
			 SettingsBuilder.main = this;
			 String key = "ShowMainMenuBackground";
			 SettingsBuilder.check("@settings.mainmenubackground", b -> SETTINGS.put(key, b), () -> SETTINGS.getBool(key));

			 ISettings.buildAll("", this, TSettings.class);
			 // find()
			 addElemValueLabel(this, "Bound Element",
				() -> topGroup.drawPadElem,
				() -> topGroup.setDrawPadElem(null),
				topGroup::setDrawPadElem,
				TSettings.debugBounds::enabled);
			 ISettings.buildAll("", this, E_Game.class);
			 ISettings.buildAll("", this, E_Extending.class);
			 ISettings.buildAll("frag", this, Frag.Settings.class);

			 button("Clear Mods Restart", HopeStyles.flatBordert, SettingsUI::disabledRestart).growX().height(42).row();
			 button("Font", HopeStyles.flatBordert, () -> {
				 new DisWindow("Fonts", 220, 200) {{
					 for (Fi fi : MyFonts.fontDirectory.findAll(fi -> fi.extEquals("ttf"))) {
						 cont.button(fi.nameWithoutExtension(), Styles.flatToggleMenut, () -> {
								SETTINGS.put("font", fi.name());
							}).height(42).growX()
							.checked(_ -> fi.name().equals(SETTINGS.getString("font")))
							.row();
					 }
					 cont.image().color(Color.gray).growX().padTop(6f).row();
					 cont.button("Open Directory", HopeStyles.flatBordert, () -> {
						 Core.app.openFolder(MyFonts.fontDirectory.path());
					 }).growX().height(45);
					 show();
				 }};
			 }).growX().height(42);

			 row();
			 table(Tex.pane, t -> {
				 t.defaults().growX().height(42);
				 t.add("@mod-tools.functions").row();
				 if (/* OS.isAndroid ||  */IntVars.isDesktop())
					 t.button("Switch Language", Icon.chatSmall, HopeStyles.flatt, () -> {
						 IntVars.async(LanguageSwitcher::switchLanguage, () -> IntUI.showInfoFade("Language changed!"));
					 }).row();
				 t.button("Enable Debug Level", Icon.chatSmall, HopeStyles.flatt, () -> {
					 Log.level = LogLevel.debug;
				 });
			 }).growX();
			 row();
			 table(Tex.pane, t -> {
				 t.add("@editor.author");
				 t.add(IntVars.meta.author).row();
				 t.defaults().growX().height(42);
				 t.button("Github", Icon.githubSmall, HopeStyles.flatt, () -> {
					 Core.app.openURI("https://github.com/" + IntVars.meta.repo);
				 });
				 t.button("QQ", HopeIcons.QQ, HopeStyles.flatt, () -> {
					 Core.app.openURI(IntVars.QQ);
				 }).row();
				 // t.button("CheckUpdate", Icon.up, HopeStyles.flatt, Updater::checkUpdate).row();
				 /* t.button("@mod-tools.check", Icon.androidSmall, HopeStyles.flatt, () -> {
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
	public static void addElemValueLabel(
	 Table table, String text, Prov<Element> prov,
	 Runnable clear, Cons<Element> setter,
	 Boolp condition) {
		var vl = new ClearValueLabel<>(Element.class, prov, clear);
		vl.setter = setter;
		vl.setAlignment(Align.right);
		Label l = new Label(text);
		table.stack(l, vl)
		 .update(_ -> {
			 vl.setVal(prov.get());
			 Color color = condition.get() ? Color.white : Color.gray;
			 l.setColor(color);
		 }).growX().row();
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
		EventHelper.doubleClick(table.add(text).growY()
		 .padRight(4f).left().labelAlign(Align.left).get(), null, () -> {
			color.set(Tmp.c2.set(defaultColor));
		});
		ColorBlock.of(table.add().right().growX(), color, false);
		table.row();
		return color;
	}

	public static Cell<CheckBox> checkboxWithEnum(Table t, String text, E_DataInterface enum_) {
		return t.check(text, 28, enum_.enabled(), enum_::set)
		 .with(cb -> cb.setStyle(HopeStyles.hope_defaultCheck));
	}

	public static void tryAddTip(Element element, String tipKey) {
		// Log.info(tipKey);
		if (!Core.bundle.has("settings.tip." + tipKey)) return;

		IntUI.addTooltipListener(element, Core.bundle.get("settings.tip." + tipKey));
	}
	/** @see mindustry.ui.dialogs.CustomRulesDialog */
	public static class SettingsBuilder {
		public static Table main;
		public SettingsBuilder(Table main) {
			build(main);
		}
		public static void build(Table main) { SettingsBuilder.main = main; }

		public static <T> Cell<Table> list(String text, Cons<T> cons, Prov<T> prov, Seq<T> list,
		                                   Func<T, String> stringify) {
			return list(text, cons, prov, list, stringify, () -> true);
		}
		public static Cell<Table> list(String prefix, String key, Data data, Seq<String> list,
		                               Func<String, String> stringify) {
			return list(STR."@\{prefix}.\{key.toLowerCase()}", v -> data.put(key, v),
			 () -> data.getString(key, list.get(0)), list,
			 stringify, () -> true);
		}
		public static <T> Cell<Table> list(String text, Cons<T> cons, Prov<T> prov, Seq<T> list,
		                                   Func<T, String> stringify,
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
				 b.clicked(() -> {
					 if (condition.get()) IntUI.showSelectListTable(b, list,
						prov, cons, stringify, 100, 42,
						true,
						Align.left);
				 });
				 if (condition != null) b.setDisabled(() -> !condition.get());
			 }, HopeStyles.hope_defaultb, () -> { })
			 .height(42).self(c -> c.update(b ->
				c.width(Mathf.clamp(b.getPrefWidth() / Scl.scl(), 64, 220))
			 ));
			return rowSelf(main.add(t).growX().padTop(0));
		}

		public static void number(String text, Floatc cons, Floatp prov) {
			number(text, false, cons, prov, () -> true, 0, Float.MAX_VALUE);
		}

		public static void number(String text, Floatc cons, Floatp prov, float min, float max) {
			number(text, false, cons, prov, () -> true, min, max);
		}

		public static void number(String text, boolean integer, Floatc cons, Floatp prov,
		                          Boolp condition) {
			number(text, integer, cons, prov, condition, 0, Float.MAX_VALUE);
		}

		public static void number(String text, Floatc cons, Floatp prov, Boolp condition) {
			number(text, false, cons, prov, condition, 0, Float.MAX_VALUE);
		}

		public static void numberi(String text, Intc cons, Intp prov, int min, int max) {
			numberi(text, cons, prov, () -> true, min, max);
		}

		public static void numberi(String text, Intc cons, Intp prov, Boolp condition, int min,
		                           int max) {
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
		public static void numberi(String text, Data data, String key, int defaultValue,
		                           Boolp condition, int min,
		                           int max) {
			if (defaultValue < min || defaultValue > max) {
				throw new IllegalArgumentException("defaultValue(" + defaultValue + ") must be in (" + min + ", " + max + ")");
			}
			numberi(text, val -> data.put(key, val), () -> data.getInt(key, defaultValue), condition, min, max);
		}

		public static void number(String text, boolean integer, Floatc cons, Floatp prov,
		                          Boolp condition, float min,
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
		public static void number(String text, Data data, String key, float defaultValue,
		                          Boolp condition, float min,
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

		public static void check(String text, Data data, String key, boolean defaultValue,
		                         Boolp condition) {
			check(text, val -> data.put(key, val), () -> data.getBool(key, defaultValue), condition);
		}


		public static void check(String text, Boolc cons, Boolp prov, Boolp condition) {
			CheckBox checkBox = main.check(text, cons).checked(prov.get()).update(a -> a.setDisabled(!condition.get()))
			 .padLeft(10f).padRight(100f).get();
			checkBox.setStyle(HopeStyles.hope_defaultCheck);
			checkBox.left();
			tryAddTip(checkBox, text.substring(text.indexOf('.') + 1));
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
