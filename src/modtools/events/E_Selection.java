package modtools.events;

import modtools.utils.MySettings;
import modtools.utils.MySettings.Data;

public enum E_Selection {
	tile, building, unit, bullet;

	public static final Data data = MySettings.D_SELECTION;
	public boolean enabled() {
		return data.getBool(name());
	}
	public void set(boolean bool) {
		data.put(name(), bool);
	}
	static {
		data.getBool(tile.name(), true);
	}
}
