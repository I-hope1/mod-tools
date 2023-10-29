package modtools.events;

import modtools.annotations.DataEnum;

@SuppressWarnings("unused")
@DataEnum
enum E_JSFuncComp implements E_DataInterface {
	watch_multi, search_exact, auto_refresh, display_generic,
	truncate_text, hidden_if_empty, display_synthetic, updateAsync;

	// public static final Data data = MySettings.D_JSFUNC;
	// public boolean enabled() {return data.getBool(name());}
	static {
		auto_refresh.def(true);
		display_generic.def(true);
		truncate_text.def(true);
		hidden_if_empty.def(true);
		display_synthetic.def(false);
		updateAsync.def(true);
	}
}