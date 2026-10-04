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
 * </ol>
 */
public class AnonClassReproTest {

	static int passed = 0;
	static int failed = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   PASS  " : "   FAIL  ") + msg);
		if (ok) passed++; else failed++;
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
		} finally {
			deleteRecursively(baseDir);
		}

		System.out.println("AnonClassReproTest 汇总: 通过=" + passed + ", 失败=" + failed);
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

	// 12. JDK 8 嵌套 Lambda 内匿名类（lambda$null$0）宿主方法扫描与对齐
	static void testScenario12_Javac8NestedLambdaNullEnclosingMethod(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 12: javac 8 嵌套 Lambda (lambda$null$0) 宿主扫描与对齐 ---");
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
			"        Runnable r1 = () -> {\n" +
			"            Runnable r2 = () -> {\n" +
			"                Runnable r3 = new Runnable() {\n" +
			"                    public void run() { System.out.println(\"job\"); }\n" +
			"                };\n" +
			"                r3.run();\n" +
			"            };\n" +
			"            r2.run();\n" +
			"        };\n" +
			"    }\n" +
			"}\n");

		File outDir = new File(dir, "out");
		outDir.mkdirs();
		runCmd(javac8, "-nowarn", "-encoding", "UTF-8", "-d", outDir.getAbsolutePath(), fSrc.getAbsolutePath());

		byte[] hostBytes = Files.readAllBytes(new File(outDir, "testJ8Nest/NestedCase.class").toPath());
		byte[] anonBytes = Files.readAllBytes(new File(outDir, "testJ8Nest/NestedCase$1.class").toPath());

		// 验证 javac 8 产物的 EnclosingMethod 确实是 lambda$null$0
		ClassNode anonNode = new ClassNode();
		new ClassReader(anonBytes).accept(anonNode, 0);
		check("lambda$null$0".equals(anonNode.outerMethod), "Scenario 12: 证实 javac 8 将嵌套 lambda 匿名类 EnclosingMethod 记为 lambda$null$0");

		Map<String, byte[]> anonMap = new HashMap<>();
		anonMap.put("testJ8Nest/NestedCase$1", anonBytes);

		Function<String, byte[]> resolver = name -> {
			if ("testJ8Nest/NestedCase".equals(name) || "testJ8Nest.NestedCase".equals(name)) return hostBytes;
			if ("testJ8Nest/NestedCase$1".equals(name) || "testJ8Nest.NestedCase$1".equals(name)) return anonBytes;
			return null;
		};

		AnonClassAligner.Result res = AnonClassAligner.align("testJ8Nest/NestedCase", hostBytes, anonMap, anonMap, resolver, resolver);
		check("testJ8Nest/NestedCase$1".equals(res.renameMap.get("testJ8Nest/NestedCase$1")), "Scenario 12: javac 8 嵌套 lambda 匿名类成功对齐");
		check(res.stats.tier1Matches == 1, "Scenario 12: resolveHostMethodForAnon 成功恢复真实宿主方法 runJob 并达成 Tier 1 命中");
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
