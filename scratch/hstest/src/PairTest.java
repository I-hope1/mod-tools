import nipx.AnnotationTransformer;
import nipx.ClassDiffUtil;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 同时验证 (a) 顺序回退告警 与 (c) 跨组指纹匹配。
 *
 * 场景：同一方法内两个同形 lambda，只改第一个的方法体。
 *   - 第一个：方法体变了 -> 指纹对不上 -> 只能顺序回退 -> 应触发 (a) 告警
 *   - 第二个：方法体没变 -> 跨组指纹匹配应把它认领回旧名（而不是被顺序回退对调）
 */
public class PairTest {

	static ClassNode parse(byte[] b) {
		ClassNode cn = new ClassNode();
		new ClassReader(b).accept(cn, 0);
		return cn;
	}

	static String semantics(byte[] bytes) {
		ClassNode cn = parse(bytes);
		List<String> out = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			List<String> ops = new ArrayList<>();
			for (AbstractInsnNode n : mn.instructions) {
				if (n instanceof FieldInsnNode f) ops.add(f.name);
				else if (n instanceof LdcInsnNode l && l.cst instanceof String s) ops.add("\"" + s + "\"");
			}
			out.add("    " + mn.name + " -> " + ops);
		}
		Collections.sort(out);
		return String.join("\n", out);
	}

	public static void main(String[] args) throws Exception {
		byte[] v1 = Files.readAllBytes(Paths.get(args[0]));
		byte[] v2 = Files.readAllBytes(Paths.get(args[1]));
		String slash = new ClassReader(v1).getClassName();
		ClassLoader cl = PairTest.class.getClassLoader();
		AnnotationTransformer.HierarchyTree.register(v1);
		AnnotationTransformer.HierarchyTree.register(v2);

		byte[] v1f = AnnotationTransformer.forceStaticLambdas(v1, slash, cl);
		byte[] v2f = AnnotationTransformer.forceStaticLambdas(v2, slash, cl);
		byte[] aligned = LambdaAligner.align(v1f, v2f);

		System.out.println("old (上一版 JVM 里生效的):");
		System.out.println(semantics(v1f));
		System.out.println("\nnew (本次编译产物):");
		System.out.println(semantics(v2f));
		System.out.println("\naligned:");
		System.out.println(semantics(aligned));

		ClassDiffUtil.ClassDiff d = ClassDiffUtil.diff(v1f, aligned);
		System.out.println("\nDIFF added   = " + d.addedMethods);
		System.out.println("DIFF removed = " + d.removedMethods);
		System.out.println("\n期望：lambda$build$1 仍指向 ctx.b（未被对调）；lambda$build$0 指向 ctx.sb");
	}
}
