import nipx.AnnotationTransformer;
import nipx.ClassDiffUtil;
import nipx.LambdaAligner;
import nipx.MethodFingerprinter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 直接检验 MethodFingerprinter 的 #SYNTHETIC_METHOD# 占位机制。
 *
 * 构造：外层 lambda 体内两个内层 lambda，只把它们的声明顺序对调。
 * 外层指令结构完全不变（同样 2 个 indy、同样描述符），变化的只有内层名字。
 *   -> 若占位生效，外层 hash 必须保持不变
 *   -> 同时内层 A/B 也应靠指纹各就各位，而不是按位置互换
 */
public class SwapTest {

	static ClassNode parse(byte[] b) {
		ClassNode cn = new ClassNode();
		new ClassReader(b).accept(cn, 0);
		return cn;
	}

	static Map<String, Long> hashes(byte[] bytes) {
		ClassNode cn = parse(bytes);
		MethodFingerprinter p = new MethodFingerprinter();
		Map<String, Long> m = new LinkedHashMap<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			p.reset(); p.setContext(cn.name); mn.accept(p);
			m.put(mn.name + mn.desc, p.getHash());
		}
		return m;
	}

	static String bodies(byte[] bytes) {
		ClassNode cn = parse(bytes);
		List<String> out = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			List<String> consts = new ArrayList<>();
			List<String> impls = new ArrayList<>();
			for (AbstractInsnNode n : mn.instructions) {
				if (n instanceof LdcInsnNode l && l.cst instanceof String s) consts.add(s);
				if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
				    && i.bsmArgs[1] instanceof Handle h) impls.add(h.getName());
			}
			out.add("    " + mn.name + mn.desc + " 常量=" + consts + " 内含indy->" + impls);
		}
		Collections.sort(out);
		return String.join("\n", out);
	}

	public static void main(String[] args) throws Exception {
		byte[] v1 = Files.readAllBytes(Paths.get(args[0]));
		byte[] v2 = Files.readAllBytes(Paths.get(args[1]));
		String slash = new ClassReader(v1).getClassName();
		ClassLoader cl = SwapTest.class.getClassLoader();
		AnnotationTransformer.HierarchyTree.register(v1);
		AnnotationTransformer.HierarchyTree.register(v2);

		byte[] v1f = AnnotationTransformer.forceStaticLambdas(v1, slash, cl);
		byte[] v2f = AnnotationTransformer.forceStaticLambdas(v2, slash, cl);

		System.out.println("=== v1 forced ===");
		System.out.println(bodies(v1f));
		System.out.println("  hashes: " + hashes(v1f));
		System.out.println("\n=== v2 forced（只对调了内层声明顺序）===");
		System.out.println(bodies(v2f));
		System.out.println("  hashes: " + hashes(v2f));

		Long outer1 = hashes(v1f).get("lambda$build$0" + "(Ltest7/SwapCase;)V");
		Long outer2 = hashes(v2f).get("lambda$build$0" + "(Ltest7/SwapCase;)V");
		System.out.println("\n>>> 外层 hash 是否保持不变？ " + Objects.equals(outer1, outer2)
			+ "  (" + Long.toHexString(outer1 == null ? 0 : outer1)
			+ " vs " + Long.toHexString(outer2 == null ? 0 : outer2) + ")");

		byte[] aligned = LambdaAligner.align(v1f, v2f);
		System.out.println("\n=== aligned ===");
		System.out.println(bodies(aligned));
		ClassDiffUtil.ClassDiff d = ClassDiffUtil.diff(v1f, aligned);
		System.out.println("\nadded   = " + d.addedMethods);
		System.out.println("removed = " + d.removedMethods);
		System.out.println("\n期望：AAA 的 lambda 仍是 $1，BBB 的 lambda 仍是 $2（不被对调）");
	}
}
