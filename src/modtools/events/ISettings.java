package modtools.events;

import arc.graphics.Color;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Log;
import modtools.ui.content.SettingsUI;
import modtools.ui.content.SettingsUI.SettingsBuilder;
import modtools.utils.MySettings.Data;
import modtools.utils.Tools;

import java.lang.reflect.Method;

import static modtools.events.ISettings.__.text;
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

		Method build = new Seq<>(ISettings.class.getDeclaredMethods())
		 .find(m -> m.getName().equals("build") && m.getParameterTypes()[0] == Tools.box(type()));
		try {
			build.invoke(this, (Object) null);
		} catch (Throwable e) {Log.err(e);}
	}
	class __ {
		static String text;
	}

	// ----通过反射执行对应的方法----

	private void build(Boolean __) {
		SettingsBuilder.check(text, this::set, this::enabled);
	}
	/** 默认step为1 */
	private void build(Integer __) {
		var args = (int[]) args();
		SettingsUI.slideri(main, data(), text, args[0], args[1],
		 getInt(), args.length == 2 ? 1 : args[2], this::set);
	}
	/** 默认step为0.1 */
	private void build(Float __) {
		var args = (float[]) args();
		SettingsUI.slider(main, data(), text, args[0], args[1],
		 getFloat(), args.length == 2 ? 0.1f : args[2], this::set);
	}
	private void build(Color __) {
		SettingsUI.colorBlock(main, text, data(), name(), getColor(), this::set);
	}
	private void build(Enum<?> __) {
		var enumClass = (Class) args();
		var enums     = new Seq<>((Enum<?>[]) enumClass.getEnumConstants());
		SettingsBuilder.list(text, this::set, () -> Enum.valueOf(enumClass, data().getString(name())),
		 enums, Enum::name);
	}
	private void build(String[] __) {
		var list = new Seq<>((String[]) args());
		SettingsBuilder.list(text, this::set, this::getString,
		 list, s -> s.replaceAll("\\n", "\\\\n"));
	}
}
