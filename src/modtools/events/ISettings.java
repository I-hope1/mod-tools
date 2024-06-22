package modtools.events;

import arc.func.*;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.event.Touchable;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.Jval.JsonMap;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.annotations.settings.SettingsInit;
import modtools.jsfunc.type.CAST;
import modtools.ui.*;
import modtools.ui.components.limit.LimitTextButton;
import modtools.ui.menu.MenuItem;
import modtools.ui.style.DelegetingDrawable;
import modtools.utils.MySettings.Data;
import modtools.utils.*;

import java.lang.reflect.Method;

import static modtools.events.ISettings.$$.*;
import static modtools.ui.IntUI.*;
import static modtools.ui.content.SettingsUI.SettingsBuilder.*;
import static modtools.ui.content.SettingsUI.colorBlock;
import static modtools.utils.Tools.*;

/**
 * @see SettingsInit
 */
@SuppressWarnings({"unused", "Convert2Lambda"/* 为了兼容java8 */, "StringTemplateMigration"})
public interface ISettings extends E_DataInterface {
	/** 这会根据实现自动更改 */
	Data   data           = null;
	String SUFFIX_ENABLED = "$enabled";
	default Data data() {
		return null;
	}

	default Class<?> type() { return boolean.class; }

	/* class Switch {
		boolean enabled() { }
		void def(boolean b) { }
		void set(boolean b) { }
	}
	default Switch _switch() {

	} */
	/** 是否为开关，用于某一个设置的开启/关闭 */
	default boolean isSwitchOn() {
		return data().getBool(name() + SUFFIX_ENABLED, true);
	}
	default void defSwitchOn(boolean b) {
		data().get(name() + SUFFIX_ENABLED, b);
	}
	default void setSwitchOn(boolean b) {
		data().put(name() + SUFFIX_ENABLED, b);
	}

	default Object args() { return null; }
	default String name() {
		return null;
	}


	default void lazyDefault(Prov<Object> o) {
		data().get(name(), o);
	}
	default void def(Object o) {
		data().get(name(), o);
	}
	default void defTrue() {
		data().get(name(), true);
	}
	default void set(Object o) {
		data().put(name(), o);
	}
	default void set(boolean b) { set((Boolean) b); }
	/* default <T> T get() {
		return (T) data().get(name());
	} */
	default boolean enabled() {
		return data().getBool(name());
	}
	default void toggle() { set(!enabled()); }
	default Object get() {
		return data().get(name());
	}
	default String getString() { return data().getString(name()); }

	default <T extends Enum<T>> T getEnum(Class<T> cl) {
		return Enum.valueOf(cl, data().getString(name()));
	}
	default int getInt() {
		return data().getInt(name(), 0);
	}
	default float getFloat() {
		return data().getFloat(name(), 0);
	}
	default int getColor() {
		return data().get0xInt(name(), -1);
	}
	default Vec2 getPosition() {
		String s = getString();
		int    i = s.indexOf(',');
		if (i == -1) return Tmp.v3.set(0, 0);
		return Tmp.v3.set(Float.parseFloat(s.substring(1, i)),
		 Float.parseFloat(s.substring(i + 1, s.length() - 1)));
	}


	static void buildAllWrap(String prefix, Table p, String title, Class<? extends ISettings> cl) {
		p.row().table(Tex.pane, table -> {
			table.left().defaults().left();
			table.add(title).color(Pal.accent).row();
			ISettings.buildAll(prefix, table, cl);
		});
	}

	/**
	 * 使用的入口
	 * @param prefix 用于显示设置文本
	 */
	static void buildAll(String prefix, Table table, Class<? extends ISettings> cl) {
		buildAll0("@settings." + autoAddComma(prefix), table, cl);
	}
	/* Internal  */
	private static void buildAll0(String prefix, Table table, Class<? extends ISettings> cl) {
		for (ISettings value : cl.getEnumConstants()) {
			value.build(prefix, table);
		}
	}

	default void buildSwitch(String prefix, Table table) {
		main = table;
		check(STR."@settings.\{autoAddComma(prefix)}\{name()}\{SUFFIX_ENABLED}", this::setSwitchOn, this::isSwitchOn);
	}
	default void build(Table table) {
		build("", table);
	}
	/**
	 * <pre>
	 * int: (min, max, step)
	 * boolean: ()
	 * Color: ()
	 * Enum: ()
	 * String[]: (...String)
	 * </pre>
	 */
	default void build(String prefix, Table table) {
		main = table;
		text = (prefix + name()).toLowerCase();
		Class<?> type = type();

		Method build = $builds.get(CAST.box(type));
		try {
			build.invoke(this, (Object) null);
		} catch (Throwable e) {
			Log.err(STR."Failed to build \{getClass()}.\{this}", e);
		}
	}
	default Drawable getDrawable(Drawable def) {
		String s     = getString();
		int    index = s.indexOf('#');
		String key   = index == -1 ? s : s.substring(0, index);
		Drawable drawable = StringHelper.lookupUI(key);
		return new DelegetingDrawable(or(drawable, def),
			index == -1 ? Color.white : Color.valueOf(s.substring(index + 1)));
	}


	class $$ {
		static String  text;
		static boolean isSwitch;

