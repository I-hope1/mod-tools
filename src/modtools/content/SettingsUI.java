
package modtools.content;

import arc.Core;
import arc.files.Fi;
import arc.func.*;
import arc.graphics.Color;
import arc.math.*;
import arc.scene.Element;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
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
import modtools.utils.*;
import modtools.utils.JSFunc.JColor;
import modtools.utils.MySettings.Data;
import modtools.utils.io.FileUtils;
import modtools.utils.ui.FormatHelper;

import java.lang.reflect.Field;

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
		table.image().color(Pal.accent).growX().fill(0.95f, 0f).colspan(2).left().row();

		// container
		t.left().defaults().left();
		table.table(container -> container.background(((NinePatchDrawable) Tex.sideline)
			 .tint(Tmp.c1.set(Pal.accent).lerp(Color.lightGray, 0.8f)))
			.add(t).grow())
		 .growX().colspan(2).left();
		cont.add(table).growX().left().row();
		return table;
	}
	public void add(Table t) {
		cont.add(t).growX().padTop(6).row();
	}

	public void lazyLoad() {
		ui = new IconWindow(390, 90, true);
		Table prev = cont;
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
		cont.add(prev).grow().row();

		add("@mod-tools.others", Icon.listSmall,
		 new LimitTable() {{
			 left().defaults().left();
			 SettingsBuilder.main = this;
			 String key = "ShowMainMenuBackground";
			 SettingsBuilder.check("@settings.mainmenubackground", b -> SETTINGS.put(key, b), () -> SETTINGS.getBool(key));

			 ISettings.buildAll("", this, TSettings.class);
			 // find()
			 addElemValueLabel(this, "Bound Element",
				TopGroup::getDrawPadElem,
				() -> TopGroup.setDrawPadElem(null),
				TopGroup::setDrawPadElem,
				TSettings.debugBounds::enabled);
			 ISettings.buildAll("", this, E_Game.class);
			 ISettings.buildAll("", this, E_Extending.class);
			 ISettings.buildAll("frag", this, Frag.Settings.class);

			 button("Font", HopeStyles.flatBordert, () -> {
				 new DisWindow("Fonts", 220, 400) {{
					 cont.top().defaults().top();
					 cont.button(MyFonts.DEFAULT, HopeStyles.flatToggleMenut, () -> SETTINGS.put("font", "DEFAULT")).height(42).growX()
						.checked(_ -> MyFonts.DEFAULT.equals(SETTINGS.getString("font"))).row();
					 cont.image().color(Color.gray).growX().padTop(6f).row();
					 for (Fi fi : MyFonts.fontDirectory.findAll(fi -> fi.extEquals("ttf"))) {
						 cont.button(fi.nameWithoutExtension(), Styles.flatToggleMenut, () -> {
								SETTINGS.put("font", fi.name());
							}).height(42).growX()
							.checked(_ -> fi.name().equals(SETTINGS.getString("font")))
							.row();
					 }
					 cont.add().expandY().row();
					 cont.image().color(Color.gray).growX().padTop(6f).row();
					 cont.button("Open Directory", HopeStyles.flatBordert, () -> {
						 FileUtils.openFile(MyFonts.fontDirectory);
					 }).growX().height(45);
					 show();
				 }};
			 }).growX().height(42);

			 row();
			 table(Tex.pane, t -> {
				 t.defaults().growX().height(42);
				 t.add("@mod-tools.functions").row();
				 t.button("Clear Mods Restart", Icon.boxSmall, HopeStyles.flatt, SettingsUI::disabledRestart).row();
				 if (/* OS.isAndroid ||  */IntVars.isDesktop())
					 t.button("Switch Language", Icon.chatSmall, HopeStyles.flatt, () -> {
						 IntVars.async(LanguageSwitcher::switchLanguage, () -> IntUI.showInfoFade("Language changed!"));
					 }).row();
				 t.button("Enable Debug Level", Icon.chatSmall, HopeStyles.flatt, () -> {
					 Log.level = LogLevel.debug;
				 });
			 }).growX();
			 row();
			 // About
			 button("About", Icon.infoCircleSmall, HopeStyles.flatBordert, () -> {
				 new DisWindow("About", 220, 100) {{
					 Table cont = this.cont;
					 cont.left().defaults().left().growX().height(42);
					 cont.add("@editor.author");
					 cont.add(IntVars.meta.author).row();
					 cont.add("@editor.version");
					 cont.add(IntVars.meta.version).row();
					 cont.button("Github", Icon.githubSmall, HopeStyles.flatt, () -> {
						 Core.app.openURI("https://github.com/" + IntVars.meta.repo);
					 });
					 cont.button("QQ", HopeIcons.QQ, HopeStyles.flatt, () -> {
						 Core.app.openURI(IntVars.QQ);
					 }).row();
					 // discord
					 // cont.button("Discord", Icon.discord, HopeStyles.flatt, () -> {
					 //  Core.app.openURI(IntVars.discord);
					 // });
				 }};
			 }).height(42).growX();
		 }});
		all.forEach(cont -> {
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
		var vl = new ClearValueLabel<>(Element.class, prov, clear, setter);
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
		Color color = new Color(
		 data == null ? defaultColor : data.get0xInt(key, defaultColor)) {
			public Color set(Color color) {
				if (this.equals(color)) return this;
				if (data != null) data.putString(key, color);
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

	public static String TIP_PREFIX = "settings.tip.";
	/**
	 * TIP_PREFIX: {@value TIP_PREFIX}
	 * @see IntUI#tips(String)
	 * @see IntUI#tips(String, String)
	 */
	public static void tryAddTip(Element element, String tipKey) {
		if (!Core.bundle.has(TIP_PREFIX + tipKey)) return;

		IntUI.addTooltipListener(element, () -> FormatHelper.parseVars(Core.bundle.get(TIP_PREFIX + tipKey)));
	}
	/** @see mindustry.ui.dialogs.CustomRulesDialog */
	public static class SettingsBuilder {
		private static Table main;
		public static Table main() {
			return main;
		}
		public SettingsBuilder() { }
		public static void build(Table main) {
			SettingsBuilder.main = main;
			main.left().defaults().left();
		}
		public static void clearBuild() {
			main = null;
		}

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
			 }, HopeStyles.hope_defaultb, IntVars.EMPTY_RUN)
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
				 .valid(f -> {
					 int i = Strings.parseInt(f);
					 return i >= min && i <= max;
				 }).width(120f).left();
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

		@SuppressWarnings("StringTemplateMigration")
		public static void number(String text, boolean integer, Floatc cons, Floatp prov,
		                          Boolp condition, float min,
		                          float max) {
			main.table(t -> {
				t.left();
				t.add(text).left().padRight(5)
				 .update(a -> a.setColor(condition.get() ? Color.white : Color.gray));
				String val;
				if (integer) {
					val = ((int) prov.get()) + "";
				} else {
					val = FormatHelper.fixed(prov.get(), 2);
				}
				t.field(val, s -> cons.get(NumberHelper.asFloat(s)))
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
			check(text, cons, prov, null);
		}

		public static void check(String text, Data data, String key, Boolp condition) {
			check(text, data, key, false, condition);
		}

		public static void check(String text, Data data, String key, boolean defaultValue,
		                         Boolp condition) {
			check(text, val -> data.put(key, val), () -> data.getBool(key, defaultValue), condition);
		}


		public static void check(String text, Boolc cons, Boolp prov, Boolp condition) {
			CheckBox checkBox = main.check(text, cons)
			 .update(a -> {
				 a.setChecked(prov.get());
				 if (condition != null) a.setDisabled(!condition.get());
			 })
			 .padLeft(10f).get();
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
		public static <T extends Enum<T>> void enum_(
		 String text, Class<T> enumClass, Cons<Enum<T>> cons, Prov<Enum<T>> prov,
		 Boolp condition) {
			var enums = new Seq<>((Enum<T>[]) enumClass.getEnumConstants());
			list(text, cons, prov, enums, Enum::name, condition);
		}

		public Cell<TextField> field(Table table, float value, Floatc setter) {
			return table.field(Strings.autoFixed(value, 2), v -> setter.get(NumberHelper.asFloat(v)))
			 .valid(Strings::canParsePositiveFloat)
			 .size(90f, 40f).pad(2f);
		}

		public static void color(String text, Color defaultColor, Cons<Color> colorSet) {
			colorBlock(main, text, null, null, defaultColor.rgba(), colorSet);
		}

		public static void interpolator(String name, Cons<Interp> cons, Prov<Interp> prov) {
			Seq<Field>               seq = Seq.with(Interp.class.getFields());
			ObjectMap<Interp, Field> map = seq.asMap(Reflect::get, f -> f);
			list(name, f -> cons.get(Reflect.get(f)), () -> map.get(prov.get()),
			 seq, Field::getName);
		}
	}
}
