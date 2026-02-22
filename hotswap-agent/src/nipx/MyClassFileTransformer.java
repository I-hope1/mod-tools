package nipx;

import nipx.annotation.*;
import nipx.profiler.ProfilerData;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import static nipx.HotSwapAgent.*;
import static nipx.LambdaRef.onClassRedefined;

public class MyClassFileTransformer implements ClassFileTransformer {
	// 预先计算注解的描述符，避免重复计算
	static final String      profileDesc     = "L" + dot2slash(Profile.class) + ";";

	private static boolean hasClassAnnotation(byte[] bytes, Class<? extends Annotation> annotationClass) {
		return hasClassAnnotation(bytes, "L" + annotationClass.getName().replace('.', '/') + ";");
	}
	private static boolean hasClassAnnotation(byte[] bytes, String annotationDesc) {
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



	/** @see InstanceTracker */
	private static byte[] injectTracker(byte[] bytes, String slashClassName, ClassLoader classLoader) {
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
								// 调用静态方法: InstanceTracker.register(Object)
								// 注意：你需要确保 InstanceTracker 类在目标 ClassLoader 中可见！
								// 如果 Agent 在 BootClassLoader，而目标类在 AppClassLoader，
								// 这里可能需要反射调用，或者把 Tracker 注入到 AppClassLoader。
								// 简单起见，假设它们能互相访问。
								mv.visitMethodInsn(INVOKESTATIC,
								 dot2slash(InstanceTracker.class),
								 "register", "(Ljava/lang/Object;)V", false);
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

	/**
	 * @param slashClassName 类名，如 nipx/MyClass
	 * @return 如果没有拦截点，则返回原始 bytes，否则返回修改后的字节码
	 * @see nipx.profiler.ProfilerData
	 */
	private static byte[] injectProfiler(byte[] bytes, String slashClassName, ClassLoader targetLoader) {
		ClassReader cr = new ClassReader(bytes);

		ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

		var cv = new ClassVisitor(Opcodes.ASM9, cw) {
			boolean anyProfiled = false;
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
			                                 String signature, String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
				return

				 new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {

					 // 标志位：当前方法是否有 @Profile
					 boolean isProfiled = false;

					 @Override
					 public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
						 if (descriptor.equals(profileDesc)) {
							 anyProfiled = true;
							 isProfiled = true;
							 // info("Found @Profile on: " + dotClassName + "." + name); // 移到这里
						 }
						 return super.visitAnnotation(descriptor, visible);
					 }

					 int startTimeVar;
					 int durationVar; // 声明为类成员
					 @Override
					 protected void onMethodEnter() {
						 // 只有被打标的方法才插桩
						 if (!isProfiled) return;

						 startTimeVar = newLocal(Type.LONG_TYPE);
						 durationVar = newLocal(Type.LONG_TYPE); // 在入口处统一分配
						 // long startTime = System.nanoTime();
						 visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
						 startTimeVar = newLocal(Type.LONG_TYPE);
						 visitVarInsn(LSTORE, startTimeVar);
					 }

					 @Override
					 protected void onMethodExit(int opcode) {
						 if (!isProfiled) return;
						 if (opcode == ATHROW) return;

						 visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
						 visitVarInsn(LLOAD, startTimeVar);
						 visitInsn(LSUB);

						 visitVarInsn(LSTORE, durationVar); // 复用已分配的局部变量
						 visitLdcInsn(slashClassName + "." + name);
						 visitVarInsn(LLOAD, durationVar);
						 visitMethodInsn(INVOKESTATIC, dot2slash(ProfilerData.class), "record", "(Ljava/lang/String;J)V", false);
					 }
				 };
			}
		};

		try {
			cr.accept(cv, ClassReader.EXPAND_FRAMES);
			if (!cv.anyProfiled) return bytes;
			return cw.toByteArray();
		} catch (Throwable e) {
			return bytes;
		}
	}
	@Override
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
	                        ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (className == null) return null;
		if (loader == null) return null;
		if (className.startsWith("org/objectweb/asm/")) return null;
		if (className.startsWith("nipx/")) return null;

		String dotClassName = className.replace('/', '.');
		if (HotSwapAgent.isBlacklisted(dotClassName)) return null;
		if (classBeingRedefined != null) {
			onClassRedefined(dotClassName);
		}

		bytecodeCache.put(dotClassName, classfileBuffer);

		try {
			if (HotSwapAgent.ENABLE_HOTSWAP_EVENT) {
				byte[]  bytes    = classfileBuffer;  // 不clone，用引用做"是否修改"判断
				boolean modified = false;

				if (hasClassAnnotation(bytes, Tracker.class)) {
					bytes = injectTracker(bytes, className, loader);
					modified = true;
				}

				// injectProfiler 内部已判断是否有@Profile，只在有时才返回修改后字节码
				byte[] profiled = injectProfiler(bytes, className, loader);
				if (profiled != bytes) {  // 引用不等 → 确实被修改了
					bytes = profiled;
					modified = true;
				}

				if (HotSwapAgent.DEBUG && modified) writeTo(className, bytes);

				// info("Transformed: " + dotClassName + ":" + modified);
				return modified ? bytes : null;  // ← 关键：未修改返回null
			}
		} catch (Throwable t) {
			error("Transformer crashed for class: " + dotClassName, t);
			return null;
		}
		return null;
	}


	private static byte[] injectBuildingProfiler(byte[] bytes) {
		ClassReader cr = new ClassReader(bytes);
		// 【修改点 1】必须使用 COMPUTE_FRAMES 来自动重新计算 StackMapTable
		ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);

		ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
			                                 String signature, String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

				if ("updateTile".equals(name) && "()V".equals(descriptor)) {
					return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
						int startTimeVar;

						@Override
						protected void onMethodEnter() {
							// 插入 nanoTime()
							visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
							startTimeVar = newLocal(Type.LONG_TYPE);
							visitVarInsn(LSTORE, startTimeVar);
						}

						@Override
						protected void onMethodExit(int opcode) {
							if (opcode != ATHROW) {
								// 1. 获取当前时间并计算耗时
								visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
								visitVarInsn(LLOAD, startTimeVar);
								visitInsn(LSUB);
								int durationVar = newLocal(Type.LONG_TYPE);
								visitVarInsn(LSTORE, durationVar);

								// 2. 准备参数调用 recordBuilding(Object obj, long duration)
								visitVarInsn(ALOAD, 0);      // 加载 this (Object类型)
								visitVarInsn(LLOAD, durationVar); // 加载 duration

								// 调用我们刚才定义的 Java 辅助方法
								visitMethodInsn(INVOKESTATIC, "nipx/profiler/ProfilerData",
								 "recordBuilding", "(Ljava/lang/Object;J)V", false);
							}
						}
					};
				}
				return mv;
			}
		};
		cr.accept(cv, ClassReader.EXPAND_FRAMES);
		return cw.toByteArray();
	}
	// --------------


	private static void writeTo(String className, byte[] classfileBuffer) {
		File file = new File("./classes/" + className + ".class");
		file.getParentFile().mkdirs();
		try (FileOutputStream fos = new FileOutputStream(file)) {
			fos.write(classfileBuffer);
		} catch (IOException e) {
			error("Failed to write bytes", e);
		}
	}

	static String dot2slash(String className) {
		return className.replace('.', '/');
	}
	static String dot2slash(Class<?> clazz) {
		return clazz.getName().replace('.', '/');
	}

}
