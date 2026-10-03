import nipx.AnnotationTransformer;
import nipx.ClassDiffUtil;
import nipx.LambdaAligner;
import nipx.MethodFingerprinter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/**
 * 嵌套 lambda 的对齐行为。
 * 量四件事：
 *   1) 外层 lambda 的指纹，在内层序号位移后是否仍然稳定（MethodFingerprinter 把本类
 *      合成方法名替换成 #SYNTHETIC_METHOD#，理论上应该稳定）
 *   2) 插入一个新的内层 lambda 后，旧的三个方法名是否各就各位
 *   3) 只改内层方法体时，内外层是否都能对回
 *   4) 逻辑名分组在嵌套形态下长什么样（extractLogicalName 的滑动去数字）
 */
public class NestTest {

	static ClassNode parse(byte[] b) {
		ClassNode cn = new ClassNode();
		new ClassReader(b).accept(cn, 0);
		return cn;
	}

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

	static String table(byte[] bytes) {
		ClassNode cn = parse(bytes);
		MethodFingerprinter p = new MethodFingerprinter();
		List<String> out = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			p.reset(); p.setContext(cn.name); mn.accept(p);
			out.add(String.format("    %-22s %-24s hash=%016x  逻辑名=%s",
				mn.name, mn.desc, p.getHash(), logical(mn.name)));
		}
		Collections.sort(out);
		return String.join("\n", out);
	}

	static String nestedHandles(byte[] bytes) {
		ClassNode cn = parse(bytes);
		List<String> out = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.startsWith("lambda$")) continue;
			List<String> impls = new ArrayList<>();
			for (AbstractInsnNode n : mn.instructions) {
				if (n instanceof InvokeDynamicInsnNode i && i.bsmArgs != null && i.bsmArgs.length > 1
				    && i.bsmArgs[1] instanceof Handle h) {
					impls.add(h.getName());
				}
			}
			if (!impls.isEmpty()) out.add("    " + mn.name + " 内部 indy 指向 -> " + impls);
		}
		Collections.sort(out);
		return String.join("\n", out);
	}

	static void show(String tag, byte[] b) {
		System.out.println("=== " + tag + " ===");
		System.out.println(table(b));
		System.out.println(nestedHandles(b));
		System.out.println();
	}

	public static void main(String[] args) throws Exception {
		byte[] v1 = Files.readAllBytes(Paths.get(args[0]));
		byte[] v2 = Files.readAllBytes(Paths.get(args[1]));
		byte[] v3 = args.length > 2 ? Files.readAllBytes(Paths.get(args[2])) : null;
		String slash = new ClassReader(v1).getClassName();
		ClassLoader cl = NestTest.class.getClassLoader();
		AnnotationTransformer.HierarchyTree.register(v1);
		AnnotationTransformer.HierarchyTree.register(v2);
		if (v3 != null) AnnotationTransformer.HierarchyTree.register(v3);

		byte[] v1f = AnnotationTransformer.forceStaticLambdas(v1, slash, cl);
		byte[] v2f = AnnotationTransformer.forceStaticLambdas(v2, slash, cl);
		byte[] v3f = v3 == null ? null : AnnotationTransformer.forceStaticLambdas(v3, slash, cl);

		show("v1 forced（原始嵌套）", v1f);
		show("v2 forced（内层插入了一个）", v2f);
		if (v3f != null) show("v3 forced（只改内层方法体）", v3f);

		System.out.println("########## 场景 A：内层插入 -> 对齐 ##########");
		byte[] a = LambdaAligner.align(v1f, v2f);
		show("aligned", a);
		ClassDiffUtil.ClassDiff da = ClassDiffUtil.diff(v1f, a);
		System.out.println("added   = " + da.addedMethods);
		System.out.println("removed = " + da.removedMethods);
		System.out.println();

		if (v3f != null) {
			System.out.println("########## 场景 B：只改内层体 -> 对齐 ##########");
			byte[] b = LambdaAligner.align(v1f, v3f);
			show("aligned", b);
			ClassDiffUtil.ClassDiff db = ClassDiffUtil.diff(v1f, b);
			System.out.println("added   = " + db.addedMethods);
			System.out.println("removed = " + db.removedMethods);
		}
	}
}
