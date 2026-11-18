package modtools.ui.comp.input;

import arc.Core;
import arc.Graphics.Cursor.SystemCursor;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.graphics.g2d.GlyphLayout.GlyphRun;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.*;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.style.Drawable;
import arc.scene.ui.Label;
import arc.struct.*;
import arc.struct.IntMap.Keys;
import arc.util.*;
import arc.util.pooling.Pools;
import mindustry.ui.Styles;
import modtools.ui.control.HopeInput;

import java.util.Objects;

/**
 * 内嵌的文本
 * <p>可以对局部染色，
 * <p>可以对局部添加点击事件
 **/
public class InlineLabel extends NoMarkupLabel {
	private static final ThreadLocal<Seq<GlyphRun>> resultLocal    = ThreadLocal.withInitial(Seq::new);
	private static final ThreadLocal<IntSeq>        colorKeysLocal = ThreadLocal.withInitial(IntSeq::new);

	public static final int    UNSET_I = -1;
	public static final Point2 UNSET_P = new Point2(UNSET_I, UNSET_I);
	public              float  labelX, labelY;

	public InlineLabel(CharSequence text) {
		super(text);
	}
	public InlineLabel(CharSequence text, LabelStyle style) {
		super(text, style);
	}
	public InlineLabel(Prov<CharSequence> sup) {
		super(sup);
	}

	public void clear() {
		super.clear();
		cache.clear();
		// 文本没了，缓存整个丢掉：下次真被查询时再按需分配
		hitDirty = true;
		hit = null;
	}

