
package modtools.ui.components;

import arc.Core;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.ui.layout.Table;
import arc.util.*;

public class MoveListener extends InputListener {
	public float bx, by;
	public final Table main;
	public final Element touch;
	public boolean disabled = false;
	public Runnable fire;

	public MoveListener(Element touch, Table main) {
		this.main = main;
		this.touch = touch;
		touch.addListener(this);
	}
	//	public Cursor lastCursor;

	public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
		if (disabled) return false;
		bx = x;
		by = y;
		return true;
	}

	public void touchDragged(InputEvent event, float x, float y, int pointer) {
		if (fire != null) fire.run();
		// Log.info(event.stageX == x);
		// Vec2 v =
		Vec2 v = main.localToStageCoordinates(Tmp.v1.set(x, y));
		if (v.x != bx || v.y != by) display(-bx + v.x, -by + v.y);

		// super.touchDragged(event, x, y, pointer);
		//		Core.graphics.cursor(SystemCursor.crosshair);
	}

	public void display(float x, float y) {
		float mainWidth = main.getWidth(), mainHeight = main.getHeight();
		float touchWidth = touch.getWidth(), touchHeight = touch.getHeight();
		main.x = Mathf.clamp(x, -touchWidth / 3f, Core.graphics.getWidth() - mainWidth / 2f);
		main.y = Mathf.clamp(y, -mainHeight + touchHeight, Core.graphics.getHeight() - mainHeight);
	}

	@Override
	public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
		super.touchUp(event, x, y, pointer, button);
		//		Core.graphics.cursor(lastCursor);
	}
}
