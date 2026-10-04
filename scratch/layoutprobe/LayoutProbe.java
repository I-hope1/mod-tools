import java.io.File;
import java.lang.instrument.ClassDefinition;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 实例状态布局安全门 —— **真机实验**（§8.3 第 7 项）。
 *
 * <p>在 JBR + {@code -XX:+AllowEnhancedClassRedefinition} 下，对一个**存活实例**逐项试字段表变更，
 * 记录四件事：{@code redefineClasses} 是否被拒、旧实例的旧字段值是否保留、读该字段的方法/反射结果、
 * 是否崩溃或抛 {@code LinkageError}。用 {@code -XX:-AllowEnhancedClassRedefinition}（或该 flag 不存在
 * 时）对照一遍。</p>
 *
 * <p>方法体在所有版本中**保持逐字节相同**（只改字段表），因此观察到的差异只可能来自字段布局处理。
 * 读取用反射（等价于"读该字段的方法"），另有一个不依赖变更字段的 {@code readStable()} 用来验证
 * 方法本身在 redefine 后仍正常。</p>
 *
 * <p>用法：{@code java -javaagent:layout-agent.jar -cp . LayoutProbe <case>}。每个 case 必须独立 JVM
 * （失败的 redefine 可能让该类不可再用）。</p>
 */
public class LayoutProbe {

	/** 各 case 的 v1 / v2 字段表；方法体共用同一模板，逐字节一致。 */
	static String fieldsFor(String caseName, boolean v2) {
		switch (caseName) {
			case "addInt":      return v2 ? "    public int a = 7;\n    public int b = 99;\n"
			                              : "    public int a = 7;\n";
			case "addRef":      return v2 ? "    public int a = 7;\n    public String b = \"NEW\";\n"
			                              : "    public int a = 7;\n";
			case "delField":    return v2 ? "    public int a = 7;\n"
			                              : "    public int a = 7;\n    public int b = 99;\n";
			case "int2long":    return v2 ? "    public long a = 7L;\n"
			                              : "    public int a = 7;\n";
			case "int2String":  return v2 ? "    public String a = \"X\";\n"
			                              : "    public int a = 7;\n";
			case "obj2String":  return v2 ? "    public String a = \"hello\";\n"
			                              : "    public Object a = \"hello\";\n";
			case "inst2static": return v2 ? "    public static int a = 7;\n"
			                              : "    public int a = 7;\n";
			case "static2inst": return v2 ? "    public int a = 7;\n"
			                              : "    public static int a = 7;\n";
			default: throw new IllegalArgumentException("unknown case: " + caseName);
		}
	}

	static String sourceFor(String caseName, boolean v2) {
		return "package lp;\n" +
			"public class Subject {\n" +
			fieldsFor(caseName, v2) +
			"    public int stable = 5;\n" +
			"    public int readStable() { return stable; }\n" +
			"    public long spin(long n) { long x = 0; for (long i = 0; i < n; i++) { x += i; } return x + stable; }\n" +
			"}\n";
	}