	//region 文本染色
	/** splitAndColorize里不会触发splitAndColorize */
	public static void splitAndColorize(Seq<GlyphRun> runs, IntMap<Color> colorMap, StringBuilder text) {
		if (runs.isEmpty() || text.length() == 0) return;
		if (colorMap.isEmpty()) return;
		if (!colorMap.containsKey(0)) colorMap.put(0, Color.white);

		if (colorMap.size == 1 || (colorMap.size == 2 && Color.white.equals(colorMap.get(text.length())))) {
			Color color = colorMap.get(0);
			runs.each(r -> r.color.set(color));
			return;
		}

		var result    = resultLocal.get();
		var colorKeys = colorKeysLocal.get();
		result.clear();
		colorKeys.clear();
		Keys keys = colorMap.keys();
		while (keys.hasNext) {
			colorKeys.add(keys.next());
		}
		colorKeys.sort();

		Color color        = colorMap.get(0);
		int   currentIndex = 0;
		int   itemIndex    = 0;

		var      iter     = runs.iterator();
		GlyphRun item     = iter.next();
		float    currentX = item.x;
		for (int i = 1; i < colorKeys.size; i++) {
			final int endIndex = colorKeys.get(i);
			if (currentIndex == endIndex) {
				color = colorMap.get(endIndex);
				continue;
			}

			do {
				int size = item.glyphs.size - itemIndex; // 当前item剩余字符数
				// 判断是否超出当前颜色范围
				if (size <= endIndex - currentIndex) {
					// 整个item在当前颜色范围
					result.add(InlineLabel.sub(item, itemIndex, item.glyphs.size, color, currentX));
					currentIndex += size;
				} else {
					// [1, {2, 3}, 4] | []: item, {}: color
					// 仅部分item在当前颜色范围
					int nextIndex = itemIndex + (endIndex - currentIndex);
					result.add(InlineLabel.sub(item, itemIndex, nextIndex, color, currentX));
					FloatSeq adv = item.xAdvances;
					for (int k = itemIndex; k < nextIndex; k++) {
						currentX += adv.get(k);
					}
					itemIndex = nextIndex;
					currentIndex = endIndex;
					continue;
				}
				if (iter.hasNext()) {
					do {
						item = iter.next();
					} while (item.glyphs.isEmpty() && iter.hasNext());

					itemIndex = 0;
					currentX = item.x;
					// 末尾几个 run 可能全是空的（如文本以换行符结束），此时没有 glyph 可用于对齐，
					// 直接跳过；否则 item.glyphs.first() 会抛 IllegalStateException
					if (!item.glyphs.isEmpty()) {
						// 对自动换行偏移
						while (currentIndex < text.length() && (char) item.glyphs.first().id != text.charAt(currentIndex)) {
							currentIndex++;
						}
					}
				} else {
					itemIndex = item.glyphs.size; // ??
					break;
				}
			} while (currentIndex < endIndex);

			color = colorMap.get(endIndex);
		}
		// 颜色边界只描述到最后一个 key 为止，其后可能还剩整段 run（换行后的行）。
		// runs 会被 result 整体替换，这里漏掉的 run 不会报错，只会让那部分文本凭空消失。
		while (true) {
			if (itemIndex < item.glyphs.size) {
				result.add(InlineLabel.sub(item, itemIndex, item.glyphs.size, color, currentX));
			}
			if (!iter.hasNext()) break;
			item = iter.next();
			itemIndex = 0;
			currentX = item.x;
			// 对自动换行/换行符跳过的空白偏移
			if (!item.glyphs.isEmpty()) {
				while (currentIndex < text.length() && (char) item.glyphs.first().id != text.charAt(currentIndex)) currentIndex++;
			}
		}

		result.removeAll(Objects::isNull);

		Pools.freeAll(runs, true);
		runs.clear();
		runs.addAll(result);

		result.clear();
	}
	private static GlyphRun sub(GlyphRun glyphRun, int startIndex, int endIndex, Color color, float currentX) {
		if (startIndex < 0) return null;
		// 越界不会读到脏数据：FloatSeq/Seq 的 addAll 与 get 都有边界检查，会直接抛
		// IllegalArgumentException。但那是把一个能画出来的 run 整个变成异常，
		// 按末尾截断更接近调用方的本意。
		if (endIndex > glyphRun.glyphs.size) endIndex = glyphRun.glyphs.size;
		if (endIndex <= startIndex) return null;
		GlyphRun newRun = Pools.obtain(GlyphRun.class, GlyphRun::new);
		boolean  isSame = startIndex == 0 && endIndex == glyphRun.glyphs.size;

		newRun.y = glyphRun.y;
		newRun.x = currentX;
		newRun.xAdvances.addAll(glyphRun.xAdvances, startIndex, endIndex - startIndex + 1);
		newRun.glyphs.addAll(glyphRun.glyphs, startIndex, Math.max(0, endIndex - startIndex));
		if (isSame) {
			newRun.width = glyphRun.width;
		} else {
			float    width = 0;
			FloatSeq adv   = glyphRun.xAdvances;
			// run.width 与 run.x 同一原点：GlyphLayout 里恒有 run.x + run.width == 右边界，
			// 而字形从 run.x + xAdvances[0] 开始画。漏掉 xAdvances[startIndex] 会让
			// run.x + run.width 落在真实右边界左侧一个 advance 处。
			for (int k = startIndex; k <= endIndex; k++) {
				width += adv.get(k);
			}
			newRun.width = width;
		}
		newRun.color.set(color);
		return newRun;
	}
	/**
	 * 修改了部分layout
	 * @see Label#layout()
	 */
	public void layout() {
		if (cache == null) return;
		Font  font      = cache.getFont();
		float oldScaleX = font.getScaleX();
		float oldScaleY = font.getScaleY();
		if (fontScaleChanged) font.getData().setScale(fontScaleX, fontScaleY);

		boolean wrap = this.wrap && ellipsis == null;
		if (wrap) {
			float prefHeight = getPrefHeight();
			if (prefHeight != lastPrefHeight) {
				lastPrefHeight = prefHeight;
				invalidateHierarchy();
			}
		}

		float    width      = getWidth(), height = getHeight();
		Drawable background = style.background;
		float    x          = 0, y = 0;
		if (background != null) {
			x = background.getLeftWidth();
			y = background.getBottomHeight();
			width -= background.getLeftWidth() + background.getRightWidth();
			height -= background.getBottomHeight() + background.getTopHeight();
		}

		GlyphLayout layout = this.layout;
		float       textWidth, textHeight;
		if (wrap || text.indexOf("\n") != -1) {
			// If the text can span multiple lines, determine the text's actual size so it can be aligned within the label.
			layout.setText(font, text, 0, text.length(), Color.white, width, lineAlign, wrap, ellipsis);
			textWidth = layout.width;
			textHeight = layout.height;

			if ((labelAlign & Align.left) == 0) {
				if ((labelAlign & Align.right) != 0) { x += width - textWidth; } else { x += (width - textWidth) / 2; }
			}
		} else {
			textWidth = width;
			textHeight = font.getData().capHeight;
		}

		if ((labelAlign & Align.top) != 0) {
			y += cache.getFont().isFlipped() ? 0 : height - textHeight;
			y += style.font.getDescent();
		} else if ((labelAlign & Align.bottom) != 0) {
			y += cache.getFont().isFlipped() ? height - textHeight : 0;
			y -= style.font.getDescent();
		} else {
			y += (height - textHeight) / 2;
		}
		if (!cache.getFont().isFlipped()) y += textHeight;


		labelX = x;
		labelY = y;
		layout.setText(font, text, 0, text.length(), Color.white, textWidth, lineAlign, wrap, ellipsis);

		splitAndColorize(layout.runs, colorMap, text);
		hitDirty = true; // runs 刚被重排，缓存的下标作废
		cache.setText(layout, x, y);
		// Pools.freeAll(layout.runs);

		if (fontScaleChanged) font.getData().setScale(oldScaleX, oldScaleY);
	}
	/**
	 * 颜色分段。初始容量给小：默认容量 51（表长 64）要 616 字节，而绝大多数标签只有几种颜色。
	 * 表只增不减，所以高水位由真实用量决定，不会因为起始小就反复扩容。
	 */
	public final IntMap<Color> colorMap = new IntMap<>(4) {
		public Color put(int key, Color value) {
			if (value == null) throw new NullPointerException("value is null");
			return super.put(key, value);
		}
	};
	//endregion

