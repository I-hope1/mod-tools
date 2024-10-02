package modtools.events;

import modtools.annotations.settings.SettingsInit;

@SettingsInit
public enum E_Extending implements ISettings {
	auto_update,
	http_redirect,
	import_mod_from_drop;

	static {
		auto_update.defTrue();
	}
}
