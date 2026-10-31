package modtools.utils.search;

import arc.scene.Element;
import arc.scene.ui.layout.Table;

import java.lang.reflect.Field;

/**
 * 精确测 TemplateTable.getPrefWidth() 的真实取值。
 * <p>关键是区分「模板 cells 里还有没有 element」——{@code Cell.prefWidth()} 是
 * {@code element == null ? 0 : element.getPrefWidth()}（Cell.java:117-119），
 * 所以只要模板的 cell 被搬空，template 的 prefWidth 就只能是 padding + background。
 */
public class PrefWidthProbe {
	static class Box extends Element {
		final float w;
		Box(float w) { this.w = w; }
		public float getPrefWidth() { return w; }
		public float getPrefHeight() { return 20; }
	}

	static class Probe extends TemplateTable<String> {
		final java.util.List<Float> calls = new java.util.ArrayList<>();
		Probe() { super(null, s -> true); }
		public float getPrefWidth() {
			float v = super.getPrefWidth();
			calls.add(v);
			return v;
		}
	}

	static Probe build(int n, int cols) {
		Probe tt = new Probe();
		tt.left().top();
		for (int i = 0; i < n; i++) {
			tt.bind("k" + i);
			tt.add(new Box(20));
			tt.unbind();
			if ((i + 1) % cols == 0) tt.newLine();
		}
		return tt;
	}

	static Table templateOf(TemplateTable<?> tt) throws Exception {
		Field f = TemplateTable.class.getDeclaredField("template");
		f.setAccessible(true);
		return (Table) f.get(tt);
	}

	static boolean sizeInvalid(Table t) throws Exception {
		Field f = Table.class.getDeclaredField("sizeInvalid");
		f.setAccessible(true);
		return f.getBoolean(t);
	}

	static float tablePrefWidth(Table t) throws Exception {
		Field f = Table.class.getDeclaredField("tablePrefWidth");
		f.setAccessible(true);
		return f.getFloat(t);
	}

	/** 模板 cells 里还有几个 cell 持有 element */
	static int nonNullCells(Table t) {
		int n = 0;
		for (int i = 0; i < t.getCells().size; i++) {
			if (t.getCells().get(i).get() != null) n++;
		}
		return n;
	}

	static void dump(String tag, Probe tt, Table tpl) throws Exception {
		System.out.printf("%-22s this.prefW=%7.1f | tpl.prefW=%7.1f  tplCells=%2d 非空=%2d sizeInvalid=%s 缓存prefW=%7.1f%n",
		 tag, tt.getPrefWidth(), tpl.getPrefWidth(),
		 tpl.getCells().size, nonNullCells(tpl), sizeInvalid(tpl), tablePrefWidth(tpl));
	}

	static void dumpParent(String tag, Table p, Probe tt, Field ew, Field cw, Field cw2) throws Exception {
		float[] cwp = (float[]) cw.get(p), cwd = (float[]) cw2.get(p);
		Field cmw = Table.class.getDeclaredField("columnMinWidth");
		cmw.setAccessible(true);
		float[] cmwd = (float[]) cmw.get(p);
		arc.scene.ui.layout.Cell c = p.getCells().first();
		System.out.printf("%-16s tt.prefW=%6.1f tt.minW=%6.1f | cell.prefW=%6.1f cell.minW=%6.1f | parent.prefW=%6.1f colPrefW[0]=%6.1f colMinW[0]=%6.1f 列宽=%6.1f%n",
		 tag, tt.getPrefWidth(), tt.getMinWidth(), c.prefWidth(), c.minWidth(), p.getPrefWidth(),
		 cwp.length > 0 ? cwp[0] : -1, cmwd.length > 0 ? cmwd[0] : -1, ew.getFloat(c));
		System.out.println("   getPrefWidth 调用记录：" + tt.calls);
		tt.calls.clear();
	}

