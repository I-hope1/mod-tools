package modtools.events;

import modtools.annotations.SettingsInit;

@SettingsInit(value = "Display", parent = "E_JSFunc", fireEvent = true)
public enum E_JSFuncDisplay implements ISettings {
	modifier, type, value, buttons,
	address;

	static {
		for (E_JSFuncDisplay value : values()) {
			value.defTrue();
		}
	}
}
