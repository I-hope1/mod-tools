package modtools.ui.windows;

import arc.Core;
import arc.func.Cons;
import arc.input.KeyCode;
import arc.scene.event.*;
import arc.scene.ui.TextField;
import arc.scene.ui.TextField.TextFieldValidator;
import modtools.ui.IntUI.PopupWindow;
import modtools.ui.components.Window;

public class NameWindow extends Window implements PopupWindow {
	TextField    namef = new TextField();
	Cons<String> okCons;

	{
		cont.table(t -> {
			t.add("@name");
			t.add(namef).growX();
		}).growX().row();

		buttons.button("@ok", () -> {
			okCons.get(namef.getText());
			hide();
		}).growX().disabled(__ -> !namef.isValid());
		// closeOnBack();
	}

	public NameWindow(Cons<String> okCons, TextFieldValidator valid, String text) {
		super("", 120, 80, false, false);
		this.okCons = okCons;
		if (valid != null) namef.setValidator(valid);
		namef.setText(text);

		moveToMouse();
	}

	public Window show() {
		namef.setMessageText("请输入");
		namef.addListener(new InputListener() {
			@Override
			public boolean keyUp(InputEvent event, KeyCode keycode) {
				if (keycode == KeyCode.enter) {
					okCons.get(namef.getText());
					hide();
				}
				return false;
			}
		});
		return super.show();
	}
}
