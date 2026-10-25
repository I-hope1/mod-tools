package modtools.ui.comp.input;

import arc.graphics.Color;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.Font.FontData;
import arc.graphics.g2d.GlyphLayout;
import arc.graphics.g2d.GlyphLayout.GlyphRun;
import arc.graphics.g2d.TextureRegion;
import arc.scene.ui.Label.LabelStyle;
import arc.struct.*;

/**
 * 验证「把每个 run 的起始字符索引缓存起来」相对现在的逐 run 追赶循环值不值。
 * <p>对照的两种实现：
 * <ul>
 *   <li>{@code old}：直接调真实的 {@link InlineLabel#getCursor}，每个 run 都跑一次
 *       {@code while (index < text.length() && first != text.charAt(index)) index++}；</li>
 *   <li>{@code new}：{@link #rebuildRunIndex} 预算一次，之后每个 run 只读数组；
 *       同时把 y 带判断提到最前面。</li>
 * </ul>
 * 字形是人工构造的（每字 advance = 10，lineHeight = 20），不碰贴图 / GL。
 */
public class CursorBench {
	static final float ADV   = 10f;
	static final float LINE  = 20f;
	static final float CAP   = 20f;
	static final int   UNSET = InlineLabel.UNSET_I;

	/* ---------------- 造数据 ---------------- */

	static Font font() {
		FontData fd = new FontData();
		fd.lineHeight = LINE;
		fd.capHeight = CAP;
		fd.down = LINE;
		return new Font(fd, Seq.with(new TextureRegion()), false);
	}

	/** nRuns 个 run，每个 runLen 个字，一行放 perLine 个 */
	static class Case {
		Seq<GlyphRun> runs;
		StringBuilder   text;
		IntSeq        starts;
		float           labelX, labelY, lineHeight;

		int    width;   // 文本总宽（单行）
		int    lines;   // 行数
	}

	static Case make(int nRuns, int runLen, int perLine) {
		Case c = new Case();
		c.runs = new Seq<>();
		c.text = new StringBuilder();
		c.lineHeight = LINE;
		c.labelX = 0;
		c.labelY = 0;   // 第一行 y=0，往下是负的

		float x = 0, y = 0;
		int   onLine = 0, maxWidth = 0;
		char  ch = 'a';
		for (int r = 0; r < nRuns; r++) {
			GlyphRun run = new GlyphRun();
			for (int i = 0; i < runLen; i++) {
				Font.Glyph g = new Font.Glyph();
				g.id = ch;
				g.xadvance = (int) ADV;
				run.glyphs.add(g);
				c.text.append(ch);
				ch = ch == 'z' ? 'a' : (char) (ch + 1);
			}
			run.xAdvances.add(0f);
			for (int i = 0; i < runLen; i++) run.xAdvances.add(ADV);
			run.width = runLen * ADV;
			run.x = x;
			run.y = y;
			c.runs.add(run);

			x += runLen * ADV;
			if (++onLine == perLine) {
				maxWidth = Math.max(maxWidth, (int) x);
				onLine = 0;
				x = 0;
				y -= LINE;
				c.lines++;
			}
		}
		if (onLine != 0) {
			maxWidth = Math.max(maxWidth, (int) x);
			c.lines++;
		}
		c.width = maxWidth;
		c.starts = new IntSeq();
		rebuildRunIndex(c.runs, c.text, c.starts);
		return c;
	}

	/** 提案里的 rebuildRunIndex：逐 run 跑一次追赶循环 */
	static void rebuildRunIndex(Seq<GlyphRun> runs, StringBuilder text, IntSeq out) {
		out.clear();
		int index = 0;
		for (int r = 0, n = runs.size; r < n; r++) {
			GlyphRun run = runs.get(r);
			if (run.glyphs.isEmpty()) {
				out.add(-1);
				continue;
			}
			char first = (char) run.glyphs.first().id;
			while (index < text.length() && first != text.charAt(index)) index++;
			out.add(index);
			index += run.glyphs.size;
		}
	}

	/* ---------------- 两种实现 ---------------- */

	/** 现在的实现（与 InlineLabel.getCursor 逐行一致，只是把字段拆成参数） */
	static int oldCursor(Seq<GlyphRun> runs, StringBuilder text,
	                     float labelX, float labelY, float lineHeight, float x, float y) {
		float currentX, currentY;
		int   index = 0;
		for (int r = 0, n = runs.size; r < n; r++) {
			GlyphRun run = runs.get(r);
			if (run.glyphs.isEmpty()) continue;
			FloatSeq xAdvances = run.xAdvances;
			currentX = labelX + run.x + xAdvances.first();
			currentY = labelY + run.y;
			while (index < text.length() && (char) run.glyphs.first().id != text.charAt(index)) index++;
			if (currentY - lineHeight < y && y <= currentY
			    && currentX <= x && x < labelX + run.x + run.width) {
				for (int i = 1; i < xAdvances.size; i++) {
					if (x < currentX + xAdvances.get(i)) return index + i - 1;
					currentX += xAdvances.get(i);
				}
				return UNSET;
			}
			index += run.glyphs.size;
		}
		return UNSET;
	}

