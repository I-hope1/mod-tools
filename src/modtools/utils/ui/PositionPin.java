package modtools.utils.ui;

import arc.Core;
import arc.scene.Element;

public class PositionPin {
	/**
	 * 将{@code a元素}移动（align）到b元素的坐标（bAlign）
	 * <p>如果超出屏幕，对齐方式(align)换个向</p>
	 * @see Element#setPosition(float x, float y, int align)
	 * @see Element#getX(int align)
	 * @see Element#getY(int align)
	 *
	 * */
	public static void pin(Element a, int align, Element b, int bAlign){
		a.setPosition(
				Math.min(
					Math.max(
						b.getX(bAlign),
						a.getX(align)
					),
				 Core.graphics.getWidth() - a.getWidth()
				),
				Math.min(
					Math.max(
						b.getY(bAlign),
						a.getY(align)
					),
					Core.graphics.getHeight() - a.getHeight()
				),
				align
			);
	}
}
