import nipx.LambdaAligner;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.function.Function;

/**
 * 验证 AnonClassHasher 与 LambdaAligner 对匿名类过度归一化碰撞的解决效果。
 *
 * <p>场景：两个 lambda 分别实例化不同的匿名类（Save 调 doSave，Delete 调 doDelete）。
 * 在前面插入一个新 lambda 后，旧 lambda 编译序号整体位移。
 * 断言：通过 AnonClassHasher 的内容哈希折入，Save 与 Delete 正确认领各自旧名，绝不对调。</p>
 */
public class AnonClassTest {
	static int passed = 0, failed = 0;

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
		System.out.println("=== AnonClassTest: 验证匿名类内容哈希防止 Save/Delete 静默对调 ===");

		String javac = System.getenv("HSTEST_JAVAC21");
		if (javac == null) javac = "F:/files/java/jdks/openjdk-21.0.2/bin/javac.exe";
		if (!new File(javac).exists()) javac = "javac";

		File outV1 = findFile("fx/anon_v1");
		File outV2 = findFile("fx/anon_v2");
		outV1.mkdirs();
		outV2.mkdirs();

		File src1 = findFile("anon/v1/testAnon/AnonCase.java");
		File src2 = findFile("anon/v2/testAnon/AnonCase.java");

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.getAbsolutePath(), src1.getAbsolutePath());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.getAbsolutePath(), src2.getAbsolutePath());

		byte[] v1 = Files.readAllBytes(new File(outV1, "testAnon/AnonCase.class").toPath());
		byte[] v2 = Files.readAllBytes(new File(outV2, "testAnon/AnonCase.class").toPath());

		Function<String, byte[]> oldResolver = name -> {
			try {
				File f = new File(outV1, name.replace('.', '/') + ".class");
				return f.exists() ? Files.readAllBytes(f.toPath()) : null;
			} catch (Exception e) {
				return null;
			}
		};

		Function<String, byte[]> newResolver = name -> {
			try {
				File f = new File(outV2, name.replace('.', '/') + ".class");
				return f.exists() ? Files.readAllBytes(f.toPath()) : null;
			} catch (Exception e) {
				return null;
			}
		};

		LambdaAligner.LAST_STATS = null;
		byte[] aligned = LambdaAligner.align(v1, v2, oldResolver, newResolver);

		check(aligned != null, "aligned 产物非空");

		ClassNode cn = new ClassNode();
		new ClassReader(aligned).accept(cn, 0);

		// 在对齐后的类中，查找哪个方法实例化了调用 doSave 的匿名类，哪个实例化了调用 doDelete 的匿名类
		String saveMethodName = null;
		String deleteMethodName = null;

		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$setup$")) continue;
			for (AbstractInsnNode insn : mn.instructions) {
				if (insn instanceof TypeInsnNode tin && tin.desc.startsWith("testAnon/AnonCase$")) {
					byte[] anonBytes = newResolver.apply(tin.desc);
					if (anonBytes != null) {
						ClassNode anonCn = new ClassNode();
						new ClassReader(anonBytes).accept(anonCn, 0);
						for (MethodNode amn : anonCn.methods) {
							if ("run".equals(amn.name)) {
								for (AbstractInsnNode ainsn : amn.instructions) {
									if (ainsn instanceof MethodInsnNode min) {
										if ("doSave".equals(min.name)) saveMethodName = mn.name;
										if ("doDelete".equals(min.name)) deleteMethodName = mn.name;
									}
								}
							}
						}
					}
				}
			}
		}

		check("lambda$setup$0".equals(saveMethodName),
			"Save 方法保持了旧名 lambda$setup$0 (实际: " + saveMethodName + ")");
		check("lambda$setup$1".equals(deleteMethodName),
			"Delete 方法保持了旧名 lambda$setup$1 (实际: " + deleteMethodName + ")");
		check(saveMethodName != null && !saveMethodName.equals(deleteMethodName),
			"Save 与 Delete 没有对调且各自保持独立身份");

		if (failed > 0) {
			throw new AssertionError("AnonClassTest failed: passed=" + passed + ", failed=" + failed);
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
		if (rc != 0) throw new RuntimeException("Command failed with exit " + rc + ": " + Arrays.toString(cmd));
	}
}
