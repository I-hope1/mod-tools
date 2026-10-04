import nipx.AnonClassAligner;
import nipx.AnnotationTransformer;
import nipx.HotSwapAgent;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * 真实 JBR-21 + DCEVM 增强重定义环境下的端到端实测验证套件：
 *
 * <ol>
 *   <li><b>对照组 (Control Group)</b>：
 *       关闭对齐器直接用原始位移产物重定义，验证旧实例确实被静默掉包（证明缺陷的客观存在与复现）。</li>
 *   <li><b>实验组 (Experiment Group - 对齐器生效)</b>：
 *       验证热更前持有的旧实例逻辑与捕获字段（Captured State）完全保活（未被篡夺）。</li>
 *   <li><b>位移后的未加载类 (Unloaded Displaced Class)</b>：
 *       类在热更前从未被加载，磁盘文件已被 javac 覆盖为错位内容，验证首次调用时通过 pending 拦截成功加载正确版本。</li>
 *   <li><b>同一 JVM 内多轮连续热更 (Multi-Round HotSwap & Cache)</b>：
 *       第二轮热更旧侧字节码从 bytecodeCache 读取，验证两轮连续热更无累积漂移。</li>
 * </ol>
 */
public class LiveDcevmTest {

	static int passed = 0;
	static int failed = 0;

	static void check(boolean cond, String msg) {
		if (cond) {
			System.out.println("   [PASS] " + msg);
			passed++;
		} else {
			System.err.println("   [FAIL] " + msg);
			failed++;
			throw new AssertionError("Test assertion failed: " + msg);
		}
	}

	public static void main(String[] args) throws Exception {
		System.out.println("=== LiveDcevmTest: 真实 JBR/DCEVM 端到端热更与实例保活验证 ===");

		Instrumentation inst = HotSwapAgent.getInstrumentation();
		check(inst != null, "Instrumentation 实例成功通过 -javaagent 注入获取");
		check(inst.isRedefineClassesSupported(), "JVM 支持类重定义 (isRedefineClassesSupported)");

		String javac = System.getenv("HSTEST_JAVAC21");
		if (javac == null) javac = "F:/files/java/jdks/openjdk-21.0.2/bin/javac.exe";
		if (!new File(javac).exists()) javac = "javac";

		File tempDir = Files.createTempDirectory("dcevm_live_test").toFile();
		try {
			// Part 1: 对照组测试（不对齐时确实错）
			testControlGroupWithoutAligner(javac, tempDir, inst);

			// Part 2: 实验组测试（状态捕获保活 + 位移后未加载类拦截 + 连续两轮热更）
			testExperimentGroupWithAligner(javac, tempDir, inst);

			System.out.println("\n>>> [DCEVM E2E ALL PASSED] 真实 DCEVM 下对照组与实验组验证全部通过！(通过=" + passed + ", 失败=" + failed + ") <<<");
		} finally {
			AnnotationTransformer.pendingAlignedClasses.clear();
			deleteRecursively(tempDir);
		}
	}

