package modtools.ui.components;

import arc.Core;
import arc.Graphics;
import arc.Graphics.Cursor.SystemCursor;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.ui.layout.Scl;
import ihope_lib.MyReflect;

public class SclLisetener extends InputListener {
	public boolean disabled;
	public float offset = 10;
	public Element bind;
	public float defWidth, defHeight, defX, defY, minW, minH;

	public SclLisetener(Element element, float minW, float minH) {
		if (element == null) throw new IllegalArgumentException("element is null");
		bind = element;
		bind.addListener(this);
		set(minW, minH);
	}

	public void set(float minW, float minH) {
		this.minW = Scl.scl(minW);
		this.minH = Scl.scl(minH);
	}

	public boolean left, bottom, right, top;
	public Runnable listener = null;

	public boolean valid() {
		return left || right || bottom || top;
	}

	public Vec2 last = new Vec2();
	public boolean scling = false;
	public SystemCursor lastCursor;

	@Override
	public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
		if (bind.parent == null || disabled) return false;
		last.set(x, y);
		left = Math.abs(x) < offset;
		right = Math.abs(x - bind.getWidth()) < offset;
		bottom = Math.abs(y) < offset;
		top = Math.abs(y - bind.getHeight()) < offset;
		scling = false;
		try {
			lastCursor = MyReflect.getValue(Core.graphics, Graphics.class, "lastCursor");
		} catch (Throwable ignored) {
		}
		//		Log.debug(last);
		//		if (valid()) Log.debug("ok");
		if (valid()) {
			change.set(0, 0);
			defWidth = bind.getWidth();
			defHeight = bind.getHeight();
			defX = bind.x;
			defY = bind.y;
			return true;
		}
		return false;
	}

	public Vec2 change = new Vec2(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);

	@Override
	public void touchDragged(InputEvent event, float x, float y, int pointer) {
		scling = true;
		if (change.x != Float.NEGATIVE_INFINITY) {
			x += change.x;
			change.x = Float.NEGATIVE_INFINITY;
		}
		if (change.y != Float.NEGATIVE_INFINITY) {
			y += change.y;
			change.y = Float.NEGATIVE_INFINITY;
		}
		if (left) {
			Core.graphics.cursor(SystemCursor.horizontalResize);
			float w = Mathf.clamp(defWidth - x - last.x, minW, Core.graphics.getWidth());
			bind.setWidth(w);
			change.x = defWidth - w;
			bind.x = defX + change.x;
			//			Log.debug("defX: @, defW: @, w: @", defX, defWidth, w);
			//			Log.debug("x: @, lx: @", x, last.x);
		}
		if (right) {
			Core.graphics.cursor(SystemCursor.horizontalResize);
			bind.setWidth(Mathf.clamp(defWidth + x - last.x, minW, Core.graphics.getWidth()));
		}
		if (bottom) {
			Core.graphics.cursor(SystemCursor.verticalResize);
			float h = Mathf.clamp(defHeight - y - last.y, minH, Core.graphics.getHeight());
			bind.setHeight(h);
			change.y = defHeight - h;
			bind.y = defY + change.y;
		}
		if (top) {
			Core.graphics.cursor(SystemCursor.verticalResize);
			bind.setHeight(Mathf.clamp(defHeight + y - last.y, minH, Core.graphics.getHeight()));
		}
		if (listener != null) listener.run();
		/*int index = bind.getZIndex();
		Group parent = bind.parent;
		parent.removeChild(bind, false);
		parent.addChildAt(index, bind);*/
	}

	@Override
	public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
		//		Log.info("end");
		super.touchUp(event, x, y, pointer, button);
		change.set(0, 0);
		Core.graphics.cursor(lastCursor);
		scling = false;
		defWidth = defHeight = defX = defY = -1;
	}
}
