import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import nipx.MethodFingerprinter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 验证发现 B：占位符让外层 lambda 的指纹丢掉"调的是哪个 handler"。
 * 两个外层 lambda 体都只是 `handler()`，handler 名被 #SYNTHETIC_METHOD# 屏蔽，
 * 于是外层 hash 相同 —— 换位置就会对调。
 */
public class Nest2Test {

	static ClassNode parse(byte[] b) {
		ClassNode cn = new ClassNode();
		new ClassReader(b).accept(cn, 0);
		return cn;
	}

	/** 每个 lambda 的方法体 + 它调用的本类方法 + 那个被调方法的体。 */
	static String trace(byte[] bytes) {
		ClassNode cn = parse(bytes);
		Map<String, MethodNode> byName = new HashMap<>();
		for (MethodNode mn : cn.methods) byName.put(mn.name, mn);

		List<String> out = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			List<String> calls = new ArrayList<>();
			for (AbstractInsnNode n : mn.instructions) {
				if (n instanceof MethodInsnNode m && m.owner.equals(cn.name)) {
					String callee = m.name;
					MethodNode cm = byName.get(callee);
					String inner = "?";
					if (cm != null) {
						List<String> l = new ArrayList<>();
						for (AbstractInsnNode x : cm.instructions) {
							if (x instanceof MethodInsnNode mm && mm.owner.equals(cn.name)) l.add(mm.name);
						}
						inner = l.toString();
					}
					calls.add(callee + "->" + inner);
				}
			}
			out.add("    " + mn.name + " 调用 " + calls);
		}
		Collections.sort(out);
		return String.join("\n", out);
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
		ClassLoader cl = Nest2Test.class.getClassLoader();
		AnnotationTransformer.HierarchyTree.register(r1);
		AnnotationTransformer.HierarchyTree.register(r2);

		byte[] v1 = AnnotationTransformer.forceStaticLambdas(r1, slash, cl);
		byte[] v2 = AnnotationTransformer.forceStaticLambdas(r2, slash, cl);

		System.out.println("=== v1 ===");
		System.out.println(trace(v1));
		System.out.println("  hashes: " + hashes(v1));
		System.out.println("=== v2 (只把两个承载方法对调) ===");
		System.out.println(trace(v2));
		System.out.println("  hashes: " + hashes(v2));

		Map<String, Long> h1 = hashes(v1);
		Collection<Long> vals = h1.values();
		Set<Long> uniq = new HashSet<>(vals);
		System.out.println("\n>>> 两个外层 lambda 指纹相同？ " + (uniq.size() < vals.size())
			+ "  (" + uniq.size() + " 种指纹 / " + vals.size() + " 个 lambda)");

		byte[] aligned = LambdaAligner.align(v1, v2);
		System.out.println("\n=== aligned ===");
		System.out.println(trace(aligned));
		System.out.println("\n期望：handleA 的 lambda 仍是 lambda$buildA$0，handleB 的仍是 lambda$buildB$0");
	}
}
