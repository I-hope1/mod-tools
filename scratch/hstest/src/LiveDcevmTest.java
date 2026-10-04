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
 * 真实 JBR-21 + DCEVM 增强重定义环境下的端到端实例保活与对齐验证。
 *
 * 验证目标：
 * 1. 验证在真实 JVM 中，当匿名类因前置插入发生编号位移时，旧存活对象实例不会发生“逻辑被静默掉包/身份篡夺”；
 * 2. 验证 Aligner 重命名后的字节码在 DCEVM 下成功执行物理热更；
 * 3. 验证未加载类在初次实例化时通过 pendingAlignedClasses 加载期注入正确生效。
 */
public class LiveDcevmTest {

	static void check(boolean cond, String msg) {
		if (cond) {
			System.out.println("   [PASS] " + msg);
		} else {
			System.err.println("   [FAIL] " + msg);
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
			File srcV1Dir = new File(tempDir, "src_v1/live");
			File srcV2Dir = new File(tempDir, "src_v2/live");
			srcV1Dir.mkdirs();
			srcV2Dir.mkdirs();

			File outDir = new File(tempDir, "out");
			outDir.mkdirs();

			File fV1 = new File(srcV1Dir, "LiveSubject.java");
			File fV2 = new File(srcV2Dir, "LiveSubject.java");

			Files.writeString(fV1.toPath(),
				"package live;\n" +
				"import java.util.concurrent.Callable;\n" +
				"public class LiveSubject {\n" +
				"    public static Callable<String> makeSave() {\n" +
				"        return new Callable<String>() {\n" +
				"            public String call() { return \"SAVE_V1\"; }\n" +
				"        };\n" +
				"    }\n" +
				"    public static Callable<String> makeDelete() {\n" +
				"        return new Callable<String>() {\n" +
				"            public String call() { return \"DELETE_V1\"; }\n" +
				"        };\n" +
				"    }\n" +
				"}\n");

			Files.writeString(fV2.toPath(),
				"package live;\n" +
				"import java.util.concurrent.Callable;\n" +
				"public class LiveSubject {\n" +
				"    public static Callable<String> makeOther() {\n" +
				"        return new Callable<String>() {\n" +
				"            public String call() { return \"OTHER_V2\"; }\n" +
				"        };\n" +
				"    }\n" +
				"    public static Callable<String> makeSave() {\n" +
				"        return new Callable<String>() {\n" +
				"            public String call() { return \"SAVE_V2\"; }\n" +
				"        };\n" +
				"    }\n" +
				"    public static Callable<String> makeDelete() {\n" +
				"        return new Callable<String>() {\n" +
				"            public String call() { return \"DELETE_V2\"; }\n" +
				"        };\n" +
				"    }\n" +
				"}\n");

			// 编译 V1 到 outDir
			runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outDir.getAbsolutePath(), fV1.getAbsolutePath());

			// 使用自定义 ClassLoader 加载 V1 类
			URLClassLoader loader = new URLClassLoader(new URL[]{outDir.toURI().toURL()}, LiveDcevmTest.class.getClassLoader());
			Class<?> hostClass = loader.loadClass("live.LiveSubject");
			Method mSave = hostClass.getMethod("makeSave");
			Method mDelete = hostClass.getMethod("makeDelete");

			// 创建旧对象实例并持有其存活引用
			@SuppressWarnings("unchecked")
			Callable<String> oldSaveInstance = (Callable<String>) mSave.invoke(null);
			@SuppressWarnings("unchecked")
			Callable<String> oldDeleteInstance = (Callable<String>) mDelete.invoke(null);

			check("live.LiveSubject$1".equals(oldSaveInstance.getClass().getName()), "V1 中 Save 实例的物理类名为 LiveSubject$1");
			check("live.LiveSubject$2".equals(oldDeleteInstance.getClass().getName()), "V1 中 Delete 实例的物理类名为 LiveSubject$2");
			check("SAVE_V1".equals(oldSaveInstance.call()), "热更前调用 Save 实例返回 SAVE_V1");
			check("DELETE_V1".equals(oldDeleteInstance.call()), "热更前调用 Delete 实例返回 DELETE_V1");

			// 记录旧侧字节码缓存（代表热更前 JVM 正在运行的已加载版本）
			byte[] v1_1 = Files.readAllBytes(new File(outDir, "live/LiveSubject$1.class").toPath());
			byte[] v1_2 = Files.readAllBytes(new File(outDir, "live/LiveSubject$2.class").toPath());

			// 模拟真实编译：javac 覆盖输出目录 outDir，生成 V2 产物
			// 此时磁盘上 LiveSubject$1 为 Other, $2 为 Save, $3 为 Delete (发生位移)
			runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outDir.getAbsolutePath(), fV2.getAbsolutePath());

