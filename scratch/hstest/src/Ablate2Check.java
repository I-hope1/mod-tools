import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 变形实验：无关 lambda 插入的三种布局（前插 / 后插 / 带子的插入）。
 *
 * 硬判据（不看名字表）：
 *   1. 旧基线里每个存活名字的**子树形状**必须等于它在最终类里的形状；
 *   2. 旧叶子名承载的语义必须是 doB2 链（独立从最终字节码重算）；
 *   3. 最终类自洽（无重复 名字+描述符）。
 *
 * 参数：baseline(v1->v2 对齐后应达到的类) 由 base 路径单独给出；
 *       mid = 插入无关 lambda 但叶子体未改的那一版；new = 叶子体已改的那一版。
 */
public class Ablate2Check {

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

	static String sem(ClassNode cn, MethodNode mn, int d) {
		if (d > 8) return "...";
		if (isGhost(mn)) return "GHOST";
		Map<String, MethodNode> by = new HashMap<>();
		for (MethodNode m : cn.methods) by.put(m.name, m);
		List<String> p = new ArrayList<>();
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode m && m.owner.equals(cn.name) && !m.name.startsWith("lambda$")) {
				p.add(m.name);
			} else if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
			           && i.bsmArgs[1] instanceof Handle h && cn.name.equals(h.getOwner())) {
				MethodNode c = by.get(h.getName());
				if (c != null) p.add(sem(cn, c, d + 1));
			}
		}
		Collections.sort(p);
		return p.toString();
	}

	static Map<String, String> semAll(byte[] b) {
		ClassNode cn = parse(b);
		Map<String, String> m = new TreeMap<>();
		for (MethodNode mn : cn.methods) if (mn.name.startsWith("lambda$")) m.put(mn.name, sem(cn, mn, 0));
		return m;
	}

	static String shape(ClassNode cn, Map<String, MethodNode> by, String name, int d) {
		if (d > 12) return "?";
		MethodNode mn = by.get(name);
		if (mn == null || isGhost(mn)) return "()";
		List<String> parts = new ArrayList<>();
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
			    && i.bsmArgs[1] instanceof Handle h && cn.name.equals(h.getOwner())) {
				parts.add(shape(cn, by, h.getName(), d + 1));
			}
		}
		Collections.sort(parts);
		return "(" + String.join("", parts) + ")";
	}

	static Map<String, String> shapeAll(byte[] b) {
		ClassNode cn = parse(b);
		Map<String, MethodNode> by = new HashMap<>();
		for (MethodNode mn : cn.methods) by.put(mn.name, mn);
		Map<String, String> out = new TreeMap<>();
		for (MethodNode mn : cn.methods) if (mn.name.startsWith("lambda$")) out.put(mn.name, shape(cn, by, mn.name, 0));
		return out;
	}

	static boolean selfConsistent(byte[] b) {
		ClassNode cn = parse(b);
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

	static byte[] force(String p, ClassLoader cl) throws Exception {
		byte[] r = Files.readAllBytes(Paths.get(p));
		String s = new ClassReader(r).getClassName();
		AnnotationTransformer.HierarchyTree.register(r);
		return AnnotationTransformer.forceStaticLambdas(r, s, cl);
	}

	public static void main(String[] args) throws Exception {
		ClassLoader cl = Ablate2Check.class.getClassLoader();
		String v1 = args[0], mid = args[1], v3 = args[2];
		String label = args[3];

		System.out.println("== " + label + " ==");
		byte[] base = LambdaAligner.align(force(v1, cl), force(mid, cl));   // 基线（含插入，叶子未改）
		byte[] fin  = LambdaAligner.align(base, force(v3, cl));             // 再改叶子体

		Map<String, String> bs = shapeAll(base), fs = shapeAll(fin);
		Map<String, String> bsem = semAll(base), fsem = semAll(fin);

		System.out.println("  基线: " + bs);
		System.out.println("  最终: " + fs);
		System.out.println("  基线语义: " + bsem);
		System.out.println("  最终语义: " + fsem);

		check(selfConsistent(fin), "最终类自洽");

		// 判据 1：存活名字的形状不变
		List<String> bad = new ArrayList<>();
		for (var e : bs.entrySet()) {
			String now = fs.get(e.getKey());
			if (now == null) continue;
			if (!now.equals(e.getValue())) bad.add(e.getKey() + " " + e.getValue() + "->" + now);
		}
		check(bad.isEmpty(), "存活名字的形状跨轮不变（错绑=" + bad + "）");

		// 判据 2：基线里承载 doB 链的名字，最终承载 doB2 链
		List<String> lost = new ArrayList<>();
		for (var e : bsem.entrySet()) {
			if (!e.getValue().contains("doB")) continue;
			String now = fsem.get(e.getKey());
			if (now == null || !now.contains("doB2")) lost.add(e.getKey() + " 旧=" + e.getValue() + " 现=" + now);
		}
		check(lost.isEmpty(), "旧 doB 链的名字在最终类承载 doB2 链（未丢失=" + lost + "）");
		System.out.println();
	}
}
