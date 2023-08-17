package modtools.events;

import modtools.annotations.DataEnum;

@DataEnum
enum E_JSFuncEditComp implements E_DataInterface {
	number, string;

	static {
		for (E_DataInterface value : values()) {
			value.def(false);
		}
	}
}
