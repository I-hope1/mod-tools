package nipx.uihook;

import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import nipx.jni.helper.MasterKey;

import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;

import static nipx.HotSwapAgent.error;

/**
 * 安全封装对 Arc Table 和 Cell 内部私有字段的反射读写操作。
 * 提供降级防护与空指针安全检查。
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class ArcReflectionAdapter {
	private static Field f_table_columns;
	private static Field f_table_rows;
	private static Field f_table_implicitEndRow;
	private static Field f_cell_row;
	private static Field f_cell_column;
	private static Field f_endRow;
	private static Field f_colspan;

	static {
		initFields();
	}

	private static void initFields() {
		f_table_columns      = nl(() -> Table.class.getDeclaredField("columns"));
		f_table_rows         = nl(() -> Table.class.getDeclaredField("rows"));
		f_table_implicitEndRow = nl(() -> Table.class.getDeclaredField("implicitEndRow"));
		f_cell_row           = nl(() -> Cell.class.getDeclaredField("row"));
		f_cell_column        = nl(() -> Cell.class.getDeclaredField("column"));
		f_endRow             = nl(() -> Cell.class.getDeclaredField("endRow"));
		f_colspan            = nl(() -> Cell.class.getDeclaredField("colspan"));
	}

	private static <T> T nl(NLSupplier<T> supplier) {
		try {
			T t = supplier.get();
			if (t instanceof AccessibleObject ac) ac.setAccessible(true);
			return t;
		} catch (Throwable e) {
			error("[ArcReflectionAdapter] Failed to access field", e);
			return null;
		}
	}

	@FunctionalInterface
	private interface NLSupplier<T> {
		T get() throws Throwable;
	}

	public static boolean isEndRow(Cell<?> cell) {
		if (cell == null) return false;
		if (f_endRow != null) {
			try {
				return f_endRow.getBoolean(cell);
			} catch (Throwable ignored) { }
		}
		return cell.isEndRow();
	}

	public static void setEndRow(Cell<?> cell, boolean endRow) {
		if (cell == null || f_endRow == null) return;
		try {
			f_endRow.setBoolean(cell, endRow);
		} catch (Throwable e) {
			error("[ArcReflectionAdapter] Failed to set endRow on Cell", e);
		}
	}

	public static int getColspan(Cell<?> cell) {
		if (cell == null || f_colspan == null) return 1;
		try {
			return f_colspan.getInt(cell);
		} catch (Throwable ignored) {
			return 1;
		}
	}

	public static void setCellPosition(Cell<?> cell, int row, int col) {
		if (cell == null) return;
		try {
			if (f_cell_row != null) f_cell_row.setInt(cell, row);
			if (f_cell_column != null) f_cell_column.setInt(cell, col);
		} catch (Throwable e) {
			error("[ArcReflectionAdapter] Failed to set cell position", e);
		}
	}

	public static int getCellRow(Cell<?> cell) {
		if (cell == null || f_cell_row == null) return -1;
		try {
			return f_cell_row.getInt(cell);
		} catch (Throwable ignored) {
			return -1;
		}
	}

	public static void setTableDimensions(Table table, int rows, int columns) {
		if (table == null) return;
		try {
			if (f_table_rows != null) f_table_rows.setInt(table, rows);
			if (f_table_columns != null) f_table_columns.setInt(table, columns);
		} catch (Throwable e) {
			error("[ArcReflectionAdapter] Failed to set table dimensions", e);
		}
	}

	public static void recalculateColumns(Table table) {
		if (table == null) return;
		try {
			int maxCols = 0;
			Seq<Cell> cells = table.getCells();
			if (cells == null) return;

			for (int i = 0; i < cells.size; ) {
				Cell c = cells.get(i);
				int rowCols = 0;
				do {
					rowCols += getColspan(c);
					i++;
					if (i >= cells.size) break;
					c = cells.get(i);
				} while (!isEndRow(c));
				if (rowCols > maxCols) maxCols = rowCols;
			}

			if (f_table_columns != null) {
				f_table_columns.setInt(table, maxCols);
			} else {
				table.invalidate();
			}
		} catch (Throwable e) {
			error("[ArcReflectionAdapter] Failed to recalculate columns", e);
		}
	}

	/**
	 * 在热重载向 Table 追加新 Cell 之前调用。
	 * <p>
	 * 问题：上一次 {@code computeSize()} 若对最后一行做了隐式 {@code endRow()}，
	 * 会将 {@code implicitEndRow=true}。此后 {@code cell.row()} → {@code table.row()}
	 * 里有个短路：{@code if (!implicitEndRow) endRow()}，导致 {@code endRow()} 被跳过，
	 * {@code table.rows} 不会递增，新 Cell 的 {@code cell.row} 却已经是 {@code rows}（越界）。
	 * <p>
	 * 清除该标志后，{@code table.row()} 会正常调用 {@code endRow()} 递增 {@code rows}。
	 */
	public static void clearImplicitEndRow(Table table) {
		if (table == null || f_table_implicitEndRow == null) return;
		try {
			f_table_implicitEndRow.setBoolean(table, false);
		} catch (Throwable e) {
			error("[ArcReflectionAdapter] Failed to clear implicitEndRow", e);
		}
	}

	/**
	 * 保底防护：若新 Cell 的 row 超出 table.rows，直接将 rows 调高，
	 * 防止 computeSize 未及时跑时 layout 越界。
	 */
	public static void ensureTableRows(Table table, int minRows) {
		if (table == null || f_table_rows == null) return;
		try {
			int rows = f_table_rows.getInt(table);
			if (rows < minRows) {
				f_table_rows.setInt(table, minRows);
			}
		} catch (Throwable e) {
			error("[ArcReflectionAdapter] Failed to ensure table rows", e);
		}
	}

	public static void repairTableGrid(Table table) {
		if (table == null) return;

		try {
			Seq<Cell> cells = table.getCells();
			if (cells == null) return;

			int currentRow = 0;
			int currentCol = 0;

			for (int i = 0; i < cells.size; i++) {
				Cell<?> c = cells.get(i);

				setCellPosition(c, currentRow, currentCol);

				int span = getColspan(c);
				currentCol += span;

				if (isEndRow(c)) {
					currentCol = 0;
					currentRow++;
				}
			}
			// 不写 table.columns / table.rows：
			// 这两个字段由 Table.add() + endRow() 在 Cell 插入时维护，
			// 强制覆盖会使 computeSize() 分配的数组长度与实际 Cell 布局不匹配，
			// 导致 layout() 中 columnWeightedWidth[column+colspan-1] 越界崩溃。
		} catch (Throwable t) {
			error("[ArcReflectionAdapter] Failed to repair table grid", t);
		}
	}


	public static Lookup lookup() {
		return MasterKey.INSTANCE.getTrustedLookup();
	}
}