	public static void main(String[] args) throws Exception {
		String caseName = args.length > 0 ? args[0] : "addInt";
		boolean enhanced = Boolean.getBoolean("lp.enhanced");   // 由脚本 -Dlp.enhanced=true 传入
		System.out.println("CASE=" + caseName + " ENHANCED=" + enhanced);

		Path tmp  = Files.createTempDirectory("layoutprobe");
		Path v1   = tmp.resolve("v1"), v2 = tmp.resolve("v2");
		Files.createDirectories(v1.resolve("lp"));
		Files.createDirectories(v2.resolve("lp"));
		String javac = System.getProperty("java.home") + File.separator + "bin" + File.separator
			+ (System.getProperty("os.name").toLowerCase().contains("win") ? "javac.exe" : "javac");
		writeCompile(javac, v1, "Subject", sourceFor(caseName, false));
		writeCompile(javac, v2, "Subject", sourceFor(caseName, true));
		byte[] v2bytes = Files.readAllBytes(v2.resolve("lp/Subject.class"));

		// v1 通过独立 URLClassLoader 加载：v2 目录**不在**任何 classpath 上，避免被误加载
		try (URLClassLoader cl = new URLClassLoader(new URL[]{ v1.toUri().toURL() }, null)) {
			Class<?> subject = Class.forName("lp.Subject", true, cl);

			// 让方法先跑热（触发 JIT 编译），再改字段表
			Object inst = subject.getDeclaredConstructor().newInstance();
			Method spin = subject.getMethod("spin", long.class);
			Method readStable = subject.getMethod("readStable");
			long sink = 0;
			for (int i = 0; i < 30_000; i++) sink += (Long) spin.invoke(inst, 2000L);
			System.out.println("WARMUP sink=" + sink);

			// redefine 前：把字段设成可辨识的值并读一遍
			setInt(subject, inst, "a", 7);
			readAndPrint(subject, inst, "v1");
			System.out.println("BEFORE readStable=" + readStable.invoke(inst)
				+ " spin=" + spin.invoke(inst, 2000L));

			// ---- redefine ----
			boolean accepted;
			String err = "";
			try {
				LayoutAgent.INST.redefineClasses(new ClassDefinition(subject, v2bytes));
				accepted = true;
			} catch (Throwable t) {
				accepted = false;
				err = t.getClass().getName() + ": " + flat(t.getMessage());
			}
			System.out.println("REDEFINE accepted=" + accepted + (accepted ? "" : " err=" + err));

			if (accepted) {
				readAndPrint(subject, inst, "v2");
				try {
					System.out.println("AFTER readStable=" + readStable.invoke(inst)
						+ " spin=" + spin.invoke(inst, 2000L));
				} catch (Throwable t) {
					System.out.println("AFTER method FAILED " + t.getClass().getName() + ": " + flat(t.getMessage()));
				}
			}
			System.out.println("DONE case=" + caseName + " accepted=" + accepted);
		}
	}

	/** 读取 v1 声明过的字段 + v2 声明过的字段，打印各自取值（读不到就注明）。 */
	static void readAndPrint(Class<?> subject, Object inst, String phase) throws Exception {
		List<String> names = new ArrayList<>();
		for (Field f : subject.getDeclaredFields()) names.add(f.getName());
		for (String n : names) {
			if ("stable".equals(n)) continue;
			Field f = subject.getDeclaredField(n);
			String val;
			try {
				f.setAccessible(true);
				Object got = java.lang.reflect.Modifier.isStatic(f.getModifiers()) ? f.get(null) : f.get(inst);
				val = String.valueOf(got);
			} catch (Throwable t) {
				val = "<" + t.getClass().getSimpleName() + ">";
			}
			System.out.println("FIELD " + phase + " " + n + ":" + f.getType().getSimpleName() + " = " + val
				+ (java.lang.reflect.Modifier.isStatic(f.getModifiers()) ? " (static)" : ""));
		}
	}

	static void setInt(Class<?> c, Object inst, String name, int v) {
		try {
			Field f = c.getDeclaredField(name);
			f.setAccessible(true);
			if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) f.set(null, v); else f.set(inst, v);
		} catch (Throwable ignored) { }
	}

	static void writeCompile(String javac, Path dir, String tag, String source) throws Exception {
		Path src = dir.resolve(tag + ".java");
		Files.write(src, source.getBytes(StandardCharsets.UTF_8));
		Process p = new ProcessBuilder(javac, "-nowarn", "-encoding", "UTF-8",
			"-d", dir.toString(), src.toString()).redirectErrorStream(true).start();
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (p.waitFor() != 0) throw new IllegalStateException("javac failed:\n" + out);
	}

	static String flat(String s) {
		return s == null ? "" : s.replace('\n', ' ');
	}
}
