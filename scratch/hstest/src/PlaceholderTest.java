import nipx.MethodFingerprinter;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.file.*;

/**
 * 精确验证 #SYNTHETIC_METHOD# 占位：
 * 用 ASM 生成两个类，外层 lambda 的字节码**逐条指令完全相同**，
 * 唯一差别是它引用的内层 lambda 方法叫什么名字（lambda$build$1 vs lambda$build$9）。
 *   -> 占位生效：外层 hash 必须相同
 *   -> 占位失效：外层 hash 不同
 *
 * 同时生成"槽位不同"的第三个类，证明真正影响 hash 的是槽位，不是内层名字。
 */
public class PlaceholderTest {

	static final String OWNER = "gen/Outer";

	/** 生成 Outer 类：外层 lambda 体内 indy 指向 innerName()V，然后唤醒它。 */
	static byte[] gen(String innerName, int localSlot) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);

		cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC, "outer", "Ljava/lang/Runnable;", null, null).visitEnd();

		// 外层 lambda：()V  ->  { Runnable x = <indy innerName>; x.run(); ... }
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
			"lambda$build$0", "()V", null, null);
		mv.visitCode();

		Handle   inner   = new Handle(Opcodes.H_INVOKESTATIC, OWNER, innerName, "()V", false);
		Handle   bsm     = new Handle(Opcodes.H_INVOKESTATIC,
			"java/lang/invoke/LambdaMetafactory", "metafactory",
			"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
			+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
			+ "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
			+ "Ljava/lang/invoke/CallSite;", false);

		mv.visitInvokeDynamicInsn("run",
			"()Ljava/lang/Runnable;", bsm,
			Type.getType("()V"), inner, Type.getType("()V"));
		mv.visitVarInsn(Opcodes.ASTORE, localSlot);
		mv.visitVarInsn(Opcodes.ALOAD, localSlot);
		mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		// 内层 lambda 方法定义（方法体固定不变）
		MethodVisitor inner0 = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
			innerName, "()V", null, null);
		inner0.visitCode();
		inner0.visitInsn(Opcodes.RETURN);
		inner0.visitMaxs(0, 0);
		inner0.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	static long fp(byte[] bytes, String method) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		MethodFingerprinter p = new MethodFingerprinter();
		for (MethodNode mn : cn.methods) {
			if (!mn.name.equals(method)) continue;
			p.reset(); p.setContext(cn.name); mn.accept(p);
			return p.getHash();
		}
		return 0;
	}

	public static void main(String[] args) throws Exception {
		byte[] a = gen("lambda$build$1", 0);   // 内层名 = lambda$build$1，槽位 0
		byte[] b = gen("lambda$build$9", 0);   // 内层名 = lambda$build$9，槽位 0（只有名字不同）
		byte[] c = gen("lambda$build$1", 1);   // 内层名相同，槽位 1（只有槽位不同）

		long ha = fp(a, "lambda$build$0");
		long hb = fp(b, "lambda$build$0");
		long hc = fp(c, "lambda$build$0");

		System.out.println("A: 内层名=lambda$build$1 槽位=0  hash=" + Long.toHexString(ha));
		System.out.println("B: 内层名=lambda$build$9 槽位=0  hash=" + Long.toHexString(hb));
		System.out.println("C: 内层名=lambda$build$1 槽位=1  hash=" + Long.toHexString(hc));
		System.out.println();
		System.out.println(">>> 只有内层名字不同 -> 外层 hash 相同？ " + (ha == hb)
			+ "   （占位机制" + (ha == hb ? "生效" : "失效") + "）");
		System.out.println(">>> 只有槽位不同     -> 外层 hash 相同？ " + (ha == hc)
			+ "   （槽位" + (ha == hc ? "不参与" : "参与") + "指纹）");
	}
}
