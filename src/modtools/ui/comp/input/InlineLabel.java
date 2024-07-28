package modtools.ui.comp.input;

import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.graphics.g2d.GlyphLayout.GlyphRun;
import arc.scene.style.Drawable;
import arc.struct.*;
import arc.struct.IntMap.Keys;
import arc.util.Align;
import arc.util.pooling.Pools;
import modtools.utils.ArrayUtils;

public class InlineLabel extends NoMarkupLabel {
	private static final Seq<GlyphRun> result    = new Seq<>();
	private static final IntSeq        colorKeys = new IntSeq();

	public InlineLabel(CharSequence text) {
		super(text);
	}

	public static Seq<GlyphRun> splitAndColorize(Seq<GlyphRun> runs, IntMap<Color> colorMap, StringBuilder text) {
		if (runs.isEmpty() || text.length() == 0) return runs;
		if (colorMap.size == 2 && colorMap.get(text.length()) == Color.white) {
			Color color = colorMap.get(0);
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
		Color color        = Color.white;
		int   startIndex   = colorKeys.first();
		int   currentIndex = 0;

		var      iter = runs.iterator();
		GlyphRun item = iter.next();
		for (int i = 1; i < colorKeys.size; i++) {
			int endIndex = colorKeys.get(i);
			if (startIndex == endIndex) continue;

			do {
				while (currentIndex < text.length() && (char) item.glyphs.first().id != text.charAt(currentIndex)) {
					currentIndex++;
				}
				if (currentIndex + item.glyphs.size <= endIndex) {
					// The whole item fits within the current color range
					result.add(InlineLabel.sub(item, 0, item.glyphs.size, color));
					currentIndex += item.glyphs.size;
					if (iter.hasNext()) item = iter.next();
				} else {
					// Only part of the item fits within the current color range
					int splitIndex = endIndex - currentIndex;
					result.add(InlineLabel.sub(item, 0, splitIndex, color));
					item = InlineLabel.sub(item, splitIndex, item.glyphs.size, colorMap.get(colorKeys.get(i)));
					currentIndex = endIndex;
				}
			} while (currentIndex < endIndex && iter.hasNext());

			startIndex = endIndex;
			color = colorMap.get(colorKeys.get(i));
		}
		// Add the remaining part of the last run
		if (currentIndex < text.length()) {
			result.add(InlineLabel.sub(item, 0, text.length() - currentIndex, color));
		}

		return result;
	}
	private static GlyphRun sub(GlyphRun glyphRun, int startIndex, int endIndex, Color color) {
		endIndex = Math.min(endIndex, glyphRun.glyphs.size);
		GlyphRun newRun = Pools.get(GlyphRun.class, GlyphRun::new).obtain();

		newRun.y = glyphRun.y;
		newRun.x = glyphRun.x + ArrayUtils.sumf(glyphRun.xAdvances, 0, startIndex);
		newRun.xAdvances.addAll(glyphRun.xAdvances, startIndex, endIndex - startIndex + 1);
		newRun.glyphs.addAll(glyphRun.glyphs, startIndex, endIndex - startIndex);
		newRun.width = ArrayUtils.sumf(glyphRun.xAdvances, startIndex, endIndex + 1);
		newRun.color.set(color);
		return newRun;
	}
	/**
	 * 获取(x, y)对应的index
	 */
	public int getCursor(float x, float y) {
		var   runs       = layout.runs;
		float lineHeight = style.font.getLineHeight();
		float currentX, currentY; // 指文字左上角的坐标

		int index = 0; // 用于跟踪字符索引

		for (GlyphRun run : runs) {
			if (run.glyphs.isEmpty()) continue;
			FloatSeq xAdvances = run.xAdvances;
			currentX = run.x;
			currentY = height + run.y;
			while (index < text.length() && (char) run.glyphs.first().id != text.charAt(index)) index++; // 弥补offset
			// 判断是否在行
			if (Math.abs(currentY - y) < lineHeight && currentX + run.width > x) {
				for (int i = 0; i < run.glyphs.size; i++) {
					// 判断是否在当前字符范围内
					if (x >= currentX && x < currentX + xAdvances.get(i)) {
						return index + i - 1;
					}
					currentX += xAdvances.get(i);
				}
			}
			index += run.glyphs.size;
		}
		return -1;
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
				if ((labelAlign & Align.right) != 0)
					x += width - textWidth;
				else
					x += (width - textWidth) / 2;
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
			layout.runs.clear();
			layout.runs.addAll(newRuns);
		}
		cache.setText(layout, x, y);
		if (fontScaleChanged) font.getData().setScale(oldScaleX, oldScaleY);
	}

	protected final IntMap<Color> colorMap = new IntMap<>();
}
