import nipx.AnonClassAligner;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.*;

/**
 * 探针：匿名类**词法嵌套深度**对对齐器的影响。
 *
 * <p>回答的问题：规格把支持范围写成 "depth &lt;= 4"，而 `maxLevel &gt; 4` 只 warn。
 * 这个 4 是来自真实约束，还是从一个从不生效的常量（{@code AnonClassHasher.MAX_DEPTH}）抄来的？</p>
 *
 * <p>做法：为 N = 1..8 各生成一对源码（v1 是 N 层嵌套匿名类链；v2 在顶层**插入**一个额外匿名类，
 * 使整条链的物理编号位移），然后要求对齐器把 v2 的每一层映射回 v1 的同一层。</p>
 *
 * <p>不参与 {@code suite.sh}，纯诊断用途。</p>
 */
public class DeepNestProbe {

	static int failed = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   PASS  " : "   FAIL  ") + msg);
		if (!ok) failed++;
	}

	public static void main(String[] args) throws Exception {
		String javac = System.getenv("HSTEST_JAVAC21");
		if (javac == null) javac = "F:/files/java/jdks/openjdk-21.0.2/bin/javac.exe";
		if (!new File(javac).exists()) javac = "javac";

		File base = Files.createTempDirectory("deepnest").toFile();
		System.out.println("=== DeepNestProbe: 嵌套深度 vs 对齐质量 ===");
		System.out.println("javac = " + javac);
		try {
			for (int n : new int[] { 1, 2, 3, 4, 5, 6, 8 }) {
				runCase(javac, base, n, false);   // 纯位移：payload 不变
				runCase(javac, base, n, true);    // payload 也变：迫使 Tier 3
			}
			System.out.println("\n=== 特征 dump（用于定位为什么深层的 Tier 1 失效）===");
			dump(javac, base, 1, true);
			dump(javac, base, 2, true);
			probeLambdaAnonAlternation(javac, base);
			probeDescriptorSpecificity(javac, base);
			probeNoLambdaHierarchyLevel();
		} finally {
			deleteRecursively(base);
		}
		System.out.println();
		System.out.println(failed == 0 ? "DEEPNEST PROBE: ALL OK" : ("DEEPNEST PROBE: FAILED=" + failed));
	}

	/**
	 * 决定性实验：Lambda 与匿名类**交替嵌套**时 ——
	 * <ol>
	 *   <li>javac 到底怎么给内层匿名类命名？Lambda 会不会构成一个命名层级？</li>
	 *   <li>内层匿名类的"创建点"该去哪个类里扫？宿主类还是直接父类？</li>
	 * </ol>
	 * 这两点决定了"是否必须把 Lambda 树与匿名类树合并成一棵异构树"。
	 */
	static void probeLambdaAnonAlternation(String javac, File base) throws Exception {
		System.out.println("\n=== 交替嵌套实验（Lambda → 匿名类 → Lambda → 匿名类）===");
		File dir = new File(base, "alt");
		File out = new File(dir, "out");
		out.mkdirs();
		File src = new File(dir, "Alt.java");
		Files.writeString(src.toPath(),
			"package deep;\n" +
			"class Alt {\n" +
			"    static class Worker { void work() {} }\n" +
			"    static class Task {}\n" +
			"    void run() {\n" +
			"        Runnable a = () -> {                       // L0\n" +
			"            new Worker() {                         // A0\n" +
			"                void work() {\n" +
			"                    Runnable b = () -> {           // L1（在 A0 内部）\n" +
			"                        new Task() {};             // A1（在 L1 内部）\n" +
			"                    };\n" +
			"                    b.run();\n" +
			"                }\n" +
			"            }.work();\n" +
			"        };\n" +
			"        a.run();\n" +
			"    }\n" +
			"}\n");
		compile(javac, out, src);

		File pkgDir = new File(out, "deep");
		List<String> names = new ArrayList<>();
		for (File f : pkgDir.listFiles()) names.add(f.getName());
		Collections.sort(names);
		System.out.println("  javac 产物: " + names);

		java.util.function.Function<String, byte[]> res =
			n -> readIfExists(new File(out, n.replace('.', '/') + ".class"));

		for (String n : names) {
			if (!n.endsWith(".class") || !n.contains("$")) continue;
			String slash = "deep/" + n.substring(0, n.length() - 6);
			// 只关心真正的匿名类；Alt$Task / Alt$Worker 是具名嵌套类（顺带证明名字闸门有效）
			if (!AnonClassAligner.isAnonymousClassName("deep/Alt", slash)) {
				System.out.println("  " + slash + "  → 具名嵌套类，不参与对齐（名字闸门排除）");
				continue;
			}
			org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
			new org.objectweb.asm.ClassReader(res.apply(slash))
				.accept(cn, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
			// 用宿主节点扫
			AnonClassAligner.EnclosingMethodInfo viaHost = AnonClassAligner.resolveHostMethodForAnon("deep/Alt",
				AnonClassAligner.parseHostNode("deep/Alt", res), slash);
			// 用直接父类节点扫
			int lastDollar = slash.lastIndexOf('$');
			String parent = lastDollar > 0 ? slash.substring(0, lastDollar) : "deep/Alt";
			AnonClassAligner.EnclosingMethodInfo viaParent = AnonClassAligner.resolveHostMethodForAnon(parent,
				AnonClassAligner.parseHostNode(parent, res), slash);
			System.out.println("  " + slash
				+ "\n      EnclosingMethod = " + cn.outerClass + "." + cn.outerMethod + cn.outerMethodDesc
				+ "\n      用【宿主 deep/Alt】扫创建点 -> " + fmt(viaHost)
				+ "\n      用【父类 " + parent + "】扫创建点 -> " + fmt(viaParent));
		}
	}

	static String fmt(AnonClassAligner.EnclosingMethodInfo e) {
		return e == null ? "null（找不到实例化点）" : (e.name + e.desc);
	}

	/**
	 * 守卫评审提出的"定向屏蔽"要求：**不能**把一般方法描述符整体降权。
	 *
	 * <p>夹具里两个匿名类的唯一差异是一个重载方法的描述符（{@code extra(I)V} vs
	 * {@code extra(Ljava/lang/String;)V}），两者都不引用匿名类。若屏蔽是"一刀切"的，
	 * 这两者会撞哈希、候选域被无端扩大；正确的定向屏蔽必须让它们仍然可区分。</p>
	 */
	static void probeDescriptorSpecificity(String javac, File base) throws Exception {
		System.out.println("\n=== 描述符定向屏蔽守卫（不引用匿名类的描述符必须保持区分度）===");
		File dir = new File(base, "desc");
		File out = new File(dir, "out");
		out.mkdirs();
		File src = new File(dir, "Desc.java");
		Files.writeString(src.toPath(),
			"package deep;\n" +
			"class Desc {\n" +
			"    interface I { void m(Object x); }\n" +
			"    void go() {\n" +
			"        I a = new I() { public void m(Object x) {}  public void extra(int v) {} };\n" +
			"        I b = new I() { public void m(Object x) {}  public void extra(String v) {} };\n" +
			"    }\n" +
			"}\n");
		compile(javac, out, src);

		java.util.function.Function<String, byte[]> res =
			n -> readIfExists(new File(out, n.replace('.', '/') + ".class"));
		Long ha = nipx.AnonClassHasher.hash("deep/Desc$1", res.apply("deep/Desc$1"), "deep/Desc", res, null, null, 0);
		Long hb = nipx.AnonClassHasher.hash("deep/Desc$2", res.apply("deep/Desc$2"), "deep/Desc", res, null, null, 0);
		System.out.println("  Desc$1(extra(I)V)=" + hex(ha) + "  Desc$2(extra(Ljava/lang/String;)V)=" + hex(hb));
		check(ha != null && hb != null && !ha.equals(hb),
			"extra(I)V 与 extra(Ljava/lang/String;)V 不含匿名类引用 → 屏蔽不得抹平该差异（哈希必须不同）");
	}

	/** 守卫"Lambda 不构成命名层级"这一实测事实：以后若有人为统一拓扑而引入 Lambda 层，这里会红。 */
	static void probeNoLambdaHierarchyLevel() {
		System.out.println("\n=== 命名层级守卫（Lambda 不增加 $ 段）===");
		String host = "deep/Alt";
		System.out.println("  parent(Alt$1$1) = " + AnonClassAligner.getParentName(host, "deep/Alt$1$1")
			+ ", level(Alt$1)=" + AnonClassAligner.getHierarchyLevel(host, "deep/Alt$1")
			+ ", level(Alt$1$1)=" + AnonClassAligner.getHierarchyLevel(host, "deep/Alt$1$1"));
		check("deep/Alt$1".equals(AnonClassAligner.getParentName(host, "deep/Alt$1$1")),
			"Alt$1$1 的父必须是 Alt$1（中间的 lambda 不产生 $ 段）");
		check(AnonClassAligner.getHierarchyLevel(host, "deep/Alt$1") == 1
			&& AnonClassAligner.getHierarchyLevel(host, "deep/Alt$1$1") == 2,
			"层级必须按匿名类二进制名数 $ 段：A0=1、A1=2（若变成 3 说明引入了 Lambda 层级）");
		check(AnonClassAligner.isAnonymousClassName(host, "deep/Alt$1$1")
			&& !AnonClassAligner.isAnonymousClassName(host, "deep/Alt$Task"),
			"名字闸门仍只放行纯数字后缀（Alt$1$1 准入、Alt$Task 排除）");
	}

	static String hex(Long h) {
		return h == null ? "null" : Long.toHexString(h);
	}

	/** 逐类 dump 特征，用来验证"深层 Tier 1 为什么失效"的具体机制。 */
	static void dump(String javac, File base, int levels, boolean payloadChanged) throws Exception {
		String tag = "d" + levels + (payloadChanged ? "b" : "a");
		File dir = new File(base, tag);
		File v1 = new File(dir, "v1"), v2 = new File(dir, "v2");
		v1.mkdirs();
		v2.mkdirs();
		File s1 = new File(dir, "V1.java");
		Files.writeString(s1.toPath(), gen("deep", "Deep", levels, "TAG", false));
		File s2 = new File(dir, "V2.java");
		Files.writeString(s2.toPath(), gen("deep", "Deep", levels,
			payloadChanged ? "TAG2" : "TAG", true));
		compile(javac, v1, s1);
		compile(javac, v2, s2);

		System.out.println("\n--- dump depth=" + levels + " payloadChanged=" + payloadChanged + " ---");
		java.util.function.Function<String, byte[]> oldRes =
			n -> readIfExists(new File(v1, n.replace('.', '/') + ".class"));
		java.util.function.Function<String, byte[]> newRes =
			n -> readIfExists(new File(v2, n.replace('.', '/') + ".class"));
		dumpSide("旧 v1", anonMap(v1), oldRes);
		dumpSide("新 v2", anonMap(v2), newRes);
	}

	static void dumpSide(String label, Map<String, byte[]> classes, java.util.function.Function<String, byte[]> res) {
		System.out.println("  " + label + ":");
		for (Map.Entry<String, byte[]> e : classes.entrySet()) {
			String n = e.getKey();
			org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
			new org.objectweb.asm.ClassReader(e.getValue())
				.accept(cn, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
			List<String> fs = new ArrayList<>();
			for (org.objectweb.asm.tree.FieldNode f : cn.fields) fs.add(f.name + ":" + f.desc);
			List<String> ms = new ArrayList<>();
			for (org.objectweb.asm.tree.MethodNode m : cn.methods) {
				boolean nonSynthetic = (m.access & org.objectweb.asm.Opcodes.ACC_SYNTHETIC) == 0
					&& !m.name.startsWith("lambda$");
				// AnonClassHasher 的分流：非合成方法进 methodSignatures（**会带原始描述符**），其余只进 methodHashes
				ms.add(m.name + m.desc + "[" + Integer.toHexString(m.access)
					+ (nonSynthetic ? " ->签名(含desc)" : " ->仅hash") + "]");
			}
			Collections.sort(fs);
			Collections.sort(ms);
			Long h = nipx.AnonClassHasher.hash(n, e.getValue(), "deep/Deep", res, null, null, 0);
			String hostMethod = AnonClassAligner.resolveHostMethodForAnon("deep/Deep", n, res);
			System.out.println("    " + n
				+ "\n        hash=" + (h == null ? "null" : Long.toHexString(h))
				+ " super=" + cn.superName + " outer=" + cn.outerClass + "." + cn.outerMethod + cn.outerMethodDesc
				+ " resolvedHost=" + hostMethod
				+ "\n        fields=" + fs + " methods=" + ms);
		}
	}

	static void runCase(String javac, File base, int levels, boolean payloadChanged) throws Exception {
		String tag = "n" + levels + (payloadChanged ? "b" : "a");
		File dir = new File(base, tag);
		File v1 = new File(dir, "v1"), v2 = new File(dir, "v2");
		v1.mkdirs();
		v2.mkdirs();

		File s1 = new File(dir, "V1.java");
		Files.writeString(s1.toPath(), gen("deep", "Deep", levels, "TAG", false));
		File s2 = new File(dir, "V2.java");
		Files.writeString(s2.toPath(), gen("deep", "Deep", levels,
			payloadChanged ? "TAG2" : "TAG", true));

		compile(javac, v1, s1);
		compile(javac, v2, s2);

		byte[] host1 = Files.readAllBytes(new File(v1, "deep/Deep.class").toPath());
		byte[] host2 = Files.readAllBytes(new File(v2, "deep/Deep.class").toPath());

		Map<String, byte[]> oldAnon = anonMap(v1);
		Map<String, byte[]> newAnon = anonMap(v2);

		String prefix = "payloadChanged=" + payloadChanged + " depth=" + levels;
		System.out.println("\n--- " + prefix + " ---");
		System.out.println("  v1 产物: " + v1Names(oldAnon));
		System.out.println("  v2 产物: " + v1Names(newAnon));

		// 期望：v1 第 L 层 = Deep$1$1$...（L 段），v2 第 L 层 = Deep$2$1$...（首段为 2）
		Map<String, String> expect = new LinkedHashMap<>();
		for (int l = 1; l <= levels; l++) {
			expect.put(levelName("deep/Deep", 2, l), levelName("deep/Deep", 1, l));
		}
		System.out.println("  期望映射: " + expect);

		AnonClassAligner.Result res = AnonClassAligner.align(
			"deep/Deep", host2, oldAnon, newAnon,
			name -> readIfExists(new File(v1, name.replace('.', '/') + ".class")),
			name -> readIfExists(new File(v2, name.replace('.', '/') + ".class")));

		System.out.println("  实际映射: " + res.renameMap);
		System.out.println("  统计    : " + res.stats + ", 孤儿=" + res.orphanOldClasses);

		// 1) 每一层都必须映射回同层
		boolean allLevelsOk = true;
		for (Map.Entry<String, String> e : expect.entrySet()) {
			String got = res.renameMap.get(e.getKey());
			if (!e.getValue().equals(got)) {
				allLevelsOk = false;
				System.out.println("    !! 层不匹配: " + e.getKey() + " 期望 " + e.getValue() + " 实际 " + got);
			}
		}
		if (!allLevelsOk) failed++;

		// 2) 不允许有孤儿（每一层都应匹配上）
		boolean orphansOk = res.orphanOldClasses.isEmpty();
		if (!orphansOk) failed++;

		// 3) 不允许有"多候选 minDiff 盲猜"（最终手段不该被触发）
		boolean noGuess = res.stats.ambiguousMatches == 0;
		if (!noGuess) failed++;

		// 4) 用来判定"哪一层最强"：Tier1=内容哈希命中，Tier3=结构签名
		int tier1 = res.stats.tier1Matches;
		int tier3 = res.stats.tier3Matches;
		int tier4 = res.stats.tier4Matches;
		System.out.println("  判定    : 逐层一致=" + allLevelsOk + " 无孤儿=" + orphansOk
			+ " 无盲猜=" + noGuess + " | T1=" + tier1 + " T3=" + tier3 + " T4=" + tier4);
	}

	/**
	 * 生成 levels 层嵌套匿名类。v2 时在顶层额外插入一个匿名类，使整条链编号位移。
	 *
	 * <p>javac 的命名：顶层第一个匿名类是 {@code Deep$1}；声明在 {@code Deep$1} 内部的匿名类是
	 * {@code Deep$1$1}，依此类推 —— 因此嵌套深度直接等于名字里的数字段数。</p>
	 */
	static String gen(String pkg, String cls, int levels, String payload, boolean insertTop) {
		StringBuilder sb = new StringBuilder();
		sb.append("package ").append(pkg).append(";\n");
		// 故意不用 public：这样源文件名不必等于类名，探针可以把 v1/v2 写成 V1.java / V2.java
		sb.append("class ").append(cls).append(" {\n");
		sb.append("    @SuppressWarnings(\"unused\")\n");
		sb.append("    public void setup() {\n");
		String pad = "        ";
		if (insertTop) {
			sb.append(pad).append("Runnable extra = new Runnable() { public void run() { System.out.println(\"EXTRA\"); } };\n");
		}
		for (int k = 1; k <= levels; k++) {
			sb.append(pad).append("Runnable n").append(k)
			  .append(" = new Runnable() { public void run() {\n");
			pad = pad + "    ";
		}
		sb.append(pad).append("System.out.println(\"").append(payload).append("\");\n");
		for (int k = levels; k >= 1; k--) {
			pad = pad.substring(4);
			sb.append(pad).append("}};\n");
		}
		sb.append("    }\n");
		sb.append("}\n");
		return sb.toString();
	}

	/** v1 的第 L 层名：{@code Deep$1} + ("$1" * (L-1))；v2 首段换成 2。 */
	static String levelName(String hostSlash, int firstSeg, int level) {
		StringBuilder sb = new StringBuilder(hostSlash).append("$").append(firstSeg);
		for (int i = 2; i <= level; i++) sb.append("$1");
		return sb.toString();
	}

	static Map<String, byte[]> anonMap(File out) throws Exception {
		Map<String, byte[]> m = new LinkedHashMap<>();
		File pkgDir = new File(out, "deep");
		File[] files = pkgDir.listFiles();
		if (files == null) return m;
		List<File> sorted = new ArrayList<>(Arrays.asList(files));
		sorted.sort(Comparator.comparing(File::getName));
		for (File f : sorted) {
			if (!f.getName().endsWith(".class")) continue;
			String n = "deep/" + f.getName().substring(0, f.getName().length() - 6);
			if (n.contains("$")) m.put(n, Files.readAllBytes(f.toPath()));
		}
		return m;
	}

	static String v1Names(Map<String, byte[]> m) {
		return new ArrayList<>(m.keySet()).toString();
	}

	static void compile(String javac, File out, File src) throws Exception {
		Process p = new ProcessBuilder(javac, "-nowarn", "-encoding", "UTF-8",
			"-d", out.getAbsolutePath(), src.getAbsolutePath())
			.redirectErrorStream(true).start();
		StringBuilder log = new StringBuilder();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
			String line;
			while ((line = r.readLine()) != null) log.append(line).append('\n');
		}
		if (p.waitFor() != 0) throw new RuntimeException("javac failed:\n" + log);
	}

	static byte[] readIfExists(File f) {
		try {
			return f.exists() ? Files.readAllBytes(f.toPath()) : null;
		} catch (Exception e) {
			return null;
		}
	}

	static void deleteRecursively(File f) {
		if (f == null || !f.exists()) return;
		File[] children = f.listFiles();
		if (children != null) for (File c : children) deleteRecursively(c);
		f.delete();
	}
}
