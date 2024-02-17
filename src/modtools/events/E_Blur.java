package modtools.events;

import modtools.annotations.SettingsInit;
import modtools.ui.effect.EBBlur.DEF;

@SettingsInit
public enum E_Blur implements ISettings {
	enabled, scale_level(int.class, 4, 1, 16),
	convolution_scheme(Enum.class, DEF.class);
	static {
		convolution_scheme.def(DEF.D);
	}

	E_Blur() {}
	E_Blur(Class<?> type, Class<?> enumClass) {}
	E_Blur(Class<?> type, int... args) {}
}
