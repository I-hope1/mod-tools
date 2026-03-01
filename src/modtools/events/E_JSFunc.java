package modtools.events;

import arc.func.Cons;
import arc.util.OS;
import modtools.annotations.settings.*;

@SettingsInit(fireEvent = true)
public enum E_JSFunc implements ISettings {
	watch_multi, search_exact, auto_refresh, display_generic,
	truncate_text,
	/** @see ISettings#$(int, int, int, int) */
	@Switch(dependency = "truncate_text")
	truncate_length(int.class, it -> it.$(1000/* def */, 100/* min */, 100000/* max */, 10/* step */)),

	hidden_if_empty, display_synthetic, update_async,
	folded_name,
	/** @see ISettings#buildStr(String, String...) */
	array_delimiter(String.class, it -> it.buildStr(", ",
	 ", ", "\n", "\n\n",
	 "\n▶▶▶▶", "\n★★★")),

	/** 美化输出开关：开启后 Map/Array 使用多彩括号 + 缩进格式
	 * @see modtools.ui.comp.utils.Viewers */
	pretty_print,

	/**
	 * 最小展开的大小
	 * @see ISettings#$(int, int, int, int)
	 */
	// @Switch(dependency = "fold_items")
	max_auto_expand_size(int.class, it -> it.$(32, 0, 1024, 1)),
	/** 给hover的区块添加outline */
	hover_outline,
	/** 给ValueLabel区块添加背景 */
	chunk_background,
	/** 给ValueLabel区块添加颜色 */
	@Switch(dependency = "chunk_background")
	colorful_background,

	change_class_reference_when_edit {
		public boolean isSwitchOn() {
			return OS.isAndroid;
		}
	}

	//
	;

	static {
		auto_refresh.defTrue();
		pretty_print.defTrue();
		display_generic.defTrue();
		truncate_text.defTrue();
		hidden_if_empty.defTrue();
		update_async.defTrue();
	}

	E_JSFunc() { }
	E_JSFunc(Class<?> type, Cons<ISettings> builder) { }
}