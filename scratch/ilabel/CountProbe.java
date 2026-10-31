package modtools.utils.search;

import arc.func.Boolf;
import arc.scene.Element;
import arc.scene.ui.layout.*;

import java.lang.reflect.Field;

/**
 * 插桩计数：用子类覆写 {@code addChild} / {@code removeChild} / {@code getCell} / {@code clearChildren}，
 * 验证「稳定帧 = 0 次」，并量出重建帧里 {@code removeChild} 与 {@code getCell} 的真实次数。
 * <p>注意 {@code TemplateTable.act} 里写的是 {@code super.clearChildren()}（静态分派），
 * 覆写拦不到它，所以前台的 clearChildren 次数由「重建帧 = 1」直接给出，不靠计数。
 * <p>模板的字段类型随实现而变（普通 {@code Table} 或 {@code FilterTable}），
 * 所以按字段声明类型选一个可计数的替身。
 */
public class CountProbe {
	/** 计数与扫描长度的公共部分 */
	static class Counts {
		int  addChild, removeChild, getCell, clearChildren;
		/** 累计扫描长度：removeChild 每次扫 children、getCell 每次扫 cells，这两个和才是真正的比较次数 */
		long removeScans, cellScans;

		void zero() {
			addChild = removeChild = getCell = clearChildren = 0;
			removeScans = cellScans = 0;
		}
		String line() {
			return "addChild=" + addChild + " removeChild=" + removeChild + " getCell=" + getCell
			       + " clearChildren=" + clearChildren
			       + "  扫描次数=" + (removeScans + cellScans);
		}
	}

	static class Box extends Element {
		public float getPrefWidth() { return 20; }
		public float getPrefHeight() { return 20; }
	}

	/** 可计数的普通 Table */
	static class CountTable extends Table {
		final Counts c = new Counts();

		public void addChild(Element actor) {
			c.addChild++;
			super.addChild(actor);
		}
		public boolean removeChild(Element actor, boolean unfocus) {
			c.removeChild++;
			c.removeScans += getChildren().size;
			return super.removeChild(actor, unfocus);
		}
		public void clearChildren() {
			c.clearChildren++;
			super.clearChildren();
		}
		public <T extends Element> Cell getCell(T actor) {
			c.getCell++;
			c.cellScans += getCells().size;
			return super.getCell(actor);
		}
	}

	/** 可计数的 FilterTable（Gemini 第二版把模板留成了 FilterTable） */
	static class CountFilterTable extends FilterTable<String> {
		final Counts c = new Counts();

		public void addChild(Element actor) {
			c.addChild++;
			super.addChild(actor);
		}
		public boolean removeChild(Element actor, boolean unfocus) {
			c.removeChild++;
			c.removeScans += getChildren().size;
			return super.removeChild(actor, unfocus);
		}
		public void clearChildren() {
			c.clearChildren++;
			super.clearChildren();
		}
		public <T extends Element> Cell getCell(T actor) {
			c.getCell++;
			c.cellScans += getCells().size;
			return super.getCell(actor);
		}
	}

	/** 可计数的 TemplateTable：前台自己就是这张表 */
	static class CountTT extends TemplateTable<String> {
		int addChild, removeChild, getCell;

		CountTT(Boolf<String> f) { super(null, f); }

		public void addChild(Element actor) {
			addChild++;
			super.addChild(actor);
		}
		public boolean removeChild(Element actor, boolean unfocus) {
			removeChild++;
			return super.removeChild(actor, unfocus);
		}
		public <T extends Element> Cell getCell(T actor) {
			getCell++;
			return super.getCell(actor);
		}
		void zero() { addChild = removeChild = getCell = 0; }
		String line() {
			return "addChild=" + addChild + " removeChild=" + removeChild + " getCell=" + getCell;
		}
	}

	/** 把 TemplateTable 私有的 template 换成可计数的替身（构造后它还是空的，换掉不丢状态） */
	static Counts swapTemplate(TemplateTable<?> tt) throws Exception {
		Field f = TemplateTable.class.getDeclaredField("template");
		f.setAccessible(true);
		if (FilterTable.class.isAssignableFrom(f.getType())) {
			CountFilterTable t = new CountFilterTable();
			f.set(tt, t);
			return t.c;
		}
		CountTable t = new CountTable();
		f.set(tt, t);
		return t.c;
	}

	static CountTT build(int n, int cols, Boolf<String> f, Counts[] out) throws Exception {
		CountTT tt = new CountTT(f);
		out[0] = swapTemplate(tt);
		tt.left().top();
		for (int i = 0; i < n; i++) {
			tt.bind("k" + i);
			tt.add(new Box());
			tt.unbind();
			if ((i + 1) % cols == 0) tt.newLine();
		}
		return tt;
	}

	public static void main(String[] args) throws Exception {
		Counts[] out = new Counts[1];
		CountTT tt = build(600, 10, s -> true, out);
		Counts tpl = out[0];
		tt.act(1f);                                   // 首次重建

		System.out.println("=== 稳态 60 帧（可见集合不变）===");
		tt.zero();
		tpl.zero();
		for (int i = 0; i < 60; i++) tt.act(1f);
		System.out.println("前台：" + tt.line());
		System.out.println("模板：" + tpl.line());

		System.out.println();
		System.out.println("=== 重建帧（600 个里剩 300，可见集合真的翻转）===");
		tt.zero();
		tpl.zero();
		tt.validator = s -> (Integer.parseInt(s.substring(1)) & 1) == 0;
		tt.updateNow();
		tt.act(1f);
		System.out.println("前台：" + tt.line());
		System.out.println("模板：" + tpl.line());
		System.out.println("前台可见元素=" + tt.getChildren().size + "，前台 cells=" + tt.getCells().size);

		System.out.println();
		System.out.println("=== 重建帧（600 → 0，全部隐藏）===");
		Counts[] out2 = new Counts[1];
		CountTT  tt2  = build(600, 10, s -> true, out2);
		Counts   tpl2 = out2[0];
		tt2.act(1f);
		tt2.zero();
		tpl2.zero();
		tt2.validator = s -> false;
		tt2.updateNow();
		tt2.act(1f);
		System.out.println("前台：" + tt2.line());
		System.out.println("模板：" + tpl2.line());
		System.out.println("前台可见元素=" + tt2.getChildren().size);

		System.out.println();
		System.out.println("=== 对照：不解绑直接 clearChildren（600 个 = 重建帧清空前台时的实际数量）===");
		for (int k : new int[]{600, 1200}) {
			CountTable naive = new CountTable();
			for (int i = 0; i < k; i++) {
				naive.add(new Box());
				if ((i + 1) % 10 == 0) naive.row();
			}
			naive.c.zero();
			naive.clearChildren();
			System.out.println("      k=" + k + "  " + naive.c.line());
		}
	}
}
