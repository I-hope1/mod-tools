package modtools.ui.control;

import arc.Core;
import arc.files.Fi;
import arc.func.*;
import arc.input.KeyCode;
import arc.scene.event.*;
import arc.struct.*;
import arc.util.Strings;
import arc.util.serialization.Jval;
import arc.util.serialization.Jval.JsonMap;
import modtools.IntVars;
import modtools.utils.MySettings.Data;

import java.util.*;

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
	 * 将一个文本解析，文本可能为:
	 * [Ctrl + ][Shift + ][Alt + ]Key
	 * <pre>
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
		if (r == null) throw new NullPointerException("r is null");
		var map = capture ? sceneCaptureKeys : sceneKeys;
		if (map.containsKey(this)) throw new IllegalArgumentException("Key already registered: " + this);
		map.put(this, r);
		return this;
	}
	private static final OrderedMap<HKeyCode, Runnable> sceneCaptureKeys = new OrderedMap<>();
	private static final OrderedMap<HKeyCode, Runnable> sceneKeys        = new OrderedMap<>();
	static void load() {
		Core.scene.addCaptureListener(new MyInputListener(sceneCaptureKeys));
		Core.scene.addListener(new MyInputListener(sceneKeys));
	}

	public String toString() {
		StringJoiner sj = new StringJoiner(" + ");
		if (ctrl) sj.add("Ctrl");
		if (shift) sj.add("Shift");
		if (alt) sj.add("Alt");
		if (key != KeyCode.anyKey) sj.add(Strings.capitalize(key.name()));
		return sj.toString();
	}

	public boolean equals(Object object) {
		if (this == object) return true;
		if (!(object instanceof HKeyCode keyCode)) return false;
		return alt == keyCode.alt && shift == keyCode.shift && ctrl == keyCode.ctrl && key == keyCode.key;
	}
	public int hashCode() {
		return Objects.hash(alt, shift, ctrl, key);
	}
	public static void eachAllKey(Cons2<String, HKeyCode> cons) {
		eachAllKey(data, null, cons);
	}
	private static void eachAllKey(KeyCodeData data, String prefix, Cons2<String, HKeyCode> cons) {
		data.each((key, value) -> {
			String t = prefix == null ? key : prefix + "." + key;
			if (value instanceof KeyCodeData d) {
				eachAllKey(d, t, cons);
			} else {
				cons.get(t, data.keyCode(key));
			}
		});
	}

	private static class DynamicHKeyCode extends HKeyCode {
		final KeyCodeData    data;
		final String         key;
		final Prov<HKeyCode> def;

		public DynamicHKeyCode(KeyCodeData data, String key) {
			this(data, key, () -> NONE);
		}
		public DynamicHKeyCode(KeyCodeData data, String key, Prov<HKeyCode> def) {
			super(KeyCode.anyKey);
			this.data = data;
			this.key = key;
			this.def = def;
		}
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof DynamicHKeyCode that)) return false;
			return Objects.equals(data, that.data) && Objects.equals(key, that.key);
		}
		public int hashCode() {
			return Objects.hash(data.name, key);
		}
		public boolean isPress() {
			return data.keyCode(key, def).isPress();
		}
		public String toString() {
			return data.keyCode(key, def).toString();
		}
		public HKeyCode alt() {
			throw new UnsupportedOperationException();
		}
		public HKeyCode shift() {
			throw new UnsupportedOperationException();
		}
		public HKeyCode ctrl() {
			throw new UnsupportedOperationException();
		}
	}

	public static class KeyCodeData extends Data {
		public final String name;
		public KeyCodeData(Fi fi) {
			super(fi);
			name = "ROOT";
		}
		private KeyCodeData(Data parent, String key, JsonMap jsonMap) {
			super(parent, jsonMap);
			name = key;
		}
		public KeyCodeData child(String key) {
			return (KeyCodeData) super.child(key);
		}
		public KeyCodeData newChild(String key, JsonMap object) {
			return new KeyCodeData(this, key, object);
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
		public HKeyCode dynamicKeyCode(String key, Prov<HKeyCode> def) {
			return new DynamicHKeyCode(this, key, def);
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
				Runnable runnable = entry.value;
				runnable.run();
				HopeInput.justPressed.clear();
				return false;
			}
			return false;
		}
	}
}
