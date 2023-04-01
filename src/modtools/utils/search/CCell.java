package modtools.utils.search;

import arc.scene.Element;
import arc.scene.ui.layout.Cell;

public class CCell {
	public static Cell<?> UNSET_CELL = new Cell<>();
	public        Cell<?> cell;
	private       Cell<?> cpy;
	public CCell(Cell<?> cell) {
		this.cell = cell;
	}
	public Cell<?> getCpy() {
		if (cpy == null) cpy = new Cell<>().set(cell);
		return cpy;
	}
	public void set(Element el) {
		if (cell.get() == el) return;
		cell.set(getCpy()).setElement(el);
	}
	public void remove() {
		if (cell.get() == null) return;
		getCpy();
		cell.set(UNSET_CELL).clearElement();
	}
}
