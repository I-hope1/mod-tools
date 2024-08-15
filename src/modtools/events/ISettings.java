package modtools.events;

import arc.func.*;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.math.geom.*;
import arc.scene.Element;
import arc.scene.event.Touchable;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.layout.Table;
import arc.scene.utils.Disableable;
import arc.struct.Seq;
import arc.util.*;
import arc.util.serialization.Jval;
import arc.util.serialization.Jval.JsonMap;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.annotations.settings.SettingsInit;
import modtools.content.SettingsUI.SettingsBuilder;
import modtools.ui.*;
import modtools.ui.comp.limit.LimitTextButton;
import modtools.ui.menu.MenuItem;
import modtools.ui.style.DelegatingDrawable;
import modtools.utils.MySettings.Data;
import modtools.utils.Tools;
import modtools.utils.ui.FormatHelper;

import static modtools.content.SettingsUI.SettingsBuilder.*;
import static modtools.content.SettingsUI.colorBlock;
import static modtools.events.ISettings.$$.*;
import static modtools.ui.IntUI.*;
import static modtools.utils.Tools.or;
import static modtools.utils.ui.CellTools.rowSelf;

/**
 * @see SettingsInit
 */
@SuppressWarnings({"unused",
                   // "Convert2Lambda"/* 为了兼容java8和安卓 */,
                   "StringTemplateMigration"})
public interface ISettings extends E_DataInterface {
	String SUFFIX_ENABLED = "$enabled";
	float  DISABLED_ALPHA = 0.7f;

	/** 这会根据实现自动更改 */
	Data data = null;


	/* 获取数据  */
	default Data data() {
		return null;
	}
	/** 默认是bool */
	default Class<?> type() { return boolean.class; }

	/** 是否为开关，用于某一个设置的开启/关闭 */
	default boolean isSwitchOn() {
		return !hasSwitch() || data().getBool(switchKey(), true);
	}
	default void defSwitchOn(boolean b) {
		data().get(switchKey(), b);
	}
	default void setSwitchOn(boolean b) {
		data().put(switchKey(), b);
	}
	/** it will be overrided by compiler. */
	default boolean hasSwitch() {
		return false;
	}
	/** it will be overrided by compiler. */
	default String switchKey() {
		return name() + SUFFIX_ENABLED;
	}

