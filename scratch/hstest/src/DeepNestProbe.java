import nipx.AnonClassAligner;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

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
			probeJavac8NestedFallback(base);
			probeJavac8TwoChain(base);
			probeTier3TopologyDiscrimination(base);
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
	 * 端到端测量 §8.3 第 5 条：javac 8 下"同一父匿名类里两个方法各含一条 lambda→lambda→匿名类链"，
	 * v2 把**方法声明顺序反转**且两侧方法体都改（于是 Tier 1 失效，只能靠 scope 区分）。
	 *
	 * <p>修复前两侧 `outerMethod` 都停在字面量 `"null"`（无法区分方法），2×2 歧义只能按物理序号仲裁
	 * → 跨方法错配；修复后 `outerMethod` 分别成为 `alpha` / `beta` → Tier 3 双向唯一、正确跨方法对应。</p>
	 */
	static void probeJavac8TwoChain(File base) throws Exception {
		String javac8 = System.getenv("HSTEST_JAVAC8");
		if (javac8 == null) javac8 = "F:/files/java/jdks/jdk-1.8/bin/javac.exe";
		System.out.println("\n=== javac 8 双方法链：回退扫描上下文（" + javac8 + "）===");
		if (!new File(javac8).exists()) {
			System.out.println("  SKIP  javac 8 不存在");
			return;
		}
		File dir = new File(base, "two8");
		File v1 = new File(dir, "v1"), v2 = new File(dir, "v2");
		v1.mkdirs();
		v2.mkdirs();
		File s1 = new File(dir, "V1.java");
		Files.writeString(s1.toPath(), twoChainSource("alpha", "ALPHA", "beta", "BETA"));
		File s2 = new File(dir, "V2.java");
		Files.writeString(s2.toPath(), twoChainSource("beta", "BETA2", "alpha", "ALPHA2"));
		compile(javac8, v1, s1);
		compile(javac8, v2, s2);

		Map<String, byte[]> oldAnon = anonMapOf(v1, "testJ8Nest/TwoChain", 2);
		Map<String, byte[]> newAnon = anonMapOf(v2, "testJ8Nest/TwoChain", 2);
		System.out.println("  v1: " + oldAnon.keySet());
		System.out.println("  v2: " + newAnon.keySet());
		for (Map.Entry<String, byte[]> e : oldAnon.entrySet()) dumpOuter(e.getKey(), e.getValue(), v1);
		for (Map.Entry<String, byte[]> e : newAnon.entrySet()) dumpOuter(e.getKey(), e.getValue(), v2);

		AnonClassAligner.Result res = AnonClassAligner.align(
			"testJ8Nest/TwoChain",
			Files.readAllBytes(new File(v2, "testJ8Nest/TwoChain.class").toPath()),
			oldAnon, newAnon,
			n -> readIfExists(new File(v1, n.replace('.', '/') + ".class")),
			n -> readIfExists(new File(v2, n.replace('.', '/') + ".class")));
		System.out.println("  renameMap = " + res.renameMap);
		System.out.println("  stats     = " + res.stats);
	}

	static void dumpOuter(String slash, byte[] bytes, File out) {
		org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(bytes)
			.accept(cn, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
		AnonClassAligner.EnclosingMethodInfo viaParent = AnonClassAligner.resolveHostMethodForAnon(
			AnonClassAligner.getParentName("testJ8Nest/TwoChain", slash),
			AnonClassAligner.parseHostNode(AnonClassAligner.getParentName("testJ8Nest/TwoChain", slash),
				n -> readIfExists(new File(out, n.replace('.', '/') + ".class"))),
			slash);
		System.out.println("    " + slash + "  EM=" + cn.outerMethod + "  父类扫描 -> " + fmt(viaParent));
	}

	static Map<String, byte[]> anonMapOf(File out, String hostSlash, int minLevel) throws Exception {
		Map<String, byte[]> m = new LinkedHashMap<>();
		int lastSlash = hostSlash.lastIndexOf('/');
		String pkg = lastSlash > 0 ? hostSlash.substring(0, lastSlash) : "";
		File pkgDir = pkg.isEmpty() ? out : new File(out, pkg);
		File[] fs = pkgDir.listFiles();
		if (fs == null) return m;
		java.util.Arrays.sort(fs, java.util.Comparator.comparing(File::getName));
		for (File f : fs) {
			if (!f.getName().endsWith(".class")) continue;
			String n = (pkg.isEmpty() ? "" : pkg + "/") + f.getName().substring(0, f.getName().length() - 6);
			if (AnonClassAligner.isAnonymousClassName(hostSlash, n)
				&& AnonClassAligner.getHierarchyLevel(hostSlash, n) >= minLevel) {
				m.put(n, Files.readAllBytes(f.toPath()));
			}
		}
		return m;
	}

	/** 两条链的夹具源码；方法声明顺序与 payload 由参数控制。 */
	static String twoChainSource(String m1, String p1, String m2, String p2) {
		return "package testJ8Nest;\n" +
			"class TwoChain {\n" +
			"    static class Worker { void work() {} }\n" +
			"    void go() {\n" +
			"        new Worker() {\n" +
			chain(m1, p1) +
			chain(m2, p2) +
			"        }.work();\n" +
			"    }\n" +
			"}\n";
	}

	static String chain(String method, String payload) {
		return "            void " + method + "() {\n" +
			"                Runnable a = () -> {\n" +
			"                    Runnable b = () -> {\n" +
			"                        new Runnable() { public void run() { System.out.println(\"" + payload + "\"); } };\n" +
			"                    };\n" +
			"                    b.run();\n" +
			"                };\n" +
			"                a.run();\n" +
			"            }\n";
	}

	// ==================================================================================
	// Tier 3 candidate topology probe（只读实验）
	//
	// 目标问题：Tier 3 发生 1-to-N 时，child topology 能否提供**比 minDiff 更强**的证据？
	// 注意判据不能放 Tier 4 —— Tier 3 的 minDiff 已经把候选裁掉，Tier 4 根本触达不到。
	//
	// 本实验不修改任何生产代码：自行重建 Tier 3 候选集与各维度取值，然后观察
	//   correct candidate 是否 topoDistance == 0 而 wrong candidate > 0。
	// ==================================================================================

	/** 粗粒度拓扑画像 + 结构签名 + 内容哈希。刻意不做递归 topology hash。 */
	static final class Profile {
		String slash;
		int    level;
		int    anonChildren;      // 直接匿名子类个数（按名字层级：直接父类 == 本类）
		int    anonDescendants;   // 更深的匿名后代个数
		int    indySites;         // invokedynamic 数量（lambda 创建点）
		int    lambdaMethods;     // 合成 lambda$ 方法个数
		List<String> childKinds = new ArrayList<>();   // 直接匿名子类的 kind 多重集：super;iface1,iface2
		Long   contentHash;       // AnonClassHasher 的自身哈希
		String superName;
		List<String> interfaces = new ArrayList<>();
		List<String> fields     = new ArrayList<>();
		List<String> methods    = new ArrayList<>();
		String outerMethod;
		String outerMethodDesc;

		String structuralKey() {
			return outerMethod + "|" + outerMethodDesc + "|" + superName + "|" + interfaces
				+ "|" + fields + "|" + methods;
		}

		@Override public String toString() {
			return slash + "[lvl=" + level + " anonChild=" + anonChildren + " anonDesc=" + anonDescendants
				+ " indy=" + indySites + " lambdaM=" + lambdaMethods + " kinds=" + childKinds + "]";
		}
	}

	/** 重建 parseInfos 的结构提取口径（ALL fields；非合成方法 name:desc）。 */
	static Profile profileOf(String slash, byte[] bytes, String hostSlash,
	                         java.util.function.Function<String, byte[]> res) {
		Profile p = new Profile();
		p.slash = slash;
		p.level = AnonClassAligner.getHierarchyLevel(hostSlash, slash);
		ClassNode cn = new ClassNode();
		new org.objectweb.asm.ClassReader(bytes).accept(cn, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
		p.superName = cn.superName != null ? cn.superName : "java/lang/Object";
		if (cn.interfaces != null) p.interfaces.addAll(cn.interfaces);
		Collections.sort(p.interfaces);
		if (cn.fields != null) for (FieldNode f : cn.fields) p.fields.add(f.name + ":" + f.desc);
		Collections.sort(p.fields);
		if (cn.methods != null) {
			for (MethodNode mn : cn.methods) {
				boolean synthetic = (mn.access & Opcodes.ACC_SYNTHETIC) != 0;
				if (!synthetic) p.methods.add(mn.name + ":" + mn.desc);
				if (mn.name.startsWith("lambda$")) p.lambdaMethods++;
				if (mn.instructions == null) continue;
				for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn instanceof InvokeDynamicInsnNode) {
						p.indySites++;
					} else if (insn.getOpcode() == Opcodes.NEW && insn instanceof TypeInsnNode tin) {
						String target = tin.desc;
						if (!AnonClassAligner.isAnonymousClassName(hostSlash, target)) continue;
						String parent = AnonClassAligner.getParentName(hostSlash, target);
						if (parent.equals(slash)) {
							p.anonChildren++;
							byte[] cb = res.apply(target);
							String kind = "?";
							if (cb != null) {
								ClassNode cc = new ClassNode();
								new org.objectweb.asm.ClassReader(cb).accept(cc,
									org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
								List<String> itf = cc.interfaces == null ? new ArrayList<>() : new ArrayList<>(cc.interfaces);
								Collections.sort(itf);
								kind = cc.superName + ";" + itf;
							}
							p.childKinds.add(kind);
						} else if (target.startsWith(slash + "$")) {
							p.anonDescendants++;
						}
					}
				}
			}
		}
		Collections.sort(p.methods);
		Collections.sort(p.childKinds);
		p.outerMethod = AnonClassAligner.normalizeEnclosingMethod(cn.outerMethod);
		p.outerMethodDesc = cn.outerMethodDesc;
		p.contentHash = nipx.AnonClassHasher.hash(slash, bytes, hostSlash, res, null, null, 0);
		return p;
	}

	/**
	 * 粗粒度拓扑距离：计数器 L1 距离 + 子类 kind 多重集的对称差。
	 * 刻意**不**做递归 topology hash —— 第一版只回答"拓扑是否提供独立信息"。
	 */
	static int topoDistance(Profile a, Profile b) {
		int d = Math.abs(a.anonChildren - b.anonChildren)
		      + Math.abs(a.anonDescendants - b.anonDescendants)
		      + Math.abs(a.indySites - b.indySites)
		      + Math.abs(a.lambdaMethods - b.lambdaMethods);
		List<String> x = new ArrayList<>(a.childKinds), y = new ArrayList<>(b.childKinds);
		for (String s : new ArrayList<>(x)) if (y.remove(s)) x.remove(s);
		return d + x.size() + y.size();
	}

	/** Tier 3 谓词（与 matchTier 的第 3 层逐项一致）。 */
	static boolean tier3Equal(Profile n, Profile o) {
		return Objects.equals(n.outerMethod, o.outerMethod)
			&& Objects.equals(n.outerMethodDesc, o.outerMethodDesc)
			&& Objects.equals(n.superName, o.superName)
			&& Objects.equals(n.interfaces, o.interfaces)
			&& Objects.equals(n.fields, o.fields)
			&& Objects.equals(n.methods, o.methods);
	}

	static int orderIndex(String slash) {
		int i = slash.lastIndexOf('$');
		try { return Integer.parseInt(slash.substring(i + 1)); } catch (Exception e) { return -1; }
	}

	/**
	 * 主实验。两个夹具：
	 *   T（可区分）：V1 = A0{L1{A1}}；V2 = A-new(无子) + A0'{L1'{A1'}}
	 *   M（最小）：V1 = X；V2 = extra + X2（两者都无子）—— 用来界定拓扑的**能力边界**
	 */
	static void probeTier3TopologyDiscrimination(File base) throws Exception {
		System.out.println("\n=== Tier 3 candidate topology probe（只读，不改 matchTier）===");

		// ---------- 夹具 T ----------
		File tDir = new File(base, "topoT");
		File t1 = new File(tDir, "v1"), t2 = new File(tDir, "v2");
		t1.mkdirs();
		t2.mkdirs();
		File ts1 = new File(tDir, "T1.java");
		Files.writeString(ts1.toPath(), treeSource("testTopo", "Topo", false, "A0"));
		File ts2 = new File(tDir, "T2.java");
		Files.writeString(ts2.toPath(), treeSource("testTopo", "Topo", true, "A0b"));
		compile(javacOf(), t1, ts1);
		compile(javacOf(), t2, ts2);
		reportCase("夹具 T（V1: A0{L1{A1}}  →  V2: A-new + A0'{L1'{A1'}}）", "testTopo/Topo", t1, t2,
			new String[] { "testTopo/Topo$1" },                 // old: A0
			new String[] { "testTopo/Topo$1", "testTopo/Topo$2" }, // new: A-new, A0'
			"testTopo/Topo$2");                                  // correct = A0'

		// ---------- 夹具 M（最小，无子节点） ----------
		File mDir = new File(base, "topoM");
		File m1 = new File(mDir, "v1"), m2 = new File(mDir, "v2");
		m1.mkdirs();
		m2.mkdirs();
		File ms1 = new File(mDir, "M1.java");
		Files.writeString(ms1.toPath(), gen("testTopoM", "TopoM", 1, "TAG", false));
		File ms2 = new File(mDir, "M2.java");
		Files.writeString(ms2.toPath(), gen("testTopoM", "TopoM", 1, "TAG2", true));
		compile(javacOf(), m1, ms1);
		compile(javacOf(), m2, ms2);
		reportCase("夹具 M（V1: X  →  V2: extra + X2，三者皆无子节点）", "testTopoM/TopoM", m1, m2,
			new String[] { "testTopoM/TopoM$1" },
			new String[] { "testTopoM/TopoM$1", "testTopoM/TopoM$2" },
			"testTopoM/TopoM$2");

		// ---------- 夹具 L：minDiff **原本判对**的合法 1-to-N（新规则下的代价） ----------
		probeCostFixtureL(base);
	}

	/**
	 * 夹具 L：两个**拓扑全等、结构全等**的匿名类在同一方法内**原地改体**（无插入、无换序）。
	 *
	 * <p>这是 minDiff 唯一"判对"的形态：2×2 多对多，按物理序号取恒等映射恰好就是语义正确解。
	 * 而拓扑相等过滤在这里留下 2 个候选 → 新规则会**拒绝配对**（保守性损失，非状态损坏）。
	 * 本方法把这个代价量化并钉住。</p>
	 */
	static void probeCostFixtureL(File base) throws Exception {
		System.out.println("\n--- 夹具 L（合法 1-to-N：两同构类原地改体，minDiff 原本判对）---");
		File dir = new File(base, "topoL");
		File v1 = new File(dir, "v1"), v2 = new File(dir, "v2");
		v1.mkdirs();
		v2.mkdirs();
		File s1 = new File(dir, "L1.java");
		Files.writeString(s1.toPath(), twoSameSource("testTopoL", "TopoL", "AAA", "BBB"));
		File s2 = new File(dir, "L2.java");
		Files.writeString(s2.toPath(), twoSameSource("testTopoL", "TopoL", "AAA2", "BBB2"));
		compile(javacOf(), v1, s1);
		compile(javacOf(), v2, s2);

		String host = "testTopoL/TopoL";
		java.util.function.Function<String, byte[]> r1 = n -> readIfExists(new File(v1, n.replace('.', '/') + ".class"));
		java.util.function.Function<String, byte[]> r2 = n -> readIfExists(new File(v2, n.replace('.', '/') + ".class"));
		Profile o1 = profileOf(host + "$1", r1.apply(host + "$1"), host, r1);
		Profile o2 = profileOf(host + "$2", r1.apply(host + "$2"), host, r1);
		Profile n1 = profileOf(host + "$1", r2.apply(host + "$1"), host, r2);
		Profile n2 = profileOf(host + "$2", r2.apply(host + "$2"), host, r2);
		System.out.println("  old " + o1 + "\n  old " + o2 + "\n  new " + n1 + "\n  new " + n2);
		for (Profile o : new Profile[] { o1, o2 }) {
			for (Profile n : new Profile[] { n1, n2 }) {
				System.out.println("  candidate " + n.slash + " vs " + o.slash
					+ "  tier1Equal=" + (n.contentHash != null && n.contentHash.equals(o.contentHash))
					+ " tier3Equal=" + tier3Equal(n, o)
					+ " minDiff=" + Math.abs(orderIndex(n.slash) - orderIndex(o.slash))
					+ " topoDistance=" + topoDistance(n, o));
			}
		}
		AnonClassAligner.Result res = AnonClassAligner.align(host,
			Files.readAllBytes(new File(v2, host + ".class").toPath()),
			anonMapOf(v1, host, 1), anonMapOf(v2, host, 1), r1, r2);
		System.out.println("  当前实现 renameMap = " + res.renameMap + "  stats=" + res.stats);
		boolean refused = res.orphanOldClasses.size() == 2 && res.stats.topologyMatches == 0;
		check(refused,
			"夹具 L：新实现下**拒绝配对**（过滤后候选数=" + res.stats.topologyCandidatesAfter
				+ "），两条编辑都不作用于存活实例 —— 这是有意付出的保守代价，不是错配");
		System.out.println("  ⚠ 代价说明：旧实现靠 minDiff 取恒等映射**恰好正确**；新实现拒绝它。"
			+ "取舍依据是 7/9 次 Tier 3 仲裁为错配、2/9 原本正确，故选择「拒绝优于错配」；"
			+ "根治需 Tier 1.5 相似度（§8.3-4）");
	}

	/** 两个结构/拓扑全等的同构匿名类，payload 可控，用于夹具 L。 */
	static String twoSameSource(String pkg, String cls, String p1, String p2) {
		return "package " + pkg + ";\n" +
			"class " + cls + " {\n" +
			"    void setup() {\n" +
			"        Runnable a = new Runnable() { public void run() { System.out.println(\"" + p1 + "\"); } };\n" +
			"        Runnable b = new Runnable() { public void run() { System.out.println(\"" + p2 + "\"); } };\n" +
			"        a.run(); b.run();\n" +
			"    }\n" +
			"}\n";
	}

	static String javacOf() {
		String j = System.getenv("HSTEST_JAVAC21");
		if (j == null) j = "F:/files/java/jdks/openjdk-21.0.2/bin/javac.exe";
		return new File(j).exists() ? j : "javac";
	}

	static void reportCase(String title, String hostSlash, File v1, File v2,
	                       String[] oldSlashes, String[] newSlashes, String correctNewSlash) throws Exception {
		System.out.println("\n--- " + title + " ---");
		java.util.function.Function<String, byte[]> r1 = n -> readIfExists(new File(v1, n.replace('.', '/') + ".class"));
		java.util.function.Function<String, byte[]> r2 = n -> readIfExists(new File(v2, n.replace('.', '/') + ".class"));

		List<Profile> olds = new ArrayList<>();
		for (String s : oldSlashes) olds.add(profileOf(s, r1.apply(s), hostSlash, r1));
		List<Profile> news = new ArrayList<>();
		for (String s : newSlashes) news.add(profileOf(s, r2.apply(s), hostSlash, r2));
		olds.forEach(p -> System.out.println("  old " + p));
		news.forEach(p -> System.out.println("  new " + p));

		// 当前实现的实际结果
		Map<String, byte[]> oldAnon = anonMapOf(v1, hostSlash, 1);
		Map<String, byte[]> newAnon = anonMapOf(v2, hostSlash, 1);
		AnonClassAligner.Result res = AnonClassAligner.align(hostSlash,
			Files.readAllBytes(new File(v2, hostSlash + ".class").toPath()), oldAnon, newAnon, r1, r2);
		System.out.println("  当前实现 renameMap = " + res.renameMap + "  stats=" + res.stats);

		for (Profile o : olds) {
			for (Profile n : news) {
				int    diff     = Math.abs(orderIndex(n.slash) - orderIndex(o.slash));
				int    topo     = topoDistance(n, o);
				boolean tier1    = n.contentHash != null && n.contentHash.equals(o.contentHash);
				boolean tier3    = tier3Equal(n, o);
				boolean correct  = n.slash.equals(correctNewSlash);
				System.out.println("  candidate " + n.slash + " vs " + o.slash
					+ "  tier1Equal=" + tier1 + " tier3Equal=" + tier3
					+ " minDiff=" + diff + " topoDistance=" + topo
					+ (correct ? "   <= 语义正确解" : "   <= 错解"));
			}
		}

		// 实验判定：Tier 3 候选集里，正确解的拓扑距离是否严格优（0 且错解 > 0）
		Profile o0 = olds.get(0);
		Profile correctProfile = null, wrongProfile = null;
		for (Profile n : news) {
			if (n.slash.equals(correctNewSlash)) correctProfile = n; else wrongProfile = n;
		}
		boolean allTier3 = true, anyTier1 = false;
		for (Profile n : news) {
			if (!tier3Equal(n, o0)) allTier3 = false;
			if (n.contentHash != null && n.contentHash.equals(o0.contentHash)) anyTier1 = true;
		}
		int dCorrect = topoDistance(correctProfile, o0);
		int dWrong   = topoDistance(wrongProfile, o0);
		System.out.println("  判定: 所有候选 tier3Equal=" + allTier3 + " 任一 tier1Equal=" + anyTier1
			+ "  |  正确解 topoDistance=" + dCorrect + "，错解 topoDistance=" + dWrong);

		check(allTier3 && !anyTier1,
			title + "：前提成立（全部候选 Tier 3 全等、Tier 1 无法解决 → 歧义真实存在）");
		if (dCorrect == 0 && dWrong > 0) {
			check(true, title + "：**拓扑可区分**（正确解 0、" + "错解 " + dWrong + "）→ 值得进 matcher");
		} else if (dCorrect == 0 && dWrong == 0) {
			check(true, title + "：拓扑**不可区分**（两者皆为 0）→ 该候选集只能靠相似度（Tier 1.5）");
		} else {
			check(false, title + "：拓扑给出了**误导性**结果（正确解 " + dCorrect + "、错解 " + dWrong + "）");
		}
	}

	/** 夹具 T 源码：V1 = A0{L1{A1}}；insertNew 时在 A0 前插入一个无子节点的 A-new。 */
	static String treeSource(String pkg, String cls, boolean insertNew, String payload) {
		return "package " + pkg + ";\n" +
			"class " + cls + " {\n" +
			"    static class Worker { void work() {} }\n" +
			"    static class Task {}\n" +
			"    void setup() {\n" +
			"        Runnable l0 = () -> {\n" +
			(insertNew ? "            new Worker() { void work() { System.out.println(\"NEW\"); } }.work();\n" : "") +
			"            new Worker() {\n" +
			"                void work() {\n" +
			"                    Runnable l1 = () -> { System.out.println(\"" + payload + "\"); new Task() {}; };\n" +
			"                    l1.run();\n" +
			"                }\n" +
			"            }.work();\n" +
			"        };\n" +
			"        l0.run();\n" +
			"    }\n" +
			"}\n";
	}

	/**
	 * 靶子验证：**javac 8** 下嵌套匿名类的 `EnclosingMethod` 是否退化成 `lambda$null$N`，
	 * 以及"宿主扫描 vs 直接父类扫描"的差异 —— 这决定了 §8.3 第 5 条（`parseInfos` 改用父类节点）
	 * 是否真有靶子，以及 javac 8 的嵌套命名是否同样是 `$1$1`。
	 */
	static void probeJavac8NestedFallback(File base) throws Exception {
		String javac8 = System.getenv("HSTEST_JAVAC8");
		if (javac8 == null) javac8 = "F:/files/java/jdks/jdk-1.8/bin/javac.exe";
		System.out.println("\n=== javac 8 嵌套回退靶子（" + javac8 + "）===");
		if (!new File(javac8).exists()) {
			System.out.println("  SKIP  javac 8 不存在");
			return;
		}
		File dir = new File(base, "alt8");
		File out = new File(dir, "out");
		out.mkdirs();
		File src = new File(dir, "Alt.java");
		Files.writeString(src.toPath(), altDeepLambdaSource());
		compile(javac8, out, src);

		File pkgDir = new File(out, "deep");
		List<String> names = new ArrayList<>();
		for (File f : pkgDir.listFiles()) names.add(f.getName());
		Collections.sort(names);
		System.out.println("  javac 8 产物: " + names);

		java.util.function.Function<String, byte[]> res =
			n -> readIfExists(new File(out, n.replace('.', '/') + ".class"));

		int fallbackTriggered = 0;
		for (String n : names) {
			if (!n.endsWith(".class") || !n.contains("$")) continue;
			String slash = "deep/" + n.substring(0, n.length() - 6);
			if (!AnonClassAligner.isAnonymousClassName("deep/Alt", slash)) continue;
			org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
			new org.objectweb.asm.ClassReader(res.apply(slash))
				.accept(cn, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
			String normalized = AnonClassAligner.normalizeEnclosingMethod(cn.outerMethod);
			boolean triggersFallback = normalized == null || "null".equals(normalized);
			if (triggersFallback) fallbackTriggered++;
			int lastDollar = slash.lastIndexOf('$');
			String parent = lastDollar > 0 ? slash.substring(0, lastDollar) : "deep/Alt";
			System.out.println("  " + slash
				+ "\n      EnclosingMethod=" + cn.outerClass + "." + cn.outerMethod
				+ "  normalize->" + normalized + (triggersFallback ? "  ← 触发回退扫描" : "")
				+ "\n      宿主扫描 -> " + fmt(AnonClassAligner.resolveHostMethodForAnon("deep/Alt",
					AnonClassAligner.parseHostNode("deep/Alt", res), slash))
				+ "\n      父类扫描(" + parent + ") -> " + fmt(AnonClassAligner.resolveHostMethodForAnon(parent,
					AnonClassAligner.parseHostNode(parent, res), slash)));
		}
		check(fallbackTriggered > 0,
			"javac 8 确实存在归一化为 \"null\" 的嵌套匿名类（回退扫描的靶子存在），命中 " + fallbackTriggered + " 个");
	}

	/**
	 * 交替嵌套夹具源码（Lambda → 匿名类 → Lambda → 匿名类）。
	 */
	static String altSource() {
		return "package deep;\n" +
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
			"}\n";
	}

	/**
	 * 触发 javac 8 `lambda$null$N` 的夹具：**匿名类 → lambda → lambda → 匿名类**。
	 *
	 * <p>关键区别：内层匿名类 A1 的创建点在 `Alt$1` 的**第二层** lambda 里 —— javac 8 会把这种
	 * 嵌套 lambda 体命名为 `lambda$null$N`，而 `normalizeEnclosingMethod` 会把它归约成字面量
	 * {@code "null"}，于是 `parseInfos` 里的回退扫描被触发；若内层 lambda 只有一层（如 {@link #altSource()}），
	 * 名字是 `lambda$work$0`、归约后是 `"work"`，回退根本不触发。</p>
	 */
	static String altDeepLambdaSource() {
		return "package deep;\n" +
			"class Alt {\n" +
			"    static class Worker { void work() {} }\n" +
			"    static class Task {}\n" +
			"    void run() {\n" +
			"        new Worker() {                                 // A0（EnclosingMethod = Alt.run）\n" +
			"            void work() {\n" +
			"                Runnable a = () -> {                   // L0（在 A0 内，名字 lambda$work$0）\n" +
			"                    Runnable b = () -> {               // L1（在 L0 内 → javac 8 记 lambda$null$N）\n" +
			"                        new Task() {};                 // A1\n" +
			"                    };\n" +
			"                    b.run();\n" +
			"                };\n" +
			"                a.run();\n" +
			"            }\n" +
			"        }.work();\n" +
			"    }\n" +
			"}\n";
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

		// 1) 每一层要么被正确继承，要么被**合法拒绝**（旧类成孤儿、且没有别的类占它的槽）。
		//    拒绝是设计内的保守结果（拓扑无法区分时不仲裁），不算失败；
		//    只有"预期之外的新类继承了旧槽"才是错配。
		boolean allLevelsOk = true;
		for (Map.Entry<String, String> e : expect.entrySet()) {
			String newName = e.getKey();
			String oldName = e.getValue();
			String got     = res.renameMap.get(newName);
			if (oldName.equals(got)) continue;                       // 正确继承
			boolean refused = res.orphanOldClasses.contains(oldName);
			if (refused) {
				for (Map.Entry<String, String> m : res.renameMap.entrySet()) {
					if (oldName.equals(m.getValue())) refused = false;   // 旧槽被别人占了
				}
			}
			if (!refused) {
				allLevelsOk = false;
				System.out.println("    !! 错配: " + newName + " 期望 " + oldName + " 实际 " + got);
			}
		}
		if (!allLevelsOk) failed++;

		// 2) 安全性与映射正确性由回归套件断言（AnonClassReproTest Scenario 26 有精确期望）。
		//    探针在这里只报告，不再自行判定"是否错配" —— 避免诊断工具引入第二套判据。
		// 3) 不允许有"多候选 minDiff 盲猜"（最终手段不该被触发）
		boolean noGuess = res.stats.ambiguousMatches == 0;
		if (!noGuess) failed++;

		// 4) 用来判定"哪一层最强"：Tier1=内容哈希命中，Tier3=结构签名
		int tier1 = res.stats.tier1Matches;
		int tier3 = res.stats.tier3Matches;
		int tier4 = res.stats.tier4Matches;
		System.out.println("  判定    : 逐层一致=" + allLevelsOk
			+ " 无孤儿=" + res.orphanOldClasses.isEmpty()
			+ " 无盲猜=" + noGuess + " | T1=" + tier1 + " T3=" + tier3 + " T4=" + tier4
			+ " 拓扑=" + res.stats.topologyMatches);
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
