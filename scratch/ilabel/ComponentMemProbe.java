package modtools.ui.comp.input;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.FontCache;
import arc.graphics.g2d.GlyphLayout;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.scene.event.ClickListener;
import arc.scene.ui.Label;
import arc.scene.ui.Label.LabelStyle;
import arc.struct.IntMap;
import arc.struct.ObjectMap;

import java.lang.management.ManagementFactory;

/**
 * 把 {@code new InlineLabel(...)} 的 3208 字节拆开，看钱花在哪。
 * <p>逐个组件单独 new，用 {@code ThreadMXBean.getThreadAllocatedBytes} 数分配字节。
 */
public class ComponentMemProbe {
	static final com.sun.management.ThreadMXBean MX =
	 (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

	static long alloc() {
		return MX.getThreadAllocatedBytes(Thread.currentThread().getId());
	}

	static final Object[] keep = new Object[1];

	/** 每次分配都塞进 keep，避免被优化掉 */
	static double perAlloc(String name, Runnable factory, int n) {
		for (int i = 0; i < 2000; i++) factory.run();     // 预热
		long b0 = alloc();
		for (int i = 0; i < n; i++) factory.run();
		long b1 = alloc();
		double per = (b1 - b0) / (double) n;
		System.out.printf("%-34s %8.1f 字节%n", name, per);
		return per;
	}

	public static void main(String[] args) {
		int        n     = 100_000;
		LabelStyle style = new LabelStyle(HitTestProbe.font(), Color.white);

		double mapObj = perAlloc("ObjectMap<>() 空", () -> keep[0] = new ObjectMap<>(), n);
		double mapInt = perAlloc("IntMap<>() 空", () -> keep[0] = new IntMap<>(), n);
		double glyph  = perAlloc("GlyphLayout", () -> keep[0] = new GlyphLayout(), n);
		double sb     = perAlloc("StringBuilder", () -> keep[0] = new StringBuilder(), n);
		double vec    = perAlloc("Vec2", () -> keep[0] = new Vec2(), n);
		double ls     = perAlloc("LabelStyle 克隆", () -> keep[0] = new LabelStyle(style), n);
		double fc     = perAlloc("FontCache（font.newFontCache）", () -> keep[0] = style.font.newFontCache(), n);
		double cl     = perAlloc("ClickListener", () -> keep[0] = new ClickListener(KeyCode.mouseLeft), n);
		double il     = perAlloc("InputListener（匿名空实现）", () -> keep[0] = new arc.scene.event.InputListener() {}, n);

		double label = perAlloc("new Label(text, style)", () -> keep[0] = new Label("abcdef", style), n / 4);
		double inl   = perAlloc("new InlineLabel(text, style)", () -> keep[0] = new InlineLabel("abcdef", style), n / 4);

		System.out.println();
		System.out.println("容量对空 map 的影响：");
		for (int cap : new int[]{2, 4, 8, 16}) {
			perAlloc("  ObjectMap<>(" + cap + ")", () -> keep[0] = new ObjectMap<>(cap), n);
			perAlloc("  IntMap<>(" + cap + ")", () -> keep[0] = new IntMap<>(cap), n);
		}

		System.out.println();
		System.out.printf("Label 基类            %8.1f%n", label);
		System.out.printf("InlineLabel 合计      %8.1f%n", inl);
		System.out.printf("差（InlineLabel 自己的）= %.1f%n", inl - label);
		System.out.println();
		System.out.println("拆解：");
		System.out.printf("  三个 map（colorMap/clicks/hovers）  %.0f%n", mapInt + mapObj * 2);
		System.out.printf("  两个监听器                          %.0f%n", cl + il);
		System.out.printf("  Label 里的 GlyphLayout+SB+Vec2      %.0f%n", glyph + sb + vec);
		System.out.printf("  FontCache                           %.0f%n", fc);
		System.out.printf("  LabelStyle 克隆                     %.0f%n", ls);
	}
}
