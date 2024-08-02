package modtools.ui.control;

import arc.Core;
import arc.files.Fi;
import arc.func.*;
import arc.input.KeyCode;
import arc.scene.event.*;
import arc.struct.*;
import arc.util.Strings;
import arc.util.serialization.*;
import arc.util.serialization.Jval.JsonMap;
import modtools.IntVars;
import modtools.utils.MySettings.Data;

import java.util.StringJoiner;

public class HKeyCode {
	public static final String   STR_NONE = "None";
	public static final HKeyCode NONE     = new HKeyCode(KeyCode.escape) {
		public boolean isPress() {
			return false;
		}
		public String toString() {
			return STR_NONE;
		}
	};
	public static       Json     json     = new Json();

	public static KeyCodeData data = new KeyCodeData(IntVars.dataDirectory.child("keycode.json"));
	boolean alt, shift, ctrl;
	final KeyCode key;
	public HKeyCode(KeyCode key) {
		this.alt = false;
		this.shift = false;
		this.ctrl = false;
		this.key = key;
	}
	public static boolean isFnKey(KeyCode keycode) {
		return keycode == KeyCode.controlLeft || keycode == KeyCode.controlRight
		       || keycode == KeyCode.shiftLeft || keycode == KeyCode.shiftRight
		       || keycode == KeyCode.altLeft || keycode == KeyCode.altRight;
	}
	public HKeyCode alt() {
		alt = true;
		return this;
	}
	public HKeyCode shift() {
		shift = true;
		return this;
	}
	public HKeyCode ctrl() {
		ctrl = true;
		return this;
	}
	/**
	 *  将一个文本解析，文本可能为:
	 *  [Ctrl + ][Shift + ][Alt + ]Key
	 *  <pre>
	 *    Ctrl + Shift + T
	 *    Ctrl + Alt + P
	 *    Shift + Alt + W
	 *    Alt + C
	 *  </pre>
	 **/
	public static HKeyCode parse(String text) {
		if (text == null || text.equalsIgnoreCase(STR_NONE)) return NONE;
		String[] split = text.split("\\s*\\+\\s*");
		HKeyCode keyCode = new HKeyCode(switch (split[split.length - 1]) {
			case "Ctrl", "Shift", "Alt" -> KeyCode.anyKey;
			default -> KeyCode.valueOf(Strings.camelize(split[split.length - 1]));
		});
		for (String s : split) {
			switch (s) {
				case "Ctrl" -> keyCode.ctrl();
				case "Shift" -> keyCode.shift();
				case "Alt" -> keyCode.alt();
			}
		}
		return keyCode;
	}
	public boolean isPress() {
		return Core.input.keyDown(key)
		       && alt == Core.input.alt()
		       && shift == Core.input.shift()
		       && ctrl == Core.input.ctrl();
	}
	public HKeyCode applyToScene(boolean capture, Runnable r) {
		if (this == NONE) return this;
		(capture ? sceneCaptureKeys : sceneKeys).put(this, r);
		return this;
	}
	private static final OrderedMap<HKeyCode, Runnable> sceneCaptureKeys = new OrderedMap<>();
	private static final OrderedMap<HKeyCode, Runnable> sceneKeys        = new OrderedMap<>();
	static void load() {
		Core.scene.addCaptureListener(new MyInputListener(sceneCaptureKeys));
		Core.scene.addListener(new MyInputListener(sceneKeys));
	}
	public boolean equals(HKeyCode other) {
		return alt == other.alt && shift == other.shift && ctrl == other.ctrl && key == other.key;
	}
	public String toString() {
		StringJoiner sj = new StringJoiner(" + ");
		if (ctrl) sj.add("Ctrl");
		if (shift) sj.add("Shift");
		if (alt) sj.add("Alt");
		if (key != KeyCode.anyKey) sj.add(Strings.capitalize(key.name()));
		return sj.toString();
	}

	public static class KeyCodeData extends Data {
		public KeyCodeData(Fi fi) {
			super(fi);
		}
		private KeyCodeData(Data parent, JsonMap jsonMap) {
			super(parent, jsonMap);
		}
		public KeyCodeData child(String key) {
			return (KeyCodeData) super.child(key);
		}
		public KeyCodeData newChild(Data parent, JsonMap object) {
			return new KeyCodeData(parent, object);
		}
		public HKeyCode keyCode(String key, Prov<HKeyCode> def) {
			if (!containsKey(key)) {
				HKeyCode value = def.get();
				setKeyCode(key, value);
				return value;
			}
			if (get(key) instanceof HKeyCode k) {
				return k;
			}
			try {
				HKeyCode value = parse(getString(key));
				put(key, value);
				return value;
			} catch (Throwable e) {
				return def.get();
			}
		}
		public HKeyCode keyCode(String key) {
			return keyCode(key, () -> NONE);
		}
		public void setKeyCode(String key, HKeyCode keyCode) {
			put(key, keyCode);
		}
		public void eachKey(Cons2<String, HKeyCode> cons) {
			for (var entry : this) {
				Object value = entry.value;
				if (value instanceof Jval) {
					value = keyCode(entry.key);
				}
				if (value instanceof HKeyCode k) cons.get(entry.key, k);
			}
		}
	}
	public static class MyInputListener extends InputListener {
		public final ObjectMap<HKeyCode, Runnable> keys;
		public MyInputListener(ObjectMap<HKeyCode, Runnable> keys) {
			this.keys = keys;
		}
		@Override
		public boolean keyDown(InputEvent event, KeyCode keycode) {
			for (var entry : keys) {
				if (!entry.key.isPress()) continue;
				event.cancel();
				entry.value.run();
				HopeInput.justPressed.clear();
				return false;
			}
			return false;
		}
	}
}
