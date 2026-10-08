import nipx.AnonClassAligner;
import nipx.HotSwapAgent;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §2.1「javac 枚举 Switch 映射表」的对齐行为回归守卫。
 *
 * <p><b>实测事实（javac 21，见 commit 里的 javap 记录）</b>：{@code Foo$N} SwitchMap 类
 * 在 Lower 阶段惰性分配，编号恒在<b>所有匿名类之后</b>（v1 {@code $2}，前插一个匿名类后
 * 变 {@code $3}）；它是 {@code ACC_SYNTHETIC}，带 {@code static final int[] $SwitchMap$...}，
 * {@code InnerClasses.innerName == null}、{@code EnclosingMethod} 是类级的（无方法）。
 * 因此它<b>满足</b> {@code AnonClassAligner.isAnonymousClassName}，当前被当作匿名类纳入
 * 对齐（而非排除）。</p>
 *
 * <p><b>本类的作用</b>：钉住"纳入对齐"这一现状在下列四形态下是<b>正确</b>的。实测四个
 * 形态全部判对、零槽位篡夺，故 §2.1 的"排除 SwitchMap + 原名直通"字面改法<b>不实现</b>
 * （它会打开 §1.2 要防的槽位篡夺）；本文件即是该结论的回归守卫 + 文档依据。</p>
 *
 * <p><b>已知限制（务必别误读覆盖范围）</b>：夹具用<b>内存 javac（宿主 JDK）</b>编译，
 * 只证明在宿主 javac 的命名/属性规则下成立，<b>不覆盖 javac 8</b>。等
 * {@code AnonClassReproTest} 迁移、夹具扩成 8/17/21 时再补多 JDK 版本。</p>
 */
class SwitchMapAlignTest {

	private static final String HOST = "sm.Host";

	@AfterEach
	void resetStatics() {
		AnonClassAligner.TEST_REVERSE_ORDER = false;
		AnonClassAligner.MAX_ANON_PER_HOST = 128;
		AnonClassAligner.ALIGN_TIMEOUT_MS = 2000L;
		HotSwapAgent.ANON_STRICT = false;
		HotSwapAgent.ANON_DEBUG = false;
	}

	// ---------------- 夹具源码 ----------------

	/** 一个匿名类（调 doA）+ 一个 TimeUnit enum switch。 */
	private static final String V1 = src(
		"  void doA(){}\n" +
		"  void run() {\n" +
		"    new Runnable(){ public void run(){ doA(); } }.run();\n" +
		"    switch (TimeUnit.SECONDS) { case SECONDS: break; case MINUTES: break; }\n" +
		"  }\n");
	/** 前插一个**不同体**匿名类（doB），原匿名类保留，switch 不变。 */
	private static final String V2_INSERT = src(
		"  void doA(){}\n" +
		"  void doB(){}\n" +
		"  void run() {\n" +
		"    new Runnable(){ public void run(){ doB(); } }.run();\n" +
		"    new Runnable(){ public void run(){ doA(); } }.run();\n" +
		"    switch (TimeUnit.SECONDS) { case SECONDS: break; case MINUTES: break; }\n" +
		"  }\n");
	/** 前插不同体匿名类 + switch 的 case 集合变化（SwitchMap 内容变 → Tier 1 失效）。 */
	private static final String V2_INSERT_CASECHANGE = src(
		"  void doA(){}\n" +
		"  void doB(){}\n" +
		"  void run() {\n" +
		"    new Runnable(){ public void run(){ doB(); } }.run();\n" +
		"    new Runnable(){ public void run(){ doA(); } }.run();\n" +
		"    switch (TimeUnit.SECONDS) { case SECONDS: break; case HOURS: break; case DAYS: break; }\n" +
		"  }\n");
	/** v2：删除 enum switch（SwitchMap 消失）。 */
	private static final String V2_NOSWITCH = src(
		"  void doA(){}\n" +
		"  void run() {\n" +
		"    new Runnable(){ public void run(){ doA(); } }.run();\n" +
		"  }\n");
	/** 字段初始化器里的匿名类（无接口），v1；用于 §2.1 最难的形态。 */
	private static final String C_V1 =
		"package sm;\npublic class Host {\n  Object o = new Object(){};\n}\n";
	/** v2：删掉字段匿名类，新增 enum switch。 */
	private static final String C_V2 = src(
		"  void run() {\n" +
		"    switch (TimeUnit.SECONDS) { case SECONDS: break; case MINUTES: break; }\n" +
		"  }\n");

