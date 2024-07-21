package modtools.ui.comp.linstener;

import arc.Core;
import arc.input.KeyCode;
import arc.scene.event.*;
import arc.scene.ui.TextField;
import modtools.ui.control.HopeInput;

public class EscapeAndAxesClearListener extends InputListener {
	TextField area;
	public EscapeAndAxesClearListener(TextField area) {
		this.area = area;
	}
	public boolean keyDown(InputEvent event, KeyCode keycode) {
		if (keycode == KeyCode.escape && Core.scene.getKeyboardFocus() == area) {
			Core.scene.unfocus(area);
			HopeInput.justPressed.remove(KeyCode.escape.ordinal());
		}
		return super.keyDown(event, keycode);
	}
	public boolean scrolled(InputEvent event, float x, float y, float amountX, float amountY) {
		HopeInput.axes.clear();
		return super.scrolled(event, x, y, amountX, amountY);
	}
}
