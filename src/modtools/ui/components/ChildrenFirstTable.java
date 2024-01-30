package modtools.ui.components;

import arc.func.Cons;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.style.Drawable;
import arc.scene.ui.layout.Table;
import modtools.ui.components.limit.LimitTable;

/** 子节点优先 */
public class ChildrenFirstTable extends LimitTable {
	public ChildrenFirstTable() {
	}
	public ChildrenFirstTable(Drawable background) {
		super(background);
	}
	public ChildrenFirstTable(Drawable background, Cons<Table> cons) {
		super(background, cons);
	}
	public ChildrenFirstTable(Cons<Table> cons) {
		super(cons);
	}
	/** Adds a hover/mouse enter listener. */
	public void hovered(Runnable r) {
		addListener(new InputListener() {
			public void enter(InputEvent event, float x, float y, int pointer, Element fromActor) {
				r.run();
				event.stop();
			}
		});
	}

	/** Adds a hover/mouse exit listener. */
	public void exited(Runnable r) {
		addListener(new InputListener() {
			public void exit(InputEvent event, float x, float y, int pointer, Element fromActor) {
				r.run();
				event.stop();
			}
		});
	}
}