	private static String src(String body) {
		return "package sm;\nimport java.util.concurrent.TimeUnit;\npublic class Host {\n" + body + "}\n";
	}

	// ---------------- 工具 ----------------

	private static Map<String, byte[]> compile(String hostSrc) {
		return LayoutGateAssert.compile(Map.of(HOST, hostSrc));
	}

	private static Map<String, byte[]> anonUnder(Map<String, byte[]> m) {
		Map<String, byte[]> out = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : m.entrySet()) {
			if (AnonClassAligner.isAnonymousClassName(HOST, e.getKey())) out.put(e.getKey(), e.getValue());
		}
		return out;
	}

	/** 夹具前提守卫：返回带 {@code $SwitchMap$} 字段的合成类名，没有则 null（防用例空过）。 */
	private static String switchMapClass(Map<String, byte[]> classes) {
		for (Map.Entry<String, byte[]> e : classes.entrySet()) {
			ClassNode cn = LayoutGateAssert.parse(e.getValue());
			if (cn.fields == null) continue;
			for (FieldNode f : cn.fields) {
				if (f.name != null && f.name.startsWith("$SwitchMap$")) return e.getKey();
			}
		}
		return null;
	}

	private static AnonClassAligner.Result align(Map<String, byte[]> c1, Map<String, byte[]> c2,
	                                             Predicate<String> live) {
		return AnonClassAligner.align("sm/Host", c2.get(HOST), anonUnder(c1), anonUnder(c2),
			n -> c1.get(n.replace('/', '.')), n -> c2.get(n.replace('/', '.')), live);
	}

	private static String stats(AnonClassAligner.Result r) {
		return "[" + r.stats + " renameMap=" + r.renameMap + " orphans=" + r.orphanOldClasses + "]";
	}

	// ---------------- 前提：夹具确实生成 SwitchMap ----------------

	@Test
	void fixtureActuallyGeneratesSwitchMap() {
		Map<String, byte[]> v1 = compile(V1);
		String sm1 = switchMapClass(v1);
		assertNotNull(sm1, "夹具前提：v1 必须生成带 $SwitchMap$ 字段的合成类（否则用例空过）");
		// v1 里 SwitchMap 恒在所有匿名类之后：匿名 $1，SwitchMap $2
		assertEquals("sm.Host$2", sm1, "javac 实测：SwitchMap 编号在匿名类之后");

		Map<String, byte[]> v2 = compile(V2_INSERT);
		String sm2 = switchMapClass(v2);
		assertEquals("sm.Host$3", sm2, "前插一个匿名类后，SwitchMap 从 $2 后移到 $3");
	}

	// ---------------- A：前插匿名类，switch 不变 ----------------

	@Test
	void a_insertAnon_switchUnchanged() {
		Map<String, byte[]> v1 = compile(V1);
		Map<String, byte[]> v2 = compile(V2_INSERT);
		AnonClassAligner.Result r = align(v1, v2, n -> false);

		assertArrayEquals(new String[0], r.orphanOldClasses.toArray(), "A：零孤儿 " + stats(r));
		// 原匿名类（doA，内容不变）保住旧 $1；SwitchMap 保住旧 $2；新插入的（doB）拿新号。
		assertEquals("sm/Host$1", r.renameMap.get("sm/Host$2"), "A：原匿名类保住旧身份 $1 " + stats(r));
		assertEquals("sm/Host$2", r.renameMap.get("sm/Host$3"), "A：SwitchMap 映射回自己的旧槽 $2 " + stats(r));
		assertEquals("sm/Host$3", r.renameMap.get("sm/Host$1"), "A：新插入的匿名类拿全新号 $3 " + stats(r));
		assertTrue(r.stats.tier1Matches >= 2, "A：原匿名类与不变体 SwitchMap 都应走 Tier 1 " + stats(r));
		assertEquals(0, r.stats.tier4Matches, "A：不应落到 Tier 4 " + stats(r));
	}

	// ---------------- B：前插匿名类 + case 集合变化 ----------------

	@Test
	void b_insertAnon_caseSetChanged() {
		Map<String, byte[]> v1 = compile(V1);
		Map<String, byte[]> v2 = compile(V2_INSERT_CASECHANGE);
		AnonClassAligner.Result r = align(v1, v2, n -> false);

		assertArrayEquals(new String[0], r.orphanOldClasses.toArray(), "B：零孤儿 " + stats(r));
		assertEquals("sm/Host$1", r.renameMap.get("sm/Host$2"), "B：原匿名类保住旧身份 $1 " + stats(r));
		assertEquals("sm/Host$2", r.renameMap.get("sm/Host$3"), "B：SwitchMap 内容变后仍映射回旧槽 $2 " + stats(r));
		assertEquals("sm/Host$3", r.renameMap.get("sm/Host$1"), "B：新插入的匿名类拿全新号 $3 " + stats(r));
		// SwitchMap 内容变了 → Tier 1 失效；它靠结构签名（Tier 3）回到旧槽，且不得落到 Tier 4。
		assertTrue(r.stats.tier3Matches >= 1, "B：变体的 SwitchMap 应由 Tier 3 结构签名裁定 " + stats(r));
		assertEquals(0, r.stats.tier4Matches, "B：不得落到 Tier 4（否则槽位可能被抢）" + stats(r));
	}

	// ---------------- C：字段匿名类删除 + 新增 switch（最难形态）----------------

	@Test
	void c_fieldInitAnonDeleted_switchAdded() {
		Map<String, byte[]> v1 = compile(C_V1);
		Map<String, byte[]> v2 = compile(C_V2);
		assertNotNull(switchMapClass(v2), "C：v2 确实生成了 SwitchMap");
		AnonClassAligner.Result r = align(v1, v2, n -> true); // 有存活实例

		// 实测：两者 outerMethod 不同（旧匿名类回退解析到 <init>，SwitchMap 无实例化点 → null），
		// 故 Tier 4 谓词不成立 —— 不配对，旧匿名类成为孤儿保留。槽位未被 SwitchMap 篡夺。
		assertTrue(r.orphanOldClasses.contains("sm/Host$1"), "C：旧字段匿名类成为孤儿并保留 " + stats(r));
		assertFalse(r.renameMap.containsKey("sm/Host$1") && "sm/Host$1".equals(r.renameMap.get("sm/Host$1")),
			"C：SwitchMap 不得顶替旧匿名类的 $1 槽位 " + stats(r));
		int paired = r.stats.tier1Matches + r.stats.tier2Matches + r.stats.tier3Matches + r.stats.tier4Matches;
		assertEquals(0, paired, "C：无任何配对（outerMethod 差异挡在 Tier 4 之前）" + stats(r));
	}

	// ---------------- D：删除 enum switch ----------------

	@Test
	void d_switchRemoved_orphansOldSwitchMap() {
		Map<String, byte[]> v1 = compile(V1);
		Map<String, byte[]> v2 = compile(V2_NOSWITCH);
		AnonClassAligner.Result r = align(v1, v2, n -> true);

		assertTrue(r.orphanOldClasses.contains("sm/Host$2"), "D：旧 SwitchMap 成为孤儿并保留旧语义 " + stats(r));
		assertEquals("sm/Host$1", r.renameMap.get("sm/Host$1"), "D：匿名类身份不受影响 " + stats(r));
	}
}
