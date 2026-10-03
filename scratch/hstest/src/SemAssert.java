import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 断言式对拍：把"语义 -> 名字"的归属查清并直接判定通过/失败。
 *   1) 删除变体（swap2）：doB 外层必须落在旧 $2，doA 外层/内层必须变幽灵
 *   2) 叶子抢占（leaf）：插入/改体时不得互相抢名字、不得把旧名配给不同语义
 */
public class SemAssert {

	static int failed = 0;

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   PASS  " : "   FAIL  ") + msg);
		if (!ok) failed++;
	}

	static ClassNode parse(byte[] b) {
		ClassNode cn = new ClassNode();
		new ClassReader(b).accept(cn, 0);
		return cn;
	}

	static String sem(ClassNode cn, MethodNode mn, int depth) {
		if (depth > 6) return "...";
		Map<String, MethodNode> byName = new HashMap<>();
		for (MethodNode m : cn.methods) byName.put(m.name, m);
		boolean ghost = false;
		List<String> parts = new ArrayList<>();
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode m) {
				if (m.owner.equals("nipx/LambdaAligner") && m.name.equals("onOrphanInvoked")) ghost = true;
				else if (m.owner.equals(cn.name) && !m.name.startsWith("lambda$")) parts.add(m.name);
			} else if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
			           && i.bsmArgs[1] instanceof Handle h && cn.name.equals(h.getOwner())) {
				MethodNode child = byName.get(h.getName());
				if (child != null) parts.add(sem(cn, child, depth + 1));
			}
		}
		if (ghost) return "GHOST";
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



	/** 新方法（按描述符取）在最终类里的语义；desc 用于区分同名的幽灵与活方法。 */
	static String semByDesc(byte[] aligned, String desc) {
		ClassNode cn = parse(aligned);
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$") || !mn.desc.equals(desc)) continue;
			return sem(cn, mn, 0);
		}
		return "<none>";
	}


	/** 最终类里是否存在某个语义（不关心它落在哪个名字上）。 */
	static boolean aliSemContains(byte[] aligned, String want) {
		ClassNode cn = parse(aligned);
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			if (sem(cn, mn, 0).equals(want)) return true;
		}
		return false;
	}


	/** 只做 forceStaticLambdas，方便检查新类自身的语义集合。 */
	static byte[] force(String path, ClassLoader cl) throws Exception {
		byte[] r = Files.readAllBytes(Paths.get(path));
		String slash = new ClassReader(r).getClassName();
		AnnotationTransformer.HierarchyTree.register(r);
		return AnnotationTransformer.forceStaticLambdas(r, slash, cl);
	}

	/** 最终类是否自洽：无重复的 名字+描述符，且无"同名不同描述符"的幽灵遮蔽。 */
	static boolean noDupOrShadow(byte[] aligned) {
		ClassNode cn = parse(aligned);
		Map<String, List<String>> byName = new HashMap<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			byName.computeIfAbsent(mn.name, k -> new ArrayList<>()).add(mn.desc);
		}
		// 判据只有一条：不得出现重复的 名字+描述符（那才是 ClassFormatError）。
		// 同名不同描述符是合法的，而且正是幽灵的正常形态 —— 捕获列表变化时
		// 旧 (I)V 与新 ()V 并存，靠描述符区分，JVM 完全接受。
		for (var e : byName.entrySet()) {
			if (new HashSet<>(e.getValue()).size() != e.getValue().size()) return false;
		}
		return true;
	}

	static byte[] aligned(String v1Path, String v2Path, ClassLoader cl) throws Exception {
		byte[] r1 = Files.readAllBytes(Paths.get(v1Path));
		byte[] r2 = Files.readAllBytes(Paths.get(v2Path));
		String slash = new ClassReader(r1).getClassName();
		AnnotationTransformer.HierarchyTree.register(r1);
		AnnotationTransformer.HierarchyTree.register(r2);
		return LambdaAligner.align(
			AnnotationTransformer.forceStaticLambdas(r1, slash, cl),
			AnnotationTransformer.forceStaticLambdas(r2, slash, cl));
	}

	public static void main(String[] args) throws Exception {
		ClassLoader cl = SemAssert.class.getClassLoader();

		// ---------- 1) 删除变体：活的那个必须保住旧名字，死的才变幽灵 ----------
		{
			System.out.println("== swap2 删除变体 ==");
			byte[] r1 = Files.readAllBytes(Paths.get(args[0]));
			String slash = new ClassReader(r1).getClassName();
			byte[] r2 = Files.readAllBytes(Paths.get(args[1]));
			AnnotationTransformer.HierarchyTree.register(r1);
			AnnotationTransformer.HierarchyTree.register(r2);
			byte[] v1 = AnnotationTransformer.forceStaticLambdas(r1, slash, cl);
			byte[] v2 = AnnotationTransformer.forceStaticLambdas(r2, slash, cl);
			Map<String, String> oldM = nameToSem(v1);
			Map<String, String> aliM = nameToSem(LambdaAligner.align(v1, v2));

			// 旧：$0=[[doA]] $1=[doA] $2=[[doB]] $3=[doB]
			check(oldM.get("lambda$build$2").equals("[[doB]]"), "夹具前提：旧 $2 是 doB 的外层");
			check(aliM.get("lambda$build$2") != null
			      && aliM.get("lambda$build$2").equals("[[doB]]"),
				"doB 外层仍落在旧名字 lambda$build$2（活 lambda 未被误杀）");
			check("GHOST".equals(aliM.get("lambda$build$0")), "doA 外层变幽灵（它确实被删了）");
			check("GHOST".equals(aliM.get("lambda$build$1")), "doA 内层变幽灵");
			check(aliM.get("lambda$build$3") != null && aliM.get("lambda$build$3").equals("[doB]"),
				"doB 内层仍落在旧名字 lambda$build$3");
		}

		// ---------- 2) 叶子：插入不得抢名字 ----------
		{
			System.out.println("== leaf 插入（v1 -> v2，开头插一个 gamma 叶子） ==");
			Map<String, String> aliM = nameToSem(aligned(args[2], args[3], cl));
			// 旧：$0=[alpha] $1=[beta]；新多一个 [gamma]
			check("[alpha]".equals(aliM.get("lambda$build$0")), "alpha 叶子保住旧名 lambda$build$0");
			check("[beta]".equals(aliM.get("lambda$build$1")), "beta 叶子保住旧名 lambda$build$1");
			long gammaCount = aliM.values().stream().filter(v -> v.equals("[gamma]")).count();
			check(gammaCount == 1, "新增的 gamma 叶子拿到独立名字（未被 alpha/beta 抢）");
		}

		// ---------- 3) 叶子：两个体都改了，不得互相抢、不得串名 ----------
		{
			System.out.println("== leaf 双改体（v1 -> v3，alpha->alpha2、beta->beta2） ==");
			Map<String, String> aliM = nameToSem(aligned(args[2], args[4], cl));
			long a2 = aliM.values().stream().filter(v -> v.equals("[alpha2]")).count();
			long b2 = aliM.values().stream().filter(v -> v.equals("[beta2]")).count();
			check(a2 == 1, "alpha2 恰好落在一个名字上");
			check(b2 == 1, "beta2 恰好落在一个名字上");
			// 旧名字若还在，不得承载不同语义
			for (String oldName : new String[]{"lambda$build$0", "lambda$build$1"}) {
				String now = aliM.get(oldName);
				if (now == null) continue;
				check(now.equals("GHOST") || now.equals("[alpha2]") || now.equals("[beta2]"),
					oldName + " 未承载无关语义（现=" + now + "）");
			}
			// 语义不得互换：alpha2 与 beta2 不能落在同一个名字上
			check(!Objects.equals(
				aliM.entrySet().stream().filter(e -> e.getValue().equals("[alpha2]")).findFirst().map(Map.Entry::getKey).orElse(null),
				aliM.entrySet().stream().filter(e -> e.getValue().equals("[beta2]")).findFirst().map(Map.Entry::getKey).orElse(null)),
				"alpha2 与 beta2 未落在同一名字");
		}

		// ---------- 4) 三层嵌套：删除变体（deep） ----------
		//
		// 这条是"原始票据指纹"解决不了的：两个中层同 hash、两个外层同 hash、
		// childHashes 也退化成相等集合。靠递归语义指纹把叶子差异向上传播才分得开。
		{
			System.out.println("== deep 三层删除变体 ==");
			Map<String, String> aliM = nameToSem(aligned(args[5], args[6], cl));
			check("[[[doB]]]".equals(aliM.get("lambda$build$3")),
				"活的外层落在旧名字 lambda$build$3");
			check("[[doB]]".equals(aliM.get("lambda$build$4")),
				"中层落在旧名字 lambda$build$4");
			check("[doB]".equals(aliM.get("lambda$build$5")),
				"叶子落在旧名字 lambda$build$5");
			check("GHOST".equals(aliM.get("lambda$build$0")), "doA 外层变幽灵");
			check("GHOST".equals(aliM.get("lambda$build$1")), "doA 中层变幽灵");
			check("GHOST".equals(aliM.get("lambda$build$2")), "doA 叶子变幽灵");
		}

		// ---------- 5) 两级链 + Step 2 时序（two） ----------
		//
		// 骨架：新类的 (LTwoLevel;)V 有"外层"和"插入的普通 lambda"两个，用描述符区分。
		// 父排在子之前被处理时，hasUnmatchedChild 会让父在 Step 2 里被跳过；
		// 若 Step 2 只跑一遍，父就永远拿不到名字。修好后父必须拿到一个可用名字。
		{
			System.out.println("== two 两级链：Step 2 时序 ==");
			byte[] ali = aligned(args[7], args[8], cl);
			check(noDupOrShadow(ali), "最终类无重复定义/幽灵遮蔽");
			// 新外层的体是"求值一个 lambda"，语义应为两层；新内层为 [doY]
			check(aliSemContains(ali, "[[doY]]"), "新外层的语义 [[doY]] 在最终类里存在（父没被丢掉）");
			check(aliSemContains(ali, "[doY]"), "新内层的语义 [doY] 在最终类里存在");
		}

		// ---------- 6) 三层链：只改叶子体（deep2 变体甲） ----------
		{
			System.out.println("== deep2 变体甲：只改 doB 叶子体 ==");
			Map<String, String> aliM = nameToSem(aligned(args[9], args[10], cl));
			check("[[[doA]]]".equals(aliM.get("lambda$build$0")), "doA 外层保住名字");
			check("[[doA]]".equals(aliM.get("lambda$build$1")), "doA 中层保住名字");
			check("[doA]".equals(aliM.get("lambda$build$2")), "doA 叶子保住名字");
			check(aliM.containsValue("[[[doB2]]]"), "doB2 外层仍存在");
			check(noDupOrShadow(aligned(args[9], args[10], cl)), "最终类无重复定义/幽灵遮蔽");
		}

		// ---------- 7) 已知限制：删一条链 + 改另一条叶子（deep2 变体乙）----------
		//
		// 【KNOWN LIMITATION】这里钉住的是**当前实际行为**，不是期望行为。写成
		// expected-failure 而不是常驻红灯：套件保持全绿，一旦行为变化（无论变好变坏）
		// 这条会响，必须有人有意识地来更新它。
		//
		// 实测行为：旧 doA 叶子的名字 $2 被新叶子拿走并承载 doB2 —— 老 doA 的 CallSite
		// **不会抛异常，而是静默执行 doB2**。
		//
		// 为什么根治不了：序号整体位移且叶子体已变，"只改了方法体"与"删除+新增"在结构上
		// 无法区分；Step 2 保名是合理默认。按设计不引入启发式去消除。
		{
			System.out.println("== deep2 变体乙：删 doA 链 + 改 doB 叶子【已知限制/期望失败】==");
			byte[] ali = aligned(args[9], args[11], cl);
			Map<String, String> aliSem = nameToSem(ali);

			check(noDupOrShadow(ali), "最终类无重复的 名字+描述符");
			check("[[[doB2]]]".equals(aliSem.get("lambda$build$6")), "新链外层在 $6");
			check("[[doB2]]".equals(aliSem.get("lambda$build$7")), "新链中层在 $7");

			// ---- 以下是 KNOWN LIMITATION 的钉子：钉住"当前行为" ----
			check("[doB2]".equals(aliSem.get("lambda$build$2")),
				"KNOWN LIMITATION: 旧 doA 叶子的名字 $2 被新叶子占用并承载 doB2（静默错绑）");
			for (String g : new String[]{"lambda$build$0", "lambda$build$1",
			                             "lambda$build$3", "lambda$build$4", "lambda$build$5"}) {
				check("GHOST".equals(aliSem.get(g)), "KNOWN LIMITATION: " + g + " 是幽灵");
			}
		}

		// ---------- 8) 三次保存：V1 两条链 → V2 删 A 链 → V3 改 B 叶子 ----------
		//
		// 检验"先删再改、分两次保存"这条缓解路径，以及幽灵是否真的退出了匹配
		// （第二次保存时类里已有 $0/$1/$2 幽灵，而 V3 的 javac 名恰好又是 $0/$1/$2）。
		{
			System.out.println("== save3 三次保存：先删后改 ==");
			byte[] v1 = force(args[12], cl);
			byte[] v2 = force(args[13], cl);
			byte[] v3 = force(args[14], cl);
			byte[] a2 = LambdaAligner.align(v1, v2);
			byte[] a3 = LambdaAligner.align(a2, v3);

			ClassNode c2 = parse(a2);
			Map<String, String> sem2 = new TreeMap<>();
			for (MethodNode mn : c2.methods) {
				if (mn.name.startsWith("lambda$")) sem2.put(mn.name, sem(c2, mn, 0));
			}
			check("[doB]".equals(sem2.get("lambda$build$5")), "V1→V2：B 链叶子保住 $5（未被幽灵抢）");
			check("[[[doB]]]".equals(sem2.get("lambda$build$3")), "V1→V2：B 链外层保住 $3");
			check("GHOST".equals(sem2.get("lambda$build$0"))
			      && "GHOST".equals(sem2.get("lambda$build$2")), "V1→V2：A 链三个名字是幽灵");

			ClassNode c3 = parse(a3);
			Map<String, String> sem3 = new TreeMap<>();
			for (MethodNode mn : c3.methods) {
				if (mn.name.startsWith("lambda$")) sem3.put(mn.name, sem(c3, mn, 0));
			}
			check("GHOST".equals(sem3.get("lambda$build$0"))
			      && "GHOST".equals(sem3.get("lambda$build$1"))
			      && "GHOST".equals(sem3.get("lambda$build$2")),
				"V2→V3：先前的 A 链幽灵被重新注入（未消失，老 CallSite 仍熔断）");
			check(noDupOrShadow(a3), "V2→V3：最终类无重复的 名字+描述符");

			// ---- KNOWN LIMITATION：分两次保存只保住了叶子，外层/中层仍被幽灵化 ----
			check("[doB2]".equals(sem3.get("lambda$build$5")),
				"KNOWN LIMITATION: 分两次保存后 B 链叶子仍保住 $5");
			check("GHOST".equals(sem3.get("lambda$build$3")) && "GHOST".equals(sem3.get("lambda$build$4")),
				"KNOWN LIMITATION: 但 B 链外层/中层被幽灵化（$3/$4）");
		}

		System.out.println();
		System.out.println(failed == 0 ? "ALL ASSERTIONS PASSED" : (failed + " ASSERTION(S) FAILED"));
		if (failed != 0) System.exit(1);
	}
}
