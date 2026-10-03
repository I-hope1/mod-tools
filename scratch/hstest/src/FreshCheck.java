import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 一行检查：seed=11 下互换的 $4/$5 到底是"基线存活名"还是"阶段二 fresh name"？
 *
 * 判据：
 *   1. $4/$5 是否出现在旧基线的名字集合里（出现 => 老 CallSite 可能指向它们 => 真缺陷）；
 *   2. doB2 整棵树的引用链是否闭合（外层 -> 中层 -> 叶子）。
 */
public class FreshCheck {

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

	/** 递归沿着 indy 引用往下核对链条：outer -> mid -> leaf，语义依次降一级。 */
	static boolean chainClosed(ClassNode cn, Map<String, MethodNode> by, MethodNode mn, String wantOuter) {
		if (!sem(cn, mn, 0).equals(wantOuter)) return false;
		MethodNode child = childOf(cn, mn);
		if (child == null) return false;
		String midWant = wantOuter.substring(1, wantOuter.length() - 1);
		if (!sem(cn, child, 0).equals(midWant)) return false;
		MethodNode leaf = childOf(cn, child);
		if (leaf == null) return false;
		return sem(cn, leaf, 0).equals("[doB2]");
	}

	static MethodNode childOf(ClassNode cn, MethodNode mn) {
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
			    && i.bsmArgs[1] instanceof Handle h && cn.name.equals(h.getOwner())) {
				for (MethodNode m : cn.methods) if (m.name.equals(h.getName())) return m;
			}
		}
		return null;
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

	public static void main(String[] args) throws Exception {
		ClassLoader cl = FreshCheck.class.getClassLoader();
		byte[] v1 = force(args[0], cl), v2 = force(args[1], cl);

		Set<String> oldNames = new TreeSet<>();
		for (MethodNode mn : parse(v1).methods) if (mn.name.startsWith("lambda$")) oldNames.add(mn.name);
		System.out.println("旧基线名字集合: " + oldNames);

		for (long seed : new long[]{-1L, 11L}) {
			byte[] got = LambdaAligner.align(shuffleMethods(v1, seed), shuffleMethods(v2, seed));
			ClassNode cn = parse(got);
			Map<String, MethodNode> by = new HashMap<>();
			for (MethodNode mn : cn.methods) by.put(mn.name, mn);

			String tag = seed == -1 ? "倒序" : "seed=11";
			System.out.println();
			System.out.println("== " + tag + " ==");
			for (String n : new String[]{"lambda$build$3", "lambda$build$4", "lambda$build$5"}) {
				MethodNode mn = by.get(n);
				System.out.println("   " + n + "  在旧基线的名字集合里? " + oldNames.contains(n)
					+ "   语义=" + (mn == null ? "<不存在>" : sem(cn, mn, 0)));
			}
			// 找到承载 [[doB2]] 的那个方法，核对链条闭合
			MethodNode outer = null;
			for (MethodNode mn : cn.methods) {
				if (mn.name.startsWith("lambda$") && sem(cn, mn, 0).equals("[[[doB2]]]")) { outer = mn; break; }
			}
			boolean closed = outer != null && chainClosed(cn, by, outer, "[[[doB2]]]");
			System.out.println("   doB2 链条闭合（外层->中层->叶子）: " + closed);
			System.out.println("   >>> $4/$5 是 fresh name（不在旧集合）: "
				+ (!oldNames.contains("lambda$build$4") && !oldNames.contains("lambda$build$5")));
			System.out.println("   >>> 结论: " + (closed && !oldNames.contains("lambda$build$4")
				&& !oldNames.contains("lambda$build$5")
				? "命名不确定性，无语义影响" : "真缺陷，需单独查"));
		}
	}
}
