package modtools.ui.components;

import arc.func.Cons;
import arc.input.KeyCode;
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

	/** {@inheritDoc}  */
	public void hovered(Runnable r) {
		addListener(new InputListener() {
			public void enter(InputEvent event, float x, float y, int pointer, Element fromActor) {
				r.run();
				event.stop();
			}
		});
	}

	/** {@inheritDoc}  */
	public void exited(Runnable r) {
		addListener(new InputListener() {
			public void exit(InputEvent event, float x, float y, int pointer, Element fromActor) {
				r.run();
				event.stop();
			}
		});
	}

	/** {@inheritDoc}  */
	public void keyDown(Cons<KeyCode> cons) {
			addListener(new InputListener() {
				@Override
				public boolean keyDown(InputEvent event, KeyCode keycode) {
					cons.get(keycode);
					event.stop();
					return true;
				}
			});
		}
}
