import nipx.LambdaAligner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pass B 计数器与兜底对齐断言（JUnit 化）。
 *
 * <p>验证目标：当方法深度发生变化（如叶子被新 lambda 包裹一层，upDepth 从 0 变为 1），
 * Pass A 因要求深度相等而跳过，Pass B 成功兜底配对并使 {@code LAST_STATS.passBPairs > 0}。</p>
 */
public class PassBTest {

	@Test
	void passBCounterIsReachable(@TempDir Path tmp) throws Exception {
		String javac = javac21();
		Path outV1 = Files.createDirectories(tmp.resolve("v1"));
		Path outV2 = Files.createDirectories(tmp.resolve("v2"));

		File src1 = findFile("passB/v1/testPassB/PassBCase.java");
		File src2 = findFile("passB/v2/testPassB/PassBCase.java");

		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV1.toString(), src1.getAbsolutePath());
		runCmd(javac, "-nowarn", "-encoding", "UTF-8", "-d", outV2.toString(), src2.getAbsolutePath());

		byte[] v1 = Files.readAllBytes(outV1.resolve("testPassB/PassBCase.class"));
		byte[] v2 = Files.readAllBytes(outV2.resolve("testPassB/PassBCase.class"));

		LambdaAligner.LAST_STATS = null;
		byte[] aligned = LambdaAligner.align(v1, v2);

		assertNotNull(aligned, "aligned 产物非空");
		assertNotNull(LambdaAligner.LAST_STATS, "LAST_STATS 已记录");
		assertTrue(LambdaAligner.LAST_STATS.passBPairs > 0,
			"passB 计数器成功记录到非零配对: " + LambdaAligner.LAST_STATS);
		assertEquals(0, LambdaAligner.LAST_STATS.passAPairs,
			"passA 跳过深度不匹配候选 (passA == 0): " + LambdaAligner.LAST_STATS);
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
