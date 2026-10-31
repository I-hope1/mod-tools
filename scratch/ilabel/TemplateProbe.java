package modtools.utils.search;

import arc.func.Boolf;
import arc.scene.Element;
import arc.scene.ui.layout.Table;

/**
 * 量化 TemplateTable 重建前台 cells 的频率与正确性。
 * <p>重建的唯一副作用出口是 act() 末尾的 {@code layout()}，所以覆写它计数就等价于
 * 「这帧有没有搬元素」。
 */
public class TemplateProbe {
	/** 固定 20×20 的哑元素：Cell.size() 会走 Scl.scl()，而探针里没有 Core.app */
	static class Box extends Element {
		public float getPrefWidth() { return 20; }
		public float getPrefHeight() { return 20; }
	}

	/**
	 * 永远说「我可能需要更新」的条件。
	 * <p>真实场景：{@code noFilter} 打开时 {@code internalFilter} 会短路，
	 * {@code PatternBoolf.valid} 不会被调用，它的 {@code last} 永远追不上 provider，
	 * 于是 {@code needUpdate()} 一直为 true。
	 */
	static class Stuck implements FilterTable.Condition<String>, Boolf<String> {
		public boolean needUpdate() { return true; }
		public boolean valid(String name) { return true; }
		public boolean get(String name) { return true; }
	}

	static class Probe extends TemplateTable<String> {
		int rebuilds;

		Probe(arc.func.Boolf<String> f) { super(null, f); }

		/** act() 每次重建都会在末尾调 layout()，而探针里没有别的东西会 validate 它 */
		public void layout() {
			rebuilds++;
			super.layout();
		}
	}

	static Probe build(int n, int cols) {
		return build(n, cols, s -> true);
	}

	static Probe build(int n, int cols, arc.func.Boolf<String> f) {
		Probe tt = new Probe(f);
		tt.left().top();
		for (int i = 0; i < n; i++) {
			tt.bind("k" + i);
			tt.add(new Box());
			tt.unbind();
			if ((i + 1) % cols == 0) tt.newLine();
		}
		tt.rebuilds = 0;
		return tt;
	}

	/** 前台里真正挂着的元素个数 */
	static int visible(Probe tt) {
		int n = 0;
		for (Element e : tt.getChildren()) if (e != null) n++;
		return n;
	}

	static Table templateOf(TemplateTable<?> tt) throws Exception {
		java.lang.reflect.Field f = TemplateTable.class.getDeclaredField("template");
		f.setAccessible(true);
		return (Table) f.get(tt);
	}

	/** 模板 cells 里还有几个 cell 持有 element（= 该被摘下来却没摘的） */
	static int nonNullCells(Table t) {
		int n = 0;
		for (int i = 0; i < t.getCells().size; i++) {
			if (t.getCells().get(i).get() != null) n++;
		}
		return n;
	}

	static void run(int n, int frames) {
		Probe tt = build(n, 10);
		tt.act(1f);          // 先让第一帧把前台建出来
		tt.rebuilds = 0;
		long t0 = System.nanoTime();
		for (int i = 0; i < frames; i++) tt.act(1f);
		long ns = System.nanoTime() - t0;
		System.out.printf("n=%4d  frames=%d  rebuilds=%4d  %8.3f ms/frame  visible=%d%n",
		 n, frames, tt.rebuilds, ns / 1e6 / frames, visible(tt));
	}

	/** 一次「可见集合真的变了」的过滤切换要花多久（含清空前台 + 重新填槽 + layout） */
	static void benchRebuild(int n, int cols, int rounds) {
		Probe tt = build(n, cols);
		tt.act(1f);
		long best = Long.MAX_VALUE;
		for (int r = 0; r < rounds; r++) {
			final int parity = r & 1;
			tt.validator = s -> (Integer.parseInt(s.substring(1)) & 1) == parity;
			// 两版的重建时机不同：一版在 updateNow() 里同步做，一版延到下一帧 act()。
			// 所以计时必须把这两个调用一起包进来，否则比的不是同一件事。
			long t0 = System.nanoTime();
			tt.updateNow();
			tt.act(1f);
			best = Math.min(best, System.nanoTime() - t0);
		}
		System.out.printf("n=%4d 过滤变化一帧：%7.3f ms  visible=%d%n", n, best / 1e6, visible(tt));
	}

