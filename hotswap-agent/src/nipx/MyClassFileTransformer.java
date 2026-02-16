package nipx;

import nipx.annotation.*;
import nipx.profiler.ProfilerData;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.instrument.ClassFileTransformer;
import java.lang.ref.SoftReference;
import java.security.ProtectionDomain;

import static nipx.HotSwapAgent.*;

public class MyClassFileTransformer implements ClassFileTransformer {
	// 预先计算注解的描述符，避免重复计算
	public static final String profileDesc = "L" + dot2slash(Profile.class) + ";";

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
	 * @param dotClassName 类名，如 nipx.MyClass，用于print
	 * @see nipx.profiler.ProfilerData
	 */
	private static byte[] injectProfiler(byte[] bytes, String dotClassName) {
		ClassReader cr = new ClassReader(bytes);
		ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

		ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
			                                 String signature, String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

				// 使用 AdviceAdapter 既能访问注解，也能插入代码
				return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {

					// 标志位：当前方法是否有 @Profile
					boolean isProfiled = false;
					int     startTimeVar;

					@Override
					public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
						if (descriptor.equals(profileDesc)) {
							isProfiled = true;
							// info("Found @Profile on: " + dotClassName + "." + name); // 移到这里
						}
						return super.visitAnnotation(descriptor, visible);
					}

					@Override
					protected void onMethodEnter() {
						// 只有被打标的方法才插桩
						if (!isProfiled) return;

						// long startTime = System.nanoTime();
						visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
						startTimeVar = newLocal(Type.LONG_TYPE);
						visitVarInsn(LSTORE, startTimeVar);
					}

					@Override
					protected void onMethodExit(int opcode) {
						if (!isProfiled) return;
						// if (opcode == ATHROW) return; // 可选：不统计抛异常的情况

						// long duration = System.nanoTime() - startTime;
						visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
						visitVarInsn(LLOAD, startTimeVar);
						visitInsn(LSUB);
						// 栈顶现在是: [long: duration]

						// 【核心修复】修复 SWAP 导致的 VerifyError
						// 此时不能直接 SWAP。最简单的做法是：
						// 把 duration 再存到一个临时变量，或者直接按参数顺序加载

						// 方案：因为 duration 已经在栈顶了，ProfilerData.record(String, long)
						// 我们需要 String 在下面，long 在上面。
						// 但 duration 是计算出来的，必须在上面。
						// 所以：先 LSTORE 到临时变量 -> LDC String -> LLOAD 临时变量

						int durationVar = newLocal(Type.LONG_TYPE);
						visitVarInsn(LSTORE, durationVar); // 存入临时变量

						// 准备参数 1: String name
						visitLdcInsn(dotClassName + "." + name);

						// 准备参数 2: long duration
						visitVarInsn(LLOAD, durationVar);

						// 调用: ProfilerData.record(String, long)
						visitMethodInsn(INVOKESTATIC,
						 dot2slash(ProfilerData.class),
						 "record", "(Ljava/lang/String;J)V", false);
					}
				};
			}
		};

		cr.accept(cv, ClassReader.EXPAND_FRAMES); // 建议加上 EXPAND_FRAMES
		return cw.toByteArray();
	}
	@Override
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
	                        ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (className == null) return null;
		// 忽略 bootclassloader
		if (loader == null) return null;
		if (className.startsWith("org/objectweb/asm/")) return null;
		if (className.startsWith("nipx/")) return null;

		String dotClassName = className.replace('/', '.');
		if (HotSwapAgent.isBlacklisted(dotClassName)) return null;

		bytecodeCache.put(dotClassName, new SoftReference<>(classfileBuffer));
		// info("transform: " + dotClassName + " ' blacklisted " + HotSwapAgent.isBlacklisted(dotClassName));

		try {
			if (HotSwapAgent.ENABLE_HOTSWAP_EVENT) {

				if (HotSwapAgent.DEBUG) writeTo(className, classfileBuffer);

				byte[] bytes = classfileBuffer.clone();

				// 处理 @Reloadable (类级别)
				if (hasClassAnnotation(bytes, Reloadable.class)) {
					bytes = injectTracker(bytes, dotClassName); // 你的 injectTracker 保持原样即可
				}

				// 处理 @Profile (方法级别)
				bytes = injectProfiler(bytes, dotClassName);
				// bytes = injectBuildingProfiler(bytes);

				return bytes;
			}
		} catch (Throwable t) {
			// ！！！这里是关键，找出凶手！！！
			error("Transformer crashed for class: " + dotClassName, t);
			return null; // 返回 null 放弃修改，保证应用不崩，但修改不生效
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
		File file = new File("F:/classes/" + className + ".class");
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
