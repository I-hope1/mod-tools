package modtools.utils;

import arc.files.Fi;
import arc.struct.OrderedMap;
import arc.util.*;
import arc.util.serialization.Jval;
import arc.util.serialization.Jval.JsonMap;
import mindustry.Vars;
import modtools.IntVars;
import modtools.utils.ui.FormatHelper;
import rhino.ScriptRuntime;

import java.util.*;

public class MySettings {
	private static final Fi dataDirectory = IntVars.dataDirectory;

	static {
		Tools.runIgnoredException(() -> {
			Fi fi = Vars.dataDirectory.child("mods(I hope...)");
			if (fi.exists() && fi.isDirectory()) {
				fi.copyFilesTo(dataDirectory);
				fi.deleteDirectory();
			}
		});
	}

	static Fi config = dataDirectory.child("mod-tools-config.hjson");

	public static final Data
	 SETTINGS         = new Data(config),
	 D_JSFUNC         = SETTINGS.child("JSFunc");

	public static class Data extends OrderedMap<String, Object> {
		public Data parent;

		public Data(Data parent, JsonMap jsonMap) {
			this.parent = parent;
			loadJval(jsonMap);
		}
		public Data(Fi fi) {
			loadFi(fi);
		}

		public Data child(String key) {
			return (Data) get(key, () -> new Data(this, new JsonMap()));
		}

		/** auto invoke {@link String#valueOf(Object)} */
		public Object putString(String key, Object value) {
			return put(key, String.valueOf(value));
		}

		public Object put(String key, Object value) {
			if (value instanceof Float f) value = FormatHelper.fixed(f);
			Object old = super.put(key, value);
			if (!Objects.equals(old, value)) {
				write();
			}
			return old;
		}
		public Object remove(String key) {
			Object o = super.remove(key);
			write();
			return o;
		}
		public Runnable task = () -> {
			if (parent == null) {
				config.writeString("" + this);
			} else parent.write();
		};
		public void write() {
			TaskManager.acquireTask(12, task);
		}

		public Object get(String key, Object defaultValue) {
			return super.get(key, () -> defaultValue);
		}

		public void loadFi(Fi fi) {
			if (!fi.exists()) {
				fi.writeString("");
				return;
			}
			Fi bak = fi.sibling("mod-tools-config.bak");
			try {
				loadJval(Jval.read(fi.readString()).asObject());
				fi.copyTo(bak);
			} catch (Exception e) {
				Log.err(e);
				bak.copyTo(fi);
			}
		}
		public void loadJval(JsonMap jsonMap) {
			for (var entry : jsonMap) {
				super.put(entry.key, entry.value.isObject() ? new Data(this, entry.value.asObject()) :
				 entry.value.isBoolean() ? entry.value.asBool() : entry.value);
			}
		}

		public boolean toBool(Object v) {
			if (v instanceof Boolean) return (Boolean) v;
			if (v instanceof Jval) {
				if (((Jval) v).isBoolean()) return ((Jval) v).asBool();
				return v.toString().equals("true");
			}
			if (v == null) return false;
			return ScriptRuntime.toBoolean("" + v);
		}
		public boolean getBool(String name) {
			return getBool(name, false);
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
				builder.append(tab).append('"')
				 .append(k.replaceAll("\"", "\\\\\""))
				 .append('"').append(": ")
				 .append(v instanceof Data ? ((Data) v).toString(tab) :
					(Reflect.isWrapper(v.getClass()) ? v :
					 STR."\"\{v.toString().replaceAll("\\\\", "\\\\")}\""))
				 .append('\n');
			});
			builder.deleteCharAt(builder.length() - 1);
			tab.deleteCharAt(tab.length() - 1);
			builder.append('\n').append(tab).append('}');
			return builder.toString();
		}

		public float getFloat(String name) {
			return getFloat(name, 0);
		}

		public float getFloat(String name, float def) {
			Object v = get(name, def);
			if (v instanceof Float f) return f;
			if (v instanceof Jval jval) {
				if (jval.isNumber()) return jval.asFloat();
				v = v.toString();
			}
			return Strings.parseFloat("" + v, def);
		}
		public int getInt(String name, int def) {
			Object v = get(name, def);
			if (v instanceof Integer i) return i;
			if (v instanceof Jval jval) {
				if (jval.isNumber()) return jval.asInt();
				v = v.toString();
			}
			return Strings.parseInt("" + v, def);
		}
		/* for color */
		public int get0xInt(String name, int def) {
			Object v = get(name, Integer.toHexString(def));
			if (v instanceof Integer) return (int) v;
			if (v instanceof Jval) {
				if (((Jval) v).isNumber()) return (int) ((Jval) v).asLong();
				v = v.toString();
			}
			if (v == null) return 0;
			try {
				return (int) Long.parseLong("" + v, 16);
			} catch (Throwable err) {
				Log.err(err);
				return def;
			}
		}

		public String getString(String name, String def) {
			Object o = get(name, def);
			return o instanceof Jval ? ((Jval) o).asString() : String.valueOf(o);
		}

		public String getString(String name) {
			Object o = get(name);
			return o instanceof Jval ? ((Jval) o).asString() : String.valueOf(o);
		}
	}
}