	// --------------------------------------------------------------------------------
	// Part 1: 对照组（不进行匿名类对齐，直接使用 javac 原始位移字节码重定义）
	// --------------------------------------------------------------------------------
	static void testControlGroupWithoutAligner(String javac, File baseDir, Instrumentation inst) throws Exception {
		System.out.println("\n--- [对照组] 未使用对齐器：证明 DCEVM 发生旧实例掉包 ---");

		File ctrlDir = new File(baseDir, "control");
		File srcV1 = new File(ctrlDir, "src_v1/ctrl");
		File srcV2 = new File(ctrlDir, "src_v2/ctrl");
		File outDir = new File(ctrlDir, "out");
		srcV1.mkdirs();
		srcV2.mkdirs();
		outDir.mkdirs();

		File fV1 = new File(srcV1, "CtrlSubject.java");
		File fV2 = new File(srcV2, "CtrlSubject.java");

		Files.writeString(fV1.toPath(),
			"package ctrl;\n" +
			"import java.util.concurrent.Callable;\n" +
			"public class CtrlSubject {\n" +
			"    public static Callable<String> makeSave() {\n" +
			"        return new Callable<String>() {\n" +
			"            public String call() { return \"SAVE_ORIGINAL\"; }\n" +
			"        };\n" +
			"    }\n" +
			"    public static Callable<String> makeDelete() {\n" +
			"        return new Callable<String>() {\n" +
			"            public String call() { return \"DELETE_ORIGINAL\"; }\n" +
			"        };\n" +
			"    }\n" +
			"}\n");

		Files.writeString(fV2.toPath(),
			"package ctrl;\n" +
			"import java.util.concurrent.Callable;\n" +
			"public class CtrlSubject {\n" +
			"    public static Callable<String> makeOther() {\n" +
			"        return new Callable<String>() {\n" +
			"            public String call() { return \"OTHER_HIJACKED\"; }\n" +
			"        };\n" +
			"    }\n" +
			"    public static Callable<String> makeSave() {\n" +
			"        return new Callable<String>() {\n" +
			"            public String call() { return \"SAVE_UPDATED\"; }\n" +
			"        };\n" +
			"    }\n" +
			"    public static Callable<String> makeDelete() {\n" +
			"        return new Callable<String>() {\n" +
			"            public String call() { return \"DELETE_UPDATED\"; }\n" +
			"        };\n" +
			"    }\n" +
			"}\n");

		// 编译 V1
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outDir.getAbsolutePath(), fV1.getAbsolutePath());

		URLClassLoader loader = new URLClassLoader(new URL[]{outDir.toURI().toURL()}, LiveDcevmTest.class.getClassLoader());
		Class<?> hostClass = loader.loadClass("ctrl.CtrlSubject");
		Method mSave = hostClass.getMethod("makeSave");

		@SuppressWarnings("unchecked")
		Callable<String> oldSaveInstance = (Callable<String>) mSave.invoke(null);
		check("ctrl.CtrlSubject$1".equals(oldSaveInstance.getClass().getName()), "对照组 V1: Save 实例物理类型为 CtrlSubject$1");
		check("SAVE_ORIGINAL".equals(oldSaveInstance.call()), "对照组 V1: 调用 Save 实例返回 SAVE_ORIGINAL");

