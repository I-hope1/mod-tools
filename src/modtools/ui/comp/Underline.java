package modtools.ui.comp;

import arc.graphics.Color;
import arc.scene.ui.layout.*;
import modtools.ui.comp.limit.LimitImage;

public class Underline extends LimitImage {
	public static Cell<Underline> of(Table table, int colspan) {
		return of(table, colspan, Color.white);
	}
	public static Cell<Underline> of(Table table, int colspan, Color color) {
		Underline underline = new Underline();
		underline.setColor(color);
		table.row();
		Cell<Underline> cell = table.add(underline).growX().colspan(colspan);
		table.row();
		return cell;
	}
	public float getHeight() {
		return 1;
	}
}
