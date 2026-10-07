import nipx.AnonClassAligner;
import nipx.ClassDiffUtil;
import nipx.LayoutGate;
import nipx.LayoutGate.FieldInfo;
import nipx.LayoutGate.Verdict;

import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * {@link LayoutGate} 规则表断言（§7.2 的精确变体）。
 *
 * <p>守的是"存活实例会不会读到零值"。判据全部来自真机实验
 * （{@code scratch/layoutprobe}）：新建实例正常初始化，<b>只有 redefine 之前
 * 已存在的实例</b>保留零值。</p>
 *
 * <p>本断言是**先红后绿**的那一半：在门接入 Tier 4 之前，这里断言的是纯函数本身。</p>
 */
public class LayoutGateAssert {

	static int failed = 0;
	static int passed = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   PASS  " : "   FAIL  ") + msg);
		if (ok) passed++; else failed++;
	}

	/** 简写：造一个字段。 */
	static FieldInfo f(String name, String desc, boolean statik, boolean synth) {
		return new FieldInfo(name, desc, statik, synth);
	}

	static FieldInfo plain(String name, String desc)      { return f(name, desc, false, false); }
	static FieldInfo statik(String name, String desc)     { return f(name, desc, true,  false); }
	static FieldInfo capture(String name, String desc)    { return f(name, desc, false, true);  }

	static List<FieldInfo> list(FieldInfo... fs) { return new ArrayList<>(Arrays.asList(fs)); }

	public static void main(String[] args) {
		System.out.println("== LayoutGate 规则表 ==");

		// ---------- 1) 字段集合完全相同 → 放行 ----------
		{
			System.out.println("-- 1) 无变化 --");
			var base = list(capture("this$0", "LHost;"), plain("n", "I"));
			var r = LayoutGate.check(base, list(capture("this$0", "LHost;"), plain("n", "I")));
			check(r.verdict() == Verdict.COMPATIBLE, "字段完全相同 → COMPATIBLE（实际 " + r.verdict() + "）");
		}

		// ---------- 2) 新增合成捕获字段 → 不兼容 ----------
		{
			System.out.println("-- 2) 新增捕获字段（本门存在的理由）--");
			var old = list(capture("this$0", "LHost;"));
			var neu = list(capture("this$0", "LHost;"), capture("val$extra", "I"));
			var r = LayoutGate.check(old, neu);
			check(r.verdict() == Verdict.ADDED_SYNTHETIC_FIELD,
				"新增 val$extra → ADDED_SYNTHETIC_FIELD（实际 " + r.verdict() + "）");
			check(r.detail().contains("val$extra"), "原因里写明字段名（不静默）");
			check(r.detail().contains("InitFix cannot patch"),
				"原因里写明 InitFix 覆盖不到（合成字段被 ClassDiffUtil 过滤）");
		}

		// ---------- 2b) 换捕获变量：val$a → val$b ----------
		{
			System.out.println("-- 2b) 换捕获变量 --");
			var old = list(capture("this$0", "LHost;"), capture("val$a", "I"));
			var neu = list(capture("this$0", "LHost;"), capture("val$b", "I"));
			var r = LayoutGate.check(old, neu);
			check(r.verdict() == Verdict.ADDED_SYNTHETIC_FIELD,
				"val$a→val$b 被判为新增（仍被挡住，符合预期）(实际 " + r.verdict() + ")");
		}

		// ---------- 3) 新增非合成字段 → 放行（交给 InitFix）----------
		{
			System.out.println("-- 3) 新增非合成字段 --");
			var old = list(capture("this$0", "LHost;"));
			var neu = list(capture("this$0", "LHost;"), plain("userField", "I"));
			var r = LayoutGate.check(old, neu);
			check(r.verdict() == Verdict.COMPATIBLE,
				"新增普通字段 → COMPATIBLE（InitFix 能补）(实际 " + r.verdict() + ")");
			check(r.detail().contains("userField"), "原因里写明放行了哪个字段");
		}

		// ---------- 4) 纯删除 → 放行 ----------
		{
			System.out.println("-- 4) 纯删除 --");
			var old = list(capture("this$0", "LHost;"), capture("val$gone", "I"), plain("u", "I"));
			var neu = list(capture("this$0", "LHost;"));
			var r = LayoutGate.check(old, neu);
			check(r.verdict() == Verdict.COMPATIBLE,
				"纯删除 → COMPATIBLE（新代码不再引用，不会读到零值）(实际 " + r.verdict() + ")");
			check(r.detail().contains("removed"), "原因里写明删了哪些字段");
		}

		// ---------- 4b) 纯删除合成字段单独可辨 ----------
		{
			System.out.println("-- 4b) 纯删除合成捕获字段 --");
			var old = list(capture("this$0", "LHost;"), capture("val$gone", "I"));
			var neu = list(capture("this$0", "LHost;"));
			var r = LayoutGate.check(old, neu);
			check(r.verdict() == Verdict.COMPATIBLE,
				"删除 val$gone → COMPATIBLE（REMOVED_SYNTHETIC_FIELD 与 COMPATIBLE 同义放行）");
		}

		// ---------- 5) 同名改类型 → 不兼容 ----------
		{
			System.out.println("-- 5) 同名改类型 --");
			var old = list(plain("a", "I"));
			var neu = list(plain("a", "J"));
			var r = LayoutGate.check(old, neu);
			check(r.verdict() == Verdict.CHANGED_FIELD_TYPE,
				"int→long 同名 → CHANGED_FIELD_TYPE（实际 " + r.verdict() + "）");
			check(r.detail().contains("I -> J"), "原因里写出类型变化");
		}

		// ---------- 5b) 捕获字段改类型 ----------
		{
			System.out.println("-- 5b) 捕获字段改类型 --");
			var old = list(capture("val$x", "I"));
			var neu = list(capture("val$x", "Ljava/lang/String;"));
			var r = LayoutGate.check(old, neu);
			check(r.verdict() == Verdict.CHANGED_FIELD_TYPE,
				"val$x 的 int→String → CHANGED_FIELD_TYPE（实际 " + r.verdict() + "）");
		}

		// ---------- 6) static 性变化 → 不兼容（且哈希看不见这一位）----------
		{
			System.out.println("-- 6) static 性变化 --");
			var old = list(plain("a", "I"));
			var neu = list(statik("a", "I"));
			var r = LayoutGate.check(old, neu);
			check(r.verdict() == Verdict.CHANGED_STATICNESS,
				"实例→静态 → CHANGED_STATICNESS（实际 " + r.verdict() + "）");
			check(r.detail().contains("instance -> static"), "原因里写明方向");
		}
		{
			var old = list(statik("a", "I"));
			var neu = list(plain("a", "I"));
			var r = LayoutGate.check(old, neu);
			check(r.verdict() == Verdict.CHANGED_STATICNESS, "静态→实例 同样被拦");
		}

		// ---------- 7) 优先级：改类型要先于新增报出 ----------
		//
		// 一个类可能同时有多种变化。改类型/改 static 性比"新增"更危险（旧值直接错读），
		// 所以必须先报。断言顺序稳定，避免日志随字段遍历顺序漂移。
		{
			System.out.println("-- 7) 多变化时的优先级 --");
			var old = list(plain("a", "I"));
			var neu = list(plain("a", "J"), capture("val$new", "I"));
			var r = LayoutGate.check(old, neu);
			check(r.verdict() == Verdict.CHANGED_FIELD_TYPE,
				"同时有改类型与新增时，报改类型（实际 " + r.verdict() + "）");
		}

		// ---------- 8) 合成字段判定：ACC_SYNTHETIC 与名字前缀双保险 ----------
		{
			System.out.println("-- 8) 合成判定双保险 --");
			var viaFlag = LayoutGate.of(List.of(fieldNode("weird", "I", 0, true)));
			check(viaFlag.size() == 1 && viaFlag.get(0).isSynthetic(),
				"靠 ACC_SYNTHETIC 认出无前缀的合成字段");
			var viaName = LayoutGate.of(List.of(fieldNode("val$byName", "I", 0, false)));
			check(viaName.get(0).isSynthetic(),
				"靠 val$ 前缀认出丢了标志位的捕获字段");
			var thisZero = LayoutGate.of(List.of(fieldNode("this$0", "LHost;", 0, false)));
			check(thisZero.get(0).isSynthetic(), "this$0 被认作合成捕获");
			var plainF = LayoutGate.of(List.of(fieldNode("normal", "I", 0, false)));
			check(!plainF.get(0).isSynthetic(), "普通字段不被误判为合成");
		}

		// ---------- 9) 空字段表 ----------
		{
			System.out.println("-- 9) 边界 --");
			check(LayoutGate.check(list(), list()).compatible(), "两侧都空 → 放行");
			check(LayoutGate.check(list(), list(plain("a", "I"))).compatible(),
				"旧空新有普通字段 → 放行");
			check(!LayoutGate.check(list(), list(capture("val$x", "I"))).compatible(),
				"旧空新有捕获字段 → 拒绝");
		}

		// ---------- 10) 重定义层入口：ClassDiff.changedFields 规则 ----------
		//
		// 这一层是"全体类（具名 + 匿名）"的门，判据是 ClassDiffUtil 现成的 changedFields。
		// 格式："+ name" / "- name"，静态字段带 "*" 前缀。按名字把 +/- 配对。
		{
			System.out.println("-- 10) 重定义层（changedFields）规则 --");

			check(LayoutGate.checkChangedFields(List.of()).compatible(), "空列表 → 放行");
			check(LayoutGate.checkChangedFields(null).compatible(), "null → 放行");

			var add = LayoutGate.checkChangedFields(List.of("+ b"));
			check(add.verdict() == Verdict.COMPATIBLE, "纯新增 → 放行（InitFix 补）(实际 " + add.verdict() + ")");
			check(add.detail().contains("b"), "原因里写明新增了哪个字段");

			var del = LayoutGate.checkChangedFields(List.of("- c"));
			check(del.verdict() == Verdict.REMOVED_FIELD, "纯删除 → REMOVED_FIELD (实际 " + del.verdict() + ")");
			check(del.detail().contains("c"), "原因里写明删了哪个字段");

			var type = LayoutGate.checkChangedFields(List.of("+ a", "- a"));
			check(type.verdict() == Verdict.CHANGED_FIELD_TYPE,
				"同名改类型（同 static 位）→ CHANGED_FIELD_TYPE (实际 " + type.verdict() + ")");

			var stat1 = LayoutGate.checkChangedFields(List.of("- a", "+ *a"));
			check(stat1.verdict() == Verdict.CHANGED_STATICNESS,
				"实例→静态（- a / + *a）→ CHANGED_STATICNESS (实际 " + stat1.verdict() + ")");

			var stat2 = LayoutGate.checkChangedFields(List.of("- *a", "+ a"));
			check(stat2.verdict() == Verdict.CHANGED_STATICNESS,
				"静态→实例（- *a / + a）→ CHANGED_STATICNESS (实际 " + stat2.verdict() + ")");

			var statType = LayoutGate.checkChangedFields(List.of("+ *s", "- *s"));
			check(statType.verdict() == Verdict.CHANGED_FIELD_TYPE,
				"静态字段改类型（两侧都带 *）→ CHANGED_FIELD_TYPE (实际 " + statType.verdict() + "）");

			// 改类型优先于删除报出（两者都危险，但改类型更直接破坏旧值语义）
			var mixed = LayoutGate.checkChangedFields(List.of("- c", "+ a", "- a"));
			check(mixed.verdict() == Verdict.CHANGED_FIELD_TYPE,
				"同时有改类型与删除 → 报改类型 (实际 " + mixed.verdict() + ")");
		}

		// ---------- 11) 与真实 ClassDiffUtil 的格式对接（内存 javac，先红后绿）----------
		//
		// 第 10 节的字符串是我手写的；这一节用真 javac 编 v1/v2，跑 ClassDiffUtil.diff，
		// 证明 changedFields 的真实格式与 checkChangedFields 的解析一致 —— 手写格式若与
		// 实现漂移，这里立刻变红。
		{
			System.out.println("-- 11) 真实 ClassDiffUtil.changedFields 对接 --");
			String cls = "lg.LgSubject";
			Map<String, String> base = new LinkedHashMap<>();
			base.put(cls, "package lg;\n" +
				"public class LgSubject {\n" +
				"    int a;\n" +
				"    int c;\n" +
				"    static int s;\n" +
				"}\n");
			byte[] v1 = compile(base).get(cls);

			Map<String, String> addB = new LinkedHashMap<>(base);
			addB.put(cls, "package lg;\n" +
				"public class LgSubject {\n" +
				"    int a;\n" +
				"    int c;\n" +
				"    static int s;\n" +
				"    int b;\n" +
				"}\n");
			checkFields(cls, v1, compile(addB).get(cls), Verdict.COMPATIBLE, "+ b", "纯新增 b");

			Map<String, String> delC = new LinkedHashMap<>(base);
			delC.put(cls, "package lg;\n" +
				"public class LgSubject {\n" +
				"    int a;\n" +
				"    static int s;\n" +
				"}\n");
			checkFields(cls, v1, compile(delC).get(cls), Verdict.REMOVED_FIELD, "- c", "纯删除 c");

			Map<String, String> typeA = new LinkedHashMap<>(base);
			typeA.put(cls, "package lg;\n" +
				"public class LgSubject {\n" +
				"    long a;\n" +
				"    int c;\n" +
				"    static int s;\n" +
				"}\n");
			checkFields(cls, v1, compile(typeA).get(cls), Verdict.CHANGED_FIELD_TYPE, null, "int a → long a");

			Map<String, String> staticS = new LinkedHashMap<>(base);
			staticS.put(cls, "package lg;\n" +
				"public class LgSubject {\n" +
				"    int a;\n" +
				"    int c;\n" +
				"    int s;\n" +
				"}\n");
			checkFields(cls, v1, compile(staticS).get(cls), Verdict.CHANGED_STATICNESS, null, "static int s → int s");
		}

		// ---------- 12) 嵌套位移反例守卫（重定义门不得因未改名字节码误拒）----------
		//
		// 危险形态（§3.1 描述符屏蔽缺陷的变体）：depth>=2 的嵌套匿名类位移后，
		// 内层匿名类的 `this$N` 字段描述符内嵌着父匿名类名（LOuter$1; -> LOuter$2;）。
		// 若重定义门吃到**未改名**的新字节码，会不会把它读成"字段改类型"而误拒？
		// 关键不变量：javac 给 this$N 打了 ACC_SYNTHETIC，ClassDiffUtil 会对称过滤掉它，
		// 所以 changedFields 里根本不会出现 —— 门免疫。（若将来有人去掉这个过滤，本守卫变红。）
		{
			System.out.println("-- 12) 嵌套位移反例守卫 --");
			String host = "lg3.Outer";
			Map<String, byte[]> c1 = compile(Map.of(host,
				"package lg3;\n" +
				"public class Outer {\n" +
				"    int v = 1;\n" +
				"    void run() {\n" +
				"        new Runnable() {\n" +
				"            int helper() { return 7; }\n" +
				"            public void run() {\n" +
				"                int x = v;\n" +
				"                new Runnable() {\n" +
				"                    public void run() { int y = helper(); }\n" +
				"                }.run();\n" +
				"            }\n" +
				"        }.run();\n" +
				"    }\n" +
				"}\n"));
			Map<String, byte[]> c2 = compile(Map.of(host,
				"package lg3;\n" +
				"public class Outer {\n" +
				"    int v = 1;\n" +
				"    void run() {\n" +
				"        new Runnable() { public void run() { } }.run();\n" +
				"        new Runnable() {\n" +
				"            int helper() { return 7; }\n" +
				"            public void run() {\n" +
				"                int x = v;\n" +
				"                new Runnable() {\n" +
				"                    public void run() { int y = helper(); }\n" +
				"                }.run();\n" +
				"            }\n" +
				"        }.run();\n" +
				"    }\n" +
				"}\n"));

			byte[] oldInner = c1.get("lg3.Outer$1$1");
			byte[] rawNewInner = c2.get("lg3.Outer$2$1");
			check(oldInner != null && rawNewInner != null,
				"夹具前提：编译出内层匿名类 Outer$1$1 与位移后的 Outer$2$1");

			// 反例前提：字段表视图（含合成字段）确实因位移而不同 —— 否则本守卫是空过。
			var fOld = LayoutGate.of(parse(oldInner).fields);
			var fNewRaw = LayoutGate.of(parse(rawNewInner).fields);
			check(!fOld.isEmpty(), "夹具前提：内层匿名类带捕获字段（this$N）实际 " + fOld);
			check(LayoutGate.check(fOld, fNewRaw).verdict() == Verdict.CHANGED_FIELD_TYPE,
				"反例前提：未改名时字段表确有类型差异（this$N 描述符内嵌的父类名位移）");

			// 关键不变量 + 守卫：changedFields 过滤掉合成字段，门对未改名/已改名都放行。
			ClassDiffUtil.ClassDiff raw = ClassDiffUtil.diff(oldInner, rawNewInner);
			check(raw.changedFields.isEmpty(),
				"关键不变量：this$N 是 ACC_SYNTHETIC，被 ClassDiff 过滤 → changedFields 为空（实际 "
					+ raw.changedFields + "）");
			check(LayoutGate.checkChangedFields(raw.changedFields).compatible(),
				"反例守卫：未改名字节码喂给重定义门也必须放行（不得因描述符位移误拒）");

			// 正路径：走真实对齐后，链位移正确归位，且重定义门对每一对齐类都放行。
			AnonClassAligner.Result res = AnonClassAligner.align("lg3/Outer", c2.get(host),
				anonUnder(host, c1), anonUnder(host, c2),
				n -> c1.get(n.replace('/', '.')), n -> c2.get(n.replace('/', '.')), n -> false);
			check("lg3/Outer$1".equals(res.renameMap.get("lg3/Outer$2")),
				"对齐：新 Outer$2(原链头) 归位旧 Outer$1（实际 " + res.renameMap + "）");
			check("lg3/Outer$1$1".equals(res.renameMap.get("lg3/Outer$2$1")),
				"对齐：新 Outer$2$1 归位旧 Outer$1$1");
			for (Map.Entry<String, byte[]> e : res.alignedAnonClasses.entrySet()) {
				byte[] oldB = c1.get(e.getKey().replace('/', '.'));
				if (oldB == null) continue;
				ClassDiffUtil.ClassDiff d = ClassDiffUtil.diff(oldB, e.getValue());
				check(LayoutGate.checkChangedFields(d.changedFields).compatible(),
					"对齐后 " + e.getKey() + " 重定义门放行（changedFields=" + d.changedFields + "）");
			}
		}

		System.out.println();
		System.out.println("通过 " + passed + " 条；失败 " + failed + " 条");
		System.out.println(failed == 0 ? "ALL ASSERTIONS PASSED" : (failed + " ASSERTION(S) FAILED"));
		if (failed != 0) System.exit(1);
	}

	/** 收集某宿主下的匿名类（点分类名 → 字节码）。 */
	static Map<String, byte[]> anonUnder(String host, Map<String, byte[]> classes) {
		Map<String, byte[]> out = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : classes.entrySet()) {
			if (AnonClassAligner.isAnonymousClassName(host, e.getKey())) out.put(e.getKey(), e.getValue());
		}
		return out;
	}

	/** 解析字节码为 ASM ClassNode（保留字段访问标志，不 SKIP）。 */
	static org.objectweb.asm.tree.ClassNode parse(byte[] bytes) {
		var cn = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(bytes).accept(cn, 0);
		return cn;
	}

	/** 用真实 ClassDiffUtil 从两版字节码算出 changedFields，再喂给重定义层入口。 */
	static void checkFields(String cls, byte[] v1, byte[] v2, Verdict expected, String rawEntry, String label) {
		ClassDiffUtil.ClassDiff d = ClassDiffUtil.diff(v1, v2);
		var r = LayoutGate.checkChangedFields(d.changedFields);
		check(r.verdict() == expected, "真实对接 " + label + " → " + expected
			+ " (实际 " + r.verdict() + ", changedFields=" + d.changedFields + "）");
		if (rawEntry != null) {
			check(d.changedFields.contains(rawEntry),
				"真实对接 " + label + " 的 changedFields 含字面量 \"" + rawEntry
				+ "\"（实际 " + d.changedFields + "）");
		}
	}

	/** 内存 javac：编译一组源文件并返回 点分类名 → 字节码。 */
	static Map<String, byte[]> compile(Map<String, String> sources) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) throw new IllegalStateException("需要 JDK 运行（找不到系统 Java 编译器）");

		Map<String, byte[]> out = new LinkedHashMap<>();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		StandardJavaFileManager standard =
			compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);

		JavaFileManager memory = new ForwardingJavaFileManager<StandardJavaFileManager>(standard) {
			@Override
			public JavaFileObject getJavaFileForOutput(Location location, String className,
			                                           JavaFileObject.Kind kind, FileObject sibling) {
				URI uri = URI.create("mem:///" + className.replace('.', '/') + kind.extension);
				return new SimpleJavaFileObject(uri, kind) {
					@Override
					public OutputStream openOutputStream() {
						return new ByteArrayOutputStream() {
							@Override
							public void close() throws IOException {
								super.close();
								out.put(className, toByteArray());
							}
						};
					}
				};
			}
		};

		List<JavaFileObject> units = new ArrayList<>();
		for (Map.Entry<String, String> e : sources.entrySet()) {
			URI uri = URI.create("string:///" + e.getKey().replace('.', '/') + ".java");
			units.add(new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
				@Override
				public CharSequence getCharContent(boolean ignoreEncodingErrors) {
					return e.getValue();
				}
			});
		}

		boolean ok = Boolean.TRUE.equals(compiler.getTask(null, memory, diagnostics,
			List.of("-proc:none", "-nowarn"), null, units).call());
		if (!ok) {
			StringBuilder sb = new StringBuilder("夹具编译失败：");
			for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
				sb.append("\n      ").append(d.getKind()).append(' ').append(d.getMessage(null));
			}
			throw new IllegalStateException(sb.toString());
		}
		return out;
	}

	/** 造一个 ASM FieldNode（避免测试依赖具体构造器重载）。 */
	static org.objectweb.asm.tree.FieldNode fieldNode(String name, String desc, int access, boolean synthetic) {
		int acc = access | (synthetic ? org.objectweb.asm.Opcodes.ACC_SYNTHETIC : 0);
		return new org.objectweb.asm.tree.FieldNode(
			org.objectweb.asm.Opcodes.ASM9, acc, name, desc, null, null);
	}
}
