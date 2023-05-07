
package modtools.ui.components.linstener;

import arc.Core;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.ui.layout.Table;

public class MoveListener extends InputListener {
	// public float bx, by;
	public final Table   main;
	public final Element touch;
	public       boolean disabled = false, isFiring = false;
	public Runnable fire;

	public MoveListener(Element touch, Table main) {
		this.main = main;
		this.touch = touch;
		touch.addListener(this);
	}
	public void remove() {
		touch.removeListener(this);
	}


	public Vec2 lastMouse = new Vec2(), lastMain = new Vec2();
	public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
		if (disabled) return false;
		lastMouse.set(Core.input.mouse());
		lastMain.set(main.x, main.y);
		isFiring = true;
		return true;
	}

	public void touchDragged(InputEvent event, float x, float y, int pointer) {
		if (disabled) return;
		if (fire != null) fire.run();

		Vec2 mouse = Core.input.mouse();
		display(lastMain.x + mouse.x - lastMouse.x, lastMain.y + mouse.y - lastMouse.y);
	}

	public void display(float x, float y) {
		float mainWidth  = main.getWidth(), mainHeight = main.getHeight();
		float touchWidth = touch.getWidth(), touchHeight = touch.getHeight();
		main.x = Mathf.clamp(x, -touchWidth / 3f, Core.graphics.getWidth() - mainWidth / 2f);
		main.y = Mathf.clamp(y, -mainHeight + touchHeight, Core.graphics.getHeight() - mainHeight);
	}

	@Override
	public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
		// super.touchUp(event, x, y, pointer, button);
		isFiring = false;
	}
}
