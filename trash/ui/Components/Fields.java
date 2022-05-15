package modmake.ui.components;

import arc.func.Cons;
import arc.func.Prov;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Nullable;
import arc.util.serialization.JsonValue;
import modmake.ui.IntUI;

import java.util.ArrayList;

public class Fields {
	public Prov<Class<?>> type;
	public UncContent map;
	public boolean isArray;
	public int size = 0;
	public Table table;

	public Table colorfulTable(int i, Cons<Table> cons) {
		return new Table(i % 3 == 0 ? IntUI.whiteui.tint(0, 1, 1, .7f)
				: i % 3 == 1 ? IntUI.whiteui.tint(1, 1, 0, .7f)
				: IntUI.whiteui.tint(1, 0, 1, .7f), cons);
	}

	public Table json(int i, String key) {
		return this.colorfulTable(i, table -> {
			table.left().defaults().left();
			// buildContent.build(type.get(), this, table, key, map.get(key));
		});
	}

	public Fields(JsonValue value, Prov<Class<?>> type, Table table) {
		this(value.isArray() ? new UncArray(value) : new UncObject(value), type, table);
	}
	public Fields(Seq<Prov<String>> seq, Prov<Class<?>> type, Table table) {
		this(new UncArray(seq), type, table);
	}

	public Fields(ObjectMap<String, Prov<String>> map, Prov<Class<?>> type, Table table) {
		this(new UncObject(map), type, table);
	}

	public Fields(UncContent map, Prov<Class<?>> type, Table table) {
		if (map == null) throw new NullPointerException("map cannot be null");
		isArray = false;
		if (map instanceof UncArray) {
			isArray = true;
			this.map = map;
		} else if (map instanceof UncObject) this.map = map;

		this.table = table;
		this.type = type;
	}

	public void add(Table table, String key, Prov<String> value) {
		if (value != null && map.has(key)) {
			map.put(key, value);
		}
		Table t = table != null ? table : json(size++, key);
		this.table.add(t).fillX().row();
	}

	public static class IntJsonValue extends JsonValue {

		public IntJsonValue(JsonValue value) {

		}
		public IntJsonValue(ValueType type) {
			super(type);
		}

		/**
		 * @param value May be null.
		 */
		public IntJsonValue(String value) {
			super(value);
		}

		public IntJsonValue(double value) {
			super(value);
		}

		public IntJsonValue(long value) {
			super(value);
		}

		public IntJsonValue(double value, String stringValue) {
			super(value, stringValue);
		}

		public IntJsonValue(long value, String stringValue) {
			super(value, stringValue);
		}

		public IntJsonValue(boolean value) {
			super(value);
		}

		public String toString(){
			StringBuilder b = new StringBuilder();
			forEach(j -> {

			});
			return b.toString();
		}
	}


	public abstract static class UncContent {
		public StringBuffer b = new StringBuffer();

		public boolean has(String key) {return false;}

		public void put(String key, String value) {put(key, () -> value);}
		public void put(String key, Prov<String> value) {}
		public String get(String key) { return getProv(key).get(); }
		public Prov<String> getProv(String key){ return () -> ""; }

		public void clearb() {
			b.delete(0, b.length());
		}
	}

	public static class UncArray extends UncContent {
		public ArrayList<Prov<String>> list = new ArrayList<>();

		@Override
		public boolean has(String id) {
			return list.get(Integer.parseInt(id)) != null;
		}

		@Override
		public void put(String key, Prov<String> value) {
			list.set(Integer.parseInt(key), value);
		}

		@Override
		public Prov<String> getProv(String key) {
			return list.get(Integer.parseInt(key));
		}

		public UncArray(Seq<Prov<String>> seq) {
			seq.each(v -> this.put("" + list.size(), v));
		}

		public UncArray(JsonValue value){
			int i = 0;
			for (JsonValue entry = value.child; entry != null; entry = entry.next)
				put(i++ + "", entry.asString());
		}
		public UncArray(){}

		public String toString() {
			clearb();
			list.forEach(v -> {
				b.append(v.get()).append("\n");
			});
			return "[\n" + b + "\n]";
		}
	}

	public static class UncObject extends UncContent {
		public ObjectMap<String, Prov<String>> map = new ObjectMap<>();
		@Override
		public boolean has(String id) {
			return map.containsKey(id);
		}

		@Override
		public void put(String key, Prov<String> value) {
			map.put(key, value);
		}

		@Override
		public Prov<String> getProv(String key) {
			return map.get(key);
		}

		public UncObject(ObjectMap<String, Prov<String>> map) {
			this.map.putAll(map);
		}
		public UncObject(JsonValue value){
			for (JsonValue entry = value.child; entry != null; entry = entry.next)
				put(entry.name(), entry.asString());
		}
		public UncObject(){}


		public String toString() {
			clearb();
			map.forEach((k, v) -> {
				b.append(k).append(": ").append(v.get());
			});
			return b.toString();
		}
	}
}
