import nipx.HotSwapAgent;
import nipx.LambdaAligner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 宿主调用序列锚点匹配（Host Sequence LCS Anchor）与嵌套 Lambda 叶子消歧回归测试。
 *
 * <p>回归目标：
 * <ol>
 *   <li><b>嵌套 Lambda 叶子哈希碰撞消歧</b>：当多个嵌套 Lambda 的叶子具有相同方法体/哈希，
 *       但父级调用不同逻辑时，删除前序语句不会因 Step 1a 同名贪婪匹配导致新叶子误配给已删除的旧叶子，
 *       进而避免父级通过 {@code calleesPairTo} 被迫逆向错配篡夺已删调用的 CallSite。</li>
 *   <li><b>组遍历顺序确定性</b>：{@code TEST_REVERSE_GROUP_ORDER} 正反遍历下配对结果与产物字节码严格一致。</li>
 *   <li><b>宿主方法内兄弟 Lambda LCS 对齐</b>：方法内多个兄弟 Lambda 增删时，按调用顺序 LCS 准确对齐留存项，
 *       被删项平稳降级为孤儿复活桩。</li>
 * </ol>
 * </p>
 */
public class HostAnchorTest {

	@BeforeEach
	@AfterEach
	void resetState() {
		LambdaAligner.TEST_REVERSE_GROUP_ORDER = false;
		LambdaAligner.LAST_STATS = null;
		LambdaAligner.clearLoggedOrphans();
	}