	//region Clickable

	private static final Point2 overChunk = new Point2(UNSET_I, UNSET_I);
	private static final Point2 downChunk = new Point2(UNSET_I, UNSET_I);
	private static final int    padding   = 4;

	//region 命中测试缓存
	/**
	 * {@link #getCursor}（鼠标事件）和 {@link #getRect}（{@code draw()} 里每帧，只要在悬停）都要把
	 * run 对齐到文本下标。逐 run 用 {@code text.charAt} 扫一遍是 O(字符数)，而后者每帧都跑；
	 * 缓存一份之后两边都变成一次数组读取，{@code getRect} 还能直接跳到选区所在的 run。
	 * <p>做成独立对象是为了**按需分配**：代码视图里成千上万个 label，绝大多数从没被悬停过，
	 * 而一个默认容量的 {@link IntSeq} 就要 104 字节。
	 */
	private static class HitCache {
		/** 与 {@code layout.runs} 一一对应的起始字符下标，空 run 为 -1 */
		final IntSeq runStart = new IntSeq();
		/** 缓存 {@code style.font.getLineHeight()}，它在一次布局内不变 */
		float lineH;
		/** 文字包围盒（相对 labelX/labelY），鼠标完全不在文字上时一次比较就能退出 */
		float relMinX, relMaxX, relMinY, relMaxY;
		/** 上一次的结果：一次 mouseMoved 里两个监听器会用同一组坐标各问一次 */
		float lastX = Float.NaN, lastY;
		int   lastCursor = UNSET_I;
	}

	/** null = 这个 label 从没被查询过，此时不占任何缓存内存 */
	private HitCache hit;
	/** {@link #hit} 需要重建：{@link #layout()} 会重排 runs，{@link #clear()} 会清空文本 */
	private boolean  hitDirty = true;

