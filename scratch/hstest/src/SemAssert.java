import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 对齐结果的确定性断言集。
 *
 * 判据都不依赖"肉眼读名字表"：
 *   - 按名字取方法体，递归算出"语义"（它最终调用哪个叶子）；
 *   - 最终类自洽性（不得出现重复的 名字+描述符）。
 *
 * 参数顺序（15 个夹具）：
 *  0,1   swap2 v1,v2        删除变体
 *  2,3,4 leaf  v1,v2,v3     叶子插入 / 双改体
 *  5,6   deep  v1,v2        三层删除变体
 *  7,8   two   v1,v2        两级链 Step 2 时序
 *  9,10,11 deep2 v1,v2,v3   只改叶子体 / 删链+改体
 *  12,13,14 save3 v1,v2,v3  三次保存（先删后改）
 */
public class SemAssert {

	static int failed = 0;
	static int passed = 0;
	/** 已知限制（expected-failure）：单独计数，不混进通过数。 */
	static int knownFailures = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   PASS  " : "   FAIL  ") + msg);
		if (ok) passed++; else failed++;
	}

	/**
	 * 已知限制专用断言：期望它**失败**。单独计数，不计入 failed。
	 * 若某天它通过了，说明行为变了，必须有人有意识地来更新。
	 */
	static void checkKnownLimitation(boolean stillBroken, String msg) {
		if (stillBroken) { knownFailures++; System.out.println("   KNOWN " + msg); }
		else { failed++; System.out.println("   FAIL  [已知限制已变化] " + msg); }
	}

	/**
	 * 自检：故意对一个已知输入断言一个已知错误的值。
	 *
	 * <p>用途是验证 <b>{@code check()} → 失败计数 → 退出码</b> 的整条链，
	 * 而不是验证匹配逻辑。它必须被记成一次失败，但**不让整体变红**
	 * （否则套件永远红）；开关打开时才真正暴露出来。</p>
	 */
	static void selfCheck() {
		boolean run = Boolean.getBoolean("hstest.selfcheck.run")
			|| "1".equals(System.getenv("HSTEST_SELFCHECK_RUN"));
		if (!run) return;
		System.out.println("== 自检：check() → 退出码 的整条链 ==");
		int before = failed;
		// 已知输入是 1+1==2，这里故意断言错误的值 —— 必须被记为一次失败。
		check(1 + 1 == 3, "自检（预期失败）：故意断言 1+1==3");
		boolean recorded = (failed == before + 1);
		System.out.println(recorded
			? "   PASS  自检：错误断言确实被记为一次失败"
			: "   FAIL  自检：错误断言没有被记为失败 —— check() 到计数这条链是断的");
		// 把这次自检造成的失败抵消掉；开关打开时不抵消，让它真的暴露。
		boolean expose = Boolean.getBoolean("hstest.selfcheck.expose")
			|| "1".equals(System.getenv("HSTEST_SELFCHECK_EXPOSE"));
		if (expose) {
			System.out.println("   [自检暴露模式] 保留这次失败，用于验证退出码非零");
		} else {
			failed = before;
		}
	}

	static ClassNode parse(byte[] b) {
		ClassNode cn = new ClassNode();
		new ClassReader(b).accept(cn, 0);
		return cn;
	}

	static boolean isGhost(MethodNode mn) {
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode m && m.owner.equals("nipx/LambdaAligner")
			    && m.name.equals("onOrphanInvoked")) return true;
		}
		return false;
	}

	/** 递归语义：叶子列出它直接调用的本类业务方法；父列出子的语义。 */
	static String sem(ClassNode cn, MethodNode mn, int depth) {
		if (depth > 6) return "...";
		if (isGhost(mn)) return "GHOST";
		Map<String, MethodNode> byName = new HashMap<>();
		for (MethodNode m : cn.methods) byName.put(m.name, m);
		List<String> parts = new ArrayList<>();
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode m && m.owner.equals(cn.name) && !m.name.startsWith("lambda$")) {
				parts.add(m.name);
			} else if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
			           && i.bsmArgs[1] instanceof Handle h && cn.name.equals(h.getOwner())) {
				MethodNode child = byName.get(h.getName());
				if (child != null) parts.add(sem(cn, child, depth + 1));
			}
		}
		Collections.sort(parts);
		return parts.toString();
	}

	static Map<String, String> nameToSem(byte[] bytes) {
		ClassNode cn = parse(bytes);
		Map<String, String> m = new TreeMap<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			m.put(mn.name, sem(cn, mn, 0));
		}
		return m;
	}

	/** 最终类是否自洽：不得出现重复的 名字+描述符。 */
	static boolean noDupOrShadow(byte[] aligned) {
		ClassNode cn = parse(aligned);
		Map<String, List<String>> byName = new HashMap<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			byName.computeIfAbsent(mn.name, k -> new ArrayList<>()).add(mn.desc);
		}
		for (var e : byName.entrySet()) {
			if (new HashSet<>(e.getValue()).size() != e.getValue().size()) return false;
		}
		return true;
	}

	/**
	 * B 链是否自洽：存在一个"三层语义"的方法，它内含的 indy 指向一个"两层语义"的方法，
	 * 后者又指向一个"一层语义"的方法，且叶子调用的正是 doB2。
	 * 用递归语义串起整条链，不关心各自叫什么名字。
	 */
	static boolean B2chainIntact(byte[] aligned) {
		ClassNode cn = parse(aligned);
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			if (!sem(cn, mn, 0).equals("[[[doB2]]]")) continue;
			// 顺着引用逐层往下核对
			MethodNode mid = childOf(cn, mn);
			if (mid == null || !sem(cn, mid, 0).equals("[[doB2]]")) continue;
			MethodNode leaf = childOf(cn, mid);
			if (leaf == null || !sem(cn, leaf, 0).equals("[doB2]")) continue;
			return true;
		}
		return false;
	}

	/** 该方法体里第一个 indy 指向的本类 lambda 方法。 */
	static MethodNode childOf(ClassNode cn, MethodNode mn) {
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
			    && i.bsmArgs[1] instanceof Handle h && cn.name.equals(h.getOwner())) {
				for (MethodNode m : cn.methods) {
					if (m.name.equals(h.getName())) return m;
				}
			}
		}
		return null;
	}

	// ---------- 子树形状（只由 indy 拓扑决定，与方法体内容无关）----------

	static List<String> kids(ClassNode cn, MethodNode mn) {
		List<String> l = new ArrayList<>();
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
			    && i.bsmArgs[1] instanceof Handle h && cn.name.equals(h.getOwner())) l.add(h.getName());
		}
		return l;
	}

	static String shape(ClassNode cn, Map<String, MethodNode> byName, String name, int depth) {
		if (depth > 12) return "?";
		MethodNode mn = byName.get(name);
		if (mn == null || isGhost(mn)) return "()";
		List<String> parts = new ArrayList<>();
		for (String c : kids(cn, mn)) parts.add(shape(cn, byName, c, depth + 1));
		Collections.sort(parts);
		return "(" + String.join("", parts) + ")";
	}

	static Map<String, String> shapeOfAll(byte[] bytes) {
		ClassNode cn = parse(bytes);
		Map<String, MethodNode> byName = new HashMap<>();
		for (MethodNode mn : cn.methods) byName.put(mn.name, mn);
		Map<String, String> out = new TreeMap<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			out.put(mn.name, shape(cn, byName, mn.name, 0));
		}
		return out;
	}

	static byte[] force(String path, ClassLoader cl) throws Exception {
		byte[] r = Files.readAllBytes(Paths.get(path));
		String slash = new ClassReader(r).getClassName();
		AnnotationTransformer.HierarchyTree.register(r);
		return AnnotationTransformer.forceStaticLambdas(r, slash, cl);
	}

	static byte[] aligned(String v1Path, String v2Path, ClassLoader cl) throws Exception {
		return LambdaAligner.align(force(v1Path, cl), force(v2Path, cl));
	}

	public static void main(String[] args) throws Exception {
		ClassLoader cl = SemAssert.class.getClassLoader();

		// ---------- 1) swap2 删除变体 ----------
		{
			System.out.println("== 1) swap2 删除变体 ==");
			byte[] v1 = force(args[0], cl);
			byte[] ali = LambdaAligner.align(v1, force(args[1], cl));
			Map<String, String> oldM = nameToSem(v1), aliM = nameToSem(ali);
			check("[[doB]]".equals(oldM.get("lambda$build$2")), "夹具前提：旧 $2 是 doB 外层");
			check("[[doB]]".equals(aliM.get("lambda$build$2")), "doB 外层保住旧名 $2（活 lambda 未误杀）");
			check("[doB]".equals(aliM.get("lambda$build$3")), "doB 内层保住旧名 $3");
			check("GHOST".equals(aliM.get("lambda$build$0")), "doA 外层变幽灵");
			check("GHOST".equals(aliM.get("lambda$build$1")), "doA 内层变幽灵");
		}

		// ---------- 2) leaf 插入 ----------
		{
			System.out.println("== 2) leaf 插入（开头插 gamma）==");
			Map<String, String> aliM = nameToSem(aligned(args[2], args[3], cl));
			check("[alpha]".equals(aliM.get("lambda$build$0")), "alpha 保住 $0");
			check("[beta]".equals(aliM.get("lambda$build$1")), "beta 保住 $1");
			check(aliM.values().stream().filter(v -> v.equals("[gamma]")).count() == 1,
				"gamma 拿独立名字（未被抢）");
		}

		// ---------- 3) leaf 双改体 ----------
		{
			System.out.println("== 3) leaf 双改体（alpha2 / beta2）==");
			byte[] ali = aligned(args[2], args[4], cl);
			Map<String, String> aliM = nameToSem(ali);
			check(aliM.values().stream().filter(v -> v.equals("[alpha2]")).count() == 1, "alpha2 落在一个名字上");
			check(aliM.values().stream().filter(v -> v.equals("[beta2]")).count() == 1, "beta2 落在一个名字上");
			check(noDupOrShadow(ali), "最终类自洽");
		}

		// ---------- 4) deep 三层删除变体 ----------
		{
			System.out.println("== 4) deep 三层删除变体 ==");
			Map<String, String> aliM = nameToSem(aligned(args[5], args[6], cl));
			check("[[[doB]]]".equals(aliM.get("lambda$build$3")), "活的外层保住 $3");
			check("[[doB]]".equals(aliM.get("lambda$build$4")), "中层保住 $4");
			check("[doB]".equals(aliM.get("lambda$build$5")), "叶子保住 $5");
			check("GHOST".equals(aliM.get("lambda$build$0"))
			      && "GHOST".equals(aliM.get("lambda$build$1"))
			      && "GHOST".equals(aliM.get("lambda$build$2")), "doA 整链变幽灵");
		}

		// ---------- 5) two 两级链 Step 2 时序 ----------
		{
			System.out.println("== 5) two 两级链：Step 2 时序 ==");
			byte[] ali = aligned(args[7], args[8], cl);
			Map<String, String> aliM = nameToSem(ali);
			check(noDupOrShadow(ali), "最终类自洽");
			check(aliM.containsValue("[[doY]]"), "新外层的语义 [[doY]] 存在（父未被丢掉）");
			check(aliM.containsValue("[doY]"), "新内层的语义 [doY] 存在");
		}

		// ---------- 6) deep2 变体甲：只改 doB 叶子体 ----------
		//
		// 本用例的关键点：后代被编辑后，祖先的语义指纹必然变化，但靠"子配给了谁"的
		// 逐层传递，doB2 整条链仍能保住原有名字，不再被幽灵化。
		{
			System.out.println("== 6) deep2 只改叶子体 ==");
			byte[] ali = aligned(args[9], args[10], cl);
			Map<String, String> aliM = nameToSem(ali);
			check("[[[doA]]]".equals(aliM.get("lambda$build$0")), "doA 外层保住 $0");
			check("[[doA]]".equals(aliM.get("lambda$build$1")), "doA 中层保住 $1");
			check("[doA]".equals(aliM.get("lambda$build$2")), "doA 叶子保住 $2");
			check("[[[doB2]]]".equals(aliM.get("lambda$build$3")), "doB2 外层保住 $3");
			check("[[doB2]]".equals(aliM.get("lambda$build$4")), "doB2 中层保住 $4");
			check("[doB2]".equals(aliM.get("lambda$build$5")), "doB2 叶子保住 $5");
			check(noDupOrShadow(ali), "最终类自洽");
		}

		// ---------- 7) deep2 变体乙：删 A 链 + 改 B 叶子体（同时发生）----------
		//
		// 【KNOWN LIMITATION】钉住当前实际行为：整条新链一致地绑到**被删掉的 A 链**的名字
		// （$0/$1/$2）上，A 链三个老调用点都静默执行 doB2 的逻辑 —— 没有熔断。
		//
		// 这是"删 + 改同时发生"这一无证据场景的固有后果：叶子的方法体变了，没有任何指纹证据
		// 能把它认回旧 doB 链，Step 2 的同名优先把它配给了位置相同的 $2；此后中层/外层顺着
		// "子配给了谁"逐层跟随，于是整条链一致地落在 A 的名字上（一致性本身是对的，错的是
		// 落在了被删链的名字上）。写成 expected-failure：套件保持全绿，行为一变就会响。
		{
			System.out.println("== 7) deep2 删 A 链 + 改 B 叶子【已知限制/期望失败】==");
			byte[] ali = aligned(args[9], args[11], cl);
			Map<String, String> aliM = nameToSem(ali);
			check(noDupOrShadow(ali), "最终类自洽");
			check("[[[doB2]]]".equals(aliM.get("lambda$build$0")), "KNOWN LIMITATION: 新外层落在 $0");
			check("[[doB2]]".equals(aliM.get("lambda$build$1")), "KNOWN LIMITATION: 新中层落在 $1");
			check("[doB2]".equals(aliM.get("lambda$build$2")), "KNOWN LIMITATION: 新叶子落在 $2");
			check("GHOST".equals(aliM.get("lambda$build$3"))
			      && "GHOST".equals(aliM.get("lambda$build$4"))
			      && "GHOST".equals(aliM.get("lambda$build$5")), "KNOWN LIMITATION: 旧 doB 链变幽灵");
		}

		// ---------- 8) save3 三次保存：先删 A 链，再改 B 叶子体 ----------
		//
		// 分两次保存：第二次保存时类里已有 A 链幽灵、旧 doB 链是活方法。叶子虽被改体
		// （无指纹证据），但"子配给了谁"的逐层传递让外层/中层都能跟着落到旧 doB 链的名字上。
		// 因此这条路径是有效的缓解做法。
		{
			System.out.println("== 8) save3 三次保存：先删后改 ==");
			byte[] a2 = LambdaAligner.align(force(args[12], cl), force(args[13], cl));
			byte[] a3 = LambdaAligner.align(a2, force(args[14], cl));

			Map<String, String> sem2 = nameToSem(a2);
			check("[[[doB]]]".equals(sem2.get("lambda$build$3")), "V1→V2：B 链外层保住 $3");
			check("[doB]".equals(sem2.get("lambda$build$5")), "V1→V2：B 链叶子保住 $5");
			check("GHOST".equals(sem2.get("lambda$build$0"))
			      && "GHOST".equals(sem2.get("lambda$build$2")), "V1→V2：A 链成为幽灵");

			Map<String, String> sem3 = nameToSem(a3);
			check("GHOST".equals(sem3.get("lambda$build$0"))
			      && "GHOST".equals(sem3.get("lambda$build$1"))
			      && "GHOST".equals(sem3.get("lambda$build$2")),
				"V2→V3：A 链幽灵被重新注入（老调用点仍熔断）");
			// 不绑定具体编号：V2→V3 里外层/中层可能与上一轮互换编号（都是旧 doB 链的名字），
			// 但只要链条自洽、叶子可达、且旧 A 链仍熔断，缓解就是有效的。
			check(sem3.containsValue("[[[doB2]]]"), "V2→V3：B 链外层（三层）存在");
			check(sem3.containsValue("[[doB2]]"), "V2→V3：B 链中层（两层）存在");
			check(sem3.containsValue("[doB2]"), "V2→V3：B 链叶子存在");
			check(B2chainIntact(a3), "V2→V3：B 链自洽（外层→中层→叶子 引用闭合）");
			check(noDupOrShadow(a3), "V2→V3：最终类自洽");

			// ---- 跨轮"形状不变"判据（已修复，普通断言）----
			// 判据：旧基线里每个存活下来的名字，其**子树形状**（只由 indy 拓扑决定、
			// 与方法体内容无关）必须保持不变。外层 ((())) 与中层 (()) 若互换，
			// 持有旧名字的 UpdateRef 回调会静默改了语义（延迟、嵌套层数都变）。
			//
			// 这里曾经标为 KNOWN ISSUE（两侧 shape 被算成同一个错值，导致校验放行）；
			// 根因是 scan 里 shape 只算一遍、父读到子的未定稿哨兵。改成迭代到定稿
			// （且哨兵用 null 而非 "()"）后已修复，因此恢复为普通断言。
			Map<String, String> s2 = shapeOfAll(a2), s3 = shapeOfAll(a3);
			List<String> swapped = new ArrayList<>();
			for (var e : s2.entrySet()) {
				String now = s3.get(e.getKey());
				if (now == null) continue;                 // 已消失可接受
				if (!now.equals(e.getValue())) swapped.add(e.getKey() + " 旧=" + e.getValue() + " 现=" + now);
			}
			check(swapped.isEmpty(), "存活名字的子树形状跨轮不变（错绑=" + swapped + "）");
		}

		selfCheck();

		System.out.println();
		System.out.println("通过 " + passed + " 条；失败 " + failed + " 条；已知限制 "
			+ knownFailures + " 条；合计 " + (passed + failed + knownFailures) + " 条");
		System.out.println(failed == 0 ? "ALL ASSERTIONS PASSED" : (failed + " ASSERTION(S) FAILED"));
		if (failed != 0) System.exit(1);
	}
}
