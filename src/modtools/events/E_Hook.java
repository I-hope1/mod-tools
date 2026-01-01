package modtools.events;

import arc.util.OS;
import modtools.annotations.settings.SettingsInit;

@SettingsInit
public enum E_Hook implements ISettings {
	android_input_fix {
		public boolean isSwitchOn() {
			return OS.isAndroid;
		}
	};

}
