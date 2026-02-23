package modtools.utils;

import arc.files.Fi;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.Jval;
import arc.util.serialization.Jval.*;
import modtools.IntVars;
import modtools.events.MyEvents;
import modtools.jsfunc.type.CAST;
import rhino.ScriptRuntime;

import java.util.Objects;

public class MySettings {
	private static final Fi dataDirectory = IntVars.dataDirectory;

	static Fi config = dataDirectory.child("mod-tools-config.hjson");

	public static final Data
	 SETTINGS = new Data(config),
	 D_JSFUNC = SETTINGS.child("JSFunc");

	public static void load() {

	}

	public static class Data extends OrderedMap<String, Object> {
		public MyEvents                  events   = new MyEvents();
		public ObjectMap<String, Object> defaults = new ObjectMap<>();
		public Data                      parent;
		public Fi                        fi;

		public Data(Data parent, JsonMap jsonMap) {
			this.parent = parent;
			loadJval(jsonMap);
		}
		public Data(Fi fi) {
			this.fi = fi;
			loadFi(fi);
		}

		public Data child(String key) {
			return (Data) get(key, () -> newChild(key, new JsonMap()));
		}

		/** auto invoke {@link String#valueOf(Object)} */
		public Object putString(String key, Object value) {
			return put(key, String.valueOf(value));
		}

		public Object put(String key, Object value) {
			Object old = super.put(key, value);
			/* 以下情况不write(), [it=value.getClass()]
			(it.isPrimitive() || it == String) -> equals(old, value) */
			if (old == null && value == null) return old;

			Class<?> it = value == null ? old.getClass() : value.getClass();
			if (CAST.unbox(it).isPrimitive() || it == String.class) {
				if (Objects.equals(old, value)) {
					return old;
				}
			}
			fireChanged(key);
			return old;
		}
		public Object remove(String key) {
			Object o = super.remove(key);
			fireChanged(key);
			return o;
		}
		public void fireChanged(String key) {
			events.fireIns(key);
			write();
		}

		public Runnable task = () -> {
			if (parent == null && fi != null) {
				fi.writeString(toString());
			} else if (parent != null) {
				parent.write();
			}
		};
		public void write() {
			TaskManager.scheduleOrReset(0.8f, task);
		}

		public void setDef(String key, Object value) {
			defaults.put(key, value);
			if (!containsKey(key)) put(key, defaults.get(key));
		}

		public Object getDef(String name) {
			return defaults.get(name);
		}
		public Object get(String key, Object defaultValue) {
			if (containsKey(key)) return get(key);
			put(key, defaultValue);
			return defaultValue;
		}

		public void loadFi(Fi fi) {
			if (!fi.exists()) {
				fi.writeString("");
				return;
			}
			Fi bak = fi.sibling(fi.nameWithoutExtension() + ".bak");
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
				super.put(entry.key, entry.value.isObject() ? newChild(entry.key, entry.value.asObject()) :
				 entry.value.isBoolean() ? entry.value.asBool() : entry.value);
			}
		}

		public Data newChild(String key, JsonMap object) {
			return new Data(this, object);
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

		/** 将 Data 及其所有值递归转为 Jval */
		private static Jval toJval(Object v) {
			return switch (v) {
				case Data data -> {
					Jval obj = Jval.newObject();
					data.each((k, val) -> obj.add(k, toJval(val)));
					yield obj;
				}
				case String[] arr -> {
					Jval array = Jval.newArray();
					for (String s : arr) array.add(s);
					yield array;
				}
				case Seq<?> seq -> {
					Jval array = Jval.newArray();
					seq.each(item -> array.add(toJval(item)));
					yield array;
				}
				case Jval jval -> jval;
				case Boolean b -> Jval.valueOf(b);
				case Integer i -> Jval.valueOf((long) i);
				case Long l -> Jval.valueOf(l);
				case Float f -> Jval.valueOf(f);
				case Double d -> Jval.valueOf(d);
				case null -> Jval.NULL;
				default -> Jval.valueOf(v.toString());
			};
		}

		public String toJsonString() {
			return toJval(this).toString(Jformat.plain);
		}
		@Override
		public String toString() {
			return toJval(this).toString(Jformat.hjson);
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
		public JsonArray getArray(String name) {
			Object o = get(name, Jval::newArray);
			return switch (o) {
				case Jval jval when jval.isArray() -> jval.asArray();
				case JsonArray array -> array;
				default -> null;
			};
		}
		public void removeArrayIndex(String name, int index) {
			JsonArray array = getArray(name);
			if (array == null) return;
			array.remove(index);
			fireChanged(name);
		}
		public void onChange(String key, Runnable run) {
			events.onIns(key, run);
		}
	}
}
