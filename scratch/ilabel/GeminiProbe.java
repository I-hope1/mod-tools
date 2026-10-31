package modtools.utils.search;

import arc.scene.Element;
import arc.scene.ui.layout.*;

/**
 * 两个用途：
 * <ol>
 *   <li>守住 {@code FilterTable} 的 HEAD 语义：往一个「已判为不匹配」的组里再加元素，
 *       下一次 {@code filter} 必须把它摘掉 —— 这条正是 {@code applied} 闸门会破坏的性质；</li>
 *   <li>量化「手动解绑」相对直接搬元素省了多少。</li>
 * </ol>
 */
public class GeminiProbe {
	/** 固定 20×20 的哑元素：Cell.size() 会走 Scl.scl()，而探针里没有 Core.app */
	static class Box extends Element {
		public float getPrefWidth() { return 20; }
		public float getPrefHeight() { return 20; }
	}

	static int nonNullCells(Table t) {
		int n = 0;
		for (int i = 0; i < t.getCells().size; i++) if (t.getCells().get(i).get() != null) n++;
		return n;
	}

	/** 一张装好 n 个元素的表，未计时 */
	static FilterTable<String> filled(int n, int cols) {
		FilterTable<String> tpl = new FilterTable<>();
		for (int i = 0; i < n; i++) {
			tpl.add(new Box());
			if ((i + 1) % cols == 0) tpl.row();
		}
		return tpl;
	}

	/** 方案 A：直接 fg.add(el) —— 触发 template.removeChild → removeValue + getCell */
	static long movePlain(FilterTable<String> tpl, Table fg) {
		long t0 = System.nanoTime();
		var cells = tpl.getCells();
		for (int i = 0; i < cells.size; i++) {
			Element el = cells.get(i).get();
			if (el != null) fg.add(el);
		}
		return System.nanoTime() - t0;
	}

	/** 方案 B：先手动摘掉 template.children（父指针置空），再 clearElement + fg.add */
	static long moveTrick(FilterTable<String> tpl, Table fg) {
		long t0 = System.nanoTime();
		var       ch    = tpl.getChildren();
		Element[] items = ch.begin();
		for (int i = 0, k = ch.size; i < k; i++) items[i].parent = null;
		ch.end();
		ch.clear();

		var cells = tpl.getCells();
		for (int i = 0; i < cells.size; i++) {
			Cell<?> c  = cells.get(i);
			Element el = c.get();
			if (el == null) continue;
			c.clearElement();
			fg.add(el);
		}
		return System.nanoTime() - t0;
	}

	static void benchMove(int n, int cols, int rounds) {
		long plain = Long.MAX_VALUE, trick = Long.MAX_VALUE;
		for (int r = 0; r < rounds; r++) {
			FilterTable<String> a = filled(n, cols);
			plain = Math.min(plain, movePlain(a, new Table()));
			FilterTable<String> b = filled(n, cols);
			trick = Math.min(trick, moveTrick(b, new Table()));
		}
		System.out.printf("n=%4d  搬走全部元素：直接 add %7.3f ms | 手动解绑 %7.3f ms%n",
		 n, plain / 1e6, trick / 1e6);
	}

	static void benchClear(int n, int cols, int rounds) {
		long native_ = Long.MAX_VALUE, manual = Long.MAX_VALUE, bypass = Long.MAX_VALUE;
		for (int r = 0; r < rounds; r++) {
			Table a = fill(new Table(), n, cols);
			long t0 = System.nanoTime();
			a.clearChildren();
			native_ = Math.min(native_, System.nanoTime() - t0);

			Table b = fill(new Table(), n, cols);
			t0 = System.nanoTime();
			detach(b);
			b.getChildren().clear();
			manual = Math.min(manual, System.nanoTime() - t0);

			Table c = fill(new Table(), n, cols);
			t0 = System.nanoTime();
			detach(c);
			c.clearChildren();
			bypass = Math.min(bypass, System.nanoTime() - t0);
		}
		System.out.printf("n=%4d  清空：原生 %7.3f ms | 手动清 seq %7.3f ms | 解绑后走原生 %7.3f ms%n",
		 n, native_ / 1e6, manual / 1e6, bypass / 1e6);
	}
	static Table fill(Table t, int n, int cols) {
		for (int i = 0; i < n; i++) {
			t.add(new Box());
			if ((i + 1) % cols == 0) t.row();
		}
		return t;
	}
	/** 只把父指针置空，不动 children 列表 */
	static void detach(Table t) {
		var       ch    = t.getChildren();
		Element[] items = ch.begin();
		for (int i = 0, k = ch.size; i < k; i++) items[i].parent = null;
		ch.end();
	}

	/** 三种清空方式结束后，Table 的记账必须完全一致 */
	static void checkClearEquivalence() {
		System.out.println("=== 清空后的记账一致性（原生 vs 解绑后走原生） ===");
		for (int variant = 0; variant < 2; variant++) {
			Table t = fill(new Table(), 30, 5);
			Element[] kids = new Element[t.getChildren().size];
			for (int i = 0; i < kids.length; i++) kids[i] = t.getChildren().get(i);
			if (variant == 1) detach(t);
			t.clearChildren();
			int stale = 0;
			for (Element e : kids) if (e.parent != null) stale++;
			System.out.printf("%s：cells=%d children=%d 仍有 parent 的=%d%n",
			 variant == 0 ? "原生      " : "解绑+原生",
			 t.getCells().size, t.getChildren().size, stale);
		}
	}

	public static void main(String[] args) {
		System.out.println("=== 1. 往「已判为不匹配」的组里再加元素，filter 后必须被摘掉 ===");
		FilterTable<String> ft = new FilterTable<>();
		ft.bind("a");
		ft.add(new Box());
		ft.unbind();
		ft.filter(s -> false);
		System.out.printf("首次 filter(false)：非空 cell=%d（期望 0）%n", nonNullCells(ft));

		ft.bind("a");
		ft.add(new Box());   // Table.add 会把它 addChild 进表里
		ft.unbind();
		System.out.printf("又加一个（还没 filter）：非空 cell=%d（期望 1）%n", nonNullCells(ft));

		ft.filter(s -> false);
		System.out.printf("再 filter(false)：非空 cell=%d（期望 0）—— applied 闸门会留成 1%n",
		 nonNullCells(ft));

		System.out.println();
		System.out.println("=== 2. 两种搬运方式的开销 ===");
		benchMove(200, 10, 20);
		benchMove(600, 10, 20);
		benchMove(1200, 10, 10);

		System.out.println();
		System.out.println("=== 3. Table.clearChildren 到底是不是 O(K) ===");
		benchClear(200, 10, 20);
		benchClear(600, 10, 20);
		benchClear(1200, 10, 10);
		System.out.println();
		checkClearEquivalence();

		System.out.println();
		System.out.println("=== 4. 两种方式搬完之后 template 的 prefWidth ===");
		FilterTable<String> p = filled(20, 5);
		float before = p.getPrefWidth();
		movePlain(p, new Table());
		System.out.printf("直接 add：   before=%.1f  after=%.1f  （塌缩说明 sizeInvalid 被置脏）%n",
		 before, p.getPrefWidth());

		FilterTable<String> q = filled(20, 5);
		float before2 = q.getPrefWidth();
		moveTrick(q, new Table());
		System.out.printf("手动解绑：   before=%.1f  after=%.1f  非空 cell=%d children=%d%n",
		 before2, q.getPrefWidth(), nonNullCells(q), q.getChildren().size);
	}
}
