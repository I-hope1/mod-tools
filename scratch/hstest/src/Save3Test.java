import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 三次保存链：V1 两条链 → V2 删 A 链 → V3 改 B 叶子体。
 * 逐轮喂 align，与真实管道一致（上一轮对齐后的字节码作为下一轮基线）。
 */
public class Save3Test {

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

	static String sem(ClassNode cn, MethodNode mn, int depth) {
		if (depth > 6) return "...";
		Map<String, MethodNode> byName = new HashMap<>();
		for (MethodNode m : cn.methods) byName.put(m.name, m);
		if (isGhost(mn)) return "GHOST";
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

	static void dump(String tag, byte[] bytes) {
		ClassNode cn = parse(bytes);
		System.out.println("--- " + tag + " ---");
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			System.out.printf("   %-16s %-24s %-6s %s%n",
				mn.name, mn.desc, isGhost(mn) ? "GHOST" : "live", sem(cn, mn, 0));
		}
	}

	public static void main(String[] args) throws Exception {
		byte[] r1 = Files.readAllBytes(Paths.get(args[0]));
		byte[] r2 = Files.readAllBytes(Paths.get(args[1]));
		byte[] r3 = Files.readAllBytes(Paths.get(args[2]));
		String slash = new ClassReader(r1).getClassName();
		ClassLoader cl = Save3Test.class.getClassLoader();
		for (byte[] b : new byte[][]{r1, r2, r3}) AnnotationTransformer.HierarchyTree.register(b);

		byte[] v1 = AnnotationTransformer.forceStaticLambdas(r1, slash, cl);
		byte[] v2 = AnnotationTransformer.forceStaticLambdas(r2, slash, cl);
		byte[] v3 = AnnotationTransformer.forceStaticLambdas(r3, slash, cl);

		dump("V1 (基线)", v1);

		byte[] a2 = LambdaAligner.align(v1, v2);
		dump("V1 -> V2 (删 A 链)", a2);

		byte[] a3 = LambdaAligner.align(a2, v3);
		dump("V2 -> V3 (改 B 叶子体)", a3);

		System.out.println();
		System.out.println("== 断言 ==");
		ClassNode c3 = parse(a3);
		Map<String, String> m = new TreeMap<>();
		for (MethodNode mn : c3.methods) {
			if (mn.name.startsWith("lambda$")) m.put(mn.name + mn.desc, sem(c3, mn, 0));
		}
		m.forEach((k, v) -> System.out.println("   " + k + " -> " + v));
	}
}
