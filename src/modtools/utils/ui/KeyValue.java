package modtools.utils.ui;

import arc.func.*;
import arc.graphics.Color;
import arc.scene.ui.Label;
import arc.scene.ui.layout.*;
import arc.util.Align;
import modtools.ui.comp.utils.ClearValueLabel;
import modtools.utils.ui.search.BindCell;

public interface KeyValue {
	Color    stressColor = Color.violet;
	KeyValue THE_ONE     = new $KeyValue();
	float    padRight    = 8f;
	float    keyScale    = 0.8f;
	float    valueScale  = 0.7f;

	default void key(Table col, String key) {
		col.add(key).fontScale(keyScale).color(Color.lightGray).left().padRight(padRight);
	}
	default void value(Table col, Label value) {
		col.add(value).fontScale(valueScale).growX().labelAlign(Align.right).row();
	}
	default void value(Table col, Cons<Table> cons) {
		Table table = new Table(cons);
		table.right().defaults().right();
		col.add(table).growX().right().row();
	}
	default void keyValue(Table col, String key, Label value) {
		key(col, key);
		value(col, value);
	}
	default void keyValue(Table col, String key, Cons<Table> cons) {
		key(col, key);
		value(col, cons);
	}
	default void keyValue(Table col, String key, Prov<CharSequence> value) {
		keyValue(col, key, new Label(value));
	}
	default Cons<Table> tableCons(String key, Cons<Table> cons) {
		return touch -> THE_ONE.keyValue(touch, key, cons);
	}
	default Cons<Table> tableCons(String key, Label value) {
		return touch -> THE_ONE.keyValue(touch, key, value);
	}
	default Cons<Table> tableCons(String key, Prov<CharSequence> value) {
		return tableCons(key, new Label(value));
	}
	default Cons<Table> tableCons(String key, Prov<CharSequence> value, Color color) {
		Label label = new Label(value);
		label.setColor(color);
		return tableCons(key, label);
	}
	/* 上面的，需要table的版本 */
	default Cell<Table> label(Table col, String key, Label value) {
		return col.row().table(tableCons(key, value));
	}
	default Cell<Table> label(Table col, String key, Prov<CharSequence> value) {
		return col.row().table(tableCons(key, value));
	}
	default Cell<Table> label(Table col, String key, Prov<CharSequence> value, Color color) {
		return col.row().table(tableCons(key, value, color));
	}
	default <T> Cell<Table> valueLabel(Table col, String key, Prov<T> prov, Class<T> type) {
		ClearValueLabel<T> label = new ClearValueLabel<>(type, prov, null);
		return col.row().table(tableCons(key, label));
	}


	default BindCell makeCell(Table t, Cons<Table> cons) {
		return new BindCell(t.row().table(cons).growX());
	}
}
class $KeyValue implements KeyValue { }
