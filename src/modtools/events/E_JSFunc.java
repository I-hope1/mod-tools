package modtools.events;

import modtools.annotations.settings.SettingsInit;

@SettingsInit(fireEvent = true)
public enum E_JSFunc implements ISettings {
	watch_multi, search_exact, auto_refresh, display_generic,
	truncate_text, hidden_if_empty, display_synthetic, update_async,
	folded_name,
	/** @see ISettings#$(String[]) */
	array_delimiter(String[].class, ", ", "\n", "\n\n", "\n▶▶▶▶", "\n★★★");

	static {
		array_delimiter.def(((String[]) array_delimiter.args())[0]);
		auto_refresh.defTrue();
		display_generic.defTrue();
		truncate_text.defTrue();
		hidden_if_empty.defTrue();
		update_async.defTrue();
	}

	E_JSFunc() {}
	E_JSFunc(Class<?> cl, String... args) {}
}