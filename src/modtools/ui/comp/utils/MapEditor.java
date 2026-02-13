package modtools.ui.comp.utils;

import arc.func.*;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.util.serialization.*;
import mindustry.gen.*;
import modtools.ui.comp.*;
import modtools.utils.search.*;

import java.lang.reflect.*;
import java.util.regex.Pattern;

public class MapEditor<K, V> extends Window {
	public FilterTable<K>     table;
	public JsonValue          jsonData;
	public Class<K>           keyClass;
	public Class<V>           valueClass;
	public Func<JsonValue, K> keyFunc;
	public Func<JsonValue, V> valueFunc;

	public MapEditor(String title, JsonValue json,
	                 Class<K> keyClass, Class<V> valueClass,
	                 Func<JsonValue, K> keyFunc, Func<JsonValue, V> valueFunc) {
		super(title);

		this.jsonData = json;
		this.keyClass = keyClass;
		this.valueClass = valueClass;
		this.keyFunc = keyFunc;
		this.valueFunc = valueFunc;

		try {
			build();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		show();
	}

	static ObjectMap<Class<?>, FieldBuilder> classBuilders = new ObjectMap<>();
	interface FieldBuilder {
		void build(Table table, Field field, JsonValue value) throws Exception;
	}

	static {
		initClassBuilders();
	}
	static void initClassBuilders() {
		classBuilders.put(String.class, (table, field, value) -> {
			ModifiableLabel.build(value::asString, null, (f, _) -> value.set(f.getText()), table);
		});
		classBuilders.put(ObjectMap.class, (table, field, value) -> {
			if (field.getGenericType() instanceof ParameterizedType type) {
				Class<?> keyClass   = (Class<?>) type.getActualTypeArguments()[0];
				Class<?> valueClass = (Class<?>) type.getActualTypeArguments()[1];
				classBuilders.get(keyClass).build(table, null, value);
				classBuilders.get(valueClass).build(table, null, value);
			}
		});
		classBuilders.put(Object.class, (table, field, value) -> {
			if (field.getType().isInterface()) {
				// makeInterface(table, value);
			}
		});
		// fieldBuilders.put(Seq.class, (table, field, value) -> {
		//
		// });
	}

	Pattern pattern;
	public void build() throws Exception {
		table = new FilterTable<>();
		Cons<JsonValue> cons = entry -> {
			table.bind(keyFunc.get(entry));
			table.table(Tex.pane, t -> {
				t.add("Key: ");
				build(keyClass, t, entry.get("key"));
				t.add("Value: ").padLeft(6f);
				build(valueClass, t, entry.get("value"));
			}).growX().row();
		};
		for (JsonValue entry = jsonData.child; entry != null; entry = entry.next) {
			cons.get(entry);
		}

		new Search<>((_, pattern) -> this.pattern = pattern)
		 .build(cont, null);
		cont.add(table).grow().row();
		cont.button("@add", Icon.add, () -> {
			JsonValue xxx = new JsonValue("xxx");
			jsonData.addChild(Integer.toHexString((int) (Math.random() * Integer.MAX_VALUE)), xxx);
			cons.get(xxx);
		}).growX().maxWidth(220);
	}
	private void build(Class<?> clazz, Table t, JsonValue entry) {
		try {
			classBuilders.get(clazz).build(t, null, entry);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
