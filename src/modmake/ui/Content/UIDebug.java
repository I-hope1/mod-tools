package modmake.ui.Content;

import arc.scene.ui.layout.Table;
import mindustry.ui.dialogs.BaseDialog;

public class UIDebug extends Content {

	BaseDialog ui;

	public UIDebug(String name) {
		super(name);
	}

	@Override
	public void load() {
		ui = new BaseDialog(localizedName());
		// ui.cont;
	}

	@Override
	public void build() {

	}
}
