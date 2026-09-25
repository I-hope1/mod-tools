package hope.magic.example;

import hope.magic.runtime.Magic;
import jdk.internal.misc.Unsafe;
import org.objectweb.asm.*;

import java.lang.invoke.MethodHandles.Lookup;

public class TestLinkTo {
	static {
		Magic.install();
	}

	static final Unsafe UNSAFE = Unsafe.getUnsafe();
	static final Lookup LOOKUP = Magic.lookup;

	public static void main(String[] args) {
		// 创建一个桥接类
		createMagicHolder();
	}
	private static void createMagicHolder() {
		var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		cw.visit(Opcodes.V9, Opcodes.ACC_PUBLIC,
		 "java/lang/invoke/MagicHolder", null, "java/lang/Object", null);

		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "mn", "Ljava/lang/invoke/MemberName;", null, null).visitEnd();
		{// <init>(MemberName mn) -> MagicHolder
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>",
			 "(Ljava/lang/invoke/MemberName;)V", null, null);
			mv.visitCode();
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
			mv.visitVarInsn(Opcodes.ALOAD, 0); // this
			mv.visitVarInsn(Opcodes.ALOAD, 1); // mn
			mv.visitMethodInsn(Opcodes.PUTFIELD, "java/lang/invoke/MagicHolder", "mn", "Ljava/lang/invoke/MemberName;", false);
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		{// of(MemberName mn) -> MagicHolder
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "of",
			 "(Ljava/lang/invoke/MemberName;)Ljava/lang/invoke/MagicHolder;", null, null);
			mv.visitTypeInsn(Opcodes.NEW, "java/lang/invoke/MagicHolder");
			mv.visitInsn(Opcodes.DUP);
			mv.visitVarInsn(Opcodes.ALOAD, 0); // aload mn
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/invoke/MagicHolder", "<init>", "(Ljava/lang/invoke/MemberName;)V", false);
			mv.visitInsn(Opcodes.ARETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		{// resolveOrNull(byte refKind, Class<?> refc, String name, Class<?> type) -> MagicHolder
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "resolveOrNull",
			 "(BLjava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MagicHolder;", null, null);
			// MemberName.getFactory()
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MemberName", "getFactory", "()Ljava/lang/invoke/MemberName$Factory;", false);

			mv.visitVarInsn(Opcodes.ILOAD, 0); // refKind -> resolveOrNull

			// new MemberName(refc, name, type, refKind)
			mv.visitTypeInsn(Opcodes.NEW, "java/lang/invoke/MemberName");
			mv.visitInsn(Opcodes.DUP);
			mv.visitVarInsn(Opcodes.ALOAD, 1); // refc
			mv.visitVarInsn(Opcodes.ALOAD, 2); // name
			mv.visitVarInsn(Opcodes.ALOAD, 3); // type
			mv.visitVarInsn(Opcodes.ILOAD, 0); // refKind
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/invoke/MemberName", "<init>", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;B)V", false);

			mv.visitInsn(Opcodes.ACONST_NULL); // class = null
			mv.visitInsn(Opcodes.ICONST_M1); // allowedModes = -1

			// factory.resolveOrNull(byte refKind, MemberName m) -> MemberName
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MemberName$Factory", "resolveOrNull", "(BLjava/lang/invoke/MemberName;Ljava/lang/Class;I)Ljava/lang/invoke/MemberName;", false);

			mv.visitInsn(Opcodes.DUP); // 复制栈顶元素

			Label success = new Label();
			mv.visitJumpInsn(Opcodes.IFNONNULL, success);

			mv.visitInsn(Opcodes.POP); // 弹出 null(MemberName)
			mv.visitInsn(Opcodes.ACONST_NULL); // null(MagicHolder)
			mv.visitInsn(Opcodes.ARETURN);

			mv.visitLabel(success);
			// MagicHolder.of(MemberName mn) -> MagicHolder
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MagicHolder", "of", "(Ljava/lang/invoke/MemberName;)Ljava/lang/invoke/MagicHolder;", false);

			mv.visitInsn(Opcodes.ARETURN);
			mv.visitMaxs(0, 0); // 自动计算栈大小和局部变量表大小
			mv.visitEnd();
		}
		{// resolveOrNull(byte refKind, Class<?> refc, String name, Class<?> type) -> MagicHolder
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "resolveOrNull",
			 "(BLjava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MagicHolder;", null, null);
			// MemberName.getFactory()
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MemberName", "getFactory", "()Ljava/lang/invoke/MemberName$Factory;", false);

			mv.visitVarInsn(Opcodes.ILOAD, 0); // refKind -> resolveOrNull

			// new MemberName(refc, name, type, refKind)
			mv.visitTypeInsn(Opcodes.NEW, "java/lang/invoke/MemberName");
			mv.visitInsn(Opcodes.DUP);
			mv.visitVarInsn(Opcodes.ALOAD, 1); // refc
			mv.visitVarInsn(Opcodes.ALOAD, 2); // name
			mv.visitVarInsn(Opcodes.ALOAD, 3); // type
			mv.visitVarInsn(Opcodes.ILOAD, 0); // refKind
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/invoke/MemberName", "<init>", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;B)V", false);

			mv.visitInsn(Opcodes.ACONST_NULL); // class = null
			mv.visitInsn(Opcodes.ICONST_M1); // allowedModes = -1

			// factory.resolveOrNull(byte refKind, MemberName m) -> MemberName
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MemberName$Factory", "resolveOrNull", "(BLjava/lang/invoke/MemberName;Ljava/lang/Class;I)Ljava/lang/invoke/MemberName;", false);

			mv.visitInsn(Opcodes.DUP); // 复制栈顶元素

			Label success = new Label();
			mv.visitJumpInsn(Opcodes.IFNONNULL, success);
			mv.visitInsn(Opcodes.POP); // 弹出 null(MemberName)
			mv.visitInsn(Opcodes.ACONST_NULL); // null(MagicHolder)
			mv.visitInsn(Opcodes.ARETURN);

			mv.visitLabel(success);
			// MagicHolder.of(MemberName mn) -> MagicHolder
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MagicHolder", "of", "(Ljava/lang/invoke/MemberName;)Ljava/lang/invoke/MagicHolder;", false);

			mv.visitInsn(Opcodes.ARETURN);
			mv.visitMaxs(0, 0); // 自动计算栈大小和局部变量表大小
			mv.visitEnd();
		}

		/* try (OutputStream stream = new FileOutputStream("F:/classes/MagicHolder.class")) {
			stream.write(cw.toByteArray());
		} catch (IOException e) {
			throw new RuntimeException(e);
		} */

		UNSAFE.defineClass(null, cw.toByteArray(), 0, cw.toByteArray().length, null, null);
	}


}
