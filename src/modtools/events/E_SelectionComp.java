package modtools.events;

import modtools.annotations.DataEnum;
import modtools.utils.MySettings.Data;

import static modtools.ui.Contents.selection;

@DataEnum
enum E_SelectionComp implements E_DataInterface {
	/* base */
	tile, building, unit, bullet
	/* other */, focusOnWorld;

	public static final Data data = selection.data();

	static {
		tile.def(true);
	}
}
