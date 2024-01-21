package modtools.events;

import modtools.annotations.SettingsInit;

@SettingsInit
public enum E_JSFunc implements ISettings {
	watch_multi, search_exact, auto_refresh, display_generic,
	truncate_text, hidden_if_empty, display_synthetic, update_async,
	folded_name;

	static {
		auto_refresh.def(true);
		display_generic.def(true);
		truncate_text.def(true);
		hidden_if_empty.def(true);
		update_async.def(true);
	}
}