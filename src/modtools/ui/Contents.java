package modtools.ui;

import modtools.ui.content.Selection;
import modtools.ui.content.Settings;
import modtools.ui.content.ShowUIList;
import modtools.ui.content.Tester;
import modtools.ui.content.UnitSpawn;

public class Contents {
	public static Settings settings;
	public static Tester tester;
	public static Selection selection;
	public static ShowUIList showuilist;
	public static UnitSpawn unitSpawn;

	public static void load() {
		settings = new Settings();
		tester = new Tester();
		selection = new Selection();
		showuilist = new ShowUIList();
		unitSpawn = new UnitSpawn();
	}
}