	default Cons<ISettings> builder() { return null; }
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
		if (type() != boolean.class)
			throw new IllegalStateException(STR."the settings is \{type()} not boolean.class");
		data().get(name(), true);
	}
	default void set(Object o) {
		o = Tools.cast(o, type());
		data().put(name(), o);
	}
	default void set(boolean b) {
		if (type() != boolean.class)
			throw new IllegalStateException(STR."the settings is \{type()} not boolean.class");
		set((Boolean) b);
	}


	// getter
	default boolean enabled() {
		if (type() != boolean.class)
			throw new IllegalStateException(STR."the settings is \{type()} not boolean.class");
		return data().getBool(name());
	}
	default void toggle() {
		if (type() != boolean.class)
			throw new IllegalStateException(STR."the settings is \{type()} not boolean.class");
		set(!enabled());
	}
	default Object get() {
		return data().get(name());
	}
	default String getString() {
		Object o = get();
		if (type() == String.class && o instanceof Jval) set(o = ((Jval) o).asString());
		return String.valueOf(o);
	}

	default <T extends Enum<T>> T getEnum(Class<T> cl) {
		return Enum.valueOf(cl, data().getString(name()));
	}
	default int getInt() {
		if (type() != int.class)
			throw new IllegalStateException(STR."the settings is \{type()} not int.class");
		return data().getInt(name(), 0);
	}
	default float getFloat() {
		if (type() != float.class)
			throw new IllegalStateException(STR."the settings is \{type()} not float.class");
		return data().getFloat(name(), 0);
	}
	default int getColor() {
		if (type() != Color.class)
			throw new IllegalStateException(STR."the settings is \{type()} not Color.class");
		return data().get0xInt(name(), -1);
	}
	default Vec2 getPosition() {
		if (type() != Position.class)
			throw new IllegalStateException(STR."the settings is \{type()} not Position.class");
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
		SettingsBuilder.build(table);
		String dependency = switchKey();
		if (dependency.endsWith(SUFFIX_ENABLED)) {
			check(prefix + name() + SUFFIX_ENABLED, this::setSwitchOn, this::isSwitchOn);
		} else {
			/* Arrays.stream(getClass().getEnumConstants())
			 .filter(e -> e.type() == boolean.class && e.name().equals(dependency))
			 .findAny()
			 .ifPresent(e -> e.$(false)); */
		}
		SettingsBuilder.clearBuild();
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
		if (hasSwitch()) {
			buildSwitch(prefix, table);
		}
		SettingsBuilder.build(table);
		text = (prefix + name()).toLowerCase();
		Class<?> type = type();

		try {
			Cons<ISettings> builder = builder();
			if (builder == null) {
				$(false);
			} else {
				builder.get(this);
			}
		} catch (Throwable e) {
			Log.err(e);
		} finally {
			SettingsBuilder.clearBuild();
		}
	}
	default Drawable getDrawable(Drawable def) {
		String   s        = getString();
		int      index    = s.indexOf('#');
		String   key      = index == -1 ? s : s.substring(0, index);
		Drawable drawable = FormatHelper.lookupUI(key);
		return new DelegatingDrawable(or(drawable, def),
		 index == -1 ? Color.white : Color.valueOf(s.substring(index)));
	}

	class $$ {
		static String text;

		@SuppressWarnings("StringTemplateMigration")
		static String autoAddComma(String s) {
			return s.isEmpty() || s.charAt(s.length() - 1) == '.' ? s : s + ".";
		}
	}

	class Condition implements Runnable {
		private final Disableable d;
		private final Boolp       condition;
		/** @param condition the d will be disabled if the return value is false. */
		public Condition(Disableable d, Boolp condition) {
			this.d = d;
			this.condition = condition;
		}
		public void run() {
			d.setDisabled(!condition.get());
			if (d instanceof Element e && e.parent != null) {
				e.parent.color.a = d.isDisabled() ? DISABLED_ALPHA : 1;
			}
		}
	}

	// 方法 SettingsType $(%args%);


	default void $(boolean def) {
		def(def);
		check(text, this::set, this::enabled, this::isSwitchOn);
	}
	default void $(int def, int min, int max) {
		$(def, min, max, 1);
	}
	/**
	 * (def, min, max, step=1)
	 */
	default void $(int def, int min, int max, int step) {
		def(def);
		Slider slider = new Slider(min, max, step, false);
		slider.setValue(getInt());
		Label value = new Label(getString(), Styles.outlineLabel);
		slider.update(new Condition(slider, this::isSwitchOn));
		slider.moved(val0 -> {
			int val = (int) val0;
			ISettings.this.set(val);
			value.setText(String.valueOf(val));
		});
		Table content = new Table();
		content.add(text, Styles.outlineLabel).left().growX().wrap();
		content.add(value).padLeft(10f).right();
		content.margin(3f, 33f, 3f, 33f);
		content.touchable = Touchable.disabled;
		main().stack(slider, content).growX().padTop(4f).row();
	}
	default void $(float def, float min, float max) {
		$(def, min, max, 1);
	}
	/** (def, min, max, step=1) */
	default void $(float def, float min, float max, float step) {
		def(def);
		Slider slider = new Slider(min, max, step, false);
		slider.setValue(getFloat());
		final Label value = new Label(getString(), Styles.outlineLabel);
		slider.update(new Condition(slider, this::isSwitchOn));
		slider.moved(val -> {
			ISettings.this.set(val);
			value.setText(Strings.autoFixed(val, -Mathf.floor(Mathf.log(10, step))));
		});
		Table content = new Table();
		content.add(text, Styles.outlineLabel).left().growX().wrap();
		content.add(value).padLeft(10f).right();
		content.margin(3f, 33f, 3f, 33f);
		content.touchable = Touchable.disabled;
		main().stack(slider, content).growX().padTop(4f).row();
	}
	/** noArgs */
	default void $(Color def) {
		def(def);
		colorBlock(main(), text, data(), name(), getColor(), this::set);
	}
	/** (enumClass) */
	default <T extends Enum<T>> void $(Enum<T> def, Class<T> enumClass) {
		def(def);
		enum_(text, enumClass, this::set, () -> {
			try {
				return Enum.valueOf(enumClass, data().getString(ISettings.this.name()));
			} catch (Throwable e) { return null; }
		}, this::isSwitchOn);
	}
	/** 参数：({@link String}, def, ...arr) */
	default void $(String def, String... arr) {
		def(def);
		list(text, this::set, this::getString,
		 new Seq<>(arr), s -> s.replaceAll("\\n", "\\\\n"));
	}

	// TODO
	default void $(Position def) {
		def(def);
	}

	/** (def, cons) */
	default void $(Drawable def, Cons<Drawable> cons) {
		def(def);
		Drawable[] drawable = {getDrawable(def)};
		main().table(t -> {
			t.add(text).left().padRight(10).growX().labelAlign(Align.left);
			t.label(() -> FormatHelper.getUIKeyOrNull(drawable[0])).fontScale(0.8f).padRight(6f);
			PreviewUtils.buildImagePreviewButton(null, t, () -> drawable[0], d -> {
				ISettings.this.set(FormatHelper.getUIKey(d));

				cons.get(d);
				drawable[0] = d;
			});
		}).growX().row();
	}

	// TODO: ContextMenu
	@SuppressWarnings("StringTemplateMigration")
	default void $(MenuItem[] def, Prov<Seq<MenuItem>> all) {
		def(def);
		lazyDefault(() -> new Data(data(), new JsonMap()));

		TextButton button = new LimitTextButton("Manage", HopeStyles.flatt);
		main().add(text).left();
		main().add(button.right()).size(96, 42).row();
		button.clicked(() -> showSelectTable(button, (p, hide, searchText) -> {
			Seq<MenuItem> lists = all.get();
			for (int i = 0; i < lists.size; i++) {
				MenuItem menu = lists.get(i);
				if (menu == null) continue;

				TextButtonStyle style = new TextButtonStyle(menu.style());
				style.checkedFontColor = Color.gray;
				var cell = rowSelf(p.button(menu.getName(), menu.icon, style,
				 menu.iconSize(), IntVars.EMPTY_RUN
				).minSize(DEFAULT_WIDTH, FUNCTION_BUTTON_SIZE).marginLeft(5f).marginRight(5f));

				TextButton btn = cell.get();
				Image      img = (Image) btn.getChildren().peek();
				int        j   = i;
				String     k_j = "" + i;
				Boolc updateState = enabled -> {
					img.setColor(enabled ? Color.gray : Color.white);
					var o = (Data) get();
					o.put(k_j, Mathf.sign(enabled) * o.getInt(k_j, j + 1));
				};
				btn.clicked(() -> {
					btn.toggle();
					updateState.get(btn.isChecked());
				});
			}
		}, false, Align.center));
	}

}