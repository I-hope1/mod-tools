package modtools.ui.comp;

import arc.graphics.Color;
import arc.scene.ui.layout.*;
import arc.util.pooling.*;
import modtools.ui.comp.Window.IDisposable;
import modtools.ui.comp.limit.LimitImage;

public class Underline extends LimitImage {
	public static Cell<Underline> of(Table table, int colspan) {
		return of(table, colspan, Color.white);
	}
	/** @see IDisposable#clearAll()   */
	public static final Pool<Underline> pool = Pools.get(Underline.class, Underline::new);
	public static Cell<Underline> of(Table table, int colspan, Color color) {
		Underline underline = pool.obtain();
		underline.setColor(color);
		table.row();
		Cell<Underline> cell = table.add(underline).growX().colspan(colspan);
		table.row();
		return cell;
	}
	public float getHeight() {
		return 1 * Scl.scl();
	}
}
