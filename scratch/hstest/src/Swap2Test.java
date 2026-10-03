import nipx.AnnotationTransformer;
import nipx.ClassDiffUtil;
import nipx.LambdaAligner;
import nipx.MethodFingerprinter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 验证用户预测：V1 两个外层 O0/O1（体仅差 indy 指向的内层），hash 相同；
 * V2 删掉第一个后 N0 与 O0 同名同 hash -> Step 1a 直接配对，
 * 结果 O1（b 的外层）变幽灵、O0 的 CallSite 去跑 b 的逻辑。
 */
public class Swap2Test {

	static ClassNode parse(byte[] b) {
		ClassNode cn = new ClassNode();
		new ClassReader(b).accept(cn, 0);
		return cn;
	}

	/** 逻辑名（复刻 extractLogicalName：剥掉 $ 后的纯数字段）。 */
	static String logical(String name) {
		if (name.startsWith("access$")) return name;
		StringBuilder sb = new StringBuilder();
		int i = 0;
		while (i < name.length()) {
			char c = name.charAt(i);
			sb.append(c);
			if (c == '$') {
				int j = i + 1;
				while (j < name.length() && Character.isDigit(name.charAt(j))) j++;
				if (j > i + 1) i = j - 1;
			}
			i++;
		}
		return sb.toString();
	}

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
					// 内层的叶子：内层体里调用的本类方法
					MethodNode im = byName.get(h.getName());
					inner.add(h.getName() + leafOf(im, cn.name) + ghostOf(mn));
				}
				if (n instanceof MethodInsnNode m && m.owner.equals("nipx/LambdaAligner")
				    && m.name.equals("onOrphanInvoked")) {
					inner.add("【幽灵】");
				}
			}
			out.add("    " + mn.name + "  逻辑名=" + logical(mn.name) + "  内含=" + inner);
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

	static String ghostOf(MethodNode mn) {
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode m && m.owner.equals("nipx/LambdaAligner")
			    && m.name.equals("onOrphanInvoked")) return "(幽灵)";
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

	/** build() 里的 indy 依次指向哪些 lambda（源码顺序 = 调用顺序）。 */
	static String buildOrder(byte[] bytes) {
		ClassNode cn = parse(bytes);
		for (MethodNode mn : cn.methods) {
			if (!mn.name.equals("build")) continue;
			List<String> impls = new ArrayList<>();
			for (AbstractInsnNode n : mn.instructions) {
				if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
				    && i.bsmArgs[1] instanceof Handle h) impls.add(h.getName());
			}
			return impls.toString();
		}
		return "?";
	}

	public static void main(String[] args) throws Exception {
		byte[] r1 = Files.readAllBytes(Paths.get(args[0]));
		byte[] r2 = Files.readAllBytes(Paths.get(args[1]));
		String slash = new ClassReader(r1).getClassName();
		ClassLoader cl = Swap2Test.class.getClassLoader();
		AnnotationTransformer.HierarchyTree.register(r1);
		AnnotationTransformer.HierarchyTree.register(r2);

		byte[] v1 = AnnotationTransformer.forceStaticLambdas(r1, slash, cl);
		byte[] v2 = AnnotationTransformer.forceStaticLambdas(r2, slash, cl);

		System.out.println("=== v1 (JVM 生效基线) ===");
		System.out.println(tree(v1));
		System.out.println("  hashes = " + hashes(v1));
		System.out.println("  build() indy = " + buildOrder(v1));

		System.out.println("\n=== v2 raw (本次编译) ===");
		System.out.println(tree(v2));
		System.out.println("  hashes = " + hashes(v2));
		System.out.println("  build() indy = " + buildOrder(v2));

		Map<String, Long> h1 = hashes(v1);
		Set<Long> outer = new LinkedHashSet<>();
		for (var e : h1.entrySet()) {
			if (e.getKey().endsWith("$0") || e.getKey().endsWith("$1")) outer.add(e.getValue());
		}
		System.out.println("\n>>> v1 外层 O0/O1 指纹相同？ " + (outer.size() < 2)
			+ "  (" + outer.size() + " 种 / 2 个外层)");

		byte[] aligned = LambdaAligner.align(v1, v2);
		System.out.println("\n=== aligned ===");
		System.out.println(tree(aligned));
		System.out.println("  build() indy = " + buildOrder(aligned));

		ClassDiffUtil.ClassDiff d = ClassDiffUtil.diff(v1, aligned);
		System.out.println("\n  DIFF added=" + d.addedMethods + " removed=" + d.removedMethods);

		boolean o1Ghost = tree(aligned).contains("lambda$build$1")
			&& tree(aligned).lines().anyMatch(l -> l.contains("lambda$build$1") && l.contains("幽灵"));
		System.out.println("\n>>> 预测检查：lambda$build$1（b 的外层）变成幽灵了吗？ " + o1Ghost);
	}
}
