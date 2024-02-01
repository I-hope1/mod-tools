package modtools.ui.components;

import arc.scene.ui.layout.*;
import modtools.ui.components.limit.LimitImage;

public class Underline extends LimitImage {
	public static Cell<Underline> of(Table table, int colspan) {
		return table.add(new Underline()).growX().colspan(colspan);
	}
}
