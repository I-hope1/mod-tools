package modtools.events;

import modtools.annotations.settings.SettingsInit;

@SettingsInit
public enum E_Extending implements ISettings {
	auto_update,
	// 高亮鼠标位置
	double_shift_highlight,
	http_redirect,
	import_mod_from_drop;

	static {
		auto_update.defTrue();
	}
}
