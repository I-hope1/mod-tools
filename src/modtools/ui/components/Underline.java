package modtools.ui.components;

import arc.graphics.Color;
import arc.scene.ui.layout.*;
import modtools.ui.components.limit.LimitImage;

public class Underline extends LimitImage {
	public static Cell<Underline> of(Table table, int colspan) {
		return of(table, colspan, Color.white);
	}
	public static Cell<Underline> of(Table table, int colspan, Color color) {
		Underline underline = new Underline();
		underline.setColor(color);
		Cell<Underline> cell = table.row().add(underline).growX().colspan(colspan);
		table.row();
		return cell;
	}

}