	/** 提案：y 带先筛、索引查缓存 */
	static int newCursor(Seq<GlyphRun> runs, IntSeq starts,
	                     float labelX, float labelY, float lineHeight, float x, float y) {
		for (int r = 0, n = runs.size; r < n; r++) {
			GlyphRun run = runs.get(r);
			if (run.glyphs.isEmpty()) continue;

			float currentY = labelY + run.y;
			if (y > currentY || y <= currentY - lineHeight) continue;

			float runX = labelX + run.x;
			if (x >= runX + run.width) continue;

			FloatSeq xAdvances = run.xAdvances;
			float    currentX  = runX + xAdvances.first();
			if (x < currentX) continue;

			int index = starts.get(r);
			for (int i = 1; i < xAdvances.size; i++) {
				if (x < currentX + xAdvances.get(i)) return index + i - 1;
				currentX += xAdvances.get(i);
			}
			return UNSET;
		}
		return UNSET;
	}

	/**
	 * 只改判断顺序、不加任何缓存：把 y 带 / x 区间测试提到前面，追赶循环挪进命中分支。
	 * <p>非命中 run 只做「一次 y 比较 + 一次 x 比较 + index 累加」，
	 * 不再读 {@code xAdvances.first()}、也不再 {@code text.charAt}。
	 */
	static int reordered(Seq<GlyphRun> runs, StringBuilder text,
	                     float labelX, float labelY, float lineHeight, float x, float y) {
		int index = 0;
		for (int r = 0, n = runs.size; r < n; r++) {
			GlyphRun run = runs.get(r);
			if (run.glyphs.isEmpty()) continue;

			float currentY = labelY + run.y;
			float runX     = labelX + run.x;
			if (y > currentY || y <= currentY - lineHeight
			    || x >= runX + run.width) {
				index += run.glyphs.size;
				continue;
			}

			FloatSeq xAdvances = run.xAdvances;
			float    currentX  = runX + xAdvances.first();
			if (x < currentX) {
				index += run.glyphs.size;
				continue;
			}

			// 只有可能命中的这一段才需要把 index 对齐到文本位置
			while (index < text.length() && (char) run.glyphs.first().id != text.charAt(index)) index++;
			for (int i = 1; i < xAdvances.size; i++) {
				if (x < currentX + xAdvances.get(i)) return index + i - 1;
				currentX += xAdvances.get(i);
			}
			return UNSET;
		}
		return UNSET;
	}

	/* ---------------- 正确性 ---------------- */

	static boolean check() {
		for (int[] spec : new int[][]{{10, 3, 10}, {100, 3, 20}, {100, 1, 7}, {37, 4, 5}}) {
			Case c = make(spec[0], spec[1], spec[2]);
			for (int li = 0; li < c.lines; li++) {
				float y = -li * LINE - 1;
				for (int x = -2; x < c.width + 2; x++) {
					int a = oldCursor(c.runs, c.text, c.labelX, c.labelY, LINE, x, y);
					int b = newCursor(c.runs, c.starts, c.labelX, c.labelY, LINE, x, y);
					int d = reordered(c.runs, c.text, c.labelX, c.labelY, LINE, x, y);
					if (a != b || a != d) {
						System.out.printf("不一致：runs=%d len=%d perLine=%d 行%d x=%d  旧=%d 缓存=%d 只换序=%d%n",
						                  spec[0], spec[1], spec[2], li, x, a, b, d);
						return false;
					}
				}
			}
		}
		System.out.println("新旧实现在 4 组数据 × 全部行 × 全部 x 上结果完全一致");
		return true;
	}

	/* ---------------- getRect ---------------- */

