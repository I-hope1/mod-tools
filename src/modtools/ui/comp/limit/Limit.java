package modtools.ui.comp.limit;

import arc.Core;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.ui.ScrollPane;

/**
 * 视口裁剪：判断元素是否还落在最近一层 {@link ScrollPane} 的可视区域内。
 * <p>
 * {@code Group.act} 每帧对每个直接子元素调用一次，整个方法都在热路径上，
 * 所以只走一遍父链，并且能在 pane 局部坐标里判死就立刻返回。
 */
public interface Limit {
	/** 渲染线程单线程复用，省掉每帧每元素一次 Vec2 分配。 */
	final class Scratch {
		private static final Vec2 p = new Vec2();
	}

	static boolean isVisible(Element actor) {
		if (actor == null) return false;

		Vec2 p = Scratch.p.set(0, 0);
		ScrollPane pane = null;

		/* 顺着父链把 actor 的原点往上变换，顺手找最近的 ScrollPane：
		   停在 pane 上时，p 正好是「actor 原点在 pane 局部坐标系里的位置」 */
		for (Element e = actor; e != null; e = e.parent) {
			if (e instanceof ScrollPane) {
				pane = (ScrollPane) e;
				break;
			}
			e.localToParentCoordinates(p);
		}
		/* 没有 pane 就没有滚动裁剪 */
		if (pane == null) return true;

		float w  = actor.getWidth(), h = actor.getHeight();
		float pw = pane.getWidth(), ph = pane.getHeight();

		/* 列表里滚出去的元素占多数，先在这一步出局：
		   不必再补一次 actor→stage 的父链变换 */
		if (!(p.x > -w && p.y > -h && p.x < pw && p.y < ph)) return false;

		/* pane 自己也可能被拖出屏幕，继续把 p 从 pane 变换到 stage。
		   起点取 pane 而不是 actor，父链更短 */
		for (Element e = pane; e != null; e = e.parent) e.localToParentCoordinates(p);

		int gw = Core.graphics.getWidth(), gh = Core.graphics.getHeight();
		return p.x > -w && p.y > -h && p.x <= gw && p.y <= gh;
	}
}
