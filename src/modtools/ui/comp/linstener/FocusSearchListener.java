package modtools.ui.comp.linstener;

import arc.Core;
import arc.input.KeyCode;
import arc.scene.event.*;
import arc.scene.ui.TextField;

public class FocusSearchListener extends InputListener {
	TextField textField;
	public FocusSearchListener(TextField textField) {
		this.textField = textField;
	}
	public boolean keyDown(InputEvent event, KeyCode keycode) {
		if (Core.input.ctrl() && keycode == KeyCode.f) {
			textField.requestKeyboard();
			textField.setCursorPosition(Integer.MAX_VALUE);
			if (Core.input.shift()) textField.clear();
			event.cancel();
		}
		return false;
	}
}
