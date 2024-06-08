
package modtools.ui.components.linstener;

import arc.Core;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.ui.layout.Table;

import static modtools.utils.world.TmpVars.mouseVec;

public class MoveListener extends InputListener {
	protected final Element  touch;
	protected final Table    main;
	public          boolean  disabled = false;
	public          boolean  isFiring = false;
	public          Runnable fire;

	public final Vec2 lastMouse = new Vec2();
	public final Vec2 lastMain  = new Vec2();

	public MoveListener(Element touch, Table main) {
		this.touch = touch;
		this.main = main;
		touch.addListener(this);
	}

	public void remove() {
		touch.removeListener(this);
	}

	public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
		if (disabled) return false;

		recordLastPositions();

		// 标记正在进行拖动操作
		isFiring = true;

		return true;
	}

	public void touchDragged(InputEvent event, float x, float y, int pointer) {
		if (disabled) return;

		if (fire != null) fire.run();

		updatePosition();
	}

	public void display(float x, float y) {
		float mainWidth   = main.getWidth();
		float mainHeight  = main.getHeight();
		float touchWidth  = touch.getWidth();
		float touchHeight = touch.getHeight();

		main.setPosition(
		 Mathf.clamp(x, -touchWidth / 3f, Core.graphics.getWidth() - mainWidth / 2f),
		Mathf.clamp(y, -mainHeight + touchHeight, Core.graphics.getHeight() - mainHeight)
		);
	}

	private void recordLastPositions() {
		lastMouse.set(mouseVec);
		lastMain.set(main.x, main.y);
	}

	private void updatePosition() {
		display(lastMain.x + mouseVec.x - lastMouse.x, lastMain.y + mouseVec.y - lastMouse.y);
	}
}
