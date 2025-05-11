package modtools.ui.comp.linstener;

import arc.Core;
import arc.input.KeyCode;
import arc.scene.event.*;
import arc.scene.ui.TextField;
import modtools.ui.control.HKeyCode;

public class FocusSearchListener extends InputListener {
	public static final HKeyCode keyCode = HKeyCode.data.dynamicKeyCode("focusSearch", () -> new HKeyCode(KeyCode.f).ctrl());
	final TextField textField;
	public FocusSearchListener(TextField textField) {
		this.textField = textField;
	}
	public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
		if (event.targetActor == textField) textField.requestKeyboard();
		return super.touchDown(event, x, y, pointer, button);
	}
	public boolean keyDown(InputEvent event, KeyCode __) {
		if (keyCode.isPress()) {
			textField.requestKeyboard();
			textField.setCursorPosition(Integer.MAX_VALUE);
			if (Core.input.shift()) textField.clear();
			event.stop();
		}
		return false;
	}
}
