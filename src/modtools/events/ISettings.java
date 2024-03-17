package modtools.events;

import arc.func.*;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.ui.content.SettingsUI;
import modtools.ui.content.SettingsUI.SettingsBuilder;
import modtools.utils.MySettings.Data;
import modtools.utils.Tools;

import java.lang.reflect.Method;

import static modtools.events.ISettings.$$.text;
import static modtools.ui.content.SettingsUI.SettingsBuilder.main;

@SuppressWarnings({"unused", "Convert2Lambda"/* 为了兼容java8 */})
public interface ISettings extends E_DataInterface {
	/** 这会根据实现自动更改 */
	Data data = null;
	default Data data() {
		return null;
	}
	default Class<?> type() {return boolean.class;}
	default Object args() {return null;}
	default String name() {
		return null;
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
	default void set(boolean b) {set((Boolean) b);}
	/* default <T> T get() {
		return (T) data().get(name());
	} */
	default boolean enabled() {
		return data().getBool(name());
	}
	default Object get() {return data().get(name());}
	default String getString() {return data().getString(name());}

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

	static void buildAllWrap(String prefix, Table p, String title, Class<? extends ISettings> cl) {
		p.row().table(Tex.pane, table -> {
			table.left().defaults().left();
			table.add(title).color(Pal.accent).row();
			ISettings.buildAll(prefix, table, cl);
		});
	}

	/** @param prefix 用于显示设置文本 */
	static void buildAll(String prefix, Table table, Class<? extends ISettings> cl) {
		buildAll0("@settings." + prefix, table, cl);
	}
	private static void buildAll0(String prefix, Table table, Class<? extends ISettings> cl) {
		for (ISettings value : cl.getEnumConstants()) {
			value.build(prefix, table);
		}
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

		Method build = builds.get(Tools.box(type));
		try {
			build.invoke(this, (Object) null);
		} catch (Throwable e) {
			e.fillInStackTrace();
			Log.err("Failed to build " + getClass() + "." + this, e);
		}
	}
	class $$ {
		static String text;

		static {
			builds.each((_, m) -> m.setAccessible(true));
		}
	}

	// ----通过反射执行对应的方法----
	ObjectMap<Class<?>, Method> builds = new Seq<>(ISettings.class.getDeclaredMethods())
	 .removeAll(b -> !b.getName().equals("b"))
	 .asMap(m -> m.getParameterTypes()[0]);


	private void b(Boolean __) {
		SettingsBuilder.check(text, this::set, this::enabled);
	}
	/** 默认step为1 */
	private void b(Integer __) {
		var    args   = (int[]) args();
		float  def    = args[0];
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
	private void b(Float __) {
		var    args   = (float[]) args();
		float  def    = args[0];
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
	private void b(Color __) {
		SettingsUI.colorBlock(main, text, data(), name(), getColor(), this::set);
	}
	private void b(Enum<?> __) {
		var enumClass = (Class) args();
		var enums     = new Seq<>((Enum<?>[]) enumClass.getEnumConstants());
		SettingsBuilder.list(text, this::set, new Prov<Enum<?>>() {
			public Enum<?> get() {
				return Enum.valueOf(enumClass, ISettings.this.data().getString(ISettings.this.name()));
			}
		}, enums, Enum::name);
	}
	private void b(String[] __) {
		var list = new Seq<>((String[]) args());
		SettingsBuilder.list(text, this::set, this::getString,
		 list, s -> s.replaceAll("\\n", "\\\\n"));
	}
}