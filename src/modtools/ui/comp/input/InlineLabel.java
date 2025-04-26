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

		if (colorMap.size == 1 || (colorMap.size == 2 && colorMap.get(text.length()) == Color.white)) {
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
					} while (item.glyphs.isEmpty());

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

	// region Clickable

	private static final Point2 overChunk = new Point2(UNSET_I, UNSET_I);
	private static final Point2 downChunk = new Point2(UNSET_I, UNSET_I);
	private static final int    padding   = 4;

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
				return UNSET_I;
			}
			index += run.glyphs.size;
		}
		return UNSET_I;
	}

	/** 遍历指定index区域 */
	// Assuming 'text' (the original text string used to create the layout)
	// and 'labelY' (the base Y coordinate for the text) are fields accessible
	// within the class containing this getRect method.
	public void getRect(Point2 region, Cons<Rect> callback) {
		// 获取行高
		float lineHeight = style.font.getLineHeight();
		// 当前光标的屏幕X、Y坐标
		float currentX = 0, currentY = 0;
		// 选区起始和结束的屏幕X坐标
		float startX = 0, endX = 0;
		// 标记是否已找到选区起始位置
		boolean startFound = false;

		// 追踪当前处理到的字符在原始文本字符串 `text` 中的索引。
		// **注意：这是代码中最不确定也是最可能出错的地方。**
		// 这个索引尝试与当前处理的 GlyphRun 对齐，但在换行、截断、移除空白等布局处理后，
		// Glyph序列与原始文本索引的对应关系可能不连续或不简单。
		int currentCharOriginalIndex = 0;

		// 获取整个布局的基线Y坐标（或顶部Y坐标，取决于labelY的定义）
		// run.y 是相对于布局基线的偏移，所以 currentY = labelY + run.y 是 glyph 的基线Y坐标。
		// 矩形需要从顶部开始，所以 Y = currentY - lineHeight。
		currentY = labelY; // 初始化 currentY，后续会在循环中根据 run.y 调整

		// 遍历所有的 GlyphRun
		for (GlyphRun run : layout.runs) {
			if (run.glyphs.isEmpty()) {
				// 如果一个 Run 是空的 (例如，颜色标记之间没有文本，或者纯粹由被移除的空白字符构成)，
				// 它可能仍然对应于原始文本中的一些字符（如换行符、标记字符或被跳过的空白）。
				// 简单地跳过它，并且不更新 currentCharOriginalIndex，意味着我们假设这些字符不影响 region 的索引判断。
				// 这在 region.x 或 region.y 落在这些被跳过的字符范围内时会导致错误。
				// 但在不修改 GlyphLayout 结构的情况下，很难确定这些空 Run 对应多少原始字符。
				// 保持与原代码相同的行为：跳过空 Run。
				continue;
			}

			// --- 潜在的问题区域：尝试将 currentCharOriginalIndex 对齐到当前 Run 在原始文本中的起始位置 ---
			// 这个 while 循环试图跳过原始文本中，从上一个 Run 结束位置到当前 Run 第一个 glyph
			// 对应的原始字符之间的所有字符（例如换行符、颜色标记字符、被移除的前导空白等）。
			// 它通过查找第一个 glyph 的 ID 在原始文本中的位置来实现。
			// 如之前讨论，这种查找方式在复杂布局下是不可靠的，可能导致 currentCharOriginalIndex 错误。
			// 然而，在没有 GlyphLayout 提供更精确映射信息的情况下，这是原代码唯一尝试对齐 offset 的机制。
			// 我们保留它，但标记为潜在错误源。
			while (currentCharOriginalIndex < text.length() && (char) run.glyphs.first().id != text.charAt(currentCharOriginalIndex)) {
				// 添加一个边界检查，防止极端情况下无限循环或越界（理论上不应该发生如果布局是有效的）
				if (currentCharOriginalIndex >= text.length()) break;
				currentCharOriginalIndex++; // 跳过在 text 中，但似乎不对应到当前 run 的 glyphs 的字符
			}
			// --- 潜在的问题区域结束 ---

			// 计算当前 Run 在屏幕上的基线Y坐标
			// run.y 是相对于布局整体Y坐标（labelY）的偏移
			currentY = labelY + run.y;

			// 计算 Run 中第一个 glyph 的屏幕起始X坐标 (Run X + 第一个 glyph 的 X 偏移)
			// xAdvances.first() 是第一个 glyph 相对于 run.x 的 X 偏移
			float runStartScreenX = run.x + run.xAdvances.first();

			// currentX 在循环中将追踪当前处理的 glyph 的屏幕起始X坐标
			currentX = runStartScreenX;

			// 遍历当前 Run 中的所有 glyphs。
			// xAdvances 序列有 glyphs.size + 1 个元素。
			// xAdvances[0] 是第一个 glyph 的 X 偏移。
			// xAdvances[1] 到 xAdvances[glyphs.size - 1] 是 glyphs[0] 到 glyphs[glyphs.size - 2] 之后的 Advance。
			// xAdvances[glyphs.size] 是最后一个 glyph (glyphs[glyphs.size - 1]) 的宽度。
			// 我们需要遍历 glyph 索引 `j` 从 0 到 `run.glyphs.size - 1`。
			// 对于每个 glyph `j`，其起始X是当前的 `currentX`，其结束X是 `currentX + (advance or width)`。

			for (int j = 0; j < run.glyphs.size; j++) {
				// 假设当前 glyph `j` 对应的原始文本字符索引是 `currentCharOriginalIndex + j`。
				// **注意：这个简单的加法假设 Run 中的 glyphs 与原始文本中的字符是 1:1 连续对应的，**
				// **这也是一个潜在的不准确来源，特别是对于连字或更复杂的文本整形。**
				int currentCharacterIndex = currentCharOriginalIndex + j;

				// `currentX` 在当前迭代开始时，是 glyph `j` 的屏幕起始 X 坐标。
				float glyphStartX = currentX;

				// 计算当前 glyph `j` 结束时的屏幕 X 坐标。
				float glyphEndX;
				if (j < run.glyphs.size - 1) {
					// 如果不是最后一个 glyph，使用 xAdvances 中的对应 Advance 来计算下一个 glyph 的起始位置。
					// xAdvances[j+1] 是 glyph `j` 之后的 Advance。
					glyphEndX = currentX + run.xAdvances.get(j + 1);
				} else {
					// 如果是最后一个 glyph，使用 xAdvances 中的最后一个值，它是最后一个 glyph 的宽度。
					// xAdvances[run.xAdvances.size - 1] 就是 xAdvances[glyphs.size]。
					glyphEndX = currentX + run.xAdvances.get(run.xAdvances.size - 1);
				}

				// --- 检查选区起始位置 ---
				// 如果还没有找到选区起始，并且当前字符索引 `currentCharacterIndex`
				// 达到了或超过了选区起始索引 `region.x`。
				if (!startFound && currentCharacterIndex >= region.x) {
					// 选区在当前 Run 的当前 glyph 或其对应的字符处开始。
					// 记录选区在此行上的起始屏幕 X 坐标。
					// 理论上，如果 region.x 落在 glyph j 对应的字符范围内，起始X应该在 glyphStartX 和 glyphEndX 之间。
					// 但根据原代码逻辑以及简化处理，我们取当前 glyph 的起始 X 作为段落的起始 X。
					startX = glyphStartX;
					startFound = true;
				}

				// --- 检查选区结束位置，并生成矩形 ---
				// 如果已经找到了选区起始 (`startFound` 为 true)
				if (startFound) {
					// 检查当前字符索引 `currentCharacterIndex` 是否达到了或超过了选区结束索引 `region.y`。
					// **注意：region.y 通常表示选区结束位置的“后一个”字符索引，或者选区包含 region.y 处的字符。**
					// **原代码的判断是 `offset + j >= region.y`，这暗示 region.y 指向选区结束的“后一个”字符。**
					// **当 `currentCharacterIndex` 第一次 >= region.y 时，表示选区到此结束（不包含当前字符）。**
					if (currentCharacterIndex >= region.y) {
						// 选区在此 Run 的当前 glyph 或其对应的字符处结束（不包含当前字符）。
						// 记录选区在此行上的结束屏幕 X 坐标。
						// 结束位置应该是当前 glyph 的起始 X。
						endX = glyphStartX;
						// 只有当起始和结束X不同（即选区有宽度）时才生成矩形。
						if (endX > startX) { // 使用 > 0 判断宽度更清晰
							// 创建并回调矩形：(起始X, 矩形顶部Y, 宽度, 高度)
							// 矩形顶部Y = 基线Y - 行高
							callback.get(Tmp.r1.set(startX, currentY - lineHeight, endX - startX, lineHeight));
						}
						// 选区结束位置已找到，后续的 glyph 和 Run 不再属于此选区。
						// 立即返回，结束方法执行。
						return;
					}

					// 如果还没有达到选区结束 (currentCharacterIndex < region.y)，但当前已处理到 Run 的最后一个 glyph...
					if (j == run.glyphs.size - 1) {
						// 这意味着选区跨越多行。当前 Run 构成了选区在当前行的最后一部分。
						// 生成一个覆盖从 `startX` 到当前 Run 结束位置的矩形。
						endX = glyphEndX; // 结束X是最后一个 glyph 的结束X
						if (endX > startX) { // 只有当起始和结束X不同时才生成矩形。
							callback.get(Tmp.r1.set(startX, currentY - lineHeight, endX - startX, lineHeight));
						}
						// 选区将在下一行继续。`startX` 需要为下一行重置，但这个重置会在下一轮外层循环开始处理新的 Run 时隐含地发生，
						// 因为 `startX` 只在 `startFound` 为 false 时被设置。如果选区跨行，`startFound` 保持为 true，
						// 下一个 Run 的第一个满足 `characterIndex >= region.x` 条件的 glyph 会更新 `startX`（虽然这个条件已经恒为真）。
						// **原代码在此处有个 `startX = run.x;` 的重置，这看起来是错误的，因为它将下一行的起始X设置为当前 Run 的起始X。**
						// **我们移除这个错误的重置，让 `startX` 自然地在下一行找到第一个 relevant glyph 时被重新捕获。**
					}
				}

				// 移动 currentX 到下一个 glyph 的起始位置（当前 glyph 的结束位置）
				currentX = glyphEndX;

			} // 内层循环结束：处理完当前 Run 的所有 glyphs

			// 在处理完 Run 的所有 glyphs 后，需要推进 currentCharOriginalIndex，使其指向下一个 Run 在原始文本中可能开始的位置。
			// **原代码简单地 `offset += run.glyphs.size;` 这是不准确的，因为它假设每个 glyph 对应一个原始字符。**
			// **同样，在不修改 GlyphLayout 的情况下，精确推进这个索引是困难的。**
			// **我们依赖外层循环开始时那个有问题的 `while` 循环来尝试定位下一个 Run 的起始原始文本索引。**
			// **因此，此处移除原有的 `offset += run.glyphs.size;`。**

		} // 外层循环结束：处理完所有 Runs

		// --- 处理选区结束正好在文本末尾的情况 ---
		// 当所有 Runs 处理完毕，但 `region.y` 还没有被达到（即 `region.y` 等于或大于原始文本的总有效字符数）时。
		// 且选区起始已被找到 (`startFound` 为 true)。
		// `currentCharOriginalIndex` 此时应该大致（如果之前的同步和计数是准确的）等于处理过的原始字符总数。
		// `currentX` 此时应该是在最后一个 Run 处理完毕后的位置，即整个文本布局的视觉结束 X 坐标。
		// 如果 `region.y` 等于或超过了这个总数，选区包含到文本的最后一个字符（或到文本末尾）。
		// 选区在视觉上延伸到整个文本布局的末尾。
		if (startFound && currentCharOriginalIndex >= region.y) {
			endX = currentX; // 选区结束X是整个文本的视觉结束X
			if (endX > startX) { // 生成最后一个矩形
				callback.get(Tmp.r1.set(startX, currentY - lineHeight, endX - startX, lineHeight));
			}
			// 不需要返回，方法自然结束。
		}

		// 方法执行完毕，所有属于 region 范围的矩形都已经通过 callback 返回。
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
				InlineLabel.overChunk.set(UNSET_I, UNSET_I);
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
						InlineLabel.overChunk.set(UNSET_I, UNSET_I);
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