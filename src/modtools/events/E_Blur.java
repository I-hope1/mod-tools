package modtools.events;

import modtools.annotations.settings.*;
import modtools.ui.effect.EBBlur.DEF;

@SettingsInit
public enum E_Blur implements ISettings {
	enabled,
	/** @see ISettings#$(Integer) */
	@Switch(dependency = "enabled")
	scale_level(int.class, 4, 1, 16),
	/** @see ISettings#$(Enum) */
	@Switch(dependency = "enabled")
	convolution_scheme(Enum.class, DEF.class);

	static {
		convolution_scheme.def(DEF.D);
	}

	E_Blur() { }
	E_Blur(Class<?> type, Class<?> enumClass) { }
	E_Blur(Class<?> type, int... args) { }
}
