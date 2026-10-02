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
import arc.util.serialization.Jval.*;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.annotations.settings.SettingsInit;
import modtools.content.SettingsUI.SettingsBuilder;
import modtools.ui.*;
import modtools.ui.comp.Underline;
import modtools.ui.comp.limit.LimitTextButton;
import modtools.ui.menu.MenuItem;
import modtools.ui.style.DelegatingDrawable;
import modtools.utils.*;
import modtools.utils.MySettings.Data;
import modtools.utils.ui.FormatHelper;

import java.util.*;

import static modtools.content.SettingsUI.SettingsBuilder.*;
import static modtools.content.SettingsUI.colorBlock;
import static modtools.events.ISettings.ZX.*;
import static modtools.ui.IntUI.*;
import static modtools.utils.Tools.or;
import static modtools.utils.ui.CellTools.rowSelf;

/**
 * ModTools 统一设置项系统的核心契约接口。
 * <p>通常由各模块内部的 {@code Settings} 枚举实现，配合 {@link SettingsInit} 注解驱动自动初始化。
 *
 * <h3>1. 国际化与 Bundle 键名规则 (I18N & Bundle Naming Conventions)</h3>
 * <ul>
 *   <li><b>标题键名（Title Key）：</b>在调用 {@link #buildAll(String, Table, Class)} 时，系统会自动拼接
 *       {@code "@settings.<prefix>.<constant_name_lowercase>"}。<br>
 *       例如：{@code buildAll("profiler", t, Settings.class)} 下的 {@code sample_freq}，
 *       对应属性文件中的键为 {@code settings.profiler.sample_freq}。若 prefix 为空，则为 {@code settings.<name>}。</li>
 *   <li><b>提示键名（Tooltip Key）：</b>在 {@link modtools.content.SettingsUI#TIP_PREFIX} 下遵循
 *       {@code "settings.tip.<prefix>.<constant_name_lowercase>"}。<br>
 *       若属性文件中定义了该键，UI 构建器会自动为该配置条目绑定浮动 Tooltip 提示。</li>
 *   <li><b>UI 分割线占位符：</b>若枚举常量名以 {@code '_'} 开头（如 {@code _1}、{@code _2}），不会生成配置项，
 *       而是被 {@link modtools.ui.comp.Underline} 渲染为水平视觉分割线。</li>
 * </ul>
 *
 * <h3>2. 数据持久化与类型支持 (Data & Type System)</h3>
 * <ul>
 *   <li>底层通过 {@link Data}（基于 JSON/Hjson）进行全局或局部的键值持久化读写。</li>
 *   <li>通过重载的 {@code $(...)} 系列方法提供流畅的 DSL，声明默认值与取值范围：
 *       包括 {@code boolean}、{@code int}（滑块/数值框）、{@code float}、{@code String}（下拉/输入）、
 *       {@code String[]}（动态数组列表）、{@code Enum}、{@code Color}、{@code Drawable} 等。</li>
 * </ul>
 *
 * <h3>3. 复合开关与依赖联动 (Switch & Dependencies)</h3>
 * <ul>
 *   <li>若配置项附带总开关（如 {@code @Switch}），开关键名默认为 {@code <name>$enabled}。</li>
 *   <li>当总开关关闭时，该项在 UI 上会自动被禁用并呈现半透明状态（{@value #DISABLED_ALPHA}），
 *       逻辑上由 {@link #isSwitchOn()} 控制是否生效。</li>
 * </ul>
 *
 * <h3>4. 事件监听与响应 (Reactivity)</h3>
 * <ul>
 *   <li>支持 {@link #onChange(Runnable)} 与 {@link #runAndOnChange(Runnable)}，当设置值发生变更时实时响应更新业务逻辑。</li>
 * </ul>
 *
 * @see SettingsInit
 * @see modtools.content.SettingsUI
 */
@SuppressWarnings({"unused"})
public interface ISettings extends E_DataInterface {
	String SUFFIX_ENABLED = "$enabled";
	float  DISABLED_ALPHA = 0.7f;

	/** 这会根据实现自动更改 */
	Data data = null;


	/** 获取数据 */
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
		data().setDef(switchKey(), b);
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
	default <T> T getDefault() {
		return (T) data().getDef(name());
	}
	default void def(Object o) {
		if (o instanceof String[]) o = array2Jval(o, String[].class);
		data().setDef(name(), o);
	}
	default void defTrue() {
		if (type() != boolean.class) {
			throw new IllegalStateException("the settings is " + type() + " not boolean.class");
		}
		data().setDef(name(), true);
	}
	default void set(Object o) {
		o = Tools.cast(o, type());
		data().put(name(), o);
	}
	default void set(boolean b) {
		if (type() != boolean.class) {
			throw new IllegalStateException("the settings is " + type() + " not boolean.class");
		}
		set((Boolean) b);
	}


