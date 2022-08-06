
package modtools.ui.components;

import arc.Core;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.ui.layout.Table;
import arc.util.Tmp;

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
		bx = x;
		by = y;
		/*try {
			lastCursor = MyReflect.getValue(Core.graphics, Graphics.class, "lastCursor");
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}*/
		return true;
	}

	public void touchDragged(InputEvent event, float x, float y, int pointer) {
		if (disabled) return;
		if (fire != null) fire.run();
		Vec2 v = main.localToStageCoordinates(Tmp.v1.set(x, y));

		if (v.x != bx || v.y != by) display(-bx + v.x, -by + v.y);
//		Core.graphics.cursor(SystemCursor.crosshair);
	}

	public void display(float x, float y) {
		float mainWidth = main.getWidth(), mainHeight = main.getHeight();
		float touchWidth = touch.getWidth(), touchHeight = touch.getHeight();
		main.setPosition(Mathf.clamp(x, -touchWidth / 3f, Core.graphics.getWidth() - mainWidth / 2f),
				Mathf.clamp(y, -Math.max(mainHeight, touchHeight) / 3f * 2f, Core.graphics.getHeight() - mainHeight));
	}

	@Override
	public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
		super.touchUp(event, x, y, pointer, button);
//		Core.graphics.cursor(lastCursor);
	}
}
