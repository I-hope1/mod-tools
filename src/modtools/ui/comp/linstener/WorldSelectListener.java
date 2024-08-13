package modtools.ui.comp.linstener;

import arc.Core;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.*;
import arc.scene.event.*;
import arc.util.*;
import mindustry.graphics.Pal;
import modtools.ui.effect.MyDraw;

import static mindustry.Vars.world;
import static modtools.utils.world.WorldDraw.CAMERA_RECT;

public class WorldSelectListener extends InputListener {
	public final Vec2 start = new Vec2();
	public final Vec2 end   = new Vec2();

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
		Rect  rect = CAMERA_RECT;
		float minX = Mathf.clamp(Math.min(start.x, end.x), rect.x, rect.x + rect.width);
		float minY = Mathf.clamp(Math.min(start.y, end.y), rect.y, rect.y + rect.height);
		float maxX = Mathf.clamp(Math.max(start.x, end.x), rect.x, rect.x + rect.width);
		float maxY = Mathf.clamp(Math.max(start.y, end.y), rect.y, rect.y + rect.height);
		rect = Tmp.r1.set(minX, minY, maxX - minX, maxY - minY);
		Vec2 center = rect.getCenter(Tmp.v1);
		float cx = center.x;
		float cy = center.y;

		Fill.crect(rect.x, rect.y, rect.width, rect.height);

		Lines.stroke(2);
		// x: 0 -> x
		MyDraw.drawText(Strings.autoFixed(rect.width, 1),
		 cx, minY, Pal.accent);
		Lines.line(minX, minY, maxX, minY);
		// y: 0 -> y
		MyDraw.drawText(Strings.autoFixed(rect.height, 1),
		 minX, cy, Pal.accent);
		Lines.line(minX, minY, minX, maxY);
	}
	protected void clampWorld() {
		clampWorld(start, end);
	}
	protected void clampWorld(Vec2 start, Vec2 end) {
		start.x = Mathf.clamp(start.x, 0, world.unitWidth());
		end.x = Mathf.clamp(end.x, 0, world.unitWidth());
		start.y = Mathf.clamp(start.y, 0, world.unitHeight());
		end.y = Mathf.clamp(end.y, 0, world.unitHeight());
	}
}