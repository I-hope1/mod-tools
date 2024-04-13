package modtools.events;

import modtools.annotations.settings.SettingsInit;

@SettingsInit(value = "Edit", parent = "E_JSFunc", fireEvent = true)
public enum E_JSFuncEdit implements ISettings {
	number, string,
	final_modify;

	static {
		for (E_JSFuncEdit value : values()) {
			value.def(false);
		}
	}
}
