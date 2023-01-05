package modtools.ui;

import modtools.ui.content.*;

public class Contents {
	public static Settings settings;
	public static Tester tester;
	public static Selection selection;
	public static ShowUIList showuilist;
	public static UnitSpawn unitSpawn;
	public static ErrorDisplay errorDisplay;
	public static ContentList contentList;
	public static ElementShow elementShow;
	public static WindowManager windowManager;
	public static ActionsDebug actionsDebug;

	public static void load() {
		settings = new Settings();
		tester = new Tester();
		selection = new Selection();
		showuilist = new ShowUIList();
		unitSpawn = new UnitSpawn();
		errorDisplay = new ErrorDisplay();
		contentList = new ContentList();
		elementShow = new ElementShow();
		windowManager = new WindowManager();
		actionsDebug = new ActionsDebug();
	}
}
