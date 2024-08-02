package modtools.ui.comp.linstener;

import arc.input.KeyCode;
import arc.scene.Element;
import arc.scene.event.*;

/** 自动保持键盘焦点  */
public class KeepFocusListener  extends InputListener {
	final Element element;
	public KeepFocusListener(Element element) {
		this.element = element;
	}
	public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
		return true;
	}
	public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
		element.requestKeyboard();
		super.touchUp(event, x, y, pointer, button);
	}
}
