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
	public static void clearTargetMethods(Class<?> clazz) {
		targetMethods.remove(clazz);
	}
	public static void removeTargetMethod(Class<?> clazz, String methodName) {
		targetMethods.computeIfAbsent(clazz, _ -> new HashSet<>()).remove(methodName);
	}
	public static void removeTargetMethods(Class<?> clazz, String... methodNames) {
		Set<String> strings = targetMethods.computeIfAbsent(clazz, _ -> new HashSet<>());
		for (String s : methodNames) {
			strings.remove(s);
		}
	}
	public static boolean hasTargetMethod(Class<?> clazz, String methodName) {
		return targetMethods.computeIfAbsent(clazz, _ -> new HashSet<>()).contains(methodName);
	}

	ClassReader       classReader;
	ClassWriter       classWriter;
	Set<String> targetMethodNames;
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
	                        ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		resetState();

		targetMethods.forEach((clazz, methodNames) -> {
			if (classWriter != null) return;
			if (methodNames == null) return;
			if (classBeingRedefined != null && clazz.isAssignableFrom(classBeingRedefined)) {
				classReader = new ClassReader(classfileBuffer);
				classWriter = new AnnotationTransformer.MyClassWriter(classReader, loader);
				targetMethodNames = methodNames;
			}
			if (classBeingRedefined == null && HierarchyTree.isAssignableFrom(AnnotationTransformer.dot2slash(clazz), className, loader)) {
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

				// 跳过构造函数、静态代码块、桥接方法等不需要统计的底层方法
				if (name.startsWith("<") || (access & Opcodes.ACC_SYNTHETIC) != 0 || (access & Opcodes.ACC_BRIDGE) != 0) {
					return mv;
				}

				return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
					final boolean isProfiled = targetMethodNames.contains(name);
					int startTimeVar;
					int durationVar; // 用于存储计算好的耗时

					@Override
					protected void onMethodEnter() {
						if (!isProfiled) return;
						anyProfiled = true;

						// 所有的局部变量分配 (newLocal) 必须在方法入口处统一执行一次！
						startTimeVar = newLocal(Type.LONG_TYPE);
						durationVar = newLocal(Type.LONG_TYPE);

						// 记录 startTime = System.nanoTime();
						visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
						visitVarInsn(LSTORE, startTimeVar);
					}

					@Override
					protected void onMethodExit(int opcode) {
						// 如果是抛出异常退出，则不记录耗时（或者你也可以选择记录）
						if (!isProfiled || opcode == ATHROW) return;

						// 记录 duration = System.nanoTime() - startTime;
						visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
						visitVarInsn(LLOAD, startTimeVar);
						visitInsn(LSUB);

						// 【修正点】：这里直接 STORE 到刚才在 Enter 分配好的变量里，绝不能再 newLocal
						visitVarInsn(LSTORE, durationVar);

						// 提取类名简写 (例如从 mindustry/gen/Building 变成 Building)
						String simpleClassName = className.substring(className.lastIndexOf('/') + 1);
						// 推入参数 1：String methodName
						visitLdcInsn(simpleClassName + "." + name);
						// 推入参数 2：long duration
						visitVarInsn(LLOAD, durationVar);
						// 调用 ProfilerData.record(String, long)
						visitMethodInsn(INVOKESTATIC, "nipx/profiler/ProfilerData", "record", "(Ljava/lang/String;J)V", false);
					}
				};
			}
		};

		try {
			classReader.accept(cv, ClassReader.EXPAND_FRAMES);
			// 只有发生了实际注入，才返回新字节码，否则返回原始字节码节省内存
			byte[] bytes = classWriter.toByteArray();
			boolean anyProfiled = cv.anyProfiled;
			resetState();
			return anyProfiled ? bytes : classfileBuffer;
		} catch (Throwable e) {
			HotSwapAgent.error("Profiler injection failed for " + className, e);
			return null;
		}
	}
	private void resetState() {
		classReader = null;
		classWriter = null;
		targetMethodNames = null;
	}
}
