import nipx.LambdaAligner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class CompeteDeleteTest {

	static ClassNode parse(byte[] b) {
		ClassNode cn = new ClassNode();
		new ClassReader(b).accept(cn, 0);
		return cn;
	}

	static boolean isGhostNode(MethodNode mn) {
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode m && m.owner.equals("nipx/LambdaAligner")
			    && m.name.equals("onOrphanInvoked")) return true;
		}
		return false;
	}

	static boolean isLive(ClassNode cn, String name, String desc) {
		for (MethodNode mn : cn.methods) {
			if (mn.name.equals(name) && mn.desc.equals(desc)) return !isGhostNode(mn);
		}
		return false;
	}

	static boolean isGhost(ClassNode cn, String name, String desc) {
		for (MethodNode mn : cn.methods) {
			if (mn.name.equals(name) && mn.desc.equals(desc)) return isGhostNode(mn);
		}
		return false;
	}

	static Set<String> getChildLambdaKeys(ClassNode cn, MethodNode mn) {
		Set<String> kids = new LinkedHashSet<>();
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof InvokeDynamicInsnNode indy) {
				if (indy.bsmArgs != null) {
					for (Object arg : indy.bsmArgs) {
						if (arg instanceof Handle h) {
							if (h.getOwner().equals(cn.name) && h.getName().startsWith("lambda$")) {
								kids.add(h.getName() + h.getDesc());
							}
						}
					}
				}
			}
		}
		return kids;
	}

	static void dumpTable(String title, byte[] b) {
		ClassNode cn = parse(b);
		System.out.println("=== " + title + " (" + cn.name + ") ===");
		System.out.printf("%-26s %-16s %-8s %s%n", "Method Name", "Descriptor", "Status", "Children (Indy Targets)");
		System.out.println("--------------------------------------------------------------------------------");
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			boolean ghost = isGhostNode(mn);
			Set<String> kids = getChildLambdaKeys(cn, mn);
			System.out.printf("%-26s %-16s %-8s %s%n", mn.name, mn.desc, (ghost ? "GHOST" : "LIVE"), kids);
		}
	}

	static File findFile(String rel) {
		File f = new File(rel);
		if (f.exists()) return f;
		File f2 = new File("scratch/hstest", rel);
		if (f2.exists()) return f2;
		return f;
	}

	@Test
	void tripleCompetitionDeleteACaptureB(@TempDir Path tmp) throws Exception {
		String javac = javac21();

		Path outV1 = Files.createDirectories(tmp.resolve("v1"));
		Path outV2 = Files.createDirectories(tmp.resolve("v2"));

		File timeSrc = findFile("compB/Time.java");
		File v1Src = findFile("compB/v1/testCompB/CompB.java");
		File v2Src = findFile("compB/v2/testCompB/CompB.java");

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.toString(),
			timeSrc.getAbsolutePath(), v1Src.getAbsolutePath());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.toString(),
			timeSrc.getAbsolutePath(), v2Src.getAbsolutePath());

		byte[] oldBytes = Files.readAllBytes(outV1.resolve("testCompB/CompB.class"));
		byte[] newBytes = Files.readAllBytes(outV2.resolve("testCompB/CompB.class"));

		dumpTable("Old V1 Methods", oldBytes);
		dumpTable("New V2 (Raw javac) Methods", newBytes);

		System.out.println("\n>>> 执行 LambdaAligner.align(oldBytes, newBytes)...");
		byte[] aligned = LambdaAligner.align(oldBytes, newBytes);

		dumpTable("Aligned (Result) Methods", aligned);

		System.out.println("\n=== 判定与 KNOWN LIMITATION 固化 ===");
		assertNotNull(aligned, "aligned 产物非空");

		ClassNode cnAligned = parse(aligned);
		boolean misboundToA = isLive(cnAligned, "lambda$build$1", "(II)V")
			&& isLive(cnAligned, "lambda$build$2", "(II)V")
			&& isGhost(cnAligned, "lambda$build$4", "(II)V")
			&& isGhost(cnAligned, "lambda$build$5", "(II)V");

		// 已知限制固化：若该形态不再误绑（限制消失），此断言变红，逼人更新基线。
		assertTrue(misboundToA,
			"三层竞争: 删 A 链 + B 叶子改捕获时，Chain B 祖先按同名误绑至已删 Chain A ($1, $2)，Old Chain B 变幽灵");

		int ghostCount = 0;
		for (MethodNode mn : cnAligned.methods) {
			if (mn.name.startsWith("lambda$") && isGhostNode(mn)) ghostCount++;
		}
		assertEquals(4, ghostCount, "旧 Chain B 整链 (3个) 与旧 Chain A 叶子 (1个) 均正确幽灵化 (ghostCount=" + ghostCount + ")");
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
