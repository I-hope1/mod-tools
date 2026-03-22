package nipx.profiler;

import nipx.*;
import nipx.AnnotationTransformer.HierarchyTree;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.*;
import java.security.ProtectionDomain;
import java.util.*;

public class ProfilerTransformer implements ClassFileTransformer {
	public static final Map<Class<?>, Set<String>> targetMethods = new HashMap<>();

	public static void addTargetMethods(Class<?> clazz, String... methodNames) {
		targetMethods.computeIfAbsent(clazz, _ -> new HashSet<>()).addAll(Arrays.asList(methodNames));
	}
	public static void addTargetMethod(Class<?> clazz, String methodName) {
		targetMethods.computeIfAbsent(clazz, _ -> new HashSet<>()).add(methodName);
	}
	public static void clearTargetMethods(Class<?> clazz)             { targetMethods.remove(clazz); }
	public static void removeTargetMethod(Class<?> clazz, String n)   { targetMethods.computeIfAbsent(clazz, _ -> new HashSet<>()).remove(n); }
	public static void removeTargetMethods(Class<?> clazz, String... ns) {
		Set<String> s = targetMethods.computeIfAbsent(clazz, _ -> new HashSet<>());
		for (String n : ns) s.remove(n);
	}
	public static boolean hasTargetMethod(Class<?> clazz, String n) {
		return targetMethods.computeIfAbsent(clazz, _ -> new HashSet<>()).contains(n);
	}

	ClassReader   classReader;
	ClassWriter   classWriter;
	Set<String>   targetMethodNames;

	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
	                        ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		resetState();

		targetMethods.forEach((clazz, methodNames) -> {
			if (classWriter != null || methodNames == null) return;
			if (classBeingRedefined != null && clazz.isAssignableFrom(classBeingRedefined)) {
				classReader = new ClassReader(classfileBuffer);
				classWriter = new AnnotationTransformer.MyClassWriter(classReader, loader);
				targetMethodNames = methodNames;
			}
			if (classBeingRedefined == null &&
				HierarchyTree.isAssignableFrom(AnnotationTransformer.dot2slash(clazz), className, loader)) {
				classReader = new ClassReader(classfileBuffer);
				classWriter = new AnnotationTransformer.MyClassWriter(classReader, loader);
				targetMethodNames = methodNames;
			}
		});
		if (classWriter == null) return null;

		var cv = new ClassVisitor(Opcodes.ASM9, classWriter) {
			boolean anyProfiled = false;

			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
			                                 String signature, String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
				if (name.startsWith("<") ||
					(access & Opcodes.ACC_SYNTHETIC) != 0 ||
					(access & Opcodes.ACC_BRIDGE) != 0) return mv;

				final String simpleClass = className.substring(className.lastIndexOf('/') + 1);
				final String methodKey   = simpleClass + "." + name;

				return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
					final boolean isProfiled = targetMethodNames.contains(name);
					int startTimeVar;
					int durationVar;

					@Override
					protected void onMethodEnter() {
						if (!isProfiled) return;
						anyProfiled  = true;
						startTimeVar = newLocal(Type.LONG_TYPE);
						durationVar  = newLocal(Type.LONG_TYPE);

						// startTime = System.nanoTime()
						visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
						visitVarInsn(LSTORE, startTimeVar);

						// ProfilerData.recordEntry(methodKey)
						visitLdcInsn(methodKey);
						visitMethodInsn(INVOKESTATIC, "nipx/profiler/ProfilerData",
							"recordEntry", "(Ljava/lang/String;)V", false);
					}

					@Override
					protected void onMethodExit(int opcode) {
						if (!isProfiled) return;

						if (opcode == ATHROW) {
							// 异常路径：只弹栈，不记录耗时
							visitLdcInsn(methodKey);
							visitMethodInsn(INVOKESTATIC, "nipx/profiler/ProfilerData",
								"recordCancel", "(Ljava/lang/String;)V", false);
							return;
						}

						// duration = System.nanoTime() - startTime
						visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
						visitVarInsn(LLOAD, startTimeVar);
						visitInsn(LSUB);
						visitVarInsn(LSTORE, durationVar);

						// ProfilerData.recordExit(methodKey, duration)
						visitLdcInsn(methodKey);
						visitVarInsn(LLOAD, durationVar);
						visitMethodInsn(INVOKESTATIC, "nipx/profiler/ProfilerData",
							"recordExit", "(Ljava/lang/String;J)V", false);
					}
				};
			}
		};

		try {
			classReader.accept(cv, ClassReader.EXPAND_FRAMES);
			byte[]  bytes       = classWriter.toByteArray();
			boolean anyProfiled = cv.anyProfiled;
			resetState();
			return anyProfiled ? bytes : classfileBuffer;
		} catch (Throwable e) {
			HotSwapAgent.error("Profiler injection failed for " + className, e);
			return null;
		}
	}

	private void resetState() { classReader = null; classWriter = null; targetMethodNames = null; }
}