	/** 现在的 getRect：每个 run 都扫一遍 text 对齐下标，且从第一个 run 开始找选区 */
	static void rectOld(Seq<GlyphRun> runs, StringBuilder text,
	                    float labelX, float labelY, float lineHeight,
	                    int rx, int ry, long[] out) {
		float   currentX = 0, currentY = 0, startX = 0, endX = 0;
		boolean startFound = false;
		int     lineStart  = 0;

		for (int r = 0, n = runs.size; r < n; r++) {
			GlyphRun run = runs.get(r);
			if (run.glyphs.isEmpty()) continue;
			FloatSeq xAdvances = run.xAdvances;
			currentX = labelX + run.x + xAdvances.first();
			if (startX == -1) startX = currentX;
			currentY = labelY + run.y;
			while (lineStart < text.length() && (char) run.glyphs.first().id != text.charAt(lineStart)) lineStart++;

			for (int i = 1; i < xAdvances.size; i++) {
				int j = i - 1;
				if (!startFound && lineStart + j >= rx) {
					startX = currentX;
					startFound = true;
				}
				if (startFound) {
					if (lineStart + j >= ry) {
						endX = currentX;
						out[0] += (long) (endX - startX);
						return;
					}
					if (i == xAdvances.size - 1) {
						endX = currentX + xAdvances.get(i);
						out[0] += (long) (endX - startX);
						startX = -1;
						break;
					}
				}
				currentX += xAdvances.get(i);
			}
			lineStart += run.glyphs.size;
		}
		if (startFound && lineStart >= ry) {
			endX = currentX;
			out[0] += (long) (endX - startX);
		}
	}

	/** 用缓存的下标，并跳过选区之前的 run */
	static void rectNew(Seq<GlyphRun> runs, IntSeq starts,
	                    float labelX, float labelY, float lineHeight,
	                    int rx, int ry, long[] out) {
		float   currentX = 0, currentY = 0, startX = 0, endX = 0;
		boolean startFound = false;

		for (int r = 0, n = runs.size; r < n; r++) {
			int start = starts.get(r);
			if (start < 0) continue;
			GlyphRun run = runs.get(r);
			if (!startFound && start + run.glyphs.size <= rx) continue;   // 整个 run 在选区之前

			FloatSeq xAdvances = run.xAdvances;
			currentX = labelX + run.x + xAdvances.first();
			if (startX == -1) startX = currentX;
			currentY = labelY + run.y;

			for (int i = 1; i < xAdvances.size; i++) {
				int j = i - 1;
				if (!startFound && start + j >= rx) {
					startX = currentX;
					startFound = true;
				}
				if (startFound) {
					if (start + j >= ry) {
						endX = currentX;
						out[0] += (long) (endX - startX);
						return;
					}
					if (i == xAdvances.size - 1) {
						endX = currentX + xAdvances.get(i);
						out[0] += (long) (endX - startX);
						startX = -1;
						break;
					}
				}
				currentX += xAdvances.get(i);
			}
		}
		if (startFound) {
			endX = currentX;
			out[0] += (long) (endX - startX);
		}
	}

	/** getRect 新旧必须画出同样的矩形 */
	static boolean checkRect() {
		for (int[] spec : new int[][]{{10, 3, 10}, {100, 3, 20}, {37, 4, 5}}) {
			Case c = make(spec[0], spec[1], spec[2]);
			int  n = c.text.length();
			for (int a = 0; a < n; a += 3) {
				for (int b = a; b < n; b += 7) {
					long[] o = new long[1], q = new long[1];
					rectOld(c.runs, c.text, c.labelX, c.labelY, LINE, a, b, o);
					rectNew(c.runs, c.starts, c.labelX, c.labelY, LINE, a, b, q);
					if (o[0] != q[0]) {
						System.out.printf("getRect 不一致：runs=%d region=[%d,%d] 旧=%d 新=%d%n",
						                  spec[0], a, b, o[0], q[0]);
						return false;
					}
				}
			}
		}
		System.out.println("getRect 新旧在 3 组数据 × 全部选区上矩形总宽一致");
		return true;
	}

	/* ---------------- 计时 ---------------- */

	static volatile long sink;

	/** 采样：单行取整行宽度，多行取每行中点 */
	static int[] sampleXs(Case c) {
		int[] xs = new int[c.width + 1];
		for (int i = 0; i <= c.width; i++) xs[i] = i;
		return xs;
	}

	static long bench(Case c, int rounds, int mode) {
		int[] xs   = sampleXs(c);
		int   mask = xs.length - 1;
		long  best = Long.MAX_VALUE;
		for (int r = 0; r < 5; r++) {
			long acc = 0;
			long t0  = System.nanoTime();
			for (int i = 0; i < rounds; i++) {
				int li = i % c.lines;
				float y = -li * LINE - 1;
				int   x = xs[i & mask];
				acc += switch (mode) {
					case 0 -> oldCursor(c.runs, c.text, c.labelX, c.labelY, LINE, x, y);
					case 1 -> newCursor(c.runs, c.starts, c.labelX, c.labelY, LINE, x, y);
					default -> reordered(c.runs, c.text, c.labelX, c.labelY, LINE, x, y);
				};
			}
			best = Math.min(best, System.nanoTime() - t0);
			sink += acc;
		}
		return best;
	}

