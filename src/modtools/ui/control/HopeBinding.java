package modtools.ui.control;

import arc.Core;
import arc.KeyBinds.KeybindValue;
import arc.input.KeyCode;
import mindustry.input.Binding;
import modtools.Constants;
import modtools.jsfunc.reflect.UNSAFE;

import java.lang.reflect.Array;

import static modtools.Constants.BINDING.*;

public enum HopeBinding {
	close(KeyCode.escape),
	view(KeyCode.v);

	public final KeybindValue keyCode;
	public final String       category;
	public final Binding      binding;
	HopeBinding(KeybindValue keyCode, String category) {
		this.keyCode = keyCode;
		this.category = category;
		Binding[] src = Binding.values();
		binding = Constants.iv(BINDING_CTOR,
		 name(), src.length, keyCode, category);
		Binding[] dest = (Binding[]) Array.newInstance(Binding.class, src.length + 1);
		System.arraycopy(src, 0, dest, 0, src.length);
		dest[src.length] = binding;
		UNSAFE.putObject(Binding.class, BINDING_VALUES, dest);
	}
	HopeBinding(KeybindValue keyCode) {
		this(keyCode, null);
	}

	public static void load() {
		Core.keybinds.setDefaults(Binding.values());
	}

	/* static KeybindValue pair(KeyCode keyCode, boolean isShift, boolean isCtrl, boolean isAlt)  {
		new KeybindValue(){
			keyCode
		}, isShift, isCtrl, isAlt);
	} */
}
