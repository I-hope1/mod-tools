import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 确定性判据：**对旧类里每个存活下来的名字，它在旧类中的"子树形状"必须等于它在
 * 最终类中的形状**。
 *
 * shape(m) = sorted( shape(child) for child in children(m) ) 的串行化
 *   叶子 = "()"      中层 = "(())"      外层 = "((()))"
 *
 * 形状只由 indy 引用拓扑决定，与方法体内容无关 —— 所以"编辑叶子方法体"不会改变它，
 * 而"外层与中层互换"一定会被抓到。
 */
public class ShapeCheck {

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

	/** 该方法体内 indy 指向的子方法名（顺序即出现顺序）。 */
	static List<String> children(ClassNode cn, MethodNode mn) {
		List<String> l = new ArrayList<>();
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
			    && i.bsmArgs[1] instanceof Handle h && cn.name.equals(h.getOwner())) {
				l.add(h.getName());
			}
		}
		return l;
	}

	/** 递归形状串。幽灵（空壳）没有子，形状为 ()。 */
	static String shape(ClassNode cn, Map<String, MethodNode> byName, String name, int depth) {
		if (depth > 12) return "?";
		MethodNode mn = byName.get(name);
		if (mn == null || isGhost(mn)) return "()";
		List<String> parts = new ArrayList<>();
		for (String c : children(cn, mn)) parts.add(shape(cn, byName, c, depth + 1));
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

	public static void main(String[] args) throws Exception {
		byte[] r1 = Files.readAllBytes(Paths.get(args[0]));
		byte[] r2 = Files.readAllBytes(Paths.get(args[1]));
		byte[] r3 = args.length > 2 ? Files.readAllBytes(Paths.get(args[2])) : null;
		String slash = new ClassReader(r1).getClassName();
		ClassLoader cl = ShapeCheck.class.getClassLoader();
		AnnotationTransformer.HierarchyTree.register(r1);
		AnnotationTransformer.HierarchyTree.register(r2);
		if (r3 != null) AnnotationTransformer.HierarchyTree.register(r3);

		byte[] v1 = AnnotationTransformer.forceStaticLambdas(r1, slash, cl);
		byte[] v2 = AnnotationTransformer.forceStaticLambdas(r2, slash, cl);
		byte[] aligned = LambdaAligner.align(v1, v2);
		if (r3 != null) {
			// 两轮：先 V1→V2 对齐作为新基线，再 V2→V3
			System.out.println("（第一轮 V1→V2 对齐完成，作为下一轮基线）");
			aligned = LambdaAligner.align(aligned,
				AnnotationTransformer.forceStaticLambdas(r3, slash, cl));
			// 基线取"第一轮对齐后的类"，因为那才是 JVM 里实际生效的
			byte[] base = LambdaAligner.align(v1, v2);
			Map<String, String> bs = shapeOfAll(base);
			Map<String, String> as = shapeOfAll(aligned);
			System.out.println("两轮基线 shape（V1→V2 后）:");
			bs.forEach((k, v) -> System.out.println("   " + k + " -> " + v));
			System.out.println("最终 shape（再 V2→V3）:");
			as.forEach((k, v) -> System.out.println("   " + k + " -> " + v));
			System.out.println();
			System.out.println("== 存活名字的 shape 是否不变（跨两轮）==");
			int bad2 = 0;
			for (var e : bs.entrySet()) {
				String now = as.get(e.getKey());
				if (now == null) { System.out.println("   - " + e.getKey() + " 已不存在"); continue; }
				if (now.equals(e.getValue())) { System.out.println("   OK " + e.getKey() + " " + now); continue; }
				System.out.println("   FAIL " + e.getKey() + " 旧=" + e.getValue() + " 现=" + now + "  ← 跨层级错绑");
				bad2++;
			}
			System.out.println();
			System.out.println(bad2 == 0 ? "SHAPE OK" : (bad2 + " SHAPE MISMATCH(ES)"));
			if (bad2 != 0) System.exit(1);
			return;
		}

		Map<String, String> oldS = shapeOfAll(v1);
		Map<String, String> aliS = shapeOfAll(aligned);

		System.out.println("旧类 shape:");
		oldS.forEach((k, v) -> System.out.println("   " + k + " -> " + v));
		System.out.println("最终类 shape:");
		aliS.forEach((k, v) -> System.out.println("   " + k + " -> " + v));

		System.out.println();
		System.out.println("== 存活名字的 shape 是否不变 ==");
		int bad = 0;
		for (var e : oldS.entrySet()) {
			String name = e.getKey();
			String now = aliS.get(name);
			if (now == null) { System.out.println("   - " + name + " 已不存在（可接受）"); continue; }
			if (now.equals(e.getValue())) { System.out.println("   OK " + name + " " + now); continue; }
			System.out.println("   FAIL " + name + " 旧=" + e.getValue() + " 现=" + now + "  ← 跨层级错绑");
			bad++;
		}
		System.out.println();
		System.out.println(bad == 0 ? "SHAPE OK" : (bad + " SHAPE MISMATCH(ES)"));
		if (bad != 0) System.exit(1);
	}
}
