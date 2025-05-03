package modtools.ui.control;

import arc.Core;
import arc.input.*;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.struct.*;
import arc.util.*;
import modtools.utils.*;

import static modtools.IntVars.*;

public class HopeInput {
	public static IntSet justPressed, pressed;
	public static  IntFloatMap axes;
	private static Element     hit;
	public static Element mouseHit() {
		if (hit == null) hit = Core.scene.hit(mouseVec.x, mouseVec.y, true);
		return hit;
	}
	private static final Vec2 last  = new Vec2();
	private static final Vec2 UNSET = new Vec2(Float.NaN, Float.NaN);
	public static boolean mouseDown() { return Core.input.isTouched(); }
	public static boolean mouseDragged() { return !last.epsilonEquals(mouseVec); }

	public static void load() {
		Tools.TASKS.add(() -> {
			hit = null;
			if (mouseDown()) {
				if (last.equals(UNSET)) last.set(mouseVec);
			} else {
				last.set(UNSET);
			}
		});

		Core.input.getInputMultiplexer().addProcessor(0, new InputProcessor() {
			public boolean mouseMoved(int screenX, int screenY) {
				mouseVec.require();
				return false;
			}
			public boolean touchDown(int screenX, int screenY, int pointer, KeyCode button) {
				mouseVec.require();
				return false;
			}
			public boolean touchDragged(int screenX, int screenY, int pointer) {
				mouseVec.require();
				return false;
			}
			public boolean touchUp(int screenX, int screenY, int pointer, KeyCode button) {
				return false;
			}
			public void connected(InputDevice device) {
			}
			public void disconnected(InputDevice device) {
			}
			public boolean keyDown(KeyCode keycode) {
				return false;
			}
			public boolean keyUp(KeyCode keycode) {
				return false;
			}
			public boolean keyTyped(char character) {
				return false;
			}
			public boolean scrolled(float amountX, float amountY) {
				return false;
			}
		});

		HKeyCode.load();
		try {
			load0();
		} catch (Throwable e) {
			Log.err("Cannot load input.", e);
			justPressed = pressed = new IntSet();
			axes = new IntFloatMap();
		}
	}
	public static void dispose() {
		pressed.clear();
		justPressed.clear();
		axes.clear();
	}
	static void load0() {
		pressed = Reflect.get(KeyboardDevice.class, Core.input.getKeyboard(), "pressed");
		justPressed = Reflect.get(KeyboardDevice.class, Core.input.getKeyboard(), "justPressed");
		axes = Reflect.get(KeyboardDevice.class, Core.input.getKeyboard(), "axes");
	}
}
