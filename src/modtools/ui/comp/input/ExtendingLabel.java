package modtools.ui.comp.input;

import arc.func.*;
import arc.graphics.*;
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
		public Object   data;

		private static final Point2        runRect = new Point2(UNSET, UNSET);
		private static final Pool<DrawRun> pool    = Pools.get(DrawRun.class, DrawRun::new);
		public static DrawRun obtain(InlineLabel label, int start, int end, DrawType type, Color color) {
			return obtain(label, start, end, type, color, null);
		}
		public static DrawRun obtain(InlineLabel label, int start, int end, DrawType type, Color color, Object data) {
			if (label == null) throw new IllegalArgumentException("label cannot be null.");
			if (start < 0 || end < 0) throw new IllegalArgumentException("start and end cannot be negative.");
			if (start > end) throw new IllegalArgumentException("start cannot be greater than end.");
			if (color == null) throw new IllegalArgumentException("color cannot be null.");
			if (type == null) throw new IllegalArgumentException("type cannot be null.");

			DrawRun run = pool.obtain();
			run.label = label;
			run.start = start;
			run.end = end;
			run.type = type;
			run.color = color;
			run.data = data;
			return run;
		}

		public void draw() {
			Draw.color(color);
			Lines.stroke(2);
			label.getRect(runRect.set(start, end), r -> {
				float x = label.x + r.x;
				float y = label.y + r.y;
				type.draw(this, x, y, r.width, r.height);
			});
		}

		public void reset() {
			label = null;
			start = UNSET;
			end = UNSET;
			color = null;
			data = null;
		}
	}
	public enum DrawType {
		// 下划线
		underline((x, y, w, h) -> Lines.line(x, y, x + w, y)),
		// 删除线
		strikethrough((x, y, w, h) -> Lines.line(x, y + h / 2, x + w, y + h / 2)),
		// 背景色
		background(Fill::crect),
		// 波浪线
		wave((x, y, width, h) -> {
			float amplitude  = 2f; // 波浪的振幅
			float wavelength = 6f; // 波浪的波长
			float step       = 1f; // 绘制步长，值越小曲线越平滑

			// 通过逐点计算波形
			for (float i = 0; i < width; i += step) {
				float x1 = x + i;
				float y1 = y + (float) Math.sin((i / wavelength) * 2 * Math.PI) * amplitude;
				float x2 = x + i + step;
				float y2 = y + (float) Math.sin(((i + step) / wavelength) * 2 * Math.PI) * amplitude;
				Lines.line(x1, y1, x2, y2); // 绘制波浪线段
			}
		}),
		// 加粗
		// bold
		// 添加icon
		icon((x, y, w, h) -> {}) {
			void draw(DrawRun run, float x, float y, float w, float h) {
				if (!(run.data instanceof TextureRegion region)) throw new IllegalArgumentException("data must be TextureRegion.");
				// 保持宽高比绘制图标
				float scl = (float) region.width / region.height;
				// 图标不能超出范围
				if (w > h * scl) {
					w = h * scl;
				} else if (h > w / scl) {
					h = w / scl;
				}
				Draw.rect(region, x + w / 2f, y + h / 2f, w, h);
			}
		},
		;
		private final Floatc4 cons;
		DrawType(Floatc4 cons) {
			this.cons = cons;
		}
		void draw(DrawRun run, float x, float y, float w, float h) {
			cons.get(x, y, w, h);
		}
	}

	private final Seq<DrawRun>          drawRuns = new Seq<>();

	public void clearDrawRuns() {
		Pools.freeAll(drawRuns, true);
		drawRuns.clear();
	}
	public void addDrawRun(int start, int end, DrawType type, Color color, Object data) {
		drawRuns.add(DrawRun.obtain(this, start, end, type, color, data));
	}
	public void addDrawRun(int start, int end, DrawType type, Color color) {
		addDrawRun(start, end, type, color, null);
	}

	public void draw() {
		super.draw();
		for (DrawRun run : drawRuns) {
			run.draw();
		}
	}
}
