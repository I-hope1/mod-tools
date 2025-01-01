package modtools.events;

import arc.func.Cons;
import mindustry.Vars;
import modtools.annotations.settings.SettingsInit;
import modtools.utils.LocaleUtils;

import java.util.*;
import java.util.stream.Stream;

@SettingsInit
public enum E_Extending implements ISettings {
	auto_update,
	// 高亮鼠标位置
	double_shift_highlight,
	http_redirect,
	/** @see Vars#locales */
	force_language(Locale.class, it -> it.$(
	 LocaleUtils.none, LocaleUtils::getLocale, LocaleUtils::getDisplayName,
	 Stream.concat(Stream.of(LocaleUtils.none), Arrays.stream(Vars.locales)).toArray(Locale[]::new))),
	import_mod_from_drop;

	E_Extending(){}
	E_Extending(Class<?> cl, Cons<ISettings> builder){}

	static {
		auto_update.defTrue();
	}
}
