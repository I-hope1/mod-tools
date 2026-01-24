package modtools.events;

import arc.func.Cons;
import modtools.IntVars;
import modtools.annotations.settings.SettingsInit;

@SettingsInit
public enum E_UISettings implements ISettings {
	menu_trigger(MenuTrigger.class, it ->
	 it.buildEnum(IntVars.isDesktop() ? MenuTrigger.right_click : MenuTrigger.long_press, MenuTrigger.class)),

	//
	;
	E_UISettings(Class<?> cl, Cons<ISettings> builder) { }

	public enum MenuTrigger {
		/** 按下鼠标右键 */
		right_click,
		/** 按下鼠标左键并长按 */
		long_press,
	}
}