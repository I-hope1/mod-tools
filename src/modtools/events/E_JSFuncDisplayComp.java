package modtools.events;

import modtools.annotations.DataEnum;
import modtools.ui.content.Content;

@SuppressWarnings("unused")
@DataEnum
enum E_JSFuncDisplayComp implements Content.E_DataInterface {
	modifier, type, value, buttons;

	/* public static final Data data = MySettings.D_JSFUNC_DISPLAY;
	public boolean enabled() {
		return data.getBool(name());
	} */
	static {
		for (Content.E_DataInterface value : values()) {
			value.def(true);
		}
	}
}
