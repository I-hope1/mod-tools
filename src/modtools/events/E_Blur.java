package modtools.events;

import modtools.annotations.settings.*;
import modtools.ui.effect.EBBlur.DEF;

@SettingsInit
public enum E_Blur implements ISettings {
	enabled,
	/** @see ISettings#$(Integer) */
	@Switch(dependency = "enabled")
	scale_level(int.class, 4/* def */, 1/* min */, 16/* max */),
	/** @see ISettings#$(Enum) */
	@Switch(dependency = "enabled")
	convolution_scheme(Enum.class, DEF.D, DEF.class);

	E_Blur() { }
	E_Blur(Class<?> type, Enum<?> obj, Class<?> clazz) { }
	E_Blur(Class<?> type, int... args) { }
}
