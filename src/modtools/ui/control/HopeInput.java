package modtools.ui.control;

import arc.Core;
import arc.input.KeyboardDevice;
import arc.scene.Element;
import arc.struct.*;
import arc.util.*;
import modtools.utils.Tools;

import static modtools.utils.world.TmpVars.mouseVec;

public class HopeInput {
	public static IntSet justPressed, pressed;
	public static IntFloatMap axes;
	static        Element     hit;
	public static Element mouseHit() {
		if (hit == null) hit = Core.scene.hit(mouseVec.x, mouseVec.y, true);
		return hit;
	}

	public static void load() {
		try {
			load0();
		} catch (Throwable e) {
			Log.err("Cannot load input.", e);
			justPressed = pressed = new IntSet();
			axes = new IntFloatMap();
		}
	}
	static void load0() {
		pressed = Reflect.get(KeyboardDevice.class, Core.input.getKeyboard(), "pressed");
		justPressed = Reflect.get(KeyboardDevice.class, Core.input.getKeyboard(), "justPressed");
		axes = Reflect.get(KeyboardDevice.class, Core.input.getKeyboard(), "axes");
		Tools.TASKS.add(() -> hit = null);

		// Vars.control.input.addLock(() -> {
		// 	if (topGroup.isSelecting()) return true;
		// 	Element hit = HopeInput.mouseHit();
		// 	return hit != null && hit.visible
		// 				 && !hit.isDescendantOf(e ->
		// 	 e == Vars.ui.hudGroup || e instanceof IInfo
		// 	 || e instanceof IMenu || e instanceof Window
		// 	 || e instanceof Frag)
		// 	 // &&
		// 	 /* && hit.isDescendantOf(e -> e instanceof IMenu) */;
		// });
	}
	/* static {
		((AndroidInput) Core.input).addKeyListener((view, i, keyEvent) -> {
			Log.info(keyEvent);
			return false;
		});
	} */
}
