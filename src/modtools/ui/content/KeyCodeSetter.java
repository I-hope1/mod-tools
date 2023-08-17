package modtools.ui.content;

import modtools.ui.components.Window;

public class KeyCodeSetter extends Content{
	public KeyCodeSetter() {
		super("keycodeSetter");
	}
	Window ui;
	public void buildUI(){
		ui = new Window(localizedName());
	}
	public void build() {
		if (ui == null) buildUI();
		ui.show();
	}
}
