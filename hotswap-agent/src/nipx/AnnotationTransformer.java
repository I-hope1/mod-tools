package nipx;

import nipx.annotation.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static nipx.HotSwapAgent.*;
import static nipx.LambdaRef.onClassRedefined;

/**
 * <p>用于注解，注入代码
 * <p>同时也用于获取bytecode，存入缓存
 * @see Tracker
 * @see Profile
 * @see OnReload
 */
public class AnnotationTransformer implements ClassFileTransformer {
	// 预先计算注解的描述符，避免重复计算
	static final String profileDesc = "L" + dot2slash(Profile.class) + ";";

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
		ClassWriter cw = new MyClassWriter(cr, targetLoader);

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

				return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
					boolean isProfiled = false;
					int     startTimeVar;
					int     durationVar; // 用于存储计算好的耗时

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

		// 注册到继承树
		HierarchyTree.register(classfileBuffer); // TODO: 如果父类是系统类，可能会出错

		String dotClassName = className.replace('/', '.');
		if (HotSwapAgent.isBlacklisted(dotClassName)) return null;
		if (classBeingRedefined != null) {
			onClassRedefined(dotClassName);
		}

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

	public static String dot2slash(String className) {
		return className.replace('.', '/');
	}
	public static String dot2slash(Class<?> clazz) {
		return clazz.getName().replace('.', '/');
	}


	/**
	 * 类层级缓存树
	 * 用于在不触发 ClassLoader.loadClass 的前提下，判断类的继承与实现关系
	 */
	public static class HierarchyTree {
		private static final ConcurrentHashMap<String, ClassNode> tree = new ConcurrentHashMap<>();

		static class ClassNode {
			String   superName;
			String[] interfaces;
			boolean  isInterface;

			ClassNode(String superName, String[] interfaces, boolean isInterface) {
				this.superName = superName;
				this.interfaces = interfaces;
				this.isInterface = isInterface;
			}
		}

		/** 提取并注册类的继承信息 */
		public static void register(byte[] classfileBuffer) {
			try {
				ClassReader cr          = new ClassReader(classfileBuffer);
				String      className   = cr.getClassName();
				String      superName   = cr.getSuperName();
				String[]    interfaces  = cr.getInterfaces();
				boolean     isInterface = (cr.getAccess() & Opcodes.ACC_INTERFACE) != 0;

				tree.put(className, new ClassNode(superName, interfaces, isInterface));
			} catch (Exception ignored) {
				// 容错处理
			}
		}

		/**
		 * 核心逻辑：判断 subType 是否是 superType 的子类或实现类
		 * 采用 BFS (广度优先搜索) 遍历继承树
		 */
		public static boolean isAssignableFrom(String superType, String subType, ClassLoader loader) {
			if (superType.equals(subType) || "java/lang/Object".equals(superType)) {
				return true;
			}

			Queue<String> queue   = new LinkedList<>();
			Set<String>   visited = new HashSet<>();
			queue.add(subType);
			visited.add(subType);

			while (!queue.isEmpty()) {
				String    current = queue.poll();
				ClassNode node    = getNode(current, loader);

				if (node == null) continue;

				// 检查父类
				if (node.superName != null) {
					if (node.superName.equals(superType)) return true;
					if (visited.add(node.superName)) {
						queue.add(node.superName);
					}
				}

				// 检查接口
				if (node.interfaces != null) {
					for (String itf : node.interfaces) {
						if (itf.equals(superType)) return true;
						if (visited.add(itf)) {
							queue.add(itf);
						}
					}
				}
			}
			return false;
		}

		/**
		 * 获取类节点，如果缓存中没有，尝试从目标 ClassLoader 以资源流的方式读取，
		 * 坚决不使用 Class.forName！
		 */
		private static ClassNode getNode(String slashName, ClassLoader loader) {
			ClassNode node = tree.get(slashName);
			if (node != null) return node;

			// 尝试从缓存获取 (兼容你原有的 bytecodeCache)
			String dotName     = slashName.replace('/', '.');
			byte[] cachedBytes = bytecodeCache.get(dotName);
			if (cachedBytes != null) {
				register(cachedBytes);
				return tree.get(slashName);
			}

			// 兜底：作为资源读取，不触发类加载
			if (loader == null) loader = ClassLoader.getSystemClassLoader();
			try (InputStream is = loader.getResourceAsStream(slashName + ".class")) {
				if (is != null) {
					byte[] bytes = is.readAllBytes();
					register(bytes);
					return tree.get(slashName);
				}
			} catch (Exception ignored) { }

			return null;
		}

		public static boolean isInterface(String slashName, ClassLoader loader) {
			ClassNode node = getNode(slashName, loader);
			return node != null && node.isInterface;
		}
	}
	public static class MyClassWriter extends ClassWriter {
		private final ClassLoader targetLoader;
		public MyClassWriter(ClassReader cr, ClassLoader targetLoader) {
			super(cr, ClassWriter.COMPUTE_FRAMES);
			this.targetLoader = targetLoader;
		}
		@Override
		protected String getCommonSuperClass(String type1, String type2) {
			if (HierarchyTree.isInterface(type1, targetLoader) || HierarchyTree.isInterface(type2, targetLoader)) {
				return "java/lang/Object";
			}
			if (HierarchyTree.isAssignableFrom(type1, type2, targetLoader)) {
				return type1;
			}
			if (HierarchyTree.isAssignableFrom(type2, type1, targetLoader)) {
				return type2;
			}
			// 向上寻找 type1 的父类，直到找到也是 type2 父类的类
			String type1Super = type1;
			do {
				HierarchyTree.ClassNode node = HierarchyTree.getNode(type1Super, targetLoader);
				if (node == null || node.superName == null) {
					return "java/lang/Object";
				}
				type1Super = node.superName;
			} while (!HierarchyTree.isAssignableFrom(type1Super, type2, targetLoader));

			return type1Super;
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
	}
}