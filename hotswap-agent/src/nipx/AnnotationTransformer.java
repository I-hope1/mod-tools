package nipx;

import arc.Core;
import nipx.annotation.*;
import nipx.ref.InitFix;
import nipx.uihook.CellPropertyRef;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static nipx.HotSwapAgent.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * <p>用于注解，注入代码
 * <p>同时也用于获取bytecode，存入缓存
 * @see Tracker
 * @see Profile
 * @see OnReload
 */
public class AnnotationTransformer implements ClassFileTransformer {

	//region Fields and Annotation Utilities
	static final String profileDesc = "L" + internalName(Profile.class) + ";";

	private static boolean hasClassAnnotation(byte[] bytes, Class<? extends Annotation> annotationClass) {
		return hasClassAnnotation(bytes, "L" + annotationClass.getName().replace('.', '/') + ";");
	}
	private static boolean hasClassAnnotation(byte[] bytes, String annotationDesc) {
		final boolean[] found = {false};
		new ClassReader(bytes).accept(new ClassVisitor(ASM9) {
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
	//endregion

	//region ClassFileTransformer Core
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

		byte[]  bytes    = classfileBuffer;  // 不clone，用引用做"是否修改"判断
		boolean modified = false;
		if (HOTSWAP_PLUS) {
			classfileBuffer = forceStaticLambdas(classfileBuffer, className, loader);
			if (classfileBuffer != bytes) {
				bytes = classfileBuffer;
				modified = true;
			}
		}
		if (classBeingRedefined != null) {
			LambdaRef.beforeClassRedefined(className, classfileBuffer);
			byte[] finalClassfileBuffer = classfileBuffer;
			if (CellPropertyRef.isEnabled()) {
				Core.app.post(() -> CellPropertyRef.afterRedefined(className, finalClassfileBuffer));
			}
			Core.app.post(() -> InitFix.afterRedefined(classBeingRedefined, finalClassfileBuffer));
		}

		try {
			if (ENABLE_HOTSWAP_EVENT) {
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

				if (DEBUG && modified) writeTo(className, bytes);

				// info("Transformed: " + dotClassName + ":" + modified);
				return modified ? bytes : null;
			}
		} catch (Throwable t) {
			error("Transformer crashed for class: " + dotClassName, t);
			return null;
		}
		return null;
	}
	//endregion

	//region Bytecode Injection - Instance Tracker
	/** @see InstanceTracker */
	private static byte[] injectTracker(byte[] bytes, String slashClassName, ClassLoader classLoader) {
		ClassReader cr = new ClassReader(bytes);
		ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

		ClassVisitor cv = new ClassVisitor(ASM9, cw) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
			                                 String signature, String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
				// 只拦截构造函数 <init>
				if ("<init>".equals(name)) {
					return new AdviceAdapter(ASM9, mv, access, name, descriptor) {
						@Override
						protected void onMethodExit(int opcode) {
							// 在构造函数返回之前 (RETURN 之前) 插入代码
							if (opcode != ATHROW) {
								mv.visitVarInsn(ALOAD, 0); // this
								// InstanceTracker.register(Object)
								mv.visitMethodInsn(INVOKESTATIC,
								 internalName(InstanceTracker.class),
								 "register", "(Ljava/lang/Object;)V", false);
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
	//endregion

	//region Bytecode Injection - Profiler
	/**
	 * <p>注入代码到类中，添加方法调用
	 * <p>跳过构造函数，静态代码块，桥接方法，以及无注解的方法
	 * @param slashClassName 类名，如 nipx/MyClass
	 * @return 如果没有拦截点，则返回原始 bytes，否则返回修改后的字节码
	 * @see nipx.profiler.ProfilerData
	 */
	private static byte[] injectProfiler(byte[] bytes, String slashClassName, ClassLoader targetLoader) {
		ClassReader cr = new ClassReader(bytes);
		ClassWriter cw = new MyClassWriter(cr, targetLoader);

		var cv = new ClassVisitor(ASM9, cw) {
			boolean anyProfiled = false;

			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
			                                 String signature, String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

				if (name.startsWith("<") || (access & ACC_SYNTHETIC) != 0 || (access & ACC_BRIDGE) != 0) {
					return mv;
				}

				return new AdviceAdapter(ASM9, mv, access, name, descriptor) {
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

						// 直接 STORE 到刚才在 Enter 分配好的变量里，不再 newLocal
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

	private static byte[] injectBuildingProfiler(byte[] bytes) {
		ClassReader cr = new ClassReader(bytes);
		// 使用 COMPUTE_FRAMES 来自动重新计算 StackMapTable
		ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);

		ClassVisitor cv = new ClassVisitor(ASM9, cw) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
			                                 String signature, String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

				if ("updateTile".equals(name) && "()V".equals(descriptor)) {
					return new AdviceAdapter(ASM9, mv, access, name, descriptor) {
						int startTimeVar;

						@Override
						protected void onMethodEnter() {
							// System.nanoTime()
							visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
							startTimeVar = newLocal(Type.LONG_TYPE);
							// 存入startTime
							visitVarInsn(LSTORE, startTimeVar);
						}

						@Override
						protected void onMethodExit(int opcode) {
							if (opcode != ATHROW) {
								// 获取当前时间并计算耗时
								visitMethodInsn(INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
								visitVarInsn(LLOAD, startTimeVar);
								visitInsn(LSUB);
								int durationVar = newLocal(Type.LONG_TYPE);
								visitVarInsn(LSTORE, durationVar);

								// recordBuilding(Object obj, long duration)
								visitVarInsn(ALOAD, 0); // this
								visitVarInsn(LLOAD, durationVar); // duration
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
	//endregion

	//region Lambda Transformations

	private record ForceLambdaRef(MethodNode container, InvokeDynamicInsnNode indy, Handle impl, String implKey,
	                              boolean isContainerInstance) { }

	private static void shiftLocals(MethodNode mn) {
		for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof VarInsnNode varInsn) {
				varInsn.var += 1;
			} else if (insn instanceof IincInsnNode iincInsn) {
				iincInsn.var += 1;
			}
		}
		if (mn.localVariables != null) {
			for (LocalVariableNode lvn : mn.localVariables) {
				lvn.index += 1;
			}
		}
		mn.maxLocals += 1;
	}

	/**
	 * <p>将当前类中所有实例 lambda 方法强制转为静态方法。<br>
	 * 同时也对定义在实例方法中的静态 lambda（即未捕获 `this` 的 lambda）进行转换，强制其捕获 `this`。
	 */
	private static byte[] forceStaticLambdas(byte[] bytes, String slashClassName, ClassLoader targetLoader) {
		ClassNode cn = new ClassNode(ASM9);
		try {
			new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
		} catch (Exception e) {
			return bytes;
		}

		final Set<String>          instanceSyntheticMethods = new HashSet<>();
		final Set<String>          staticSyntheticMethods   = new HashSet<>();
		final List<ForceLambdaRef> references               = new ArrayList<>();

		// 收集所有合成（Lambda）方法
		for (MethodNode mn : cn.methods) {
			boolean isInstance = (mn.access & ACC_STATIC) == 0;
			if ((mn.access & ACC_SYNTHETIC) != 0) {
				if (isInstance) {
					instanceSyntheticMethods.add(mn.name + ":" + mn.desc);
				} else {
					staticSyntheticMethods.add(mn.name + ":" + mn.desc);
				}
			}
		}

		// 收集所有 invokedynamic 调用关系
		for (MethodNode mn : cn.methods) {
			if (mn.instructions == null) continue;
			// 构造函数在调用 super() / this() 之前 this 是 uninitializedThis 状态，不能强制捕获，否则会触发 VerifyError
			boolean isInstance = (mn.access & ACC_STATIC) == 0 && !mn.name.equals("<init>");
			for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn.getType() != AbstractInsnNode.INVOKE_DYNAMIC_INSN) continue;
				InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
				if (!isLambdaMetafactory(indy.bsm)) continue;
				if (indy.bsmArgs == null || indy.bsmArgs.length < 2) continue;
				if (!(indy.bsmArgs[1] instanceof Handle impl)) continue;
				if (!slashClassName.equals(impl.getOwner())) continue;

				String key = impl.getName() + ":" + impl.getDesc();
				references.add(new ForceLambdaRef(mn, indy, impl, key, isInstance));
			}
		}

		final Set<String> needConversionToStatic = new HashSet<>();
		final Set<String> needForceCaptureThis   = new HashSet<>();

		for (ForceLambdaRef ref : references) {
			if (instanceSyntheticMethods.contains(ref.implKey)) {
				needConversionToStatic.add(ref.implKey);
			} else if (staticSyntheticMethods.contains(ref.implKey) && ref.isContainerInstance) {
				needForceCaptureThis.add(ref.implKey);
			}
		}

		if (needConversionToStatic.isEmpty() && needForceCaptureThis.isEmpty()) {
			return bytes;
		}

		// 修改目标 lambda 方法定义
		for (MethodNode mn : cn.methods) {
			String key = mn.name + ":" + mn.desc;
			if (needConversionToStatic.contains(key)) {
				mn.access |= ACC_STATIC;
				mn.desc = "(" + "L" + slashClassName + ";" + mn.desc.substring(1);
			} else if (needForceCaptureThis.contains(key)) {
				// 修改目标签名，使其接受 this 作为首个显式参数
				mn.desc = "(" + "L" + slashClassName + ";" + mn.desc.substring(1);
				// 静态方法新增参数，必须将其方法体内的局部变量统一后移 1 槽
				shiftLocals(mn);
			}
		}

		// 修改 invokedynamic 站点和入栈代码
		for (ForceLambdaRef ref : references) {
			String newDesc = "(L" + slashClassName + ";" + ref.impl.getDesc().substring(1);
			Handle handle = new Handle(
			 H_INVOKESTATIC,
			 ref.impl.getOwner(),
			 ref.impl.getName(),
			 newDesc,
			 ref.impl.isInterface()
			);
			if (needConversionToStatic.contains(ref.implKey)) {
				ref.indy.bsmArgs[1] = handle;
			} else if (needForceCaptureThis.contains(ref.implKey)) {
				ref.indy.bsmArgs[1] = handle;

				Type[] captureTypes = Type.getArgumentTypes(ref.indy.desc);
				int    captureCount = captureTypes.length;

				InsnList wrapper = new InsnList();
				if (captureCount > 0) {
					// 寻找可用 LVT 偏移量以避免临时变量冲突
					int baseLocal = getBaseLocal(ref);

					// 将原有已在栈上的捕获变量按原类型大小保存在临时局部变量槽中
					int[] temps     = new int[captureCount];
					int   nextLocal = baseLocal;
					for (int i = 0; i < captureCount; i++) {
						temps[i] = nextLocal;
						nextLocal += captureTypes[i].getSize();
					}

					// 倒序弹出
					for (int i = captureCount - 1; i >= 0; i--) {
						Type ct = captureTypes[i];
						wrapper.add(new VarInsnNode(ct.getOpcode(ISTORE), temps[i]));
					}

					// 先将 `this` 压入操作数栈底部
					wrapper.add(new VarInsnNode(ALOAD, 0));

					// 再将原捕获变量依次重新压回栈中
					for (int i = 0; i < captureCount; i++) {
						Type ct = captureTypes[i];
						wrapper.add(new VarInsnNode(ct.getOpcode(ILOAD), temps[i]));
					}
				} else {
					// 没有其他捕获变量时，直接压入 `this`
					wrapper.add(new VarInsnNode(ALOAD, 0));
				}

				ref.container.instructions.insertBefore(ref.indy, wrapper);
				// 更新 invokedynamic 描述符，增加 LClass; 类型的捕获声明
				ref.indy.desc = "(" + "L" + slashClassName + ";" + ref.indy.desc.substring(1);
			}
		}

		MyClassWriter cw = new MyClassWriter(targetLoader, ClassWriter.COMPUTE_FRAMES);
		try {
			cn.accept(cw);
			return cw.toByteArray();
		} catch (Exception e) {
			HotSwapAgent.error("forceStaticLambdas failed for " + slashClassName, e);
			return bytes;
		}
	}

	private static int getBaseLocal(ForceLambdaRef ref) {
		int baseLocal = 0;
		if (ref.container.instructions != null) {
			for (AbstractInsnNode ain = ref.container.instructions.getFirst(); ain != null; ain = ain.getNext()) {
				if (ain instanceof VarInsnNode v) {
					int size = (v.getOpcode() == LLOAD || v.getOpcode() == LSTORE ||
					            v.getOpcode() == DLOAD || v.getOpcode() == DSTORE) ? 2 : 1;
					baseLocal = Math.max(baseLocal, v.var + size);
				}
			}
		}
		if (ref.container.localVariables != null) {
			for (LocalVariableNode lvn : ref.container.localVariables) {
				int size = ("J".equals(lvn.desc) || "D".equals(lvn.desc)) ? 2 : 1;
				baseLocal = Math.max(baseLocal, lvn.index + size);
			}
		}
		return baseLocal;
	}

	private static boolean isLambdaMetafactory(Handle h) {
		return "java/lang/invoke/LambdaMetafactory".equals(h.getOwner())
		       && ("metafactory".equals(h.getName()) || "altMetafactory".equals(h.getName()));
	}
	private static void writeTo(String className, byte[] classfileBuffer) {
		File file = new File("./classes/" + className + ".class");
		file.getParentFile().mkdirs();
		try (FileOutputStream fos = new FileOutputStream(file)) {
			fos.write(classfileBuffer);
		} catch (IOException e) {
			error("Failed to write bytes", e);
		}
	}

	public static String dot2slash(String dotClassName) {
		return dotClassName.replace('.', '/');
	}
	public static String internalName(Class<?> clazz) {
		return clazz.getName().replace('.', '/');
	}
	public static String typeToNative(Class<?> cls) {
		if (cls.isArray()) return "[" + typeToNative(cls.getComponentType());
		if (cls == int.class) return "I";
		if (cls == long.class) return "J";
		if (cls == float.class) return "F";
		if (cls == double.class) return "D";
		if (cls == char.class) return "C";
		if (cls == short.class) return "S";
		if (cls == byte.class) return "B";
		if (cls == boolean.class) return "Z";
		if (cls == void.class) return "V";

		return "L" + internalName(cls) + ";";
	}
	//endregion

	//region Hierarchy Tree
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

		public static void register(byte[] classfileBuffer) {
			try {
				ClassReader cr          = new ClassReader(classfileBuffer);
				String      className   = cr.getClassName();
				String      superName   = cr.getSuperName();
				String[]    interfaces  = cr.getInterfaces();
				boolean     isInterface = (cr.getAccess() & ACC_INTERFACE) != 0;

				tree.put(className, new ClassNode(superName, interfaces, isInterface));
			} catch (Exception ignored) {
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

			// 尝试从缓存获取 (兼容原有的 bytecodeCache)
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
	//endregion

	//region Custom ClassWriter
	/**
	 * <p>COMPUTE_FRAMES 会调用 getCommonSuperClass 推断类型层级。<br>
	 * 默认实现用 Class.forName 加载类，在 transformer 内部触发新的类加载，<br>
	 * 可能导致 LinkageError: duplicate class definition（正在被定义的类被二次加载）。<br>
	 * 该类完全绕开类加载，直接从 bytecodeCache 读字节码提取 superName，<br>
	 * 在 cache 中找不到时才 fallback 到 java/lang/Object。
	 **/
	public static class MyClassWriter extends ClassWriter {
		private final ClassLoader targetLoader;
		public MyClassWriter(ClassReader cr, ClassLoader targetLoader) {
			super(cr, ClassWriter.COMPUTE_FRAMES);
			this.targetLoader = targetLoader;
		}
		public MyClassWriter(ClassLoader targetLoader, int flags) {
			super(flags);
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
	}
	//endregion
}