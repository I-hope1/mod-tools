import nipx.LambdaAligner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 AnonClassHasher 与 LambdaAligner 对匿名类过度归一化碰撞的解决效果（JUnit 化）。
 *
 * <p>场景：两个 lambda 分别实例化不同的匿名类（Save 调 doSave，Delete 调 doDelete）。
 * 在前面插入一个新 lambda 后，旧 lambda 编译序号整体位移。
 * 断言：通过 AnonClassHasher 的内容哈希折入，Save 与 Delete 正确认领各自旧名，绝不对调。</p>
 */
public class AnonClassTest {

	@Test
	void saveAndDeleteAnonClassesKeepTheirIdentity(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		Path outV1 = Files.createDirectories(tmp.resolve("v1"));
		Path outV2 = Files.createDirectories(tmp.resolve("v2"));

		File src1 = findFile("anon/v1/testAnon/AnonCase.java");
		File src2 = findFile("anon/v2/testAnon/AnonCase.java");

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.toString(), src1.getAbsolutePath());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.toString(), src2.getAbsolutePath());

		byte[] v1 = Files.readAllBytes(outV1.resolve("testAnon/AnonCase.class"));
		byte[] v2 = Files.readAllBytes(outV2.resolve("testAnon/AnonCase.class"));

		Function<String, byte[]> oldResolver = name -> {
			try {
				Path f = outV1.resolve(name.replace('.', '/') + ".class");
				return Files.exists(f) ? Files.readAllBytes(f) : null;
			} catch (Exception e) {
				return null;
			}
		};

		Function<String, byte[]> newResolver = name -> {
			try {
				Path f = outV2.resolve(name.replace('.', '/') + ".class");
				return Files.exists(f) ? Files.readAllBytes(f) : null;
			} catch (Exception e) {
				return null;
			}
		};

		LambdaAligner.LAST_STATS = null;
		byte[] aligned = LambdaAligner.align(v1, v2, oldResolver, newResolver);

		assertNotNull(aligned, "aligned 产物非空");

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

		assertEquals("lambda$setup$0", saveMethodName,
			"Save 方法保持了旧名 lambda$setup$0 (实际: " + saveMethodName + ")");
		assertEquals("lambda$setup$1", deleteMethodName,
			"Delete 方法保持了旧名 lambda$setup$1 (实际: " + deleteMethodName + ")");
		assertTrue(saveMethodName != null && !saveMethodName.equals(deleteMethodName),
			"Save 与 Delete 没有对调且各自保持独立身份");
	}

	static String javac21() {
		String j = System.getProperty("hstest.javac21");
		if (j != null && new File(j).exists()) return j;
		j = System.getenv("HSTEST_JAVAC21");
		if (j != null && new File(j).exists()) return j;
		j = "F:/files/java/jdks/openjdk-21.0.2/bin/javac.exe";
		return new File(j).exists() ? j : "javac";
	}

	static File findFile(String rel) {
		File f = new File(rel);
		if (f.exists()) return f;
		File f2 = new File("scratch/hstest", rel);
		if (f2.exists()) return f2;
		return f;
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