	private HitCache hitCache() {
		HitCache h = hit;
		if (h == null) hit = h = new HitCache();

		Seq<GlyphRun> runs = layout.runs;
		// size 校验：getPrefWidth/Height 失效重算时会重新 layout.setText 覆盖 runs
		//（那时还没做染色拆分），数量可能和缓存对不上
		if (!hitDirty && h.runStart.size == runs.size) return h;
		hitDirty = false;
		h.lastX = Float.NaN;

		h.lineH = style.font.getLineHeight();
		h.runStart.clear();
		h.relMinX = h.relMinY = Float.MAX_VALUE;
		h.relMaxX = h.relMaxY = -Float.MAX_VALUE;

		int index = 0, len = text.length();
		for (int r = 0; r < runs.size; r++) {
			GlyphRun run = runs.get(r);
			if (run.glyphs.isEmpty()) {
				h.runStart.add(-1);
				continue;
			}
			char first = (char) run.glyphs.first().id;
			while (index < len && first != text.charAt(index)) index++; // 弥补offset
			h.runStart.add(index);
			index += run.glyphs.size;

			// 包围盒：左边要算上 xAdvances.first()（kerning 可能为负），右边用 run.width
			h.relMinX = Math.min(h.relMinX, run.x + Math.min(0, run.xAdvances.first()));
			h.relMaxX = Math.max(h.relMaxX, run.x + run.width);
			h.relMinY = Math.min(h.relMinY, run.y - h.lineH);
			h.relMaxY = Math.max(h.relMaxY, run.y);
		}
		return h;
	}
	//endregion

	/** 获取(x, y)对应的index */
	public int getCursor(float x, float y) {
		HitCache h = hitCache();
		if (x == h.lastX && y == h.lastY) return h.lastCursor;
		h.lastX = x;
		h.lastY = y;
		return h.lastCursor = computeCursor(h, x, y);
	}

	private int computeCursor(HitCache h, float x, float y) {
		// 鼠标不在文字包围盒里：一次比较就退出（点在标签留白上是很常见的情况）
		if (x < labelX + h.relMinX || x >= labelX + h.relMaxX
		    || y <= labelY + h.relMinY || y > labelY + h.relMaxY) return UNSET_I;

		Seq<GlyphRun> runs = layout.runs;
		for (int r = 0, n = runs.size; r < n; r++) {
			int start = h.runStart.get(r);
			if (start < 0) continue; // 空 run，否则 glyphs.first() 越界
			GlyphRun run = runs.get(r);

			// 先用最便宜的两个判断剔除：y 带、x 终点。被剔除的 run 什么都不做，
			// 不读 xAdvances —— 逐 run 的开销主要就花在这类读取上。
			// run.y 与 getRect/cache 一样以 labelY 为原点；用 height 只在「高度恰好等于文字高」
			// 时才对得上，标签上下留白时整条命中带会跟着上移。
			float currentY = labelY + run.y;
			float runX     = labelX + run.x;
			// hit-test 终点用 run.x + run.width（不含 xAdvances.first()，与 libGDX 一致）
			if (y > currentY || y <= currentY - h.lineH || x >= runX + run.width) continue;

			FloatSeq xAdvances = run.xAdvances;
			// xAdvances[0] 是第一个字形相对 run.x 的初始偏移（kerning/对齐）。
			// getRect 用 run.x + xAdvances.first() 作为起点；这里必须一致，
			// 否则 cursor 检测比实际字形起点早 xAdvances.first() 像素，表现为"偏左"。
			float currentX = runX + xAdvances.first();
			if (x < currentX) continue;

			for (int i = 1; i < xAdvances.size; i++) {
				// xAdvances[i] 是字形 i-1 的 advance
				if (x < currentX + xAdvances.get(i)) return start + i - 1;
				currentX += xAdvances.get(i);
			}
			return UNSET_I;
		}
		return UNSET_I;
	}

