package modtools.ui.comp.input;

import arc.graphics.Color;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.Font.FontData;
import arc.graphics.g2d.GlyphLayout;
import arc.graphics.g2d.TextureRegion;
import arc.scene.ui.Label.LabelStyle;
import arc.struct.*;
import arc.util.Tmp;

/**
 * 量真实的 {@link InlineLabel#getCursor} / {@link InlineLabel#getRect}（不是副本）。
 * <p>数据由 {@link CursorBench#make} 造，与副本基准用同一份布局，方便和改前对照。
 */
public class RealPerfProbe {
	static final float LINE = CursorBench.LINE;

	static Font font() {
		FontData fd = new FontData();
		fd.lineHeight = LINE;
		fd.capHeight = CursorBench.CAP;
		fd.down = LINE;
		return new Font(fd, Seq.with(new TextureRegion()), false);
	}

	static InlineLabel labelOf(CursorBench.Case c) {
		InlineLabel label = new InlineLabel(c.text.toString(), new LabelStyle(font(), Color.white));
		GlyphLayout layout = HitTestProbe.layoutOf(label);
		layout.runs.clear();
		layout.runs.addAll(c.runs);
		label.labelX = 0;
		label.labelY = 0;
		return label;
	}

	static volatile long sink;

	public static void main(String[] args) {
		System.out.println("真实 InlineLabel 的单次耗时（ns）");
		System.out.printf("%-14s %12s %12s %12s%n", "数据", "getCursor", "getRect(末尾)", "getRect(开头)");
		for (int n : new int[]{100, 1000, 5000}) {
			CursorBench.Case c     = CursorBench.make(n, 3, 20);
			InlineLabel        label = labelOf(c);
			int                len   = c.text.length();
			int                rounds = Math.max(10_000, 3_000_000 / n);

			// 预热 + 触发一次缓存构建
			label.getCursor(1, 1);
			label.getRect(Tmp.p1.set(0, 2), r -> sink += (long) r.width);

			long bc = Long.MAX_VALUE, be = Long.MAX_VALUE, bs = Long.MAX_VALUE;
			for (int r = 0; r < 5; r++) {
				long t0 = System.nanoTime();
				for (int i = 0; i < rounds; i++) {
					// 鼠标只能落在某一行里：x 在行宽内、y 在该行的文字带内
					int   li = i % c.lines;
					float y  = -li * LINE - 1;
					sink += label.getCursor((i * 7) % (c.width + 1), y);
				}
				bc = Math.min(bc, System.nanoTime() - t0);

				t0 = System.nanoTime();
				for (int i = 0; i < rounds; i++) label.getRect(Tmp.p1.set(len - 3, len - 1), r1 -> sink += (long) r1.width);
				be = Math.min(be, System.nanoTime() - t0);

				t0 = System.nanoTime();
				for (int i = 0; i < rounds; i++) label.getRect(Tmp.p1.set(0, 2), r1 -> sink += (long) r1.width);
				bs = Math.min(bs, System.nanoTime() - t0);
			}
			System.out.printf("%-14s %12.2f %12.2f %12.2f%n", n + " run",
			                  bc / (double) rounds, be / (double) rounds, bs / (double) rounds);
		}
	}
}
