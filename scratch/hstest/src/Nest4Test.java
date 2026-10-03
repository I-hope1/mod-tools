import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import nipx.MethodFingerprinter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/** 验证：外层 lambda 体内再定义 lambda 时，外层指纹是否丢掉"内层是哪一个"。 */
public class Nest4Test {

	static ClassNode parse(byte[] b) {
		ClassNode cn = new ClassNode();
		new ClassReader(b).accept(cn, 0);
		return cn;
	}

	/** 递归展示：每个 lambda 的体，以及它体内 indy 指向哪个内层 lambda。 */
	static String tree(byte[] bytes) {
		ClassNode cn = parse(bytes);
		Map<String, MethodNode> byName = new HashMap<>();
		for (MethodNode mn : cn.methods) byName.put(mn.name, mn);

		List<String> out = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			List<String> inner = new ArrayList<>();
			for (AbstractInsnNode n : mn.instructions) {
				if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
				    && i.bsmArgs[1] instanceof Handle h) {
					inner.add(h.getName() + leafOf(byName.get(h.getName()), cn.name));
				}
			}
			out.add("    " + mn.name + "  内含内层=" + inner);
		}
		Collections.sort(out);
		return String.join("\n", out);
	}

	static String leafOf(MethodNode mn, String owner) {
		if (mn == null) return "?";
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode m && m.owner.equals(owner)) return "->" + m.name;
		}
		return "";
	}

	static Map<String, Long> hashes(byte[] bytes) {
		ClassNode cn = parse(bytes);
		MethodFingerprinter p = new MethodFingerprinter();
		Map<String, Long> m = new LinkedHashMap<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			p.reset(); p.setContext(cn.name); mn.accept(p);
			m.put(mn.name, p.getHash());
		}
		return m;
	}

	public static void main(String[] args) throws Exception {
		byte[] r1 = Files.readAllBytes(Paths.get(args[0]));
		byte[] r2 = Files.readAllBytes(Paths.get(args[1]));
		String slash = new ClassReader(r1).getClassName();
		ClassLoader cl = Nest4Test.class.getClassLoader();
		AnnotationTransformer.HierarchyTree.register(r1);
		AnnotationTransformer.HierarchyTree.register(r2);

		byte[] v1 = AnnotationTransformer.forceStaticLambdas(r1, slash, cl);
		byte[] v2 = AnnotationTransformer.forceStaticLambdas(r2, slash, cl);

		System.out.println("=== v1 ===");
		System.out.println(tree(v1));
		System.out.println("  hashes: " + hashes(v1));
		System.out.println("=== v2 (只对调承载方法顺序) ===");
		System.out.println(tree(v2));
		System.out.println("  hashes: " + hashes(v2));

		Map<String, Long> h1 = hashes(v1);
		Set<Long> uniq = new HashSet<>(h1.values());
		System.out.println("\n>>> 两个外层 lambda 指纹相同？ " + (uniq.size() < h1.size())
			+ "  (" + uniq.size() + " 种指纹 / " + h1.size() + " 个 lambda)");

		byte[] aligned = LambdaAligner.align(v1, v2);
		System.out.println("\n=== aligned ===");
		System.out.println(tree(aligned));
		System.out.println("\n期望：buildA 的外层仍含 a()，buildB 的外层仍含 b()");
	}
}
