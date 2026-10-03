import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * 验证发现 A：跨组指纹匹配会波及"非 lambda 的合成方法"。
 *
 * 构造两个 ACC_SYNTHETIC 方法，名字带 $ 但**不是** lambda 系（模拟 Kotlin 的
 * getFoo$annotations 这类），方法体完全相同：
 *   v1: getFoo$annotations()I   getBar$annotations()I
 *   v2: getBar$annotations()I   getBaz$annotations()I     （Foo 删、Baz 增）
 * 期望：Baz 不该被改名成 getFoo$annotations（外部类对它的调用不会被改写）。
 */
public class KtTest {

	static final String OWNER = "kt/Kt";

	static byte[] gen(boolean withFoo) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);

		if (withFoo) {
			MethodVisitor m = cw.visitMethod(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "getFoo$annotations", "()I", null, null);
			m.visitCode();
			m.visitInsn(Opcodes.ICONST_0);
			m.visitInsn(Opcodes.IRETURN);
			m.visitMaxs(0, 0);
			m.visitEnd();
		}

		MethodVisitor b = cw.visitMethod(
			Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "getBar$annotations", "()I", null, null);
		b.visitCode();
		b.visitInsn(Opcodes.ICONST_0);
		b.visitInsn(Opcodes.IRETURN);
		b.visitMaxs(0, 0);
		b.visitEnd();

		if (!withFoo) {
			MethodVisitor z = cw.visitMethod(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "getBaz$annotations", "()I", null, null);
			z.visitCode();
			z.visitInsn(Opcodes.ICONST_0);
			z.visitInsn(Opcodes.IRETURN);
			z.visitMaxs(0, 0);
			z.visitEnd();
		}

		cw.visitEnd();
		return cw.toByteArray();
	}

	static String methods(byte[] bytes) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		List<String> out = new ArrayList<>();
		for (MethodNode mn : cn.methods) {
			out.add("    " + mn.name + mn.desc);
		}
		Collections.sort(out);
		return String.join("\n", out);
	}

	public static void main(String[] args) throws Exception {
		byte[] v1 = gen(true);
		byte[] v2 = gen(false);
		ClassLoader cl = KtTest.class.getClassLoader();
		AnnotationTransformer.HierarchyTree.register(v1);
		AnnotationTransformer.HierarchyTree.register(v2);

		byte[] v1f = AnnotationTransformer.forceStaticLambdas(v1, OWNER, cl);
		byte[] v2f = AnnotationTransformer.forceStaticLambdas(v2, OWNER, cl);

		System.out.println("=== v1 ===");
		System.out.println(methods(v1f));
		System.out.println("=== v2 (Foo 删、Baz 增) ===");
		System.out.println(methods(v2f));

		byte[] aligned = LambdaAligner.align(v1f, v2f);
		System.out.println("=== aligned ===");
		System.out.println(methods(aligned));

		// 注意：aligned 里出现 getFoo$annotations 本身是正常的 —— 它被复活成"幽灵空壳"
		// 兜住外部类对它的老调用。关键判据是 getBaz 是否**保住了自己的名字**。
		String a = methods(aligned);
		boolean bazKeptName = a.contains("getBaz$annotations");
		boolean bazRenamedToFoo = !bazKeptName && a.contains("getFoo$annotations");
		System.out.println("\n>>> getBaz$annotations 保住自己的名字了吗？ " + bazKeptName);
		System.out.println(">>> getBaz 被改名成 getFoo$annotations 了吗？ " + bazRenamedToFoo
			+ (bazRenamedToFoo ? "   ← 发现 A 成立（外部类调用会 NoSuchMethodError）"
			                   : "   ← 未被波及（修复生效）"));
	}
}
