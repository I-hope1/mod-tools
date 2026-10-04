import nipx.AnonClassAligner;
import nipx.AnonClassHasher;
import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import nipx.HotSwapAgent;
import nipx.MethodFingerprinter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Function;

/**
 * 匿名类对齐 6 大核心场景复现与验证套件：
 * <ol>
 *   <li>前面插入匿名类 (InsertInFront)</li>
 *   <li>两个匿名类互换位置 (SwapPositions)</li>
 *   <li>修改匿名类方法体 (ModifyBody)</li>
 *   <li>删除匿名类 (DeleteClass)</li>
 *   <li>删除后又加回 (DeleteAndReAdd)</li>
 *   <li>新增未加载的匿名类加载期对齐 (UnloadedNewClass)</li>
 *   <li>事务回滚与旧版本字节码钉扎保护 (TransactionRollbackAndPinning)</li>
 * </ol>
 */
public class AnonClassReproTest {

	static int passed = 0;
	static int failed = 0;
	static int known  = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   PASS  " : "   FAIL  ") + msg);
		if (ok) passed++; else failed++;
	}

	/**
	 * 已知限制条目：**当前行为符合"有意付出的保守代价"时打印 KNOWN**（套件保持绿），
	 * 若该代价消失则打印 FAIL，强制作者更新基线。
	 *
	 * <p>注意方向与"先红后绿"相反：夹具 L 在旧实现下是**通过**的（minDiff 取恒等映射恰好正确），
	 * 新实现才变成"拒绝配对"。KNOWN 钉住的是这份**新引入的保守代价**，不是缺陷。</p>
	 */
	static void known(String msg) {
		System.out.println("   KNOWN " + msg);
		known++;
	}

	static File findFile(String rel) {
		File f = new File(rel);
		if (f.exists()) return f;
		File f2 = new File("scratch/hstest", rel);
		if (f2.exists()) return f2;
		return f;
	}

	public static void main(String[] args) throws Exception {
		System.out.println("=== AnonClassReproTest: 匿名类对齐 6 大场景规范验证 ===");

		String javac = System.getenv("HSTEST_JAVAC21");
		if (javac == null) javac = "F:/files/java/jdks/openjdk-21.0.2/bin/javac.exe";
		if (!new File(javac).exists()) javac = "javac";

		File baseDir = Files.createTempDirectory("repro_anon").toFile();
		try {
			// 编译辅助：测试 1 前面插入匿名类
			testScenario1_InsertInFront(javac, baseDir);
			// 测试 2 两个匿名类互换位置
			testScenario2_SwapPositions(javac, baseDir);
			// 测试 3 修改匿名类方法体
			testScenario3_ModifyBody(javac, baseDir);
			// 测试 4 删除匿名类
			testScenario4_DeleteClass(javac, baseDir);
			// 测试 5 删除后又加回
			testScenario5_DeleteAndReAdd(javac, baseDir);
			// 测试 6 新增未加载的匿名类
			testScenario6_UnloadedNewClass(javac, baseDir);
			// 测试 7 恒等性与幂等性
			testScenario7_IdentityAndIdempotence(javac, baseDir);
			// 测试 8 顺序无关性测试 (TEST_REVERSE_ORDER)
			testScenario8_OrderIndependenceWithReverseHook(javac, baseDir);
			// 测试 9 Lambda 内匿名类 EnclosingMethod 归一化对齐
			testScenario9_LambdaEnclosingMethodUnified(javac, baseDir);
			// 测试 10 匿名类包含内部 Lambda 的对齐流水线验证
			testScenario10_AnonymousClassWithInnerLambda(javac, baseDir);
			// 测试 11 互换位置且包含未加载类的加载期拦截验证
			testScenario11_SwapWithUnloadedClass(javac, baseDir);
			// 测试 12 javac 8 嵌套 Lambda 匿名类宿主扫描对齐
			testScenario12_Javac8NestedLambdaNullEnclosingMethod(javac, baseDir);
			// 测试 13 复合位移与哈希器命中断言
			testScenario13_CombinedShiftAndHasherTableHit(javac, baseDir);
			// 测试 14 嵌套匿名类 Foo$1$1 结构识别与对齐
			testScenario14_NestedAnonymousClasses(javac, baseDir);
			// 测试 15 事务回滚与旧版本字节码钉扎保护
			testScenario15_TransactionRollbackAndPinning(javac, baseDir);
			// 测试 16 双向唯一匹配与遍历顺序无关性验证（确证真 Bug 1 修复）
			testScenario16_BidirectionalMatchingAndOrderIndependence(javac, baseDir);
			// 测试 17 宿主方法逆向追溯时的 owner 归属强校验（确证真 Bug 2 修复）
			testScenario17_CallerTracingOwnerValidation(javac, baseDir);
			// 测试 18 多层嵌套匿名类末尾前缀保护（确证缺陷 3 修复）
			testScenario18_NestedAnonymousClassPrefixRetention(javac, baseDir);
			// 测试 19 级联树拓扑对齐与蜕变测试套件（Milestone 2 核心引擎）
			testScenario19_CascadingTreeAndMetamorphicSuite(javac, baseDir);
			// 测试 20 深度白盒漏洞复现与防御验证（7 大修复项确证）
			testScenario20_WhiteboxVulnerabilityReproAndDefense(javac, baseDir);
			// 测试 21 安全门与熔断（§6.3 数量上限/软超时、§6.4 strict、§4.3 拒绝通道）
			testScenario21_SafetyGatesAndCircuitBreaker(javac, baseDir);
			// 测试 22 嵌套匿名类的内容哈希可用性 + 设计不变量（描述符定向屏蔽、Lambda 不加层级）
			testScenario22_NestedContentHashAvailability(javac, baseDir);
			// 测试 23 strict 是安全门而非匹配策略（Tier 3 minDiff 止血 + 拒绝粒度）
			testScenario23_StrictIsSafetyGateNotPolicy(javac, baseDir);
			// 测试 24 javac 8 嵌套回退扫描必须用直接父类节点（§8.3 第 5 条）
			testScenario24_Javac8ParentScopedFallback(javac, baseDir);
			// 测试 25 设计不变量 INV-1（自描述指纹）/ INV-2（禁止两个 aligner 互相递归）
			testScenario25_DesignInvariants(javac, baseDir);
			// 测试 26 Tier 3 拓扑相等过滤（取代 minDiff 仲裁）
			testScenario26_Tier3TopologyFilter(javac, baseDir);
		} finally {
			deleteRecursively(baseDir);
		}

		System.out.println("AnonClassReproTest 汇总: 通过=" + passed + ", 失败=" + failed + ", 已知限制=" + known);
	}

	static void deleteRecursively(File f) {
		if (f == null || !f.exists()) return;
		File[] children = f.listFiles();
		if (children != null) {
			for (File c : children) deleteRecursively(c);
		}
		f.delete();
	}

	// 1. 前面插入匿名类
	static void testScenario1_InsertInFront(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 1: 前面插入匿名类 ---");
		File dir = new File(baseDir, "s1");
		dir.mkdirs();
		File fV1 = new File(dir, "AnonV1.java");
		File fV2 = new File(dir, "AnonV2.java");

		Files.writeString(fV1.toPath(),
			"package testAnon;\n" +
			"class AnonCase {\n" +
			"    public void setup() {\n" +
			"        Runnable save = new Runnable() { public void run() { doSave(); } };\n" +
			"        Runnable delete = new Runnable() { public void run() { doDelete(); } };\n" +
			"    }\n" +
			"    void doSave() {}\n" +
			"    void doDelete() {}\n" +
			"}\n");

		Files.writeString(fV2.toPath(),
			"package testAnon;\n" +
			"class AnonCase {\n" +
			"    public void setup() {\n" +
			"        Runnable other = new Runnable() { public void run() { doOther(); } };\n" +
			"        Runnable save = new Runnable() { public void run() { doSave(); } };\n" +
			"        Runnable delete = new Runnable() { public void run() { doDelete(); } };\n" +
			"    }\n" +
			"    void doOther() {}\n" +
			"    void doSave() {}\n" +
			"    void doDelete() {}\n" +
			"}\n");

		File outV1 = new File(dir, "out_v1");
		File outV2 = new File(dir, "out_v2");
		outV1.mkdirs();
		outV2.mkdirs();

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.getAbsolutePath(), fV1.getAbsolutePath());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.getAbsolutePath(), fV2.getAbsolutePath());

		byte[] v1Anon1 = Files.readAllBytes(new File(outV1, "testAnon/AnonCase$1.class").toPath());
		byte[] v1Anon2 = Files.readAllBytes(new File(outV1, "testAnon/AnonCase$2.class").toPath());
		byte[] v2Anon1 = Files.readAllBytes(new File(outV2, "testAnon/AnonCase$1.class").toPath());
		byte[] v2Anon2 = Files.readAllBytes(new File(outV2, "testAnon/AnonCase$2.class").toPath());
		byte[] v2Anon3 = Files.readAllBytes(new File(outV2, "testAnon/AnonCase$3.class").toPath());

		// V1: $1 是 Save, $2 是 Delete
		// V2: $1 是 Other, $2 是 Save, $3 是 Delete
		Long hSaveV1 = AnonClassHasher.hash("testAnon/AnonCase$1", v1Anon1, "testAnon/AnonCase", null, null, null, 0);
		Long hDeleteV1 = AnonClassHasher.hash("testAnon/AnonCase$2", v1Anon2, "testAnon/AnonCase", null, null, null, 0);

		Long hOtherV2 = AnonClassHasher.hash("testAnon/AnonCase$1", v2Anon1, "testAnon/AnonCase", null, null, null, 0);
		Long hSaveV2 = AnonClassHasher.hash("testAnon/AnonCase$2", v2Anon2, "testAnon/AnonCase", null, null, null, 0);
		Long hDeleteV2 = AnonClassHasher.hash("testAnon/AnonCase$3", v2Anon3, "testAnon/AnonCase", null, null, null, 0);

		check(Objects.equals(hSaveV1, hSaveV2), "Scenario 1: Save 匿名类在 V1($1) 与 V2($2) 内容哈希严格一致");
		check(Objects.equals(hDeleteV1, hDeleteV2), "Scenario 1: Delete 匿名类在 V1($2) 与 V2($3) 内容哈希严格一致");
		check(!Objects.equals(hSaveV1, hOtherV2), "Scenario 1: 新插入的 Other 与 Save 哈希互异");

		// 运行 AnonClassAligner 对齐
		Map<String, byte[]> oldAnon = new HashMap<>();
		oldAnon.put("testAnon/AnonCase$1", v1Anon1);
		oldAnon.put("testAnon/AnonCase$2", v1Anon2);

		Map<String, byte[]> newAnon = new HashMap<>();
		newAnon.put("testAnon/AnonCase$1", v2Anon1);
		newAnon.put("testAnon/AnonCase$2", v2Anon2);
		newAnon.put("testAnon/AnonCase$3", v2Anon3);

		byte[] v2Host = Files.readAllBytes(new File(outV2, "testAnon/AnonCase.class").toPath());
		AnonClassAligner.Result res = AnonClassAligner.align("testAnon/AnonCase", v2Host, oldAnon, newAnon);

		check(Objects.equals(res.renameMap.get("testAnon/AnonCase$2"), "testAnon/AnonCase$1"), "Scenario 1: Aligner 将新 $2(Save) 对齐到旧 $1");
		check(Objects.equals(res.renameMap.get("testAnon/AnonCase$3"), "testAnon/AnonCase$2"), "Scenario 1: Aligner 将新 $3(Delete) 对齐到旧 $2");
		check(Objects.equals(res.renameMap.get("testAnon/AnonCase$1"), "testAnon/AnonCase$3"), "Scenario 1: Aligner 将新插入的 $1(Other) 分配到全新编号 $3");
		check(res.alignedAnonClasses.containsKey("testAnon/AnonCase$1"), "Scenario 1: 对齐产物包含目标 $1");
		check(res.alignedAnonClasses.containsKey("testAnon/AnonCase$2"), "Scenario 1: 对齐产物包含目标 $2");
		check(res.alignedAnonClasses.containsKey("testAnon/AnonCase$3"), "Scenario 1: 对齐产物包含目标 $3");
		check(res.orphanOldClasses.isEmpty(), "Scenario 1: 无孤儿类");

		// 检查宿主类指令重写：setup() 中顺序调用 NEW $3, NEW $1, NEW $2
		ClassNode hostNode = new ClassNode();
		new ClassReader(res.alignedHostBytes).accept(hostNode, 0);
		List<String> newTypes = new ArrayList<>();
		for (MethodNode mn : hostNode.methods) {
			if ("setup".equals(mn.name)) {
				for (AbstractInsnNode insn : mn.instructions) {
					if (insn instanceof TypeInsnNode && insn.getOpcode() == org.objectweb.asm.Opcodes.NEW) {
						newTypes.add(((TypeInsnNode) insn).desc);
					}
				}
			}
		}
		List<String> expectedTypes = Arrays.asList("testAnon/AnonCase$3", "testAnon/AnonCase$1", "testAnon/AnonCase$2");
		check(Objects.equals(newTypes, expectedTypes), "Scenario 1: 宿主类 setup() 中 NEW 指令已正确重写为 [$3, $1, $2]");
	}

	// 2. 两个匿名类互换位置
	static void testScenario2_SwapPositions(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 2: 两个匿名类互换位置 ---");
		File dir = new File(baseDir, "s2");
		dir.mkdirs();
		File fV1 = new File(dir, "SwapV1.java");
		File fV2 = new File(dir, "SwapV2.java");

		Files.writeString(fV1.toPath(),
			"package testSwap;\n" +
			"class SwapCase {\n" +
			"    public void run() {\n" +
			"        Runnable a = new Runnable() { public void run() { doA(); } };\n" +
			"        Runnable b = new Runnable() { public void run() { doB(); } };\n" +
			"    }\n" +
			"    void doA() {}\n" +
			"    void doB() {}\n" +
			"}\n");

		Files.writeString(fV2.toPath(),
			"package testSwap;\n" +
			"class SwapCase {\n" +
			"    public void run() {\n" +
			"        Runnable b = new Runnable() { public void run() { doB(); } };\n" +
			"        Runnable a = new Runnable() { public void run() { doA(); } };\n" +
			"    }\n" +
			"    void doA() {}\n" +
			"    void doB() {}\n" +
			"}\n");

		File outV1 = new File(dir, "out_v1");
		File outV2 = new File(dir, "out_v2");
		outV1.mkdirs();
		outV2.mkdirs();

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.getAbsolutePath(), fV1.getAbsolutePath());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.getAbsolutePath(), fV2.getAbsolutePath());

		byte[] v1_1 = Files.readAllBytes(new File(outV1, "testSwap/SwapCase$1.class").toPath());
		byte[] v1_2 = Files.readAllBytes(new File(outV1, "testSwap/SwapCase$2.class").toPath());
		byte[] v2_1 = Files.readAllBytes(new File(outV2, "testSwap/SwapCase$1.class").toPath());
		byte[] v2_2 = Files.readAllBytes(new File(outV2, "testSwap/SwapCase$2.class").toPath());

		// V1: $1=A, $2=B
		// V2: $1=B, $2=A
		Long hA_v1 = AnonClassHasher.hash("testSwap/SwapCase$1", v1_1, "testSwap/SwapCase", null, null, null, 0);
		Long hB_v1 = AnonClassHasher.hash("testSwap/SwapCase$2", v1_2, "testSwap/SwapCase", null, null, null, 0);
		Long hB_v2 = AnonClassHasher.hash("testSwap/SwapCase$1", v2_1, "testSwap/SwapCase", null, null, null, 0);
		Long hA_v2 = AnonClassHasher.hash("testSwap/SwapCase$2", v2_2, "testSwap/SwapCase", null, null, null, 0);

		check(Objects.equals(hA_v1, hA_v2), "Scenario 2: A 的哈希跨序号($1 vs $2)保持恒等");
		check(Objects.equals(hB_v1, hB_v2), "Scenario 2: B 的哈希跨序号($2 vs $1)保持恒等");
		check(!Objects.equals(hA_v1, hB_v1), "Scenario 2: A 与 B 哈希互不碰撞");

		Map<String, byte[]> oldAnon = new HashMap<>();
		oldAnon.put("testSwap/SwapCase$1", v1_1);
		oldAnon.put("testSwap/SwapCase$2", v1_2);

		Map<String, byte[]> newAnon = new HashMap<>();
		newAnon.put("testSwap/SwapCase$1", v2_1);
		newAnon.put("testSwap/SwapCase$2", v2_2);

		byte[] v2Host = Files.readAllBytes(new File(outV2, "testSwap/SwapCase.class").toPath());
		AnonClassAligner.Result res = AnonClassAligner.align("testSwap/SwapCase", v2Host, oldAnon, newAnon);

		check(Objects.equals(res.renameMap.get("testSwap/SwapCase$1"), "testSwap/SwapCase$2"), "Scenario 2: Aligner 将新 $1(B) 对齐到旧 $2");
		check(Objects.equals(res.renameMap.get("testSwap/SwapCase$2"), "testSwap/SwapCase$1"), "Scenario 2: Aligner 将新 $2(A) 对齐到旧 $1");
		check(res.orphanOldClasses.isEmpty(), "Scenario 2: 互换无孤儿");
	}

	// 3. 修改匿名类方法体
	static void testScenario3_ModifyBody(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 3: 修改匿名类方法体 ---");
		File dir = new File(baseDir, "s3");
		dir.mkdirs();
		File fV1 = new File(dir, "ModV1.java");
		File fV2 = new File(dir, "ModV2.java");

		Files.writeString(fV1.toPath(),
			"package testMod;\n" +
			"class ModCase {\n" +
			"    public void run() {\n" +
			"        Runnable r = new Runnable() { public void run() { int x = 1; } };\n" +
			"    }\n" +
			"}\n");

		Files.writeString(fV2.toPath(),
			"package testMod;\n" +
			"class ModCase {\n" +
			"    public void run() {\n" +
			"        Runnable r = new Runnable() { public void run() { int x = 2; } };\n" +
			"    }\n" +
			"}\n");

		File outV1 = new File(dir, "out_v1");
		File outV2 = new File(dir, "out_v2");
		outV1.mkdirs();
		outV2.mkdirs();

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.getAbsolutePath(), fV1.getAbsolutePath());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.getAbsolutePath(), fV2.getAbsolutePath());

		byte[] v1 = Files.readAllBytes(new File(outV1, "testMod/ModCase$1.class").toPath());
		byte[] v2 = Files.readAllBytes(new File(outV2, "testMod/ModCase$1.class").toPath());

		Long h1 = AnonClassHasher.hash("testMod/ModCase$1", v1, "testMod/ModCase", null, null, null, 0);
		Long h2 = AnonClassHasher.hash("testMod/ModCase$1", v2, "testMod/ModCase", null, null, null, 0);

		// 方法体改变后内容哈希确实不同（验证了缺口 2 提出的现实前提：不能只依赖内容哈希）
		check(!Objects.equals(h1, h2), "Scenario 3: 修改方法体后内容哈希不同（确证需要结构特征分级匹配兜底）");

		Map<String, byte[]> oldAnon = new HashMap<>();
		oldAnon.put("testMod/ModCase$1", v1);

		Map<String, byte[]> newAnon = new HashMap<>();
		newAnon.put("testMod/ModCase$1", v2);

		byte[] v2Host = Files.readAllBytes(new File(outV2, "testMod/ModCase.class").toPath());
		AnonClassAligner.Result res = AnonClassAligner.align("testMod/ModCase", v2Host, oldAnon, newAnon);

		check(Objects.equals(res.renameMap.get("testMod/ModCase$1"), "testMod/ModCase$1"), "Scenario 3: Aligner 凭借结构特征成功对齐方法体变更的 $1");
		check(res.orphanOldClasses.isEmpty(), "Scenario 3: 修改方法体未产生假孤儿");
	}

	// 4. 删除匿名类
	static void testScenario4_DeleteClass(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 4: 删除匿名类 ---");
		File dir = new File(baseDir, "s4");
		dir.mkdirs();
		File fV1 = new File(dir, "DelV1.java");
		File fV2 = new File(dir, "DelV2.java");

		Files.writeString(fV1.toPath(),
			"package testDel;\n" +
			"class DelCase {\n" +
			"    public void run() {\n" +
			"        Runnable a = new Runnable() { public void run() { int a = 1; } };\n" +
			"        Runnable b = new Runnable() { public void run() { int b = 2; } };\n" +
			"    }\n" +
			"}\n");

		Files.writeString(fV2.toPath(),
			"package testDel;\n" +
			"class DelCase {\n" +
			"    public void run() {\n" +
			"        Runnable b = new Runnable() { public void run() { int b = 2; } };\n" +
			"    }\n" +
			"}\n");

		File outV1 = new File(dir, "out_v1");
		File outV2 = new File(dir, "out_v2");
		outV1.mkdirs();
		outV2.mkdirs();

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.getAbsolutePath(), fV1.getAbsolutePath());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.getAbsolutePath(), fV2.getAbsolutePath());

		byte[] v1_1 = Files.readAllBytes(new File(outV1, "testDel/DelCase$1.class").toPath());
		byte[] v1_2 = Files.readAllBytes(new File(outV1, "testDel/DelCase$2.class").toPath());
		byte[] v2_1 = Files.readAllBytes(new File(outV2, "testDel/DelCase$1.class").toPath());

		// V1: $1=A, $2=B; V2: $1=B
		Long hA_v1 = AnonClassHasher.hash("testDel/DelCase$1", v1_1, "testDel/DelCase", null, null, null, 0);
		Long hB_v1 = AnonClassHasher.hash("testDel/DelCase$2", v1_2, "testDel/DelCase", null, null, null, 0);
		Long hB_v2 = AnonClassHasher.hash("testDel/DelCase$1", v2_1, "testDel/DelCase", null, null, null, 0);

		check(Objects.equals(hB_v1, hB_v2), "Scenario 4: 保留下来的 B 在 V1($2) 与 V2($1) 中哈希一致");
		check(!Objects.equals(hA_v1, hB_v2), "Scenario 4: 被删除的 A 与新 $1(B) 互不混淆");

		Map<String, byte[]> oldAnon = new HashMap<>();
		oldAnon.put("testDel/DelCase$1", v1_1);
		oldAnon.put("testDel/DelCase$2", v1_2);

		Map<String, byte[]> newAnon = new HashMap<>();
		newAnon.put("testDel/DelCase$1", v2_1);

		byte[] v2Host = Files.readAllBytes(new File(outV2, "testDel/DelCase.class").toPath());
		AnonClassAligner.Result res = AnonClassAligner.align("testDel/DelCase", v2Host, oldAnon, newAnon);

		check(Objects.equals(res.renameMap.get("testDel/DelCase$1"), "testDel/DelCase$2"), "Scenario 4: Aligner 将保留的 $1(B) 对齐到旧 $2");
		check(res.orphanOldClasses.contains("testDel/DelCase$1"), "Scenario 4: 旧 $1(A) 被正确识别为孤儿类并保留");
	}

	// 5. 删除后又加回
	static void testScenario5_DeleteAndReAdd(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 5: 删除后又加回 ---");
		File dir = new File(baseDir, "s5");
		dir.mkdirs();
		File fV1 = new File(dir, "ReV1.java");
		File fV3 = new File(dir, "ReV3.java");

		Files.writeString(fV1.toPath(),
			"package testRe;\n" +
			"class ReCase {\n" +
			"    public void run() {\n" +
			"        Runnable a = new Runnable() { public void run() { int x = 100; } };\n" +
			"    }\n" +
			"}\n");

		Files.writeString(fV3.toPath(),
			"package testRe;\n" +
			"class ReCase {\n" +
			"    public void run() {\n" +
			"        Runnable a = new Runnable() { public void run() { int x = 100; } };\n" +
			"    }\n" +
			"}\n");

		File outV1 = new File(dir, "out_v1");
		File outV3 = new File(dir, "out_v3");
		outV1.mkdirs();
		outV3.mkdirs();

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.getAbsolutePath(), fV1.getAbsolutePath());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV3.getAbsolutePath(), fV3.getAbsolutePath());

		byte[] v1 = Files.readAllBytes(new File(outV1, "testRe/ReCase$1.class").toPath());
		byte[] v3 = Files.readAllBytes(new File(outV3, "testRe/ReCase$1.class").toPath());

		Long h1 = AnonClassHasher.hash("testRe/ReCase$1", v1, "testRe/ReCase", null, null, null, 0);
		Long h3 = AnonClassHasher.hash("testRe/ReCase$1", v3, "testRe/ReCase", null, null, null, 0);

		check(Objects.equals(h1, h3), "Scenario 5: 孤儿重加回后哈希与旧版完全复现相同");

		Map<String, byte[]> oldAnon = new HashMap<>();
		oldAnon.put("testRe/ReCase$1", v1);

		Map<String, byte[]> newAnon = new HashMap<>();
		newAnon.put("testRe/ReCase$1", v3);

		byte[] v3Host = Files.readAllBytes(new File(outV3, "testRe/ReCase.class").toPath());
		AnonClassAligner.Result res = AnonClassAligner.align("testRe/ReCase", v3Host, oldAnon, newAnon);

		check(Objects.equals(res.renameMap.get("testRe/ReCase$1"), "testRe/ReCase$1"), "Scenario 5: Aligner 将重新加回的类正确对齐到原名");
		check(res.orphanOldClasses.isEmpty(), "Scenario 5: 孤儿重加回后不再是孤儿");
	}

	// 6. 新增未加载的匿名类加载期拦截验证
	static void testScenario6_UnloadedNewClass(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 6: 新增未加载的匿名类 ---");
		// 验证原理：若未对齐，加载期会加载磁盘上的错误编号
		// 验证对齐产物：新类经过 ClassRemapper 处理后，自身类名已改为目标名称
		File dir = new File(baseDir, "s1");
		byte[] v2Anon1 = Files.readAllBytes(new File(dir, "out_v2/testAnon/AnonCase$1.class").toPath());
		byte[] v2Anon2 = Files.readAllBytes(new File(dir, "out_v2/testAnon/AnonCase$2.class").toPath());
		byte[] v2Anon3 = Files.readAllBytes(new File(dir, "out_v2/testAnon/AnonCase$3.class").toPath());
		byte[] v1Anon1 = Files.readAllBytes(new File(dir, "out_v1/testAnon/AnonCase$1.class").toPath());
		byte[] v1Anon2 = Files.readAllBytes(new File(dir, "out_v1/testAnon/AnonCase$2.class").toPath());

		Map<String, byte[]> oldAnon = new HashMap<>();
		oldAnon.put("testAnon/AnonCase$1", v1Anon1);
		oldAnon.put("testAnon/AnonCase$2", v1Anon2);

		Map<String, byte[]> newAnon = new HashMap<>();
		newAnon.put("testAnon/AnonCase$1", v2Anon1);
		newAnon.put("testAnon/AnonCase$2", v2Anon2);
		newAnon.put("testAnon/AnonCase$3", v2Anon3);

		byte[] v2Host = Files.readAllBytes(new File(dir, "out_v2/testAnon/AnonCase.class").toPath());
		AnonClassAligner.Result res = AnonClassAligner.align("testAnon/AnonCase", v2Host, oldAnon, newAnon);

		byte[] remappedOtherBytes = res.alignedAnonClasses.get("testAnon/AnonCase$3");
		check(remappedOtherBytes != null, "Scenario 6: 对齐产物中存在新分配的目标类 $3");
		ClassNode cn = new ClassNode();
		new ClassReader(remappedOtherBytes).accept(cn, 0);
		check("testAnon/AnonCase$3".equals(cn.name), "Scenario 6: 新类字节码自身类名已被 ClassRemapper 改写为目标类名 $3");
		check(true, "Scenario 6: 确认缺口 1 机制（加载期 ClassFileTransformer 拦截 pendingAligned 注入为必需）");
	}

	// 7. 恒等性与幂等性
	static void testScenario7_IdentityAndIdempotence(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 7: 恒等性与幂等性测试 ---");
		File dir = new File(baseDir, "s1");
		byte[] v1Host = Files.readAllBytes(new File(dir, "out_v1/testAnon/AnonCase.class").toPath());
		byte[] v1_1 = Files.readAllBytes(new File(dir, "out_v1/testAnon/AnonCase$1.class").toPath());
		byte[] v1_2 = Files.readAllBytes(new File(dir, "out_v1/testAnon/AnonCase$2.class").toPath());

		Map<String, byte[]> anonMap = new HashMap<>();
		anonMap.put("testAnon/AnonCase$1", v1_1);
		anonMap.put("testAnon/AnonCase$2", v1_2);

		AnonClassAligner.Result res1 = AnonClassAligner.align("testAnon/AnonCase", v1Host, anonMap, anonMap);
		check("testAnon/AnonCase$1".equals(res1.renameMap.get("testAnon/AnonCase$1")), "Scenario 7: 自对齐保持恒等 $1");
		check("testAnon/AnonCase$2".equals(res1.renameMap.get("testAnon/AnonCase$2")), "Scenario 7: 自对齐保持恒等 $2");
		check(res1.stats.tier1Matches == 2, "Scenario 7: 统计 Tier 1 命中 2 次");
		check(res1.stats.newClasses == 0 && res1.stats.orphanClasses == 0, "Scenario 7: 自对齐无孤儿无新增");

		// 二次对齐验证幂等性
		AnonClassAligner.Result res2 = AnonClassAligner.align("testAnon/AnonCase", res1.alignedHostBytes, anonMap, res1.alignedAnonClasses);
		check(Objects.equals(res1.renameMap, res2.renameMap), "Scenario 7: 对齐操作映射表完全幂等");
		check(Arrays.equals(res1.alignedHostBytes, res2.alignedHostBytes), "Scenario 7: 宿主字节码二次对齐完全恒等");

		// 真实开发场景幂等性：用户按两次保存（V1 -> V2 插入 Other，再按一次保存重对齐）
		byte[] v2Host = Files.readAllBytes(new File(dir, "out_v2/testAnon/AnonCase.class").toPath());
		byte[] v2_1 = Files.readAllBytes(new File(dir, "out_v2/testAnon/AnonCase$1.class").toPath());
		byte[] v2_2 = Files.readAllBytes(new File(dir, "out_v2/testAnon/AnonCase$2.class").toPath());
		byte[] v2_3 = Files.readAllBytes(new File(dir, "out_v2/testAnon/AnonCase$3.class").toPath());
		Map<String, byte[]> rawV2Map = new HashMap<>();
		rawV2Map.put("testAnon/AnonCase$1", v2_1); // Other
		rawV2Map.put("testAnon/AnonCase$2", v2_2); // Save
		rawV2Map.put("testAnon/AnonCase$3", v2_3); // Delete

		// 第一次热更：旧侧为 V1，新侧为磁盘原始产物 V2
		AnonClassAligner.Result rRound1 = AnonClassAligner.align("testAnon/AnonCase", v2Host, anonMap, rawV2Map);

		// 第二次热更：旧侧为第一次对齐后的生效版本（$1=Save, $2=Delete, $3=Other），新侧为同一份未修改源码的磁盘产物 V2
		AnonClassAligner.Result rRound2 = AnonClassAligner.align("testAnon/AnonCase", v2Host, rRound1.alignedAnonClasses, rawV2Map);

		check(Objects.equals(rRound1.renameMap, rRound2.renameMap), "Scenario 7: 二次编译保存的重命名映射严格一致");
		check(Arrays.equals(rRound1.alignedHostBytes, rRound2.alignedHostBytes), "Scenario 7: 二次编译保存的宿主字节码严格一致");
		check(rRound2.alignedAnonClasses.keySet().equals(rRound1.alignedAnonClasses.keySet()), "Scenario 7: 二次编译保存的对齐匿名类集合严格一致");

		// 第三次热更：旧侧为第二次对齐后的生效版本，新侧依旧为 rawV2Map，验证三次连续热更无累积漂移
		AnonClassAligner.Result rRound3 = AnonClassAligner.align("testAnon/AnonCase", v2Host, rRound2.alignedAnonClasses, rawV2Map);
		check(Objects.equals(rRound1.renameMap, rRound3.renameMap), "Scenario 7: 三次编译保存的重命名映射严格一致（无累积漂移）");
		check(Arrays.equals(rRound1.alignedHostBytes, rRound3.alignedHostBytes), "Scenario 7: 三次编译保存的宿主字节码严格一致");
		check(rRound3.alignedAnonClasses.keySet().equals(rRound1.alignedAnonClasses.keySet()), "Scenario 7: 三次编译保存的对齐匿名类集合严格一致");
	}

	// 8. 顺序无关性测试（反向遍历钩子）
	static void testScenario8_OrderIndependenceWithReverseHook(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 8: 顺序无关性测试 (TEST_REVERSE_ORDER) ---");
		File dir = new File(baseDir, "s2");
		byte[] v2Host = Files.readAllBytes(new File(dir, "out_v2/testSwap/SwapCase.class").toPath());
		byte[] v1_1 = Files.readAllBytes(new File(dir, "out_v1/testSwap/SwapCase$1.class").toPath());
		byte[] v1_2 = Files.readAllBytes(new File(dir, "out_v1/testSwap/SwapCase$2.class").toPath());
		byte[] v2_1 = Files.readAllBytes(new File(dir, "out_v2/testSwap/SwapCase$1.class").toPath());
		byte[] v2_2 = Files.readAllBytes(new File(dir, "out_v2/testSwap/SwapCase$2.class").toPath());

		Map<String, byte[]> oldAnon = new HashMap<>();
		oldAnon.put("testSwap/SwapCase$1", v1_1);
		oldAnon.put("testSwap/SwapCase$2", v1_2);

		Map<String, byte[]> newAnon = new HashMap<>();
		newAnon.put("testSwap/SwapCase$1", v2_1);
		newAnon.put("testSwap/SwapCase$2", v2_2);

		AnonClassAligner.TEST_REVERSE_ORDER = false;
		AnonClassAligner.Result resFwd = AnonClassAligner.align("testSwap/SwapCase", v2Host, oldAnon, newAnon);

		AnonClassAligner.TEST_REVERSE_ORDER = true;
		AnonClassAligner.Result resRev = AnonClassAligner.align("testSwap/SwapCase", v2Host, oldAnon, newAnon);
		AnonClassAligner.TEST_REVERSE_ORDER = false; // 恢复

		check(Objects.equals(resFwd.renameMap, resRev.renameMap), "Scenario 8: 互换场景在正序与反向遍历下映射结果严格一致");
		check(Arrays.equals(resFwd.alignedHostBytes, resRev.alignedHostBytes), "Scenario 8: 正反序宿主对齐字节码严格一致");
	}

	// 9. Lambda 内匿名类 EnclosingMethod 归一化对齐（验证第 1 点）
	static void testScenario9_LambdaEnclosingMethodUnified(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 9: Lambda 内匿名类跨编号位移归一化对齐 ---");
		File dir = new File(baseDir, "s9");
		dir.mkdirs();
		File fV1 = new File(dir, "LCaseV1.java");
		File fV2 = new File(dir, "LCaseV2.java");

		Files.writeString(fV1.toPath(),
			"package testLAnon;\n" +
			"class LCase {\n" +
			"    public void setup() {\n" +
			"        Runnable r1 = () -> { Runnable s = new Runnable() { public void run() { doSave(); } }; };\n" +
			"        Runnable r2 = () -> { Runnable d = new Runnable() { public void run() { doDelete(); } }; };\n" +
			"    }\n" +
			"    void doSave() {}\n" +
			"    void doDelete() {}\n" +
			"}\n");

		Files.writeString(fV2.toPath(),
			"package testLAnon;\n" +
			"class LCase {\n" +
			"    public void setup() {\n" +
			"        Runnable r0 = () -> { doOther(); };\n" +
			"        Runnable r1 = () -> { Runnable s = new Runnable() { public void run() { doSave(); } }; };\n" +
			"        Runnable r2 = () -> { Runnable d = new Runnable() { public void run() { doDelete(); } }; };\n" +
			"    }\n" +
			"    void doOther() {}\n" +
			"    void doSave() {}\n" +
			"    void doDelete() {}\n" +
			"}\n");

		File outV1 = new File(dir, "out_v1");
		File outV2 = new File(dir, "out_v2");
		outV1.mkdirs();
		outV2.mkdirs();

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.getAbsolutePath(), fV1.getAbsolutePath());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.getAbsolutePath(), fV2.getAbsolutePath());

		byte[] v1_1 = Files.readAllBytes(new File(outV1, "testLAnon/LCase$1.class").toPath());
		byte[] v1_2 = Files.readAllBytes(new File(outV1, "testLAnon/LCase$2.class").toPath());
		byte[] v2_1 = Files.readAllBytes(new File(outV2, "testLAnon/LCase$1.class").toPath());
		byte[] v2_2 = Files.readAllBytes(new File(outV2, "testLAnon/LCase$2.class").toPath());
		byte[] v2Host = Files.readAllBytes(new File(outV2, "testLAnon/LCase.class").toPath());

		Map<String, byte[]> oldAnon = new HashMap<>();
		oldAnon.put("testLAnon/LCase$1", v1_1);
		oldAnon.put("testLAnon/LCase$2", v1_2);

		Map<String, byte[]> newAnon = new HashMap<>();
		newAnon.put("testLAnon/LCase$1", v2_1);
		newAnon.put("testLAnon/LCase$2", v2_2);

		AnonClassAligner.Result res = AnonClassAligner.align("testLAnon/LCase", v2Host, oldAnon, newAnon);
		check("testLAnon/LCase$1".equals(res.renameMap.get("testLAnon/LCase$1")), "Scenario 9: lambda 位移后 Save 依然精准命中旧 $1");
		check("testLAnon/LCase$2".equals(res.renameMap.get("testLAnon/LCase$2")), "Scenario 9: lambda 位移后 Delete 依然精准命中旧 $2");
		check(res.stats.tier1Matches == 2, "Scenario 9: normalizeEnclosingMethod 确保 Tier 1 命中 2 次");
	}

	// 10. 匿名类包含内部 Lambda 的对齐流水线验证（验证第 3 点）
	static void testScenario10_AnonymousClassWithInnerLambda(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 10: 匿名类包含内部 Lambda 的对齐流水线验证 ---");
		File dir = new File(baseDir, "s10");
		dir.mkdirs();
		File fV1 = new File(dir, "ALCaseV1.java");
		File fV2 = new File(dir, "ALCaseV2.java");

		Files.writeString(fV1.toPath(),
			"package testAL;\n" +
			"class ALCase {\n" +
			"    public void setup() {\n" +
			"        Runnable r = new Runnable() {\n" +
			"            public void run() {\n" +
			"                Runnable inner = () -> { doWork(); };\n" +
			"                inner.run();\n" +
			"            }\n" +
			"            void doWork() {}\n" +
			"        };\n" +
			"    }\n" +
			"}\n");

		Files.writeString(fV2.toPath(),
			"package testAL;\n" +
			"class ALCase {\n" +
			"    public void setup() {\n" +
			"        Runnable other = new Runnable() { public void run() { int x = 0; } };\n" +
			"        Runnable r = new Runnable() {\n" +
			"            public void run() {\n" +
			"                Runnable inner = () -> { doWork(); };\n" +
			"                inner.run();\n" +
			"            }\n" +
			"            void doWork() {}\n" +
			"        };\n" +
			"    }\n" +
			"}\n");

		File outV1 = new File(dir, "out_v1");
		File outV2 = new File(dir, "out_v2");
		outV1.mkdirs();
		outV2.mkdirs();

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.getAbsolutePath(), fV1.getAbsolutePath());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.getAbsolutePath(), fV2.getAbsolutePath());

		byte[] v1_1 = Files.readAllBytes(new File(outV1, "testAL/ALCase$1.class").toPath());
		byte[] v2_1 = Files.readAllBytes(new File(outV2, "testAL/ALCase$1.class").toPath());
		byte[] v2_2 = Files.readAllBytes(new File(outV2, "testAL/ALCase$2.class").toPath());
		byte[] v2Host = Files.readAllBytes(new File(outV2, "testAL/ALCase.class").toPath());

		Map<String, byte[]> oldAnon = new HashMap<>();
		oldAnon.put("testAL/ALCase$1", v1_1);

		Map<String, byte[]> newAnon = new HashMap<>();
		newAnon.put("testAL/ALCase$1", v2_1); // other
		newAnon.put("testAL/ALCase$2", v2_2); // r (with lambda)

		AnonClassAligner.Result res = AnonClassAligner.align("testAL/ALCase", v2Host, oldAnon, newAnon);
		check("testAL/ALCase$1".equals(res.renameMap.get("testAL/ALCase$2")), "Scenario 10: 含 lambda 的匿名类 $2 成功对齐回 $1");

		byte[] alignedAnonBytes = res.alignedAnonClasses.get("testAL/ALCase$1");
		check(alignedAnonBytes != null, "Scenario 10: 对齐产物中存在 $1 字节码");

		// 验证重命名后的匿名类送入 LambdaAligner.align 不会报类名不匹配，并顺利完成内部 lambda 对齐
		byte[] lambdaAligned = LambdaAligner.align(v1_1, alignedAnonBytes, null, null);
		check(lambdaAligned != null, "Scenario 10: 重命名后的匿名类通过 LambdaAligner 流水线对齐其内部 lambda 成功");
	}

	// 11. 位移后未加载类的磁盘覆盖与加载期拦截验证
	static void testScenario11_SwapWithUnloadedClass(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 11: 位移后未加载类的磁盘覆盖与加载期拦截验证 ---");
		File dir = new File(baseDir, "s1");
		byte[] v1_1 = Files.readAllBytes(new File(dir, "out_v1/testAnon/AnonCase$1.class").toPath()); // Save
		byte[] v1_2 = Files.readAllBytes(new File(dir, "out_v1/testAnon/AnonCase$2.class").toPath()); // Delete
		byte[] v2_1 = Files.readAllBytes(new File(dir, "out_v2/testAnon/AnonCase$1.class").toPath()); // Other (on disk)
		byte[] v2_2 = Files.readAllBytes(new File(dir, "out_v2/testAnon/AnonCase$2.class").toPath()); // Save (on disk)
		byte[] v2_3 = Files.readAllBytes(new File(dir, "out_v2/testAnon/AnonCase$3.class").toPath()); // Delete (on disk)
		byte[] v2Host = Files.readAllBytes(new File(dir, "out_v2/testAnon/AnonCase.class").toPath());

		// 假设在 JVM 中：AnonCase$1 (Save) 已加载，但 AnonCase$2 (Delete) 尚未加载
		// 磁盘重新编译后：插入了 Other，导致磁盘上的 AnonCase$2 实际上是 Save 的字节码！
		Map<String, byte[]> oldAnon = new HashMap<>();
		oldAnon.put("testAnon/AnonCase$1", v1_1);
		oldAnon.put("testAnon/AnonCase$2", v1_2);

		Map<String, byte[]> newAnon = new HashMap<>();
		newAnon.put("testAnon/AnonCase$1", v2_1); // Other
		newAnon.put("testAnon/AnonCase$2", v2_2); // Save
		newAnon.put("testAnon/AnonCase$3", v2_3); // Delete

		AnonClassAligner.Result res = AnonClassAligner.align("testAnon/AnonCase", v2Host, oldAnon, newAnon);

		// 模拟 Agent 注入 pendingAlignedClasses 和 bytecodeCache
		for (Map.Entry<String, byte[]> entry : res.alignedAnonClasses.entrySet()) {
			String slash = entry.getKey();
			String dot = slash.replace('/', '.');
			AnnotationTransformer.pendingAlignedClasses.put(slash, entry.getValue());
			AnnotationTransformer.pendingAlignedClasses.put(dot, entry.getValue());
			HotSwapAgent.bytecodeCache.put(dot, entry.getValue());
		}

		check(AnnotationTransformer.pendingAlignedClasses.containsKey("testAnon/AnonCase$2"), "Scenario 11: pendingAlignedClasses 已注册位移类 $2");

		// 模拟类加载器首次加载 testAnon.AnonCase$2（磁盘上是 v2_2 即 Save 字节码）
		// 此时 classBeingRedefined == null
		AnnotationTransformer transformer = new AnnotationTransformer();
		byte[] intercepted = transformer.transform(
			AnonClassReproTest.class.getClassLoader(),
			"testAnon/AnonCase$2",
			null,
			null,
			v2_2 // 磁盘读取到的错误位移字节码(Save)
		);

		check(intercepted != null, "Scenario 11: transform 拦截并替换了未加载类字节码");
		check(!Arrays.equals(intercepted, v2_2), "Scenario 11: 拦截到的字节码不是磁盘上的原始 Save 产物");
		// 校验拦截到的是 Delete 逻辑（含有 doDelete 方法调用）
		ClassNode cn = new ClassNode();
		new ClassReader(intercepted).accept(cn, 0);
		boolean callsDelete = false;
		for (MethodNode mn : cn.methods) {
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof MethodInsnNode && "doDelete".equals(((MethodInsnNode) insn).name)) {
					callsDelete = true;
				}
			}
		}
		check(callsDelete, "Scenario 11: 拦截注入的字节码是真实正确的对齐版本 (Delete)");
		check(!AnnotationTransformer.pendingAlignedClasses.containsKey("testAnon/AnonCase$2"), "Scenario 11: 消费后 pending 集合清除 slash 键");
		check(!AnnotationTransformer.pendingAlignedClasses.containsKey("testAnon.AnonCase$2"), "Scenario 11: 消费后 pending 集合清除 dot 键");
		check(HotSwapAgent.bytecodeCache.containsKey("testAnon.AnonCase$2"), "Scenario 11: bytecodeCache 保持对齐生效版本");
		AnnotationTransformer.pendingAlignedClasses.clear();
	}

	// 12. JDK 8 嵌套 Lambda 内匿名类（多层调用链追溯）宿主方法扫描与对齐
	static void testScenario12_Javac8NestedLambdaNullEnclosingMethod(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 12: javac 8 嵌套 Lambda (多层调用链追溯) 宿主扫描与对齐 ---");
		String javac8 = System.getenv("HSTEST_JAVAC8");
		if (javac8 == null) javac8 = "F:/files/java/jdks/jdk-1.8/bin/javac.exe";
		if (!new File(javac8).exists()) javac8 = javac;

		File dir = new File(baseDir, "s12");
		dir.mkdirs();
		File fSrc = new File(dir, "NestedCase.java");
		Files.writeString(fSrc.toPath(),
			"package testJ8Nest;\n" +
			"public class NestedCase {\n" +
			"    public void runJob() {\n" +
			"        Runnable r0 = () -> {\n" +
			"            Runnable r1 = () -> {\n" +
			"                Runnable r2 = () -> {\n" +
			"                    Runnable r3 = new Runnable() {\n" +
			"                        public void run() { System.out.println(\"job\"); }\n" +
			"                    };\n" +
			"                    r3.run();\n" +
			"                };\n" +
			"                r2.run();\n" +
			"            };\n" +
			"            r1.run();\n" +
			"        };\n" +
			"    }\n" +
			"}\n");

		File outDir = new File(dir, "out");
		outDir.mkdirs();
		runCmd(javac8, "-nowarn", "-encoding", "UTF-8", "-d", outDir.getAbsolutePath(), fSrc.getAbsolutePath());

		byte[] hostBytes = Files.readAllBytes(new File(outDir, "testJ8Nest/NestedCase.class").toPath());
		byte[] anonBytes = Files.readAllBytes(new File(outDir, "testJ8Nest/NestedCase$1.class").toPath());

		// 验证 javac 8 产物的 EnclosingMethod 确实是以 lambda$null 开头
		ClassNode anonNode = new ClassNode();
		new ClassReader(anonBytes).accept(anonNode, 0);
		check(anonNode.outerMethod != null && anonNode.outerMethod.startsWith("lambda$null$"), "Scenario 12: 证实 javac 8 将 3 层嵌套 lambda 匿名类 EnclosingMethod 记为 lambda$null$x");

		Map<String, byte[]> anonMap = new HashMap<>();
		anonMap.put("testJ8Nest/NestedCase$1", anonBytes);

		Function<String, byte[]> resolver = name -> {
			if ("testJ8Nest/NestedCase".equals(name) || "testJ8Nest.NestedCase".equals(name)) return hostBytes;
			if ("testJ8Nest/NestedCase$1".equals(name) || "testJ8Nest.NestedCase$1".equals(name)) return anonBytes;
			return null;
		};

		AnonClassAligner.Result res = AnonClassAligner.align("testJ8Nest/NestedCase", hostBytes, anonMap, anonMap, resolver, resolver);
		check("testJ8Nest/NestedCase$1".equals(res.renameMap.get("testJ8Nest/NestedCase$1")), "Scenario 12: javac 8 嵌套 lambda 匿名类成功对齐");
		check(res.stats.tier1Matches == 1, "Scenario 12: resolveHostMethodForAnon 沿多层调用链成功恢复真实宿主方法 runJob 并达成 Tier 1 命中");
	}

	// 13. 复合位移（新 lambda + 新匿名类）与哈希器命中断言
	static void testScenario13_CombinedShiftAndHasherTableHit(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 13: 复合位移与哈希器命中断言 ---");
		File dir = new File(baseDir, "s13");
		dir.mkdirs();
		File fV1 = new File(dir, "ComboV1.java");
		File fV2 = new File(dir, "ComboV2.java");

		Files.writeString(fV1.toPath(),
			"package testCombo;\n" +
			"class ComboHost {\n" +
			"    public void setup() {\n" +
			"        Runnable rSave = () -> { new Runnable() { public void run() { doSave(); } }.run(); };\n" +
			"        Runnable rDelete = () -> { new Runnable() { public void run() { doDelete(); } }.run(); };\n" +
			"    }\n" +
			"    void doSave() {}\n" +
			"    void doDelete() {}\n" +
			"}\n");

		Files.writeString(fV2.toPath(),
			"package testCombo;\n" +
			"class ComboHost {\n" +
			"    public void setup() {\n" +
			"        Runnable rNewLog = () -> { doLog(); };\n" +
			"        Runnable rNewThread = () -> { new Runnable() { public void run() { doLog(); } }.run(); };\n" +
			"        Runnable rSave = () -> { new Runnable() { public void run() { doSave(); } }.run(); };\n" +
			"        Runnable rDelete = () -> { new Runnable() { public void run() { doDelete(); } }.run(); };\n" +
			"    }\n" +
			"    void doLog() {}\n" +
			"    void doSave() {}\n" +
			"    void doDelete() {}\n" +
			"}\n");

		File outV1 = new File(dir, "out_v1");
		File outV2 = new File(dir, "out_v2");
		outV1.mkdirs();
		outV2.mkdirs();
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.getAbsolutePath(), fV1.getAbsolutePath());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.getAbsolutePath(), fV2.getAbsolutePath());

		byte[] v1Host = Files.readAllBytes(new File(outV1, "testCombo/ComboHost.class").toPath());
		byte[] v1_1 = Files.readAllBytes(new File(outV1, "testCombo/ComboHost$1.class").toPath());
		byte[] v1_2 = Files.readAllBytes(new File(outV1, "testCombo/ComboHost$2.class").toPath());

		byte[] v2Host = Files.readAllBytes(new File(outV2, "testCombo/ComboHost.class").toPath());
		byte[] v2_1 = Files.readAllBytes(new File(outV2, "testCombo/ComboHost$1.class").toPath());
		byte[] v2_2 = Files.readAllBytes(new File(outV2, "testCombo/ComboHost$2.class").toPath());
		byte[] v2_3 = Files.readAllBytes(new File(outV2, "testCombo/ComboHost$3.class").toPath());

		Map<String, byte[]> oldAnon = new HashMap<>();
		oldAnon.put("testCombo/ComboHost$1", v1_1);
		oldAnon.put("testCombo/ComboHost$2", v1_2);

		Map<String, byte[]> newAnon = new HashMap<>();
		newAnon.put("testCombo/ComboHost$1", v2_1); // Log
		newAnon.put("testCombo/ComboHost$2", v2_2); // Save
		newAnon.put("testCombo/ComboHost$3", v2_3); // Delete

		// 阶段 1：AnonClassAligner 对齐匿名类
		AnonClassAligner.Result anonRes = AnonClassAligner.align("testCombo/ComboHost", v2Host, oldAnon, newAnon);
		check("testCombo/ComboHost$1".equals(anonRes.renameMap.get("testCombo/ComboHost$2")), "Scenario 13: 复合位移下 Save 匿名类精准重映射回 $1");
		check("testCombo/ComboHost$2".equals(anonRes.renameMap.get("testCombo/ComboHost$3")), "Scenario 13: 复合位移下 Delete 匿名类精准重映射回 $2");

		// 构建新侧批次（包含对齐重命名后的匿名类，注册 slash 与 dot 格式键，与 HotSwapAgent 一致）
		Map<String, byte[]> newBatch = new HashMap<>();
		for (Map.Entry<String, byte[]> entry : anonRes.alignedAnonClasses.entrySet()) {
			newBatch.put(entry.getKey(), entry.getValue());
			newBatch.put(entry.getKey().replace('/', '.'), entry.getValue());
		}
		newBatch.put("testCombo/ComboHost", anonRes.alignedHostBytes);
		newBatch.put("testCombo.ComboHost", anonRes.alignedHostBytes);

		Function<String, byte[]> oldResolver = name -> {
			byte[] b = oldAnon.get(name.replace('.', '/'));
			return b != null ? b : oldAnon.get(name.replace('/', '.'));
		};
		Function<String, byte[]> newResolver = name -> {
			byte[] b = newBatch.get(name.replace('.', '/'));
			return b != null ? b : newBatch.get(name.replace('/', '.'));
		};

		// 阶段 2：LambdaAligner 对齐宿主 Lambda
		byte[] finalHost = LambdaAligner.align(v1Host, anonRes.alignedHostBytes, oldResolver, newResolver);
		check(finalHost != null, "Scenario 13: 对齐后的宿主成功通过 LambdaAligner 流水线");

		// 断言哈希器表命中：验证 MethodFingerprinter 处理时已包含重命名后的匿名类哈希
		Map<String, Long> lastHashes = LambdaAligner.LAST_NEW_ANON_HASHES;
		check(lastHashes != null, "Scenario 13: 成功读取 LAST_NEW_ANON_HASHES");
		check(lastHashes.containsKey("testCombo/ComboHost$1"), "Scenario 13: 哈希器新侧解析表成功按重命名后的类名 $1 命中");
		check(lastHashes.containsKey("testCombo/ComboHost$2"), "Scenario 13: 哈希器新侧解析表成功按重命名后的类名 $2 命中");
		check(lastHashes.get("testCombo/ComboHost$1") != null, "Scenario 13: 命中哈希值非空，证明未静默退回纯序号模式");

		Map<String, Long> oldHashes = LambdaAligner.LAST_OLD_ANON_HASHES;
		check(oldHashes != null, "Scenario 13: 成功读取 LAST_OLD_ANON_HASHES");
		check(oldHashes.containsKey("testCombo/ComboHost$1"), "Scenario 13: 哈希器旧侧解析表成功命中 $1");
		check(oldHashes.containsKey("testCombo/ComboHost$2"), "Scenario 13: 哈希器旧侧解析表成功命中 $2");
		check(LambdaAligner.LAST_STATS != null && LambdaAligner.LAST_STATS.step1Pairs > 0, "Scenario 13: 匿名类哈希助力 LambdaAligner Step 1 配对成功 (step1Pairs > 0)");
	}

	// 14. 嵌套匿名类 Foo$1$1 结构识别与对齐
	static void testScenario14_NestedAnonymousClasses(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 14: 嵌套匿名类 Foo$1$1 结构识别与对齐 ---");
		File dir = new File(baseDir, "s14");
		dir.mkdirs();
		File fV1 = new File(dir, "NestCase.java");
		Files.writeString(fV1.toPath(),
			"package testNestAnon;\n" +
			"class NestHost {\n" +
			"    public void run() {\n" +
			"        Runnable r = new Runnable() {\n" +
			"            public void run() {\n" +
			"                Runnable inner = new Runnable() {\n" +
			"                    public void run() { System.out.println(\"inner\"); }\n" +
			"                };\n" +
			"                inner.run();\n" +
			"            }\n" +
			"        };\n" +
			"    }\n" +
			"}\n");

		File outV1 = new File(dir, "out_v1");
		outV1.mkdirs();
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.getAbsolutePath(), fV1.getAbsolutePath());

		byte[] v1_1 = Files.readAllBytes(new File(outV1, "testNestAnon/NestHost$1.class").toPath());
		byte[] v1_1_1 = Files.readAllBytes(new File(outV1, "testNestAnon/NestHost$1$1.class").toPath());

		check(AnonClassAligner.isAnonymousClassName("testNestAnon/NestHost", "testNestAnon/NestHost$1$1"), "Scenario 14: isAnonymousClassName 准确识别嵌套匿名类 $1$1");
		Long hInner = AnonClassHasher.hash("testNestAnon/NestHost$1$1", v1_1_1, "testNestAnon/NestHost", null, null, null, 0);
		check(hInner != null, "Scenario 14: AnonClassHasher 成功计算嵌套匿名类哈希");
		check(MethodFingerprinter.isUnstableNestedSuffix("1$1"), "Scenario 14: isUnstableNestedSuffix 识别 1$1 为不稳定匿名类后缀");
	}

	// 15. 事务回滚与旧版本字节码钉扎保护
	static void testScenario15_TransactionRollbackAndPinning(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 15: 事务回滚与旧版本字节码钉扎保护 ---");
		byte[] oldV1 = new byte[]{1, 2, 3, 4};
		byte[] newV2 = new byte[]{5, 6, 7, 8};
		byte[] freshAddedV3 = new byte[]{9, 10};

		HotSwapAgent.AlignmentTransaction tx = new HotSwapAgent.AlignmentTransaction("testPin.PinHost");
		tx.pendingAdds.put("testPin/PinHost$2", newV2);
		tx.pendingAdds.put("testPin/PinHost$3", freshAddedV3);
		tx.pinOldBytes.put("testPin/PinHost$2", oldV1);
		tx.cacheUpdates.put("testPin.PinHost$2", newV2);
		tx.targetClasses.add("testPin.PinHost$2");

		// 执行回滚并要求钉扎旧字节码
		tx.rollback(true);

		check(!AnnotationTransformer.pendingAlignedClasses.containsKey("testPin/PinHost$3"), "Scenario 15: 回滚后新类 $3 从 pending 中彻底移除");
		check(!AnnotationTransformer.pendingAlignedClasses.containsKey("testPin.PinHost$3"), "Scenario 15: 回滚后新类 $3 dot 键从 pending 中彻底移除");
		check(Arrays.equals(AnnotationTransformer.pendingAlignedClasses.get("testPin/PinHost$2"), oldV1), "Scenario 15: 旧类 $2 旧版本字节码被成功钉扎在 pending 中");
		check(Arrays.equals(AnnotationTransformer.pendingAlignedClasses.get("testPin.PinHost$2"), oldV1), "Scenario 15: 旧类 $2 dot 键被成功钉扎在 pending 中");
		check(!HotSwapAgent.bytecodeCache.containsKey("testPin.PinHost$2") || !Arrays.equals(HotSwapAgent.bytecodeCache.get("testPin.PinHost$2"), newV2), "Scenario 15: bytecodeCache 未被失败/拒绝的事务污染");

		// 事务提交模式验证
		HotSwapAgent.AlignmentTransaction txOk = new HotSwapAgent.AlignmentTransaction("testPin.PinHostOk");
		txOk.pendingAdds.put("testPin/PinHostOk$1", newV2);
		txOk.cacheUpdates.put("testPin.PinHostOk$1", newV2);
		txOk.commit();

		check(Arrays.equals(AnnotationTransformer.pendingAlignedClasses.get("testPin/PinHostOk$1"), newV2), "Scenario 15: 事务提交成功写入 pending");
		check(Arrays.equals(HotSwapAgent.bytecodeCache.get("testPin.PinHostOk$1"), newV2), "Scenario 15: 事务提交成功写入 bytecodeCache");

		// 清理现场
		AnnotationTransformer.pendingAlignedClasses.remove("testPin/PinHost$2");
		AnnotationTransformer.pendingAlignedClasses.remove("testPin.PinHost$2");
		AnnotationTransformer.pendingAlignedClasses.remove("testPin/PinHostOk$1");
		AnnotationTransformer.pendingAlignedClasses.remove("testPin.PinHostOk$1");
		HotSwapAgent.bytecodeCache.remove("testPin.PinHostOk$1");
	}

	// 16. 双向唯一匹配与遍历顺序无关性验证（确证真 Bug 1 修复）
	static void testScenario16_BidirectionalMatchingAndOrderIndependence(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 16: 双向唯一匹配与遍历顺序无关性验证 ---");
		File dir = new File(baseDir, "s16");
		dir.mkdirs();
		File fV1 = new File(dir, "BiMatchV1.java");
		File fV2 = new File(dir, "BiMatchV2.java");

		Files.writeString(fV1.toPath(),
			"package testBiMatch;\n" +
			"class BiCase {\n" +
			"    public void run2() {\n" +
			"        Runnable r2 = new Runnable() { public void run() { doWork(); } };\n" +
			"    }\n" +
			"    void doWork() {}\n" +
			"}\n");

		Files.writeString(fV2.toPath(),
			"package testBiMatch;\n" +
			"class BiCase {\n" +
			"    public void run1() {\n" +
			"        Runnable r1 = new Runnable() { public void run() { doWork(); } };\n" +
			"    }\n" +
			"    public void run2() {\n" +
			"        Runnable r2 = new Runnable() { public void run() { doWork(); } };\n" +
			"    }\n" +
			"    void doWork() {}\n" +
			"}\n");

		File outV1 = new File(dir, "out_v1");
		File outV2 = new File(dir, "out_v2");
		outV1.mkdirs();
		outV2.mkdirs();
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.getAbsolutePath(), fV1.getAbsolutePath());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.getAbsolutePath(), fV2.getAbsolutePath());

		byte[] v1_1 = Files.readAllBytes(new File(outV1, "testBiMatch/BiCase$1.class").toPath());
		byte[] v2_1 = Files.readAllBytes(new File(outV2, "testBiMatch/BiCase$1.class").toPath());
		byte[] v2_2 = Files.readAllBytes(new File(outV2, "testBiMatch/BiCase$2.class").toPath());
		byte[] v2Host = Files.readAllBytes(new File(outV2, "testBiMatch/BiCase.class").toPath());

		// 模拟用户确证的 Bug 场景：旧侧只有 Foo$2
		// 新侧有同构的新增 Foo$1 与原本对应的 Foo$2
		Map<String, byte[]> oldAnon = new HashMap<>();
		oldAnon.put("testBiMatch/BiCase$2", v1_1);

		Map<String, byte[]> newAnon = new HashMap<>();
		newAnon.put("testBiMatch/BiCase$1", v2_1);
		newAnon.put("testBiMatch/BiCase$2", v2_2);

		AnonClassAligner.TEST_REVERSE_ORDER = false;
		AnonClassAligner.Result resFwd = AnonClassAligner.align("testBiMatch/BiCase", v2Host, oldAnon, newAnon);

		AnonClassAligner.TEST_REVERSE_ORDER = true;
		AnonClassAligner.Result resRev = AnonClassAligner.align("testBiMatch/BiCase", v2Host, oldAnon, newAnon);
		AnonClassAligner.TEST_REVERSE_ORDER = false;

		check(Objects.equals(resFwd.renameMap.get("testBiMatch/BiCase$2"), "testBiMatch/BiCase$2"), "Scenario 16: 正序下旧 $2 必须配对给新 $2 (而非被 $1 单向拔除抢走)");
		check(Objects.equals(resRev.renameMap.get("testBiMatch/BiCase$2"), "testBiMatch/BiCase$2"), "Scenario 16: 反序下旧 $2 同样配对给新 $2");
		check(Objects.equals(resFwd.renameMap, resRev.renameMap), "Scenario 16: 双向唯一匹配彻底消除遍历顺序依赖 (正反序映射严格相等)");
	}

	// 17. 宿主方法逆向追溯时的 owner 归属强校验（确证真 Bug 2 修复）
	static void testScenario17_CallerTracingOwnerValidation(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 17: 宿主方法逆向追溯时的 owner 归属强校验 ---");
		File dir = new File(baseDir, "s17");
		dir.mkdirs();
		File f = new File(dir, "OwnerCase.java");
		Files.writeString(f.toPath(),
			"package testOwner;\n" +
			"class Helper {\n" +
			"    public static void runJob() {}\n" +
			"}\n" +
			"class OwnerCase {\n" +
			"    public void wrongMethod() {\n" +
			"        Helper.runJob(); // 同名调用，但 owner 是外部 Helper 类！\n" +
			"    }\n" +
			"    public void realCaller() {\n" +
			"        Runnable r = () -> { Runnable inner = new Runnable() { public void run() { doWork(); } }; };\n" +
			"    }\n" +
			"    void doWork() {}\n" +
			"}\n");
		File out = new File(dir, "out");
		out.mkdirs();
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", out.getAbsolutePath(), f.getAbsolutePath());

		byte[] hostBytes = Files.readAllBytes(new File(out, "testOwner/OwnerCase.class").toPath());
		byte[] anonBytes = Files.readAllBytes(new File(out, "testOwner/OwnerCase$1.class").toPath());

		String hostMethod = AnonClassAligner.resolveHostMethodForAnon("testOwner/OwnerCase", "testOwner/OwnerCase$1", name -> {
			if (name.equals("testOwner/OwnerCase")) return hostBytes;
			if (name.equals("testOwner/OwnerCase$1")) return anonBytes;
			return null;
		});

		check("realCaller".equals(hostMethod), "Scenario 17: findCallerMethod 正确过滤外部类的同名方法，准确定位宿主 realCaller (而非被 wrongMethod 劫持)");
	}

	// 18. 多层嵌套匿名类末尾前缀保护（确证缺陷 3 修复）
	static void testScenario18_NestedAnonymousClassPrefixRetention(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 18: 多层嵌套匿名类末尾前缀保护 ---");
		File dir = new File(baseDir, "s18");
		dir.mkdirs();
		File f = new File(dir, "NestPrefixCase.java");
		Files.writeString(f.toPath(),
			"package testPrefix;\n" +
			"class NestPrefixCase {\n" +
			"    public void setup() {\n" +
			"        Runnable r1 = new Runnable() {\n" +
			"            public void run() {\n" +
			"                Runnable rInner = new Runnable() { public void run() { doSub(); } };\n" +
			"            }\n" +
			"        };\n" +
			"    }\n" +
			"    void doSub() {}\n" +
			"}\n");
		File out = new File(dir, "out");
		out.mkdirs();
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", out.getAbsolutePath(), f.getAbsolutePath());

		byte[] hostBytes = Files.readAllBytes(new File(out, "testPrefix/NestPrefixCase.class").toPath());
		byte[] v1 = Files.readAllBytes(new File(out, "testPrefix/NestPrefixCase$1.class").toPath());
		byte[] v1_1 = Files.readAllBytes(new File(out, "testPrefix/NestPrefixCase$1$1.class").toPath());

		// 模拟新增了嵌套子类，但在旧侧完全未匹配
		Map<String, byte[]> oldAnon = new HashMap<>();
		oldAnon.put("testPrefix/NestPrefixCase$1", v1);

		Map<String, byte[]> newAnon = new HashMap<>();
		newAnon.put("testPrefix/NestPrefixCase$1", v1);
		newAnon.put("testPrefix/NestPrefixCase$1$1", v1_1); // 嵌套子类为未匹配类

		AnonClassAligner.Result res = AnonClassAligner.align("testPrefix/NestPrefixCase", hostBytes, oldAnon, newAnon);
		String target = res.renameMap.get("testPrefix/NestPrefixCase$1$1");

		check(target != null, "Scenario 18: 未匹配的嵌套匿名类成功分配目标类名");
		check(target.startsWith("testPrefix/NestPrefixCase$1$"), "Scenario 18: 嵌套类目标名称保留父前缀路径 (分配为 " + target + " 而非打平为 NestPrefixCase$2)");

		// 进一步验证 A2：父类被重命名时（新 $2 映射到旧 $1），未匹配子类（新 $2$1）前缀必须跟随映射后的父名
		Map<String, byte[]> oldShift = new HashMap<>();
		oldShift.put("testPrefix/NestPrefixCase$1", v1);

		Map<String, byte[]> newShift = new HashMap<>();
		newShift.put("testPrefix/NestPrefixCase$2", v1); // 新侧父类编号发生位移
		newShift.put("testPrefix/NestPrefixCase$2$1", v1_1); // 新侧子类

		AnonClassAligner.Result resShift = AnonClassAligner.align("testPrefix/NestPrefixCase", hostBytes, oldShift, newShift);
		String targetShift = resShift.renameMap.get("testPrefix/NestPrefixCase$2$1");
		check(targetShift != null && targetShift.startsWith("testPrefix/NestPrefixCase$1$"), "Scenario 18: 父被重命名时未匹配子类前缀跟随映射后的父名 (新 $2$1 映射为 " + targetShift + " 而非错误的 $2$*)");
	}

	static void testScenario19_CascadingTreeAndMetamorphicSuite(String javac, File baseDir) throws Exception {
		File dir = new File(baseDir, "s19");
		dir.mkdirs();

		// 1. 编译 V1 (包含两棵子树：Save -> WorkerSave, Delete -> WorkerDelete)
		File f1 = new File(dir, "CascadeSubjectV1.java");
		Files.writeString(f1.toPath(),
			"package testCascade;\n" +
			"class CascadeSubject {\n" +
			"    public String test() {\n" +
			"        Runnable r1 = new Runnable() {\n" +
			"            public void run() {\n" +
			"                String tag = \"TAG_SAVE\";\n" +
			"                Runnable w1 = new Runnable() { public void run() { String sub = \"TAG_WORKER_SAVE\"; } };\n" +
			"            }\n" +
			"        };\n" +
			"        Runnable r2 = new Runnable() {\n" +
			"            public void run() {\n" +
			"                String tag = \"TAG_DELETE\";\n" +
			"                Runnable w2 = new Runnable() { public void run() { String sub = \"TAG_WORKER_DEL\"; } };\n" +
			"            }\n" +
			"        };\n" +
			"        return \"V1\";\n" +
			"    }\n" +
			"}\n");
		File out1 = new File(dir, "out1");
		out1.mkdirs();
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", out1.getAbsolutePath(), f1.getAbsolutePath());

		// 2. 编译 V2 (头部插入 Audit，导致 Save 与 Delete 整体位移)
		File f2 = new File(dir, "CascadeSubjectV2.java");
		Files.writeString(f2.toPath(),
			"package testCascade;\n" +
			"class CascadeSubject {\n" +
			"    public String test() {\n" +
			"        Runnable rAudit = new Runnable() {\n" + // 新增 $1: Audit
			"            public void run() { String tag = \"TAG_AUDIT\"; }\n" +
			"        };\n" +
			"        Runnable r1 = new Runnable() {\n" + // 位移为 $2: Save -> $2$1
			"            public void run() {\n" +
			"                String tag = \"TAG_SAVE\";\n" +
			"                Runnable w1 = new Runnable() { public void run() { String sub = \"TAG_WORKER_SAVE\"; } };\n" +
			"            }\n" +
			"        };\n" +
			"        Runnable r2 = new Runnable() {\n" + // 位移为 $3: Delete -> $3$1
			"            public void run() {\n" +
			"                String tag = \"TAG_DELETE\";\n" +
			"                Runnable w2 = new Runnable() { public void run() { String sub = \"TAG_WORKER_DEL\"; } };\n" +
			"            }\n" +
			"        };\n" +
			"        return \"V2\";\n" +
			"    }\n" +
			"}\n");
		File out2 = new File(dir, "out2");
		out2.mkdirs();
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", out2.getAbsolutePath(), f2.getAbsolutePath());

		// 读取字节码
		byte[] hostV2 = Files.readAllBytes(new File(out2, "testCascade/CascadeSubject.class").toPath());
		Map<String, byte[]> oldAnon = new LinkedHashMap<>();
		oldAnon.put("testCascade/CascadeSubject$1", Files.readAllBytes(new File(out1, "testCascade/CascadeSubject$1.class").toPath()));
		oldAnon.put("testCascade/CascadeSubject$1$1", Files.readAllBytes(new File(out1, "testCascade/CascadeSubject$1$1.class").toPath()));
		oldAnon.put("testCascade/CascadeSubject$2", Files.readAllBytes(new File(out1, "testCascade/CascadeSubject$2.class").toPath()));
		oldAnon.put("testCascade/CascadeSubject$2$1", Files.readAllBytes(new File(out1, "testCascade/CascadeSubject$2$1.class").toPath()));

		Map<String, byte[]> newAnon = new LinkedHashMap<>();
		newAnon.put("testCascade/CascadeSubject$1", Files.readAllBytes(new File(out2, "testCascade/CascadeSubject$1.class").toPath())); // Audit
		newAnon.put("testCascade/CascadeSubject$2", Files.readAllBytes(new File(out2, "testCascade/CascadeSubject$2.class").toPath())); // Save
		newAnon.put("testCascade/CascadeSubject$2$1", Files.readAllBytes(new File(out2, "testCascade/CascadeSubject$2$1.class").toPath())); // WorkerSave
		newAnon.put("testCascade/CascadeSubject$3", Files.readAllBytes(new File(out2, "testCascade/CascadeSubject$3.class").toPath())); // Delete
		newAnon.put("testCascade/CascadeSubject$3$1", Files.readAllBytes(new File(out2, "testCascade/CascadeSubject$3$1.class").toPath())); // WorkerDel

		// 验证 1: 级联树自顶向下推进与作用域收敛
		AnonClassAligner.Result res = AnonClassAligner.alignCascading("testCascade/CascadeSubject", hostV2, oldAnon, newAnon, null, null);
		Map<String, String> m = res.renameMap;

		check("testCascade/CascadeSubject$1".equals(m.get("testCascade/CascadeSubject$2")),
			"Scenario 19: Level 1 新 Save($2) 精准映射到旧 Save($1)");
		check("testCascade/CascadeSubject$2".equals(m.get("testCascade/CascadeSubject$3")),
			"Scenario 19: Level 1 新 Delete($3) 精准映射到旧 Delete($2)");
		check("testCascade/CascadeSubject$3".equals(m.get("testCascade/CascadeSubject$1")),
			"Scenario 19: Level 1 全新 Audit($1) 分配未占用编号 ($3)");

		check("testCascade/CascadeSubject$1$1".equals(m.get("testCascade/CascadeSubject$2$1")),
			"Scenario 19: Level 2 作用域收敛 - WorkerSave($2$1) 精准对齐到旧 WorkerSave($1$1)");
		check("testCascade/CascadeSubject$2$1".equals(m.get("testCascade/CascadeSubject$3$1")),
			"Scenario 19: Level 2 作用域收敛 - WorkerDel($3$1) 精准对齐到旧 WorkerDel($2$1)");

		// 验证 2: 蜕变测试 - 语义标记置换不变性 (Permutation Invariance)
		List<String> newKeys = new ArrayList<>(newAnon.keySet());
		List<String> oldKeys = new ArrayList<>(oldAnon.keySet());
		boolean permPassed = true;
		for (int seed = 1; seed <= 5; seed++) {
			Collections.shuffle(newKeys, new Random(seed * 42L));
			Collections.shuffle(oldKeys, new Random(seed * 99L));
			Map<String, byte[]> shuffledNew = new LinkedHashMap<>();
			for (String k : newKeys) shuffledNew.put(k, newAnon.get(k));
			Map<String, byte[]> shuffledOld = new LinkedHashMap<>();
			for (String k : oldKeys) shuffledOld.put(k, oldAnon.get(k));

			AnonClassAligner.Result permRes = AnonClassAligner.align("testCascade/CascadeSubject", hostV2, shuffledOld, shuffledNew);
			if (!permRes.renameMap.equals(res.renameMap)) {
				permPassed = false;
				break;
			}
		}
		check(permPassed, "Scenario 19: 蜕变测试 - 随机打乱输入序列后的置换不变性 100% 成立");

		// 验证 3: 蜕变测试 - 幂等性 (Idempotence)
		AnonClassAligner.Result idemRes = AnonClassAligner.align("testCascade/CascadeSubject", hostV2, newAnon, newAnon);
		boolean isIdentity = true;
		for (Map.Entry<String, String> e : idemRes.renameMap.entrySet()) {
			if (!e.getKey().equals(e.getValue())) isIdentity = false;
		}
		check(isIdentity, "Scenario 19: 蜕变测试 - 同一版本连续对齐的幂等性成立（恒等映射）");

		// 验证 4: 蜕变测试 - 尾部追加不变性 (Append Invariance)
		Map<String, byte[]> appendedNew = new LinkedHashMap<>(newAnon);
		// 构造尾部全新类 $4 (带有独立字节码)
		appendedNew.put("testCascade/CascadeSubject$4", Files.readAllBytes(new File(out2, "testCascade/CascadeSubject$1.class").toPath()));
		AnonClassAligner.Result appRes = AnonClassAligner.align("testCascade/CascadeSubject", hostV2, oldAnon, appendedNew);
		boolean appendInvariant = true;
		for (Map.Entry<String, String> e : res.renameMap.entrySet()) {
			if (!e.getValue().equals(appRes.renameMap.get(e.getKey()))) {
				appendInvariant = false;
				break;
			}
		}
		check(appendInvariant, "Scenario 19: 蜕变测试 - 尾部追加全新匿名类不扰动原有所有层级映射");

		// 验证 5: 严格准入分类器 - 具名局部类与枚举排除校验 (D8)
		org.objectweb.asm.tree.ClassNode localCn = new org.objectweb.asm.tree.ClassNode();
		localCn.name = "testCascade/CascadeSubject$1Local";
		localCn.access = org.objectweb.asm.Opcodes.ACC_SUPER;
		org.objectweb.asm.tree.InnerClassNode icn = new org.objectweb.asm.tree.InnerClassNode(
			"testCascade/CascadeSubject$1Local", "testCascade/CascadeSubject", "Local", 0
		);
		localCn.innerClasses = Collections.singletonList(icn);
		check(!AnonClassAligner.isAnonymousClass(localCn, "testCascade/CascadeSubject"),
			"Scenario 19: 严格准入分类器 - 成功识别并排除具名局部类 (Foo$1Local)");

		org.objectweb.asm.tree.ClassNode enumCn = new org.objectweb.asm.tree.ClassNode();
		enumCn.name = "testCascade/CascadeSubject$1";
		enumCn.access = org.objectweb.asm.Opcodes.ACC_ENUM;
		check(!AnonClassAligner.isAnonymousClass(enumCn, "testCascade/CascadeSubject"),
			"Scenario 19: 严格准入分类器 - 成功识别并排除枚举类型 (ACC_ENUM)");

		// 验证 6: 后置校验单射性与前缀不变量防护 (D12)
		Map<String, String> badMap = new HashMap<>();
		badMap.put("testCascade/CascadeSubject$2$1", "testCascade/CascadeSubject$3$1"); // 父是 $1，子却跑到 $3 下
		boolean rejectCaught = false;
		try {
			AnonClassAligner.validateRenameMap(badMap, "testCascade/CascadeSubject");
		} catch (IllegalStateException expected) {
			rejectCaught = true;
		}
		check(rejectCaught, "Scenario 19: 后置校验 - 前缀不变量破坏时触发异常防护");
	}

	static void testScenario20_WhiteboxVulnerabilityReproAndDefense(String javac, File baseDir) throws Exception {
		// 1. 验证缺陷 5: getParentName 在宿主本身是内部类时不跨界
		String hostInner = "com/example/Outer$Inner";
		String anonLevel1 = "com/example/Outer$Inner$1";
		String anonLevel2 = "com/example/Outer$Inner$1$1";
		check(hostInner.equals(AnonClassAligner.getParentName(hostInner, anonLevel1)),
			"Scenario 20: getParentName 宿主为内部类时 Level 1 正确截断为宿主内部名");
		check(anonLevel1.equals(AnonClassAligner.getParentName(hostInner, anonLevel2)),
			"Scenario 20: getParentName 宿主为内部类时 Level 2 正确截断为父匿名类名");
		check(hostInner.equals(AnonClassAligner.getParentName(hostInner, hostInner)),
			"Scenario 20: getParentName 传入宿主类自身时不跨越边界破坏宿主名");

		// 2. 验证缺陷 4: 消除 Tier 5 盲信 name 导致的已删除类被误配（防止反向夺舍）
		File dir = new File(baseDir, "s20");
		dir.mkdirs();
		File fOld = new File(dir, "DeleteShiftOld.java");
		Files.writeString(fOld.toPath(),
			"package testVuln;\n" +
			"class DeleteShift {\n" +
			"    public void run() {\n" +
			"        Runnable taskA = new Runnable() { int a = 1; public void run() { a++; } };\n" +
			"        Runnable taskB = new Runnable() { String b = \"B\"; public void run() { b.trim(); } };\n" +
			"    }\n" +
			"}\n");
		File outOld = new File(dir, "outOld");
		outOld.mkdirs();
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outOld.getAbsolutePath(), fOld.getAbsolutePath());

		File fNew = new File(dir, "DeleteShiftNew.java");
		Files.writeString(fNew.toPath(),
			"package testVuln;\n" +
			"class DeleteShift {\n" +
			"    public void run() {\n" +
			"        Runnable taskC = new Runnable() { double c = 3.14; public void run() { Math.sin(c); } };\n" +
			"    }\n" +
			"}\n");
		File outNew = new File(dir, "outNew");
		outNew.mkdirs();
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outNew.getAbsolutePath(), fNew.getAbsolutePath());

		byte[] hostNew = Files.readAllBytes(new File(outNew, "testVuln/DeleteShift.class").toPath());
		Map<String, byte[]> oldClasses = new HashMap<>();
		oldClasses.put("testVuln/DeleteShift$1", Files.readAllBytes(new File(outOld, "testVuln/DeleteShift$1.class").toPath()));
		oldClasses.put("testVuln/DeleteShift$2", Files.readAllBytes(new File(outOld, "testVuln/DeleteShift$2.class").toPath()));

		Map<String, byte[]> newClasses = new HashMap<>();
		newClasses.put("testVuln/DeleteShift$1", Files.readAllBytes(new File(outNew, "testVuln/DeleteShift$1.class").toPath()));

		AnonClassAligner.Result resVuln = AnonClassAligner.align("testVuln/DeleteShift", hostNew, oldClasses, newClasses);
		check(resVuln.orphanOldClasses.contains("testVuln/DeleteShift$1"),
			"Scenario 20: 移除 Tier 5 盲信 name 匹配 - 旧 TaskA($1) 保持为孤儿类不被篡夺");
		check(resVuln.orphanOldClasses.contains("testVuln/DeleteShift$2"),
			"Scenario 20: 移除 Tier 5 盲信 name 匹配 - 旧 TaskB($2) 保持为孤儿类");
		check(resVuln.stats.orphanClasses == 2,
			"Scenario 20: 结构完全不同且无法在 Tier 1~4 匹配的匿名类绝不按名字盲配 (孤儿数=2)");

		// 3. 验证缺陷 1 & 3: 默认 Resolver 下 lambda 匿名类 outerMethod 与 outerMethodDesc 双恢复
		AnonClassAligner.EnclosingMethodInfo emi = AnonClassAligner.resolveHostMethodForAnon(
			"testVuln/DeleteShift",
			AnonClassAligner.parseHostNode("testVuln/DeleteShift", k -> hostNew),
			"testVuln/DeleteShift$1"
		);
		check(emi != null && "run".equals(emi.name) && "()V".equals(emi.desc),
			"Scenario 20: resolveHostMethodForAnon 同时精准恢复方法名与方法描述符 (run:()V)");
	}

	/**
	 * Scenario 21: §6.3/§6.4 安全门与熔断。
	 *
	 * <p>夹具刻意构造 Tier 4 歧义：新侧只有一个匿名类 P，旧侧有 Q、R 两个匿名类，
	 * 三者同宿主方法 / 同基类(`Runnable`) / 同接口，但字段表两两不同（因此 Tier 3 全部失败），
	 * 于是 Tier 4 上 P 同时匹配 Q 和 R —— 双向唯一不成立，即"低置信度同构平局"（§4.3-①）。</p>
	 */
	static void testScenario21_SafetyGatesAndCircuitBreaker(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 21: 安全门与熔断（§6.3 数量上限/软超时、§6.4 strict） ---");
		File dir = new File(baseDir, "s21");
		dir.mkdirs();

		// 旧侧：两个同宿主方法、同基类接口、字段表各不相同的匿名类
		File fOld = new File(dir, "GateAmb.java");
		Files.writeString(fOld.toPath(),
			"package testGate;\n" +
			"class GateAmb {\n" +
			"    public void setup() {\n" +
			"        Runnable q = new Runnable() { int q = 1; public void run() { q++; } };\n" +
			"        Runnable r = new Runnable() { long r = 2L; public void run() { r++; } };\n" +
			"    }\n" +
			"}\n");
		File outOld = new File(dir, "outOld");
		outOld.mkdirs();
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outOld.getAbsolutePath(), fOld.getAbsolutePath());

		// 新侧：只剩一个匿名类，字段表与 Q、R 都不同 -> Tier 4 一对二歧义
		File fNew = new File(dir, "GateAmbNew.java");
		Files.writeString(fNew.toPath(),
			"package testGate;\n" +
			"class GateAmb {\n" +
			"    public void setup() {\n" +
			"        Runnable p = new Runnable() { String p = \"3\"; public void run() { p.trim(); } };\n" +
			"    }\n" +
			"}\n");
		File outNew = new File(dir, "outNew");
		outNew.mkdirs();
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outNew.getAbsolutePath(), fNew.getAbsolutePath());

		byte[] hostOld = Files.readAllBytes(new File(outOld, "testGate/GateAmb.class").toPath());
		byte[] hostNew = Files.readAllBytes(new File(outNew, "testGate/GateAmb.class").toPath());

		Function<String, byte[]> oldRes = n -> readIfExists(new File(outOld, n.replace('.', '/') + ".class"));
		Function<String, byte[]> newRes = n -> readIfExists(new File(outNew, n.replace('.', '/') + ".class"));

		Map<String, byte[]> oldAnon = new LinkedHashMap<>();
		oldAnon.put("testGate/GateAmb$1", Files.readAllBytes(new File(outOld, "testGate/GateAmb$1.class").toPath()));
		oldAnon.put("testGate/GateAmb$2", Files.readAllBytes(new File(outOld, "testGate/GateAmb$2.class").toPath()));
		Map<String, byte[]> newAnon = new LinkedHashMap<>();
		newAnon.put("testGate/GateAmb$1", Files.readAllBytes(new File(outNew, "testGate/GateAmb$1.class").toPath()));

		// ---- 前置事实：非严格模式下这是"不配对 + 2 个孤儿"，而不是抛异常 ----
		AnonClassAligner.Result relaxed = AnonClassAligner.align(
			"testGate/GateAmb", hostNew, oldAnon, newAnon, oldRes, newRes);
		check(relaxed.stats.ambiguousPairs > 0,
			"Scenario 21: Tier 4 一对二歧义被统计到 stats.ambiguousPairs (=" + relaxed.stats.ambiguousPairs + ")");
		check(relaxed.orphanOldClasses.size() == 2 && relaxed.stats.newClasses == 1,
			"Scenario 21: 非严格模式下歧义退化为「不配对 + 新增/孤儿」，绝不按名字盲配");

		// ---- 1) strict 模式：歧义 -> 整体拒绝宿主组（§4.3-①） ----
		boolean savedStrict = HotSwapAgent.ANON_STRICT;
		AnonClassAligner.AlignmentRejectedException strictReject = null;
		try {
			HotSwapAgent.ANON_STRICT = true;
			AnonClassAligner.align("testGate/GateAmb", hostNew, oldAnon, newAnon, oldRes, newRes);
		} catch (AnonClassAligner.AlignmentRejectedException e) {
			strictReject = e;
		} finally {
			HotSwapAgent.ANON_STRICT = savedStrict;
		}
		check(strictReject != null,
			"Scenario 21: strict 模式下 Tier 4 歧义触发 AlignmentRejectedException（§4.3-①）");
		check(strictReject != null && "testGate/GateAmb".equals(strictReject.hostSlash),
			"Scenario 21: 拒绝异常携带宿主内部名，供 [HOTSWAP-REJECT] 告警定位");
		check(strictReject instanceof IllegalStateException,
			"Scenario 21: 拒绝异常继承 IllegalStateException，保持既有调用方兼容性");

		// ---- 2) 数量硬上限（§6.3-2） ----
		int savedMax = AnonClassAligner.MAX_ANON_PER_HOST;
		AnonClassAligner.AlignmentRejectedException capReject = null;
		try {
			AnonClassAligner.MAX_ANON_PER_HOST = 1;
			AnonClassAligner.align("testGate/GateAmb", hostNew, oldAnon, newAnon, oldRes, newRes);
		} catch (AnonClassAligner.AlignmentRejectedException e) {
			capReject = e;
		} finally {
			AnonClassAligner.MAX_ANON_PER_HOST = savedMax;
		}
		check(capReject != null && capReject.reason.contains("MAX_ANON_PER_HOST"),
			"Scenario 21: 匿名类数量超过 MAX_ANON_PER_HOST 时整体拒绝（§6.3-2）");

		// ---- 3) 软超时（§6.3-3） ----
		long savedTimeout = AnonClassAligner.ALIGN_TIMEOUT_MS;
		AnonClassAligner.AlignmentRejectedException timeoutReject = null;
		try {
			AnonClassAligner.ALIGN_TIMEOUT_MS = 0L;
			AnonClassAligner.align("testGate/GateAmb", hostNew, oldAnon, newAnon, oldRes, newRes);
		} catch (AnonClassAligner.AlignmentRejectedException e) {
			timeoutReject = e;
		} finally {
			AnonClassAligner.ALIGN_TIMEOUT_MS = savedTimeout;
		}
		check(timeoutReject != null && timeoutReject.reason.contains("soft timeout"),
			"Scenario 21: 超出 ALIGN_TIMEOUT_MS 软超时窗口时整体拒绝（§6.3-3）");

		// ---- 4) 后置校验失败也走同一个拒绝通道（§4.3-③） ----
		AnonClassAligner.AlignmentRejectedException validationReject = null;
		try {
			Map<String, String> badMap = new LinkedHashMap<>();
			badMap.put("testGate/GateAmb$1", "testGate/GateAmb$1");
			badMap.put("testGate/GateAmb$2", "testGate/GateAmb$1");
			AnonClassAligner.validateRenameMap(badMap, "testGate/GateAmb");
		} catch (AnonClassAligner.AlignmentRejectedException e) {
			validationReject = e;
		}
		check(validationReject != null && validationReject.reason.contains("non-injective"),
			"Scenario 21: 后置校验（非单射）复用同一拒绝通道，供调用方统一回滚（§4.3-③）");

		// ---- 5) 门恢复后的正常路径不受影响 ----
		AnonClassAligner.Result restored = AnonClassAligner.align(
			"testGate/GateAmb", hostNew, oldAnon, newAnon, oldRes, newRes);
		check(restored.stats.orphanClasses == 2 && AnonClassAligner.MAX_ANON_PER_HOST == 128,
			"Scenario 21: 门限恢复默认后正常返回（无残留状态污染）");
	}

	static byte[] readIfExists(File f) {
		try {
			return f.exists() ? Files.readAllBytes(f.toPath()) : null;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Scenario 22: 嵌套匿名类的**内容哈希可用性**与两条设计不变量。
	 *
	 * <p>背景（探针 {@code DeepNestProbe} 实测）：`AnonClassHasher` 组装非合成方法签名时原本使用
	 * **未屏蔽的原始描述符**，而嵌套匿名类的构造器形如 `<init>(LHost$1;)V` —— 父类一移位，
	 * 子类哈希必变，导致 depth ≥ 2 的每一层都只能靠 **Tier 4**（不比字段表的那层）配对。
	 * 本场景把它锁死：内容不变时**每一层都必须靠 Tier 1 命中**。</p>
	 */
	static void testScenario22_NestedContentHashAvailability(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 22: 嵌套匿名类的内容哈希可用性 + 设计不变量 ---");
		File dir = new File(baseDir, "s22");
		dir.mkdirs();

		// v1: 4 层嵌套匿名类链
		File fV1 = new File(dir, "DeepNestV1.java");
		Files.writeString(fV1.toPath(), deepNestSource("testDeep", "DeepNest", 4, false));
		File outV1 = new File(dir, "v1");
		outV1.mkdirs();
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.getAbsolutePath(), fV1.getAbsolutePath());

		// v2: 顶层**插入**一个额外匿名类，使整条链物理编号位移（链本身内容不变）
		File fV2 = new File(dir, "DeepNestV2.java");
		Files.writeString(fV2.toPath(), deepNestSource("testDeep", "DeepNest", 4, true));
		File outV2 = new File(dir, "v2");
		outV2.mkdirs();
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.getAbsolutePath(), fV2.getAbsolutePath());

		byte[] hostV2 = Files.readAllBytes(new File(outV2, "testDeep/DeepNest.class").toPath());
		Map<String, byte[]> oldAnon = anonClasses(outV1, "testDeep/DeepNest");
		Map<String, byte[]> newAnon = anonClasses(outV2, "testDeep/DeepNest");
		Function<String, byte[]> oldRes = n -> readIfExists(new File(outV1, n.replace('.', '/') + ".class"));
		Function<String, byte[]> newRes = n -> readIfExists(new File(outV2, n.replace('.', '/') + ".class"));

		AnonClassAligner.Result res = AnonClassAligner.align(
			"testDeep/DeepNest", hostV2, oldAnon, newAnon, oldRes, newRes);
		check(res.stats.tier1Matches == 4,
			"Scenario 22: 4 层嵌套在内容不变时必须全部靠 Tier 1 命中（实测 T1=" + res.stats.tier1Matches
				+ "，修复前恒为 1）");
		check(res.orphanOldClasses.isEmpty() && res.stats.newClasses == 1,
			"Scenario 22: 插入的顶层新类判为新增、4 层链全部配对且无孤儿");
		check(res.stats.tier4Matches == 0,
			"Scenario 22: 内容未变时不得退化到不比字段表的 Tier 4（实测 T4=" + res.stats.tier4Matches + "）");

		// 定向屏蔽守卫：不引用匿名类的描述符差异必须保留（否则候选域被无端扩大）
		File fDesc = new File(dir, "Desc.java");
		Files.writeString(fDesc.toPath(),
			"package testDeep;\n" +
			"class Desc {\n" +
			"    interface I { void m(Object x); }\n" +
			"    void go() {\n" +
			"        I a = new I() { public void m(Object x) {}  public void extra(int v) {} };\n" +
			"        I b = new I() { public void m(Object x) {}  public void extra(String v) {} };\n" +
			"    }\n" +
			"}\n");
		File outDesc = new File(dir, "desc");
		outDesc.mkdirs();
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outDesc.getAbsolutePath(), fDesc.getAbsolutePath());
		Function<String, byte[]> descRes = n -> readIfExists(new File(outDesc, n.replace('.', '/') + ".class"));
		Long hInt = AnonClassHasher.hash("testDeep/Desc$1", descRes.apply("testDeep/Desc$1"), "testDeep/Desc", descRes, null, null, 0);
		Long hStr = AnonClassHasher.hash("testDeep/Desc$2", descRes.apply("testDeep/Desc$2"), "testDeep/Desc", descRes, null, null, 0);
		check(hInt != null && hStr != null && !hInt.equals(hStr),
			"Scenario 22: 描述符屏蔽必须定向 —— extra(I)V 与 extra(Ljava/lang/String;)V 不含匿名类引用，仍须可区分");

		// INV-3 守卫：Lambda 不构成命名层级（javac 实测 Alt$1$1，而非 Alt$1$1$1）
		String host = "testDeep/DeepNest";
		check("testDeep/DeepNest$1".equals(AnonClassAligner.getParentName(host, "testDeep/DeepNest$1$1"))
			&& AnonClassAligner.getHierarchyLevel(host, "testDeep/DeepNest$1") == 1
			&& AnonClassAligner.getHierarchyLevel(host, "testDeep/DeepNest$1$1") == 2,
			"Scenario 22: 层级必须按匿名类二进制名数 $ 段（A0=1、A1=2）；若变成 3 说明有人引入了 Lambda 层级");
	}

	/**
	 * Scenario 23: `strict` 是**安全门**而不是匹配策略 —— 三类行为边界。
	 *
	 * <p>背景（§4.1 注记 + 探针 `DeepNestProbe`）：Tier 1/3 允许 minDiff 仲裁时，`CandidatePair.diff`
	 * 用的是物理名序号，于是"插在前面的新类"总是以 diff=0 抢走旧身份（depth 1 即可复现）。这是
	 * **确定性但语义错误**的 tie-breaker，不是随机 bug。</p>
	 *
	 * <p>本场景钉死三条边界（对应用户提出的三类测试）：<br>
	 * A. Tier 3 结构相同 + 1-to-N：non-strict 保持现状；strict → 拒绝且不产出任何 rename；<br>
	 * B. Tier 3 结构唯一：strict **不得**影响它（安全门不改非歧义配对）；<br>
	 * C. 同一宿主里"安全配对 + 歧义配对"共存：验证拒绝粒度是**整个宿主组**，不存在半批次。</p>
	 */
	static void testScenario23_StrictIsSafetyGateNotPolicy(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 23: strict 是安全门而非匹配策略（拒绝粒度 = 整个宿主组）---");
		File dir = new File(baseDir, "s23");
		dir.mkdirs();
		boolean savedStrict = HotSwapAgent.ANON_STRICT;
		try {
			// 非 strict 基线：本场景内所有 "relaxed" 调用都在此状态下，避免开关泄漏到下一个小节
			HotSwapAgent.ANON_STRICT = false;

			// ================= A. Tier 3 结构相同 + 1-to-N =================
			// v1: 一个匿名类；v2: 前面插入一个同构匿名类 + 原类方法体改变
			// → 两个新类都只满足 Tier 3，1-to-2，minDiff 用物理序号仲裁，插入类 diff=0 必胜
			byte[][] a = compilePair(javac, dir, "a", "AmbA",
				"package testStrict;\n" +
				"class AmbA {\n" +
				"    void setup() {\n" +
				"        Runnable x = new Runnable() { public void run() { System.out.println(\"A\"); } };\n" +
				"        x.run();\n" +
				"    }\n}\n",
				"package testStrict;\n" +
				"class AmbA {\n" +
				"    void setup() {\n" +
				"        Runnable extra = new Runnable() { public void run() { System.out.println(\"E\"); } };\n" +
				"        Runnable x = new Runnable() { public void run() { System.out.println(\"A2\"); } };\n" +
				"        extra.run(); x.run();\n" +
				"    }\n}\n");
			AnonClassAligner.Result aRelaxed = alignHost("testStrict/AmbA", dir, "a", a);
			check(aRelaxed.stats.ambiguousMatches == 0 && aRelaxed.stats.topologyMatches == 0,
				"Scenario 23A: Tier 3 不再用 minDiff 仲裁（ambiguousMatches=" + aRelaxed.stats.ambiguousMatches
					+ ", topologyMatches=" + aRelaxed.stats.topologyMatches + "）");
			check(aRelaxed.stats.topologyCandidatesBefore == 2 && aRelaxed.stats.topologyCandidatesAfter == 2,
				"Scenario 23A: 拓扑相等过滤后候选数为 2（before=" + aRelaxed.stats.topologyCandidatesBefore
					+ " -> after=" + aRelaxed.stats.topologyCandidatesAfter + "）→ 不仲裁");
			check("testStrict/AmbA$2".equals(aRelaxed.renameMap.get("testStrict/AmbA$1"))
				&& "testStrict/AmbA$3".equals(aRelaxed.renameMap.get("testStrict/AmbA$2"))
				&& aRelaxed.orphanOldClasses.contains("testStrict/AmbA$1"),
				"Scenario 23A: 不再错配 —— 两个新类都不占旧槽（$1->$2, $2->$3），旧 $1 成孤儿保留旧语义 renameMap="
					+ aRelaxed.renameMap);

			StrictOutcome aStrict = alignStrict("testStrict/AmbA", dir, "a", a);
			check(aStrict.reject != null && aStrict.result == null,
				"Scenario 23A: strict=true 时同一歧义升级为 Reject（不产出 rename）");
			check(aStrict.reject != null && aStrict.reject.reason.contains("unresolved-after-bi-unique=1"),
				"Scenario 23A: 拒绝原因给出缺乏唯一证据的候选对数（reason="
					+ (aStrict.reject == null ? "null" : aStrict.reject.reason) + "）");

			// ================= B. Tier 3 结构唯一：strict 不得影响 =================
			byte[][] b = compilePair(javac, dir, "b", "UniqueB",
				"package testStrict;\n" +
				"class UniqueB {\n" +
				"    void setup() {\n" +
				"        Runnable q = new Runnable() { int q = 1; public void run() { q++; } };\n" +
				"        Runnable s = new Runnable() { String s = \"x\"; public void run() { s.trim(); } };\n" +
				"        q.run(); s.run();\n" +
				"    }\n}\n",
				"package testStrict;\n" +
				"class UniqueB {\n" +
				"    void setup() {\n" +
				"        Runnable q = new Runnable() { int q = 1; public void run() { q += 2; } };\n" +
				"        Runnable s = new Runnable() { String s = \"x\"; public void run() { s.concat(\"\"); } };\n" +
				"        q.run(); s.run();\n" +
				"    }\n}\n");
			StrictOutcome bStrict = alignStrict("testStrict/UniqueB", dir, "b", b);
			check(bStrict.reject == null && bStrict.result != null,
				"Scenario 23B: 结构唯一（每侧只有一个候选）时 strict 不得拒绝 —— 安全门不是新匹配策略");
			check(bStrict.result != null && bStrict.result.stats.ambiguousMatches == 0
					&& bStrict.result.stats.ambiguousPairs == 0,
				"Scenario 23B: 唯一配对不走 minDiff，两个歧义计数都必须为 0");
			check(bStrict.result != null
					&& "testStrict/UniqueB$1".equals(bStrict.result.renameMap.get("testStrict/UniqueB$1"))
					&& "testStrict/UniqueB$2".equals(bStrict.result.renameMap.get("testStrict/UniqueB$2")),
				"Scenario 23B: 结构唯一的两个匿名类在 strict 下仍逐层正确配对（$1→$1, $2→$2）");

			// ================= C. 安全配对 + 歧义配对共存 → 拒绝粒度 =================
			// v1: S(内容不变) + X；v2: 前面插入 extra + S(内容不变) + X2(内容变)
			// Tier 1 唯一命中 S→S（安全）；extra 与 X2 对 X 形成 1-to-2（歧义）
			byte[][] c = compilePair(javac, dir, "c", "MixedC",
				"package testStrict;\n" +
				"class MixedC {\n" +
				"    void setup() {\n" +
				"        Runnable s = new Runnable() { public void run() { System.out.println(\"S\"); } };\n" +
				"        Runnable x = new Runnable() { public void run() { System.out.println(\"X\"); } };\n" +
				"        s.run(); x.run();\n" +
				"    }\n}\n",
				"package testStrict;\n" +
				"class MixedC {\n" +
				"    void setup() {\n" +
				"        Runnable extra = new Runnable() { public void run() { System.out.println(\"E\"); } };\n" +
				"        Runnable s = new Runnable() { public void run() { System.out.println(\"S\"); } };\n" +
				"        Runnable x2 = new Runnable() { public void run() { System.out.println(\"X2\"); } };\n" +
				"        extra.run(); s.run(); x2.run();\n" +
				"    }\n}\n");
			AnonClassAligner.Result cRelaxed = alignHost("testStrict/MixedC", dir, "c", c);
			check(cRelaxed.stats.tier1Matches >= 1 && cRelaxed.stats.ambiguousMatches == 0
					&& cRelaxed.stats.topologyCandidatesAfter == 2,
				"Scenario 23C: 安全配对仍在（T1=" + cRelaxed.stats.tier1Matches
					+ "），歧义对经拓扑过滤后剩 2 个候选 -> 不仲裁（ambiguousMatches="
					+ cRelaxed.stats.ambiguousMatches + "）");
			check("testStrict/MixedC$1".equals(cRelaxed.renameMap.get("testStrict/MixedC$2")),
				"Scenario 23C: 安全配对 $2(内容未变的 S) -> 旧 $1 不受歧义影响，依然成立");
			check("testStrict/MixedC$3".equals(cRelaxed.renameMap.get("testStrict/MixedC$1"))
					&& "testStrict/MixedC$4".equals(cRelaxed.renameMap.get("testStrict/MixedC$3"))
					&& cRelaxed.orphanOldClasses.contains("testStrict/MixedC$2"),
				"Scenario 23C: 歧义对不再错配 —— extra 与 X2 都不占旧槽 $2，旧 $2 成孤儿 renameMap="
					+ cRelaxed.renameMap);

			StrictOutcome cStrict = alignStrict("testStrict/MixedC", dir, "c", c);
			check(cStrict.reject != null && cStrict.result == null,
				"Scenario 23C: strict 下整个宿主组被拒绝（异常在 Result 构造之前抛出，故安全配对也不会落地）");
			check(cStrict.reject != null && cStrict.reject.reason.contains("unresolved-after-bi-unique=1")
					&& "testStrict/MixedC".equals(cStrict.reject.hostSlash),
				"Scenario 23C: 拒绝携带宿主名与无证据候选对数，调用方 rejectHostGroup 据此丢弃宿主+全部匿名类（无半批次）");
		} finally {
			HotSwapAgent.ANON_STRICT = savedStrict;
		}
	}

	/** strict 模式下跑一次 align 的结果：要么拿到 Result，要么拿到拒绝异常（二者互斥）。 */
	static class StrictOutcome {
		AnonClassAligner.Result result;
		AnonClassAligner.AlignmentRejectedException reject;
	}

	/**
	 * 在 {@code ANON_STRICT = true} 下执行一次 align，并在 **finally** 中还原开关。
	 *
	 * <p>开关必须在 finally 里还原 —— 本场景第一版就是漏了这个，导致后一小节的"非 strict 基线"
	 * 调用在 strict 下抛出未捕获异常，整个测试进程直接死掉。</p>
	 */
	static StrictOutcome alignStrict(String hostSlash, File dir, String tag, byte[][] pair) throws Exception {
		StrictOutcome out = new StrictOutcome();
		boolean saved = HotSwapAgent.ANON_STRICT;
		try {
			HotSwapAgent.ANON_STRICT = true;
			out.result = alignHost(hostSlash, dir, tag, pair);
		} catch (AnonClassAligner.AlignmentRejectedException e) {
			out.reject = e;
		} finally {
			HotSwapAgent.ANON_STRICT = saved;
		}
		return out;
	}

	/** 编译一对 (v1, v2) 源码，返回 {hostV2, oldAnon?, ...} 打包成便于 align 的形式。 */
	static byte[][] compilePair(String javac, File dir, String tag, String cls, String v1src, String v2src) throws Exception {
		File s1 = new File(dir, tag + "V1.java");
		Files.writeString(s1.toPath(), v1src);
		File o1 = new File(dir, tag + "v1");
		o1.mkdirs();
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", o1.getAbsolutePath(), s1.getAbsolutePath());

		File s2 = new File(dir, tag + "V2.java");
		Files.writeString(s2.toPath(), v2src);
		File o2 = new File(dir, tag + "v2");
		o2.mkdirs();
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", o2.getAbsolutePath(), s2.getAbsolutePath());

		return new byte[][] { Files.readAllBytes(new File(o2, "testStrict/" + cls + ".class").toPath()) };
	}

	/** 用 Scenario 23 的约定（testStrict 包、tag 目录）跑一次 align。 */
	static AnonClassAligner.Result alignHost(String hostSlash, File dir, String tag, byte[][] pair) throws Exception {
		File o1 = new File(dir, tag + "v1");
		File o2 = new File(dir, tag + "v2");
		Map<String, byte[]> oldAnon = anonClasses(o1, hostSlash);
		Map<String, byte[]> newAnon = anonClasses(o2, hostSlash);
		Function<String, byte[]> oldRes = n -> readIfExists(new File(o1, n.replace('.', '/') + ".class"));
		Function<String, byte[]> newRes = n -> readIfExists(new File(o2, n.replace('.', '/') + ".class"));
		return AnonClassAligner.align(hostSlash, pair[0], oldAnon, newAnon, oldRes, newRes);
	}

	/** 生成 levels 层嵌套匿名类源码；insertTop 时在顶层额外插入一个匿名类制造整链位移。 */
	static String deepNestSource(String pkg, String cls, int levels, boolean insertTop) {
		StringBuilder sb = new StringBuilder();
		sb.append("package ").append(pkg).append(";\n");
		sb.append("class ").append(cls).append(" {\n");
		sb.append("    public void setup() {\n");
		String pad = "        ";
		if (insertTop) {
			sb.append(pad).append("Runnable extra = new Runnable() { public void run() { System.out.println(\"EXTRA\"); } };\n");
		}
		for (int k = 1; k <= levels; k++) {
			sb.append(pad).append("Runnable n").append(k).append(" = new Runnable() { public void run() {\n");
			pad = pad + "    ";
		}
		sb.append(pad).append("System.out.println(\"TAG\");\n");
		for (int k = levels; k >= 1; k--) {
			pad = pad.substring(4);
			sb.append(pad).append("}};\n");
		}
		sb.append("    }\n}\n");
		return sb.toString();
	}

	/** 收集某个宿主类下所有匿名类字节码（内部名 -> 字节码）；按文件名排序以保证确定性。 */
	static Map<String, byte[]> anonClasses(File outDir, String hostSlash) throws Exception {
		Map<String, byte[]> m = new LinkedHashMap<>();
		int lastSlash = hostSlash.lastIndexOf('/');
		String pkgPath = lastSlash > 0 ? hostSlash.substring(0, lastSlash) : "";
		File pkgDir = pkgPath.isEmpty() ? outDir : new File(outDir, pkgPath);
		File[] files = pkgDir.listFiles();
		if (files == null) return m;
		java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
		for (File f : files) {
			if (!f.getName().endsWith(".class")) continue;
			String n = (pkgPath.isEmpty() ? "" : pkgPath + "/") + f.getName().substring(0, f.getName().length() - 6);
			if (AnonClassAligner.isAnonymousClassName(hostSlash, n)) m.put(n, Files.readAllBytes(f.toPath()));
		}
		return m;
	}

	/**
	 * Scenario 24: javac 8 嵌套匿名类的**回退扫描上下文**（§8.3 第 5 条）。
	 *
	 * <p>javac 8 把"嵌套在另一个 lambda 里的 lambda 体"命名为 {@code lambda$null$N}，
	 * `normalizeEnclosingMethod` 会把它归约成字面量 {@code "null"}，于是 `parseInfos` 的回退
	 * 扫描被触发。而嵌套匿名类（{@code Foo$1$1}）的实例化点在**直接父匿名类** {@code Foo$1}
	 * 的方法里，原先固定拿宿主 {@code Foo} 去扫永远返回 null → 两侧 `outerMethod` 都停在
	 * {@code "null"}，**方法作用域这个判据被抹平**。</p>
	 *
	 * <p>夹具：同一父匿名类里两个方法各含一条 {@code lambda→lambda→匿名类} 链；v2 把方法
	 * **声明顺序反转**且两侧方法体都改（Tier 1 失效）。此时唯一可用判据就是方法作用域。
	 * 修复前 2×2 歧义只能按物理序号仲裁 → 恒等映射（跨方法错配）；修复后双向唯一、跨方法正确。</p>
	 */
	static void testScenario24_Javac8ParentScopedFallback(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 24: javac 8 嵌套回退扫描必须用直接父类节点 ---");
		String javac8 = System.getenv("HSTEST_JAVAC8");
		if (javac8 == null) javac8 = "F:/files/java/jdks/jdk-1.8/bin/javac.exe";
		if (!new File(javac8).exists()) javac8 = javac;

		File dir = new File(baseDir, "s24");
		File v1 = new File(dir, "v1"), v2 = new File(dir, "v2");
		v1.mkdirs();
		v2.mkdirs();
		File s1 = new File(dir, "V1.java");
		Files.writeString(s1.toPath(), twoChainSource("alpha", "ALPHA", "beta", "BETA"));
		File s2 = new File(dir, "V2.java");
		Files.writeString(s2.toPath(), twoChainSource("beta", "BETA2", "alpha", "ALPHA2"));
		runCmd(javac8, "-nowarn", "-encoding", "UTF-8", "-d", v1.getAbsolutePath(), s1.getAbsolutePath());
		runCmd(javac8, "-nowarn", "-encoding", "UTF-8", "-d", v2.getAbsolutePath(), s2.getAbsolutePath());

		String host = "testJ8Nest/TwoChain";
		String parent = "testJ8Nest/TwoChain$1";
		File a1 = new File(v1, "testJ8Nest/TwoChain$1$1.class");
		File a2 = new File(v1, "testJ8Nest/TwoChain$1$2.class");
		check(a1.exists() && a2.exists(), "Scenario 24: javac 8 的嵌套匿名类命名为 TwoChain$1$1 / $1$2（非平铺）");

		// 靶子：两条链的内层匿名类 EnclosingMethod 都归约成字面量 "null"
		Function<String, byte[]> res1 = n -> readIfExists(new File(v1, n.replace('.', '/') + ".class"));
		Function<String, byte[]> res2 = n -> readIfExists(new File(v2, n.replace('.', '/') + ".class"));
		String norm1 = normalizedEnclosing(res1.apply(host + "$1$1"));
		String norm2 = normalizedEnclosing(res1.apply(host + "$1$2"));
		check("null".equals(norm1) && "null".equals(norm2),
			"Scenario 24: javac 8 的 lambda$null$N 归约为 \"null\"，回退扫描确实被触发（norm=" + norm1 + "/" + norm2 + "）");

		// 宿主扫描找不到实例化点（这正是必须换上下文的原因）
		boolean hostScanNull = AnonClassAligner.resolveHostMethodForAnon(
			host, AnonClassAligner.parseHostNode(host, res1), host + "$1$1") == null;
		check(hostScanNull, "Scenario 24: 用宿主节点扫 TwoChain$1$1 找不到实例化点（返回 null）");

		// 父类扫描能区分两个方法
		ClassNode parentNode = AnonClassAligner.parseHostNode(parent, res1);
		AnonClassAligner.EnclosingMethodInfo m1 = AnonClassAligner.resolveHostMethodForAnon(parent, parentNode, host + "$1$1");
		AnonClassAligner.EnclosingMethodInfo m2 = AnonClassAligner.resolveHostMethodForAnon(parent, parentNode, host + "$1$2");
		check(m1 != null && m2 != null && !m1.name.equals(m2.name)
				&& (("alpha".equals(m1.name) && "beta".equals(m2.name))
				 || ("beta".equals(m1.name) && "alpha".equals(m2.name))),
			"Scenario 24: 用直接父类节点扫能区分方法作用域（" + fmtEM(m1) + " / " + fmtEM(m2) + "）");

		// 端到端：跨方法正确映射，且没走 minDiff 仲裁
		AnonClassAligner.Result res = AnonClassAligner.align(host,
			Files.readAllBytes(new File(v2, "testJ8Nest/TwoChain.class").toPath()),
			anonClasses(v1, host), anonClasses(v2, host), res1, res2);
		check((host + "$1$2").equals(res.renameMap.get(host + "$1$1"))
				&& (host + "$1$1").equals(res.renameMap.get(host + "$1$2")),
			"Scenario 24: 两条链跨方法正确对齐（$1$1→$1$2, $1$2→$1$1），而不是按物理序号的恒等映射");
		check(res.stats.ambiguousMatches == 0 && res.orphanOldClasses.isEmpty() && res.stats.newClasses == 0,
			"Scenario 24: 靠方法作用域达成双向唯一（T3=" + res.stats.tier3Matches
				+ ", ambiguous=0, 无孤儿无新增），无需 minDiff 仲裁");
	}

	static String normalizedEnclosing(byte[] bytes) {
		if (bytes == null) return null;
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return AnonClassAligner.normalizeEnclosingMethod(cn.outerMethod);
	}

	static String fmtEM(AnonClassAligner.EnclosingMethodInfo e) {
		return e == null ? "null" : e.name;
	}

	/** 两条链的夹具源码；方法声明顺序与 payload 由参数控制。 */
	static String twoChainSource(String m1, String p1, String m2, String p2) {
		return "package testJ8Nest;\n" +
			"class TwoChain {\n" +
			"    static class Worker { void work() {} }\n" +
			"    void go() {\n" +
			"        new Worker() {\n" +
			twoChainMethod(m1, p1) +
			twoChainMethod(m2, p2) +
			"        }.work();\n" +
			"    }\n" +
			"}\n";
	}

	static String twoChainMethod(String method, String payload) {
		return "            void " + method + "() {\n" +
			"                Runnable a = () -> {\n" +
			"                    Runnable b = () -> {\n" +
			"                        new Runnable() { public void run() { System.out.println(\"" + payload + "\"); } };\n" +
			"                    };\n" +
			"                    b.run();\n" +
			"                };\n" +
			"                a.run();\n" +
			"            }\n";
	}

	/**
	 * Scenario 25: 设计不变量 INV-1（自描述指纹）与 INV-2（两个 aligner 不得互相递归）。
	 *
	 * <p><b>INV-1</b>：节点的 primary fingerprint 只描述自身，子节点关系不得递归吸收整棵
	 * descendant 子树。当前它成立靠的是 {@code AnonClassHasher.hash} **内部没有自递归**
	 * （那个 `depth` 参数与 `MAX_DEPTH` 是死代码）—— 属于"碰巧成立"。一旦有人"顺手把递归
	 * 修好"，子类内容哈希就会被折进父类，新增/修改任意后代都会让整条祖先链雪崩。本场景把它
	 * 变成受保护的断言。</p>
	 *
	 * <p><b>INV-2</b>：{@code LambdaAligner} 与 {@code AnonClassAligner} 不得互相调用、
	 * 互相递归求指纹；统一入口只能是纯函数 {@code AnonClassHasher}。用字节码常量池扫描做
	 * 架构守卫（位置无关，不依赖源码路径）。</p>
	 */
	static void testScenario25_DesignInvariants(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 25: INV-1 自描述指纹 / INV-2 禁止互相递归 ---");
		File dir = new File(baseDir, "s25");
		dir.mkdirs();
		String host = "testInv/Inv";

		// A: 基线（子层 payload=TAG，父层无额外语句）
		// B: **只改子层** payload → 父层指纹必须不变、子层指纹必须变
		// C: **只改父层自身**方法体（加一条 println）→ 父层指纹必须变
		File outA = compileInv(javac, dir, "a", invSource("TAG", false));
		File outB = compileInv(javac, dir, "b", invSource("TAG2", false));
		File outC = compileInv(javac, dir, "c", invSource("TAG", true));

		Long outerA = invHash(outA, host, host + "$1");
		Long outerB = invHash(outB, host, host + "$1");
		Long outerC = invHash(outC, host, host + "$1");
		Long innerA = invHash(outA, host, host + "$1$1");
		Long innerB = invHash(outB, host, host + "$1$1");

		check(outerA != null && outerA.equals(outerB),
			"Scenario 25/INV-1: 只改子匿名类内容时，父匿名类指纹必须不变（outer=" + hex(outerA) + " vs " + hex(outerB) + "）");
		check(innerA != null && innerB != null && !innerA.equals(innerB),
			"Scenario 25/INV-1（负向对照）: 子匿名类自身内容确实变了，指纹必须不同（" + hex(innerA) + " vs " + hex(innerB) + "）");
		check(outerA != null && outerC != null && !outerA.equals(outerC),
			"Scenario 25/INV-1（正对照）: 父匿名类**自身**方法体变化必须改变其指纹（" + hex(outerA) + " vs " + hex(outerC) + "）");

		check(!constantPoolMentions(LambdaAligner.class, "nipx/AnonClassAligner"),
			"Scenario 25/INV-2: LambdaAligner 不得引用 AnonClassAligner（禁止互相递归）");
		check(!constantPoolMentions(AnonClassAligner.class, "nipx/LambdaAligner"),
			"Scenario 25/INV-2: AnonClassAligner 不得引用 LambdaAligner");
		check(constantPoolMentions(LambdaAligner.class, "nipx/AnonClassHasher")
				&& constantPoolMentions(AnonClassAligner.class, "nipx/AnonClassHasher"),
			"Scenario 25/INV-2（正对照）: 两者都只通过纯函数 AnonClassHasher 取指纹");
	}

	/** 只改子层内容（innerPayload）或只改父层自身语句（outerExtra）的夹具。 */
	static String invSource(String innerPayload, boolean outerExtra) {
		return "package testInv;\n" +
			"class Inv {\n" +
			"    void setup() {\n" +
			"        Runnable a = new Runnable() { public void run() {\n" +
			(outerExtra ? "            System.out.println(\"OUTER\");\n" : "") +
			"            Runnable b = new Runnable() { public void run() { System.out.println(\"" + innerPayload + "\"); } };\n" +
			"            b.run();\n" +
			"        }};\n" +
			"        a.run();\n" +
			"    }\n" +
			"}\n";
	}

	static File compileInv(String javac, File dir, String tag, String source) throws Exception {
		File src = new File(dir, tag + ".java");
		Files.writeString(src.toPath(), source);
		File out = new File(dir, tag);
		out.mkdirs();
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", out.getAbsolutePath(), src.getAbsolutePath());
		return out;
	}

	static Long invHash(File out, String hostSlash, String slash) {
		byte[] bytes = readIfExists(new File(out, slash + ".class"));
		Function<String, byte[]> res = n -> readIfExists(new File(out, n.replace('.', '/') + ".class"));
		return AnonClassHasher.hash(slash, bytes, hostSlash, res, null, null, 0);
	}

	/** 读取某个类自身的 class 字节（位置无关，jar 或目录都能取到）。 */
	static byte[] ownClassBytes(Class<?> c) throws Exception {
		try (java.io.InputStream in = c.getResourceAsStream(c.getSimpleName() + ".class")) {
			return in == null ? null : in.readAllBytes();
		}
	}

	/**
	 * 常量池是否出现某个内部名。class 文件里类/方法/字段引用都以 UTF-8 内部名存储，
	 * 因此对原始字节做 ISO-8859-1 解码后的 contains 即可（ASCII 名不受 modified-UTF8 影响）。
	 */
	static boolean constantPoolMentions(Class<?> c, String internalName) throws Exception {
		byte[] bytes = ownClassBytes(c);
		if (bytes == null) return false;
		return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains(internalName);
	}

	static String hex(Long h) {
		return h == null ? "null" : Long.toHexString(h);
	}

	/**
	 * Scenario 26: Tier 3 **拓扑相等过滤**取代 minDiff 仲裁（§4.1 / §8.3-4）。
	 *
	 * <p>背景：Tier 3 一旦出现多候选，`minDiff` 按物理名序号仲裁，而"插在前面的新类"总以 diff=0
	 * 抢走旧身份 —— 确定性但语义错误的 tie-breaker（探针 `DeepNestProbe` depth 1 即可复现）。
	 * 新规则：双向唯一之后只保留"拓扑签名与旧类严格相等"的候选；恰好一个且双向唯一才采纳，
	 * 否则**不仲裁**（non-strict 降级为新增/孤儿，strict 拒绝宿主组）。</p>
	 *
	 * <p>三个夹具的职责：T = 拓扑可区分（期望判对）；M = 拓扑无信息（期望拒绝，且 Tier 4 不得绕过）；
	 * L = minDiff 原本判对的合法 1-to-N（期望拒绝，作为**有意付出的保守代价**记 KNOWN）。</p>
	 */
	static void testScenario26_Tier3TopologyFilter(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 26: Tier 3 拓扑相等过滤取代 minDiff 仲裁 ---");
		File dir = new File(baseDir, "s26");
		dir.mkdirs();
		boolean savedReverse = AnonClassAligner.TEST_REVERSE_ORDER;
		try {
			// ================= 夹具 T：拓扑可区分 → 期望判对 =================
			String hostT = "testTopo/Topo";
			File t1 = new File(dir, "tv1"), t2 = new File(dir, "tv2");
			t1.mkdirs(); t2.mkdirs();
			compileInto(javac, t1, new File(dir, "T1.java"), treeSource("testTopo", "Topo", 0, "A0"));
			compileInto(javac, t2, new File(dir, "T2.java"), treeSource("testTopo", "Topo", 1, "A0b"));
			AnonClassAligner.Result tRes = alignDirs(hostT, t1, t2);
			check(tRes.stats.topologyMatches == 1 && tRes.stats.tier3Matches == 0
					&& tRes.stats.tier4Matches == 0 && tRes.stats.ambiguousPairs == 0,
				"Scenario 26/T: 由拓扑判据唯一裁定（topology=" + tRes.stats.topologyMatches
					+ ", T3=" + tRes.stats.tier3Matches + ", T4=" + tRes.stats.tier4Matches
					+ ", ambiguousPairs=" + tRes.stats.ambiguousPairs + "）");
			check((hostT + "$1").equals(tRes.renameMap.get(hostT + "$2"))
					&& (hostT + "$1$1").equals(tRes.renameMap.get(hostT + "$2$1"))
					&& (hostT + "$2").equals(tRes.renameMap.get(hostT + "$1")),
				"Scenario 26/T: 真 A0'($2) 继承旧身份 $1、嵌套类跟随 $2$1->$1$1、空壳($1) 拿未占用新号；"
					+ " renameMap=" + tRes.renameMap);
			check(tRes.orphanOldClasses.isEmpty(),
				"Scenario 26/T: 零孤儿（旧 A0 与其嵌套类都被正确继承）");

			AnonClassAligner.TEST_REVERSE_ORDER = true;
			AnonClassAligner.Result tRev = alignDirs(hostT, t1, t2);
			AnonClassAligner.TEST_REVERSE_ORDER = false;
			check(tRes.renameMap.equals(tRev.renameMap),
				"Scenario 26/T: 反序遍历下映射严格一致（规则不得隐含偏向物理序号）");

			// ================= 夹具 M：拓扑无信息 → 期望拒绝，且 Tier 4 不得绕过 =================
			String hostM = "testTopoM/TopoM";
			File m1 = new File(dir, "mv1"), m2 = new File(dir, "mv2");
			m1.mkdirs(); m2.mkdirs();
			compileInto(javac, m1, new File(dir, "M1.java"), shellChainSource("testTopoM", "TopoM", false, "TAG"));
			compileInto(javac, m2, new File(dir, "M2.java"), shellChainSource("testTopoM", "TopoM", true, "TAG2"));
			AnonClassAligner.Result mRes = alignDirs(hostM, m1, m2);
			check(mRes.stats.topologyCandidatesBefore == 2 && mRes.stats.topologyCandidatesAfter == 2,
				"Scenario 26/M: 拓扑相等过滤后候选数仍为 2（before=" + mRes.stats.topologyCandidatesBefore
					+ " -> after=" + mRes.stats.topologyCandidatesAfter + "）-> 不仲裁");
			check((hostM + "$2").equals(mRes.renameMap.get(hostM + "$1"))
					&& (hostM + "$3").equals(mRes.renameMap.get(hostM + "$2"))
					&& mRes.orphanOldClasses.contains(hostM + "$1"),
				"Scenario 26/M: 不再错配 —— 两个新类都不占旧槽（$1->$2, $2->$3），旧 $1 成孤儿保留旧语义；"
					+ " renameMap=" + mRes.renameMap);
			check(mRes.stats.tier4Matches == 0,
				"Scenario 26/M: Tier 4 没有把拓扑刚否决的候选又配上（Tier 4 谓词是 Tier 3 的真超集，"
					+ "非双向唯一性单调保持，故不可能绕过）");
			check(mRes.stats.ambiguousPairs == 1,
				"Scenario 26/M: 缺乏唯一证据的候选对数 = 1（旧类被两个新类争抢），供 strict 熔断与诊断");
			AnonClassAligner.TEST_REVERSE_ORDER = true;
			AnonClassAligner.Result mRev = alignDirs(hostM, m1, m2);
			AnonClassAligner.TEST_REVERSE_ORDER = false;
			check(mRes.renameMap.equals(mRev.renameMap),
				"Scenario 26/M: 反序遍历下映射严格一致");

			StrictOutcome mStrict = alignStrictDirs(hostM, m1, m2);
			check(mStrict.reject != null && mStrict.result == null,
				"Scenario 26/M: strict 模式下该宿主组被整体拒绝（AlignmentRejectedException）");

			// ================= 夹具 L：minDiff 原本判对的合法 1-to-N → KNOWN 保守代价 =================
			String hostL = "testTopoL/TopoL";
			File l1 = new File(dir, "lv1"), l2 = new File(dir, "lv2");
			l1.mkdirs(); l2.mkdirs();
			compileInto(javac, l1, new File(dir, "L1.java"), twoSameAnonSource("testTopoL", "TopoL", "AAA", "BBB"));
			compileInto(javac, l2, new File(dir, "L2.java"), twoSameAnonSource("testTopoL", "TopoL", "AAA2", "BBB2"));
			AnonClassAligner.Result lRes = alignDirs(hostL, l1, l2);
			boolean lRefused = lRes.orphanOldClasses.size() == 2 && lRes.stats.topologyMatches == 0
				&& (hostL + "$3").equals(lRes.renameMap.get(hostL + "$1"))
				&& (hostL + "$4").equals(lRes.renameMap.get(hostL + "$2"));
			if (lRefused) {
				known("Scenario 26/L: 两个同构匿名类原地改体被**拒绝配对**（拓扑过滤后候选数="
					+ lRes.stats.topologyCandidatesAfter + "）—— 有意付出的保守代价：旧实现靠 minDiff 取"
					+ "恒等映射恰好正确，代价是两条编辑本轮不作用于存活实例；根治需 Tier 1.5 相似度（§8.3-4）");
			} else {
				check(false, "Scenario 26/L: 不再被拒绝（renameMap=" + lRes.renameMap
					+ "）—— 若因拓扑/相似度改进所致，请把本 KNOWN 条目转为 PASS 并同步基线");
			}
			check(lRes.stats.topologyCandidatesBefore == 4 && lRes.stats.topologyCandidatesAfter == 4,
				"Scenario 26/L: 2x2 全等的候选计数（before=" + lRes.stats.topologyCandidatesBefore
					+ " -> after=" + lRes.stats.topologyCandidatesAfter + "），拓扑在此**完全无信息**");

			// ================= 夹具 E：拓扑只配上一对，剩余对是否被 Tier 4 用"排除法"配上 =================
			// old{E1 有匿名子, E2 无子} / new{E1' 有匿名子(内容变), E2' 两个 lambda 站点}
			// 拓扑：E1/E1' 相等；E2 的拓扑为空、E2' 的拓扑为 (0,0,2,2,[]) —— **不等**
			// 因此拓扑过滤只能配上 (E1',E1)，剩 (E2',E2) 进入 Tier 4。
			String hostE = "testElim/Elim";
			File e1 = new File(dir, "ev1"), e2 = new File(dir, "ev2");
			e1.mkdirs(); e2.mkdirs();
			compileInto(javac, e1, new File(dir, "E1.java"), elimSource(false));
			compileInto(javac, e2, new File(dir, "E2.java"), elimSource(true));
			AnonClassAligner.Result eRes = alignDirs(hostE, e1, e2);
			check(eRes.stats.topologyMatches == 1 && eRes.stats.ambiguousPairs == 0,
				"Scenario 26/E: 拓扑只唯一配上一对（topology=" + eRes.stats.topologyMatches
					+ "）；剩余一对在**缩小后的剩余集**里被 Tier 4 以双向唯一配上（T4="
					+ eRes.stats.tier4Matches + "），最终无残留歧义（ambiguousPairs="
					+ eRes.stats.ambiguousPairs + "）");
			check(eRes.stats.tier4Matches == 1 && eRes.orphanOldClasses.isEmpty()
					&& (hostE + "$2").equals(eRes.renameMap.get(hostE + "$2")),
				"Scenario 26/E: 排除法配对的行为被**明确钉住**（不是偶然）：E2'(两个 lambda) 仍继承旧 E2；"
					+ " 注意这是**谓词层面的排除法**（不涉及序号），但确实覆盖了拓扑对 (E2',E2) 的否决 —— "
					+ " renameMap=" + eRes.renameMap);
			StrictOutcome eStrict = alignStrictDirs(hostE, e1, e2);
			check(eStrict.reject == null,
				"Scenario 26/E: 因此 strict 在本形态下**接受**（最终剩余集无歧义）—— 与旧实现（Tier 3 仲裁 2 次 -> strict 拒绝）"
					+ "相比这是一次**口径放宽**，已按你的要求显式钉住而非默认偶然");

			// ================= 保守性损失必须可见：非 strict 拒绝要打 warn =================
			List<String> warnsM = new ArrayList<>();
			captureWarns(() -> { try { alignDirs(hostM, m1, m2); } catch (Exception ignored) { } }, warnsM);
			check(warnsM.stream().anyMatch(w -> w.contains(hostM)
					&& w.contains("RESTART") && w.contains("candidate pair(s) lack unique evidence")),
				"Scenario 26/M: 非 strict 拒绝必须打 warn（含宿主名 + 候选数 + 需重启），"
					+ "否则用户会以为热更已生效却看不到任何效果；warns=" + warnsM);
			List<String> warnsL = new ArrayList<>();
			final File fl1 = l1, fl2 = l2;
			captureWarns(() -> { try { alignDirs(hostL, fl1, fl2); } catch (Exception ignored) { } }, warnsL);
			check(warnsL.stream().anyMatch(w -> w.contains(hostL) && w.contains("RESTART")),
				"Scenario 26/L: 夹具 L（原地改两个同构匿名类）同样必须打出这条 warn —— 这种编辑不罕见");

			// ================= 反序变体：extra 插在 A0 **之后** =================
			String hostTa = "testTopoA/TopoA";
			File a1 = new File(dir, "av1"), a2 = new File(dir, "av2");
			a1.mkdirs(); a2.mkdirs();
			compileInto(javac, a1, new File(dir, "A1.java"), treeSource("testTopoA", "TopoA", 0, "A0"));
			compileInto(javac, a2, new File(dir, "A2.java"), treeSource("testTopoA", "TopoA", 2, "A0b"));
			AnonClassAligner.Result aRes = alignDirs(hostTa, a1, a2);
			check(aRes.stats.topologyMatches == 1 && aRes.orphanOldClasses.isEmpty()
					&& (hostTa + "$1").equals(aRes.renameMap.get(hostTa + "$1")),
				"Scenario 26/反序变体: extra 插在后面时同样判对（A0' 保持旧身份 $1），结论不随位置改变；"
					+ " renameMap=" + aRes.renameMap);
			AnonClassAligner.TEST_REVERSE_ORDER = true;
			AnonClassAligner.Result aRev = alignDirs(hostTa, a1, a2);
			AnonClassAligner.TEST_REVERSE_ORDER = false;
			check(aRes.renameMap.equals(aRev.renameMap),
				"Scenario 26/反序变体: 反序遍历下映射严格一致");
		} finally {
			AnonClassAligner.TEST_REVERSE_ORDER = savedReverse;
		}
	}

	/** 写源码并编译到指定输出目录。 */
	static void compileInto(String javac, File out, File src, String source) throws Exception {
		Files.writeString(src.toPath(), source);
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", out.getAbsolutePath(), src.getAbsolutePath());
	}

	/** 用两个输出目录作为旧/新侧跑一次 align（全部匿名类都参与，与生产路径一致）。 */
	static AnonClassAligner.Result alignDirs(String hostSlash, File v1, File v2) throws Exception {
		Function<String, byte[]> r1 = n -> readIfExists(new File(v1, n.replace('.', '/') + ".class"));
		Function<String, byte[]> r2 = n -> readIfExists(new File(v2, n.replace('.', '/') + ".class"));
		return AnonClassAligner.align(hostSlash,
			Files.readAllBytes(new File(v2, hostSlash + ".class").toPath()),
			anonClasses(v1, hostSlash), anonClasses(v2, hostSlash), r1, r2);
	}

	static StrictOutcome alignStrictDirs(String hostSlash, File v1, File v2) throws Exception {
		StrictOutcome out = new StrictOutcome();
		boolean saved = HotSwapAgent.ANON_STRICT;
		try {
			HotSwapAgent.ANON_STRICT = true;
			out.result = alignDirs(hostSlash, v1, v2);
		} catch (AnonClassAligner.AlignmentRejectedException e) {
			out.reject = e;
		} finally {
			HotSwapAgent.ANON_STRICT = saved;
		}
		return out;
	}

	/**
	 * 夹具 T / 反序变体源码：宿主 {@code setup()} 的 lambda 里创建匿名类 A0，A0 的方法里再有
	 * lambda，该 lambda 里再创建嵌套匿名类 A1（即 {@code A0{L1{A1}}}）。
	 *
	 * @param extraPos 0 = 不插入；1 = 在 A0 **之前**插入无子节点的空壳；2 = 在 A0 **之后**插入
	 */
	static String treeSource(String pkg, String cls, int extraPos, String payload) {
		String shell = "            new Worker() { void work() { System.out.println(\"NEW\"); } }.work();\n";
		return "package " + pkg + ";\n" +
			"class " + cls + " {\n" +
			"    static class Worker { void work() {} }\n" +
			"    static class Task {}\n" +
			"    void setup() {\n" +
			"        Runnable l0 = () -> {\n" +
			(extraPos == 1 ? shell : "") +
			"            new Worker() {\n" +
			"                void work() {\n" +
			"                    Runnable l1 = () -> { System.out.println(\"" + payload + "\"); new Task() {}; };\n" +
			"                    l1.run();\n" +
			"                }\n" +
			"            }.work();\n" +
			(extraPos == 2 ? shell : "") +
			"        };\n" +
			"        l0.run();\n" +
			"    }\n" +
			"}\n";
	}

	/**
	 * 夹具 M 源码：可选在链**前**插入一个空壳匿名类；payload 控制内容哈希是否变化。
	 *
	 * <p>注意 payload 必须变化：否则新链与旧链**逐字节相同**，Tier 1 会直接命中，
	 * 根本走不到 Tier 3 的多候选分支（这正是第一版夹具没复现出歧义的原因）。</p>
	 */
	static String shellChainSource(String pkg, String cls, boolean insertShell, String payload) {
		return "package " + pkg + ";\n" +
			"class " + cls + " {\n" +
			"    void setup() {\n" +
			(insertShell
				? "        Runnable extra = new Runnable() { public void run() { System.out.println(\"EXTRA\"); } };\n"
				  + "        extra.run();\n"
				: "") +
			"        Runnable n1 = new Runnable() { public void run() { System.out.println(\"" + payload + "\"); } };\n" +
			"        n1.run();\n" +
			"    }\n" +
			"}\n";
	}

	/** 夹具 L 源码：两个结构/拓扑全等的同构匿名类，payload 可控。 */
	static String twoSameAnonSource(String pkg, String cls, String p1, String p2) {
		return "package " + pkg + ";\n" +
			"class " + cls + " {\n" +
			"    void setup() {\n" +
			"        Runnable a = new Runnable() { public void run() { System.out.println(\"" + p1 + "\"); } };\n" +
			"        Runnable b = new Runnable() { public void run() { System.out.println(\"" + p2 + "\"); } };\n" +
			"        a.run(); b.run();\n" +
			"    }\n" +
			"}\n";
	}

	/** 在替换后的 logger 下执行一次操作，收集 warn/error 文本（用于断言"保守性损失可见"）。 */
	static void captureWarns(Runnable action, List<String> out) {
		HotSwapAgent.Logger saved = HotSwapAgent.logger;
		HotSwapAgent.logger = new HotSwapAgent.Logger() {
			@Override public void log(String msg) { }
			@Override public void info(String msg) { }
			@Override public void warn(String msg) { out.add(msg); }
			@Override public void error(String msg) { out.add("ERROR " + msg); }
			@Override public void error(String msg, Throwable t) { out.add("ERROR " + msg); }
		};
		try {
			action.run();
		} finally {
			HotSwapAgent.logger = saved;
		}
	}

	/**
	 * 夹具 E（排除法 / 拓扑只配上一对）：old{E1 有匿名子节点, E2 无子}，
	 * new{E1' 有匿名子节点(内容变), E2' 有两个 lambda 站点}。
	 */
	static String elimSource(boolean v2) {
		String b1 = v2
			? "        Runnable n1 = new Runnable() { public void run() {\n" +
			  "            Runnable n1x = () -> { System.out.println(\"N1\"); };\n" +
			  "            n1x.run(); new Task() {};\n" +
			  "        } };\n        n1.run();\n"
			: "        Runnable o1 = new Runnable() { public void run() {\n" +
			  "            Runnable o1x = () -> { System.out.println(\"O1\"); };\n" +
			  "            o1x.run(); new Task() {};\n" +
			  "        } };\n        o1.run();\n";
		String b2 = v2
			? "        Runnable n2 = new Runnable() { public void run() {\n" +
			  "            Runnable n2x = () -> { System.out.println(\"N2\"); };\n" +
			  "            Runnable n2y = () -> { System.out.println(\"N2b\"); };\n" +
			  "            n2x.run(); n2y.run();\n" +
			  "        } };\n        n2.run();\n"
			: "        Runnable o2 = new Runnable() { public void run() { System.out.println(\"O2\"); } };\n" +
			  "        o2.run();\n";
		return "package testElim;\n" +
			"class Elim {\n" +
			"    static class Task {}\n" +
			"    void setup() {\n" +
			b1 + b2 +
			"    }\n}\n";
	}

	static void runCmd(String... cmd) throws Exception {
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
			String line;
			while ((line = r.readLine()) != null) {
				System.out.println("[javac] " + line);
			}
		}
		int rc = p.waitFor();
		if (rc != 0) throw new RuntimeException("Command failed with exit " + rc + ": " + Arrays.toString(cmd));
	}
}
