import nipx.LambdaAligner;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Pass B 计数器与兜底对齐断言。
 *
 * <p>验证目标：当方法深度发生变化（如叶子被新 lambda 包裹一层，upDepth 从 0 变为 1），
 * Pass A 因要求深度相等而跳过，Pass B 成功兜底配对并使 {@code LAST_STATS.passBPairs > 0}。</p>
 */
public class PassBTest {

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
		System.out.println("=== PassBTest: 验证 Pass B 计数器非零可达性 ===");

		String javac = System.getenv("HSTEST_JAVAC21");
		if (javac == null) javac = "F:/files/java/jdks/openjdk-21.0.2/bin/javac.exe";
		if (!new File(javac).exists()) javac = "javac";

		File outV1 = findFile("fx/passB_v1");
		File outV2 = findFile("fx/passB_v2");
		outV1.mkdirs();
		outV2.mkdirs();

		File src1 = findFile("passB/v1/testPassB/PassBCase.java");
		File src2 = findFile("passB/v2/testPassB/PassBCase.java");

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.getAbsolutePath(), src1.getAbsolutePath());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.getAbsolutePath(), src2.getAbsolutePath());

		byte[] v1 = Files.readAllBytes(new File(outV1, "testPassB/PassBCase.class").toPath());
		byte[] v2 = Files.readAllBytes(new File(outV2, "testPassB/PassBCase.class").toPath());

		LambdaAligner.LAST_STATS = null;
		byte[] aligned = LambdaAligner.align(v1, v2);

		check(aligned != null, "aligned 产物非空");
		check(LambdaAligner.LAST_STATS != null, "LAST_STATS 已记录");
		check(LambdaAligner.LAST_STATS.passBPairs > 0,
			"passB 计数器成功记录到非零配对: " + LambdaAligner.LAST_STATS);
		check(LambdaAligner.LAST_STATS.passAPairs == 0,
			"passA 跳过深度不匹配候选 (passA == 0): " + LambdaAligner.LAST_STATS);
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
