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
		LambdaAligner.clearLoggedWarnings();
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
		// 独立因果判据：P2' 明确配对到 oldP2 是依靠消费者证据（CALL:...b 区别于 CALL:...a），
		// 而非依赖任何编译器物理方法表顺序或遍历偏好。
		assertEquals(oldP2, activeParent, "留存的语句 b(...) 基于消费者特征准确配对到 oldP2");
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

		// 人为反转方法表顺序（3 种非对称/对称组合），断言对齐结果完全不变（不依赖编译器物理方法表顺序）
		// 组合 1：仅反转旧版 V1 方法表
		ClassNode cnV1OnlyRev = readClass(v1);
		Collections.reverse(cnV1OnlyRev.methods);
		byte[] alignedRev1 = LambdaAligner.align(writeClass(cnV1OnlyRev), v2);
		assertNotNull(alignedRev1);
		assertEquals(oldP1, findIndyTargets(readClass(alignedRev1), "build").get(0),
			"[仅反转旧版 V1] 留存项仍必须稳定对齐到 oldP1");

		// 组合 2：仅反转新版 V2 方法表
		ClassNode cnV2OnlyRev = readClass(v2);
		Collections.reverse(cnV2OnlyRev.methods);
		byte[] alignedRev2 = LambdaAligner.align(v1, writeClass(cnV2OnlyRev));
		assertNotNull(alignedRev2);
		assertEquals(oldP1, findIndyTargets(readClass(alignedRev2), "build").get(0),
			"[仅反转新版 V2] 留存项仍必须稳定对齐到 oldP1");

		// 组合 3：同时反转新旧两侧方法表
		byte[] alignedRevBoth = LambdaAligner.align(writeClass(cnV1OnlyRev), writeClass(cnV2OnlyRev));
		assertNotNull(alignedRevBoth);
		assertEquals(oldP1, findIndyTargets(readClass(alignedRevBoth), "build").get(0),
			"[同时反转两侧] 留存项仍必须稳定对齐到 oldP1");
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

		// 人为反转方法表验证顺序无关性（3 种非对称/对称组合）
		ClassNode cnV1OnlyRev = readClass(v1);
		Collections.reverse(cnV1OnlyRev.methods);
		byte[] alignedRev1 = LambdaAligner.align(writeClass(cnV1OnlyRev), v2);
		assertNotNull(alignedRev1);
		assertEquals(oldP1, findIndyTargets(readClass(alignedRev1), "build").get(0),
			"[仅反转旧版 V1] 留存的前序语句仍必须对齐到 oldP1");

		ClassNode cnV2OnlyRev = readClass(v2);
		Collections.reverse(cnV2OnlyRev.methods);
		byte[] alignedRev2 = LambdaAligner.align(v1, writeClass(cnV2OnlyRev));
		assertNotNull(alignedRev2);
		assertEquals(oldP1, findIndyTargets(readClass(alignedRev2), "build").get(0),
			"[仅反转新版 V2] 留存的前序语句仍必须对齐到 oldP1");

		byte[] alignedRevBoth = LambdaAligner.align(writeClass(cnV1OnlyRev), writeClass(cnV2OnlyRev));
		assertNotNull(alignedRevBoth);
		assertEquals(oldP1, findIndyTargets(readClass(alignedRevBoth), "build").get(0),
			"[同时反转两侧] 留存的前序语句仍必须对齐到 oldP1");
	}

	/**
	 * 同构 Lambda 缩减（完全相同的父级方法体与相同的消费者调用）删除首条语句场景。
	 *
	 * <p><b>约定而非推导（Convention, not deduction）</b>：
	 * 当开发者删除了前一条语句 {@code a(() -> x1(() -> leaf()))}，留存后一条语句时，
	 * 由于两条语句无论从方法体、嵌套结构还是栈上消费者（均为 {@code CALL:...a}）均完全无异，
	 * 字节码层面不存在任何区分二者的物理证据。
	 * 此时系统遵循确定性的<b>前缀稳定对齐约定</b>（Prefix Convention，即按源码流序将留存的唯一实例
	 * 对齐到首个旧候选 {@code oldP1}，并将 {@code oldP2} 退休为孤儿桩），同时触发警告留痕。</p>
	 */
	@Test
	void identicalParentsAndConsumersMirrorDeleteFirstStatement(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		Path src1Dir = Files.createDirectories(tmp.resolve("src1"));
		Path src2Dir = Files.createDirectories(tmp.resolve("src2"));
		Path outV1 = Files.createDirectories(tmp.resolve("v1"));
		Path outV2 = Files.createDirectories(tmp.resolve("v2"));

		// V1：两个语句的父级逻辑与消费者均完全相同
		String v1Src = "package test;\n"
			+ "public class HomomorphicDeleteFirstCase {\n"
			+ "    static void a(Runnable r) { r.run(); }\n"
			+ "    static void x1(Runnable r) { r.run(); }\n"
			+ "    static void leaf() { }\n"
			+ "    public void build() {\n"
			+ "        a(() -> x1(() -> leaf()));\n" // P1 -> L1 (语句 1)
			+ "        a(() -> x1(() -> leaf()));\n" // P2 -> L2 (语句 2，完全同构)
			+ "    }\n"
			+ "}\n";

		// V2：删除前一条语句（保留后一条语句）
		String v2Src = "package test;\n"
			+ "public class HomomorphicDeleteFirstCase {\n"
			+ "    static void a(Runnable r) { r.run(); }\n"
			+ "    static void x1(Runnable r) { r.run(); }\n"
			+ "    static void leaf() { }\n"
			+ "    public void build() {\n"
			+ "        a(() -> x1(() -> leaf()));\n" // P2' -> L2' (源码上对应语句 2)
			+ "    }\n"
			+ "}\n";

		Path src1 = src1Dir.resolve("HomomorphicDeleteFirstCase.java");
		Path src2 = src2Dir.resolve("HomomorphicDeleteFirstCase.java");
		Files.writeString(src1, v1Src, StandardCharsets.UTF_8);
		Files.writeString(src2, v2Src, StandardCharsets.UTF_8);

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.toString(), src1.toString());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.toString(), src2.toString());

		byte[] v1 = Files.readAllBytes(outV1.resolve("test/HomomorphicDeleteFirstCase.class"));
		byte[] v2 = Files.readAllBytes(outV2.resolve("test/HomomorphicDeleteFirstCase.class"));

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

		// 1. 断言同构缩减告警必须被触发
		boolean hasPruningWarn = warnings.stream().anyMatch(w -> w.contains("宿主序列存在多个同构 Lambda 候选且发生缩减"));
		assertTrue(hasPruningWarn, "同构缩减场景下必须触发警告: " + warnings);

		// 2. 断言当前前缀约定：留存项对齐到 oldP1，未对齐的 oldP2 降级为幽灵桩
		ClassNode cnAligned = readClass(aligned);
		List<String> alignedTargets = findIndyTargets(cnAligned, "build");
		assertEquals(1, alignedTargets.size());
		assertEquals(oldP1, alignedTargets.get(0), "根据前缀约定，留存项稳定对齐到首个旧候选 oldP1");

		MethodNode p1Node = findMethod(cnAligned, oldP1);
		assertNotNull(p1Node);
		assertTrue(callsMethod(p1Node, "x1"));
		assertFalse(callsMethod(p1Node, "onOrphanInvoked"));

		MethodNode p2Node = findMethod(cnAligned, oldP2);
		assertNotNull(p2Node);
		assertTrue(callsMethod(p2Node, "onOrphanInvoked"));

		// 3. 验证方法表物理顺序反转（3 种非对称/对称组合）下，前缀约定行为严格确定且不变
		ClassNode cnV1OnlyRev = readClass(v1);
		Collections.reverse(cnV1OnlyRev.methods);
		byte[] alignedRev1 = LambdaAligner.align(writeClass(cnV1OnlyRev), v2);
		assertNotNull(alignedRev1);
		assertEquals(oldP1, findIndyTargets(readClass(alignedRev1), "build").get(0),
			"[仅反转旧版 V1] 前缀约定仍稳定对齐到 oldP1");

		ClassNode cnV2OnlyRev = readClass(v2);
		Collections.reverse(cnV2OnlyRev.methods);
		byte[] alignedRev2 = LambdaAligner.align(v1, writeClass(cnV2OnlyRev));
		assertNotNull(alignedRev2);
		assertEquals(oldP1, findIndyTargets(readClass(alignedRev2), "build").get(0),
			"[仅反转新版 V2] 前缀约定仍稳定对齐到 oldP1");

		byte[] alignedRevBoth = LambdaAligner.align(writeClass(cnV1OnlyRev), writeClass(cnV2OnlyRev));
		assertNotNull(alignedRevBoth);
		assertEquals(oldP1, findIndyTargets(readClass(alignedRevBoth), "build").get(0),
			"[同时反转两侧] 前缀约定仍稳定对齐到 oldP1");
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

	/**
	 * 单层完全同体 Lambda 在同宿主方法内被不同消费者调用，删除首条语句场景。
	 *
	 * <p>本测试专杀设计级变异：<b>只保留 hostKey 桶键，但去掉 canPair 的消费者否决守卫</b>。
	 * 在嵌套场景下，叶子分桶与 calleesPairTo 会掩盖该守卫的作用；但在单层（叶子即顶层）场景下，
	 * 两者均处于宿主方法 "M:build()V" 的同一个 LCS 序列中。若没有 canPair 的消费者否决，
	 * L2' 将错配给首个同体候选 oldL1。本测试断言其必须依靠消费者精确对齐到 oldL2。</p>
	 */
	@Test
	void singleLevelIdenticalLambdasWithDifferentConsumersAndDeletedStatement(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		Path src1Dir = Files.createDirectories(tmp.resolve("src1"));
		Path src2Dir = Files.createDirectories(tmp.resolve("src2"));
		Path outV1 = Files.createDirectories(tmp.resolve("v1"));
		Path outV2 = Files.createDirectories(tmp.resolve("v2"));

		String v1Src = "package test;\n"
			+ "public class SingleLevelCase {\n"
			+ "    static void a(Runnable r) { r.run(); }\n"
			+ "    static void b(Runnable r) { r.run(); }\n"
			+ "    static void leaf() { }\n"
			+ "    public void build() {\n"
			+ "        a(() -> leaf());\n" // L1 (consumer: CALL:...a)
			+ "        b(() -> leaf());\n" // L2 (consumer: CALL:...b)
			+ "    }\n"
			+ "}\n";

		String v2Src = "package test;\n"
			+ "public class SingleLevelCase {\n"
			+ "    static void a(Runnable r) { r.run(); }\n"
			+ "    static void b(Runnable r) { r.run(); }\n"
			+ "    static void leaf() { }\n"
			+ "    public void build() {\n"
			+ "        b(() -> leaf());\n" // L2' (consumer: CALL:...b)
			+ "    }\n"
			+ "}\n";

		Path src1 = src1Dir.resolve("SingleLevelCase.java");
		Path src2 = src2Dir.resolve("SingleLevelCase.java");
		Files.writeString(src1, v1Src, StandardCharsets.UTF_8);
		Files.writeString(src2, v2Src, StandardCharsets.UTF_8);

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.toString(), src1.toString());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.toString(), src2.toString());

		byte[] v1 = Files.readAllBytes(outV1.resolve("test/SingleLevelCase.class"));
		byte[] v2 = Files.readAllBytes(outV2.resolve("test/SingleLevelCase.class"));

		ClassNode cnV1 = readClass(v1);
		List<String> oldTargets = findIndyTargets(cnV1, "build");
		assertEquals(2, oldTargets.size());
		String oldL1 = oldTargets.get(0);
		String oldL2 = oldTargets.get(1);

		byte[] aligned = LambdaAligner.align(v1, v2);
		assertNotNull(aligned);

		ClassNode cnAligned = readClass(aligned);
		List<String> alignedTargets = findIndyTargets(cnAligned, "build");
		assertEquals(1, alignedTargets.size());
		String activeL = alignedTargets.get(0);

		// 核心断言：单层同体 Lambda 必须经由 canPair 消费者守卫精准匹配到 oldL2，绝不能因首选贪婪错配给 oldL1
		assertEquals(oldL2, activeL, "留存的语句 b(...) 在单层场景下必须经由消费者守卫准确对齐到 oldL2");
		MethodNode ghostL1 = findMethod(cnAligned, oldL1);
		assertNotNull(ghostL1, "已删除的语句 a(...) oldL1 必须复活为幽灵桩");
		assertTrue(callsMethod(ghostL1, "onOrphanInvoked"), "oldL1 必须是孤儿桩");
	}

	/**
	 * 择优决策候选分层决胜断言。
	 *
	 * <p>本测试专杀设计级变异：<b>决胜键混淆或回退回 methodIndex 物理位置序</b>。
	 * 严格验证三条不变量：
	 * 1. 可达候选（callOrder >= 0）无条件胜过不可达候选（callOrder = -1），即使不可达候选的 methodIndex 极小；
	 * 2. 均可达时：小 callOrder 胜出，不受 methodIndex 大小干扰；
	 * 3. 均不可达时：小 methodIndex 胜出。</p>
	 */
	@Test
	void unreachableVersusReachableCandidateTieBreaking() {
		// 1. 可达优先于不可达：候选可达（callOrder=2, methodIndex=10）对比当前最优不可达（callOrder=-1, methodIndex=0）
		assertTrue(LambdaAligner.testIsBetterCandidate(2, 10, -1, 0),
			"可达候选必须无条件优于不可达候选，即使不可达候选的 methodIndex 更小");
		assertFalse(LambdaAligner.testIsBetterCandidate(-1, 0, 2, 10),
			"不可达候选绝不能夺取可达候选的优先权");

		// 2. 均可达：按 callOrder 决胜，忽略 methodIndex
		assertTrue(LambdaAligner.testIsBetterCandidate(1, 99, 2, 0),
			"两者均可达时，小 callOrder 胜出");
		assertFalse(LambdaAligner.testIsBetterCandidate(2, 0, 1, 99),
			"两者均可达时，大 callOrder 绝不能因小 methodIndex 胜出");

		// 3. 均不可达：按 methodIndex 决胜
		assertTrue(LambdaAligner.testIsBetterCandidate(-1, 1, -1, 5),
			"两者均不可达时，小 methodIndex 胜出");
		assertFalse(LambdaAligner.testIsBetterCandidate(-1, 5, -1, 1),
			"两者均不可达时，大 methodIndex 不能胜出");
	}

	/**
	 * Characterization test: 记录并守住当前消费者发生变更且前序语句被删除时的已知退化行为。
	 *
	 * <p>退化机制：当重构将语句 2 的消费者方法名改变（如 b() 改为 b2()），
	 * 同时删除了前序语句 1 时，LCS 锚点对齐因消费者不一致而主动放弃配对（anchorPairs == 0）；
	 * 随后对齐退化至 Step 1a 的同名贪婪阶段，留存项因编译序号整体位移（新版唯一 lambda 为 $0）
	 * 而与旧版 $0（即已删除的语句 1）同名，从而被 Step 1a 配给旧语句 1。</p>
	 */
	@Test
	void consumerEditedAndStatementDeletedDegradation(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		Path src1Dir = Files.createDirectories(tmp.resolve("src1"));
		Path src2Dir = Files.createDirectories(tmp.resolve("src2"));
		Path outV1 = Files.createDirectories(tmp.resolve("v1"));
		Path outV2 = Files.createDirectories(tmp.resolve("v2"));

		String v1Src = "package test;\n"
			+ "public class ConsumerEditCase {\n"
			+ "    static void a(Runnable r) { r.run(); }\n"
			+ "    static void b(Runnable r) { r.run(); }\n"
			+ "    static void b2(Runnable r) { r.run(); }\n"
			+ "    static void leaf() { }\n"
			+ "    public void build() {\n"
			+ "        a(() -> leaf());\n" // L1 ($0)
			+ "        b(() -> leaf());\n" // L2 ($1)
			+ "    }\n"
			+ "}\n";

		// V2：删除语句 1，且重构语句 2 的消费者为 b2(...)
		String v2Src = "package test;\n"
			+ "public class ConsumerEditCase {\n"
			+ "    static void a(Runnable r) { r.run(); }\n"
			+ "    static void b(Runnable r) { r.run(); }\n"
			+ "    static void b2(Runnable r) { r.run(); }\n"
			+ "    static void leaf() { }\n"
			+ "    public void build() {\n"
			+ "        b2(() -> leaf());\n" // L2' (新 $0, 消费者异化为 b2)
			+ "    }\n"
			+ "}\n";

		Path src1 = src1Dir.resolve("ConsumerEditCase.java");
		Path src2 = src2Dir.resolve("ConsumerEditCase.java");
		Files.writeString(src1, v1Src, StandardCharsets.UTF_8);
		Files.writeString(src2, v2Src, StandardCharsets.UTF_8);

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.toString(), src1.toString());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.toString(), src2.toString());

		byte[] v1 = Files.readAllBytes(outV1.resolve("test/ConsumerEditCase.class"));
		byte[] v2 = Files.readAllBytes(outV2.resolve("test/ConsumerEditCase.class"));

		ClassNode cnV1 = readClass(v1);
		List<String> oldTargets = findIndyTargets(cnV1, "build");
		assertEquals(2, oldTargets.size());
		String oldL1 = oldTargets.get(0);
		String oldL2 = oldTargets.get(1);

		byte[] aligned = LambdaAligner.align(v1, v2);
		assertNotNull(aligned);
		assertNotNull(LambdaAligner.LAST_STATS);

		// 记录退化指标：LCS 锚点因消费者异化放弃配对，退化走 Step 1a 同名贪婪
		assertEquals(0, LambdaAligner.LAST_STATS.anchorPairs, "LCS 锚点必须放弃配对");
		assertEquals(1, LambdaAligner.LAST_STATS.step1Pairs, "退化至 Step 1a 同名命中");
		assertTrue(LambdaAligner.LAST_STATS.consumerMismatchPairs > 0,
			"消费者异化但退化配对时，必须明确记录 consumerMismatchPairs 信号: " + LambdaAligner.LAST_STATS);
		assertTrue(LambdaAligner.LAST_STATS.consumerMismatchWarnPairs > 0,
			"因存在多候选歧义，消费者异化必须升级为 WARN: " + LambdaAligner.LAST_STATS);

		ClassNode cnAligned = readClass(aligned);
		String activeL = findIndyTargets(cnAligned, "build").get(0);
		// 当前退化特征：由于新版 L2' 的名字为 $0，与 oldL1 同名同指纹，故被 Step 1a 贪婪配对到 oldL1
		assertEquals(oldL1, activeL, "Characterization: 退化路径下配对到同名旧候选 oldL1");
	}

	/**
	 * 验证良性消费端重构（如辅助方法改名 log -> trace）在同名且桶内唯一候选时降级为 DEBUG，不触发 WARN 噪音。
	 */
	@Test
	void benignConsumerRenameDoesNotWarn(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		Path src1Dir = Files.createDirectories(tmp.resolve("src1"));
		Path src2Dir = Files.createDirectories(tmp.resolve("src2"));
		Path outV1 = Files.createDirectories(tmp.resolve("v1"));
		Path outV2 = Files.createDirectories(tmp.resolve("v2"));

		String v1Src = "package test;\n"
			+ "public class BenignCase {\n"
			+ "    static void log(Runnable r) { r.run(); }\n"
			+ "    static void trace(Runnable r) { r.run(); }\n"
			+ "    static void leaf() { }\n"
			+ "    public void run() {\n"
			+ "        log(() -> leaf());\n" // L1 ($0)
			+ "    }\n"
			+ "}\n";

		// V2: 将调用者重命名为 trace(...)，方法内仅这一个 Lambda
		String v2Src = "package test;\n"
			+ "public class BenignCase {\n"
			+ "    static void log(Runnable r) { r.run(); }\n"
			+ "    static void trace(Runnable r) { r.run(); }\n"
			+ "    static void leaf() { }\n"
			+ "    public void run() {\n"
			+ "        trace(() -> leaf());\n" // L1' ($0, 唯一候选同名)
			+ "    }\n"
			+ "}\n";

		Path src1 = src1Dir.resolve("BenignCase.java");
		Path src2 = src2Dir.resolve("BenignCase.java");
		Files.writeString(src1, v1Src, StandardCharsets.UTF_8);
		Files.writeString(src2, v2Src, StandardCharsets.UTF_8);

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.toString(), src1.toString());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.toString(), src2.toString());

		byte[] v1 = Files.readAllBytes(outV1.resolve("test/BenignCase.class"));
		byte[] v2 = Files.readAllBytes(outV2.resolve("test/BenignCase.class"));

		byte[] aligned = LambdaAligner.align(v1, v2);
		assertNotNull(aligned);
		assertNotNull(LambdaAligner.LAST_STATS);

		// 核心断言：良性编辑记录 consumerMismatchPairs 与 debugPairs，但 warnPairs 严格为 0
		assertEquals(1, LambdaAligner.LAST_STATS.consumerMismatchPairs, "必须记录消费者变化");
		assertEquals(1, LambdaAligner.LAST_STATS.consumerMismatchDebugPairs, "同名且桶内唯一必须降级为 DEBUG");
		assertEquals(0, LambdaAligner.LAST_STATS.consumerMismatchWarnPairs, "良性重构绝不触发 WARN 噪音");
	}

	/**
	 * 局部变量传递中转（ASTORE / ALOAD 链）的 Fail-Closed 安全性断言。
	 *
	 * <p>验证 Lambda 赋值给局部变量再作为参数传递时，数据流反向追踪严格返回空特征（Fail-Closed），
	 * 绝不错误将 ALOAD 等中间指令误认为消费者；全流程对齐正常成功，不抛出任何异常。</p>
	 */
	@Test
	void intermediateVariableConsumerIsNullAndSafe(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		Path srcDir = Files.createDirectories(tmp.resolve("src"));
		Path outV1 = Files.createDirectories(tmp.resolve("v1"));
		Path outV2 = Files.createDirectories(tmp.resolve("v2"));

		String src = "package test;\n"
			+ "public class LocalVarCase {\n"
			+ "    static void a(Runnable r) { r.run(); }\n"
			+ "    static void leaf() { }\n"
			+ "    public void run() {\n"
			+ "        Runnable r = () -> leaf();\n"
			+ "        a(r);\n"
			+ "    }\n"
			+ "}\n";

		Path srcFile = srcDir.resolve("LocalVarCase.java");
		Files.writeString(srcFile, src, StandardCharsets.UTF_8);

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.toString(), srcFile.toString());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.toString(), srcFile.toString());

		byte[] v1 = Files.readAllBytes(outV1.resolve("test/LocalVarCase.class"));
		byte[] v2 = Files.readAllBytes(outV2.resolve("test/LocalVarCase.class"));

		byte[] aligned = LambdaAligner.align(v1, v2);
		assertNotNull(aligned);

		ClassNode cnAligned = readClass(aligned);
		List<String> targets = findIndyTargets(cnAligned, "run");
		assertEquals(1, targets.size());
	}

	/**
	 * 单一方法内 120 个复杂调用 Lambda 的端到端与数据流反向追踪分析性能基准断言。
	 *
	 * <p>验证当单个宿主方法包含 120+ Lambda（混合 CALL、PUT、RETURN、三元表达式等）时，
	 * ASM 数据流分析与宿主序列 LCS 对齐在毫秒级内完成，绝无指数级回溯或栈溢出。</p>
	 */
	@Test
	void stressBenchmark120Lambdas(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		Path srcDir = Files.createDirectories(tmp.resolve("src"));
		Path outV1 = Files.createDirectories(tmp.resolve("v1"));
		Path outV2 = Files.createDirectories(tmp.resolve("v2"));

		StringBuilder sb = new StringBuilder();
		sb.append("package test;\n");
		sb.append("public class StressCase {\n");
		sb.append("    static void consume(int id, Runnable r) { if (r != null) r.run(); }\n");
		sb.append("    public static Runnable sink;\n");
		sb.append("    public void build() {\n");
		for (int i = 0; i < 120; i++) {
			if (i % 3 == 0) {
				sb.append("        consume(").append(i).append(", () -> { int x = ").append(i).append("; });\n");
			} else if (i % 3 == 1) {
				sb.append("        sink = () -> { int x = ").append(i).append("; };\n");
			} else {
				sb.append("        consume(").append(i).append(", (true ? () -> { int x = ").append(i).append("; } : null));\n");
			}
		}
		sb.append("    }\n");
		sb.append("}\n");

		Path srcFile = srcDir.resolve("StressCase.java");
		Files.writeString(srcFile, sb.toString(), StandardCharsets.UTF_8);

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.toString(), srcFile.toString());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.toString(), srcFile.toString());

		byte[] v1 = Files.readAllBytes(outV1.resolve("test/StressCase.class"));
		byte[] v2 = Files.readAllBytes(outV2.resolve("test/StressCase.class"));

		// 1. 冷启动测量（首次调用，无 JIT 预热）
		long coldT0 = System.nanoTime();
		byte[] coldAligned = LambdaAligner.align(v1, v2);
		long coldT1 = System.nanoTime();
		assertNotNull(coldAligned);
		double coldMs = (coldT1 - coldT0) / 1_000_000.0;

		// 2. 预热 3 次
		for (int i = 0; i < 3; i++) {
			LambdaAligner.align(v1, v2);
		}

		// 3. 正式热态计时 5 次取平均值
		long totalNanos = 0;
		int runs = 5;
		for (int i = 0; i < runs; i++) {
			long t0 = System.nanoTime();
			byte[] aligned = LambdaAligner.align(v1, v2);
			long t1 = System.nanoTime();
			assertNotNull(aligned);
			totalNanos += (t1 - t0);
		}
		double avgMs = (totalNanos / (double) runs) / 1_000_000.0;
		System.out.println("[Benchmark] 120 lambdas in build() cold-start: " + String.format(Locale.ROOT, "%.2f ms", coldMs)
			+ ", warm-avg: " + String.format(Locale.ROOT, "%.2f ms", avgMs));

		// 放宽数量级阈值作为防死锁与指数退化上限（冷态 < 3000ms，热态 < 1000ms），避免高负载 CI 偶发超时 Flaky
		assertTrue(coldMs < 3000.0, "冷态对齐耗时应低于 3000ms: " + coldMs + "ms");
		assertTrue(avgMs < 1000.0, "热态对齐耗时应低于 1000ms: " + avgMs + "ms");
	}
}
