package modtools.events;

import modtools.annotations.*;
import modtools.ui.content.Content;
import modtools.ui.content.Content.ISettings;

@SettingsInit
public enum E_JSFuncEdit implements ISettings {
	number, string,
	final_modify;

	static {
		for (E_JSFuncEdit value : values()) {
			value.def(false);
		}
	}
}
