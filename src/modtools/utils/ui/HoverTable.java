package modtools.utils.ui;

import arc.func.Cons;
import arc.scene.style.Drawable;
import arc.scene.ui.layout.Table;
import modtools.ui.components.limit.LimitTable;

/** hover就显示（alpha 1）否则alpha 0 */
public class HoverTable extends LimitTable {
	boolean shown = false;

	{
		hovered(() -> shown = true);
		exited(() -> shown = false);
	}

	public void draw() {
		parentAlpha *= shown ? 1 : 0.3f;
		super.draw();
	}
	public HoverTable() {
	}
	public HoverTable(Drawable background) {
		super(background);
	}
	public HoverTable(Drawable background, Cons<Table> cons) {
		super(background, cons);
	}
	public HoverTable(Cons<Table> cons) {
		super(cons);
	}
}
