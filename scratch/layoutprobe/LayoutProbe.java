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
		if ("named".equals(caseName))       return namedSource(v2);
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
	 * <p><b>实测结论（JBR 21.0.9 验证）</b>：</p>
	 * <ul>
	 *   <li>{@code -XX:-AllowEnhancedClassRedefinition}：{@code redefineClasses} <b>被拒绝</b>，
	 *       错误为 {@code UnsupportedOperationException: attempted to change the schema (add/remove fields)}。
	 *       这是安全的行为。</li>
	 *   <li>{@code -XX:+AllowEnhancedClassRedefinition}：{@code redefineClasses} <b>被接受</b>。
	 *       <b>新建实例会按新构造器正常初始化</b>（见下方对照实验）；
	 *       <b>只有 redefine 之前就已存在的存活实例</b>读新增字段得到零值 ——
	 *       因为它的布局在创建时就定了，JVM 无法为它补上新字段的初始化。</li>
	 * </ul>
	 *
	 * <p><b>⚠️ 一次被推翻的错误结论（务必保留，防止重蹈）</b>：本 case 初版曾据
	 * "redefine 后新建实例 {@code get()} 仍返回 7" 得出<b>"增强模式下新字段的初始化器根本不执行"</b>，
	 * 并据此写下"门必须无条件拒绝"。<b>该结论是探针假象，已推翻。</b>
	 * 当时的推理链看似完备（{@code javap} 确认 v2 字节码里确实有 {@code putfield extra}，
	 * 且 {@code -Xint}/{@code -XX:TieredStopAtLevel=1} 下结果一致，故排除了 JIT），
	 * 但漏掉了一个因素：<b>创建实例的路径本身可能是旧的</b>。</p>
	 *
	 * <p><b>真正的对照实验（{@code ctrl.sh}，{@code named} case）</b>：三个互相独立的控制同时做，
	 * 全部得到<b>正确值 1000</b>：</p>
	 * <table>
	 *   <tr><th>控制</th><th>做法</th><th>enhanced=on 结果</th></tr>
	 *   <tr><td>A</td><td>redefine 后<b>重新取</b> {@code getDeclaredConstructor()}</td>
	 *       <td>{@code readExtra=1000}</td></tr>
	 *   <tr><td>B</td><td><b>完全绕开反射</b>：由字节码里的 {@code new Subject()} 创建（辅助类 Factory）</td>
	 *       <td>{@code readExtra=1000}</td></tr>
	 *   <tr><td>C</td><td>直接反射读字段</td><td>{@code extra=1000}</td></tr>
	 * </table>
	 * <p>控制 B 不经过任何反射，因此与 JEP 416（JDK 18+ 反射改走 MethodHandle）无关；
	 * 并在 {@code -Djdk.reflect.useDirectMethodHandle=false} 下重复，结论不变。
	 * ⇒ <b>DCEVM 行为与文档一致</b>：新建实例正常初始化，只有存活实例保留零值。</p>
	 *
	 * <p><b>教训</b>：{@code anonCapture} 里那个"新实例拿 0"的观测，
	 * 与 {@code named} 的差别只在**读取路径**（前者经匿名类合成字段与宿主字段，
	 * 后者直接读宿主字段）。在把"初始化器不执行"这种反直觉结论写进文档前，
	 * 必须先让"创建/读取路径"本身也过一遍对照。详见 {@link #namedSource}。</p>
	 *
	 * <p>对门的意义：本 case 支持的是<b>匿名类配对</b>的理由（存活实例从没捕获过该变量，
	 * 新方法体读它就是零值），<b>不支持</b>"具名类新增字段一律拒绝"。</p>
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

	/**
	 * 具名类新增字段的 case + **反射混杂因素对照**（§8.3 第 7 项）。
	 *
	 * <p>anonCapture 的初版结论是"redefine 后新建实例也不执行新初始化器"。
	 * 这与 DCEVM 增强重定义的已知行为相反（它的卖点之一就是新建实例按新构造器初始化），
	 * 因此必须排除探针自身的混杂因素，尤其是：</p>
	 * <ul>
	 *   <li><b>JEP 416</b>（JDK 18+）：反射 {@code Constructor.newInstance} 默认改走
	 *       {@code MethodHandle}，绑定的可能是 redefine 之前的方法版本。
	 *       {@code -Xint} 能排除 JIT，但排除不了这一条。</li>
	 * </ul>
	 *
	 * <p>三个对照，任一得到"正确值"即说明原结论是探针假象：</p>
	 * <ol>
	 *   <li>{@code -Djdk.reflect.useDirectMethodHandle=false} —— 退回旧反射实现（脚本层控制）。</li>
	 *   <li>redefine 之后**重新取** {@code getDeclaredConstructor()}，不复用旧引用。</li>
	 *   <li>由**字节码里的 {@code new}** 创建实例（辅助类 {@code Factory}），完全绕开反射。</li>
	 * </ol>
	 */
	static String namedSource(boolean v2) {
		return "package lp;\n" +
			"public class Subject {\n" +
			(v2 ? "    public int extra = 1000;\n" : "") +
			"    public int base = 7;\n" +
			"    public int stable = 5;\n" +
			"    public int readStable() { return stable; }\n" +
			"    public long spin(long n) { long x = 0; for (long i = 0; i < n; i++) { x += i; } return x + stable; }\n" +
			"    public int readExtra() { return " + (v2 ? "extra" : "base") + "; }\n" +
			"}\n";
	}

	/** 辅助类：在**字节码**里 new Subject()，完全绕开反射（对照 3）。 */
	static String factorySource() {
		return "package lp;\n" +
			"public class Factory {\n" +
			"    public static Object make() { return new Subject(); }\n" +
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
		// named case 需要一个在字节码里 new Subject() 的辅助类（对照 3）。
		// Factory 引用 Subject，两者必须**同批**编译，否则 sourcepath 上找不到彼此。
		if ("named".equals(caseName)) {
			writeCompileTogether(javac, v1, "Subject", sourceFor(caseName, false), "Factory", factorySource());
			writeCompileTogether(javac, v2, "Subject", sourceFor(caseName, true),  "Factory", factorySource());
		} else {
			writeCompile(javac, v1, "Subject", sourceFor(caseName, false));
			writeCompile(javac, v2, "Subject", sourceFor(caseName, true));
		}
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
			// ⚠️ 这里曾经的解读是错的，务必按下面正确的解读读输出：
			//   • liveCell —— redefine 前创建的匿名对象（**存活实例**）。
			//     它读不到新捕获的变量是**预期**行为：该实例的布局在创建时就定了，
			//     JVM 无法为它补上 val$extra 的初始化。输出里的 0 属于这一类。
			//   • call() 新建的匿名对象 —— 注意 call() 是调在**旧的存活宿主实例**上的，
			//     所以它捕获到的是那个宿主的 extra（0），而不是"初始化器没执行"。
			// 判据因此不是"这里是否读到 1000"，而是 named case 的三个对照 ——
			// 实测三个控制全部得到 1000，即新建实例初始化正常。见 anonSource 的 javadoc。
			// 必须限定在 anonCapture：其他 case 没有 call()。
			if (isAnon) {
				try {
					Method call = subject.getMethod("call");
					Object fresh1 = call.invoke(inst);
					System.out.println("ANON live-host get=" + invokeGet(fresh1)
						+ " fields=" + fieldsOf(fresh1)
						+ "  <-- 宿主是**存活实例**，其 extra 为 0 属预期（非初始化器问题）");
					System.out.println("ANON live-anon get=" + invokeGet(liveCell)
						+ " fields=" + fieldsOf(liveCell) + "  <-- redefine 前的存活匿名对象");
					// 手动写宿主字段：仅用于证明字段可读、捕获链通畅
					setInt(subject, inst, "extra", 4242);
					Object fresh2 = call.invoke(inst);
					System.out.println("ANON manual-set get=" + invokeGet(fresh2)
						+ " fields=" + fieldsOf(fresh2)
						+ "  <-- 读出 4249 即证明字段可读、捕获链正常");
				} catch (Throwable t) {
					System.out.println("ANON FAILED " + t.getClass().getName() + ": " + flat(t.getMessage()));
				}
			}

			// ---- named case：反射混杂因素对照（JEP 416）----
			//
			// v1 里 readExtra() 返回 base(=7)，v2 里返回 extra(声明 =1000)。
			// redefine 后新建实例若得到 1000 ⇒ 初始化器正常执行（探针假象被排除前需先看这三条）。
			if ("named".equals(caseName)) {
				System.out.println("REFLECT useDirectMethodHandle="
					+ System.getProperty("jdk.reflect.useDirectMethodHandle", "(default)"));

				// 对照 2：redefine 后**重新取**构造器，不复用旧引用
				try {
					Object fresh2 = subject.getDeclaredConstructor().newInstance();
					Method re = subject.getMethod("readExtra");
					System.out.println("CTRL re-fetched-ctor readExtra=" + re.invoke(fresh2) + " (期望 1000)");
				} catch (Throwable t) {
					System.out.println("CTRL re-fetched-ctor FAILED " + t.getClass().getSimpleName()
						+ ": " + flat(t.getMessage()));
				}

				// 对照 3：完全绕开反射 —— 由字节码里的 new 创建（辅助类 Factory）
				try {
					Class<?> factoryCls = Class.forName("lp.Factory", true, cl);
					Method make = factoryCls.getMethod("make");
					Object viaNew = make.invoke(null);
					Method re = subject.getMethod("readExtra");
					System.out.println("CTRL bytecode-new readExtra=" + re.invoke(viaNew) + " (期望 1000)");
				} catch (Throwable t) {
					System.out.println("CTRL bytecode-new FAILED " + t.getClass().getSimpleName()
						+ ": " + flat(t.getMessage()));
				}

				// 另读一次实际字段值，区分"初始化器没跑"与"方法读错字段"
				try {
					Field fe = subject.getDeclaredField("extra");
					fe.setAccessible(true);
					Object any = subject.getDeclaredConstructor().newInstance();
					System.out.println("CTRL field-read extra=" + fe.get(any) + " (期望 1000)");
				} catch (Throwable t) {
					System.out.println("CTRL field-read FAILED " + t.getClass().getSimpleName()
						+ ": " + flat(t.getMessage()));
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
		writeCompileTogether(javac, dir, tag, source);
	}

	/** 多文件一起编译（相互引用的类必须同批编，否则 sourcepath 上找不到彼此）。 */
	static void writeCompileTogether(String javac, Path dir, String... tagSourcePairs) throws Exception {
		List<String> cmd = new ArrayList<>();
		cmd.add(javac);
		cmd.add("-nowarn");
		cmd.add("-encoding");
		cmd.add("UTF-8");
		cmd.add("-d");
		cmd.add(dir.toString());
		for (int i = 0; i < tagSourcePairs.length; i += 2) {
			String tag = tagSourcePairs[i];
			String source = tagSourcePairs[i + 1];
			Path src = dir.resolve(tag + ".java");
			Files.write(src, source.getBytes(StandardCharsets.UTF_8));
			cmd.add(src.toString());
		}
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (p.waitFor() != 0) throw new IllegalStateException("javac failed:\n" + out);
	}

	static String flat(String s) {
		return s == null ? "" : s.replace('\n', ' ');
	}
}
