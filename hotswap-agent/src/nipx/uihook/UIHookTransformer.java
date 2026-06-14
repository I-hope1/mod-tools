package nipx.uihook;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;

import static nipx.AnnotationTransformer.dot2slash;

public class UIHookTransformer implements ClassFileTransformer {
	@Override
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
	                        java.security.ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		ClassReader cr = new ClassReader(classfileBuffer);
		ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

		ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
			private String currentClassName;

			@Override
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				this.currentClassName = name.replace('/', '.');
				super.visit(version, access, name, signature, superName, interfaces);
			}

			@Override
			public MethodVisitor visitMethod(int access, String methodName, String methodDesc, String signature,
			                                 String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, methodName, methodDesc, signature, exceptions);
				return new MethodVisitor(Opcodes.ASM9, mv) {
					private int currentLineNumber = -1;
					private int callIndexOnLine   = 0;

					@Override
					public void visitLineNumber(int line, Label start) {
						currentLineNumber = line;
						callIndexOnLine = 0; // 换行时计数归零
						super.visitLineNumber(line, start);
					}

					@Override
					public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
						super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
						// 如果调用的方法返回 Cell（无论是 Table.add 还是 Table.button 均适用）
						if (Type.getReturnType(descriptor).getDescriptor().equals("Larc/scene/ui/layout/Cell;")) {
							if (currentLineNumber != -1) {
								visitInsn(Opcodes.DUP); // 复制栈顶返回的 Cell
								visitLdcInsn(currentClassName);
								visitLdcInsn(methodName + methodDesc);
								visitLdcInsn(currentLineNumber);
								visitLdcInsn(callIndexOnLine);
								// 自动注册实例
								super.visitMethodInsn(Opcodes.INVOKESTATIC, dot2slash(UIHookRegistry.class), "register", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;II)V", false);
								callIndexOnLine++;
							}
						}
					}
				};
			}
		};
		cr.accept(cv, 0);
		return cw.toByteArray();
	}
}
