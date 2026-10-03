import nipx.AnnotationTransformer;
import nipx.ClassDiffUtil;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;
import java.util.*;

/** 对比 "raw 基线对齐" 与 "forced 基线对齐" 在插入场景下的差异。 */
public class RawVsForcedTest {

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
			List<String> ops = new ArrayList<>();
			for (AbstractInsnNode n : mn.instructions) {
				if (n instanceof FieldInsnNode f) ops.add(f.name);
				else if (n instanceof MethodInsnNode m && !m.owner.startsWith("java/lang/String"))
					ops.add(m.owner.substring(m.owner.lastIndexOf('/') + 1) + "." + m.name);
			}
			out.add("    " + (mn.access & Opcodes.ACC_STATIC) + " " + mn.name + mn.desc + " -> " + ops);
		}
		return String.join("\n", out);
	}

	public static void main(String[] args) throws Exception {
		byte[] v1 = Files.readAllBytes(Paths.get(args[0]));
		byte[] v2 = Files.readAllBytes(Paths.get(args[1]));
		String slash = new ClassReader(v1).getClassName();
		ClassLoader cl = RawVsForcedTest.class.getClassLoader();
		AnnotationTransformer.HierarchyTree.register(v1);
		AnnotationTransformer.HierarchyTree.register(v2);

		byte[] v1f = AnnotationTransformer.forceStaticLambdas(v1, slash, cl);
		byte[] v2f = AnnotationTransformer.forceStaticLambdas(v2, slash, cl);

		System.out.println("=========== RAW 形态（编译器原始产物） ===========");
		System.out.println("old raw:");
		System.out.println(shape(v1));
		System.out.println("new raw:");
		System.out.println(shape(v2));

		System.out.println("\n=========== FORCED 形态（JVM 里实际生效的） ===========");
		System.out.println("old forced:");
		System.out.println(shape(v1f));
		System.out.println("new forced:");
		System.out.println(shape(v2f));

		System.out.println("\n=========== 1) raw 基线：align(rawOld, rawNew) ===========");
		byte[] r = LambdaAligner.align(v1, v2);
		System.out.println("aligned raw:");
		System.out.println(shape(r));
		dumpDiff("raw基线", v1, r);

		System.out.println("\n=========== 2) forced 基线：align(forcedOld, forcedNew) ===========");
		byte[] f = LambdaAligner.align(v1f, v2f);
		System.out.println("aligned forced:");
		System.out.println(shape(f));
		dumpDiff("forced基线", v1f, f);
	}

	static void dumpDiff(String tag, byte[] oldB, byte[] newB) {
		ClassDiffUtil.ClassDiff d = ClassDiffUtil.diff(oldB, newB);
		System.out.println("  [" + tag + "] added   = " + d.addedMethods);
		System.out.println("  [" + tag + "] removed = " + d.removedMethods);
	}
}
