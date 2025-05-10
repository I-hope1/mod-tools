package modtools.utils.search;

import arc.scene.Element;
import arc.scene.ui.layout.*;
import arc.util.pooling.*;
import arc.util.pooling.Pool.Poolable;
import modtools.utils.ui.CellTools;

public final class BindCell implements Poolable {
	public static final  Cell<?>        UNSET_CELL   = new Cell<>();
	private static final Pool<Cell>     cellPool     = Pools.get(Cell.class, Cell::new);
	private static final Pool<BindCell> bindCellPool = Pools.get(BindCell.class, BindCell::new);

	/* static {
		UNSET_CELL.colspan(0);
	} */

	public  Cell<?> cell;
	private Cell<?> cpy;
	public  Element el;

	private BindCell() { }
	private BindCell init(Cell<?> cell) {
		if (cell == null) throw new NullPointerException("cell is null");
		this.cell = cell;
		require();
		return this;
	}

	public static BindCell of(Cell<?> cell) {
		return bindCellPool.obtain().init(cell);
	}
	public static BindCell ofConst(Cell<?> cell) {
		return new BindCell().init(cell);
	}


	public void require() {
		this.el = cell.get();
	}
	public void replace(Element el) {
		replace(el, false);
	}
	public void replace(Element newEl, boolean keepSize) {
		if (keepSize) cell.size(el.getWidth() / Scl.scl(), el.getHeight() / Scl.scl());
		el = newEl;
		build();
	}
	public void unsetSize() {
		cell.size(CellTools.unset);
	}
	public Cell<?> getCpy() {
		if (cpy == null) {
			cpy = cellPool.obtain();
			cpy.set(cell);
		}
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
	/** clear时会回收自己（不包括cell，cell由table回收）  */
	public void clear() {
		if (el != null) el.clear();
		if (cell != null) cell.clearElement();
		bindCellPool.free(this);
	}

	// toggle
	public void toggle() {
		toggle(cell.get() != el);
	}
	public void toggle(boolean b) {
		if (b) build();
		else remove();
	}
	public boolean toggle1(boolean b) {
		toggle(b);
		return b;
	}
	public void reset() {
		if (cpy != null) {
			cpy.clearElement();
			cellPool.free(cpy);
		}
		el = null;
		cpy = null;
		cell = null;
	}
	public void setCell(Cell cell) {
		this.cell = cell;
		require();
	}
}
