package modtools.events;

import modtools.annotations.DataEnum;
import modtools.utils.MySettings;
import modtools.utils.MySettings.Data;

@SuppressWarnings("unused")
@DataEnum
enum E_TesterComp implements E_DataInterface {
	ignore_popup_error, catch_outsize_error, wrap_ref,
	rollback_history, multi_windows, output_to_log, js_prop;

	public static final Data data = MySettings.SETTINGS.child("tester");
}
