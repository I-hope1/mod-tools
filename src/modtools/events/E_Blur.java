package modtools.events;

import arc.func.Cons;
import modtools.annotations.settings.*;
import modtools.ui.effect.EBBlur.DEF;

@SettingsInit
public enum E_Blur implements ISettings {
	enabled,
	/** @see ISettings#$(float def, float min, float max, float step) */
	@Switch(dependency = "enabled")
	scale_level(int.class, it -> it.$(4, 1, 16, 1)),
	/** @see ISettings#$(Enum, Class)  */
	@Switch(dependency = "enabled")
	convolution_scheme(Enum.class, it -> it.$(DEF.D, DEF.class));

	E_Blur() { }
	E_Blur(Class<?> type, Cons<ISettings> builder) { }
}
