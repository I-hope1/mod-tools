package modtools.events;

import arc.func.*;
import arc.graphics.Color;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import arc.util.Log;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import modtools.ui.content.SettingsUI;
import modtools.ui.content.SettingsUI.SettingsBuilder;
import modtools.utils.MySettings.Data;

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
	default int getColor() {
		return data().get0xInt(name(), -1);
	}

	default void build(Table table) {
		build("", table);
	}

	default void build(String prefix, Table table) {
		SettingsBuilder.main = table;
		final String key  = name();
		final String text = prefix + key;

		Class<?> type = type();
		if (type == boolean.class) {
			SettingsBuilder.check(text, this::set, this::enabled);
		} else if (type == int.class) {
			int[] args = (int[]) args();
			SettingsUI.slideri(table, data(), text, args[0], args[1],
			 getInt(), args.length == 2 ? 1 : args[2], this::set);
		} else if (type == Color.class) {
			SettingsUI.colorBlock(table, text, data(), key, getColor(), this::set);
		} else if (Enum.class.isAssignableFrom(type)) {
			var enums = new Seq<>((Enum<?>[]) type.getEnumConstants());
			SettingsBuilder.list(text, this::set, () -> Enum.valueOf((Class) type, data().getString(name())),
			 enums, Enum::name);
		} else if (String[].class == type) {
			var list = new Seq<>((String[])args());
			SettingsBuilder.list(text, this::set, this::getString,
			 list, s -> s.replaceAll("\\n", "\\\\n"));
		}
	}
}
