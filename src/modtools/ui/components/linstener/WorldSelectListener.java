package modtools.ui.components.linstener;

import arc.Core;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.event.*;
import arc.util.Strings;
import mindustry.graphics.Pal;
import modtools.ui.effect.MyDraw;

import static mindustry.Vars.world;
import static modtools.utils.world.WorldDraw.CAMERA_RECT;

public class WorldSelectListener extends InputListener {
	protected final Vec2 start = new Vec2();
	protected final Vec2 end   = new Vec2();

	public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
		acquireWorldPos(x, y);
		return true;
	}
	public void touchDragged(InputEvent event, float x, float y, int pointer) {
		acquireWorldPos(x, y);
	}
	/**
	 * 获取世界坐标
	 * @param x 视口中的横向坐标
	 * @param y 视口中的纵向坐标
	 */
	protected void acquireWorldPos(float x, float y) {
		end.set(Core.camera.unproject(x, y));
	}
	public void touchUp(InputEvent event, float mx, float my, int pointer, KeyCode button) {
		acquireWorldPos(mx, my);

		/* 交换两个数 */
		if (start.x > end.x) {
			start.x = end.x + (end.x = start.x) * 0;
		}
		if (start.y > end.y) {
			start.y = end.y + (end.y = start.y) * 0;
		}
	}
	public void draw() {
		float minX = Mathf.clamp(Math.min(start.x, end.x), CAMERA_RECT.x, CAMERA_RECT.x + CAMERA_RECT.width);
		float minY = Mathf.clamp(Math.min(start.y, end.y), CAMERA_RECT.y, CAMERA_RECT.y + CAMERA_RECT.height);
		float maxX = Mathf.clamp(Math.max(start.x, end.x), CAMERA_RECT.x, CAMERA_RECT.x + CAMERA_RECT.width);
		float maxY = Mathf.clamp(Math.max(start.y, end.y), CAMERA_RECT.y, CAMERA_RECT.y + CAMERA_RECT.height);

		Fill.crect(minX, minY, maxX - minX, maxY - minY);

		Lines.stroke(2);
		// x: 0 -> x
		float width = Math.abs(maxX - minX);
		MyDraw.drawText(Strings.autoFixed(width, 1),
		 minX + width / 2f, minY, Pal.accent);
		Lines.line(minX, minY, maxX, minY);
		// y: 0 -> y
		float height = Math.abs(maxY - minY);
		MyDraw.drawText(Strings.autoFixed(height, 1),
		 minX, minY + height / 2f, Pal.accent);
		Lines.line(minX, minY, minX, maxY);
	}
	protected void clampWorld() {
		start.x = Mathf.clamp(start.x, 0, world.unitWidth());
		end.x = Mathf.clamp(end.x, 0, world.unitWidth());
		start.y = Mathf.clamp(start.y, 0, world.unitHeight());
		end.y = Mathf.clamp(end.y, 0, world.unitHeight());
	}
}
