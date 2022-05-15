package modtools.ui.content;

import arc.scene.actions.Actions;
import mindustry.ui.dialogs.BaseDialog;

import java.lang.reflect.Method;

public class ActionsDebug extends Content {

	BaseDialog ui;

	public ActionsDebug(String name) {
		super(name);
	}

	@Override
	public void load() {
		ui = new BaseDialog(localizedName());
		ui.cont.table(t -> {
			Method[] methods = Actions.class.getMethods();
			for (Method m : methods) {

			}
		});
		ui.addCloseButton();
	}

	@Override
	public void build() {
		ui.show();
	}
}