	public static void main(String[] args) {
		if (!check()) return;
		if (!checkRect()) return;
		System.out.println();
		System.out.println("单次 getCursor 耗时（ns），同一 Case 内 x 扫过整行、y 轮换各行");
		System.out.printf("%-28s %10s %10s %10s%n", "数据", "现在", "提案(缓存)", "只换顺序");

		for (int[] spec : new int[][]{
		 {10, 3, 10}, {100, 3, 100}, {1000, 3, 1000},   // 单行
		 {100, 3, 20}, {1000, 3, 20}, {5000, 3, 20}      // 多行
		}) {
			Case c       = make(spec[0], spec[1], spec[2]);
			int  rounds  = Math.max(50_000, 20_000_000 / Math.max(1, c.runs.size));
			long o       = bench(c, rounds, 0);
			long n       = bench(c, rounds, 1);
			long d       = bench(c, rounds, 2);
			String label = spec[0] + " run × " + spec[1] + " 字，每行 " + spec[2]
			               + "（" + c.lines + " 行，" + c.text.length() + " 字）";
			System.out.printf("%-28s %10.2f %10.2f %10.2f%n",
			                  label, o / (double) rounds, n / (double) rounds, d / (double) rounds);
		}

		System.out.println();
		System.out.println("rebuildRunIndex 的代价（只在 text/layout 变化后跑一次）");
		for (int n : new int[]{10, 100, 1000, 5000}) {
			Case c      = make(n, 3, 20);
			int  rounds = Math.max(10_000, 5_000_000 / n);
			long best   = Long.MAX_VALUE;
			for (int r = 0; r < 5; r++) {
				long t0 = System.nanoTime();
				for (int i = 0; i < rounds; i++) rebuildRunIndex(c.runs, c.text, c.starts);
				best = Math.min(best, System.nanoTime() - t0);
			}
			System.out.printf("  %5d run（%5d 字）：%8.2f ns/次%n",
			                  n, c.text.length(), best / (double) rounds);
		}

		mixed();
		rect();
		frame();
	}

	/** getRect 单次耗时：选区取一个 chunk（真实的悬停高亮），分别落在开头 / 中间 / 末尾 */
	static void rect() {
		System.out.println();
		System.out.println("getRect 单次耗时（ns），选区 = 一个 3 字 chunk");
		System.out.printf("%-30s %10s %10s%n", "数据 / 选区位置", "现在", "用缓存");
		for (int[] spec : new int[][]{{10, 3, 10}, {100, 3, 20}, {1000, 3, 20}, {5000, 3, 20}}) {
			Case c  = make(spec[0], spec[1], spec[2]);
			int  n  = c.text.length();
			int  mid = n / 2;
			int[][] regions = {{0, 2}, {mid, mid + 2}, {n - 3, n - 1}};
			String[] posNames = {"开头", "中间", "末尾"};
			for (int p = 0; p < 3; p++) {
				int    rx = regions[p][0], ry = regions[p][1];
				int    rounds = Math.max(20_000, 5_000_000 / spec[0]);
				long[] out    = new long[1];
				long   bo = Long.MAX_VALUE, bn = Long.MAX_VALUE;
				for (int r = 0; r < 5; r++) {
					long t0 = System.nanoTime();
					for (int i = 0; i < rounds; i++) rectOld(c.runs, c.text, c.labelX, c.labelY, LINE, rx, ry, out);
					bo = Math.min(bo, System.nanoTime() - t0);

					t0 = System.nanoTime();
					for (int i = 0; i < rounds; i++) rectNew(c.runs, c.starts, c.labelX, c.labelY, LINE, rx, ry, out);
					bn = Math.min(bn, System.nanoTime() - t0);
				}
				System.out.printf("%-30s %10.2f %10.2f%n",
				                  spec[0] + " run（" + c.lines + " 行）· " + posNames[p],
				                  bo / (double) rounds, bn / (double) rounds);
			}
		}
	}

