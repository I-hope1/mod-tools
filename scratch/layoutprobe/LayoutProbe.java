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
		if ("anonCapture".equals(caseName)) return anonSource(v2);
		return "package lp;\n" +
			"public class Subject {\n" +
			fieldsFor(caseName, v2) +
			"    public int stable = 5;\n" +
			"    public int readStable() { return stable; }\n" +
			"    public long spin(long n) { long x = 0; for (long i = 0; i < n; i++) { x += i; } return x + stable; }\n" +
			"}\n";
	}

	/**
	 * 匿名类新增捕获变量的 case（§8.3 第 7 项的门控判据来源）。
	 *
	 * <p><b>要验证什么</b>：宿主方法里新增一个局部变量，且匿名类的方法体**读取**它 ——
	 * 于是匿名类会合成一个新的捕获字段，宿主实例的字段布局也随之变化。</p>
	 *
	 * <p><b>踩过的坑（务必保持）</b>：局部变量**不能是编译期常量**。
	 * 最初写成 {@code final int extra = 1000;}，javac 会把它内联进方法体，
	 * 于是匿名类<b>不合成任何捕获字段</b>（实测 {@code Subject$1} 只有 {@code this$0}），
	 * 这个 case 就完全没测到"新增捕获字段"。这里改成由**实例字段**赋值的局部变量
	 * （{@code this.extra}），javac 必须真正捕获它，才会生成 {@code val$extra}。</p>
	 *
	 * <p>预期（待实测确认，不预设结论）：这类"合成捕获字段"的布局变化**不应**被放行 ——
	 * 匿名类是宿主的独立 class 文件，二者必须一起重定义；只放行宿主会造成
	 * "宿主已重定义、匿名类没动"的不一致。本 case 的作用就是把这个预期变成可观测的输出。</p>
	 *
	 * <p><b>实测结论（JBR 21.0.9，2024 验证；这是字段布局门的判据来源）</b>：</p>
	 * <ul>
	 *   <li>{@code -XX:-AllowEnhancedClassRedefinition}：{@code redefineClasses} <b>被拒绝</b>，
	 *       错误为 {@code UnsupportedOperationException: attempted to change the schema (add/remove fields)}。
	 *       这是安全的行为。</li>
	 *   <li>{@code -XX:+AllowEnhancedClassRedefinition}：{@code redefineClasses} <b>被接受</b>，
	 *       但新增字段的<b>初始化器不执行</b>。实测宿主新增的 {@code extra} 保持 0
	 *       （源码声明为 {@code = 1000}），匿名类合成的 {@code val$extra} 同样为 0。
	 *       <br>关键区分：把宿主 {@code extra} 手动写成 4242 后，新实例的 {@code get()} 返回
	 *       {@code 4249} = 4242 + 7 —— 说明<b>字段可读、捕获链正常，唯独初始化器没跑</b>。
	 *       因此这不是"存活实例没迁移"，而是<b>之后新建的每一个实例都错</b>。</li>
	 * </ul>
	 *
	 * <p><b>已排除 JIT 混杂因素（2024 对照实验）</b>：有人会问"是不是 JIT 编译过的旧构造器
	 * 没被作废"。用 {@code jitcmp.sh} 在三种执行模式下跑本 case，结果<b>逐字节一致</b>：</p>
	 * <table>
	 *   <tr><th>模式</th><th>enhanced=on</th><th>enhanced=off</th></tr>
	 *   <tr><td>默认</td><td>accept；{@code val$extra=0}，手写后 4249</td><td>reject</td></tr>
	 *   <tr><td>{@code -Xint}（完全禁用 JIT）</td><td><b>同上</b></td><td>reject</td></tr>
	 *   <tr><td>{@code -XX:TieredStopAtLevel=1}</td><td><b>同上</b></td><td>reject</td></tr>
	 * </table>
	 * <p>{@code -Xint} 下 JIT 根本不存在，行为却完全相同 ⇒ <b>不是 JIT 残留</b>，
	 * 而是 JVM 确实不执行新字段的初始化器。故门必须<b>无条件拒绝</b>这类布局变更，
	 * 不能加"类是否被热点编译过"这种条件。</p>
	 *
	 * <p>两者对比说明：增强模式<b>接受</b>了它其实无法正确处理的 schema 变更，
	 * 于是把"拒绝后用户会重启"变成了"静默的零值字段"。这正是字段布局门必须存在的原因 ——
	 * 门要在 {@code redefineClasses} 之前就拒绝这类变更，不能依赖 JVM 拒绝。</p>
	 *
	 * <p>v1：匿名类只捕获 {@code base}；v2：同一个匿名类额外捕获 {@code extra} 并在方法体里读它。
	 * 宿主方法 {@code call()} 的**签名与返回类型不变**，只有局部变量和匿名类体变化。</p>
	 */
	static String anonSource(boolean v2) {
		String extraField = v2 ? "    public int extra = 1000;\n" : "";
		String extraDecl  = v2 ? "        final int extra = this.extra;\n" : "";
		String body = v2
			? "            public int get() { return base + extra; }\n"
			: "            public int get() { return base; }\n";
		return "package lp;\n" +
			"public class Subject {\n" +
			"    public int base = 7;\n" +
			"    public int stable = 5;\n" +
			extraField +
			"    public interface Cell { int get(); }\n" +
			"    public Cell call() {\n" +
			extraDecl +
			"        return new Cell() {\n" +
			body +
			"        };\n" +
			"    }\n" +
			"    public int readStable() { return stable; }\n" +
			"    public long spin(long n) { long x = 0; for (long i = 0; i < n; i++) { x += i; } return x + stable; }\n" +
			"}\n";
	}

	/** 匿名类 case 需要连同宿主一起重定义：返回 v2 目录下全部 class 的 (类名, 字节码)。 */
	static List<Object[]> allClasses(Path dir, ClassLoader cl) throws Exception {
		List<Object[]> out = new ArrayList<>();
		Path pkg = dir.resolve("lp");
		if (!Files.isDirectory(pkg)) return out;
		try (var s = Files.list(pkg)) {
			for (Path p : s.sorted().toList()) {
				String fn = p.getFileName().toString();
				if (!fn.endsWith(".class")) continue;
				String cn = "lp." + fn.substring(0, fn.length() - ".class".length());
				Class<?> c = Class.forName(cn, false, cl);
				out.add(new Object[]{ c, Files.readAllBytes(p) });
			}
		}
		return out;
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

			// 匿名类 case：在 redefine **之前**创建一个匿名对象，作为"存活实例"
			Object liveCell = null;
			if ("anonCapture".equals(caseName)) {
				liveCell = subject.getMethod("call").invoke(inst);
				System.out.println("ANON pre-redefine live.get=" + invokeGet(liveCell)
					+ " fields=" + fieldsOf(liveCell));
			}

			// ---- redefine ----
			//
			// anonCapture 需要**宿主与其匿名类一起**重定义：匿名类是独立 class 文件，
			// 只重定义宿主会让"宿主已换、匿名类没动"的不一致变得不可观测。
			// 其他 case 仍是单类，保持与原矩阵一致。
			boolean isAnon = "anonCapture".equals(caseName);
			boolean accepted;
			String err = "";
			try {
				if (isAnon) {
					List<ClassDefinition> defs = new ArrayList<>();
					for (Object[] pair : allClasses(v2, cl)) {
						defs.add(new ClassDefinition((Class<?>) pair[0], (byte[]) pair[1]));
					}
					System.out.println("REDEFINE-GROUP size=" + defs.size());
					LayoutAgent.INST.redefineClasses(defs.toArray(new ClassDefinition[0]));
				} else {
					LayoutAgent.INST.redefineClasses(new ClassDefinition(subject, v2bytes));
				}
				accepted = true;
			} catch (Throwable t) {
				accepted = false;
				err = t.getClass().getName() + ": " + flat(t.getMessage());
			}
			System.out.println("REDEFINE accepted=" + accepted + (accepted ? "" : " err=" + err));

			// 匿名类 case 的额外观测：区分"存活实例"与"redefine 后新建实例"
			//
			// 二者语义不同，必须分开看：
			//   • liveCell  —— redefine 前创建的匿名对象（存活实例）；
			//   • freshCell —— redefine 后重新调用 call() 得到的新匿名对象。
			// 若新实例也拿不到正确的捕获值，说明问题不在"旧实例没迁移"，而在更深处。
			// 必须限定在 anonCapture：其他 case 没有 call()。
			if (isAnon) {
				try {
					Method call = subject.getMethod("call");
					// 第一次**不碰字段**，直接新建实例读：用于判断初始化器是否执行过
					// （源码声明 extra = 1000；若读到 0，说明新字段的初始化器没跑）
					Object fresh1 = call.invoke(inst);
					System.out.println("ANON init-check fresh.get=" + invokeGet(fresh1)
						+ " fields=" + fieldsOf(fresh1)
						+ "  <-- get=7 且 val$extra=0 即\u201c\u521d\u59cb\u5316\u5668\u672a\u6267\u884c\u201d");
					System.out.println("ANON post live.get=" + invokeGet(liveCell)
						+ " fields=" + fieldsOf(liveCell) + "  <-- redefine 前的存活实例");
					// 再手动写可辨识值：区分"字段不可读"与"初始化器没跑"
					setInt(subject, inst, "extra", 4242);
					Object fresh2 = call.invoke(inst);
					System.out.println("ANON manual-set fresh.get=" + invokeGet(fresh2)
						+ " fields=" + fieldsOf(fresh2)
						+ "  <-- 读出 4249 即证明字段可读、仅初始化器缺失");
				} catch (Throwable t) {
					System.out.println("ANON FAILED " + t.getClass().getName() + ": " + flat(t.getMessage()));
				}
			}

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

	/** 反射调用匿名对象的 get()。匿名类是 package-private，必须 setAccessible。 */
	static String invokeGet(Object cell) {
		try {
			Method get = cell.getClass().getDeclaredMethod("get");
			get.setAccessible(true);
			return String.valueOf(get.invoke(cell));
		} catch (Throwable t) {
			return "<" + t.getClass().getSimpleName() + ">";
		}
	}

	/** 列出匿名对象的全部字段与取值 —— 用于确认合成捕获字段是否出现及其实际值。 */
	static String fieldsOf(Object cell) {
		List<String> fs = new ArrayList<>();
		for (Field f : cell.getClass().getDeclaredFields()) {
			try {
				f.setAccessible(true);
				fs.add(f.getName() + "=" + f.get(cell));
			} catch (Throwable t) {
				fs.add(f.getName() + "=<" + t.getClass().getSimpleName() + ">");
			}
		}
		return fs.toString();
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
