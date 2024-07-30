package modtools.events;

import modtools.annotations.settings.*;

@SettingsInit(fireEvent = true)
public enum E_JSFunc implements ISettings {
	watch_multi, search_exact, auto_refresh, display_generic,
	truncate_text,
	/** @see ISettings#$(Integer) */
	@Switch(dependency = "truncate_text")
	truncate_length(int.class, 1000/* def */, 100/* min */, 100000/* max */, 10/* step */),

	hidden_if_empty, display_synthetic, update_async,
	folded_name,
	/** @see ISettings#$(String) */
	array_delimiter(String.class, ", ",
	 ", ", "\n", "\n\n",
	 "\n▶▶▶▶", "\n★★★");

	static {
		auto_refresh.defTrue();
		display_generic.defTrue();
		truncate_text.defTrue();
		hidden_if_empty.defTrue();
		update_async.defTrue();
	}

	E_JSFunc() { }
	E_JSFunc(Class<?> cl, String def, String... args) { }
	E_JSFunc(Class<?> cl, int... args) { }
}