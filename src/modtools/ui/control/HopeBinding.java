package modtools.ui.control;

import arc.Core;
import arc.KeyBinds.KeybindValue;
import arc.input.KeyCode;
import mindustry.input.Binding;
import modtools.HopeConstant;

import java.lang.reflect.Array;

import static ihope_lib.MyReflect.unsafe;
import static modtools.HopeConstant.BINDING.*;

public enum HopeBinding {
	close(KeyCode.escape);
	public final KeybindValue keyCode;
	public final String       category;
	public final Binding      binding;
	HopeBinding(KeybindValue keyCode, String category) {
		this.keyCode = keyCode;
		this.category = category;
		Binding[] src = Binding.values();
		binding = HopeConstant.iv(BINDING_CTOR,
		 name(), src.length, keyCode, category);
		Binding[] dest = (Binding[]) Array.newInstance(Binding.class, src.length + 1);
		System.arraycopy(src, 0, dest, 0, src.length);
		dest[src.length] = binding;
		unsafe.putObject(Binding.class, BINDING_VALUES, dest);
	}
	HopeBinding(KeybindValue keyCode) {
		this(keyCode, null);
	}

	public static void load() {
		Core.keybinds.setDefaults(Binding.values());
	}
}