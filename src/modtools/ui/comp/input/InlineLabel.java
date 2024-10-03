package modtools.ui.comp.input;

import arc.Core;
import arc.Graphics.Cursor.SystemCursor;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.graphics.g2d.GlyphLayout.GlyphRun;
import arc.input.KeyCode;
import arc.math.geom.*;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.style.Drawable;
import arc.struct.*;
import arc.struct.IntMap.Keys;
import arc.util.*;
import arc.util.pooling.Pools;
import mindustry.ui.Styles;
import modtools.ui.control.HopeInput;
import modtools.utils.ArrayUtils;

import static modtools.utils.Tools.or;

public class InlineLabel extends NoMarkupLabel {
	private static final Seq<GlyphRun> result = new Seq<>();
	private static final IntSeq        colorKeys = new IntSeq();
	public static final int UNSET = -1;

	public InlineLabel(CharSequence text) {
		super(text);
	}
	public InlineLabel(CharSequence text, LabelStyle style) {
		super(text, style);
	}
	public InlineLabel(Prov<CharSequence> sup) {
		super(sup);
	}

	public static Seq<GlyphRun> splitAndColorize(Seq<GlyphRun> runs, IntMap<Color> colorMap, StringBuilder text) {
		if (runs.isEmpty() || text.length() == 0) return runs;
		if (colorMap.isEmpty()) return runs;
		if (colorMap.size == 1 || (colorMap.size == 2 && colorMap.get(text.length()) == Color.white)) {
			Color color = or(colorMap.get(0), Color.white);
			runs.each(r -> r.color.set(color));
			return runs;
		}
		if (!colorMap.containsKey(0)) colorMap.put(0, Color.white);

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
					do item = iter.next(); while (item.glyphs.isEmpty());

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

		return result;
	}
	private static GlyphRun sub(GlyphRun glyphRun, int startIndex, int endIndex, Color color) {
		GlyphRun newRun = Pools.obtain(GlyphRun.class, GlyphRun::new);
		boolean  isSame = startIndex == 0 && endIndex == glyphRun.glyphs.size;

		newRun.y = glyphRun.y;
		newRun.x = glyphRun.x + (isSame ? 0 : ArrayUtils.sumf(glyphRun.xAdvances, 0, startIndex));
		newRun.xAdvances.addAll(glyphRun.xAdvances, startIndex, endIndex - startIndex + 1);
		newRun.glyphs.addAll(glyphRun.glyphs, startIndex, endIndex - startIndex);
		newRun.width = isSame ? glyphRun.width : ArrayUtils.sumf(glyphRun.xAdvances, startIndex + 1, endIndex + 1);
		newRun.color.set(color);
		return newRun;
	}
	/** 获取(x, y)对应的index */
	public int getCursor(float x, float y) {
		float lineHeight = style.font.getLineHeight();
		float currentX, currentY; // 指文字左上角的坐标

		int index = 0; // 用于跟踪字符索引

		for (GlyphRun run : layout.runs) {
			FloatSeq xAdvances = run.xAdvances;
			currentX = run.x;
			currentY = height + run.y;
			while (index < text.length() && (char) run.glyphs.first().id != text.charAt(index)) index++; // 弥补offset
			// 判断是否在行
			if (currentY - lineHeight < y && y <= currentY && currentX <= x && x < currentX + run.width) {
				for (int i = 1; i < xAdvances.size; i++) {
					// 判断是否在当前字符范围内
					if (x < currentX + xAdvances.get(i)) {
						return index + i - 1;
					}
					currentX += xAdvances.get(i);
				}
				return UNSET;
			}
			index += run.glyphs.size;
		}
		return UNSET;
	}

