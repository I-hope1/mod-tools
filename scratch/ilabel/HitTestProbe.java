package modtools.ui.comp.input;

import arc.graphics.Color;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.Font.FontData;
import arc.graphics.g2d.GlyphLayout;
import arc.graphics.g2d.GlyphLayout.GlyphRun;
import arc.graphics.g2d.TextureRegion;
import arc.math.geom.Point2;
import arc.scene.ui.Label.LabelStyle;
import arc.struct.*;

/**
 * 用真实的 InlineLabel 实例验证两处几何问题：
 * 1) sub() 切出来的 run，width 少算 xAdvances[startIndex]，getCursor 的右边界短一截；
 * 2) getCursor 用 height + run.y 定位，getRect 用 labelY + run.y，两者不是同一个原点。
 * <p>
 * 字形是人工构造的（每字 advance = 10，lineHeight = 20），不依赖任何贴图/GL。
 */
public class HitTestProbe {
	static final float ADV      = 10f;
	static final float LINE     = 20f;
	static final float CAP      = 20f;
	static final Point2 UNSET_P = new Point2(-1, -1);

	static Font font() {
		FontData fd = new FontData();
		fd.lineHeight = LINE;
		fd.capHeight = CAP;
		fd.down = LINE;
		fd.descent = 0;
		// 空字形表 + 一个占位 region：Font 的构造只要求 regions 非空，不会碰到纹理
		return new Font(fd, Seq.with(new TextureRegion()), false);
	}

	static Seq<GlyphRun> build(String... lines) {
		Seq<GlyphRun> runs = new Seq<>();
		float y = 0;
		for (String line : lines) {
			GlyphRun run = new GlyphRun();
			for (int i = 0; i < line.length(); i++) {
				Font.Glyph g = new Font.Glyph();
				g.id = line.charAt(i);
				g.xadvance = (int) ADV;
				run.glyphs.add(g);
			}
			run.xAdvances.add(0f);
			for (int i = 0; i < line.length(); i++) run.xAdvances.add(ADV);
			run.width = line.length() * ADV;
			run.x = 0;
			run.y = y;
			runs.add(run);
			y -= LINE;
		}
		return runs;
	}

	/** 造一个 label：runs 由 build 决定，labelY/height 由调用方按 layout() 的公式给出 */
	static InlineLabel make(String text, String[] lines, float labelY, float height) {
		InlineLabel label = new InlineLabel(text, new LabelStyle(font(), Color.white));
		GlyphLayout layout = layoutOf(label);
		layout.runs.clear();
		layout.runs.addAll(build(lines));
		label.labelX = 0;
		label.labelY = labelY;
		label.setSize(200, height);
		return label;
	}

