package modtools.utils.search;

import arc.func.Boolf;
import arc.scene.Element;
import arc.scene.Scene;
import arc.scene.ui.layout.*;
import arc.struct.Seq;

import java.lang.reflect.*;

/**
 * 自查文档里「对我方实现同样成立」的几条，逐条给出可执行证据。
 * <ul>
 *   <li>T1 / T1b：validator 中途抛异常，{@code built} 会不会被污染；污染之后能不能自愈。</li>
 *   <li>T2：{@code clear()} 的扫描次数（朴素 vs 先解绑）。</li>
 *   <li>T3：{@code clear()} 是否走到了 {@code Group.clearChildren} 里的 {@code setScene(null)}
 *       （{@code unfocus} 就在它上一行，同一个循环体）。</li>
 *   <li>T4：{@code re == true} 但可见集合没变的帧，{@code childrenChanged} 次数与前台顺序。</li>
 *   <li>T5：{@code getPrefWidth()} 的取值来源，特别是「模板被清空后缓存还停在清空前」的情况。</li>
 * </ul>
 */
public class EdgeProbe {

	/* ==================== 公共件 ==================== */

	static class Box extends Element {
		public float getPrefWidth() { return 20; }
		public float getPrefHeight() { return 20; }
	}

	@SuppressWarnings("unchecked")
	static Seq<Boolean> builtOf(TemplateTable<?> tt) throws Exception {
		Field f = TemplateTable.class.getDeclaredField("built");
		f.setAccessible(true);
		return (Seq<Boolean>) f.get(tt);
	}

	static Method DETACH;

	static void detachAll(Table t) throws Exception {
		if (DETACH == null) {
			DETACH = TemplateTable.class.getDeclaredMethod("detachAll", Table.class);
			DETACH.setAccessible(true);
		}
		DETACH.invoke(null, t);
	}

	/** 按 cols 一行的规则建一张表，元素是 {@code Box}，名字 k0..k{n-1} */
	static TemplateTable<String> build(int n, int cols, Boolf<String> f) {
		TemplateTable<String> tt = new TemplateTable<>(null, f);
		tt.left().top();
		for (int i = 0; i < n; i++) {
			tt.bind("k" + i);
			tt.add(new Box());
			tt.unbind();
			if ((i + 1) % cols == 0) tt.newLine();
		}
		return tt;
	}

	static String names(TemplateTable<?> tt) {
		StringBuilder sb = new StringBuilder();
		var           ch = tt.getChildren();
		for (int i = 0; i < ch.size; i++) {
			sb.append(ch.get(i).name == null ? "-" : ch.get(i).name).append(' ');
		}
		return sb.toString().trim();
	}

	/* ==================== T1 异常 ==================== */

	/**
	 * 新条件只隐藏 k0、k1，但在求值到第 3 个（k2）时抛异常。
	 * <p>抛点之后的槽位（k2、k3）在新条件下仍然是可见的——这正是旧写法会永久出错的那种组合：
	 * 抛之前写进去的 [false, false] 让下一轮「剩下的没变」，changed 于是为 false，
	 * 那两处已经落地、却从未渲染出去的隐藏就再也没人补。
	 */
	public static void main(String[] args) throws Exception {
		System.out.println("=== T1：validator 在 k2 上抛异常（两阶段提交）===");
		TemplateTable<String> tt = build(4, 2, s -> true);
		tt.act(1f);
		System.out.println("初始   前台可见=" + tt.getChildren().size + "  built=" + builtOf(tt));

		boolean[] boom = {true};
		int[]     calls = {0};
		tt.validator = s -> {
			if (boom[0] && ++calls[0] == 3) throw new IllegalStateException("正则编不过");
			return !s.equals("k0") && !s.equals("k1");
		};
		try {
			tt.updateNow();
			tt.act(1f);
			System.out.println("!! 预期抛异常，没有抛");
		} catch (IllegalStateException e) {
			System.out.println("抛出：" + e.getMessage());
		}
		System.out.println("抛异常后 前台可见=" + tt.getChildren().size + "  built=" + builtOf(tt)
		                   + "   ← built 应原样停在 [true, true, true, true]");

		boom[0] = false;
		calls[0] = 0;
		tt.updateNow();   // 调用方改输入时就是这么做的（WatchWindow:162）
		tt.act(1f);
		System.out.println("恢复后   前台可见=" + tt.getChildren().size + "  ← 应为 2");

		System.out.println();
		System.out.println("=== T1b 对照：把 built 手动污染成「边算边写」的中途状态 ===");
		TemplateTable<String> tb = build(4, 2, s -> true);
		tb.act(1f);
		Seq<Boolean> b = builtOf(tb);
		b.set(0, false);
		b.set(1, false);   // 模拟旧写法：抛之前已经写了两个
		tb.validator = s -> !s.equals("k0") && !s.equals("k1");
		tb.updateNow();
		tb.act(1f);
		System.out.println("恢复后   前台可见=" + tb.getChildren().size
		                   + "  ← 应为 2，污染后只剩这一处没人补，永远停在 4");

		/* ==================== T2 clear 的扫描次数 ==================== */
		System.out.println();
		System.out.println("=== T2：clear() 的扫描次数（CountTable 计 removeChild / getCell）===");
		for (int k : new int[]{300, 600, 1200}) {
			CountProbe.CountTable naive = fill(k);
			naive.c.zero();
			naive.clear();

			CountProbe.CountTable fixed = fill(k);
			fixed.c.zero();
			detachAll(fixed);   // 就是 TemplateTable.clear() 现在多出来的那一行
			fixed.clear();

			System.out.println("k=" + k + "\n  朴素      " + naive.c.line()
			                   + "\n  先解绑    " + fixed.c.line());
		}

		/* ==================== T3 setScene(null) ==================== */
		System.out.println();
		System.out.println("=== T3：clear() 是否走到 Group.clearChildren 里的 setScene(null) ===");
		TemplateTable<String> t3 = new TemplateTable<>(null, s -> true);
		SceneSpy[] spies = new SceneSpy[4];
		for (int i = 0; i < 4; i++) {
			spies[i] = new SceneSpy();
			spies[i].name = "k" + i;
			t3.bind("k" + i);
			t3.add(spies[i]);
			t3.unbind();
			if ((i + 1) % 2 == 0) t3.newLine();
		}
		t3.act(1f);
		for (SceneSpy s : spies) { s.sceneCleared = 0; s.counting = true; }
		t3.clear();
		for (SceneSpy s : spies) s.counting = false;
		for (SceneSpy s : spies) {
			System.out.println("  " + s.name + "  setScene(null) 次数=" + s.sceneCleared
			                   + "  parent=" + s.parent + "  getScene()=" + s.getScene());
		}
		System.out.println("  前台 children=" + t3.getChildren().size + " cells=" + t3.getCells().size);

		/* ==================== T4 无翻转帧 ==================== */
		System.out.println();
		System.out.println("=== T4：re == true 但可见集合没变 ===");
		ChanTT t4 = new ChanTT(s -> true);
		for (int i = 0; i < 6; i++) {
			t4.bind("k" + i);
			t4.add(new Box());
			t4.unbind();
			if ((i + 1) % 3 == 0) t4.newLine();
		}
		t4.act(1f);
		String before = names(t4);
		t4.changed = 0;
		t4.adds = 0;
		t4.updateNow();     // evalDirty = true，可见集合不变
		t4.act(1f);
		System.out.println("  childrenChanged=" + t4.changed + "  addChild=" + t4.adds
		                   + "   ← 都应为 0");
		System.out.println("  顺序一致=" + before.equals(names(t4)) + "  [" + before + "]");

		/* ==================== T5 prefWidth ==================== */
		System.out.println();
		System.out.println("=== T5：getPrefWidth 的取值来源 ===");
		PrefTT t5 = new PrefTT(s -> true);
		t5.left().top();
		for (int i = 0; i < 4; i++) {
			t5.bind("k" + i);
			t5.add(new Box());
			t5.unbind();
			if ((i + 1) % 2 == 0) t5.newLine();
		}
		report(t5, "add 之后、还没 act（值全在模板里）", 40);
		t5.act(1f);
		report(t5, "重建后（值全在前台）", 40);
		t5.validator = s -> s.equals("k0");
		t5.updateNow();
		t5.act(1f);
		report(t5, "只剩 1 个可见", 20);
		t5.validator = s -> false;
		t5.updateNow();
		t5.act(1f);
		report(t5, "全部隐藏", 0);
	}

