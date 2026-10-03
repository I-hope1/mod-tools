import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/** 打印最终方法表（名字/描述符/语义），用于核对"叶子到底落在哪个编号"。 */
public class TableDump {

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

	static byte[] force(String p, ClassLoader cl) throws Exception {
		byte[] r = Files.readAllBytes(Paths.get(p));
		String s = new ClassReader(r).getClassName();
		AnnotationTransformer.HierarchyTree.register(r);
		return AnnotationTransformer.forceStaticLambdas(r, s, cl);
	}

	public static void main(String[] a) throws Exception {
		ClassLoader cl = TableDump.class.getClassLoader();
		byte[] base = LambdaAligner.align(force(a[0], cl), force(a[1], cl));
		byte[] fin  = LambdaAligner.align(base, force(a[2], cl));
		System.out.println("== 基线方法表 ==");
		dump(base);
		System.out.println("== 最终方法表 ==");
		dump(fin);
	}

	static void dump(byte[] b) {
		ClassNode cn = parse(b);
		List<String> out = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			out.add("   " + mn.name + "  " + sem(cn, mn, 0) + (isGhost(mn) ? "  [GHOST]" : ""));
		}
		Collections.sort(out);
		System.out.println(String.join("\n", out));
	}
}
