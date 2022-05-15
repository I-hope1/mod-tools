package modtools.ui;

import modtools.ui.content.*;

public class Contents {
	public static Settings settings;
	public static Tester tester;
	public static Selection selection;
	public static ShowUIList showuilist;
	public static UnitSpwan unitSpwan;
	public static void load() {
		settings = new Settings();
		tester = new Tester();
		selection = new Selection();
		showuilist = new ShowUIList();
		unitSpwan = new UnitSpwan();
	}
}