package modtools.utils.search;

import arc.scene.Element;
import arc.scene.ui.layout.Cell;

public class BindCell {
	public CCell   cell;
	public Element element;

	public BindCell(Cell<?> cell) {
		this.cell = new CCell(cell);
		this.element = cell.get();
		// this.head = currentHead;
	}
	public void build() {
		cell.set(element);
	}
	public void remove() {
		cell.remove();
	}
}
