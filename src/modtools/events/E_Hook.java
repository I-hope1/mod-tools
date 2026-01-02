package modtools.events;

import arc.func.Cons;
import arc.util.OS;
import modtools.IntVars;
import modtools.annotations.settings.SettingsInit;

@SettingsInit
public enum E_Hook implements ISettings {
	dynamic_jdwp {
		public boolean isSwitchOn() {
			return IntVars.isDesktop();
		}
	},
	dynamic_jdwp_port(int.class, i -> i.intField(5005, 1, 65535)),
	android_input_fix {
		public boolean isSwitchOn() {
			return OS.isAndroid;
		}
	};
	E_Hook(Class<?> type, Cons<ISettings> builder) { }
	E_Hook(){}
}
