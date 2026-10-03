import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 组合顺序实验：组序反转（TEST_REVERSE_GROUP_ORDER）× 组内方法表乱序。
 *
 * 目的：把"那份代码阅读"变成"有失败用例"或"未观测到"。
 * 此前 OrderTest 只在**默认组序**下测过组内乱序（全过）。这里补上组序反转这一维，
 * 并直接比较两种组序下的**最终方法表**是否一致 —— 若不一致，说明组遍历顺序泄漏到了输出。
 *
 * 判据：
 *   A. 同一组内排列下，组序正/反的最终方法表必须一致（顺序无关性）
 *   B. 记录每个排列下"旧叶子名是否被 noop 抢走"（顺序敏感性的度量）
 */
public class CombinedOrderTest {

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

	static boolean isGhost(MethodNode mn) {
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode m && m.owner.equals("nipx/LambdaAligner")
			    && m.name.equals("onOrphanInvoked")) return true;
		}
		return false;
	}

	static String table(byte[] bytes) {
		ClassNode cn = parse(bytes);
		List<String> l = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			l.add(mn.name + mn.desc + (isGhost(mn) ? "#G" : "#L"));
		}
		Collections.sort(l);
		return String.join("\n", l);
	}

	static String sem(ClassNode cn, MethodNode mn, int d) {
		if (d > 8) return "...";
		if (isGhost(mn)) return "GHOST";
		Map<String, MethodNode> by = new HashMap<>();
		for (MethodNode m : cn.methods) by.put(m.name, m);
		List<String> p = new ArrayList<>();
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode m && m.owner.equals(cn.name) && !m.name.startsWith("lambda$")) p.add(m.name);
			else if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
			         && i.bsmArgs[1] instanceof Handle h && cn.name.equals(h.getOwner())) {
				MethodNode c = by.get(h.getName());
				if (c != null) p.add(sem(cn, c, d + 1));
			}
		}
		Collections.sort(p);
		return p.toString();
	}

	static byte[] shuffleMethods(byte[] bytes, long seed) {
		ClassNode cn = parse(bytes);
		if (seed == -1L) Collections.reverse(cn.methods);
		else if (seed > 0) Collections.shuffle(cn.methods, new Random(seed));
		ClassWriter cw = new ClassWriter(0);
		cn.accept(cw);
		return cw.toByteArray();
	}

	static byte[] force(String p, ClassLoader cl) throws Exception {
		byte[] r = Files.readAllBytes(Paths.get(p));
		String s = new ClassReader(r).getClassName();
		AnnotationTransformer.HierarchyTree.register(r);
		return AnnotationTransformer.forceStaticLambdas(r, s, cl);
	}

	static byte[] align(byte[] oldB, byte[] newB, boolean reverse) {
		try {
			LambdaAligner.TEST_REVERSE_GROUP_ORDER = reverse;
			return LambdaAligner.align(oldB, newB);
		} finally {
			LambdaAligner.TEST_REVERSE_GROUP_ORDER = false;
		}
	}

	// 【未完成的验证】`TEST_REVERSE_GROUP_ORDER` 是否**真的**改变了遍历顺序，
	// 本入口尚未证明。没有这一步，下面"泄漏数=0"的负结果可能只是因为夹具里只有一个组
	// （那样反转组序不产生任何区别，负结果毫无意义）。见 README 的"未完成"一节。

	public static void main(String[] args) throws Exception {
		ClassLoader cl = CombinedOrderTest.class.getClassLoader();
		String label = args[2];
		byte[] v1 = force(args[0], cl), v2 = force(args[1], cl);

		System.out.println("== " + label + " ==");
		long[] seeds = {0L, -1L, 1L, 2L, 3L, 7L, 11L, 42L};
		int divergent = 0, badTotal = 0, badFwd = 0, badRev = 0;
		List<String> divTags = new ArrayList<>();

		for (long seed : seeds) {
			String tag = seed == 0 ? "原序" : (seed == -1 ? "倒序" : ("seed=" + seed));
			byte[] a = align(shuffleMethods(v1, seed), shuffleMethods(v2, seed), false);
			byte[] b = align(shuffleMethods(v1, seed), shuffleMethods(v2, seed), true);
			if (!table(a).equals(table(b))) {
				divergent++;
				divTags.add(tag);
				System.out.println("   [组序泄漏] " + tag);
				System.out.println("      正序: " + table(a).replace("\n", " | "));
				System.out.println("      反序: " + table(b).replace("\n", " | "));
			}
			// 顺序敏感性度量：旧叶子名是否被 noop 抢走
			boolean badA = isBad(a), badB = isBad(b);
			if (badA) badFwd++;
			if (badB) badRev++;
			if (badA || badB) badTotal++;
		}

		System.out.println();
		check(divergent == 0, "组序正/反的最终方法表一致（泄漏数=" + divergent + " " + divTags + "）");
		System.out.println("   顺序敏感排列：组序正 " + badFwd + "/8，组序反 " + badRev + "/8，任一 " + badTotal + "/8");
		System.out.println();
	}

	/** 旧叶子名（$2 或 lambda$build$2）是否被 noop 抢走。 */
	static boolean isBad(byte[] aligned) {
		ClassNode cn = parse(aligned);
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			String n = mn.name;
			if (n.endsWith("$2") && sem(cn, mn, 0).contains("noop")) return true;
		}
		return false;
	}
}
