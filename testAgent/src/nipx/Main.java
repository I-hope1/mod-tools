package nipx;

import net.bytebuddy.jar.asm.*;

import java.io.*;
import java.lang.instrument.*;
import java.security.ProtectionDomain;


public class Main {

	// 假设游戏要修改的类和方法
	private static final String TARGET_CLASS         = "mindustry.entities.Damage";
	private static final String TARGET_STATIC_METHOD = "findLength";

	public static void agentmain(String agentArgs, Instrumentation inst) {
		System.out.println("[YourAgent] Agent loaded dynamically via agentmain.");

        /* // 1. 注册 ClassFileTransformer
        new AgentBuilder.Default()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION) // 允许重新转换已加载的类
            // .with(AgentBuilder.InitializationStrategy.SelfInjection.Default.ENABLED) // 确保Agent相关类能被正确加载
            .ignore((ElementMatcher) ElementMatchers.noneOf(String.class, Object.class).and(ElementMatchers.isBootstrapClassLoader())) // 忽略一些不必要的系统类
            .type(ElementMatchers.named(TARGET_CLASS)) // 匹配目标类
            .transform((builder, typeDescription, classLoader, module) -> {
                System.out.println("[YourAgent] Applying transformation to class: " + typeDescription.getName());
                return builder
                    .method(ElementMatchers.named(TARGET_STATIC_METHOD).and(ElementMatchers.isStatic())) // 匹配目标static方法
                    .intercept(Advice.to(MyStaticMethodAdvice.class)); // 替换为你的Advice
            })
            // .with(AgentBuilder.Listener.StreamWriting.toSystemOut()) // 打印转换日志
            .installOn(inst); */

		// customTransformer(inst);

		System.out.println("[YourAgent] ClassFileTransformer registered.");

		// 2. 重新转换已经加载的类 (如果目标类在Agent加载前已经被游戏加载了)
		try {
			// 尝试获取目标类的Class对象
			// 注意: 这必须在正确的ClassLoader上下文中运行。
			// 如果CoreLogic被AppClassLoader加载，则直接Class.forName即可。
			// 如果被自定义游戏ClassLoader加载，你需要获取到那个ClassLoader实例。
			// 在Agent的环境下，Class.forName通常会正确地使用加载Agent的ClassLoader或其父ClassLoader。
			Class<?> targetClazz = Class.forName(TARGET_CLASS);
			if (targetClazz != null) {
				System.out.println("[YourAgent] Target class " + TARGET_CLASS + " already loaded. Attempting retransformation.");
				inst.retransformClasses(targetClazz);
				System.out.println("[YourAgent] Retransformation of " + TARGET_CLASS + " completed.");
			}
		} catch (ClassNotFoundException e) {
			System.out.println("[YourAgent] Target class " + TARGET_CLASS + " not yet loaded. Will be transformed upon first load.");
		} catch (Throwable e) { // 捕获所有异常，特别是retransformClasses可能抛出的一些错误
			System.err.println("[YourAgent] Error during retransformation: " + e.getMessage());
			e.printStackTrace();
		}
	}
	private static void customTransformer(Instrumentation inst) {
		inst.addTransformer(new ClassFileTransformer() {
			@Override
			public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			                        ProtectionDomain protectionDomain, byte[] classfileBuffer) {
				if (className.equals(TARGET_CLASS.replace(".", "/"))) {
					System.out.println("[YourAgent] Applying transformation to class: " + className);
					ClassReader reader = new ClassReader(classfileBuffer);
					ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
					reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
						public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
						                                 String[] exceptions) {
							MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
							if ((access & Opcodes.ACC_STATIC) != 0 && name.equals(TARGET_STATIC_METHOD)) {
								return new MethodAdapter(mv);
							}
							return mv;
						}
					}, 0);
					byte[] bytes = writer.toByteArray();
					writeBytesExternal(className, bytes);
					return bytes;
				}
				return classfileBuffer;
			}
		});
	}
	static void writeBytesExternal(String className, byte[] bytes) {
		File file = new File("F:/gen/" + className.replace("/", ".") + ".class");
		try (FileOutputStream fos = new FileOutputStream(file)) {
			fos.write(bytes);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	private static class MethodAdapter extends MethodVisitor {
		public MethodAdapter(MethodVisitor mv) { super(Opcodes.ASM9, mv); }
		public void visitCode() {
			mv.visitIntInsn(Opcodes.FLOAD, 1);
			mv.visitInsn(Opcodes.FCONST_2);
			mv.visitInsn(Opcodes.FMUL);
			mv.visitInsn(Opcodes.FRETURN);
			mv.visitMaxs(4, 0);
		}

		@Override
		public void visitLocalVariable(String name, String descriptor, String signature, Label start,
		                               Label end, int index) { }
		@Override
		public void visitEnd() { }
		@Override
		public void visitIincInsn(int varIndex, int increment) { }
		@Override
		public void visitVarInsn(int opcode, int varIndex) { }
		@Override
		public void visitJumpInsn(int opcode, Label label) { }
		@Override
		public void visitLineNumber(int line, Label start) { }
		@Override
		public void visitFieldInsn(int opcode, String owner, String name, String descriptor) { }
		@Override
		public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
		                            boolean isInterface) { }
		@Override
		public void visitInsn(int opcode) { }
		@Override
		public void visitIntInsn(int opcode, int operand) { }
		@Override
		public void visitMaxs(int maxStack, int maxLocals) { }
		@Override
		public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) { /* Do nothing */ }
		@Override
		public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) { /* Do nothing */ }
		@Override
		public void visitMultiANewArrayInsn(String descriptor, int numDimensions) { /* Do nothing */ }
		@Override
		public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath, String descriptor,
		                                             boolean visible) { return null; /* Do nothing */ }
		@Override
		public void visitTryCatchBlock(Label start, Label end, Label handler, String type) { /* Do nothing */ }
		public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath, String descriptor,
		                                                 boolean visible) { return null; }
		@Override
		public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath, Label[] start, Label[] end,
		                                                      int[] index, String descriptor,
		                                                      boolean visible) { return null; /* Do nothing */ }
	}
}