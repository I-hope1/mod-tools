import nipx.AnnotationTransformer;
import nipx.ClassDiffUtil;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/** 验证"Context 包装"提案：捕获列表恒定后，热更对齐到底稳不稳。 */
public class CtxTest {

	static ClassNode parse(byte[] b) {
		ClassNode cn = new ClassNode();
		new ClassReader(b).accept(cn, 0);
		return cn;
	}

	/** 每个 lambda 的“语义轨迹”：它到底访问了哪些字段 / 调用了什么。 */
	static String semantics(byte[] bytes) {
		ClassNode cn = parse(bytes);
		List<String> out = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			List<String> ops = new ArrayList<>();
			for (AbstractInsnNode n : mn.instructions) {
				if (n instanceof FieldInsnNode f) ops.add(f.name);
				else if (n instanceof MethodInsnNode m && !m.owner.startsWith("java/lang/String"))
					ops.add(m.owner.substring(m.owner.lastIndexOf('/') + 1) + "." + m.name);
			}
			out.add("    " + mn.name + " -> " + ops);
		}
		Collections.sort(out);
		return String.join("\n", out);
	}

	static String bodies(byte[] bytes) {
		ClassNode cn = parse(bytes);
		List<String> out = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			if (mn.name.startsWith("lambda$")) out.add(mn.name + mn.desc);
		}
		return out.toString();
	}

	public static void main(String[] args) throws Exception {
		byte[] v1 = Files.readAllBytes(Paths.get(args[0]));
		byte[] v2 = Files.readAllBytes(Paths.get(args[1]));
		String slash = new ClassReader(v1).getClassName();
		ClassLoader cl = CtxTest.class.getClassLoader();
		AnnotationTransformer.HierarchyTree.register(v1);
		AnnotationTransformer.HierarchyTree.register(v2);

		byte[] v1f = AnnotationTransformer.forceStaticLambdas(v1, slash, cl);
		byte[] v2f = AnnotationTransformer.forceStaticLambdas(v2, slash, cl);
		byte[] aligned = LambdaAligner.align(v1f, v2f);

		System.out.println("###### 场景 B：在 build() 最前面插入一个新 lambda ######\n");
		System.out.println("old (JVM 里上一版实际生效的形态):");
		System.out.println(semantics(v1f));
		System.out.println("\nnew (本次编译产物):");
		System.out.println(semantics(v2f));
		System.out.println("\naligned (LambdaAligner 产物):");
		System.out.println(semantics(aligned));
		System.out.println("\naligned 骨架: " + bodies(aligned));
		System.out.println("\naligned DIFF vs old:");
		ClassDiffUtil.ClassDiff d = ClassDiffUtil.diff(v1f, aligned);
		System.out.println("  added   = " + d.addedMethods);
		System.out.println("  removed = " + d.removedMethods);
	}
}
