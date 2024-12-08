package modtools.ui.comp.input;

import arc.func.Prov;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.math.geom.Point2;
import arc.struct.Seq;
import arc.util.pooling.*;
import arc.util.pooling.Pool.Poolable;

public class ExtendingLabel extends InlineLabel {

	public ExtendingLabel(CharSequence text) {
		super(text);
	}
	public ExtendingLabel(CharSequence text, LabelStyle style) {
		super(text, style);
	}
	public ExtendingLabel(Prov<CharSequence> sup) {
		super(sup);
	}

	public static final class DrawRun implements Poolable {
		public InlineLabel label;
		public int         start, end;
		public DrawType type;
		public Color    color;

		private static final Point2        runRect = new Point2(UNSET, UNSET);
		private static final Pool<DrawRun> pool    = Pools.get(DrawRun.class, DrawRun::new);
		public static DrawRun obtain(InlineLabel label, int start, int end, DrawType type, Color color) {
			if (label == null) throw new IllegalArgumentException("label cannot be null.");
			if (start < 0 || end < 0) throw new IllegalArgumentException("start and end cannot be negative.");
			if (start > end) throw new IllegalArgumentException("start cannot be greater than end.");
			if (color == null) throw new IllegalArgumentException("color cannot be null.");
			if (type == null) throw new IllegalArgumentException("type cannot be null.");

			DrawRun underlineRun = pool.obtain();
			underlineRun.label = label;
			underlineRun.start = start;
			underlineRun.end = end;
			underlineRun.type = type;
			underlineRun.color = color;
			return underlineRun;
		}

		public void draw() {
			Draw.color(color);
			Lines.stroke(2);
			label.getRect(runRect.set(start, end), r -> {
				float x = label.x + r.x;
				float y = label.y + r.y;
				switch (type) {
					case underline -> Lines.line(x, y, x + r.width, y);
					case strikethrough -> Lines.line(x, y + r.height / 2, x + r.width, y + r.height / 2);
					case background -> Fill.crect(x, y, r.width, r.height);
					// 波浪线
					case wave -> {
						float amplitude  = 2f; // 波浪的振幅
						float wavelength = 6f; // 波浪的波长
						float step       = 1f; // 绘制步长，值越小曲线越平滑

						// 通过逐点计算波形
						for (float i = 0; i < r.width; i += step) {
							float x1 = x + i;
							float y1 = y + (float) Math.sin((i / wavelength) * 2 * Math.PI) * amplitude;
							float x2 = x + i + step;
							float y2 = y + (float) Math.sin(((i + step) / wavelength) * 2 * Math.PI) * amplitude;
							Lines.line(x1, y1, x2, y2); // 绘制波浪线段
						}
					}

				}
			});
		}

		public void reset() {
			label = null;
			start = UNSET;
			end = UNSET;
			color = null;
		}
	}
	public enum DrawType {
		// 下划线
		underline,
		// 删除线
		strikethrough,
		// 背景色
		background,
		// 波浪线
		wave
		// 加粗
		// bold
	}

	private final Seq<DrawRun> drawRuns = new Seq<>();

	public void clearDrawRuns() {
		Pools.freeAll(drawRuns, true);
		drawRuns.clear();
	}
	public void addDrawRun(int start, int end, DrawType type, Color color) {
		drawRuns.add(DrawRun.obtain(this, start, end, type, color));
	}

	public void draw() {
		super.draw();
		for (DrawRun run : drawRuns) {
			run.draw();
		}
	}
}
