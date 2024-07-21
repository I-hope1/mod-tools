package modtools.ui;

import arc.Core;
import arc.func.Cons;
import arc.graphics.Color;
import arc.scene.ui.layout.Cell;
import arc.util.Align;
import mindustry.ui.BorderImage;
import modtools.ui.IntUI.ColorContainer;
import modtools.utils.EventHelper;

import static modtools.utils.ElementUtils.getAbsolutePos;

public class ColorBlock {
	public static void of(Cell<?> cell, Color color, boolean needDouble) {
		of(cell, color, null, needDouble);
	}
	/**
	 * <p>为{@link Cell cell}添加一个{@link Color color（颜色）}块</p>
	 * {@linkplain #of(Cell, Color, Cons, boolean)
	 * colorBlock(
	 * cell,
	 * color,
	 * callback,
	 * needDclick: boolean = true
	 * )}*
	 * @param cell     the cell
	 * @param color    the color
	 * @param callback the callback
	 * @see #of(Cell cell, Color color, Cons callback, boolean needDclick) #colorBlock(Cell cell, Color color, Cons callback, boolean needDclick)
	 */
	public static void of(Cell<?> cell, Color color, Cons<Color> callback) {
		of(cell, color, callback, true);
	}
	/**
	 * <p>为{@link Cell cell}添加一个{@link Color color（颜色）}块</p>
	 * @param cell       被修改成颜色块的cell
	 * @param color      初始化颜色
	 * @param callback   回调函数，形参为修改后的{@link Color color}
	 * @param needDclick 触发修改事件，是否需要双击（{@code false}为点击）
	 */
	public static void of(Cell<?> cell, Color color, Cons<Color> callback,
	                      boolean needDclick) {
		BorderImage image = new ColorContainer(color);
		cell.setElement(image).size(42f);
		Runnable runnable = () -> {
			IntUI.colorPicker().show(color, c1 -> {
				color.set(c1);
				if (callback != null) callback.get(c1);
			});
			Core.app.post(() -> IntUI.colorPicker().setPosition(getAbsolutePos(image), Align.left | Align.center));
		};
		EventHelper.doubleClick(image, needDclick ? null : runnable, needDclick ? runnable : null);
	}
}