	// getter
	/** 获取设置是否可用，如果禁用，则返回false */
	default boolean enabled() {
		if (type() != boolean.class) {
			throw new IllegalStateException("the settings is " + type() + " not boolean.class");
		}
		return isSwitchOn() && data().getBool(name());
	}
	default void toggle() {
		if (type() != boolean.class) {
			throw new IllegalStateException("the settings is " + type() + " not boolean.class");
		}
		set(!enabled());
	}
	default Object get() {
		return data().get(name());
	}
	/** 仅仅是调用{@link String#valueOf(Object)} */
	default String getString() {
		Object o = get();
		if (type() == String.class && o instanceof Jval) set(o = ((Jval) o).asString());
		return String.valueOf(o);
	}
	default JsonArray getArray() {
		Object o = get();
		switch (o) {
			case null -> {
				return new JsonArray();
			}
			case JsonArray jvals -> {
				return jvals;
			}
			case Jval jval when jval.isArray() -> {
				return jval.asArray();
			}
			default -> {
			}
		}

		if (type() == String[].class && o instanceof String[]) {
			set(o = array2Jval(o, String[].class));
		} else {
			set(o = new JsonArray());
			Log.err(new IllegalStateException("the settings " + type() + " is not supported"));
		}
		return (JsonArray) o;
	}
	private static JsonArray array2Jval(Object o, Class<?> knownType) {
		return Jval.read(IntVars.json.toJson(o, knownType)).asArray();
	}
	default Locale getLocale() {
		return LocaleUtils.getLocale(getString());
	}
	default Drawable getDrawable(Drawable def) {
		String   s        = getString();
		int      index    = s.indexOf('#');
		String   key      = index == -1 ? s : s.substring(0, index);
		Drawable drawable = FormatHelper.lookupUI(key);
		return new DelegatingDrawable(or(drawable, def),
		 index == -1 ? Color.white : Color.valueOf(s.substring(index)));
	}

	default <T extends Enum<T>> T getEnum(Class<T> cl) {
		try {
			return Enum.valueOf(cl, data().getString(name()).trim());
		} catch (Exception e) {
			Log.err(e);
			return getDefault();
		}
	}
	default int getInt() {
		if (type() != int.class) { throw new IllegalStateException("the settings " + type() + " not int.class"); }
		return data().getInt(name(), 0);
	}
	default float getFloat() {
		if (type() != float.class) { throw new IllegalStateException("the settings " + type() + " not float.class"); }
		return data().getFloat(name(), 0);
	}
	default int getColorInt() {
		if (type() != Color.class) { throw new IllegalStateException("the settings " + type() + " not Color.class"); }
		return data().get0xInt(name(), -1);
	}

	Color $c1 = new Color();
	/** @return {@link #$c1}同一个实例 */
	default Color getColor() {
		return $c1.set(getColorInt());
	}
	default Vec2 getPosition() {
		if (type() != Position.class) {
			throw new IllegalStateException("the settings " + type() + " not Position.class");
		}
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
			if (value.name().startsWith("_")) {
				Underline.of(table, 1);
			} else {
				value.build(prefix, table);
			}
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
		text = (prefix + name()).toLowerCase();
		Class<?> type = type();

		try {
			SettingsBuilder.build(table);
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
	default void onChange(Runnable r) {
		data().onChange(name(), r);
	}
	default void runAndOnChange(Runnable r) {
		r.run();
		onChange(r);
	}

	class ZX {
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
			set(val);
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
			set(val);
			value.setText(Strings.autoFixed(val, -Mathf.floor(Mathf.log(10, step)) + 1));
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
		colorBlock(main(), text, data(), name(), getColorInt(), this::set);
	}
	/** (enumClass) */
	default <T extends Enum<T>> void buildEnum(Enum<T> def, Class<T> enumClass) {
		def(def);
		enum_(text, enumClass, this::set, () -> {
			try {
				return Enum.valueOf(enumClass, data().getString(name()));
			} catch (Throwable e) { return getDefault(); }
		}, this::isSwitchOn);
	}
	/** 参数：({@link String}, def, ...arr) */
	default void buildStr(String def, String... arr) {
		def(def);
		list(text, this::set, this::getString,
		 new Seq<>(arr), s -> s.replaceAll("\\n", "\\\\n"));
	}
	default void intField(int def, int min, int max) {
		def(def);
		field(text, getInt(), this::set, min, max);
	}

	default <T> void $(T def, Func<String, T> valFunc, Func<T, String> stringify, T[]... arr) {
		list(text, this::set, () -> valFunc.get(getString()), new Seq<>(arr[0]), stringify);
	}

	default void array(String[] def) {
		SettingsBuilder.array(text, data(), name(), this::isSwitchOn);
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
			}).disabled(_ -> !isSwitchOn());
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