package modtools.events;

import arc.func.Cons;
import modtools.annotations.settings.*;
import modtools.ui.effect.ScreenSampler;

@SettingsInit
public enum E_Blur implements ISettings {
	enabled {
		public boolean isSwitchOn() {
			return ScreenSampler.instance.activity();
		}
	},
	/** 金字塔层数与采样偏移都由它推导，见 {@code EBBlur#depthFor} / {@code EBBlur#offsetFor}。 */
	/** @see ISettings#$(float def, float min, float max, float step) */
	@Switch(dependency = "enabled")
	scale_level(int.class, it -> it.$(4, 1, 16, 1));

	E_Blur() { }
	E_Blur(Class<?> type, Cons<ISettings> builder) { }
}
