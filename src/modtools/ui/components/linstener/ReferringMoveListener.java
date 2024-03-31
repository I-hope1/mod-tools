package modtools.ui.components.linstener;

import arc.math.Interp;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.ui.layout.Table;
import arc.util.*;
import mindustry.graphics.*;
import modtools.utils.ui.LerpFun;

public class ReferringMoveListener extends MoveListener {
	public static final float UNSET      = Float.NEGATIVE_INFINITY;
	public static final int   snapOffset = 8;
	/**
	 * width或height的百分比<br>
	 * 比如<code>0.5</code>代表中间
	 */
	float[] horizontalLines, verticalLines;

	public ReferringMoveListener(Element touch, Table main, float[] horizontalLines,
															 float[] verticalLines) {
		super(touch, main);
		this.horizontalLines = horizontalLines;
		this.verticalLines = verticalLines;
	}

	public void display(float x, float y) {
		Vec2 result = snap(x, y);
		super.display(result.x, result.y);
	}
	protected Vec2 snap(float x, float y) {
		return snap(main, horizontalLines, verticalLines, x, y);
	}

	public static Vec2 snap(
	 Element main, float[] horizontalLines, float[] verticalLines,
	 float x, float y) {
		float parentWidth  = main.parent.getWidth();
		float parentHeight = main.parent.getHeight();
		float halfWidth    = main.getWidth() / 2f;
		float halfHeight   = main.getHeight() / 2f;

		float res = UNSET;
		for (float xl : horizontalLines) {
			float prefX = parentWidth * xl;
			/* 与元素中心点的距离 */
			float dst = Math.abs(prefX - x - halfWidth);
			if (dst < snapOffset) {
				res = prefX - halfWidth;
			} else if (Math.abs(dst - halfWidth) < snapOffset) {
				if (x < prefX - snapOffset) res = prefX - halfWidth * 2;
				else res = prefX;
			}
			if (res != UNSET) {
				x = res;
				new LerpFun(Interp.linear).onUI(main.parent).registerDispose(0.3f, _ -> {
					Drawf.dashLine(Pal.accent, prefX, 0, prefX, parentHeight);
				});
				break;
			}
		}
		res = UNSET;
		for (float yl : verticalLines) {
			float prefY = parentHeight * yl;
			/* 与元素中心点的距离 */
			float dst = Math.abs(prefY - y - halfHeight);

			if (dst < snapOffset) {
				res = prefY - halfHeight;
			} else if (Math.abs(dst - halfHeight) < snapOffset) {
				if (y < prefY - snapOffset) res = prefY - halfHeight * 2;
				else res = prefY;
			}

			if (res != UNSET) {
				y = res;
				new LerpFun(Interp.linear).onUI(main.parent).registerDispose(0.3f, _ -> {
					Drawf.dashLine(Pal.accent, 0, prefY, parentWidth, prefY);
				});
				break;
			}
		}
		return Tmp.v2.set(x, y);
	}
}
