package modtools.ui;

import modtools.annotations.ContentInit;
import modtools.ui.content.*;
import modtools.ui.content.debug.*;
import modtools.ui.content.ui.*;
import modtools.ui.content.world.*;

@SuppressWarnings("unused")
@ContentInit
public class Contents {
	public static SettingsUI    settings_ui;
	public static Tester        tester;
	public static Selection     selection;
	public static ShowUIList    show_ui_list;
	public static UnitSpawn     unit_spawn;
	public static LogDisplay    log_display;
	public static ContentList   content_list;
	public static ReviewElement review_element;
	public static ActionsDebug  actions_debug;
	// public static DesignContent design_content;
	public static Executor      executor;
	public static KeyCodeSetter key_code_setter;
	public static WindowManager window_manager;

	/**
	 * generate by annotation
	 * @see ContentInit
	 */
	public static void load() {}
}