	/** 遍历指定index区域 */
	public void getRect(Point2 region, Cons<Rect> callback) {
		float   lineHeight = style.font.getLineHeight();
		float   currentX   = 0, currentY = 0;
		float   startX     = 0, endX = 0;
		boolean startFound = false;

		int offset = 0;

		for (GlyphRun run : layout.runs) {
			if (run.glyphs.isEmpty()) continue;
			FloatSeq xAdvances = run.xAdvances;
			currentX = run.x;
			currentY = height + run.y;
			while (offset < text.length() && (char) run.glyphs.first().id != text.charAt(offset)) offset++; // 弥补offset

			for (int i = 0; i < xAdvances.size; i++) {
				if (!startFound && offset + i >= region.x) {
					startX = currentX;
					startFound = true;
				}

				if (startFound) {
					// Check if the end of the region is in the same run
					if (offset + i - 1 >= region.y) {
						endX = currentX;
						if (endX != startX) { callback.get(Tmp.r1.set(startX, currentY - lineHeight, endX - startX, lineHeight)); }
						return;
					}

					// If the region spans multiple lines, create a rect for this line and prepare for the next
					if (i == xAdvances.size - 1) {
						endX = currentX + xAdvances.get(i);
						if (endX != startX) { callback.get(Tmp.r1.set(startX, currentY - lineHeight, endX - startX, lineHeight)); }
						startX = run.x; // Reset startX for the next line
					}
				}

				currentX += xAdvances.get(i);
			}

			offset += run.glyphs.size;
		}

		// Handle the case where the region ends at the last character of the text
		if (startFound && offset >= region.y) {
			endX = currentX;
			if (endX != startX) { callback.get(Tmp.r1.set(startX, currentY - lineHeight, endX - startX, lineHeight)); }
		}
	}


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

		layout.setText(font, text, 0, text.length(), Color.white, textWidth, lineAlign, wrap, ellipsis);

		var newRuns = InlineLabel.splitAndColorize(layout.runs, colorMap, text);
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
	public void clear() {
		super.clear();
		cache.clear();
	}


	private static final Point2 overChunk = new Point2(UNSET, UNSET);
	private static final Point2 downChunk = new Point2(UNSET, UNSET);
	private static final int    padding   = 4;
	public void draw() {
		if (HopeInput.mouseHit() == this) {
			if (!downChunk.equals(UNSET, UNSET)) {
				getRect(downChunk, r1 -> {
					Draw.color();
					Styles.flatDown.draw(x + r1.x - padding, y + r1.y - padding, r1.width + padding * 2, r1.height + padding * 2);
				});
			} else if (!overChunk.equals(UNSET, UNSET)) {
				getRect(overChunk, r1 -> {
					Draw.color();
					Styles.flatOver.draw(x + r1.x - padding, y + r1.y - padding, r1.width + padding * 2, r1.height + padding * 2);
				});
			}
		}
		super.draw();
	}
	/** 给指定区域添加点击事件 */
	public void clickedRegion(Prov<Point2> point2Prov, Runnable runnable) {
		addListener(new ClickListener(KeyCode.mouseLeft) {
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				pressed = false;
				if (super.touchDown(event, x, y, pointer, button)) {
					int    cursor = getCursor(x, y);
					Point2 point2 = point2Prov.get();
					int    start  = point2.x, end = point2.y;
					if (start <= cursor && cursor <= end) {
						InlineLabel.downChunk.set(point2);
						return true;
					}
				}
				InlineLabel.downChunk.set(UNSET, UNSET);
				return false;
			}
			public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
				super.touchUp(event, x, y, pointer, button);
				InlineLabel.downChunk.set(UNSET, UNSET);
			}
			public void clicked(InputEvent event, float x, float y) {
				int    cursor = getCursor(x, y);
				Point2 point2 = point2Prov.get();
				int    start  = point2.x, end = point2.y;
				if (start <= cursor && cursor <= end) {
					runnable.run();
				}
			}
			public void exit(InputEvent event, float x, float y, int pointer, Element toActor) {
				super.exit(event, x, y, pointer, toActor);
				InlineLabel.overChunk.set(UNSET, UNSET);
			}
			public boolean mouseMoved(InputEvent event, float x, float y) {
				int    cursor = getCursor(x, y);
				Point2 point2 = point2Prov.get();
				int    start  = point2.x, end = point2.y;
				if (start <= cursor && cursor <= end) {
					InlineLabel.overChunk.set(point2);
					Core.graphics.cursor(SystemCursor.hand);
				} else {
					InlineLabel.overChunk.set(UNSET, UNSET);
					Core.graphics.cursor(SystemCursor.arrow);
				}
				return super.mouseMoved(event, x, y);
			}
		});
	}
	protected final IntMap<Color> colorMap = new IntMap<>() {
		/* public Color put(int key, Color value) {
			// return super.put(key, value);
			return null;
		} */
	};
}