	/** 遍历指定index区域 */
	public void getRect(Point2 region, Cons<Rect> callback) {
		HitCache h         = hitCache();
		float    lineHeight = h.lineH;
		float    currentX   = 0, currentY = 0;
		float    startX     = 0, endX = 0;
		boolean  startFound = false;

		int lineStart = 0;

		Seq<GlyphRun> runs = layout.runs;
		for (int r = 0, n = runs.size; r < n; r++) {
			int start = h.runStart.get(r);
			if (start < 0) continue;
			GlyphRun run = runs.get(r);
			// 整个 run 都在选区之前：直接用缓存的下标跳过，不必扫 text
			if (!startFound && start + run.glyphs.size <= region.x) {
				lineStart = start + run.glyphs.size;
				continue;
			}
			/*
			 * xAdvances contains glyphs.size+1 entries: First entry is X offset relative to the drawing position. Subsequent entries are the X
			 * advance relative to previous glyph position. Last entry is the width of the last glyph.
			 */
			FloatSeq xAdvances = run.xAdvances;
			// run.x/y 是 layout 局部坐标（layout.setText 后不含 labelX/labelY 偏移）。
			// cache.setText(layout, labelX, labelY) 只烘焙进顶点缓冲，不回写 run.x/y。
			// Y 轴已正确加上 labelY，X 轴同理必须加上 labelX，否则水平位置整体偏移。
			currentX = labelX + run.x + xAdvances.first();
			if (startX == -1) startX = currentX;

			currentY = labelY + run.y;
			// 这里原本逐 run 用 text.charAt 对齐，改用缓存的下标
			lineStart = start;

			for (int i = 1; i < xAdvances.size; i++) {
				int j = i - 1;
				if (!startFound && lineStart + j >= region.x) {
					startX = currentX;
					startFound = true;
				}

				if (startFound) {
					// Check if the end of the region is in the same run
					if (lineStart + j >= region.y) {
						endX = currentX;
						if (!Mathf.equal(startX, endX)) {
							callback.get(Tmp.r1.set(startX, currentY - lineHeight, endX - startX, lineHeight));
						}
						return;
					}

					// If the region spans multiple lines, create a rect for this line and prepare for the next
					if (i == xAdvances.size - 1) {
						endX = currentX + xAdvances.get(i);
						if (!Mathf.equal(startX, endX)) {
							callback.get(Tmp.r1.set(startX, currentY - lineHeight, endX - startX, lineHeight));
						}
						startX = -1; // Reset startX for the next line
						break;
					}
				}

				currentX += xAdvances.get(i);
			}

			lineStart = start + run.glyphs.size;
		}

		// Handle the case where the region ends at the last character of the text.
		// startX == -1 表示最后一个 run 的末字已在上面按整行回调过（选区延续到文本末尾）：
		// 此时 currentX 是末字起点，再回调只会画出一个 x = -1 的错位矩形。
		if (startFound && startX != -1 && lineStart >= region.y) {
			endX = currentX;
			if (!Mathf.equal(startX, endX)) {
				callback.get(Tmp.r1.set(startX, currentY - lineHeight, endX - startX, lineHeight));
			}
		}
	}

	public Drawable down = Styles.flatDown, over = Styles.flatOver;
	public void draw() {
		if (HopeInput.mouseHit() == this) {
			if (!downChunk.equals(UNSET_I, UNSET_I)) {
				getRect(downChunk, r1 -> {
					Draw.color();
					down.draw(x + r1.x - padding, y + r1.y - padding, r1.width + padding * 2, r1.height + padding * 2);
				});
			} else if (!overChunk.equals(UNSET_I, UNSET_I)) {
				getRect(overChunk, r1 -> {
					Draw.color();
					over.draw(x + r1.x - padding, y + r1.y - padding, r1.width + padding * 2, r1.height + padding * 2);
				});
			}
		} else {
			// 不在这里清 overChunk：它是静态共享的，任何一个没被悬停的标签每帧都会走这里，
			// 会把刚被悬停的那个标签设好的高亮清掉。清空由 exit / mouseMoved 负责。
		}
		super.draw();
	}

	public void clearMouseEvents() {
		clicks.clear();
		hovers.clear();
	}
	/** 可点击区域，左闭右开 [x, y)：与 {@link #getRect} 的高亮区间一致（不含下标 y 本身）。
	 * <p>初始容量给小：默认容量 51 要 608 字节，而一个标签通常只注册 1~3 个区域 */
	public final ObjectMap<Prov<Point2>, Runnable> clicks = new ObjectMap<>(4);
	/** 悬停区域，同上：也是左闭右开 [x, y) */
	public final ObjectMap<Prov<Point2>, Runnable> hovers = new ObjectMap<>(4);
	/** {@link #clicks} 命中的回调：遍历完再执行，因为回调里可能重新注册区域。按需分配，通常 0~1 个 */
	private Seq<Runnable> pendingRuns;

