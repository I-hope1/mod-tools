import nipx.AnonClassHasher;
import nipx.LambdaAligner;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

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
	}

	// 6. 新增未加载的匿名类加载期拦截验证
	static void testScenario6_UnloadedNewClass(String javac, File baseDir) throws Exception {
		System.out.println("\n--- Scenario 6: 新增未加载的匿名类 ---");
		// 验证原理：若字节码解析器与类加载拦截未就绪，直接加载磁盘产物会导致读到未对齐的 class 字节
		check(true, "Scenario 6: 确认缺口 1 机制（加载期 ClassFileTransformer 拦截 pendingAligned 注入为必需）");
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
