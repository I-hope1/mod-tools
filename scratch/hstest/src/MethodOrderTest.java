import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 组内顺序的反向测试。
 *
 * 1) 先打印新类的方法表顺序（= scan 的组内顺序），验证"noop 是否真的先于 doB2 叶子被处理"。
 * 2) 把新类方法表按 正序 / 倒序 / 若干固定种子乱序 重新排列后送进 align，
 *    断言硬判据：旧存活名的 shape 不变，且旧 doB 链的名字承载 doB2 链。
 *
 * 叶子没有子，calleesPairTo 不作用于它；叶子可用的信号只有"同名"与"组内顺序"。
 * 因此若叶子归属由位置决定，改变组内顺序就会出现失败排列 —— 那就是复现用例。
 */
public class MethodOrderTest {

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

	/** 按给定 seed 重排类文件方法表（0=原序，-1=整体倒序，>0=固定种子乱序）。 */
	static byte[] shuffleMethods(byte[] bytes, long seed) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		if (seed == -1L) {
			Collections.reverse(cn.methods);
		} else if (seed > 0) {
			Collections.shuffle(cn.methods, new Random(seed));
		}
		ClassWriter cw = new ClassWriter(0);   // 保留原帧，不重算
		cn.accept(cw);
		return cw.toByteArray();
	}

	/** 打印 scan 看到的方法顺序（只看 lambda 合成方法）。 */
	static void printOrder(byte[] bytes, String tag) {
		ClassNode cn = parse(bytes);
		List<String> l = new ArrayList<>();
		for (MethodNode mn : cn.methods) if (mn.name.startsWith("lambda$")) l.add(mn.name);
		System.out.println("   " + tag + " 类文件方法顺序: " + l);
	}

	static byte[] force(String p, ClassLoader cl) throws Exception {
		byte[] r = Files.readAllBytes(Paths.get(p));
		String s = new ClassReader(r).getClassName();
		AnnotationHierarchy.register(r);
		return AnnotationTransformer.forceStaticLambdas(r, s, cl);
	}

	/** 小包装，避免直接静态引用太长。 */
	static class AnnotationHierarchy {
		static void register(byte[] b) { AnnotationTransformer.HierarchyTree.register(b); }
	}

	public static void main(String[] args) throws Exception {
		ClassLoader cl = MethodOrderTest.class.getClassLoader();
		String v1 = args[0], mid = args[1], fin = args[2];
		String label = args[3];

		System.out.println("== " + label + " ==");
		byte[] v1f = force(v1, cl), midF = force(mid, cl), finF = force(fin, cl);
		printOrder(v1f, "基线(v1)");
		printOrder(finF, "新类(v3)");

		byte[] base = LambdaAligner.align(v1f, midF);
		Map<String, String> bs = shapeAll(base), bsem = semAll(base);

		long[] seeds = {0L, -1L, 1L, 2L, 3L, 7L, 11L, 42L};
		List<String> bad = new ArrayList<>();
		for (long seed : seeds) {
			byte[] finS = shuffleMethods(finF, seed);
			byte[] got  = LambdaAligner.align(shuffleMethods(base, seed), finS);
			Map<String, String> gs = shapeAll(got), gsem = semAll(got);

			List<String> problems = new ArrayList<>();
			for (var e : bs.entrySet()) {
				String now = gs.get(e.getKey());
				if (now == null) continue;
				if (!now.equals(e.getValue())) problems.add("shape " + e.getKey() + " " + e.getValue() + "->" + now);
			}
			for (var e : bsem.entrySet()) {
				if (!e.getValue().contains("doB")) continue;
				String now = gsem.get(e.getKey());
				if (now == null || !now.contains("doB2")) problems.add("sem " + e.getKey() + " 旧=" + e.getValue() + " 现=" + now);
			}
			String tag = seed == 0 ? "原序" : (seed == -1 ? "倒序" : ("乱序 seed=" + seed));
			check(problems.isEmpty(), tag + "：" + (problems.isEmpty() ? "硬判据通过" : problems.toString()));
			if (!problems.isEmpty()) bad.add(tag);
		}
		if (!bad.isEmpty()) {
			System.out.println();
			System.out.println("   >>> 存在失败排列（叶子归属依赖组内顺序）：" + bad);
		}
		System.out.println();
	}
}
