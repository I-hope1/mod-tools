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
	float bx, by;
	private final Table main;

	public MoveListener(Element touch, Table main) {
		this.main = main;
		touch.addListener(this);
	}

	public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
		bx = x;
		by = y;
		return true;
	}

	public void touchDragged(InputEvent event, float x, float y, int pointer) {
		Vec2 v = main.localToStageCoordinates(Tmp.v1.set(x, y));

		main.setPosition(
				Mathf.clamp(-bx + v.x, 0f, Core.graphics.getWidth() - main.getPrefWidth()),
				Mathf.clamp(-by + v.y, 0f, Core.graphics.getHeight() - main.getPrefHeight()));
		// Log.info(-by + v.y + ", 0, " + (Core.graphics.getHeight() - main.getPrefHeight()));
	}
}
