package modtools.events;

import modtools.utils.MySettings;
import modtools.utils.MySettings.Data;

public enum E_Tester {
	ignore_popup_error, catch_outsize_error, wrap_ref,
	rollback_history, multi_windows, output_to_log, js_prop;

	public static final Data data = MySettings.D_TESTER;
	public boolean enabled() {
		return data.getBool(name());
	}
}