	/** 给指定区域添加点击事件 */
	public void clickedRegion(Prov<Point2> point2Prov, Runnable runnable) {
		clicks.put(point2Prov, runnable);
	}

	public void hoveredRegion(Prov<Point2> point2Prov, Runnable runnable) {
		hovers.put(point2Prov, runnable);
	}
	{
		// clicks
		addListener(new ClickListener(KeyCode.mouseLeft) {
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				pressed = false;
				if (super.touchDown(event, x, y, pointer, button)) {
					int cursor = getCursor(x, y);
					for (var entry : clicks) {
						Point2 point2 = entry.key.get();
						if (point2.equals(UNSET_P)) continue;

						int start = point2.x, end = point2.y;
						if (start <= cursor && cursor < end) {
							InlineLabel.downChunk.set(point2);
							event.cancel();
							return true;
						}
					}
				}
				InlineLabel.downChunk.set(UNSET_P);
				return false;
			}
			public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
				super.touchUp(event, x, y, pointer, button);
				event.cancel();
				InlineLabel.downChunk.set(UNSET_P);
			}
			public void clicked(InputEvent event, float x, float y) {
				event.cancel();
				int cursor = getCursor(x, y);
				// 先收集再执行：回调里可能会 clearMouseEvents() 后重新注册区域，
				// 边遍历 ObjectMap 边改它，行为不可靠
				if (pendingRuns == null) pendingRuns = new Seq<>();
				pendingRuns.clear();
				for (var entry : clicks) {
					Point2 point2 = entry.key.get();
					if (point2.equals(UNSET_P)) continue;

					int start = point2.x, end = point2.y;
					if (start <= cursor && cursor < end) {
						pendingRuns.add(entry.value);
					}
				}
				for (int i = 0; i < pendingRuns.size; i++) pendingRuns.get(i).run();
				pendingRuns.clear();
			}
			public void exit(InputEvent event, float x, float y, int pointer, Element toActor) {
				super.exit(event, x, y, pointer, toActor);
				InlineLabel.overChunk.set(UNSET_P);
				// 直接划出整个 Label 时不会再收到未命中的 mouseMoved，这里必须自己复原手型
				Core.graphics.cursor(SystemCursor.arrow);
			}
			public boolean mouseMoved(InputEvent event, float x, float y) {
				int cursor = getCursor(x, y);
				// 命中即停：否则后面不命中的 entry 会把刚设好的手型和 overChunk 覆盖回去
				Point2 hit = null;
				if (cursor != UNSET_I) {
					for (var entry : clicks) {
						Point2 point2 = entry.key.get();
						if (point2.equals(UNSET_P)) continue;

						if (point2.x <= cursor && cursor < point2.y) {
							hit = point2;
							break;
						}
					}
				}
				if (hit != null) {
					InlineLabel.overChunk.set(hit);
					Core.graphics.cursor(SystemCursor.hand);
				} else {
					InlineLabel.overChunk.set(UNSET_P);
					Core.graphics.cursor(SystemCursor.arrow);
				}
				return super.mouseMoved(event, x, y);
			}
		});
		// hovers
		addListener(new InputListener() {
			public boolean mouseMoved(InputEvent event, float x, float y) {
				int cursor = getCursor(x, y);
				for (var entry : hovers) {
					Point2 point2 = entry.key.get();
					if (point2.equals(UNSET_P)) continue;

					int start = point2.x, end = point2.y;
					if (start <= cursor && cursor < end) {
						InlineLabel.overChunk.set(point2);
						entry.value.run();
					}
					// 不命中时不要写 overChunk：clicks 监听器已经设过，这里清掉会互相打架
				}
				return super.mouseMoved(event, x, y);
			}
		});
	}

	// endregion
}