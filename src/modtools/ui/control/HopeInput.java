package modtools.ui.control;

import arc.Core;
import arc.input.KeyboardDevice;
import arc.scene.Element;
import arc.struct.IntSet;
import arc.util.*;
import mindustry.Vars;
import modtools.ui.IntUI.IMenu;
import modtools.ui.components.Window;
import modtools.ui.components.Window.IInfo;
import modtools.utils.Tools;

import static modtools.ui.IntUI.topGroup;

public class HopeInput {
	public static IntSet justPressed, pressed;
	static Element hit;
	public static Element mouseHit() {
		if (hit == null) hit = Core.scene.hit(Core.input.mouseX(), Core.input.mouseY(), true);
		return hit;
	}

	public static void load() {
		try {
			load0();
		} catch (Throwable e) {
			Log.err("Cannot load input.", e);
			justPressed = pressed = new IntSet();
		}
	}
	static void load0() {
		pressed = Reflect.get(KeyboardDevice.class, Core.input.getKeyboard(), "pressed");
		justPressed = Reflect.get(KeyboardDevice.class, Core.input.getKeyboard(), "justPressed");
		Tools.TASKS.add(() -> hit = null);

		Vars.control.input.addLock(() -> {
			if (topGroup.isSelecting()) return true;
			Element hit = HopeInput.mouseHit();
			return hit != null && hit.visible
						 && !hit.isDescendantOf(Vars.ui.hudGroup)
						 && !hit.isDescendantOf(e -> e instanceof IInfo)
						 && !hit.isDescendantOf(e -> e instanceof IMenu)
						 && !hit.isDescendantOf(e -> e instanceof Window)
			 // &&
			 /* && hit.isDescendantOf(e -> e instanceof IMenu) */;
		});
	}
	/* static {
		((AndroidInput) Core.input).addKeyListener((view, i, keyEvent) -> {
			Log.info(keyEvent);
			return false;
		});
	} */
}
