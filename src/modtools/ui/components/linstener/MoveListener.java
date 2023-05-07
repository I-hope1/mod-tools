
package modtools.ui.components.linstener;

import arc.Core;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.ui.layout.*;

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

	//	public Cursor lastCursor;

	public Vec2 lastMouse = new Vec2(), lastMain = new Vec2();
	/*public Task task = new Task() {
		@Override
		public void run() {
			if (disabled) cancel();
			Vec2 mouse = Core.input.mouse();
			// main.setPosition(-bx + v.x, -by + v.y);
			display(lastMain.x + mouse.x - lastMouse.x, lastMain.y + mouse.y - lastMouse.y);
		}
	};*/

	public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
		if (disabled) return false;
		lastMouse.set(Core.input.mouse());
		lastMain.set(main.x, main.y);
		isFiring = true;
		// bx = x;
		// by = y;
		return true;
	}

	public void touchDragged(InputEvent event, float x, float y, int pointer) {
		if (disabled) return;
		if (fire != null) fire.run();
		// Log.info(event.stageX == x);
		// Vec2 v = main.localToStageCoordinates(Tmp.v1.set(x, y));
		// display(-bx + v.x, -by + v.y);

		Vec2 mouse = Core.input.mouse();
		display(lastMain.x + mouse.x - lastMouse.x, lastMain.y + mouse.y - lastMouse.y);

		// super.touchDragged(event, x, y, pointer);
		//		Core.graphics.cursor(SystemCursor.crosshair);
	}

	public void display(float x, float y) {
		float mainWidth  = main.getWidth(), mainHeight = main.getHeight();
		float touchWidth = touch.getWidth(), touchHeight = touch.getHeight();
		main.x = Mathf.clamp(x, -touchWidth / 3f, Core.graphics.getWidth() - mainWidth / 2f);
		main.y = Mathf.clamp(y, -mainHeight + touchHeight, Core.graphics.getHeight() - mainHeight);
	}

	@Override
	public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
		// task.cancel();
		super.touchUp(event, x, y, pointer, button);
		isFiring = false;
		//		Core.graphics.cursor(lastCursor);
	}
}
