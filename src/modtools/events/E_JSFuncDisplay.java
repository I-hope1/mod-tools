package modtools.events;

import modtools.utils.MySettings;
import modtools.utils.MySettings.Data;

public enum E_JSFuncDisplay {
	modifier, type, value, buttons;

	public static final Data data = MySettings.D_JSFUNC_DISPLAY;
	public boolean enabled() {
		return data.getBool(name());
	}
	static {
		for (var value : values()) {
			data.getBool(value.name(), true);
		}
	}
}
