import nipx.AnonClassAligner;
import nipx.HotSwapAgent;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §2.2 第 4 类：**挂起编号避让**（reserved-slot avoidance）。
 *
 * <p>问题：{@code AlignmentTransaction} 提交后，对齐类被写进
 * {@code AnnotationTransformer.pendingAlignedClasses} 等待 JVM 首次加载。若在它被加载前
 * 又发生一批热更，同一宿主重新对齐时，一个**新增**匿名类会从 {@code $1} 开始找空闲编号，
 * 于是再次拿到 {@code Foo$3} —— 与那个还没加载的挂起类**撞名**。撞名后新字节码会覆盖掉
 * 挂起类还没生效的版本，且是静默的。</p>
 *
 * <p>修法：把挂起集合作为 {@code reserved} 传进 {@link AnonClassAligner}，只播种
 * {@code takenTargetNames}（已占用编号），让新编号跳过它。挂起类不参与 Tier 匹配 ——
 * 它没被加载、没有旧字节码，不是"旧类"。</p>
 *
 * <p><b>先红后绿</b>：本类在避让实现之前调用的是新的 {@code Set} 重载（桩会忽略 {@code reserved}），
 * 此时 "expectsHost$4" 之类的断言必然失败。实现避让后才转绿。变异检查见提交记录
 * （去掉避让判断，本类必须再红）。</p>
 */
class ReservedSlotsAlignTest {

	private static final String HOST = "rs.Host";
	private static final String NEST = "rs.Nest";

	@AfterEach
	void resetStatics() {
		AnonClassAligner.TEST_REVERSE_ORDER = false;
		AnonClassAligner.MAX_ANON_PER_HOST = 128;
		AnonClassAligner.ALIGN_TIMEOUT_MS = 2000L;
		HotSwapAgent.ANON_STRICT = false;
		HotSwapAgent.ANON_DEBUG = false;
	}

	// ---------------- 夹具源码（内存 javac）----------------

	/** old：两个匿名类 doA / doB（编号 $1 / $2）。 */
	private static final String HOST_V1 = host(
		"  void doA(){}\n" +
		"  void doB(){}\n" +
		"  void run() {\n" +
		"    new Runnable(){ public void run(){ doA(); } }.run();\n" +
		"    new Runnable(){ public void run(){ doB(); } }.run();\n" +
		"  }\n");

	/** 轮次 1/2 的 new：前插一个**新增**匿名类（doX）→ 它应拿全新编号。 */
	private static final String HOST_INSERT_X = host(
		"  void doA(){}\n" +
		"  void doB(){}\n" +
		"  void doX(){}\n" +
		"  void run() {\n" +
		"    new Runnable(){ public void run(){ doX(); } }.run();\n" +
		"    new Runnable(){ public void run(){ doA(); } }.run();\n" +
		"    new Runnable(){ public void run(){ doB(); } }.run();\n" +
		"  }\n");

	/** 轮次 2 的 new：与 HOST_INSERT_X 结构相同，但新类内容不同（doY）。 */
	private static final String HOST_INSERT_Y = host(
		"  void doA(){}\n" +
		"  void doB(){}\n" +
		"  void doY(){}\n" +
		"  void run() {\n" +
		"    new Runnable(){ public void run(){ doY(); } }.run();\n" +
		"    new Runnable(){ public void run(){ doA(); } }.run();\n" +
		"    new Runnable(){ public void run(){ doB(); } }.run();\n" +
		"  }\n");

	private static String host(String body) {
		return "package rs;\npublic class Host {\n" + body + "}\n";
	}

	/** old：外层匿名类 + 一个内层匿名类 Nest$1$1。 */
	private static final String NEST_V1 = nest(false);
	/** new：内层前插一个不同体匿名类 → 它应跳过旧 $1（已被占用）后取新编号。 */
	private static final String NEST_INSERT = nest(true);

	private static String nest(boolean insert) {
		return "package rs;\n" +
			"public class Nest {\n" +
			"  void doA(){}\n" +
			(insert ? "  void doZ(){}\n" : "") +
			"  void run() {\n" +
			"    new Runnable(){ public void run() {\n" +
			(insert ? "      new Runnable(){ public void run(){ doZ(); } }.run();\n" : "") +
			"      new Runnable(){ public void run(){ doA(); } }.run();\n" +
			"    }}.run();\n" +
			"  }\n" +
			"}\n";
	}

