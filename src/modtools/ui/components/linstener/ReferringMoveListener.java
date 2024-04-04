package modtools.ui.components.linstener;

import arc.graphics.Color;
import arc.input.KeyCode;
import arc.math.Interp;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.ui.layout.Table;
import arc.struct.FloatSeq;
import arc.util.Tmp;
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

	public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
		super.touchUp(event, x, y, pointer, button);
	}
	public void display(float x, float y) {
		Vec2 result = snap(x, y);
		super.display(result.x, result.y);
	}
	protected Vec2 snap(float x, float y) {
		return snap(main, horizontalLines, verticalLines, x, y);
	}

	static UniqueFloatSeq xLines = new UniqueFloatSeq(), yLines = new UniqueFloatSeq();
	static class UniqueFloatSeq extends FloatSeq {
		public void add(float value) {
			if (!contains(value)) super.add(value);
		}
	}
	public static Vec2 snap(
	 Element main, float[] horizontalLines, float[] verticalLines,
	 float x, float y) {
		float parentWidth  = main.parent.getWidth();
		float parentHeight = main.parent.getHeight();
		float halfWidth    = main.getWidth() / 2f, halfHeight = main.getHeight() / 2f;
		float originalX    = x, originalY = y;

		xLines.clear();
		yLines.clear();
		for (float v : horizontalLines) {
			xLines.add(v * parentWidth);
		}
		for (float v : verticalLines) {
			yLines.add(v * parentHeight);
		}
		for (Element child : main.parent.getChildren()) {
			if (child == main) continue;
			xLines.add(child.x);
			xLines.add(child.x + child.getWidth());
			yLines.add(child.y);
			yLines.add(child.y + child.getHeight());
		}
		for (int i = 0; i < xLines.size; i++) {
			x = process(false, originalX, xLines.get(i), halfWidth, main, parentHeight,
			 i < horizontalLines.length ? Pal.accent : Color.acid);
			if (x != UNSET) break;
		}
		for (int i = 0; i < yLines.size; i++) {
			y = process(true, originalY, yLines.get(i), halfHeight, main, parentWidth,
			 i < verticalLines.length ? Pal.accent : Color.acid);
			if (y != UNSET) break;
		}

		if (x == UNSET) x = originalX;
		if (y == UNSET) y = originalY;
		return Tmp.v2.set(x, y);
	}

	static float process(boolean vertical, float value, float prefValue, float halfValue, Element element,
											 float drawingValue, Color lineColor) {
		float res = UNSET;
		/* 与元素中心点的距离 */
		float dst = Math.abs(prefValue - value - halfValue);

		if (dst < snapOffset) {
			res = prefValue - halfValue;
		} else if (Math.abs(dst - halfValue) < snapOffset) {
			if (value < prefValue - snapOffset) res = prefValue - halfValue * 2;
			else res = prefValue;
		}
		if (res != UNSET) {
			new LerpFun(Interp.linear).onUI(element.parent).registerDispose(0.3f,
			 vertical ? _ -> Drawf.dashLine(lineColor, 0, prefValue, drawingValue, prefValue)
			 : _ -> Drawf.dashLine(lineColor, prefValue, 0, prefValue, drawingValue)
			);
		}
		return res;
	}
}
