package modtools.utils.ui.search;

import arc.scene.Element;
import arc.scene.ui.layout.Cell;

public class BindCell {
	public static final Cell<?> UNSET_CELL = new Cell<>();

	public  Cell<?> cell;
	private Cell<?> cpy;
	private Element el;

	public BindCell(Cell<?> cell) {
		this.cell = cell;
		reget();
	}
	public void reget() {
		el = cell.get();
	}
	public Cell<?> getCpy() {
		if (cpy == null) cpy = new Cell<>().set(cell);
		return cpy;
	}
	public void build() {
		if (cell.get() == el) return;
		cell.set(getCpy()).setElement(el);
	}
	public void remove() {
		if (cell.get() == null) return;
		getCpy();
		cell.set(UNSET_CELL).clearElement();
	}
	public void clear() {
		el.clear();
		if (cpy != null) cpy.clearElement();
		if (cell != null) cell.clearElement();
		el = null;
		cpy = null;
		cell = null;
	}
	public void toggle() {
		toggle(cell.get() != el);
	}
	public void toggle(boolean b) {
		if (b) build();
		else remove();
	}
}
