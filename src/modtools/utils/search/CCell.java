package modtools.utils.search;

import arc.scene.Element;
import arc.scene.ui.layout.Cell;

public class CCell {
	public static Cell<?> UNSET_CELL = new Cell<>();
	public        Cell<?> cell, cpy;
	public CCell(Cell<?> cell) {
		this.cell = cell;
	}
	public void set(Element el) {
		if (cell.get() == el) return;
		cell.set(cpy).setElement(el);
	}
	public void remove() {
		if (cell.get() == null) return;
		if (cpy == null) cpy = new Cell<>().set(cell);
		cell.set(UNSET_CELL).clearElement();
	}
}
