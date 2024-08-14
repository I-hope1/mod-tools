package modtools.ui.comp;

import arc.func.Cons;
import arc.scene.Element;
import arc.scene.ui.layout.*;
import modtools.utils.Tools;

/** 对add事件进行再处理  */
public class ListenerTable extends Table {
	private Cons<Cell<?>> listener;
	public Cons<Cell<?>> getListener() {
		return listener;
	}
	public <E extends Element> void setListener(Cons<Cell<E>> listener) {
		this.listener = Tools.as(listener);
	}
	public void clearCellListener() {
		listener = null;
	}
	public <T extends Element> Cell<T> add(T element) {
		Cell<T> cell = super.add(element);
		if (listener != null) listener.get(cell);
		return cell;
	}
}
