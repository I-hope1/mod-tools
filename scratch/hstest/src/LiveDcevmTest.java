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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 真实 JBR-21 + DCEVM 增强重定义环境下的端到端实测验证套件：
 *
 * <p>测试运行环境：JetBrains Runtime 21.0.9+1 (build 21.0.9+1-b1163.86)
 * 参数：-XX:+AllowEnhancedClassRedefinition -javaagent:hotswap-agent.jar</p>
 *
 * <ol>
 *   <li><b>对照组 (Control Group)</b>：
 *       关闭对齐器直接用原始位移产物重定义，验证旧实例确实被静默掉包（证明缺陷的客观存在与复现）。</li>
 *   <li><b>实验组 (Experiment Group - 对齐器生效)</b>：
 *       验证热更前持有的旧实例逻辑与捕获字段（Captured State）完全保活（未被篡夺）。</li>
 *   <li><b>位移后的未加载类 (Unloaded Displaced Class)</b>：
 *       类在热更前从未被加载，磁盘文件已被 javac 覆盖为错位内容，验证首次调用时通过 pending 拦截成功加载正确版本。</li>
 *   <li><b>并发首次加载竞态消除 (Pre-Registration Concurrency)</b>：
 *       验证通过前置乐观登记 (preRegister)，重定义期间并发线程首次加载类绝不会读到磁盘错位内容。</li>
 *   <li><b>孤儿匿名类（策略 A）与编号避开验证</b>：
 *       删除匿名类后，持有的旧实例依然可用；新增类自动避开孤儿编号，分配全新序号。</li>
 *   <li><b>第二轮再次发生位移 (Round 2 Shift with bytecodeCache)</b>：
 *       第二轮热更再次在最前插入新类，旧侧读自 bytecodeCache，验证连续位移无累积漂移。</li>
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
		System.out.println("=== LiveDcevmTest: 真实 JBR-21 (21.0.9+1) DCEVM 端到端实测套件 ===");

		Instrumentation inst = HotSwapAgent.getInstrumentation();
		if (inst == null || !inst.isRedefineClassesSupported()) {
			System.out.println("   [SKIP] 当前 JVM 不支持增强类重定义或未挂载 HotSwapAgent，跳过 E2E 测试。");
			return;
		}

		String javac = System.getenv("HSTEST_JAVAC21");
		if (javac == null) javac = "F:/files/java/jdks/openjdk-21.0.2/bin/javac.exe";
		if (!new File(javac).exists()) javac = "javac";

		File tempDir = Files.createTempDirectory("dcevm_live_test").toFile();
		try {
			// Part 1: 对照组测试（不对齐时确实错）
			testControlGroupWithoutAligner(javac, tempDir, inst);

			// Part 2: 实验组测试（捕获状态保活 + 位移未加载类拦截 + 并发首次加载竞态消除 + 第二轮再次位移）
			testExperimentGroupWithAligner(javac, tempDir, inst);

			// Part 3: 孤儿匿名类（策略 A）与编号避开实测
			testOrphanAndFreshNumbering(javac, tempDir, inst);

			System.out.println("\n>>> [DCEVM E2E ALL PASSED] 真实 DCEVM 下对照组、实验组、并发竞态消除与孤儿避让验证全部通过！(通过=" + passed + ", 失败=" + failed + ") <<<");
		} finally {
			AnnotationTransformer.pendingAlignedClasses.clear();
			deleteRecursively(tempDir);
		}
	}

	// --------------------------------------------------------------------------------
	// Part 1: 对照组（不进行匿名类对齐，直接使用 javac 原始位移字节码重定义）
	// --------------------------------------------------------------------------------
	static void testControlGroupWithoutAligner(String javac, File baseDir, Instrumentation inst) throws Exception {
		System.out.println("\n--- [对照组] 未使用对齐器：证实 DCEVM 旧实例被篡夺 (JBR 21.0.9+1) ---");

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

		Class<?> classAnon1 = loader.loadClass("ctrl.CtrlSubject$1");

		// 对照组操作：不走 Aligner，直接将 javac 原始字节码重定义进 JVM
		ClassDefinition defHost = new ClassDefinition(hostClass, rawV2Host);
		ClassDefinition def1 = new ClassDefinition(classAnon1, rawV2Anon1);
		inst.redefineClasses(defHost, def1);

		// 核心实测断言：由于 CtrlSubject$1 字节码被物理重定义为 Other 的逻辑，
		// 原本持有的旧 Save 实例在无对齐器保护下，逻辑被彻底篡夺为 OTHER_HIJACKED！
		String hijackedResult = oldSaveInstance.call();
		check("OTHER_HIJACKED".equals(hijackedResult), "对照组证实：不对齐时，旧 Save 实例确实被 Other 逻辑篡夺（返回 OTHER_HIJACKED）！");
	}

	// --------------------------------------------------------------------------------
	// Part 2: 实验组（对齐器保护：捕获状态保活 + 位移后未加载类拦截 + 并发首次加载竞态消除 + 第二轮再次位移）
	// --------------------------------------------------------------------------------
	static void testExperimentGroupWithAligner(String javac, File baseDir, Instrumentation inst) throws Exception {
		System.out.println("\n--- [实验组] 对齐器保护：捕获字段保活、未加载类拦截、并发无竞态与第二轮再次位移 ---");

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

		// V3: 第二轮修改：最前方再插入一个 makeHeader！发生二次累积位移！
		// 此时 raw javac 编号：
		// $1: Header
		// $2: Other
		// $3: Save
		// $4: Delete
		// $5: Unused
		Files.writeString(fV3.toPath(),
			"package live;\n" +
			"import java.util.concurrent.Callable;\n" +
			"public class LiveSubject {\n" +
			"    public static Callable<String> makeHeader() {\n" +
			"        return new Callable<String>() {\n" +
			"            public String call() { return \"HEADER_V3\"; }\n" +
			"        };\n" +
			"    }\n" +
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

		// 事务建立与乐观前置登记 (preRegister)
		HotSwapAgent.AlignmentTransaction tx1 = new HotSwapAgent.AlignmentTransaction("live.LiveSubject");
		for (String targetName : Arrays.asList("live/LiveSubject$3", "live/LiveSubject$4")) {
			byte[] b = r1.alignedAnonClasses.get(targetName);
			tx1.pendingAdds.put(targetName, b);
			tx1.cacheUpdates.put(targetName.replace('/', '.'), b);
		}
		// 关键断言：在重定义前 preRegister 立即写入 pending，消除并发加载竞态窗口
		tx1.preRegister();
		check(AnnotationTransformer.pendingAlignedClasses.containsKey("live/LiveSubject$3"), "前置登记 (preRegister): 重定义执行前 pending 已注册位移未加载类 $3");
		check(AnnotationTransformer.pendingAlignedClasses.containsKey("live/LiveSubject$4"), "前置登记 (preRegister): 重定义执行前 pending 已注册新类 $4");

		// 并发验证：在重定义期间启动后台线程首次加载未加载类 LiveSubject$3 (Unused)
		ExecutorService concurrentLoaderPool = Executors.newSingleThreadExecutor();
		AtomicReference<String> concurrentLoadedVal = new AtomicReference<>();
		AtomicReference<Callable<String>> concurrentUnusedRef = new AtomicReference<>();
		AtomicReference<Throwable> concurrentError = new AtomicReference<>();
		Future<?> concurrentTask = concurrentLoaderPool.submit(() -> {
			try {
				// makeUnused 在 V1 中存在但类从未被加载，磁盘已被覆盖成 Delete
				// 由于 preRegister 已完成，此首次加载绝不会拿到磁盘的 Delete 错位内容
				Method mu = hostClass.getMethod("makeUnused");
				@SuppressWarnings("unchecked")
				Callable<String> un = (Callable<String>) mu.invoke(null);
				concurrentUnusedRef.set(un);
				concurrentLoadedVal.set(un.call());
			} catch (Throwable t) {
				concurrentError.set(t);
			}
		});

		// 已加载类物理重定义（$1 Save, $2 Delete, 以及宿主）
		Class<?> classAnon1 = loader.loadClass("live.LiveSubject$1");
		Class<?> classAnon2 = loader.loadClass("live.LiveSubject$2");
		ClassDefinition dHost = new ClassDefinition(hostClass, r1.alignedHostBytes);
		ClassDefinition d1 = new ClassDefinition(classAnon1, r1.alignedAnonClasses.get("live/LiveSubject$1"));
		ClassDefinition d2 = new ClassDefinition(classAnon2, r1.alignedAnonClasses.get("live/LiveSubject$2"));

		inst.redefineClasses(dHost, d1, d2);
		tx1.commit();

		HotSwapAgent.bytecodeCache.put("live.LiveSubject", r1.alignedHostBytes);
		HotSwapAgent.bytecodeCache.put("live.LiveSubject$1", r1.alignedAnonClasses.get("live/LiveSubject$1"));
		HotSwapAgent.bytecodeCache.put("live.LiveSubject$2", r1.alignedAnonClasses.get("live/LiveSubject$2"));

		concurrentTask.get(5, TimeUnit.SECONDS);
		concurrentLoaderPool.shutdown();

		if (concurrentError.get() != null) {
			concurrentError.get().printStackTrace();
		}
		check(concurrentError.get() == null, "并发首次加载: 线程执行未抛出类结构或加载异常");
		check("UNUSED_V2".equals(concurrentLoadedVal.get()), "并发首次加载: 前置登记 (preRegister) 确保并发线程首次加载读到正确对齐字节码 (UNUSED_V2)，无竞态错位！");

		Callable<String> unusedInstance = concurrentUnusedRef.get();

		// 验证 1：持有的旧实例逻辑正确升级，且捕获字段稳定
		check("SAVE_V2:CTX_SAVE_100".equals(oldSaveInstance.call()), "轮次 1: 旧 Save 实例成功执行 SAVE_V2 且保留捕获字段 CTX_SAVE_100");
		check("DELETE_V2:CTX_DEL_200".equals(oldDeleteInstance.call()), "轮次 1: 旧 Delete 实例成功执行 DELETE_V2 且保留捕获字段 CTX_DEL_200");

		// 验证 2：宿主重定义后新增的 makeOther 调用与首次加载
		Method mOther = hostClass.getMethod("makeOther");
		@SuppressWarnings("unchecked")
		Callable<String> otherInstance = (Callable<String>) mOther.invoke(null);
		check("live.LiveSubject$4".equals(otherInstance.getClass().getName()), "首次加载后: Other 物理类名为 LiveSubject$4");
		check("OTHER_V2".equals(otherInstance.call()), "首次加载后: Other 执行正确的 OTHER_V2 逻辑");

		// --- 轮次 2：第二轮再次位移 (头部再插入 Header) ---
		System.out.println("   --- 启动第二轮再次位移热更 (V2 -> V3) ---");
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outDir.getAbsolutePath(), fV3.getAbsolutePath());

		byte[] v3Host = Files.readAllBytes(new File(outDir, "live/LiveSubject.class").toPath());
		byte[] v3_1 = Files.readAllBytes(new File(outDir, "live/LiveSubject$1.class").toPath()); // Header
		byte[] v3_2 = Files.readAllBytes(new File(outDir, "live/LiveSubject$2.class").toPath()); // Other
		byte[] v3_3 = Files.readAllBytes(new File(outDir, "live/LiveSubject$3.class").toPath()); // Save
		byte[] v3_4 = Files.readAllBytes(new File(outDir, "live/LiveSubject$4.class").toPath()); // Delete
		byte[] v3_5 = Files.readAllBytes(new File(outDir, "live/LiveSubject$5.class").toPath()); // Unused

		Map<String, byte[]> newAnonR2 = new HashMap<>();
		newAnonR2.put("live/LiveSubject$1", v3_1);
		newAnonR2.put("live/LiveSubject$2", v3_2);
		newAnonR2.put("live/LiveSubject$3", v3_3);
		newAnonR2.put("live/LiveSubject$4", v3_4);
		newAnonR2.put("live/LiveSubject$5", v3_5);

		// 第二轮热更的旧侧必须严格从 bytecodeCache 读取（验证登记即写缓存机制）
		Map<String, byte[]> oldAnonFromCache = new HashMap<>();
		for (String k : Arrays.asList("live.LiveSubject$1", "live.LiveSubject$2", "live.LiveSubject$3", "live.LiveSubject$4")) {
			byte[] b = HotSwapAgent.bytecodeCache.get(k);
			check(b != null, "轮次 2 旧侧从 bytecodeCache 成功读取 " + k);
			oldAnonFromCache.put(k.replace('.', '/'), b);
		}

		AnonClassAligner.Result r2 = AnonClassAligner.align("live/LiveSubject", v3Host, oldAnonFromCache, newAnonR2);
		check("live/LiveSubject$1".equals(r2.renameMap.get("live/LiveSubject$3")), "轮次 2: 再次右移后 Save 依然精准对齐到 $1");
		check("live/LiveSubject$2".equals(r2.renameMap.get("live/LiveSubject$4")), "轮次 2: 再次右移后 Delete 依然精准对齐到 $2");
		check("live/LiveSubject$3".equals(r2.renameMap.get("live/LiveSubject$5")), "轮次 2: 再次右移后 Unused 依然精准对齐到 $3");
		check("live/LiveSubject$4".equals(r2.renameMap.get("live/LiveSubject$2")), "轮次 2: 再次右移后 Other 依然精准对齐到 $4");
		check("live/LiveSubject$5".equals(r2.renameMap.get("live/LiveSubject$1")), "轮次 2: 全新头部类 Header 分配空闲号 $5");

		// 前置登记未加载类 $5
		HotSwapAgent.AlignmentTransaction tx2 = new HotSwapAgent.AlignmentTransaction("live.LiveSubject");
		tx2.pendingAdds.put("live/LiveSubject$5", r2.alignedAnonClasses.get("live/LiveSubject$5"));
		tx2.cacheUpdates.put("live.LiveSubject$5", r2.alignedAnonClasses.get("live/LiveSubject$5"));
		tx2.preRegister();

		// 执行第二轮重定义
		Class<?> classAnon3 = loader.loadClass("live.LiveSubject$3");
		Class<?> classAnon4 = loader.loadClass("live.LiveSubject$4");
		ClassDefinition dHost2 = new ClassDefinition(hostClass, r2.alignedHostBytes);
		ClassDefinition d1_2 = new ClassDefinition(classAnon1, r2.alignedAnonClasses.get("live/LiveSubject$1"));
		ClassDefinition d2_2 = new ClassDefinition(classAnon2, r2.alignedAnonClasses.get("live/LiveSubject$2"));
		ClassDefinition d3_2 = new ClassDefinition(classAnon3, r2.alignedAnonClasses.get("live/LiveSubject$3"));
		ClassDefinition d4_2 = new ClassDefinition(classAnon4, r2.alignedAnonClasses.get("live/LiveSubject$4"));

		inst.redefineClasses(dHost2, d1_2, d2_2, d3_2, d4_2);
		tx2.commit();

		// 验证最原始持有的旧 Save 实例在两次位移热更后依然正确执行 V3 业务逻辑
		check("SAVE_V3:CTX_SAVE_100".equals(oldSaveInstance.call()), "轮次 2: 原始 Save 实例在两次位移后依然正确执行 SAVE_V3 且保留捕获字段 CTX_SAVE_100");
		check("DELETE_V3:CTX_DEL_200".equals(oldDeleteInstance.call()), "轮次 2: 原始 Delete 实例在两次位移后依然正确执行 DELETE_V3 且保留捕获字段 CTX_DEL_200");
		check("UNUSED_V3".equals(unusedInstance.call()), "轮次 2: Unused 实例成功执行 UNUSED_V3");

		Method mHeader = hostClass.getMethod("makeHeader");
		@SuppressWarnings("unchecked")
		Callable<String> headerInstance = (Callable<String>) mHeader.invoke(null);
		check("live.LiveSubject$5".equals(headerInstance.getClass().getName()), "轮次 2: Header 成功加载为 LiveSubject$5");
		check("HEADER_V3".equals(headerInstance.call()), "轮次 2: Header 成功执行 HEADER_V3 业务逻辑");
	}

	// --------------------------------------------------------------------------------
	// Part 3: 孤儿匿名类（策略 A）与避开孤儿编号实测
	// --------------------------------------------------------------------------------
	static void testOrphanAndFreshNumbering(String javac, File baseDir, Instrumentation inst) throws Exception {
		System.out.println("\n--- [孤儿实测] 策略 A：删除匿名类后旧实例保活，且新增类避开孤儿编号 ---");

		File orphDir = new File(baseDir, "orphan");
		File srcV1 = new File(orphDir, "src_v1/orph");
		File srcV2 = new File(orphDir, "src_v2/orph");
		File outDir = new File(orphDir, "out");
		srcV1.mkdirs();
		srcV2.mkdirs();
		outDir.mkdirs();

		File fV1 = new File(srcV1, "OrphanSubject.java");
		File fV2 = new File(srcV2, "OrphanSubject.java");

		// V1: $1=TaskA, $2=TaskB
		Files.writeString(fV1.toPath(),
			"package orph;\n" +
			"import java.util.concurrent.Callable;\n" +
			"public class OrphanSubject {\n" +
			"    public static Callable<String> makeA() {\n" +
			"        return new Callable<String>() {\n" +
			"            public String call() { return \"TASK_A_V1\"; }\n" +
			"        };\n" +
			"    }\n" +
			"    public static Callable<String> makeB() {\n" +
			"        return new Callable<String>() {\n" +
			"            public String call() { return \"TASK_B_V1\"; }\n" +
			"        };\n" +
			"    }\n" +
			"}\n");

		// V2: 删除 makeA（使 $1 成为孤儿！），保留 makeB，并新增 makeC！
		// 在 raw V2 中：$1 为 TaskB，$2 为 TaskC！
		Files.writeString(fV2.toPath(),
			"package orph;\n" +
			"import java.util.concurrent.Callable;\n" +
			"public class OrphanSubject {\n" +
			"    public static Callable<String> makeB() {\n" +
			"        return new Callable<String>() {\n" +
			"            public String call() { return \"TASK_B_V2\"; }\n" +
			"        };\n" +
			"    }\n" +
			"    public static Callable<String> makeC() {\n" +
			"        return new Callable<String>() {\n" +
			"            public String call() { return \"TASK_C_V2\"; }\n" +
			"        };\n" +
			"    }\n" +
			"}\n");

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outDir.getAbsolutePath(), fV1.getAbsolutePath());

		URLClassLoader loader = new URLClassLoader(new URL[]{outDir.toURI().toURL()}, LiveDcevmTest.class.getClassLoader());
		Class<?> hostClass = loader.loadClass("orph.OrphanSubject");
		Method mA = hostClass.getMethod("makeA");
		Method mB = hostClass.getMethod("makeB");

		@SuppressWarnings("unchecked")
		Callable<String> oldAInstance = (Callable<String>) mA.invoke(null);
		@SuppressWarnings("unchecked")
		Callable<String> oldBInstance = (Callable<String>) mB.invoke(null);

		check("orph.OrphanSubject$1".equals(oldAInstance.getClass().getName()), "孤儿实测 V1: TaskA 物理类型为 OrphanSubject$1");
		check("orph.OrphanSubject$2".equals(oldBInstance.getClass().getName()), "孤儿实测 V1: TaskB 物理类型为 OrphanSubject$2");
		check("TASK_A_V1".equals(oldAInstance.call()), "孤儿实测 V1: 调用 TaskA 返回 TASK_A_V1");
		check("TASK_B_V1".equals(oldBInstance.call()), "孤儿实测 V1: 调用 TaskB 返回 TASK_B_V1");

		byte[] v1_1 = Files.readAllBytes(new File(outDir, "orph/OrphanSubject$1.class").toPath());
		byte[] v1_2 = Files.readAllBytes(new File(outDir, "orph/OrphanSubject$2.class").toPath());

		Map<String, byte[]> oldAnon = new HashMap<>();
		oldAnon.put("orph/OrphanSubject$1", v1_1);
		oldAnon.put("orph/OrphanSubject$2", v1_2);

		// 编译 V2 并执行对齐
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outDir.getAbsolutePath(), fV2.getAbsolutePath());

		byte[] v2Host = Files.readAllBytes(new File(outDir, "orph/OrphanSubject.class").toPath());
		byte[] v2_1 = Files.readAllBytes(new File(outDir, "orph/OrphanSubject$1.class").toPath()); // TaskB
		byte[] v2_2 = Files.readAllBytes(new File(outDir, "orph/OrphanSubject$2.class").toPath()); // TaskC

		Map<String, byte[]> newAnon = new HashMap<>();
		newAnon.put("orph/OrphanSubject$1", v2_1);
		newAnon.put("orph/OrphanSubject$2", v2_2);

		AnonClassAligner.Result rOrph = AnonClassAligner.align("orph/OrphanSubject", v2Host, oldAnon, newAnon);

		check(rOrph.orphanOldClasses.contains("orph/OrphanSubject$1"), "孤儿识别: Aligner 准确将已删除的 TaskA 标记为孤儿 (OrphanSubject$1)");
		check("orph/OrphanSubject$2".equals(rOrph.renameMap.get("orph/OrphanSubject$1")), "孤儿重映射: 存活的 TaskB($1) 精确对齐回旧号 $2");
		// 关键断言：新增的 TaskC 分配时，必须避开孤儿类占用过的 $1 和存活类 $2，分配给全新编号 $3！
		check("orph/OrphanSubject$3".equals(rOrph.renameMap.get("orph/OrphanSubject$2")), "编号避开孤儿: 新增的 TaskC 分配到全新编号 $3，避开孤儿 $1 占用的槽位！");

		// 对于全新分配且磁盘上尚无对应 .class 文件的新编号类，对齐后的字节码写入输出目录供 ClassLoader 读取
		byte[] cBytes = rOrph.alignedAnonClasses.get("orph/OrphanSubject$3");
		Files.write(new File(outDir, "orph/OrphanSubject$3.class").toPath(), cBytes);
		AnnotationTransformer.pendingAlignedClasses.put("orph/OrphanSubject$3", cBytes);
		AnnotationTransformer.pendingAlignedClasses.put("orph.OrphanSubject$3", cBytes);

		// 重定义宿主与存活的 TaskB ($2)
		Class<?> classB = loader.loadClass("orph.OrphanSubject$2");
		ClassDefinition dHost = new ClassDefinition(hostClass, rOrph.alignedHostBytes);
		ClassDefinition dB = new ClassDefinition(classB, rOrph.alignedAnonClasses.get("orph/OrphanSubject$2"));

		inst.redefineClasses(dHost, dB);

		// 核心实测断言：
		// 1. 策略 A 内存保活：旧 A 实例虽然在源码中被删除，但在 JVM 内存中依然可以正常运行，返回 TASK_A_V1，不崩溃！
		check("TASK_A_V1".equals(oldAInstance.call()), "策略 A 实测: 源码中被删除的孤儿实例 oldA 依然在 JVM 内存中正常工作 (TASK_A_V1)！");
		// 2. TaskB 升级成功
		check("TASK_B_V2".equals(oldBInstance.call()), "存活升级: TaskB 实例升级为 TASK_B_V2！");
		// 3. TaskC 成功加载为 $3
		Method mC = hostClass.getMethod("makeC");
		@SuppressWarnings("unchecked")
		Callable<String> cInstance = (Callable<String>) mC.invoke(null);
		check("orph.OrphanSubject$3".equals(cInstance.getClass().getName()), "新类加载: TaskC 成功作为 OrphanSubject$3 加载运行");
		check("TASK_C_V2".equals(cInstance.call()), "新类执行: TaskC 成功执行 TASK_C_V2 业务逻辑！");
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
