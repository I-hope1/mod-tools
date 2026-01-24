package modtools.events;

import arc.func.Cons;
import modtools.annotations.settings.*;
import modtools.ui.effect.EBBlur.DEF;
import modtools.ui.effect.ScreenSampler;

@SettingsInit
public enum E_Blur implements ISettings {
	enabled {
		public boolean isSwitchOn() {
			return ScreenSampler.activity;
		}
	},
	/** @see ISettings#$(float def, float min, float max, float step) */
	@Switch(dependency = "enabled")
	scale_level(int.class, it -> it.$(4, 1, 16, 1)),
	/** @see ISettings#buildEnum(Enum, Class)  */
	@Switch(dependency = "enabled")
	convolution_scheme(DEF.class, it -> it.buildEnum(DEF.D, DEF.class));

	E_Blur() { }
	E_Blur(Class<?> type, Cons<ISettings> builder) { }
}
