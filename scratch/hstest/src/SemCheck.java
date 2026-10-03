import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 精确语义检查：对每个 lambda，报告它**最终调用的叶子方法**。
 * 对拍两个版本里"同名方法是否指向同一个叶子"。
 */
public class SemCheck {

	static ClassNode parse(byte[] b) {
		ClassNode cn = new ClassNode();
		new ClassReader(b).accept(cn, 0);
		return cn;
	}

	/** name -> 该方法最终调用到的本类叶子方法集合（递归一层 indy + 一层 method call）。 */
	static Map<String, String> leafMap(byte[] bytes) {
		ClassNode cn = parse(bytes);
		Map<String, MethodNode> byName = new HashMap<>();
		for (MethodNode mn : cn.methods) byName.put(mn.name, mn);

		Map<String, String> out = new TreeMap<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			Set<String> leaves = new TreeSet<>();
			for (AbstractInsnNode n : mn.instructions) {
				if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
				    && i.bsmArgs[1] instanceof Handle h) {
					leaves.addAll(leafOf(byName.get(h.getName()), cn.name));
				} else if (n instanceof MethodInsnNode m && m.owner.equals(cn.name)
				           && !m.name.startsWith("lambda$")) {
					leaves.add(m.name);
				}
			}
			boolean ghost = false;
			for (AbstractInsnNode n : mn.instructions) {
				if (n instanceof MethodInsnNode m && m.owner.equals("nipx/LambdaAligner")
				    && m.name.equals("onOrphanInvoked")) ghost = true;
			}
			out.put(mn.name, ghost ? "【幽灵】" : leaves.toString());
		}
		return out;
	}

	static List<String> leafOf(MethodNode mn, String owner) {
		if (mn == null) return List.of("?");
		List<String> l = new ArrayList<>();
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode m && m.owner.equals(owner)) l.add(m.name);
		}
		return l;
	}

	public static void main(String[] args) throws Exception {
		byte[] r1 = Files.readAllBytes(Paths.get(args[0]));
		byte[] r2 = Files.readAllBytes(Paths.get(args[1]));
		String slash = new ClassReader(r1).getClassName();
		ClassLoader cl = SemCheck.class.getClassLoader();
		AnnotationTransformer.HierarchyTree.register(r1);
		AnnotationTransformer.HierarchyTree.register(r2);

		byte[] v1 = AnnotationTransformer.forceStaticLambdas(r1, slash, cl);
		byte[] v2 = AnnotationTransformer.forceStaticLambdas(r2, slash, cl);
		byte[] aligned = LambdaAligner.align(v1, v2);

		Map<String, String> before = leafMap(v1);
		Map<String, String> after  = leafMap(aligned);

		System.out.println("旧 (JVM 基线):");
		before.forEach((k, v) -> System.out.println("    " + k + " -> " + v));
		System.out.println("aligned:");
		after.forEach((k, v) -> System.out.println("    " + k + " -> " + v));

		System.out.println("\n>>> 同名方法是否指向同一叶子（这是老 CallSite 是否被错绑的判据）：");
		boolean bad = false;
		for (var e : after.entrySet()) {
			String name = e.getKey();
			if (!before.containsKey(name)) continue;              // 新方法
			String b = before.get(name), a = e.getValue();
			if (b.equals("【幽灵】") || a.equals("【幽灵】")) continue; // 幽灵是预期的
			if (!b.equals(a)) {
				System.out.println("    ✗ " + name + " : 旧=" + b + "  现在=" + a + "   ← 语义被换掉！");
				bad = true;
			}
		}
		if (!bad) System.out.println("    ✓ 没有发现语义互换");
	}
}
