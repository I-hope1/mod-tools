import nipx.AnnotationTransformer;
import nipx.ClassDiffUtil;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 验证 review 第 1 条：lambda 换承载方法 + 目标位置原有 lambda 被删。
 * 期望：搬过来的 lambda 认回它自己的旧名（build1$0），
 *       被删的那个（build2$0）成为孤儿空壳，而不是被顶替。
 */
public class MoveTest {

	static ClassNode parse(byte[] b) {
		ClassNode cn = new ClassNode();
		new ClassReader(b).accept(cn, 0);
		return cn;
	}

	static String shape(byte[] bytes) {
		ClassNode cn = parse(bytes);
		List<String> out = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			List<String> ops = new ArrayList<>();
			boolean ghost = false;
			for (AbstractInsnNode n : mn.instructions) {
				if (n instanceof LdcInsnNode l && l.cst instanceof String s) ops.add("\"" + s + "\"");
				if (n instanceof MethodInsnNode m && m.owner.equals("nipx/LambdaAligner")
				    && m.name.equals("onOrphanInvoked")) ghost = true;
			}
			out.add("    " + mn.name + mn.desc + "  " + ops + (ghost ? "   ← 幽灵空壳" : ""));
		}
		Collections.sort(out);
		return String.join("\n", out);
	}

	/** build1/build2 的 indy 分别指向哪个 lambda。 */
	static String callers(byte[] bytes) {
		ClassNode cn = parse(bytes);
		List<String> out = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("build")) continue;
			List<String> impls = new ArrayList<>();
			for (AbstractInsnNode n : mn.instructions) {
				if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
				    && i.bsmArgs[1] instanceof Handle h) impls.add(h.getName());
			}
			out.add("    " + mn.name + " -> " + impls);
		}
		Collections.sort(out);
		return String.join("\n", out);
	}

	public static void main(String[] args) throws Exception {
		byte[] v1 = Files.readAllBytes(Paths.get(args[0]));
		byte[] v2 = Files.readAllBytes(Paths.get(args[1]));
		String slash = new ClassReader(v1).getClassName();
		ClassLoader cl = MoveTest.class.getClassLoader();
		AnnotationTransformer.HierarchyTree.register(v1);
		AnnotationTransformer.HierarchyTree.register(v2);

		byte[] v1f = AnnotationTransformer.forceStaticLambdas(v1, slash, cl);
		byte[] v2f = AnnotationTransformer.forceStaticLambdas(v2, slash, cl);

		System.out.println("=== old (JVM 里上一版) ===");
		System.out.println(shape(v1f));
		System.out.println(callers(v1f));
		System.out.println("\n=== new (本次编译) ===");
		System.out.println(shape(v2f));
		System.out.println(callers(v2f));

		byte[] aligned = LambdaAligner.align(v1f, v2f);
		System.out.println("\n=== aligned ===");
		System.out.println(shape(aligned));
		System.out.println(callers(aligned));

		ClassDiffUtil.ClassDiff d = ClassDiffUtil.diff(v1f, aligned);
		System.out.println("\nadded   = " + d.addedMethods);
		System.out.println("removed = " + d.removedMethods);

		System.out.println("\n判据：aligned 里应同时存在 lambda$build1$0(AAA) 与 lambda$build2$0(幽灵)");
		System.out.println("      若只剩一个 lambda$build2$0(AAA)，说明旧 build2 的 CallSite 被错绑到 A —— 即 review 第 1 条成立");
	}
}
