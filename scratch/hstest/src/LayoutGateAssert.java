import nipx.LayoutGate;
import nipx.LayoutGate.FieldInfo;
import nipx.LayoutGate.Verdict;

import java.util.*;

/**
 * {@link LayoutGate} 规则表断言（§7.2 的精确变体）。
 *
 * <p>守的是"存活实例会不会读到零值"。判据全部来自真机实验
 * （{@code scratch/layoutprobe}）：新建实例正常初始化，<b>只有 redefine 之前
 * 已存在的实例</b>保留零值。</p>
 *
 * <p>本断言是**先红后绿**的那一半：在门接入 Tier 4 之前，这里断言的是纯函数本身。</p>
 */
public class LayoutGateAssert {

	static int failed = 0;
	static int passed = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   PASS  " : "   FAIL  ") + msg);
		if (ok) passed++; else failed++;
	}

	/** 简写：造一个字段。 */
	static FieldInfo f(String name, String desc, boolean statik, boolean synth) {
		return new FieldInfo(name, desc, statik, synth);
	}

	static FieldInfo plain(String name, String desc)      { return f(name, desc, false, false); }
	static FieldInfo statik(String name, String desc)     { return f(name, desc, true,  false); }
	static FieldInfo capture(String name, String desc)    { return f(name, desc, false, true);  }

	static List<FieldInfo> list(FieldInfo... fs) { return new ArrayList<>(Arrays.asList(fs)); }

	public static void main(String[] args) {
		System.out.println("== LayoutGate 规则表 ==");

		// ---------- 1) 字段集合完全相同 → 放行 ----------
		{
			System.out.println("-- 1) 无变化 --");
			var base = list(capture("this$0", "LHost;"), plain("n", "I"));
			var r = LayoutGate.check(base, list(capture("this$0", "LHost;"), plain("n", "I")));
			check(r.verdict() == Verdict.COMPATIBLE, "字段完全相同 → COMPATIBLE（实际 " + r.verdict() + "）");
		}

		// ---------- 2) 新增合成捕获字段 → 不兼容 ----------
		{
			System.out.println("-- 2) 新增捕获字段（本门存在的理由）--");
			var old = list(capture("this$0", "LHost;"));
			var neu = list(capture("this$0", "LHost;"), capture("val$extra", "I"));
			var r = LayoutGate.check(old, neu);
			check(r.verdict() == Verdict.ADDED_SYNTHETIC_FIELD,
				"新增 val$extra → ADDED_SYNTHETIC_FIELD（实际 " + r.verdict() + "）");
			check(r.detail().contains("val$extra"), "原因里写明字段名（不静默）");
			check(r.detail().contains("InitFix cannot patch"),
				"原因里写明 InitFix 覆盖不到（合成字段被 ClassDiffUtil 过滤）");
		}

		// ---------- 2b) 换捕获变量：val$a → val$b ----------
		{
			System.out.println("-- 2b) 换捕获变量 --");
			var old = list(capture("this$0", "LHost;"), capture("val$a", "I"));
			var neu = list(capture("this$0", "LHost;"), capture("val$b", "I"));
			var r = LayoutGate.check(old, neu);
			check(r.verdict() == Verdict.ADDED_SYNTHETIC_FIELD,
				"val$a→val$b 被判为新增（仍被挡住，符合预期）(实际 " + r.verdict() + ")");
		}

		// ---------- 3) 新增非合成字段 → 放行（交给 InitFix）----------
		{
			System.out.println("-- 3) 新增非合成字段 --");
			var old = list(capture("this$0", "LHost;"));
			var neu = list(capture("this$0", "LHost;"), plain("userField", "I"));
			var r = LayoutGate.check(old, neu);
			check(r.verdict() == Verdict.COMPATIBLE,
				"新增普通字段 → COMPATIBLE（InitFix 能补）(实际 " + r.verdict() + ")");
			check(r.detail().contains("userField"), "原因里写明放行了哪个字段");
		}

		// ---------- 4) 纯删除 → 放行 ----------
		{
			System.out.println("-- 4) 纯删除 --");
			var old = list(capture("this$0", "LHost;"), capture("val$gone", "I"), plain("u", "I"));
			var neu = list(capture("this$0", "LHost;"));
			var r = LayoutGate.check(old, neu);
			check(r.verdict() == Verdict.COMPATIBLE,
				"纯删除 → COMPATIBLE（新代码不再引用，不会读到零值）(实际 " + r.verdict() + ")");
			check(r.detail().contains("removed"), "原因里写明删了哪些字段");
		}

		// ---------- 4b) 纯删除合成字段单独可辨 ----------
		{
			System.out.println("-- 4b) 纯删除合成捕获字段 --");
			var old = list(capture("this$0", "LHost;"), capture("val$gone", "I"));
			var neu = list(capture("this$0", "LHost;"));
			var r = LayoutGate.check(old, neu);
			check(r.verdict() == Verdict.COMPATIBLE,
				"删除 val$gone → COMPATIBLE（REMOVED_SYNTHETIC_FIELD 与 COMPATIBLE 同义放行）");
		}

		// ---------- 5) 同名改类型 → 不兼容 ----------
		{
			System.out.println("-- 5) 同名改类型 --");
			var old = list(plain("a", "I"));
			var neu = list(plain("a", "J"));
			var r = LayoutGate.check(old, neu);
			check(r.verdict() == Verdict.CHANGED_FIELD_TYPE,
				"int→long 同名 → CHANGED_FIELD_TYPE（实际 " + r.verdict() + "）");
			check(r.detail().contains("I -> J"), "原因里写出类型变化");
		}

		// ---------- 5b) 捕获字段改类型 ----------
		{
			System.out.println("-- 5b) 捕获字段改类型 --");
			var old = list(capture("val$x", "I"));
			var neu = list(capture("val$x", "Ljava/lang/String;"));
			var r = LayoutGate.check(old, neu);
			check(r.verdict() == Verdict.CHANGED_FIELD_TYPE,
				"val$x 的 int→String → CHANGED_FIELD_TYPE（实际 " + r.verdict() + "）");
		}

		// ---------- 6) static 性变化 → 不兼容（且哈希看不见这一位）----------
		{
			System.out.println("-- 6) static 性变化 --");
			var old = list(plain("a", "I"));
			var neu = list(statik("a", "I"));
			var r = LayoutGate.check(old, neu);
			check(r.verdict() == Verdict.CHANGED_STATICNESS,
				"实例→静态 → CHANGED_STATICNESS（实际 " + r.verdict() + "）");
			check(r.detail().contains("instance -> static"), "原因里写明方向");
		}
		{
			var old = list(statik("a", "I"));
			var neu = list(plain("a", "I"));
			var r = LayoutGate.check(old, neu);
			check(r.verdict() == Verdict.CHANGED_STATICNESS, "静态→实例 同样被拦");
		}

		// ---------- 7) 优先级：改类型要先于新增报出 ----------
		//
		// 一个类可能同时有多种变化。改类型/改 static 性比"新增"更危险（旧值直接错读），
		// 所以必须先报。断言顺序稳定，避免日志随字段遍历顺序漂移。
		{
			System.out.println("-- 7) 多变化时的优先级 --");
			var old = list(plain("a", "I"));
			var neu = list(plain("a", "J"), capture("val$new", "I"));
			var r = LayoutGate.check(old, neu);
			check(r.verdict() == Verdict.CHANGED_FIELD_TYPE,
				"同时有改类型与新增时，报改类型（实际 " + r.verdict() + "）");
		}

		// ---------- 8) 合成字段判定：ACC_SYNTHETIC 与名字前缀双保险 ----------
		{
			System.out.println("-- 8) 合成判定双保险 --");
			var viaFlag = LayoutGate.of(List.of(fieldNode("weird", "I", 0, true)));
			check(viaFlag.size() == 1 && viaFlag.get(0).isSynthetic(),
				"靠 ACC_SYNTHETIC 认出无前缀的合成字段");
			var viaName = LayoutGate.of(List.of(fieldNode("val$byName", "I", 0, false)));
			check(viaName.get(0).isSynthetic(),
				"靠 val$ 前缀认出丢了标志位的捕获字段");
			var thisZero = LayoutGate.of(List.of(fieldNode("this$0", "LHost;", 0, false)));
			check(thisZero.get(0).isSynthetic(), "this$0 被认作合成捕获");
			var plainF = LayoutGate.of(List.of(fieldNode("normal", "I", 0, false)));
			check(!plainF.get(0).isSynthetic(), "普通字段不被误判为合成");
		}

		// ---------- 9) 空字段表 ----------
		{
			System.out.println("-- 9) 边界 --");
			check(LayoutGate.check(list(), list()).compatible(), "两侧都空 → 放行");
			check(LayoutGate.check(list(), list(plain("a", "I"))).compatible(),
				"旧空新有普通字段 → 放行");
			check(!LayoutGate.check(list(), list(capture("val$x", "I"))).compatible(),
				"旧空新有捕获字段 → 拒绝");
		}

		System.out.println();
		System.out.println("通过 " + passed + " 条；失败 " + failed + " 条");
		System.out.println(failed == 0 ? "ALL ASSERTIONS PASSED" : (failed + " ASSERTION(S) FAILED"));
		if (failed != 0) System.exit(1);
	}

	/** 造一个 ASM FieldNode（避免测试依赖具体构造器重载）。 */
	static org.objectweb.asm.tree.FieldNode fieldNode(String name, String desc, int access, boolean synthetic) {
		int acc = access | (synthetic ? org.objectweb.asm.Opcodes.ACC_SYNTHETIC : 0);
		return new org.objectweb.asm.tree.FieldNode(
			org.objectweb.asm.Opcodes.ASM9, acc, name, desc, null, null);
	}
}