	@Test
	void nestedLambdaLeafDisambiguation(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		Path src1Dir = Files.createDirectories(tmp.resolve("src1"));
		Path src2Dir = Files.createDirectories(tmp.resolve("src2"));
		Path outV1 = Files.createDirectories(tmp.resolve("v1"));
		Path outV2 = Files.createDirectories(tmp.resolve("v2"));

		String v1Src = "package test;\n"
			+ "public class NestedCase {\n"
			+ "    static void a(Runnable r) { r.run(); }\n"
			+ "    static void b(Runnable r) { r.run(); }\n"
			+ "    static void x1(Runnable r) { r.run(); }\n"
			+ "    static void x2(Runnable r) { r.run(); }\n"
			+ "    static void leaf() { }\n"
			+ "    public void build() {\n"
			+ "        a(() -> x1(() -> leaf()));\n" // P1 -> L1
			+ "        b(() -> x2(() -> leaf()));\n" // P2 -> L2
			+ "    }\n"
			+ "}\n";

		String v2Src = "package test;\n"
			+ "public class NestedCase {\n"
			+ "    static void a(Runnable r) { r.run(); }\n"
			+ "    static void b(Runnable r) { r.run(); }\n"
			+ "    static void x1(Runnable r) { r.run(); }\n"
			+ "    static void x2(Runnable r) { r.run(); }\n"
			+ "    static void leaf() { }\n"
			+ "    public void build() {\n"
			+ "        b(() -> x2(() -> leaf()));\n" // P2' -> L2'
			+ "    }\n"
			+ "}\n";

		Path src1 = src1Dir.resolve("NestedCase.java");
		Path src2 = src2Dir.resolve("NestedCase.java");
		Files.writeString(src1, v1Src, StandardCharsets.UTF_8);
		Files.writeString(src2, v2Src, StandardCharsets.UTF_8);

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.toString(), src1.toString());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.toString(), src2.toString());

		byte[] v1 = Files.readAllBytes(outV1.resolve("test/NestedCase.class"));
		byte[] v2 = Files.readAllBytes(outV2.resolve("test/NestedCase.class"));

		// 解析 V1 中的两个父 Lambda 方法名
		ClassNode cnV1 = readClass(v1);
		List<String> oldBuildIndyTargets = findIndyTargets(cnV1, "build");
		assertEquals(2, oldBuildIndyTargets.size(), "V1 build() 应包含两个 indy 调用");
		String oldP1 = oldBuildIndyTargets.get(0);
		String oldP2 = oldBuildIndyTargets.get(1);
		assertNotEquals(oldP1, oldP2, "两个旧父 Lambda 方法名不应相同");

		// 执行对齐
		byte[] aligned = LambdaAligner.align(v1, v2);
		assertNotNull(aligned, "对齐产物非空");
		assertNotNull(LambdaAligner.LAST_STATS, "LAST_STATS 已记录");
		assertTrue(LambdaAligner.LAST_STATS.anchorPairs > 0,
			"宿主锚点应至少产生 1 次配对: " + LambdaAligner.LAST_STATS);
		assertEquals(0, LambdaAligner.LAST_STATS.hashMismatchPairs,
			"不应存在票据哈希不匹配的错位配对: " + LambdaAligner.LAST_STATS);

		// 解析对齐产物
		ClassNode cnAligned = readClass(aligned);
		List<String> alignedBuildIndyTargets = findIndyTargets(cnAligned, "build");
		assertEquals(1, alignedBuildIndyTargets.size(), "aligned build() 应只包含 1 个 indy 调用");

		String actualParentInBuild = alignedBuildIndyTargets.get(0);
		// 独立字节码判据（按 V1 旧名字在产物中查方法体，绝不依赖 build() 的重写指针或 ctx 统计指标）：
		// 1. 旧 P2 名字在产物中存活，且其方法体内真实执行 x2() 业务调用，且绝不是幽灵桩
		MethodNode p2NodeInAligned = findMethod(cnAligned, oldP2);
		assertNotNull(p2NodeInAligned, "旧 P2 名字必须在产物中存活");
		assertTrue(callsMethod(p2NodeInAligned, "x2"), "旧 P2 名字对应的方法体必须真实调用 x2()");
		assertFalse(callsMethod(p2NodeInAligned, "onOrphanInvoked"), "旧 P2 绝不能是幽灵桩");

		// 2. 旧 P1 名字在产物中被降级为幽灵桩，调用 onOrphanInvoked，绝不执行业务逻辑 x1() 或 x2()
		MethodNode p1NodeInAligned = findMethod(cnAligned, oldP1);
		assertNotNull(p1NodeInAligned, "旧 P1 名字必须在产物中作为幽灵桩存在");
		assertTrue(callsMethod(p1NodeInAligned, "onOrphanInvoked"), "旧 P1 必须真实调用 onOrphanInvoked 熔断桩");
		assertFalse(callsMethod(p1NodeInAligned, "x1"), "旧 P1 绝不能继续执行业务调用 x1()");
		assertFalse(callsMethod(p1NodeInAligned, "x2"), "旧 P1 绝不能被篡夺去执行 x2()");

		// 3. build() 方法的 indy 调用必须指向 oldP2
		assertEquals(oldP2, actualParentInBuild, "build() 的调用点必须重写为 oldP2");
	}

	@Test
	void groupOrderDeterminismWithNestedLambdas(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		Path src1Dir = Files.createDirectories(tmp.resolve("src1"));
		Path src2Dir = Files.createDirectories(tmp.resolve("src2"));
		Path outV1 = Files.createDirectories(tmp.resolve("v1"));
		Path outV2 = Files.createDirectories(tmp.resolve("v2"));

		String v1Src = "package test;\n"
			+ "public class OrderCase {\n"
			+ "    static void a(Runnable r) { r.run(); }\n"
			+ "    static void b(Runnable r) { r.run(); }\n"
			+ "    static void x1(Runnable r) { r.run(); }\n"
			+ "    static void x2(Runnable r) { r.run(); }\n"
			+ "    static void leaf() { }\n"
			+ "    public void build() {\n"
			+ "        a(() -> x1(() -> leaf()));\n"
			+ "        b(() -> x2(() -> leaf()));\n"
			+ "    }\n"
			+ "}\n";

		String v2Src = "package test;\n"
			+ "public class OrderCase {\n"
			+ "    static void a(Runnable r) { r.run(); }\n"
			+ "    static void b(Runnable r) { r.run(); }\n"
			+ "    static void x1(Runnable r) { r.run(); }\n"
			+ "    static void x2(Runnable r) { r.run(); }\n"
			+ "    static void leaf() { }\n"
			+ "    public void build() {\n"
			+ "        b(() -> x2(() -> leaf()));\n"
			+ "    }\n"
			+ "}\n";

		Path src1 = src1Dir.resolve("OrderCase.java");
		Path src2 = src2Dir.resolve("OrderCase.java");
		Files.writeString(src1, v1Src, StandardCharsets.UTF_8);
		Files.writeString(src2, v2Src, StandardCharsets.UTF_8);

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.toString(), src1.toString());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.toString(), src2.toString());

		byte[] v1 = Files.readAllBytes(outV1.resolve("test/OrderCase.class"));
		byte[] v2 = Files.readAllBytes(outV2.resolve("test/OrderCase.class"));

		LambdaAligner.TEST_REVERSE_GROUP_ORDER = false;
		byte[] alignedForward = LambdaAligner.align(v1, v2);

		LambdaAligner.TEST_REVERSE_GROUP_ORDER = true;
		byte[] alignedReverse = LambdaAligner.align(v1, v2);

		assertArrayEquals(alignedForward, alignedReverse,
			"组遍历顺序倒置(TEST_REVERSE_GROUP_ORDER)产物字节码必须完全一致");
	}

	@Test
	void hostSequenceLcsDeletionDisambiguation(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		Path src1Dir = Files.createDirectories(tmp.resolve("src1"));
		Path src2Dir = Files.createDirectories(tmp.resolve("src2"));
		Path outV1 = Files.createDirectories(tmp.resolve("v1"));
		Path outV2 = Files.createDirectories(tmp.resolve("v2"));

		String v1Src = "package test;\n"
			+ "import java.util.List;\n"
			+ "public class SiblingCase {\n"
			+ "    static void step1() { System.out.println(\"1\"); }\n"
			+ "    static void step2() { System.out.println(\"2\"); }\n"
			+ "    static void step3() { System.out.println(\"3\"); }\n"
			+ "    public void run(List<Runnable> list) {\n"
			+ "        list.add(() -> step1());\n"
			+ "        list.add(() -> step2());\n"
			+ "        list.add(() -> step3());\n"
			+ "    }\n"
			+ "}\n";

		String v2Src = "package test;\n"
			+ "import java.util.List;\n"
			+ "public class SiblingCase {\n"
			+ "    static void step1() { System.out.println(\"1\"); }\n"
			+ "    static void step2() { System.out.println(\"2\"); }\n"
			+ "    static void step3() { System.out.println(\"3\"); }\n"
			+ "    public void run(List<Runnable> list) {\n"
			+ "        list.add(() -> step1());\n"
			+ "        list.add(() -> step3());\n"
			+ "    }\n"
			+ "}\n";

		Path src1 = src1Dir.resolve("SiblingCase.java");
		Path src2 = src2Dir.resolve("SiblingCase.java");
		Files.writeString(src1, v1Src, StandardCharsets.UTF_8);
		Files.writeString(src2, v2Src, StandardCharsets.UTF_8);

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.toString(), src1.toString());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.toString(), src2.toString());

		byte[] v1 = Files.readAllBytes(outV1.resolve("test/SiblingCase.class"));
		byte[] v2 = Files.readAllBytes(outV2.resolve("test/SiblingCase.class"));

		ClassNode cnV1 = readClass(v1);
		List<String> oldTargets = findIndyTargets(cnV1, "run");
		assertEquals(3, oldTargets.size(), "V1 run() 应有 3 个 indy 调用");
		String oldL1 = oldTargets.get(0);
		String oldL2 = oldTargets.get(1);
		String oldL3 = oldTargets.get(2);

		byte[] aligned = LambdaAligner.align(v1, v2);
		assertNotNull(aligned);

		ClassNode cnAligned = readClass(aligned);
		List<String> alignedTargets = findIndyTargets(cnAligned, "run");
		assertEquals(2, alignedTargets.size(), "aligned run() 应有 2 个 indy 调用");

		assertEquals(oldL1, alignedTargets.get(0), "第 1 个调用应配对到 oldL1");
		assertEquals(oldL3, alignedTargets.get(1), "第 2 个调用应经 LCS 准确配对到 oldL3 (跳过已删的 oldL2)");

		MethodNode ghostL2 = findMethod(cnAligned, oldL2);
		assertNotNull(ghostL2, "已删除的 oldL2 应作为孤儿桩复活");
	}

	/**
	 * Characterization test: 记录并守住当前父级方法体变更且子叶同哈希碰撞时的已知退化行为。
	 *
	 * <p>退化机制：新父级方法体改写后，其票据哈希改变导致无法与旧父级落入同一 L:hash 锚点桶，
	 * 叶子退化走 Step 1a 同名贪婪匹配；父级随之在 Pass A 配对到旧父级并触发 warnPositionalMismatch，
	 * 在 stats 中表现为 hashMismatchPairs > 0 且 anchorPairs == 0。</p>
	 */
	@Test
	void parentEditedAndStatementDeletedDegradation(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		Path src1Dir = Files.createDirectories(tmp.resolve("src1"));
		Path src2Dir = Files.createDirectories(tmp.resolve("src2"));
		Path outV1 = Files.createDirectories(tmp.resolve("v1"));
		Path outV2 = Files.createDirectories(tmp.resolve("v2"));

		String v1Src = "package test;\n"
			+ "public class ParentEditCase {\n"
			+ "    static void a(Runnable r) { r.run(); }\n"
			+ "    static void b(Runnable r) { r.run(); }\n"
			+ "    static void x1(Runnable r) { r.run(); }\n"
			+ "    static void x2(Runnable r) { r.run(); }\n"
			+ "    static void leaf() { }\n"
			+ "    public void build() {\n"
			+ "        a(() -> x1(() -> leaf()));\n" // P1 -> L1
			+ "        b(() -> x2(() -> leaf()));\n" // P2 -> L2
			+ "    }\n"
			+ "}\n";

		// V2：删除语句 1，且修改语句 2 父级方法体（引入额外调用与参数，使票据哈希改变）
		String v2Src = "package test;\n"
			+ "public class ParentEditCase {\n"
			+ "    static void a(Runnable r) { r.run(); }\n"
			+ "    static void b(Runnable r) { r.run(); }\n"
			+ "    static void x1(Runnable r) { r.run(); }\n"
			+ "    static void x2(Runnable r) { r.run(); }\n"
			+ "    static void x2_mod(Runnable r) { r.run(); }\n"
			+ "    static void leaf() { }\n"
			+ "    public void build() {\n"
			+ "        b(() -> x2_mod(() -> leaf()));\n" // P2' -> L2' (P2' 哈希异化)
			+ "    }\n"
			+ "}\n";

		Path src1 = src1Dir.resolve("ParentEditCase.java");
		Path src2 = src2Dir.resolve("ParentEditCase.java");
		Files.writeString(src1, v1Src, StandardCharsets.UTF_8);
		Files.writeString(src2, v2Src, StandardCharsets.UTF_8);

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.toString(), src1.toString());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.toString(), src2.toString());

		byte[] v1 = Files.readAllBytes(outV1.resolve("test/ParentEditCase.class"));
		byte[] v2 = Files.readAllBytes(outV2.resolve("test/ParentEditCase.class"));

		byte[] aligned = LambdaAligner.align(v1, v2);
		assertNotNull(aligned);
		assertNotNull(LambdaAligner.LAST_STATS);

		// 父级哈希改变导致无法通过 L:hash 锚点对齐；
		// 叶子降级回 Step 1a 同名贪婪匹配（L2' 匹配 L1），父级随之落入 Pass A/B
		assertEquals(0, LambdaAligner.LAST_STATS.anchorPairs,
			"父级哈希异化导致锚点失配，anchorPairs 应为 0: " + LambdaAligner.LAST_STATS);
		assertTrue(LambdaAligner.LAST_STATS.hashMismatchPairs > 0,
			"退化路径应准确记录到哈希异化配对 (hashMismatchPairs > 0): " + LambdaAligner.LAST_STATS);
	}

	@Test
	void identicalParentsWithLeafCollisionAndDeletedStatement(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		Path src1Dir = Files.createDirectories(tmp.resolve("src1"));
		Path src2Dir = Files.createDirectories(tmp.resolve("src2"));
		Path outV1 = Files.createDirectories(tmp.resolve("v1"));
		Path outV2 = Files.createDirectories(tmp.resolve("v2"));

		// V1：两个语句的父级逻辑也完全相同（均调用 x1(() -> leaf())）
		String v1Src = "package test;\n"
			+ "public class IdenticalCase {\n"
			+ "    static void a(Runnable r) { r.run(); }\n"
			+ "    static void b(Runnable r) { r.run(); }\n"
			+ "    static void x1(Runnable r) { r.run(); }\n"
			+ "    static void leaf() { }\n"
			+ "    public void build() {\n"
			+ "        a(() -> x1(() -> leaf()));\n" // P1 -> L1
			+ "        b(() -> x1(() -> leaf()));\n" // P2 -> L2 (与 P1 完全相同)
			+ "    }\n"
			+ "}\n";

		// V2：删除语句 1
		String v2Src = "package test;\n"
			+ "public class IdenticalCase {\n"
			+ "    static void a(Runnable r) { r.run(); }\n"
			+ "    static void b(Runnable r) { r.run(); }\n"
			+ "    static void x1(Runnable r) { r.run(); }\n"
			+ "    static void leaf() { }\n"
			+ "    public void build() {\n"
			+ "        b(() -> x1(() -> leaf()));\n" // P2' -> L2'
			+ "    }\n"
			+ "}\n";

		Path src1 = src1Dir.resolve("IdenticalCase.java");
		Path src2 = src2Dir.resolve("IdenticalCase.java");
		Files.writeString(src1, v1Src, StandardCharsets.UTF_8);
		Files.writeString(src2, v2Src, StandardCharsets.UTF_8);

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.toString(), src1.toString());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.toString(), src2.toString());

		byte[] v1 = Files.readAllBytes(outV1.resolve("test/IdenticalCase.class"));
		byte[] v2 = Files.readAllBytes(outV2.resolve("test/IdenticalCase.class"));

		ClassNode cnV1 = readClass(v1);
		List<String> oldTargets = findIndyTargets(cnV1, "build");
		assertEquals(2, oldTargets.size());
		String oldP1 = oldTargets.get(0);
		String oldP2 = oldTargets.get(1);

		byte[] aligned = LambdaAligner.align(v1, v2);
		assertNotNull(aligned);
		assertNotNull(LambdaAligner.LAST_STATS);

		// 父子语义完全等价时，哈希完全匹配
		assertEquals(0, LambdaAligner.LAST_STATS.hashMismatchPairs,
			"语义完全一致，无哈希异化: " + LambdaAligner.LAST_STATS);

		ClassNode cnAligned = readClass(aligned);
		List<String> alignedTargets = findIndyTargets(cnAligned, "build");
		assertEquals(1, alignedTargets.size(), "aligned build() 应只包含 1 个 indy 调用");
		String activeParent = alignedTargets.get(0);
		assertEquals(oldP2, activeParent, "留存的语句 b(...) 正确配对到 oldP2");
		MethodNode ghostP1 = findMethod(cnAligned, oldP1);
		assertNotNull(ghostP1, "已删除的语句 a(...) oldP1 应作为孤儿桩复活");
	}

	@Test
	void identicalParentsMirrorDeleteLastStatement(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		Path src1Dir = Files.createDirectories(tmp.resolve("src1"));
		Path src2Dir = Files.createDirectories(tmp.resolve("src2"));
		Path outV1 = Files.createDirectories(tmp.resolve("v1"));
		Path outV2 = Files.createDirectories(tmp.resolve("v2"));

		// V1：两个语句的父级逻辑也完全相同（均调用 x1(() -> leaf())）
		String v1Src = "package test;\n"
			+ "public class MirrorCase {\n"
			+ "    static void a(Runnable r) { r.run(); }\n"
			+ "    static void b(Runnable r) { r.run(); }\n"
			+ "    static void x1(Runnable r) { r.run(); }\n"
			+ "    static void leaf() { }\n"
			+ "    public void build() {\n"
			+ "        a(() -> x1(() -> leaf()));\n" // P1 -> L1
			+ "        b(() -> x1(() -> leaf()));\n" // P2 -> L2 (与 P1 完全相同)
			+ "    }\n"
			+ "}\n";

		// V2：镜像操作 —— 删除语句 2（保留语句 1）
		String v2Src = "package test;\n"
			+ "public class MirrorCase {\n"
			+ "    static void a(Runnable r) { r.run(); }\n"
			+ "    static void b(Runnable r) { r.run(); }\n"
			+ "    static void x1(Runnable r) { r.run(); }\n"
			+ "    static void leaf() { }\n"
			+ "    public void build() {\n"
			+ "        a(() -> x1(() -> leaf()));\n" // P1' -> L1'
			+ "    }\n"
			+ "}\n";

		Path src1 = src1Dir.resolve("MirrorCase.java");
		Path src2 = src2Dir.resolve("MirrorCase.java");
		Files.writeString(src1, v1Src, StandardCharsets.UTF_8);
		Files.writeString(src2, v2Src, StandardCharsets.UTF_8);

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.toString(), src1.toString());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.toString(), src2.toString());

		byte[] v1 = Files.readAllBytes(outV1.resolve("test/MirrorCase.class"));
		byte[] v2 = Files.readAllBytes(outV2.resolve("test/MirrorCase.class"));

		ClassNode cnV1 = readClass(v1);
		List<String> oldTargets = findIndyTargets(cnV1, "build");
		assertEquals(2, oldTargets.size());
		String oldP1 = oldTargets.get(0);
		String oldP2 = oldTargets.get(1);

		byte[] aligned = LambdaAligner.align(v1, v2);
		assertNotNull(aligned);

		ClassNode cnAligned = readClass(aligned);
		List<String> alignedTargets = findIndyTargets(cnAligned, "build");
		assertEquals(1, alignedTargets.size(), "aligned build() 应只包含 1 个 indy 调用");
		String activeParent = alignedTargets.get(0);

		// 检查镜像对齐：断言具体留存项必须是 oldP1，已删项 oldP2 必须是幽灵桩
		assertEquals(oldP1, activeParent,
			"留存的语句 1 父级必须配对到 oldP1 (" + oldP1 + ")，绝不能错配为已删除的 oldP2 (" + oldP2 + ")");
		MethodNode p1Node = findMethod(cnAligned, oldP1);
		assertNotNull(p1Node, "旧 P1 必须在产物中存活");
		assertTrue(callsMethod(p1Node, "x1"), "旧 P1 必须真实执行 x1() 业务逻辑");
		assertFalse(callsMethod(p1Node, "onOrphanInvoked"), "旧 P1 绝不能是幽灵桩");

		MethodNode p2Node = findMethod(cnAligned, oldP2);
		assertNotNull(p2Node, "未留存的旧 P2 必须作为孤儿桩复活");
		assertTrue(callsMethod(p2Node, "onOrphanInvoked"), "未留存的旧 P2 必须是幽灵熔断桩");
		assertFalse(callsMethod(p2Node, "x1"), "旧 P2 绝不能残留业务逻辑");

		// 人为反转方法表顺序，断言对齐结果完全不变（不依赖编译器物理方法表顺序）
		ClassNode cnV1Rev = readClass(v1);
		Collections.reverse(cnV1Rev.methods);
		ClassNode cnV2Rev = readClass(v2);
		Collections.reverse(cnV2Rev.methods);

		byte[] alignedRev = LambdaAligner.align(writeClass(cnV1Rev), writeClass(cnV2Rev));
		assertNotNull(alignedRev);
		ClassNode cnAlignedRev = readClass(alignedRev);
		List<String> revAlignedTargets = findIndyTargets(cnAlignedRev, "build");
		assertEquals(1, revAlignedTargets.size());
		assertEquals(oldP1, revAlignedTargets.get(0), "方法表物理顺序反转后，留存项仍必须稳定对齐到 oldP1");
	}

	@Test
	void identicalParentsAndConsumersMirrorDeleteLastStatement(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		Path src1Dir = Files.createDirectories(tmp.resolve("src1"));
		Path src2Dir = Files.createDirectories(tmp.resolve("src2"));
		Path outV1 = Files.createDirectories(tmp.resolve("v1"));
		Path outV2 = Files.createDirectories(tmp.resolve("v2"));

		// V1：两个语句的父级逻辑与消费者均完全相同（均调用 a(() -> x1(() -> leaf()))）
		String v1Src = "package test;\n"
			+ "public class HomomorphicMirrorCase {\n"
			+ "    static void a(Runnable r) { r.run(); }\n"
			+ "    static void x1(Runnable r) { r.run(); }\n"
			+ "    static void leaf() { }\n"
			+ "    public void build() {\n"
			+ "        a(() -> x1(() -> leaf()));\n" // P1 -> L1
			+ "        a(() -> x1(() -> leaf()));\n" // P2 -> L2 (完全相同且同消费者 a)
			+ "    }\n"
			+ "}\n";

		// V2：镜像操作 —— 删除后一条语句（保留前一条）
		String v2Src = "package test;\n"
			+ "public class HomomorphicMirrorCase {\n"
			+ "    static void a(Runnable r) { r.run(); }\n"
			+ "    static void x1(Runnable r) { r.run(); }\n"
			+ "    static void leaf() { }\n"
			+ "    public void build() {\n"
			+ "        a(() -> x1(() -> leaf()));\n" // P1' -> L1'
			+ "    }\n"
			+ "}\n";

		Path src1 = src1Dir.resolve("HomomorphicMirrorCase.java");
		Path src2 = src2Dir.resolve("HomomorphicMirrorCase.java");
		Files.writeString(src1, v1Src, StandardCharsets.UTF_8);
		Files.writeString(src2, v2Src, StandardCharsets.UTF_8);

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.toString(), src1.toString());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.toString(), src2.toString());

		byte[] v1 = Files.readAllBytes(outV1.resolve("test/HomomorphicMirrorCase.class"));
		byte[] v2 = Files.readAllBytes(outV2.resolve("test/HomomorphicMirrorCase.class"));

		ClassNode cnV1 = readClass(v1);
		List<String> oldTargets = findIndyTargets(cnV1, "build");
		assertEquals(2, oldTargets.size());
		String oldP1 = oldTargets.get(0);
		String oldP2 = oldTargets.get(1);

		HotSwapAgent.Logger oldLogger = HotSwapAgent.logger;
		List<String> warnings = new ArrayList<>();
		HotSwapAgent.logger = new HotSwapAgent.DefaultLogger() {
			@Override
			public void warn(String msg) {
				warnings.add(msg);
				super.warn(msg);
			}
		};
		byte[] aligned;
		try {
			aligned = LambdaAligner.align(v1, v2);
		} finally {
			HotSwapAgent.logger = oldLogger;
		}
		assertNotNull(aligned);

		// 断言同构缩减告警在镜像场景下也必须被触发
		boolean hasPruningWarn = warnings.stream().anyMatch(w -> w.contains("宿主序列存在多个同构 Lambda 候选且发生缩减"));
		assertTrue(hasPruningWarn, "镜像场景下多个完全同构（同体且同消费者）Lambda 候选缩减时，必须触发警告: " + warnings);

		ClassNode cnAligned = readClass(aligned);
		List<String> alignedTargets = findIndyTargets(cnAligned, "build");
		assertEquals(1, alignedTargets.size());
		assertEquals(oldP1, alignedTargets.get(0), "根据 BFS 源码序，留存的前序语句必须对齐到 oldP1");

		// 人为反转方法表验证顺序无关性
		ClassNode cnV1Rev = readClass(v1);
		Collections.reverse(cnV1Rev.methods);
		ClassNode cnV2Rev = readClass(v2);
		Collections.reverse(cnV2Rev.methods);
		byte[] alignedRev = LambdaAligner.align(writeClass(cnV1Rev), writeClass(cnV2Rev));
		assertNotNull(alignedRev);
		ClassNode cnAlignedRev = readClass(alignedRev);
		assertEquals(oldP1, findIndyTargets(cnAlignedRev, "build").get(0), "即使编译器方法表倒序，BFS 调用流序仍保证对齐到 oldP1");
	}

	@Test
	void multiRoundRedefinitionLifecycle(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		Path srcDir = Files.createDirectories(tmp.resolve("src"));
		Path outV1 = Files.createDirectories(tmp.resolve("v1"));
		Path outV2 = Files.createDirectories(tmp.resolve("v2"));
		Path outV3 = Files.createDirectories(tmp.resolve("v3"));
		Path outV4 = Files.createDirectories(tmp.resolve("v4"));

		String v1Src = "package test;\n"
			+ "import java.util.List;\n"
			+ "public class LifeCase {\n"
			+ "    static void a() { System.out.println(\"A\"); }\n"
			+ "    static void b() { System.out.println(\"B\"); }\n"
			+ "    public void run(List<Runnable> list) {\n"
			+ "        list.add(() -> a());\n" // L1
			+ "        list.add(() -> b());\n" // L2
			+ "    }\n"
			+ "}\n";

		// V2: 删除 L1
		String v2Src = "package test;\n"
			+ "import java.util.List;\n"
			+ "public class LifeCase {\n"
			+ "    static void a() { System.out.println(\"A\"); }\n"
			+ "    static void b() { System.out.println(\"B\"); }\n"
			+ "    public void run(List<Runnable> list) {\n"
			+ "        list.add(() -> b());\n" // L2
			+ "    }\n"
			+ "}\n";

		// V3: 重新加回 L1
		String v3Src = "package test;\n"
			+ "import java.util.List;\n"
			+ "public class LifeCase {\n"
			+ "    static void a() { System.out.println(\"A\"); }\n"
			+ "    static void b() { System.out.println(\"B\"); }\n"
			+ "    public void run(List<Runnable> list) {\n"
			+ "        list.add(() -> a());\n" // L1 重新加回
			+ "        list.add(() -> b());\n" // L2
			+ "    }\n"
			+ "}\n";

		// V4: 修改 L2 方法体
		String v4Src = "package test;\n"
			+ "import java.util.List;\n"
			+ "public class LifeCase {\n"
			+ "    static void a() { System.out.println(\"A\"); }\n"
			+ "    static void b_mod() { System.out.println(\"B_MOD\"); }\n"
			+ "    public void run(List<Runnable> list) {\n"
			+ "        list.add(() -> a());\n" // L1
			+ "        list.add(() -> b_mod());\n" // L2 修改
			+ "    }\n"
			+ "}\n";

		Path srcFile = srcDir.resolve("LifeCase.java");

		Files.writeString(srcFile, v1Src, StandardCharsets.UTF_8);
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.toString(), srcFile.toString());
		byte[] rawV1 = Files.readAllBytes(outV1.resolve("test/LifeCase.class"));

		Files.writeString(srcFile, v2Src, StandardCharsets.UTF_8);
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.toString(), srcFile.toString());
		byte[] rawV2 = Files.readAllBytes(outV2.resolve("test/LifeCase.class"));

		Files.writeString(srcFile, v3Src, StandardCharsets.UTF_8);
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV3.toString(), srcFile.toString());
		byte[] rawV3 = Files.readAllBytes(outV3.resolve("test/LifeCase.class"));

		Files.writeString(srcFile, v4Src, StandardCharsets.UTF_8);
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV4.toString(), srcFile.toString());
		byte[] rawV4 = Files.readAllBytes(outV4.resolve("test/LifeCase.class"));

		// 轮次 1：V1 -> V2 (删除 L1)
		byte[] alignedV2 = LambdaAligner.align(rawV1, rawV2);
		ClassNode cnV2 = readClass(alignedV2);
		ClassNode cnV1 = readClass(rawV1);
		String oldL1 = findIndyTargets(cnV1, "run").get(0);
		String oldL2 = findIndyTargets(cnV1, "run").get(1);
		assertNotNull(findMethod(cnV2, oldL1), "V2 中已删除的 oldL1 必须复活为幽灵桩");
		assertEquals(oldL2, findIndyTargets(cnV2, "run").get(0), "V2 中留存的 L2 必须保持 oldL2 名字");

		// 轮次 2：V2 -> V3 (加回 L1，遵守调用约定传入上一轮实际生效的 alignedV2)
		byte[] alignedV3 = LambdaAligner.align(alignedV2, rawV3);
		ClassNode cnV3 = readClass(alignedV3);
		List<String> v3Targets = findIndyTargets(cnV3, "run");
		assertEquals(2, v3Targets.size());
		// 断言：加回的 L1 不会认领幽灵桩 oldL1，而是避障分配 fresh name（或不同于 oldL1）
		// 同时老幽灵桩 oldL1 依然存在于类中保护老 CallSite
		assertNotNull(findMethod(cnV3, oldL1), "V3 中历史幽灵桩 oldL1 依然保留");
		assertNotEquals(oldL1, v3Targets.get(0), "V3 加回的新 L1 不会认领旧幽灵桩名字");

		// 轮次 3：V3 -> V4 (修改 L2，遵守调用约定传入上一轮实际生效的 alignedV3)
		byte[] alignedV4 = LambdaAligner.align(alignedV3, rawV4);
		ClassNode cnV4 = readClass(alignedV4);
		assertNotNull(alignedV4);
		// 断言：累积的幽灵桩不阻碍 L2 的改名与对齐
		assertTrue(findMethod(cnV4, oldL1) != null, "V4 中历史幽灵桩依然受到保护");
	}

	@Test
	void alignWithBytecodeResolvers(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		Path src1Dir = Files.createDirectories(tmp.resolve("src1"));
		Path src2Dir = Files.createDirectories(tmp.resolve("src2"));
		Path outV1 = Files.createDirectories(tmp.resolve("v1"));
		Path outV2 = Files.createDirectories(tmp.resolve("v2"));

		String v1Src = "package test;\n"
			+ "public class AnonHostCase {\n"
			+ "    public Runnable makeRunnable() {\n"
			+ "        return () -> {\n"
			+ "            Runnable r = new Runnable() {\n"
			+ "                public void run() { System.out.println(\"anon1\"); }\n"
			+ "            };\n"
			+ "            r.run();\n"
			+ "        };\n"
			+ "    }\n"
			+ "}\n";

		String v2Src = "package test;\n"
			+ "public class AnonHostCase {\n"
			+ "    public Runnable makeRunnable() {\n"
			+ "        return () -> {\n"
			+ "            Runnable r = new Runnable() {\n"
			+ "                public void run() { System.out.println(\"anon1\"); }\n"
			+ "            };\n"
			+ "            r.run();\n"
			+ "        };\n"
			+ "    }\n"
			+ "}\n";

		Path src1 = src1Dir.resolve("AnonHostCase.java");
		Path src2 = src2Dir.resolve("AnonHostCase.java");
		Files.writeString(src1, v1Src, StandardCharsets.UTF_8);
		Files.writeString(src2, v2Src, StandardCharsets.UTF_8);

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.toString(), src1.toString());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.toString(), src2.toString());

		byte[] hostV1 = Files.readAllBytes(outV1.resolve("test/AnonHostCase.class"));
		byte[] hostV2 = Files.readAllBytes(outV2.resolve("test/AnonHostCase.class"));

		Map<String, byte[]> v1Cache = new HashMap<>();
		Files.walk(outV1).filter(p -> p.toString().endsWith(".class")).forEach(p -> {
			try {
				String rel = outV1.relativize(p).toString().replace('\\', '/').replace(".class", "");
				v1Cache.put(rel, Files.readAllBytes(p));
			} catch (Exception e) { throw new RuntimeException(e); }
		});

		Map<String, byte[]> v2Cache = new HashMap<>();
		Files.walk(outV2).filter(p -> p.toString().endsWith(".class")).forEach(p -> {
			try {
				String rel = outV2.relativize(p).toString().replace('\\', '/').replace(".class", "");
				v2Cache.put(rel, Files.readAllBytes(p));
			} catch (Exception e) { throw new RuntimeException(e); }
		});

		byte[] aligned = LambdaAligner.align(hostV1, hostV2, v1Cache::get, v2Cache::get);
		assertNotNull(aligned);
		assertNotNull(LambdaAligner.LAST_OLD_ANON_HASHES);
		assertNotNull(LambdaAligner.LAST_NEW_ANON_HASHES);
		assertFalse(LambdaAligner.LAST_OLD_ANON_HASHES.isEmpty(), "4参数重载应成功解析并记录匿名类内容哈希");
		assertEquals(LambdaAligner.LAST_OLD_ANON_HASHES.get("test/AnonHostCase$1"),
		             LambdaAligner.LAST_NEW_ANON_HASHES.get("test/AnonHostCase$1"),
		             "相同匿名类的真实内容哈希在新旧侧应完全一致");
	}

	private static ClassNode readClass(byte[] bytes) {
		ClassReader cr = new ClassReader(bytes);
		ClassNode cn = new ClassNode();
		cr.accept(cn, 0);
		return cn;
	}

	private static byte[] writeClass(ClassNode cn) {
		org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

	private static List<String> findIndyTargets(ClassNode cn, String methodName) {
		List<String> targets = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			if (mn.name.equals(methodName)) {
				for (AbstractInsnNode insn : mn.instructions) {
					if (insn instanceof InvokeDynamicInsnNode indy) {
						if (indy.bsmArgs != null && indy.bsmArgs.length > 1
						    && indy.bsmArgs[1] instanceof Handle h
						    && cn.name.equals(h.getOwner())) {
							targets.add(h.getName());
						}
					}
				}
			}
		}
		return targets;
	}

	private static MethodNode findMethod(ClassNode cn, String name) {
		for (MethodNode mn : cn.methods) {
			if (mn.name.equals(name)) return mn;
		}
		return null;
	}

	private static boolean callsMethod(MethodNode mn, String targetMethodName) {
		if (mn == null || mn.instructions == null) return false;
		for (AbstractInsnNode insn : mn.instructions) {
			if (insn instanceof org.objectweb.asm.tree.MethodInsnNode minsn) {
				if (minsn.name.equals(targetMethodName)) return true;
			}
		}
		return false;
	}

	static String javac21() {
		String j = System.getProperty("hstest.javac21");
		if (j != null && new File(j).exists()) return j;
		j = System.getenv("HSTEST_JAVAC21");
		if (j != null && new File(j).exists()) return j;
		j = "F:/files/java/jdks/openjdk-21.0.2/bin/javac.exe";
		return new File(j).exists() ? j : "javac";
	}

	static void runCmd(String... cmd) throws Exception {
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		StringBuilder out = new StringBuilder();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
			String line;
			while ((line = r.readLine()) != null) {
				out.append(line).append('\n');
			}
		}
		int rc = p.waitFor();
		if (rc != 0) throw new RuntimeException("Command failed with exit " + rc + ": " + Arrays.toString(cmd) + "\nOutput:\n" + out);
	}
}
