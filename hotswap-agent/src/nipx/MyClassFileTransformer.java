package nipx;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.tree.*;
import jdk.internal.org.objectweb.asm.util.*;
import jdk.internal.org.objectweb.asm.commons.AdviceAdapter;

import static nipx.HotSwapAgent.log;

class MyClassFileTransformer implements ClassFileTransformer {
	private static boolean hasAnnotation(byte[] bytes, String annotationDesc) {
		final boolean[] found = {false};
		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
				if (descriptor.equals(annotationDesc)) {
					found[0] = true;
				}
				return null;
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
		return found[0];
	}
	private static byte[] injectTracker(byte[] bytes, String className) {
		ClassReader cr = new ClassReader(bytes);
		ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

		ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
			                                 String signature, String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
				// 只拦截构造函数 <init>
				if ("<init>".equals(name)) {
					return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
						@Override
						protected void onMethodExit(int opcode) {
							// 在构造函数返回之前 (RETURN 或 ATHROW 之前) 插入代码
							if (opcode != ATHROW) {
								// 加载 this
								mv.visitVarInsn(ALOAD, 0);
								// 调用静态方法: nipx.InstanceTracker.register(Object)
								// 注意：你需要确保 InstanceTracker 类在目标 ClassLoader 中可见！
								// 如果 Agent 在 BootClassLoader，而目标类在 AppClassLoader，
								// 这里可能需要反射调用，或者把 Tracker 注入到 AppClassLoader。
								// 简单起见，假设它们能互相访问。
								mv.visitMethodInsn(INVOKESTATIC, "nipx/InstanceTracker", "register", "(Ljava/lang/Object;)V", false);
							}
						}
					};
				}
				return mv;
			}
		};

		cr.accept(cv, 0);
		return cw.toByteArray();
	}
	@Override
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
	                        ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (className == null) {
			return null; // 返回 null 表示不修改字节码
		}
		// ASM 和 JVM 使用 / 分隔，我们统一转换为 .
		String dotClassName = className.replace('/', '.');
		if (!HotSwapAgent.isBlacklisted(dotClassName)) {
			// clone 一份，因为 classfileBuffer 可能会被后续链修改
			HotSwapAgent.bytecodeCache.put(dotClassName, classfileBuffer.clone());
			if (HotSwapAgent.ENABLE_HOTSWAP_EVENT && hasAnnotation(classfileBuffer, "Lnipx/Reloadable;")) {
				log("[ASM] Found @Reloadable on " + dotClassName + ", injecting tracker...");
				return injectTracker(classfileBuffer, dotClassName);
			}
		}
		return null; // 返回 null 表示不修改字节码
	}
}
