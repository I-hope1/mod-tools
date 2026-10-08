import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * hstest 迁移期小工具：toolchain javac 解析（系统属性优先，环境变量/F: 路径兜底）+ 夹具现编。
 * 仅测试用，不参与生产。
 */
final class HstestFixtures {

	private HstestFixtures() { }

	/** 解析某个主版本的 javac：{@code hstest.javacN} → {@code HSTEST_JAVACN} → F:/ 兜底 → PATH 上的 javac。 */
	static String javac(int major) {
		String j = System.getProperty("hstest.javac" + major);
		if (j != null && new File(j).exists()) return j;
		j = System.getenv("HSTEST_JAVAC" + major);
		if (j != null && new File(j).exists()) return j;
		String f;
		if (major == 8)       f = "F:/files/java/jdks/jdk-1.8/bin/javac.exe";
		else if (major == 11) f = "F:/files/java/jdks/jdk-11.0.12.7-hotspot/bin/javac.exe";
		else if (major == 17) f = "F:/files/java/jdks/jdk-17.0.2/bin/javac.exe";
		else if (major == 21) f = "F:/files/java/jdks/openjdk-21.0.2/bin/javac.exe";
		else                  f = null;
		return f != null && new File(f).exists() ? f : "javac";
	}

	/** 相对 {@code scratch/hstest} 的源路径解析（JUnit 工作目录可能是仓库根）。 */
	static File find(String rel) {
		File f = new File(rel);
		if (f.exists()) return f;
		File f2 = new File("scratch/hstest", rel);
		return f2.exists() ? f2 : f;
	}

	/** 用给定 javac 把 sources 编到 out（自动建目录）。 */
	static void compile(String javac, Path out, File... sources) throws Exception {
		Files.createDirectories(out);
		List<String> cmd = new ArrayList<>(Arrays.asList(javac, "-nowarn", "-encoding", "UTF-8", "-d", out.toString()));
		for (File s : sources) cmd.add(s.getAbsolutePath());
		runCmd(cmd.toArray(new String[0]));
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
