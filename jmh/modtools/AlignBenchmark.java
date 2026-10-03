package modtools;

import nipx.AnnotationTransformer;
import nipx.LambdaAligner;
import org.objectweb.asm.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * `LambdaAligner.align` 的 JMH 基准。
 *
 * <p>用 JMH 而不是手写计时循环的原因：手写循环在同一版本上波动 ±30%
 * （实测 N=1600 得到 55.8 / 61.9 / 66.3 / 68.9 / 74.1 ms），
 * 分辨不出小改动。JMH 提供预热、多轮次、fork 与统计输出。</p>
 *
 * <p>配合 `-prof gc` 可以看**每次 align 的分配量**（gc.alloc.rate.norm，B/op）——
 * 这对 shape 定稿环那类"少分配一批临时对象"的改动，比看时间更灵敏。</p>
 *
 * <p>运行：</p>
 * <pre>
 *   ./gradlew jmh -PjmhIncludes=AlignBenchmark -PjmhProfilers=gc
 * </pre>
 * 或在 build.gradle 的 jmh 块里临时把 includes 改成 AlignBenchmark。
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class AlignBenchmark {

	@Param({"100", "400", "1600"})
	public int n;

	private byte[] oldBytes;
	private byte[] newBytes;

	@Setup(Level.Trial)
	public void setup() {
		oldBytes = force(gen("gen/Align" + n, n, false));
		newBytes = force(gen("gen/Align" + n, n, true));
		// 预热一次，排除首次加载/类初始化的影响
		LambdaAligner.align(oldBytes, newBytes);
	}

	@Benchmark
	public void align(Blackhole bh) {
		bh.consume(LambdaAligner.align(oldBytes, newBytes));
	}

	// ── 夹具生成（与 scratch/hstest 的 AlignScaleBench 同构）────────────────────

	/** 生成含 n 个 lambda 方法的类：lambda$build$i 的体内用 indy 引用 lambda$build$(i+1)，形成长链。 */
	static byte[] gen(String className, int n, boolean changed) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);

		MethodVisitor c = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		c.visitCode();
		c.visitVarInsn(Opcodes.ALOAD, 0);
		c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		c.visitInsn(Opcodes.RETURN);
		c.visitMaxs(0, 0);
		c.visitEnd();

		String owner = className;
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
				mv.visitVarInsn(Opcodes.ALOAD, 0);
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "leaf", "()V", false);
			}
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

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
		if (changed) lf.visitInsn(Opcodes.NOP);
		lf.visitInsn(Opcodes.RETURN);
		lf.visitMaxs(0, 0);
		lf.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	static byte[] force(byte[] r) {
		String slash = new ClassReader(r).getClassName();
		AnnotationTransformer.HierarchyTree.register(r);
		return AnnotationTransformer.forceStaticLambdas(r, slash,
			AlignBenchmark.class.getClassLoader());
	}
}
