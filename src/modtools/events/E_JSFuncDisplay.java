package modtools.events;

import modtools.annotations.SettingsInit;
import modtools.ui.content.Content.ISettings;

@SettingsInit(value = "Display", parent = "E_JSFunc")
public enum E_JSFuncDisplay implements ISettings {
	modifier, type, value, buttons;

	static {
		for (E_JSFuncDisplay value : values()) {
			value.def(true);
		}
	}
}
