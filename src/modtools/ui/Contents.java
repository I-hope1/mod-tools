package modtools.ui;

import modtools.ui.content.*;
import modtools.ui.content.debug.*;
import modtools.ui.content.ui.*;
import modtools.ui.content.world.*;

public class Contents {
	public static SettingsContent settingsUI;
	public static Tester          tester;
	public static Selection       selection;
	public static ShowUIList      showuilist;
	public static UnitSpawn       unitSpawn;
	public static ErrorDisplay    errorDisplay;
	public static ContentList     contentList;
	public static ReviewElement   reviewElement;
	public static WindowManager   windowManager;
	public static ActionsDebug    actionsDebug;

	public static void load() {
		settingsUI = new SettingsContent();
		tester = new Tester();
		selection = new Selection();
		showuilist = new ShowUIList();
		unitSpawn = new UnitSpawn();
		errorDisplay = new ErrorDisplay();
		contentList = new ContentList();
		reviewElement = new ReviewElement();
		windowManager = new WindowManager();
		actionsDebug = new ActionsDebug();
	}
}
