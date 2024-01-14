package modtools.events;

import modtools.annotations.DataEnum;
import modtools.ui.content.Content;

@DataEnum
enum E_JSFuncEditComp implements Content.E_DataInterface {
	number, string;

	static {
		for (Content.E_DataInterface value : values()) {
			value.def(false);
		}
	}
}
