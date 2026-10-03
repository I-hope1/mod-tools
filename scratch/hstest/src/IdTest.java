import nipx.AnnotationTransformer;
import nipx.ClassDiffUtil;
import nipx.LambdaAligner;
import nipx.MethodFingerprinter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/** 完全同形同体的 lambda：指纹无法区分时，对齐到底还剩什么信息。 */
public class IdTest {

	static ClassNode parse(byte[] b) {
		ClassNode cn = new ClassNode();
		new ClassReader(b).accept(cn, 0);
		return cn;
	}

	static String fp(byte[] bytes) {
		ClassNode cn = parse(bytes);
		MethodFingerprinter p = new MethodFingerprinter();
		List<String> out = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			p.reset(); p.setContext(cn.name); mn.accept(p);
			out.add(String.format("    %-16s %-26s hash=%016x  bodySize=%d",
				mn.name, mn.desc, p.getHash(), mn.instructions.size()));
		}
		Collections.sort(out);
		return String.join("\n", out);
	}

	public static void main(String[] args) throws Exception {
		byte[] v1 = Files.readAllBytes(Paths.get(args[0]));
		byte[] v2 = Files.readAllBytes(Paths.get(args[1]));
		String slash = new ClassReader(v1).getClassName();
		ClassLoader cl = IdTest.class.getClassLoader();
		AnnotationTransformer.HierarchyTree.register(v1);
		AnnotationTransformer.HierarchyTree.register(v2);

		byte[] v1f = AnnotationTransformer.forceStaticLambdas(v1, slash, cl);
		byte[] v2f = AnnotationTransformer.forceStaticLambdas(v2, slash, cl);

		System.out.println("=== v1 forced（3 个 lambda）===");
		System.out.println(fp(v1f));
		System.out.println("\n=== v2 forced（4 个 lambda，最前面插了一个）===");
		System.out.println(fp(v2f));

		Set<Long> hs = new HashSet<>();
		ClassNode c1 = parse(v1f);
		MethodFingerprinter p = new MethodFingerprinter();
		for (MethodNode mn : c1.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			p.reset(); p.setContext(c1.name); mn.accept(p); hs.add(p.getHash());
		}
		System.out.println("\n>>> v1 三个 lambda 指纹互不相同？ " + (hs.size() == 3)
			+ "（只有 " + hs.size() + " 种指纹）");

		byte[] aligned = LambdaAligner.align(v1f, v2f);
		System.out.println("\n=== aligned ===");
		System.out.println(fp(aligned));
		ClassDiffUtil.ClassDiff d = ClassDiffUtil.diff(v1f, aligned);
		System.out.println("\naligned DIFF added   = " + d.addedMethods);
		System.out.println("aligned DIFF removed = " + d.removedMethods);
		System.out.println("\n注：三个方法体字节码完全相同，所以“谁是谁”在类文件里本就没有答案；");
		System.out.println("    但正因完全相同，对调也不改变行为。真正会出问题的前提是“体不同”。");
	}
}
