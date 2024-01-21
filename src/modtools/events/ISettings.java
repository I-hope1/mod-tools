package modtools.events;

import arc.scene.ui.layout.Table;
import modtools.ui.content.SettingsUI;
import modtools.utils.MySettings.Data;

public interface ISettings extends E_DataInterface {
	default Data data() {
		return null;
	}
	default Class<?> type() {return null;}
	default String name() {
		return null;
	}
	default void def(Object o) {
		data().get(name(), o);
	}
	default void defTrue() {
		data().get(name(), "true");
	}
	default void set(Object o) {
		data().put(name(), o);
	}
	default void set(boolean o) {
		data().put(name(), o);
	}
	/* default <T> T get() {
		return (T) data().get(name());
	} */
	default boolean enabled() {
		return data().getBool(name());
	}

	default void build(Table table) {
		if (type() == boolean.class) {
			SettingsUI.bool(table, name(), data(), name(), false, this::set);
		}
	}
}
