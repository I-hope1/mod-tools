package modmake.ui.Content;

import arc.scene.actions.Actions;
import mindustry.ui.dialogs.BaseDialog;

public class ActionsDebug extends Content {

	BaseDialog ui;

	public ActionsDebug(String name) {
		super(name);
	}

	@Override
	public void load() {
		ui = new BaseDialog(localizedName());
		ui.cont.table(t -> {

		});
		ui.addCloseButton();
	}

	@Override
	public void build() {
		ui.show();
	}
}