	public static void main(String[] args) throws Exception {
		System.out.println("=== 逐帧（每帧 act 后 validate，模拟 draw 阶段的布局） ===");
		Probe tt = build(10, 5);
		Table tpl = templateOf(tt);
		for (int f = 0; f < 5; f++) {
			tt.act(1f);
			tt.validate();
			dump("frame " + f, tt, tpl);
		}

		System.out.println();
		System.out.println("=== add 之后、act 之前（新加的元素还在模板里） ===");
		Probe a = build(10, 5);
		a.act(1f);
		a.validate();
		Table at = templateOf(a);
		dump("稳态", a, at);
		a.bind("k10");
		a.add(new Box(20));
		a.unbind();
		dump("add 后/act 前", a, at);
		a.act(1f);
		a.validate();
		dump("add 后/act 后", a, at);

		System.out.println();
		System.out.println("=== 放进父表后，父表给这一列多宽 ===");
		Field ew = arc.scene.ui.layout.Cell.class.getDeclaredField("elementWidth");
		ew.setAccessible(true);
		Field cw = Table.class.getDeclaredField("columnPrefWidth");
		cw.setAccessible(true);
		Field cw2 = Table.class.getDeclaredField("columnWidth");
		cw2.setAccessible(true);

		Probe ca = build(10, 5);
		Table pa = new Table();
		pa.add(ca);
		ca.act(1f);
		pa.validate();
		dumpParent("A 重建后测量", pa, ca, ew, cw, cw2);
		pa.invalidate();
		pa.validate();
		dumpParent("A 再重测一次", pa, ca, ew, cw, cw2);

		Probe cb = build(10, 5);
		Table pb = new Table();
		pb.add(cb);
		pb.validate();
		dumpParent("B 重建前测量", pb, cb, ew, cw, cw2);
		cb.act(1f);
		pb.invalidate();
		pb.validate();
		dumpParent("B 重建后重测", pb, cb, ew, cw, cw2);

		System.out.println();
		System.out.println("=== pack() 是唯一直接用 getPrefWidth() 的地方 ===");
		Probe pk = build(10, 5);
		pk.act(1f);
		pk.pack();
		System.out.printf("pack 后：width=%.1f height=%.1f（内容真实需要 100 宽 / 40 高）%n",
		 pk.getWidth(), pk.getHeight());

		System.out.println();
		System.out.println("=== +12 到底有没有参与布局 ===");
		// 1) 父表不 grow，pack 父表
		Probe p1 = build(10, 5);
		p1.act(1f);
		Table host1 = new Table();
		host1.add(p1);
		host1.pack();
		System.out.printf("父表不 grow + pack：  host.w=%6.1f  tt.w=%6.1f  （内容 100）%n",
		 host1.getWidth(), p1.getWidth());

		// 2) 父表 grow，pack 父表
		Probe p2 = build(10, 5);
		p2.act(1f);
		Table host2 = new Table();
		host2.add(p2).grow();
		host2.pack();
		System.out.printf("父表 grow  + pack：  host.w=%6.1f  tt.w=%6.1f%n",
		 host2.getWidth(), p2.getWidth());

		// 3) 直接 pack TemplateTable 自己（唯一会让 +12 生效的路径）
		Probe p3 = build(10, 5);
		p3.act(1f);
		p3.pack();
		System.out.printf("直接 pack tt：       tt.w=%6.1f  （内容 100；+12 生效则是 112，不带则是 100，坏值是 12）%n",
		 p3.getWidth());

		System.out.println();
		System.out.println("=== 元素宽度换成 200（每列 200，5 列 = 1000） ===");
		Probe d = new Probe();
		d.left().top();
		for (int i = 0; i < 10; i++) {
			d.bind("k" + i);
			d.add(new Box(200));
			d.unbind();
			if ((i + 1) % 5 == 0) d.newLine();
		}
		Table dt = templateOf(d);
		dump("构造后/act 前", d, dt);
		d.act(1f);
		d.validate();
		dump("重建后", d, dt);
	}
}