		@SuppressWarnings("StringTemplateMigration")
		static String autoAddComma(String s) {
			return s.isEmpty() ? s : s + ".";
		}
		static {
			$builds.each((_, m) -> m.setAccessible(true));
		}
	}

	// ----通过反射执行对应的方法----
	ObjectMap<Class<?>, Method> $builds = new Seq<>(ISettings.class.getDeclaredMethods())
	 .removeAll(b -> !b.getName().equals("$"))
	 .asMap(m -> m.getParameterTypes()[0]);


	// 方法
	private void $(Boolean __) {
		check(text, this::set, this::enabled);
	}
	/** 默认step为1 */
	private void $(Integer __) {
		var   args = (int[]) args();
		float def  = args[0];
		def(def);
		float  min    = args[1];
		float  max    = args[2];
		float  step   = args.length == 3 ? 1f : args[3];
		Slider slider = new Slider(min, max, step, false);
		slider.setValue(getInt());
		Label value = new Label(getString(), Styles.outlineLabel);
		slider.moved(new Floatc() {
			public void get(float val0) {
				int val = (int) val0;
				ISettings.this.set(val);
				value.setText(String.valueOf(val));
			}
		});
		Table content = new Table();
		content.add(text, Styles.outlineLabel).left().growX().wrap();
		content.add(value).padLeft(10f).right();
		content.margin(3f, 33f, 3f, 33f);
		content.touchable = Touchable.disabled;
		main.stack(slider, content).growX().padTop(4f).row();
	}
	/** 默认step为0.1 */
	private void $(Float __) {
		var   args = (float[]) args();
		float def  = args[0];
		def(def);
		float  min    = args[1];
		float  max    = args[2];
		float  step   = args.length == 3 ? 0.1f : args[3];
		Slider slider = new Slider(min, max, step, false);
		slider.setValue(getFloat());
		final Label value = new Label(getString(), Styles.outlineLabel);
		slider.moved(new Floatc() {
			public void get(float val) {
				ISettings.this.set(val);
				value.setText(Strings.autoFixed(val, -Mathf.floor(Mathf.log(10, step))));
			}
		});
		Table content = new Table();
		content.add(text, Styles.outlineLabel).left().growX().wrap();
		content.add(value).padLeft(10f).right();
		content.margin(3f, 33f, 3f, 33f);
		content.touchable = Touchable.disabled;
		main.stack(slider, content).growX().padTop(4f).row();
	}
	private void $(Color __) {
		colorBlock(main, text, data(), name(), getColor(), this::set);
	}
	private void $(Enum<?> __) {
		var enumClass = (Class) args();
		var enums     = new Seq<>((Enum<?>[]) enumClass.getEnumConstants());
		list(text, this::set, new Prov<>() {
			public Enum<?> get() {
				return Enum.valueOf(enumClass, data().getString(ISettings.this.name()));
			}
		}, enums, Enum::name);
	}
	private void $(String[] __) {
		var list = new Seq<>((String[]) args());
		list(text, this::set, this::getString,
		 list, s -> s.replaceAll("\\n", "\\\\n"));
	}

	private void $(Drawable __) {
		Object[]       args     = (Object[]) args();
		Drawable[]     drawable = {getDrawable((Drawable) args[0])};
		Cons<Drawable> cons     = as(args[1]);
		main.table(t -> {
			t.add(text).left().padRight(10).growX().labelAlign(Align.left);
			t.label(() -> StringHelper.getUIKey(drawable[0])).fontScale(0.8f).padRight(6f);
			IntUI.imagePreviewButton(null, t, () -> drawable[0], d -> {
				set(StringHelper.getUIKey(d));

				cons.get(d);
				drawable[0] = d;
			});
		}).growX().row();
	}

	// ContextMenu
	@SuppressWarnings("StringTemplateMigration")
	private void $(MenuItem[] __) {
		var all = (Prov<Seq<MenuItem>>) args();
		lazyDefault(() -> new Data(data(), new JsonMap()));

		TextButton button = new LimitTextButton("Manage", HopeStyles.flatt);
		main.add(text).left();
		main.add(button.right()).size(96, 42).row();
		button.clicked(() -> showSelectTable(button, (p, hide, searchText) -> {
			Seq<MenuItem> lists = all.get();
			for (int i = 0; i < lists.size; i++) {
				MenuItem menu = lists.get(i);
				if (menu == null) continue;

				TextButtonStyle style = new TextButtonStyle(menu.style());
				style.checkedFontColor = Color.gray;
				var cell = p.button(menu.getName(), menu.icon, style,
				 menu.iconSize(), () -> { }
				).minSize(DEFAULT_WIDTH, FUNCTION_BUTTON_SIZE).marginLeft(5f).marginRight(5f);

				TextButton btn = cell.get();
				Image      img = (Image) btn.getChildren().peek();
				int        j   = i;
				String     k_j = "" + i;
				Boolc updateState = enabled -> {
					img.setColor(enabled ? Color.gray : Color.white);
					var o = (Data) get();
					o.put(k_j, (enabled ? 1 : -1) * o.getInt(k_j, j + 1));
				};
				btn.clicked(() -> {
					btn.toggle();
					updateState.get(btn.isChecked());
				});
				cell.row();
			}
		}, false, Align.center));
	}
}