	static void report(PrefTT t, String what, float expect) throws Exception {
		System.out.printf("  %-30s 前台(cells=%d pref=%5.1f sizeInvalid=%s)  模板(cells=%d pref=%5.1f sizeInvalid=%s)"
		                  + "  getPrefWidth=%5.1f   ← 应 %.0f%n",
		                  what,
		                  t.getCells().size, PrefTT.pref(t), PrefTT.invalid(t),
		                  PrefTT.tplCells(t), PrefTT.tplPref(t), PrefTT.tplInvalid(t),
		                  t.getPrefWidth(), expect);
	}

	static CountProbe.CountTable fill(int k) {
		CountProbe.CountTable t = new CountProbe.CountTable();
		for (int i = 0; i < k; i++) {
			t.add(new CountProbe.Box());
			if ((i + 1) % 10 == 0) t.row();
		}
		return t;
	}

	/** 只用来数 setScene(null)：Group.clearChildren 对每个 child 都会调它 */
	static class SceneSpy extends Box {
		boolean counting;
		int     sceneCleared;

		protected void setScene(Scene stage) {
			if (counting && stage == null) sceneCleared++;
			super.setScene(stage);
		}
	}

	/** 数 childrenChanged 与 addChild */
	static class ChanTT extends TemplateTable<String> {
		int changed, adds;

		ChanTT(Boolf<String> f) { super(null, f); }

		protected void childrenChanged() {
			changed++;
			super.childrenChanged();
		}
		public void addChild(Element actor) {
			adds++;
			super.addChild(actor);
		}
	}

	/** 把「前台自己的 pref」和「模板的 pref」拆开看：直接读字段，绕开虚分派 */
	static class PrefTT extends TemplateTable<String> {
		PrefTT(Boolf<String> f) { super(null, f); }

		static Field PREF, INVALID, TPL;

		static void init() throws Exception {
			if (PREF != null) return;
			PREF = Table.class.getDeclaredField("tablePrefWidth");
			PREF.setAccessible(true);
			INVALID = Table.class.getDeclaredField("sizeInvalid");
			INVALID.setAccessible(true);
			TPL = TemplateTable.class.getDeclaredField("template");
			TPL.setAccessible(true);
		}
		static float pref(Object t) throws Exception { init(); return PREF.getFloat(t); }
		static boolean invalid(Object t) throws Exception { init(); return INVALID.getBoolean(t); }
		static Table tpl(Object t) throws Exception { init(); return (Table) TPL.get(t); }

		static float tplPref(Object t) throws Exception { return pref(tpl(t)); }
		static boolean tplInvalid(Object t) throws Exception { return invalid(tpl(t)); }
		static int tplCells(Object t) throws Exception { return tpl(t).getCells().size; }
	}
}
