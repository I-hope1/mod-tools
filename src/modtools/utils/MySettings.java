package modtools.utils;

import arc.files.Fi;
import arc.struct.StringMap;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;

import java.util.Objects;

public class MySettings {
	static Fi dataDirectory = Vars.dataDirectory.child("mods(I hope...)");
	static Fi config = dataDirectory.child("mod-tools-config.hjson");
	public static final Data settings = new Data();
	static {
		settings.loadFi(config);
	}

	public static class Data extends StringMap {
		public String put(String key, Object value) {
			return put(key, value.toString());
		}

		@Override
		public String put(String key, String value) {
			String old = super.put(key, value);
			if (!Objects.equals(old, value)) config.writeString("" + this);
			return old;
		}

		@Override
		public String get(String key, String defaultValue) {
			return get(key, () -> defaultValue);
		}

		public void loadFi(Fi fi) {
			if (!fi.exists()) {
				fi.writeString("");
				return;
			}
			try {
				for (var entry : Jval.read(fi.readString()).asObject()) {
					super.put(entry.key, "" + entry.value);
				}
			} catch (Exception e) {
				Log.err(e);
			}
		}

		public boolean getBool(String name, String def) {
			return get(name, def).equals("true");
		}

		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder();
			each((k, v) -> {
				builder.append(k).append(": ").append("\"").append(v).append("\"").append("\n");
			});
			return builder.toString();
		}
	}
}
