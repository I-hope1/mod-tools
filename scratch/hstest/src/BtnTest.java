import nipx.AnnotationTransformer;
import nipx.ClassDiffUtil;
import nipx.LambdaAligner;
import nipx.MethodFingerprinter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 用户的反例：t.button("新建", () -> create()) 这种形态，
 * 每个 lambda 体只有"取捕获参数 + 调一个本类方法"，调用目标还不一样。
 * 这里量三件事：
 *   1) 三个 lambda 的指纹是否彼此不同（不同则跨组匹配可用）
 *   2) 插入后对齐结果是否仍然一致
 *   3) 如果指纹相同，说明跨组匹配救不了，只能靠位置
 */
public class BtnTest {

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
			p.reset();
			p.setContext(cn.name);
			mn.accept(p);
			out.add(String.format("    %-16s %-40s hash=%016x  调用链=%s",
				mn.name, mn.desc, p.getHash(), calledMethods(mn)));
		}
		Collections.sort(out);
		return String.join("\n", out);
	}

	static String calledMethods(MethodNode mn) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode n : mn.instructions) {
			if (n instanceof MethodInsnNode m) out.add(m.name);
			else if (n instanceof InvokeDynamicInsnNode i) out.add("indy:" + i.name);
		}
		return out.toString();
	}

	public static void main(String[] args) throws Exception {
		byte[] v1 = Files.readAllBytes(Paths.get(args[0]));
		byte[] v2 = Files.readAllBytes(Paths.get(args[1]));
		String slash = new ClassReader(v1).getClassName();
		ClassLoader cl = BtnTest.class.getClassLoader();
		AnnotationTransformer.HierarchyTree.register(v1);
		AnnotationTransformer.HierarchyTree.register(v2);

		byte[] v1f = AnnotationTransformer.forceStaticLambdas(v1, slash, cl);
		byte[] v2f = AnnotationTransformer.forceStaticLambdas(v2, slash, cl);

		System.out.println("=== v1 forced：三个按钮的 lambda 指纹 ===");
		System.out.println(fp(v1f));
		System.out.println("\n=== v2 forced：插入新按钮后 ===");
		System.out.println(fp(v2f));

		Set<Long> hashes = new HashSet<>();
		ClassNode c1 = parse(v1f);
		MethodFingerprinter p = new MethodFingerprinter();
		for (MethodNode mn : c1.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			p.reset(); p.setContext(c1.name); mn.accept(p);
			hashes.add(p.getHash());
		}
		System.out.println("\n>>> v1 三个 lambda 的指纹互不相同？ " + (hashes.size() == 3));

		byte[] aligned = LambdaAligner.align(v1f, v2f);
		System.out.println("\n=== aligned ===");
		System.out.println(fp(aligned));
		ClassDiffUtil.ClassDiff d = ClassDiffUtil.diff(v1f, aligned);
		System.out.println("\naligned DIFF added   = " + d.addedMethods);
		System.out.println("aligned DIFF removed = " + d.removedMethods);
	}
}
