package modtools.events;

import arc.Core;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.scene.event.Touchable;
import arc.scene.ui.*;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
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

public interface ISettings extends E_DataInterface {
	/** 这会根据实现自动更改  */
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
	 * */
	default void build(String prefix, Table table) {
		main = table;
		text = (prefix + name()).toLowerCase();

		Method build = builds.find(m -> m.getParameterTypes()[0] == Tools.box(type()));
		Tools.runLoggedException(() -> build.invoke(this, (Object) null));
	}
	class $$ {
		static String text;
	}

	// ----通过反射执行对应的方法----
	Seq<Method> builds = new Seq<>(ISettings.class.getDeclaredMethods()).removeAll(b -> !b.getName().equals("b"));

	private void b(Boolean __) {
		SettingsBuilder.check(text, this::set, this::enabled);
	}
	/** 默认step为1 */
	private void b(Integer __) {
		var    args   = (int[]) args();
		float  min    = args[0];
		float  max    = args[1];
		float  step   = args.length == 2 ? 1f : args[2];
		Slider slider = new Slider(min, max, step, false);
		slider.setValue(getInt());
		Label value = new Label(getString(), Styles.outlineLabel);
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
		main.stack(slider, content).growX().padTop(4f).row();
	}
	/** 默认step为0.1 */
	private void b(Float __) {
		var    args   = (float[]) args();
		float  min    = args[0];
		float  max    = args[1];
		float  step   = args.length == 2 ? 0.1f : args[2];
		Slider slider = new Slider(min, max, step, false);
		slider.setValue(getFloat());
		Label value = new Label(getString(), Styles.outlineLabel);
		slider.moved(val -> {
			set(val);
			value.setText(Strings.autoFixed(val, -Mathf.floor(Mathf.log(10, step))));
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
		SettingsBuilder.list(text, this::set, () -> Enum.valueOf(enumClass, data().getString(name())),
		 enums, Enum::name);
	}
	private void b(String[] __) {
		var list = new Seq<>((String[]) args());
		SettingsBuilder.list(text, this::set, this::getString,
		 list, s -> s.replaceAll("\\n", "\\\\n"));
	}
}
