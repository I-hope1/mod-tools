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

		System.out.println();
		System.out.println(failed == 0 ? "ALL ASSERTIONS PASSED" : (failed + " ASSERTION(S) FAILED"));
		if (failed != 0) System.exit(1);
	}
}
