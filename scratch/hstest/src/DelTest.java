import nipx.AnnotationTransformer;
import nipx.ClassDiffUtil;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 链式场景：V1 两个 lambda -> V2 删第一个（b 从 $1 变 $0）-> V3 改 b 的方法体。
 * 逐轮喂给 align 的是"上一轮对齐后的字节码"，与真实管道一致。
 */
public class DelTest {

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
			List<String> calls = new ArrayList<>();
			boolean ghost = false;
			for (AbstractInsnNode n : mn.instructions) {
				if (n instanceof MethodInsnNode m) {
					if (m.owner.equals("nipx/LambdaAligner") && m.name.equals("onOrphanInvoked")) ghost = true;
					else if (m.owner.equals(cn.name)) calls.add(m.name);
				}
			}
			out.add("    " + mn.name + mn.desc + " 调用=" + calls + (ghost ? "  ← 幽灵" : ""));
		}
		Collections.sort(out);
		return String.join("\n", out);
	}

	static String callers(byte[] bytes) {
		ClassNode cn = parse(bytes);
		List<String> out = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.equals("build")) continue;
			List<String> impls = new ArrayList<>();
			for (AbstractInsnNode n : mn.instructions) {
				if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
				    && i.bsmArgs[1] instanceof Handle h) impls.add(h.getName());
			}
			out.add("    build() -> " + impls);
		}
		return String.join("\n", out);
	}

	static void show(String tag, byte[] b) {
		System.out.println("=== " + tag + " ===");
		System.out.println(shape(b));
		System.out.println(callers(b));
	}

	public static void main(String[] args) throws Exception {
		byte[] r1 = Files.readAllBytes(Paths.get(args[0]));
		byte[] r2 = Files.readAllBytes(Paths.get(args[1]));
		byte[] r3 = Files.readAllBytes(Paths.get(args[2]));
		String slash = new ClassReader(r1).getClassName();
		ClassLoader cl = DelTest.class.getClassLoader();
		for (byte[] b : new byte[][]{r1, r2, r3}) AnnotationTransformer.HierarchyTree.register(b);

		// 与真实管道一致：进入 align 的双方都先归一化
		byte[] v1 = AnnotationTransformer.forceStaticLambdas(r1, slash, cl);
		byte[] v2 = AnnotationTransformer.forceStaticLambdas(r2, slash, cl);
		byte[] v3 = AnnotationTransformer.forceStaticLambdas(r3, slash, cl);

		show("v1 (JVM 生效基线)", v1);
		show("v2 raw (本次编译)", v2);

		byte[] a2 = LambdaAligner.align(v1, v2);
		show("v2 aligned", a2);
		ClassDiffUtil.ClassDiff d2 = ClassDiffUtil.diff(v1, a2);
		System.out.println("  DIFF added=" + d2.addedMethods + " removed=" + d2.removedMethods);
		System.out.println();

		show("v3 raw (本次编译)", v3);
		byte[] a3 = LambdaAligner.align(a2, v3);
		show("v3 aligned", a3);
		ClassDiffUtil.ClassDiff d3 = ClassDiffUtil.diff(a2, a3);
		System.out.println("  DIFF added=" + d3.addedMethods + " removed=" + d3.removedMethods);
	}
}
