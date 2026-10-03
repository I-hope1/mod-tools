import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * ② 的收益测量：`infoByName` 从"双重线性查找"改为 O(1) 索引，实际差别有多大？
 *
 * 需要一个**大量 lambda 的大类**：只有 N 足够大时，三个 64 轮定稿循环里的
 * O(64·N²) 才会显出来。夹具用 ASM 直接生成，规模可控。
 *
 * 做法：
 *   1. 生成一个含 N 个 lambda 合成方法的类，并在 `build()` 里用 indy 互相引用，
 *      使得 children 非空（否则三个定稿环几乎不做功，测不出差异）；
 *   2. 把生成的两个版本（old/new）喂给 `align`，反复计时取最小值；
 *   3. 为对比"改前"，用一个**独立的线性查找实现**复现旧逻辑的复杂度 ——
 *      直接改产品代码再编译两遍代价太高，且容易出错；
 *      这里改为在同一夹具上量出"当前实现的耗时"，并**单独量出
 *      线性查找在该规模下的理论成本**（遍历 N 个元素 × 调用次数），
 *      两者对照，得出量级判断，而不是伪造一个 before 数字。
 */
public class AlignScaleBench {

	static final int N = 400;          // lambda 方法数
	static final int REPEAT = 12;      // align 次数
	static final int WARM = 3;

	/** 生成一个含 N 个 lambda 方法的类：每个 lambda_i 的体内引用 lambda_{i+1}，形成长链。 */
	static byte[] gen(String className, int n, boolean changed) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);

		// 默认构造器
		MethodVisitor c = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		c.visitCode();
		c.visitVarInsn(Opcodes.ALOAD, 0);
		c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		c.visitInsn(Opcodes.RETURN);
		c.visitMaxs(0, 0);
		c.visitEnd();

		String owner = className;
		// N 个 lambda 方法：lambda$build$i (Lowner;)V
		// 体内对 lambda_{i+1} 发 indy，从而使 children 非空、定稿环有活干
		for (int i = 0; i < n; i++) {
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
				"lambda$build$" + i, "(L" + owner + ";)V", null, null);
			mv.visitCode();
			if (i + 1 < n) {
				Handle target = new Handle(Opcodes.H_INVOKESTATIC, owner,
					"lambda$build$" + (i + 1), "(L" + owner + ";)V", false);
				mv.visitVarInsn(Opcodes.ALOAD, 0);
				mv.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;",
					new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
						"metafactory",
						"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
							+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
							+ "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
							+ "Ljava/lang/invoke/CallSite;", false),
					Type.getType("()V"), target, Type.getType("()V"));
				mv.visitInsn(Opcodes.POP);
			} else {
				// 叶子：调用一个业务方法
				mv.visitVarInsn(Opcodes.ALOAD, 0);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "leaf", "()V", false);
			}
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		// build()：引用 lambda$build$0
		MethodVisitor b = cw.visitMethod(Opcodes.ACC_PUBLIC, "build", "()V", null, null);
		b.visitCode();
		b.visitVarInsn(Opcodes.ALOAD, 0);
		b.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;",
			new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
				"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
					+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
					+ "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
					+ "Ljava/lang/invoke/CallSite;", false),
			Type.getType("()V"),
			new Handle(Opcodes.H_INVOKESTATIC, owner, "lambda$build$0", "(L" + owner + ";)V", false),
			Type.getType("()V"));
		b.visitInsn(Opcodes.POP);
		b.visitInsn(Opcodes.RETURN);
		b.visitMaxs(0, 0);
		b.visitEnd();

		MethodVisitor lf = cw.visitMethod(Opcodes.ACC_PUBLIC, "leaf", "()V", null, null);
		lf.visitCode();
		if (changed) { lf.visitInsn(Opcodes.NOP); }   // new 版本：叶子体略变，制造"改体"
		lf.visitInsn(Opcodes.RETURN);
		lf.visitMaxs(0, 0);
		lf.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	static byte[] force(byte[] r) {
		String slash = new ClassReader(r).getClassName();
		AnnotationTransformer.HierarchyTree.register(r);
		return AnnotationTransformer.forceStaticLambdas(r, slash, AlignScaleBench.class.getClassLoader());
	}

	public static void main(String[] args) {
		System.out.println("== ② infoByName O(1) 规模基准 ==");
		for (int n : new int[]{100, 400, 1600}) {
			byte[] oldB = force(gen("gen/Scale" + n, n, false));
			byte[] newB = force(gen("gen/Scale" + n, n, true));
			// 热身
			for (int i = 0; i < WARM; i++) LambdaAligner.align(oldB, newB);
			long best = Long.MAX_VALUE;
			for (int i = 0; i < REPEAT; i++) {
				long t0 = System.nanoTime();
				LambdaAligner.align(oldB, newB);
				long dt = System.nanoTime() - t0;
				if (dt < best) best = dt;
			}
			System.out.printf("   N=%5d  align 最快 %8.3f ms%n", n, best / 1e6);
		}
		System.out.println();
		System.out.println("   说明：本项只量**当前（O(1)）实现**的耗时随 N 的增长形态。");
		System.out.println("   旧的双重线性查找在同一夹具下为 O(64·N²)，未在本次二进制上复现；");
		System.out.println("   若要严格对比，需 checkout 改前版本各跑一次（见 README 的记录）。");
	}
}
