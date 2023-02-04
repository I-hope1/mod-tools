package modtools.utils;

import arc.files.Fi;
import arc.struct.*;
import arc.util.Log;
import arc.util.serialization.Jval;
import arc.util.serialization.Jval.JsonMap;
import mindustry.Vars;
import rhino.ScriptRuntime;

import java.util.Objects;

public class MySettings {
	public static final Fi dataDirectory = Vars.dataDirectory.child("b0kkihope");

	static {
		try {
			Fi fi = Vars.dataDirectory.child("mods(I hope...)");
			if (fi.exists() && fi.isDirectory()) {
				fi.copyFilesTo(dataDirectory);
				fi.deleteDirectory();
			}
		} catch (Throwable ignored) {}
	}

	static              Fi   config   = dataDirectory.child("mod-tools-config.hjson");
	public static final Data settings = new Data(config);

	public static class Data extends OrderedMap<String, Object> {
		public Data parent;

		public Data(Data parent, JsonMap jsonMap) {
			this.parent = parent;
			loadJval(parent, jsonMap);
		}
		public Data(Fi fi) {
			loadFi(fi);
		}

		public Object put(String key, Object value) {
			Object old = super.put(key, value);
			if (!Objects.equals(old, value)) {
				write();
			}
			return old;
		}
		public void write() {
			if (parent == null) config.writeString("" + this);
			else parent.write();
		}

		public Object get(String key, Object defaultValue) {
			return get(key, () -> defaultValue);
		}

		public void loadFi(Fi fi) {
			if (!fi.exists()) {
				fi.writeString("");
				return;
			}
			try {
				loadJval(null, Jval.read(fi.readString()).asObject());
			} catch (Exception e) {
				Log.err(e);
			}
		}
		public void loadJval(Data parent, JsonMap jsonMap) {
			for (var entry : jsonMap) {
				super.put(entry.key, entry.value.isObject() ? new Data(parent, entry.value.asObject()) : entry.value);
			}
		}

		public boolean toBool(Object v) {
			if (v instanceof Jval) {
				if (((Jval) v).isBoolean()) return ((Jval) v).asBool();
				else return v.toString().equals("true");
			}
			if (v instanceof Boolean) return (boolean) v;
			if (v == null) return false;
			return ScriptRuntime.toBoolean("" + v);
		}
		public boolean getBool(String name) {
			return toBool(get(name));
		}
		public boolean getBool(String name, Object def) {
			return toBool(get(name, def));
		}

		public String toString() {
			return toString(new StringBuilder());
		}
		public String toString(StringBuilder tab) {
			StringBuilder builder = new StringBuilder();
			builder.append("{\n");
			tab.append("	");
			each((k, v) -> {
				builder.append(tab).append(k).append(": ")
						.append(v instanceof Data ? ((Data) v).toString(tab) : v)
						.append("\n");
			});
			tab.deleteCharAt(tab.length() - 1);
			builder.append('\n').append(tab).append('}');
			return builder.toString();
		}

		public float getFloat(String name, float def) {
			Object v = get(name, def);
			if (v instanceof Jval) {
				if (((Jval) v).isNumber()) return ((Jval) v).asFloat();
				v = v.toString();
			}
			return Float.parseFloat("" + v);
		}
		public int getInt(String name, int def) {
			Object v = get(name, def);
			if (v instanceof Jval) {
				if (((Jval) v).isNumber()) return ((Jval) v).asInt();
				v = v.toString();
			}
			return Integer.parseInt("" + v);
		}
	}
}
