package nipx;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.jar.asm.*;

import java.lang.instrument.*;

import static net.bytebuddy.matcher.ElementMatchers.named;

public class YourAgent {

	public static String LAUNCHER_CLASS = "mindustry.desktop.DesktopLauncher";
	public static void agentmain(String agentArgs, Instrumentation inst) {
		System.out.println("[YourAgent] Agent loaded dynamically via agentmain.");

		try {
			/* System.out.println("[YourAgent] ----");
			for (Class loadedClass : inst.getAllLoadedClasses()) {
				if (!loadedClass.isHidden() && loadedClass.getClassLoader() == ClassLoader.getSystemClassLoader()) System.out.println(loadedClass.getName());
			}
			System.out.println("[YourAgent] ----"); */
			ClassWriter cw            = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			String      applicationClass = "arc.backend.sdl.SdlApplication";
			Class<?>    theClass      = Class.forName(applicationClass);
			ClassReader cr            = new ClassReader(theClass.getClassLoader().getResourceAsStream(applicationClass.replace(".", "/") + ".class"));
			cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
				public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
				                                 String[] exceptions) {
					MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
					if (name.equals("loop")) {
						return new MethodVisitor(Opcodes.ASM9, mv) {
							public void visitLineNumber(int line, Label start) {
								super.visitLineNumber(line, start);
								if (line == 211) {
									mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
									mv.visitLdcInsn("loop() method called! (Modified by Agent)");
									mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
								}
							}
						};
					}
					return mv;
				}
			}, 0);
			Main.writeBytesExternal(applicationClass, cw.toByteArray());
			inst.redefineClasses(new ClassDefinition(theClass, cw.toByteArray()));
			inst.retransformClasses(theClass);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		// handleCrash(inst);
		// A(inst);
	}
	private static void handleCrash(Instrumentation inst) {
		try {
			ClassWriter cw       = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			Class<?>    theClass = Class.forName(LAUNCHER_CLASS);
			ClassReader cr       = new ClassReader(theClass.getClassLoader().getResourceAsStream(LAUNCHER_CLASS.replace(".", "/") + ".class"));
			cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
				public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
				                                 String[] exceptions) {
					MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
					if (name.equals("handleCrash")) {
						return new MethodVisitor(Opcodes.ASM9, mv) {
							public void visitCode() {
								// 生成：System.out.println("handleCrash(e) method called! (Modified by Agent)");
								mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
								mv.visitLdcInsn("handleCrash(e) method called! (Modified by Agent)");
								mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
							}
						};
					}
					return mv;
				}
			}, 0);
			inst.redefineClasses(new ClassDefinition(theClass, cw.toByteArray()));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	private static void A(Instrumentation inst) {
		// 定义你的转换规则
		new AgentBuilder.Default()
		 .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION) // 启用重转换策略
		 .type(named(LAUNCHER_CLASS)) // 指定要修改的类
		 .transform((builder, type, classLoader, module, protectionDomain) -> {
			 // 假设你要修改 launcher 类中的 handleCrash() 方法
			 return builder.method(named("handleCrash")).intercept(Advice.to(launcherDrawAdvice.class));
		 })
		 .installOn(inst); // 安装此Transformer

		// 现在，所有未来加载的 launcher 类都会被转换。
		// 但如果 launcher 类已经加载了，我们需要手动触发 retransformClasses
		try {
			Class<?> launcherClass = Class.forName(LAUNCHER_CLASS);
			if (inst.isRetransformClassesSupported()) {
				System.out.println("[YourAgent] Retransforming launcher class...");
				inst.retransformClasses(launcherClass); // 触发已加载 launcher 类的重转换
				System.out.println("[YourAgent] launcher class retransformed.");
			} else {
				System.err.println("[YourAgent] Retransformation not supported by this JVM.");
			}
		} catch (ClassNotFoundException e) {
			System.err.println("[YourAgent] launcher class not found for retransformation.");
		} catch (Throwable e) {
			System.err.println("[YourAgent] Error during retransformation: " + e.getMessage());
			e.printStackTrace();
		}
	}
}

// 你的 Advice 类 (例如，用于修改 launcher 类的 draw 方法)
class launcherDrawAdvice {
	@Advice.OnMethodEnter
	public static void onEnter(Throwable e) {
		System.out.println("handleCrash(e) method called! (Modified by Agent)");
		// 可以在这里插入你的逻辑
	}
}