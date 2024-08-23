package modtools.utils.ui;

import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.ui.layout.Cell;
import arc.util.*;
import modtools.utils.reflect.FieldUtils;

import java.lang.reflect.Field;

public interface CellTools {
	/* 获取一些字段 */
	Field f_column       = f("column"),
	 f_row               = f("row"),
	 f_align             = f("align"),
	 f_computedPadLeft   = f("computedPadLeft"),
	 f_computedPadTop    = f("computedPadTop"),
	 f_computedPadRight  = f("computedPadRight"),
	 f_computedPadBottom = f("computedPadBottom"),
	 f_colspan           = f("colspan"),
	 f_minWidth          = f("minWidth"),
	 f_minHeight         = f("minHeight"),
	 f_maxWidth          = f("maxWidth"),
	 f_maxHeight         = f("maxHeight"),
	 f_fillX             = f("fillX"),
	 f_fillY             = f("fillY"),
	 f_expandX           = f("expandX"),
	 f_expandY           = f("expandY"),
	 f_uniformX          = f("uniformX"),
	 f_uniformY          = f("uniformY");

	/** @see Cell#unset */
	float unset = Float.NEGATIVE_INFINITY;

	private static Field f(String name) {
		return FieldUtils.getFieldAccess(Cell.class, name);
	}

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

	// 下面的不用setter

	static int align(Cell<?> cell) {
		return Reflect.get(cell, f_align);
	}
	static void align(Cell<?> cell, int align) {
		Reflect.set(cell, f_align, align);
	}
	static float padLeft(Cell<?> cell) {
		return Reflect.get(cell, f_computedPadLeft);
	}
	static float padTop(Cell<?> cell) {
		return Reflect.get(cell, f_computedPadTop);
	}
	static float padRight(Cell<?> cell) {
		return Reflect.get(cell, f_computedPadRight);
	}
	static float padBottom(Cell<?> cell) {
		return Reflect.get(cell, f_computedPadBottom);
	}

	static int colspan(Cell<?> cell) {
		return Reflect.get(cell, f_colspan);
	}
	static float minWidth(Cell<?> cell) {
		return Reflect.get(cell, f_minWidth);
	}
	static float minHeight(Cell<?> cell) {
		return Reflect.get(cell, f_minHeight);
	}
	static float maxWidth(Cell<?> cell) {
		return Reflect.get(cell, f_maxWidth);
	}
	static float maxHeight(Cell<?> cell) {
		return Reflect.get(cell, f_maxHeight);
	}
	static float fillX(Cell<?> cell) {
		return Reflect.get(cell, f_fillX);
	}
	static float fillY(Cell<?> cell) {
		return Reflect.get(cell, f_fillY);
	}
	static int expandX(Cell<?> cell) {
		return Reflect.get(cell, f_expandX);
	}
	static int expandY(Cell<?> cell) {
		return Reflect.get(cell, f_expandY);
	}
	static boolean uniformX(Cell<?> cell) {
		return Reflect.get(cell, f_uniformX);
	}
	static boolean uniformY(Cell<?> cell) {
		return Reflect.get(cell, f_uniformY);
	}

	/** 换行并返回自己 */
	static <T extends Element> Cell<T> rowSelf(Cell<T> cell) {
		cell.row();
		return cell;
	}
	/** 返回的是{@link Tmp#v1} */
	static Vec2 minSize(Cell<?> cell) {
		return Tmp.v1.set(CellTools.minWidth(cell), CellTools.minHeight(cell));
	}
	/** 返回的是{@link Tmp#v1} */
	static Vec2 maxSize(Cell<?> cell) {
		return Tmp.v1.set(CellTools.maxWidth(cell), CellTools.maxHeight(cell));
	}
}