	public static void main(String[] args) throws Exception {
		System.out.println("=== 稳态：可见集合不变时不应重建 ===");
		run(50, 60);
		run(200, 60);
		run(600, 60);

		System.out.println();
		System.out.println("=== 可见集合变化时必须重建（且只重建一次） ===");
		Probe tt = build(100, 10);
		tt.act(1f);
		tt.rebuilds = 0;
		tt.validator = s -> s.endsWith("0");     // 100 个里剩 10 个
		tt.updateNow();
		tt.act(1f);
		System.out.printf("过滤后 1 帧：rebuilds=%d  visible=%d（期望 1 / 10）%n",
		 tt.rebuilds, visible(tt));

		tt.rebuilds = 0;
		tt.act(1f);
		tt.act(1f);
		System.out.printf("再跑 2 帧： rebuilds=%d（期望 0）%n", tt.rebuilds);

		tt.validator = s -> true;                // 取消过滤 -> 全部回来
		tt.updateNow();
		tt.act(1f);
		System.out.printf("取消过滤：  rebuilds=%d  visible=%d（期望 1 / 100）%n",
		 tt.rebuilds, visible(tt));

		System.out.println();
		System.out.println("=== 槽位紧凑补位 ===");
		Probe c = build(20, 5);   // 4 行 × 5 列
		c.act(1f);
		c.validator = s -> !s.endsWith("1") && !s.endsWith("3");  // 去掉 k1/k11/k3/k13
		c.updateNow();
		c.act(1f);
		System.out.printf("20 个里剩 %d 个（期望 16）；前台 cells=%d（期望 16）%n",
		 visible(c), c.getCells().size);
		// 槽位约束按模板前 16 个槽位给，第 5/10/15 个槽位（原行末）仍要换行
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < c.getCells().size; i++) {
			sb.append(c.getCells().get(i).isEndRow() ? '|' : '.');
		}
		System.out.println("行末标记：" + sb + "（期望 ....|....|....||）");

		System.out.println();
		System.out.println("=== noFilter 开关 ===");
		Probe nf = build(20, 5, s -> s.endsWith("0"));
		nf.act(1f);
		System.out.printf("初始：visible=%d（期望 2：k0/k10）%n", visible(nf));
		nf.rebuilds = 0;
		nf.noFilter = true;
		nf.act(1f);
		System.out.printf("开 noFilter：rebuilds=%d visible=%d（期望 1 / 20）%n",
		 nf.rebuilds, visible(nf));
		nf.rebuilds = 0;
		nf.act(1f);
		System.out.printf("保持：      rebuilds=%d（期望 0）%n", nf.rebuilds);

		System.out.println();
		System.out.println("=== 过滤变化一帧的完整开销 ===");
		benchRebuild(200, 10, 20);
		benchRebuild(600, 10, 20);
		benchRebuild(1200, 10, 10);

		System.out.println();
		System.out.println("=== needUpdate() 卡在 true 时不应每帧重建 ===");
		Probe st = build(100, 10, new Stuck());
		st.act(1f);
		st.rebuilds = 0;
		for (int i = 0; i < 60; i++) st.act(1f);
		System.out.printf("rebuilds=%d（期望 0） visible=%d（期望 100）%n", st.rebuilds, visible(st));
		st.noFilter = true;
		st.rebuilds = 0;
		for (int i = 0; i < 60; i++) st.act(1f);
		System.out.printf("开 noFilter（可见集合没变）rebuilds=%d（期望 0）%n", st.rebuilds);

		System.out.println();
		System.out.println("=== 初始就不匹配的组：applied 初值陷阱 ===");
		Probe im = build(20, 5, s -> s.endsWith("0"));   // 构造时就只有 k0/k10 匹配
		im.act(1f);
		Table tpl = templateOf(im);
		System.out.printf("前台 visible=%d（期望 2）  tt.prefW=%.1f（期望 40 = 2 列 × 20）%n",
		 visible(im), im.getPrefWidth());
		System.out.printf("template 里残留：非空 cell=%d（期望 0） children=%d（期望 0） tpl.prefW=%.1f（期望 0）%n",
		 nonNullCells(tpl), tpl.getChildren().size, tpl.getPrefWidth());
		im.act(1f);
		im.act(1f);
		System.out.printf("再跑 2 帧后：非空 cell=%d（期望 0） children=%d（期望 0）%n",
		 nonNullCells(tpl), tpl.getChildren().size);

		System.out.println();
		System.out.println("=== 初始全不匹配，再全放开 ===");
		Probe none = build(20, 5, s -> false);
		none.act(1f);
		Table nt = templateOf(none);
		System.out.printf("全不匹配：visible=%d（期望 0） 残留=%d（期望 0） children=%d（期望 0） prefW=%.1f（期望 0）%n",
		 visible(none), nonNullCells(nt), nt.getChildren().size, none.getPrefWidth());
		none.validator = s -> true;
		none.updateNow();
		none.act(1f);
		System.out.printf("全放开：  visible=%d（期望 20） 残留=%d（期望 0） prefW=%.1f（期望 100）%n",
		 visible(none), nonNullCells(nt), none.getPrefWidth());

		System.out.println();
		System.out.println("=== clear 之后 ===");
		Probe cl = build(10, 5);
		cl.act(1f);
		cl.clear();
		cl.act(1f);
		System.out.printf("clear 后 visible=%d（期望 0）%n", visible(cl));

		System.out.println();
		System.out.println("=== 元素 parent 归属 ===");
		Probe p = build(10, 5, s -> s.endsWith("0"));
		p.act(1f);
		int bad = 0;
		for (Element e : p.getChildren()) if (e.parent != p) bad++;
		System.out.printf("前台 %d 个元素，parent 不是本表的：%d（期望 0）%n", visible(p), bad);

		System.out.println();
		System.out.println("=== prefWidth（历史遗留，两版都一样） ===");
		Probe w = build(10, 5);
		float before = w.getPrefWidth();
		w.act(1f);
		float after = w.getPrefWidth();
		System.out.printf("before=%.1f  after=%.1f%n", before, after);
	}
}