		// 编译 V2 覆盖 outDir（在最前插入了 makeOther，导致原始位移：$1=Other, $2=Save, $3=Delete）
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outDir.getAbsolutePath(), fV2.getAbsolutePath());

		byte[] rawV2Host = Files.readAllBytes(new File(outDir, "ctrl/CtrlSubject.class").toPath());
		byte[] rawV2Anon1 = Files.readAllBytes(new File(outDir, "ctrl/CtrlSubject$1.class").toPath()); // Other!
		byte[] rawV2Anon2 = Files.readAllBytes(new File(outDir, "ctrl/CtrlSubject$2.class").toPath()); // Save!

		Class<?> classAnon1 = loader.loadClass("ctrl.CtrlSubject$1");

		// 对照组操作：不走 Aligner，直接将 javac 原始字节码重定义进 JVM
		ClassDefinition defHost = new ClassDefinition(hostClass, rawV2Host);
		ClassDefinition def1 = new ClassDefinition(classAnon1, rawV2Anon1);
		inst.redefineClasses(defHost, def1);

		// 核心实测断言：由于 CtrlSubject$1 字节码被物理重定义为 Other 的逻辑，
		// 原本持有的旧 Save 实例在无对齐器保护下，逻辑被彻底篡夺为 OTHER_HIJACKED！
		String hijackedResult = oldSaveInstance.call();
		check("OTHER_HIJACKED".equals(hijackedResult), "对照组证实：不对齐时，旧 Save 实例确实被 Other 逻辑篡夺（返回 OTHER_HIJACKED），推演被物理证实！");
	}

	// --------------------------------------------------------------------------------
	// Part 2: 实验组（对齐器保护：捕获状态保活 + 位移后未加载类拦截 + 连续两轮热更）
	// --------------------------------------------------------------------------------
	static void testExperimentGroupWithAligner(String javac, File baseDir, Instrumentation inst) throws Exception {
		System.out.println("\n--- [实验组] 对齐器保护：捕获字段保活、位移未加载类拦截与连续热更 ---");

		File expDir = new File(baseDir, "experiment");
		File srcV1 = new File(expDir, "src_v1/live");
		File srcV2 = new File(expDir, "src_v2/live");
		File srcV3 = new File(expDir, "src_v3/live");
		File outDir = new File(expDir, "out");
		srcV1.mkdirs();
		srcV2.mkdirs();
		srcV3.mkdirs();
		outDir.mkdirs();

		File fV1 = new File(srcV1, "LiveSubject.java");
		File fV2 = new File(srcV2, "LiveSubject.java");
		File fV3 = new File(srcV3, "LiveSubject.java");

		// V1:
		// $1: Save (捕获 saveCtx)
		// $2: Delete (捕获 delCtx)
		// $3: Unused (未加载类，热更前绝不调用)
		Files.writeString(fV1.toPath(),
			"package live;\n" +
			"import java.util.concurrent.Callable;\n" +
			"public class LiveSubject {\n" +
			"    public static Callable<String> makeSave(final String saveCtx) {\n" +
			"        return new Callable<String>() {\n" +
			"            public String call() { return \"SAVE_V1:\" + saveCtx; }\n" +
			"        };\n" +
			"    }\n" +
			"    public static Callable<String> makeDelete(final String delCtx) {\n" +
			"        return new Callable<String>() {\n" +
			"            public String call() { return \"DELETE_V1:\" + delCtx; }\n" +
			"        };\n" +
			"    }\n" +
			"    public static Callable<String> makeUnused() {\n" +
			"        return new Callable<String>() {\n" +
			"            public String call() { return \"UNUSED_V1\"; }\n" +
			"        };\n" +
			"    }\n" +
			"}\n");

		// V2: 最前方插入 makeOther
		// 此时 raw javac 编号：
		// $1: Other
		// $2: Save (捕获 saveCtx)
		// $3: Delete (捕获 delCtx)
		// $4: Unused (位移到了 $4，且磁盘上的 $3 变成了 Delete!)
		Files.writeString(fV2.toPath(),
			"package live;\n" +
			"import java.util.concurrent.Callable;\n" +
			"public class LiveSubject {\n" +
			"    public static Callable<String> makeOther() {\n" +
			"        return new Callable<String>() {\n" +
			"            public String call() { return \"OTHER_V2\"; }\n" +
			"        };\n" +
			"    }\n" +
			"    public static Callable<String> makeSave(final String saveCtx) {\n" +
			"        return new Callable<String>() {\n" +
			"            public String call() { return \"SAVE_V2:\" + saveCtx; }\n" +
			"        };\n" +
			"    }\n" +
			"    public static Callable<String> makeDelete(final String delCtx) {\n" +
			"        return new Callable<String>() {\n" +
			"            public String call() { return \"DELETE_V2:\" + delCtx; }\n" +
			"        };\n" +
			"    }\n" +
			"    public static Callable<String> makeUnused() {\n" +
			"        return new Callable<String>() {\n" +
			"            public String call() { return \"UNUSED_V2\"; }\n" +
			"        };\n" +
			"    }\n" +
			"}\n");

		// V3: 第二轮修改，进一步更新 Save 业务逻辑为 V3
		Files.writeString(fV3.toPath(),
			"package live;\n" +
			"import java.util.concurrent.Callable;\n" +
			"public class LiveSubject {\n" +
			"    public static Callable<String> makeOther() {\n" +
			"        return new Callable<String>() {\n" +
			"            public String call() { return \"OTHER_V3\"; }\n" +
			"        };\n" +
			"    }\n" +
			"    public static Callable<String> makeSave(final String saveCtx) {\n" +
			"        return new Callable<String>() {\n" +
			"            public String call() { return \"SAVE_V3:\" + saveCtx; }\n" +
			"        };\n" +
			"    }\n" +
			"    public static Callable<String> makeDelete(final String delCtx) {\n" +
			"        return new Callable<String>() {\n" +
			"            public String call() { return \"DELETE_V3:\" + delCtx; }\n" +
			"        };\n" +
			"    }\n" +
			"    public static Callable<String> makeUnused() {\n" +
			"        return new Callable<String>() {\n" +
			"            public String call() { return \"UNUSED_V3\"; }\n" +
			"        };\n" +
			"    }\n" +
			"}\n");

		// 编译 V1
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outDir.getAbsolutePath(), fV1.getAbsolutePath());

		URLClassLoader loader = new URLClassLoader(new URL[]{outDir.toURI().toURL()}, LiveDcevmTest.class.getClassLoader());
		Class<?> hostClass = loader.loadClass("live.LiveSubject");
		Method mSave = hostClass.getMethod("makeSave", String.class);
		Method mDelete = hostClass.getMethod("makeDelete", String.class);

		// 创建旧对象实例并持有其存活引用（分别传入不同的捕获变量）
		@SuppressWarnings("unchecked")
		Callable<String> oldSaveInstance = (Callable<String>) mSave.invoke(null, "CTX_SAVE_100");
		@SuppressWarnings("unchecked")
		Callable<String> oldDeleteInstance = (Callable<String>) mDelete.invoke(null, "CTX_DEL_200");

		check("live.LiveSubject$1".equals(oldSaveInstance.getClass().getName()), "实验组 V1: Save 实例物理类型为 LiveSubject$1");
		check("live.LiveSubject$2".equals(oldDeleteInstance.getClass().getName()), "实验组 V1: Delete 实例物理类型为 LiveSubject$2");
		check("SAVE_V1:CTX_SAVE_100".equals(oldSaveInstance.call()), "实验组 V1: 调用 Save 实例返回 SAVE_V1:CTX_SAVE_100");
		check("DELETE_V1:CTX_DEL_200".equals(oldDeleteInstance.call()), "实验组 V1: 调用 Delete 实例返回 DELETE_V1:CTX_DEL_200");

		// 记录旧侧字节码缓存
		byte[] v1_1 = Files.readAllBytes(new File(outDir, "live/LiveSubject$1.class").toPath());
		byte[] v1_2 = Files.readAllBytes(new File(outDir, "live/LiveSubject$2.class").toPath());
		byte[] v1_3 = Files.readAllBytes(new File(outDir, "live/LiveSubject$3.class").toPath()); // Unused (未加载)

		Map<String, byte[]> oldAnon = new HashMap<>();
		oldAnon.put("live/LiveSubject$1", v1_1);
		oldAnon.put("live/LiveSubject$2", v1_2);
		oldAnon.put("live/LiveSubject$3", v1_3);

		// --- 轮次 1：编译 V2 并执行第一轮热更 ---
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outDir.getAbsolutePath(), fV2.getAbsolutePath());

		byte[] v2Host = Files.readAllBytes(new File(outDir, "live/LiveSubject.class").toPath());
		byte[] v2_1 = Files.readAllBytes(new File(outDir, "live/LiveSubject$1.class").toPath()); // Other
		byte[] v2_2 = Files.readAllBytes(new File(outDir, "live/LiveSubject$2.class").toPath()); // Save
		byte[] v2_3 = Files.readAllBytes(new File(outDir, "live/LiveSubject$3.class").toPath()); // Delete
		byte[] v2_4 = Files.readAllBytes(new File(outDir, "live/LiveSubject$4.class").toPath()); // Unused

		Map<String, byte[]> newAnonR1 = new HashMap<>();
		newAnonR1.put("live/LiveSubject$1", v2_1);
		newAnonR1.put("live/LiveSubject$2", v2_2);
		newAnonR1.put("live/LiveSubject$3", v2_3);
		newAnonR1.put("live/LiveSubject$4", v2_4);

		AnonClassAligner.Result r1 = AnonClassAligner.align("live/LiveSubject", v2Host, oldAnon, newAnonR1);

		check("live/LiveSubject$1".equals(r1.renameMap.get("live/LiveSubject$2")), "轮次 1: Save($2) 对齐回 $1");
		check("live/LiveSubject$2".equals(r1.renameMap.get("live/LiveSubject$3")), "轮次 1: Delete($3) 对齐回 $2");
		check("live/LiveSubject$3".equals(r1.renameMap.get("live/LiveSubject$4")), "轮次 1: 位移的未加载类 Unused($4) 对齐回 $3");
		check("live/LiveSubject$4".equals(r1.renameMap.get("live/LiveSubject$1")), "轮次 1: 新增类 Other($1) 分配空闲号 $4");

		// 建立事务：将未加载类（$3 Unused 与 $4 Other）注册至 pending 与 cache
		HotSwapAgent.AlignmentTransaction tx1 = new HotSwapAgent.AlignmentTransaction("live.LiveSubject");
		for (String targetName : Arrays.asList("live/LiveSubject$3", "live/LiveSubject$4")) {
			byte[] b = r1.alignedAnonClasses.get(targetName);
			tx1.pendingAdds.put(targetName, b);
			tx1.cacheUpdates.put(targetName.replace('/', '.'), b);
		}
		tx1.commit();

		// 已加载类物理重定义（$1 Save, $2 Delete, 以及宿主）
		Class<?> classAnon1 = loader.loadClass("live.LiveSubject$1");
		Class<?> classAnon2 = loader.loadClass("live.LiveSubject$2");
		ClassDefinition dHost = new ClassDefinition(hostClass, r1.alignedHostBytes);
		ClassDefinition d1 = new ClassDefinition(classAnon1, r1.alignedAnonClasses.get("live/LiveSubject$1"));
		ClassDefinition d2 = new ClassDefinition(classAnon2, r1.alignedAnonClasses.get("live/LiveSubject$2"));

		inst.redefineClasses(dHost, d1, d2);
		HotSwapAgent.bytecodeCache.put("live.LiveSubject", r1.alignedHostBytes);
		HotSwapAgent.bytecodeCache.put("live.LiveSubject$1", r1.alignedAnonClasses.get("live/LiveSubject$1"));
		HotSwapAgent.bytecodeCache.put("live.LiveSubject$2", r1.alignedAnonClasses.get("live/LiveSubject$2"));

		// 验证 1：持有的旧实例逻辑正确升级，且捕获字段稳定
		check("SAVE_V2:CTX_SAVE_100".equals(oldSaveInstance.call()), "轮次 1: 旧 Save 实例成功执行 SAVE_V2 且保留捕获字段 CTX_SAVE_100");
		check("DELETE_V2:CTX_DEL_200".equals(oldDeleteInstance.call()), "轮次 1: 旧 Delete 实例成功执行 DELETE_V2 且保留捕获字段 CTX_DEL_200");

		// 验证 2：位移后的未加载类 (Unused) 首次加载时成功通过 pending 拦截
		check(AnnotationTransformer.pendingAlignedClasses.containsKey("live/LiveSubject$3"), "首次加载前: pendingAlignedClasses 已注册位移未加载类 $3");
		Method mUnused = hostClass.getMethod("makeUnused");
		@SuppressWarnings("unchecked")
		Callable<String> unusedInstance = (Callable<String>) mUnused.invoke(null);
		check("live.LiveSubject$3".equals(unusedInstance.getClass().getName()), "首次加载后: Unused 物理类名为 LiveSubject$3");
		check("UNUSED_V2".equals(unusedInstance.call()), "首次加载后: Unused 执行正确的 UNUSED_V2 逻辑（证明磁盘上覆盖的 Delete 字节码被成功拦截拦截）");
		check(!AnnotationTransformer.pendingAlignedClasses.containsKey("live/LiveSubject$3"), "消费后: pendingAlignedClasses 清除了 $3 键");

		// 验证 3：新增的 Other 类首次加载拦截
		Method mOther = hostClass.getMethod("makeOther");
		@SuppressWarnings("unchecked")
		Callable<String> otherInstance = (Callable<String>) mOther.invoke(null);
		check("live.LiveSubject$4".equals(otherInstance.getClass().getName()), "新类 Other 被分配并加载为 LiveSubject$4");
		check("OTHER_V2".equals(otherInstance.call()), "新类 Other 成功执行 OTHER_V2 逻辑");

		// --- 轮次 2：同一个 JVM 内执行第二轮热更 (V3) ---
		System.out.println("   --- 启动第二轮热更 (V2 -> V3) ---");
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outDir.getAbsolutePath(), fV3.getAbsolutePath());

		byte[] v3Host = Files.readAllBytes(new File(outDir, "live/LiveSubject.class").toPath());
		byte[] v3_1 = Files.readAllBytes(new File(outDir, "live/LiveSubject$1.class").toPath()); // Other
		byte[] v3_2 = Files.readAllBytes(new File(outDir, "live/LiveSubject$2.class").toPath()); // Save
		byte[] v3_3 = Files.readAllBytes(new File(outDir, "live/LiveSubject$3.class").toPath()); // Delete
		byte[] v3_4 = Files.readAllBytes(new File(outDir, "live/LiveSubject$4.class").toPath()); // Unused

		Map<String, byte[]> newAnonR2 = new HashMap<>();
		newAnonR2.put("live/LiveSubject$1", v3_1);
		newAnonR2.put("live/LiveSubject$2", v3_2);
		newAnonR2.put("live/LiveSubject$3", v3_3);
		newAnonR2.put("live/LiveSubject$4", v3_4);

		// 第二轮热更的旧侧必须严格从 bytecodeCache 读取（验证登记即写缓存机制）
		Map<String, byte[]> oldAnonFromCache = new HashMap<>();
		for (String k : Arrays.asList("live.LiveSubject$1", "live.LiveSubject$2", "live.LiveSubject$3", "live.LiveSubject$4")) {
			byte[] b = HotSwapAgent.bytecodeCache.get(k);
			check(b != null, "轮次 2 旧侧从 bytecodeCache 成功读取 " + k);
			oldAnonFromCache.put(k.replace('.', '/'), b);
		}

		AnonClassAligner.Result r2 = AnonClassAligner.align("live/LiveSubject", v3Host, oldAnonFromCache, newAnonR2);
		check("live/LiveSubject$1".equals(r2.renameMap.get("live/LiveSubject$2")), "轮次 2: Save 依然稳定对齐到 $1");
		check("live/LiveSubject$2".equals(r2.renameMap.get("live/LiveSubject$3")), "轮次 2: Delete 依然稳定对齐到 $2");
		check("live/LiveSubject$3".equals(r2.renameMap.get("live/LiveSubject$4")), "轮次 2: Unused 依然稳定对齐到 $3");
		check("live/LiveSubject$4".equals(r2.renameMap.get("live/LiveSubject$1")), "轮次 2: Other 依然稳定对齐到 $4");

		// 执行第二轮重定义
		Class<?> classAnon3 = loader.loadClass("live.LiveSubject$3");
		Class<?> classAnon4 = loader.loadClass("live.LiveSubject$4");
		ClassDefinition dHost2 = new ClassDefinition(hostClass, r2.alignedHostBytes);
		ClassDefinition d1_2 = new ClassDefinition(classAnon1, r2.alignedAnonClasses.get("live/LiveSubject$1"));
		ClassDefinition d2_2 = new ClassDefinition(classAnon2, r2.alignedAnonClasses.get("live/LiveSubject$2"));
		ClassDefinition d3_2 = new ClassDefinition(classAnon3, r2.alignedAnonClasses.get("live/LiveSubject$3"));
		ClassDefinition d4_2 = new ClassDefinition(classAnon4, r2.alignedAnonClasses.get("live/LiveSubject$4"));

		inst.redefineClasses(dHost2, d1_2, d2_2, d3_2, d4_2);

		// 验证最原始持有的旧 Save 实例在两轮热更后依然正确执行 V3 业务逻辑
		check("SAVE_V3:CTX_SAVE_100".equals(oldSaveInstance.call()), "轮次 2: 最原始旧 Save 实例成功执行 SAVE_V3 且保留捕获字段 CTX_SAVE_100");
		check("DELETE_V3:CTX_DEL_200".equals(oldDeleteInstance.call()), "轮次 2: 最原始旧 Delete 实例成功执行 DELETE_V3 且保留捕获字段 CTX_DEL_200");
		check("UNUSED_V3".equals(unusedInstance.call()), "轮次 2: Unused 实例成功执行 UNUSED_V3");
		check("OTHER_V3".equals(otherInstance.call()), "轮次 2: Other 实例成功执行 OTHER_V3");
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
		if (rc != 0) throw new RuntimeException("Command failed with exit " + rc);
	}

	static void deleteRecursively(File f) {
		if (f == null || !f.exists()) return;
		File[] children = f.listFiles();
		if (children != null) {
			for (File c : children) deleteRecursively(c);
		}
		f.delete();
	}
}
