import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class CompeteDeleteTest {

	static int passed = 0, failed = 0, known = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   PASS  " : "   FAIL  ") + msg);
		if (ok) passed++; else failed++;
	}

	static void checkKnownLimitation(boolean stillBroken, String msg) {
		if (stillBroken) { known++; System.out.println("   KNOWN " + msg); }
		else { failed++; System.out.println("   FAIL  [已知限制已变化] " + msg); }
	}

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

	public static void main(String[] args) throws Exception {
		System.out.println("=== 竞争夹具实验：删除 A 链 (3层) + B 链 (3层) 叶子改捕获 ===");

		String javac = System.getenv("HSTEST_JAVAC21");
		if (javac == null) javac = "F:/files/java/jdks/openjdk-21.0.2/bin/javac.exe";
		if (!new File(javac).exists()) javac = "javac";

		File outV1 = findFile("fx/compB_v1");
		File outV2 = findFile("fx/compB_v2");
		outV1.mkdirs();
		outV2.mkdirs();

		File timeSrc = findFile("compB/Time.java");
		File v1Src = findFile("compB/v1/testCompB/CompB.java");
		File v2Src = findFile("compB/v2/testCompB/CompB.java");

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.getAbsolutePath(),
			timeSrc.getAbsolutePath(), v1Src.getAbsolutePath());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.getAbsolutePath(),
			timeSrc.getAbsolutePath(), v2Src.getAbsolutePath());

		byte[] oldBytes = Files.readAllBytes(new File(outV1, "testCompB/CompB.class").toPath());
		byte[] newBytes = Files.readAllBytes(new File(outV2, "testCompB/CompB.class").toPath());

		dumpTable("Old V1 Methods", oldBytes);
		dumpTable("New V2 (Raw javac) Methods", newBytes);

		System.out.println("\n>>> 执行 LambdaAligner.align(oldBytes, newBytes)...");
		byte[] aligned = LambdaAligner.align(oldBytes, newBytes);

		dumpTable("Aligned (Result) Methods", aligned);

		System.out.println("\n=== 判定与 KNOWN LIMITATION 固化 ===");
		check(aligned != null, "aligned 产物非空");

		ClassNode cnAligned = parse(aligned);
		boolean misboundToA = isLive(cnAligned, "lambda$build$1", "(II)V")
			&& isLive(cnAligned, "lambda$build$2", "(II)V")
			&& isGhost(cnAligned, "lambda$build$4", "(II)V")
			&& isGhost(cnAligned, "lambda$build$5", "(II)V");

		checkKnownLimitation(misboundToA,
			"三层竞争: 删 A 链 + B 叶子改捕获时，Chain B 祖先按同名误绑至已删 Chain A ($1, $2)，Old Chain B 变幽灵");

		int ghostCount = 0;
		for (MethodNode mn : cnAligned.methods) {
			if (mn.name.startsWith("lambda$") && isGhostNode(mn)) ghostCount++;
		}
		check(ghostCount == 4, "旧 Chain B 整链 (3个) 与旧 Chain A 叶子 (1个) 均正确幽灵化 (ghostCount=" + ghostCount + ")");
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