	/** 一次 hover 帧：draw() 里的 1 次 getRect（高亮末尾那个 chunk）+ 鼠标事件的 1 次 getCursor */
	static void frame() {
		System.out.println();
		System.out.println("每个 hover 帧（1 次 getRect + 1 次 getCursor，ns）");
		System.out.printf("%-14s %12s %12s %12s%n", "数据", "现在", "全缓存(静态文本)", "全缓存(文本每帧变)");
		for (int[] spec : new int[][]{{100, 3, 20}, {1000, 3, 20}, {5000, 3, 20}}) {
			Case   c  = make(spec[0], spec[1], spec[2]);
			int    n  = c.text.length();
			int    rx = n - 3, ry = n - 1;
			int    rounds = Math.max(10_000, 2_000_000 / spec[0]);
			long[] out    = new long[1];
			long   bo = Long.MAX_VALUE, bs = Long.MAX_VALUE, bd = Long.MAX_VALUE;
			for (int r = 0; r < 5; r++) {
				long t0 = System.nanoTime();
				for (int i = 0; i < rounds; i++) {
					rectOld(c.runs, c.text, c.labelX, c.labelY, LINE, rx, ry, out);
					oldCursor(c.runs, c.text, c.labelX, c.labelY, LINE, (i * 7) % n, -1);
				}
				bo = Math.min(bo, System.nanoTime() - t0);

				// 静态文本：rebuild 只发生一次
				t0 = System.nanoTime();
				for (int i = 0; i < rounds; i++) {
					rectNew(c.runs, c.starts, c.labelX, c.labelY, LINE, rx, ry, out);
					newCursor(c.runs, c.starts, c.labelX, c.labelY, LINE, (i * 7) % n, -1);
				}
				bs = Math.min(bs, System.nanoTime() - t0);

				// 活值：每帧文本都变，缓存每帧重建
				t0 = System.nanoTime();
				for (int i = 0; i < rounds; i++) {
					rebuildRunIndex(c.runs, c.text, c.starts);
					rectNew(c.runs, c.starts, c.labelX, c.labelY, LINE, rx, ry, out);
					newCursor(c.runs, c.starts, c.labelX, c.labelY, LINE, (i * 7) % n, -1);
				}
				bd = Math.min(bd, System.nanoTime() - t0);
			}
			System.out.printf("%-14s %12.0f %12.0f %12.0f%n",
			                  spec[0] + " run", bo / (double) rounds,
			                  bs / (double) rounds, bd / (double) rounds);
		}
	}

	/**
	 * 关键场景：text 每帧都变（watch 的是活值），同时鼠标在标签上移动。
	 * 此时缓存每帧都会被置脏，一次「变化 + N 次 hover」的总开销才是真实收益。
	 */
	static void mixed() {
		System.out.println();
		System.out.println("每帧「text 变化一次 + N 次 hover」的总耗时（ns）");
		System.out.printf("%-12s %6s %12s %12s %12s%n", "数据", "N", "现在", "提案(缓存)", "只换顺序");
		for (int n : new int[]{100, 1000}) {
			Case c  = make(n, 3, 20);
			int  xs = c.width;
			for (int per : new int[]{1, 2, 3, 5}) {
				int  rounds = Math.max(20_000, 5_000_000 / n);
				long bestOld = Long.MAX_VALUE, bestNew = Long.MAX_VALUE, bestRe = Long.MAX_VALUE;
				for (int r = 0; r < 5; r++) {
					long t0  = System.nanoTime();
					long acc = 0;
					for (int i = 0; i < rounds; i++) {
						for (int k = 0; k < per; k++) {
							acc += oldCursor(c.runs, c.text, c.labelX, c.labelY, LINE,
							                 (i * 37 + k * 11) % xs, -1);
						}
					}
					bestOld = Math.min(bestOld, System.nanoTime() - t0);
					sink += acc;

					t0 = System.nanoTime();
					acc = 0;
					for (int i = 0; i < rounds; i++) {
						rebuildRunIndex(c.runs, c.text, c.starts);   // 这一帧 text 变了
						for (int k = 0; k < per; k++) {
							acc += newCursor(c.runs, c.starts, c.labelX, c.labelY, LINE,
							                 (i * 37 + k * 11) % xs, -1);
						}
					}
					bestNew = Math.min(bestNew, System.nanoTime() - t0);
					sink += acc;

					t0 = System.nanoTime();
					acc = 0;
					for (int i = 0; i < rounds; i++) {
						for (int k = 0; k < per; k++) {
							acc += reordered(c.runs, c.text, c.labelX, c.labelY, LINE,
							                 (i * 37 + k * 11) % xs, -1);
						}
					}
					bestRe = Math.min(bestRe, System.nanoTime() - t0);
					sink += acc;
				}
				System.out.printf("%-12s %6d %12.0f %12.0f %12.0f%n",
				                  n + " run", per,
				                  bestOld / (double) rounds, bestNew / (double) rounds,
				                  bestRe / (double) rounds);
			}
		}
	}
}