			byte[] v2Host = Files.readAllBytes(new File(outDir, "live/LiveSubject.class").toPath());
			byte[] v2_1 = Files.readAllBytes(new File(outDir, "live/LiveSubject$1.class").toPath()); // Other (on disk)
			byte[] v2_2 = Files.readAllBytes(new File(outDir, "live/LiveSubject$2.class").toPath()); // Save (on disk)
			byte[] v2_3 = Files.readAllBytes(new File(outDir, "live/LiveSubject$3.class").toPath()); // Delete (on disk)

			Map<String, byte[]> oldAnon = new HashMap<>();
			oldAnon.put("live/LiveSubject$1", v1_1);
			oldAnon.put("live/LiveSubject$2", v1_2);

			Map<String, byte[]> newAnon = new HashMap<>();
			newAnon.put("live/LiveSubject$1", v2_1); // Other
			newAnon.put("live/LiveSubject$2", v2_2); // Save
			newAnon.put("live/LiveSubject$3", v2_3); // Delete

			AnonClassAligner.Result alignRes = AnonClassAligner.align("live/LiveSubject", v2Host, oldAnon, newAnon);
			check("live/LiveSubject$1".equals(alignRes.renameMap.get("live/LiveSubject$2")), "Aligner 将新 $2(Save) 成功重映射到旧 $1");
			check("live/LiveSubject$2".equals(alignRes.renameMap.get("live/LiveSubject$3")), "Aligner 将新 $3(Delete) 成功重映射到旧 $2");
			check("live/LiveSubject$3".equals(alignRes.renameMap.get("live/LiveSubject$1")), "Aligner 将新增的 $1(Other) 重映射到空闲号 $3");

			// 注册未加载类 $3 到 pending
			byte[] remappedOtherBytes = alignRes.alignedAnonClasses.get("live/LiveSubject$3");
			AnnotationTransformer.pendingAlignedClasses.put("live/LiveSubject$3", remappedOtherBytes);
			AnnotationTransformer.pendingAlignedClasses.put("live.LiveSubject$3", remappedOtherBytes);

			// 执行已加载类的真实 JVM 热更重定义
			Class<?> classAnon1 = loader.loadClass("live.LiveSubject$1");
			Class<?> classAnon2 = loader.loadClass("live.LiveSubject$2");
			ClassDefinition defHost = new ClassDefinition(hostClass, alignRes.alignedHostBytes);
			ClassDefinition def1 = new ClassDefinition(classAnon1, alignRes.alignedAnonClasses.get("live/LiveSubject$1"));
			ClassDefinition def2 = new ClassDefinition(classAnon2, alignRes.alignedAnonClasses.get("live/LiveSubject$2"));

			inst.redefineClasses(defHost, def1, def2);
			System.out.println("   [DCEVM] 成功通过 inst.redefineClasses 重定义 LiveSubject, LiveSubject$1, LiveSubject$2");

			// 关键验证：验证此前已经创建并存活的旧对象实例！
			// 若未对齐，LiveSubject$1 会被覆盖为 Other，调用将得到 OTHER_V2！
			String saveCallAfterHotSwap = oldSaveInstance.call();
			check("SAVE_V2".equals(saveCallAfterHotSwap), "核心断言：热更前创建的旧 Save 实例在位移后依然正确执行 SAVE_V2（未被 Other 掉包）");

			String deleteCallAfterHotSwap = oldDeleteInstance.call();
			check("DELETE_V2".equals(deleteCallAfterHotSwap), "核心断言：热更前创建的旧 Delete 实例在位移后依然正确执行 DELETE_V2");

			// 验证新加载类通过 pending 拦截加载
			check(AnnotationTransformer.pendingAlignedClasses.containsKey("live/LiveSubject$3"), "加载前 pendingAlignedClasses 已注册新类 $3");

			Method mOther = hostClass.getMethod("makeOther");
			@SuppressWarnings("unchecked")
			Callable<String> otherInstance = (Callable<String>) mOther.invoke(null);
			check(!AnnotationTransformer.pendingAlignedClasses.containsKey("live/LiveSubject$3"), "加载后 pendingAlignedClasses 自动消费清除新类 $3");
			check("live.LiveSubject$3".equals(otherInstance.getClass().getName()), "新类被分配并加载为 LiveSubject$3");
			check("OTHER_V2".equals(otherInstance.call()), "新类实例成功执行 OTHER_V2 业务逻辑");

			// 验证宿主类新方法与对齐后的 Save
			@SuppressWarnings("unchecked")
			Callable<String> newSaveInstance = (Callable<String>) mSave.invoke(null);
			check("SAVE_V2".equals(newSaveInstance.call()), "热更后新创建的 Save 实例执行 SAVE_V2");

			System.out.println("\n>>> [DCEVM E2E ALL PASSED] 真实 DCEVM 下实例保活与匿名类对齐验证全部通过！<<<");
		} finally {
			AnnotationTransformer.pendingAlignedClasses.clear();
			deleteRecursively(tempDir);
		}
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
