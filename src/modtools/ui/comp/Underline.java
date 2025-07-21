package modtools.ui.comp;

import arc.graphics.Color;
import arc.scene.ui.layout.*;
import arc.util.*;
import arc.util.pooling.*;
import arc.util.pooling.Pool.Poolable;
import modtools.ui.comp.Window.IDisposable;
import modtools.ui.comp.limit.LimitImage;
import modtools.utils.JSFunc.JColor;

public class Underline extends LimitImage implements Poolable {
	public static final boolean DEBUG_ID = false;
	public String toString() {
		return DEBUG_ID ? super.toString() + "#" + hashCode() : super.toString();
	}
	public static Cell<Underline> of(Table table, int colspan) {
		return of(table, colspan, Tmp.c1.set(JColor.c_underline));
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
		return 1.2f * Scl.scl();
	}
	public void reset() {
		color.set(Color.white);
	}
}
