package modtools.utils.ui;

import arc.func.*;
import arc.graphics.Color;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import modtools.utils.ui.search.BindCell;

public interface KeyValue {
	KeyValue THE_ONE    = new $KeyValue();
	float    padRight   = 8f;
	float    keyScale   = 0.7f;
	float    valueScale = 0.6f;
	default void key(Table col, String key) {
		col.add(key).fontScale(keyScale).color(Color.lightGray).growX().padRight(padRight);
	}
	default void value(Table col, Label value) {
		col.add(value).fontScale(valueScale).right().row();
	}
	default void keyValue(Table col, String key, Label value) {
		key(col, key);
		value(col, value);
	}
	default void keyValue(Table col, String key, Prov<CharSequence> value) {
		keyValue(col, key, new Label(value));
	}
	default Cons<Table> tableCons(String key, Label value) {
		return touch -> THE_ONE.keyValue(touch, key, value);
	}
	default Cons<Table> tableCons(String key, Prov<CharSequence> value) {
		return tableCons(key, new Label(value));
	}
	default BindCell makeCell(Table t, Cons<Table> cons) {
		return new BindCell(t.row().table(cons).growX());
	}
	class $KeyValue implements KeyValue { }
}
