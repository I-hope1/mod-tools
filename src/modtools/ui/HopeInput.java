package modtools.ui;

import arc.Core;
import arc.backend.android.AndroidInput;
import arc.input.KeyboardDevice;
import arc.struct.IntSet;
import arc.util.*;

public class HopeInput {
	public static IntSet justPressed, pressed;
	public static void load() {
		pressed = Reflect.get(KeyboardDevice.class, Core.input.getKeyboard(), "pressed");
		justPressed = Reflect.get(KeyboardDevice.class, Core.input.getKeyboard(), "justPressed");
	}
	/* static {
		((AndroidInput) Core.input).addKeyListener((view, i, keyEvent) -> {
			Log.info(keyEvent);
			return false;
		});
	} */
}
