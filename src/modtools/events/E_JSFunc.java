package modtools.events;

import modtools.annotations.SettingsInit;

@SettingsInit
public enum E_JSFunc implements ISettings {
	watch_multi, search_exact, auto_refresh, display_generic,
	truncate_text, hidden_if_empty, display_synthetic, update_async,
	folded_name;

	static {
		auto_refresh.defTrue();
		display_generic.defTrue();
		truncate_text.defTrue();
		hidden_if_empty.defTrue();
		update_async.defTrue();
	}
}