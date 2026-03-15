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
		// COMPUTE_FRAMES 会调用 getCommonSuperClass 推断类型层级。
		// 默认实现用 Class.forName 加载类，在 transformer 内部触发新的类加载，
		// 可能导致 LinkageError: duplicate class definition（正在被定义的类被二次加载）。
		// 解法：完全绕开类加载，直接从 bytecodeCache 读字节码提取 superName，
		// 在 cache 中找不到时才 fallback 到 java/lang/Object。
		ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
			@Override
			protected String getCommonSuperClass(String type1, String type2) {
				// 用字节码遍历 type1 的祖先链，看是否能到达 type2（或反向）
				// 不触发任何 ClassLoader.loadClass / Class.forName
				if (type1.equals(type2)) return type1;
				if (isAssignableFromCache(type2, type1)) return type2;
				if (isAssignableFromCache(type1, type2)) return type1;
				return "java/lang/Object";
			}
			/** 判断 superCandidate 是否是 subCandidate 的祖先（从 bytecodeCache 读字节码爬继承链） */
			private boolean isAssignableFromCache(String superCandidate, String subCandidate) {
				String cur = subCandidate;
				for (int depth = 0; depth < 64; depth++) { // 最多爬 64 层，防止死循环
					if (cur == null || cur.equals("java/lang/Object")) break;
					if (cur.equals(superCandidate)) return true;
					cur = getSuperNameFromCache(cur);
				}
				return false;
			}
			/** 从 bytecodeCache 读字节码取 superName，找不到返回 null */
			private String getSuperNameFromCache(String slashName) {
				String dotName = slashName.replace('/', '.');
				byte[] cached  = bytecodeCache.get(dotName);
				if (cached == null) return null;
				try {
					return new ClassReader(cached).getSuperName();
				} catch (Throwable e) {
					return null;
				}
			}
		};

		var cv = new ClassVisitor(Opcodes.ASM9, cw) {
			boolean anyProfiled = false;

			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
			                                 String signature, String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

				// 跳过构造函数、静态代码块、桥接方法等不需要统计的底层方法
				if (name.startsWith("<") || (access & Opcodes.ACC_SYNTHETIC) != 0 || (access & Opcodes.ACC_BRIDGE) != 0) {
					return mv;
				}

				// 检查该方法是否在动态目标名单中
				boolean isTargeted = ProfilerData.isTargeted(slashClassName, name);

				return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
					boolean isProfiled = isTargeted;
					int startTimeVar;
					int durationVar; // 用于存储计算好的耗时

					@Override
					public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
						if (descriptor.equals(profileDesc)) {
							isProfiled = true;
						}
						return super.visitAnnotation(descriptor, visible);
					}

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
						String simpleClassName = slashClassName.substring(slashClassName.lastIndexOf('/') + 1);
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
			cr.accept(cv, ClassReader.EXPAND_FRAMES);
			// 只有发生了实际注入，才返回新字节码，否则返回原始字节码节省内存
			return cv.anyProfiled ? cw.toByteArray() : bytes;
		} catch (Throwable e) {
			HotSwapAgent.error("Profiler injection failed for " + slashClassName, e);
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