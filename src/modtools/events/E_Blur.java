package modtools.events;

import modtools.annotations.SettingsInit;
import modtools.ui.effect.EBBlur.DEF;

@SettingsInit
public enum E_Blur implements ISettings {
	enabled, scale_level(int.class, 0, 16),
	convolution_scheme(DEF.class);
	static {
		scale_level.def(4);
		convolution_scheme.def(DEF.D);
	}
	E_Blur() {}
	E_Blur(Class<?> type) {}
	E_Blur(Class<?> type, int... args) {}
}
