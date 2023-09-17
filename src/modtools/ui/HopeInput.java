package modtools.ui;

import android.view.KeyEvent;
import arc.Core;
import arc.backend.android.AndroidInput;
import arc.input.KeyboardDevice;
import arc.struct.IntSet;
import arc.util.*;

public class HopeInput {
	public static IntSet justPressed;
	public static void load() {
		justPressed = Reflect.get(KeyboardDevice.class, Core.input.getKeyboard(), "justPressed");
	}
	static {
		/* ((AndroidInput) Core.input).addKeyListener((view, i, keyEvent) -> {
			Log.info(keyEvent.getAction() == KeyEvent.ACTION_DOWN);
			return false;
		}); */
	}
}