	// ---------------- 工具 ----------------

	private static Map<String, byte[]> anonUnder(String hostDot, Map<String, byte[]> m) {
		Map<String, byte[]> out = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : m.entrySet()) {
			if (AnonClassAligner.isAnonymousClassName(hostDot, e.getKey())) out.put(e.getKey(), e.getValue());
		}
		return out;
	}

	private static AnonClassAligner.Result align(String hostSlash, String hostDot,
	                                             Map<String, byte[]> oldAll, Map<String, byte[]> newAll,
	                                             Set<String> reserved) {
		return AnonClassAligner.align(hostSlash, newAll.get(hostDot),
			anonUnder(hostDot, oldAll), anonUnder(hostDot, newAll),
			n -> oldAll.get(n.replace('/', '.')), n -> newAll.get(n.replace('/', '.')),
			n -> false, reserved);
	}

	private static String stats(AnonClassAligner.Result r) {
		return "[" + r.stats + " renameMap=" + r.renameMap + " orphans=" + r.orphanOldClasses + "]";
	}

	// ---------------- 1) 平层：新增类必须跳过挂起编号 ----------------

	@Test
	void flat_newClassAvoidsReservedSlot() {
		Map<String, byte[]> oldAll = LayoutGateAssert.compile(Map.of(HOST, HOST_V1));

		// 轮次 1：新增 doX 拿到全新编号 $3。
		Map<String, byte[]> r1All = LayoutGateAssert.compile(Map.of(HOST, HOST_INSERT_X));
		AnonClassAligner.Result r1 = align("rs/Host", HOST, oldAll, r1All, Set.of());
		String round1New = r1.renameMap.get("rs/Host$1");
		assertEquals("rs/Host$3", round1New,
			"轮次 1：前插的新增匿名类应拿到全新编号 $3 " + stats(r1));
		assertEquals("rs/Host$1", r1.renameMap.get("rs/Host$2"), "轮次 1：doA 归位旧 $1 " + stats(r1));
		assertEquals("rs/Host$2", r1.renameMap.get("rs/Host$3"), "轮次 1：doB 归位旧 $2 " + stats(r1));

		// 轮次 2：结构相同但新类内容不同（doY）；$3 已被轮次 1 占用且尚未加载。
		Map<String, byte[]> r2All = LayoutGateAssert.compile(Map.of(HOST, HOST_INSERT_Y));
		Set<String> pending = Set.of(round1New);   // 模拟 pendingAlignedClasses 里的挂起类

		AnonClassAligner.Result r2 = align("rs/Host", HOST, oldAll, r2All, pending);
		assertEquals("rs/Host$4", r2.renameMap.get("rs/Host$1"),
			"轮次 2：新增类必须跳过挂起的 $3，改取 $4（否则覆盖挂起类）" + stats(r2));
		assertEquals("rs/Host$1", r2.renameMap.get("rs/Host$2"), "轮次 2：doA 仍归位旧 $1 " + stats(r2));
		assertEquals("rs/Host$2", r2.renameMap.get("rs/Host$3"), "轮次 2：doB 仍归位旧 $2 " + stats(r2));

		// 负向对照：不带 reserved 时它必然再次拿到 $3 —— 这就是被修复的撞名。
		AnonClassAligner.Result noReserved = align("rs/Host", HOST, oldAll, r2All, Set.of());
		assertEquals("rs/Host$3", noReserved.renameMap.get("rs/Host$1"),
			"负向对照：不带 reserved 时确实会重用 $3（确定性撞名，证明避让不是空过）" + stats(noReserved));
	}

	// ---------------- 2) 嵌套：level>1 分支也避让 ----------------

	@Test
	void nested_newClassAvoidsReservedSlot() {
		Map<String, byte[]> oldAll = LayoutGateAssert.compile(Map.of(NEST, NEST_V1));
		Map<String, byte[]> newAll = LayoutGateAssert.compile(Map.of(NEST, NEST_INSERT));

		// 前提：old 里确实有 Nest$1 与 Nest$1$1。
		assertNotNull(oldAll.get("rs.Nest$1"), "夹具前提：外层匿名类 Nest$1 存在");
		assertNotNull(oldAll.get("rs.Nest$1$1"), "夹具前提：内层匿名类 Nest$1$1 存在");

		// 负向对照：无 reserved → 新增内层类取 targetParent$2 = Nest$1$2。
		AnonClassAligner.Result noReserved = align("rs/Nest", NEST, oldAll, newAll, Set.of());
		String nestedNewNoReserved = noReserved.renameMap.get("rs/Nest$1$1");
		assertEquals("rs/Nest$1$2", nestedNewNoReserved,
			"负向对照：嵌套新增类默认取 Nest$1$2 " + stats(noReserved));
		assertEquals("rs/Nest$1$1", noReserved.renameMap.get("rs/Nest$1$2"),
			"嵌套：内容不变的内层类归位旧 Nest$1$1 " + stats(noReserved));

		// 避让：挂起 Nest$1$2 → 新增内层类必须跳过它取 Nest$1$3。
		AnonClassAligner.Result r = align("rs/Nest", NEST, oldAll, newAll,
			Set.of("rs/Nest$1$2"));   // 模拟上一个挂起的子类
		assertEquals("rs/Nest$1$3", r.renameMap.get("rs/Nest$1$1"),
			"嵌套：新增内层类必须跳过挂起的 Nest$1$2，改取 Nest$1$3（level>1 分支）" + stats(r));
		assertEquals("rs/Nest$1$1", r.renameMap.get("rs/Nest$1$2"),
			"嵌套：内容不变的内层类仍归位旧 Nest$1$1" + stats(r));
	}

	// ---------------- 3) 向后兼容：新重载在空 reserved 下与旧重载逐字等价 -----------------

	@Test
	void emptyReservedIsEquivalentToLegacyOverload() {
		Map<String, byte[]> oldAll = LayoutGateAssert.compile(Map.of(HOST, HOST_V1));
		Map<String, byte[]> newAll = LayoutGateAssert.compile(Map.of(HOST, HOST_INSERT_X));

		AnonClassAligner.Result legacy = AnonClassAligner.align("rs/Host", newAll.get(HOST),
			anonUnder(HOST, oldAll), anonUnder(HOST, newAll));
		AnonClassAligner.Result viaSet = align("rs/Host", HOST, oldAll, newAll, Set.of());

		assertEquals(legacy.renameMap, viaSet.renameMap, "空 reserved 下重命名映射必须逐字相同");
		assertEquals(legacy.orphanOldClasses, viaSet.orphanOldClasses, "空 reserved 下孤儿集合必须逐字相同");
		assertEquals(legacy.stats.toString(), viaSet.stats.toString(), "空 reserved 下统计必须逐字相同");
	}

	// ---------------- 4) 接线守卫：HotSwapAgent 必须把挂起快照传进去 -----------------
	//
	// 避让逻辑本身可单测，但"HotSwapAgent 到底有没有传 pendingAlignedClasses 的快照"单测守不住。
	// 用字节码扫描钉住调用点：必须存在一次 nipx/AnonClassAligner.align 调用，其描述符含
	// Ljava/util/Set;（即走了带 reserved 的重载）。与 Scenario 25 的常量池守卫同一思路。

	@Test
	void hotSwapAgentPassesReservedSnapshot() throws Exception {
		byte[] bytes = ownClassBytes(HotSwapAgent.class);
		assertNotNull(bytes, "取不到 HotSwapAgent.class 字节");

		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		boolean callsWithSet = false;
		for (MethodNode mn : cn.methods) {
			if (mn.instructions == null) continue;
			for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode min
				    && "nipx/AnonClassAligner".equals(min.owner)
				    && "align".equals(min.name)
				    && min.desc.contains("Ljava/util/Set;")) {
					callsWithSet = true;
				}
			}
		}
		assertTrue(callsWithSet,
			"接线守卫：HotSwapAgent 必须调用带 reserved(Set) 的 align 重载，否则挂起编号避让不会被触发");
	}

	private static byte[] ownClassBytes(Class<?> c) throws Exception {
		try (java.io.InputStream in = c.getResourceAsStream(c.getSimpleName() + ".class")) {
			return in == null ? null : in.readAllBytes();
		}
	}
}
