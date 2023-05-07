package modtools.events;

import modtools.utils.MySettings;
import modtools.utils.MySettings.Data;

public enum E_JSFunc {
	watch_multi, search_exact, auto_refresh, display_generic,
	truncate_text;

	public static final Data data = MySettings.D_JSFUNC;
	public boolean enabled() {
		return data.getBool(name());
	}
	static {
		data.getBool(auto_refresh.name(), true);
		data.getBool(display_generic.name(), true);
		data.getBool(truncate_text.name(), true);
	}
}