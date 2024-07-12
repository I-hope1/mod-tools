package modtools.utils.ui;

import arc.scene.ui.layout.Cell;
import arc.util.Reflect;
import modtools.utils.reflect.FieldUtils;

import java.lang.reflect.Field;

@SuppressWarnings("DataFlowIssue")
public interface CellTools {
	Field f_column       = FieldUtils.getFieldAccess(Cell.class, "column"),
	 f_row               = FieldUtils.getFieldAccess(Cell.class, "row"),
	 f_align             = FieldUtils.getFieldAccess(Cell.class, "align"),
	 f_computedPadLeft   = FieldUtils.getFieldAccess(Cell.class, "computedPadLeft"),
	 f_computedPadTop    = FieldUtils.getFieldAccess(Cell.class, "computedPadTop"),
	 f_computedPadRight  = FieldUtils.getFieldAccess(Cell.class, "computedPadRight"),
	 f_computedPadBottom = FieldUtils.getFieldAccess(Cell.class, "computedPadBottom");

	static int column(Cell<?> cell) {
		return Reflect.get(cell, f_column);
	}

	static void column(Cell<?> cell, int value) {
		Reflect.set(cell, f_column, value);
	}

	static int row(Cell<?> cell) {
		return Reflect.get(cell, f_row);
	}

	static void row(Cell<?> cell, int value) {
		Reflect.set(cell, f_row, value);
	}

	static int align(Cell<?> cell) {
		return Reflect.get(cell, f_align);
	}
	static void align(Cell<?> cell, int value) {
		Reflect.set(cell, f_align, value);
	}

	static float padLeft(Cell<?> cell) {
		return Reflect.get(cell, f_computedPadLeft);
	}
	static void padLeft(Cell<?> cell, float value) {
		Reflect.set(cell, f_computedPadLeft, value);
	}
	static float padTop(Cell<?> cell) {
		return Reflect.get(cell, f_computedPadTop);
	}
	static void padTop(Cell<?> cell, float value) {
		Reflect.set(cell, f_computedPadTop, value);
	}
	static float padRight(Cell<?> cell) {
		return Reflect.get(cell, f_computedPadRight);
	}
	static void padRight(Cell<?> cell, float value) {
		Reflect.set(cell, f_computedPadRight, value);
	}
	static float padBottom(Cell<?> cell) {
		return Reflect.get(cell, f_computedPadBottom);
	}
	static void padBottom(Cell<?> cell, float value) {
		Reflect.set(cell, f_computedPadBottom, value);
	}
}
