package modtools.utils.ui;

import arc.func.*;
import arc.graphics.Color;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import modtools.ui.components.input.NoMarkupLabel;
import modtools.utils.ui.search.BindCell;

public interface KeyValue {
	float padRight   = 8f;
	float keyScale   = 0.7f;
	float valueScale = 0.6f;
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
	default Cons<Table> tableCons(String key, Label value) {
		return touch -> keyValue(touch, key, value);
	}
	default Cons<Table> tableCons(String key, Prov<CharSequence> value) {
		return tableCons(key, new Label(value));
	}

	default BindCell makeCell(Table t, Cons<Table> cons) {
		return new BindCell(t.row().table(cons).growX());
	}

	class VLabel extends NoMarkupLabel {
		public VLabel(float scale, Color color) {
			super(scale);
			setColor(color);
		}
		public VLabel(CharSequence text, Color color) {
			super(text);
			setColor(color);
		}
	}
}