	/** Label.layout 是 protected 且在 arc.scene.ui 包内，只能反射取 */
	static GlyphLayout layoutOf(InlineLabel label) {
		try {
			java.lang.reflect.Field f = arc.scene.ui.Label.class.getDeclaredField("layout");
			f.setAccessible(true);
			return (GlyphLayout) f.get(label);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	/** layout() 里居中标签的 y：y += (height - textHeight) / 2; 然后 y += textHeight */
	static float centerLabelY(float height, float textHeight) {
		return (height - textHeight) / 2 + textHeight;
	}

	static void probeWidth() {
		System.out.println("=== 问题1：sub() 的 width 与 getCursor 右边界 ===");
		Seq<GlyphRun> runs = build("abcde");
		IntMap<Color> map = new IntMap<>();
		map.put(0, Color.white);
		map.put(3, Color.red);
		InlineLabel.splitAndColorize(runs, map, new StringBuilder("abcde"));

		for (GlyphRun r : runs) {
			StringBuilder s = new StringBuilder();
			for (Font.Glyph g : r.glyphs) s.append((char) g.id);
			float trueRight = r.x + r.xAdvances.first();
			for (int i = 1; i < r.xAdvances.size; i++) trueRight += r.xAdvances.get(i);
			System.out.printf("run[%s] x=%.0f adv0=%.0f width=%.0f | x+width=%.0f  真实右边界=%.0f  差=%.0f%n",
			 s, r.x, r.xAdvances.first(), r.width, r.x + r.width, trueRight, trueRight - (r.x + r.width));
		}

		InlineLabel label = make("abcde", new String[]{"abcde"}, LINE, LINE);
		GlyphLayout layout = layoutOf(label);
		layout.runs.clear();
		layout.runs.addAll(runs);
		StringBuilder hit = new StringBuilder();
		for (int x = 0; x < 50; x++) {
			int idx = label.getCursor(x, LINE - 1);
			hit.append(idx == InlineLabel.UNSET_I ? '.' : (char) ('0' + idx));
		}
		System.out.println("getCursor 命中图（x=0..49，. = 未命中，数字 = 字符下标）:");
		System.out.println("          " + hit);
		System.out.println("          0123456789012345678901234567890123456789012345678901");
		System.out.println("          期望：  00000000001111111111222222222233333333334444444444");
		System.out.println("          期望：  0123456789012345678901234567890123456789012345678");
		System.out.println();
	}

	static void probeY() {
		System.out.println("=== 问题2：getCursor 的 y 原点 ===");
		float height = 40, textHeight = CAP;
		float labelY = centerLabelY(height, textHeight);
		InlineLabel label = make("abcde", new String[]{"abcde"}, labelY, height);
		System.out.printf("height=%.0f textHeight=%.0f -> layout() 会算出 labelY=%.0f%n", height, textHeight, labelY);

		Point2 region = new Point2(0, 4);
		label.getRect(region, r -> System.out.printf("getRect(0..4) 画出来的矩形: x=%.0f y=%.0f w=%.0f h=%.0f -> y 区间 [%.0f, %.0f]%n",
		 r.x, r.y, r.width, r.height, r.y, r.y + r.height));

		for (float y : new float[]{15, 25, 35}) {
			System.out.printf("getCursor(5, %.0f) = %s%n", y,
			 label.getCursor(5, y) == InlineLabel.UNSET_I ? "未命中" : String.valueOf(label.getCursor(5, y)));
		}
		System.out.println("getCursor 的判定区间 = (labelY - lineHeight, labelY] = (" + (labelY - LINE) + ", " + labelY + "]");
		System.out.println();
	}

	public static void main(String[] args) throws Exception {
		probeWidth();
		probeHighlightRect();
		probeY();
		probeAdjacent();
		probeAdv0Meaning();
		probeSubBounds();
	}

	/** 点1：相邻子 run 的 currentX 到底怎么接 */
	static void probeAdjacent() {
		System.out.println("=== 点1：相邻子 run 的衔接 ===");
		String text = "abcdefghij";
		Seq<GlyphRun> runs = build(text);
		IntMap<Color> map = new IntMap<>();
		map.put(0, Color.white);
		map.put(3, Color.red);
		map.put(7, Color.blue);
		InlineLabel.splitAndColorize(runs, map, new StringBuilder(text));

		for (int i = 0; i < runs.size; i++) {
			GlyphRun r = runs.get(i);
			float pen = r.x + r.xAdvances.first();
			System.out.printf("run%d x=%.0f adv0=%.0f w=%.0f 首字笔位=%.0f 右边界=%.0f%n",
			 i, r.x, r.xAdvances.first(), r.width, pen, r.x + r.width);
			if (i > 0) {
				GlyphRun p = runs.get(i - 1);
				System.out.printf("   上一段 x+width=%.0f vs 本段首字笔位=%.0f -> %s%n",
				 p.x + p.width, pen, Math.abs(p.x + p.width - pen) < 1e-4 ? "重合" : "错位");
				System.out.printf("   反例：若 currentX += 上一段 width，本段首字笔位变成 %.0f（右移 %.0f）%n",
				 p.x + p.width + r.xAdvances.first(), r.xAdvances.first());
			}
		}
		System.out.println();
	}

	/** 点2：首项 adv0 的含义（笔位 vs 墨迹左缘） */
	static void probeAdv0Meaning() {
		System.out.println("=== 点2：adv0 = -xoffset * scaleX - padLeft 时，矩形从哪起画 ===");
		int xoffset = 2;
		float padLeft = 1f, glyphWidth = 8f;
		GlyphRun r = new GlyphRun();
		Font.Glyph g = new Font.Glyph();
		g.id = 'a';
		g.xadvance = 10;
		g.xoffset = xoffset;
		g.width = (int) glyphWidth;
		r.glyphs.add(g);
		// 与 Font.getGlyphs（L887-888）、GlyphLayout.wrap（L399）同一公式
		float adv0 = -xoffset - padLeft;
		r.xAdvances.add(adv0);
		r.xAdvances.add(glyphWidth + xoffset); // 末项是最后一字的宽度
		r.x = 0;
		r.width = adv0 + (glyphWidth + xoffset);

		float pen = r.x + adv0;
		float inkLeft = pen + xoffset;
		float inkRight = inkLeft + glyphWidth;
		System.out.printf("adv0=%.0f 笔位=%.0f 墨迹左缘=%.0f 墨迹右缘=%.0f 右边界(x+width)=%.0f%n",
		 adv0, pen, inkLeft, inkRight, r.x + r.width);
		System.out.printf("矩形从笔位起画: [%.0f, %.0f] -> 左缘比墨迹偏左 %.0f（= xoffset）%n",
		 pen, pen + (r.width - adv0), pen - inkLeft);
		System.out.printf("矩形从 run.x 起画: [%.0f, %.0f] -> 左缘比墨迹偏左 %.0f%n", r.x, r.x + r.width, r.x - inkLeft);
		System.out.println("（切段 run 的 adv0 是上一个字的 advance，从 run.x 起画会偏左整整一个字，所以首段/后续段不能分开处理）");
		System.out.println();
	}

	/** 点3：sub() 对 endIndex 上界的处理 */
	static void probeSubBounds() throws Exception {
		System.out.println("=== 点3：sub() 的 endIndex 上界 ===");
		java.lang.reflect.Method m = InlineLabel.class.getDeclaredMethod(
		 "sub", GlyphRun.class, int.class, int.class, Color.class, float.class);
		m.setAccessible(true);
		GlyphRun src = build("abcde").get(0);
		for (int end : new int[]{5, 7}) {
			try {
				GlyphRun out = (GlyphRun) m.invoke(null, src, 0, end, Color.white, 0f);
				System.out.printf("endIndex=%d (glyphs.size=5) -> 返回 %d 个字形%n", end, out == null ? -1 : out.glyphs.size);
			} catch (java.lang.reflect.InvocationTargetException e) {
				System.out.printf("endIndex=%d (glyphs.size=5) -> %s: %s%n",
				 end, e.getCause().getClass().getSimpleName(), e.getCause().getMessage());
			}
		}
		System.out.println();
	}

	/** SearchedLabel 画高亮背景时，width 要不要减 adv0 */
	static void probeHighlightRect() {
		System.out.println("=== 问题1b：高亮背景矩形的宽度 ===");
		Seq<GlyphRun> runs = build("abcde");
		IntMap<Color> map = new IntMap<>();
		map.put(0, Color.white);
		map.put(3, Color.red);
		InlineLabel.splitAndColorize(runs, map, new StringBuilder("abcde"));

		GlyphRun r = runs.get(1);
		float adv0 = r.xAdvances.first();
		float glyphLeft = r.x + adv0;
		float trueRight = r.x + r.width;
		System.out.printf("run[de] x=%.0f adv0=%.0f width=%.0f -> 字形左边界=%.0f 右边界=%.0f%n",
		 r.x, adv0, r.width, glyphLeft, trueRight);
		System.out.printf("起点 run.x + adv0、宽度不减: [%.0f, %.0f]  右边界多出 %.0f%n",
		 glyphLeft, glyphLeft + r.width, glyphLeft + r.width - trueRight);
		System.out.printf("起点 run.x + adv0、宽度减 adv0: [%.0f, %.0f]  右边界多出 %.0f%n",
		 glyphLeft, glyphLeft + (r.width - adv0), glyphLeft + (r.width - adv0) - trueRight);
		System.out.printf("起点 run.x、宽度不减:        [%.0f, %.0f]  左边界多出 %.0f%n%n",
		 r.x, r.x + r.width, r.x - glyphLeft);
	}
}
