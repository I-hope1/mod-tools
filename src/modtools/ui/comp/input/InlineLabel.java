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
import modtools.utils.ArrayUtils;

import java.util.Objects;

/**
 * 内嵌的文本
 * <p>可以对局部染色，
 * <p>可以对局部添加点击事件
 **/
public class InlineLabel extends NoMarkupLabel {
	private static final Seq<GlyphRun> result = new Seq<>();

	private static final IntSeq colorKeys = new IntSeq();
	public static final  int    UNSET_I   = -1;
	public static final  Point2 UNSET_P   = new Point2(UNSET_I, UNSET_I);
	public               float  labelX, labelY;

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
	}
	//region 文本染色
	public static Seq<GlyphRun> splitAndColorize(Seq<GlyphRun> runs, IntMap<Color> colorMap, StringBuilder text) {
		if (runs.isEmpty() || text.length() == 0) return runs;
		if (colorMap.isEmpty()) return runs;
		if (!colorMap.containsKey(0)) colorMap.put(0, Color.white);

		if (colorMap.size == 1 || (colorMap.size == 2 && Color.white.equals(colorMap.get(text.length())))) {
			Color color = colorMap.get(0);
			runs.each(r -> r.color.set(color));
			return runs;
		}

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

		var      iter = runs.iterator();
		GlyphRun item = iter.next();
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
					result.add(InlineLabel.sub(item, itemIndex, item.glyphs.size, color));
					currentIndex += size;
				} else {
					// [1, {2, 3}, 4] | []: item, {}: color
					// 仅部分item在当前颜色范围
					result.add(InlineLabel.sub(item, itemIndex, itemIndex += endIndex - currentIndex, color));
					currentIndex = endIndex;
					continue;
				}
				if (iter.hasNext()) {
					do {
						item = iter.next();
					} while (item.glyphs.isEmpty() && iter.hasNext());

					itemIndex = 0;
					// 对自动换行偏移
					while (currentIndex < text.length() && (char) item.glyphs.first().id != text.charAt(currentIndex)) {
						currentIndex++;
					}
				} else {
					itemIndex = item.glyphs.size; // ??
					break;
				}
			} while (currentIndex < endIndex);

			color = colorMap.get(endIndex);
		}
		if (itemIndex < item.glyphs.size) {
			result.add(InlineLabel.sub(item, itemIndex, item.glyphs.size, color));
		}

		result.removeAll(Objects::isNull);

		return result;
	}
	private static GlyphRun sub(GlyphRun glyphRun, int startIndex, int endIndex, Color color) {
		if (startIndex < 0) return null;
		if (endIndex <= startIndex) return null;
		GlyphRun newRun = Pools.obtain(GlyphRun.class, GlyphRun::new);
		boolean  isSame = startIndex == 0 && endIndex == glyphRun.glyphs.size;

		newRun.y = glyphRun.y;
		newRun.x = glyphRun.x + (isSame ? 0 : ArrayUtils.sumf(glyphRun.xAdvances, 0, startIndex));
		newRun.xAdvances.addAll(glyphRun.xAdvances, startIndex, endIndex - startIndex + 1);
		newRun.glyphs.addAll(glyphRun.glyphs, startIndex, Math.max(0, endIndex - startIndex));
		newRun.width = isSame ? glyphRun.width : ArrayUtils.sumf(glyphRun.xAdvances, startIndex + 1, endIndex + 1);
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

		var newRuns = splitAndColorize(layout.runs, colorMap, text);
		if (newRuns != layout.runs) {
			Pools.freeAll(layout.runs, true);
			layout.runs.clear();
			layout.runs.addAll(newRuns);
			// Log.info(layout);
		}
		cache.setText(layout, x, y);
		// Pools.freeAll(layout.runs);

		if (fontScaleChanged) font.getData().setScale(oldScaleX, oldScaleY);
	}
	public final IntMap<Color> colorMap = new IntMap<>() {
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

	/** 获取(x, y)对应的index */
	public int getCursor(float x, float y) {
		float lineHeight = style.font.getLineHeight();
		float currentX, currentY; // 指文字左上角的坐标

		int index = 0; // 用于跟踪字符索引

		for (GlyphRun run : layout.runs) {
			if (run.glyphs.isEmpty()) continue; // 空 run 直接跳过，否则 first() 越界
			FloatSeq xAdvances = run.xAdvances;
			// xAdvances[0] 是第一个字形相对 run.x 的初始偏移（kerning/对齐）。
			// getRect 用 run.x + xAdvances.first() 作为起点；这里必须一致，
			// 否则 cursor 检测比实际字形起点早 xAdvances.first() 像素，表现为"偏左"。
			currentX = labelX + run.x + xAdvances.first();
			currentY = height + run.y;
			while (index < text.length() && (char) run.glyphs.first().id != text.charAt(index)) index++; // 弥补offset
			// hit-test 终点用 run.x + run.width（不含 xAdvances.first()，与 libGDX 一致）
			if (currentY - lineHeight < y && y <= currentY && currentX <= x && x < labelX + run.x + run.width) {
				for (int i = 1; i < xAdvances.size; i++) {
					// xAdvances[i] 是字形 i-1 的 advance
					if (x < currentX + xAdvances.get(i)) {
						return index + i - 1;
					}
					currentX += xAdvances.get(i);
				}
				return UNSET_I;
			}
			index += run.glyphs.size;
		}
		return UNSET_I;
	}

/** 遍历指定index区域 */
	public void getRect(Point2 region, Cons<Rect> callback) {
		float   lineHeight = style.font.getLineHeight();
		float   currentX   = 0, currentY = 0;
		float   startX     = 0, endX = 0;
		boolean startFound = false;

		int lineStart = 0;

		for (GlyphRun run : layout.runs) {
			if (run.glyphs.isEmpty()) continue;
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
			while (lineStart < text.length() && (char) run.glyphs.first().id != text.charAt(lineStart)) lineStart++; // 弥补offset

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

			lineStart += run.glyphs.size;
		}

		// Handle the case where the region ends at the last character of the text
		if (startFound && lineStart >= region.y) {
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
			InlineLabel.overChunk.set(UNSET_P);
		}
		super.draw();
	}
	public final ObjectMap<Prov<Point2>, Runnable> clicks = new ObjectMap<>();
	public final ObjectMap<Prov<Point2>, Runnable> hovers = new ObjectMap<>();

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
						if (start <= cursor && cursor <= end) {
							InlineLabel.downChunk.set(point2);
							return true;
						}
					}
				}
				InlineLabel.downChunk.set(UNSET_P);
				return false;
			}
			public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
				super.touchUp(event, x, y, pointer, button);
				InlineLabel.downChunk.set(UNSET_P);
			}
			public void clicked(InputEvent event, float x, float y) {
				int cursor = getCursor(x, y);
				for (var entry : clicks) {
					Point2 point2 = entry.key.get();
					if (point2.equals(UNSET_P)) continue;

					int start = point2.x, end = point2.y;
					if (start <= cursor && cursor <= end) {
						entry.value.run();
					}
				}
			}
			public void exit(InputEvent event, float x, float y, int pointer, Element toActor) {
				super.exit(event, x, y, pointer, toActor);
				InlineLabel.overChunk.set(UNSET_P);
			}
			public boolean mouseMoved(InputEvent event, float x, float y) {
				int cursor = getCursor(x, y);
				for (var entry : clicks) {
					Point2 point2 = entry.key.get();
					if (point2.equals(UNSET_P)) continue;

					int start = point2.x, end = point2.y;
					if (start <= cursor && cursor <= end) {
						InlineLabel.overChunk.set(point2);
						Core.graphics.cursor(SystemCursor.hand);
					} else {
						InlineLabel.overChunk.set(UNSET_P);
						Core.graphics.cursor(SystemCursor.arrow);
					}
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
					if (start <= cursor && cursor <= end) {
						InlineLabel.overChunk.set(point2);
						entry.value.run();
					} else {
						InlineLabel.overChunk.set(UNSET_P);
					}
				}
				return super.mouseMoved(event, x, y);
			}
		});
	}

	// endregion
}