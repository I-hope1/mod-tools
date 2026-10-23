package modtools.ui.comp.review;

import arc.scene.*;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import arc.util.Tmp;

/**
 * 布局体检：把「一眼看得出是 bug」的几何状态标出来。
 * <p>判断刻意保守——误报比漏报更烦人。所以只报告**完全**落在父级/可视区之外、
 * 或**完全**被兄弟覆盖这类确定的情况，部分超出只字不提。
 * <p>没有面积（宽高为 0）和不可见的元素一律不审：未布局的子树（折叠的 Fragment、
 * 还没显示过的窗口）里每个元素都是 0×0，把它们算成问题是纯粹的噪音。
 * 祖先链上任何一层没有面积或不可见时也整棵跳过——子元素身上的宽高只是上次布局的残留，
 * 拿它跟 0×0 的父级比会一律判成「超出父级」。
 * <p>「被遮挡」只在 {@link Table} 内部判断：顶层 actor（scene.root / TopGroup）之间
 * 按 z 序重叠是正常现象，全屏的拾取层会把底下的元素一律判成被完全覆盖。
 */
public final class LayoutIssues {
	public static final int
	 NONE          = 0,
	/** 宽高是 NaN/Infinity，通常是除零或未初始化的计算 */
	INVALID_SIZE  = 1 << 1,
	/** 完全在父级矩形之外 */
	OUT_OF_PARENT = 1 << 2,
	/** 完全在最近的 ScrollPane 可视区之外（滚也看不到，除非滚动位置变了） */
	CLIPPED       = 1 << 3,
	/** 被后面的兄弟完全盖住 */
	COVERED       = 1 << 4;

	/** 判断时的容差：布局里的浮点误差不该算问题 */
	private static final float EPS = 0.5f;

	private LayoutIssues() { }

	public static boolean has(int mask, int flag) {
		return (mask & flag) != 0;
	}

	/** @return 位掩码，0 表示没发现问题 */
	public static int check(Element e) {
		if (e == null) return NONE;

		float w = e.getWidth(), h = e.getHeight();
		if (Float.isNaN(w) || Float.isNaN(h) || Float.isInfinite(w) || Float.isInfinite(h)) {
			return INVALID_SIZE;
		}
		// 没有面积的元素没有可审的几何
		if (w <= 0f || h <= 0f) return NONE;
		// 不可见元素的几何多半是上次布局的残留，审它只会误报
		if (!e.visible) return NONE;

		int     mask   = NONE;
		Element parent = e.parent;
		if (parent == null) return mask;

		/*
		 * 祖先链上只要有一层没有面积或不可见，这棵子树就是「还没布局」：
		 * 子元素身上的宽高是上一次布局留下的，拿它跟 0×0 的父级比必然判成「超出父级」。
		 * 必须整条链一起看——父级自己可能是被更上层压成 0 的，它的值同样是残留。
		 */
		for (Element p = parent; p != null; p = p.parent) {
			if (!p.visible || p.getWidth() <= 0f || p.getHeight() <= 0f) return NONE;
		}

		// 父级 ScrollPane 的「溢出」是正常的（widget 本来就可能比可视区大），交给 CLIPPED 判断
		if (!(parent instanceof ScrollPane) && !rectInside(e, parent, 0, 0, parent.getWidth(), parent.getHeight())) {
			mask |= OUT_OF_PARENT;
		}
		mask |= clippedByPane(e);
		mask |= coveredBySibling(e);
		return mask;
	}

	/** 元素矩形是否完整落在 (x, y, w, h) 内（坐标都换算到 ancestor 的局部坐标） */
	private static boolean rectInside(Element e, Element ancestor, float x, float y, float w, float h) {
		float[] rect = rectIn(e, ancestor);
		return rect[0] >= x - EPS && rect[1] >= y - EPS
		       && rect[2] <= x + w + EPS && rect[3] <= y + h + EPS;
	}

	/**
	 * 元素矩形换算到 ancestor 的局部坐标，返回 {x1, y1, x2, y2}。
	 * <p>用返回值而不是共享 Vec2：调用方可能同时持有多个矩形。
	 */
	private static float[] rectIn(Element e, Element ancestor) {
		float x1 = 0, y1 = 0, x2 = e.getWidth(), y2 = e.getHeight();
		Tmp.v1.set(x1, y1);
		e.localToAscendantCoordinates(ancestor, Tmp.v1);
		x1 = Tmp.v1.x;
		y1 = Tmp.v1.y;
		Tmp.v1.set(x2, y2);
		e.localToAscendantCoordinates(ancestor, Tmp.v1);
		x2 = Tmp.v1.x;
		y2 = Tmp.v1.y;
		return new float[]{Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2)};
	}

	private static int clippedByPane(Element e) {
		ScrollPane pane = findPane(e);
		if (pane == null || pane.getWidget() == null || e == pane.getWidget()) return NONE;
		// 元素自己就在可视区里（父链上被裁）→ 用 pane 的局部矩形判断
		float[] rect = rectIn(e, pane);
		float   pw   = pane.getWidth(), ph = pane.getHeight();
		boolean disjoint = rect[2] <= 0f || rect[3] <= 0f || rect[0] >= pw || rect[1] >= ph;
		return disjoint ? CLIPPED : NONE;
	}

	private static ScrollPane findPane(Element e) {
		for (Element el = e.parent; el != null; el = el.parent) {
			if (el instanceof ScrollPane pane) return pane;
		}
		return null;
	}

	private static int coveredBySibling(Element e) {
		if (!(e.parent instanceof Group parent) || parent.getChildren().size <= 1) return NONE;
		/*
		 * 只有 Table 里的兄弟才谈得上「被遮挡」。
		 * scene.root / TopGroup 下是互不相干的顶层 actor，按 z 序重叠本来就是正常行为；
		 * 在那里判断会让全屏的拾取层（TopGroup）把底下所有元素一律判成「被完全覆盖」。
		 */
		if (!(parent instanceof Table)) return NONE;
		// 兄弟极多的容器多半是列表，互相覆盖不是这里的常见 bug，跳过以省掉 O(n²)
		if (parent.getChildren().size > 64) return NONE;
		if (!e.visible) return NONE;

		float[] rect = rectIn(e, parent);
		for (Element sibling : parent.getChildren()) {
			if (sibling == e || !sibling.visible) continue;
			// 只有后画的（zIndex 更大）才可能盖住
			if (sibling.getZIndex() <= e.getZIndex()) continue;
			float[] other = rectIn(sibling, parent);
			if (other[0] <= rect[0] + EPS && other[1] <= rect[1] + EPS
			    && other[2] >= rect[2] - EPS && other[3] >= rect[3] - EPS) {
				return COVERED;
			}
		}
		return NONE;
	}

	/** 短描述，用于树上/面板上的提示；mask 为 0 时返回空串 */
	public static String describe(int mask) {
		if (mask == NONE) return "";
		StringBuilder sb = new StringBuilder(24);
		append(sb, mask, INVALID_SIZE, "尺寸非法");
		append(sb, mask, OUT_OF_PARENT, "超出父级");
		append(sb, mask, CLIPPED, "被裁剪");
		append(sb, mask, COVERED, "被遮挡");
		return sb.toString();
	}

	private static void append(StringBuilder sb, int mask, int flag, String text) {
		if (!has(mask, flag)) return;
		if (sb.length() > 0) sb.append('、');
		sb.append(text);
	}
}
