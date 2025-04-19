package modtools.events;

import arc.func.Cons;

// @SettingsInit
public enum E_Color implements ISettings{
	// keyword(Color.class, it -> it.$(new Color(0xF92672_FF))),
	// type(Color.class, it -> it.$(new Color(0x66D9EF_FF))),
	// underline(Color.class, it -> it.$(Color.lightGray.cpy().a(0.5f))),
	// window_title(Color.class, it -> it.$(Color.sky.cpy().lerp(Color.gray, 0.6f).a(0.9f))),
	//
	;

	E_Color(Class<?> type, Cons<ISettings> builder){};
}
