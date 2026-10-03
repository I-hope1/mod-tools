package nipx;

import nipx.annotation.*;
import nipx.ref.InitFix;
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

		byte[] bytes = classfileBuffer;  // 不clone，用引用做"是否修改"判断


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
			InitFix.beforeRedefine(classBeingRedefined);
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
		return modified ? bytes : null;
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

	public record ForceLambdaRef(MethodNode container, InvokeDynamicInsnNode indy, Handle impl, String implKey,
	                             boolean isContainerInstance) { }

	@SuppressWarnings("unchecked")
	private static List<AnnotationNode>[] prependNull(List<AnnotationNode>[] src) {
		if (src == null) return null;
		List<AnnotationNode>[] dst = new List[src.length + 1];
		System.arraycopy(src, 0, dst, 1, src.length);
		return dst;
	}

	private static void shiftLocals(MethodNode mn) {
		for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof VarInsnNode v) v.var++;
			else if (insn instanceof IincInsnNode i) i.var++;
		}
		if (mn.localVariables != null) {
			for (LocalVariableNode lvn : mn.localVariables) lvn.index++;
		}
		if (mn.parameters != null) {
			mn.parameters.add(0, new ParameterNode("this$0", ACC_SYNTHETIC | ACC_FINAL));
		}
		mn.visibleParameterAnnotations   = prependNull(mn.visibleParameterAnnotations);
		mn.invisibleParameterAnnotations = prependNull(mn.invisibleParameterAnnotations);
		if (mn.visibleAnnotableParameterCount > 0)   mn.visibleAnnotableParameterCount++;
		if (mn.invisibleAnnotableParameterCount > 0) mn.invisibleAnnotableParameterCount++;
		mn.maxLocals++;
	}

	/**
	 * <p>将当前类中所有实例 lambda 方法强制转为静态方法。<br>
	 * 同时也对定义在实例方法中的静态 lambda（即未捕获 `this` 的 lambda）进行转换，强制其捕获 `this`。
	 *
	 * <p><b>幂等性（重要）</b>：本方法对同一份输入可以安全地重复调用——已经是
	 * “静态 + this 显式首参数”形态的 lambda 会被立即识别并原样返回，不会二次前置 {@code this}。
	 * 这是因为它在两条路径上都会被调用：{@link #transform} 拦截类加载/重定义时，
	 * 以及热更调度层在送入 {@code LambdaAligner.align} 之前。两条路径必须得到<b>完全一致</b>
	 * 的基线，否则“对齐时看到的字节码”和“JVM 里实际生效的字节码”会脱节，
	 * 反而制造出新的 {@link NoSuchMethodError}。</p>
	 *
	 * @param bytes         输入字节码
	 * @param slashClassName 当前类的内部名（形如 {@code com/example/Foo}）
	 * @param targetLoader  用于计算 StackMapTable 的目标 ClassLoader（可为 {@code null}）
	 * @return 变换后的字节码；无需变换或输入不可变换时，原样返回 {@code bytes}
	 */
	public static byte[] forceStaticLambdas(byte[] bytes, String slashClassName, ClassLoader targetLoader) {
		ClassNode cn = new ClassNode(ASM9);
		try {
			new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
		} catch (Exception e) {
			return bytes;
		}

		// 1. 序列化 lambda 会按 impl 签名字符串比对，改签名会破坏反序列化
		for (MethodNode mn : cn.methods) {
			if ("$deserializeLambda$".equals(mn.name)) return bytes;
		}

		final Set<String>          instanceSyntheticMethods = new HashSet<>();
		final Set<String>          staticSyntheticMethods   = new HashSet<>();
		final Set<String>          directlyCalled           = new HashSet<>();   // 被直接 invoke 的方法
		final List<ForceLambdaRef> references               = new ArrayList<>();

		for (MethodNode mn : cn.methods) {
			if ((mn.access & ACC_SYNTHETIC) == 0) continue;
			// 统一用"未捕获 this"的规范化描述符做键，保证本方法对同一份输入幂等：
			// 若这里直接用 mn.desc，第二轮拿到的就是已被前置 this 的描述符，
			// 与 invokedynamic 侧的键对不上，护栏与决策都会失效。
			String key = mn.name + ":" + stripThisParam(mn.desc, slashClassName);
			if ((mn.access & ACC_STATIC) == 0) instanceSyntheticMethods.add(key);
			else staticSyntheticMethods.add(key);
		}

		for (MethodNode mn : cn.methods) {
			// 排除构造函数，避免在 super() 之前访问 uninitializedThis 导致 VerifyError
			boolean isInstance = (mn.access & ACC_STATIC) == 0 && !mn.name.equals("<init>");
			for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode m && m.owner.equals(slashClassName)) {
					directlyCalled.add(m.name + ":" + stripThisParam(m.desc, slashClassName));
					continue;
				}
				if (!(insn instanceof InvokeDynamicInsnNode indy)) continue;
				if (!isLambdaMetafactory(indy.bsm)) continue;
				if (indy.bsmArgs == null || indy.bsmArgs.length < 2) continue;
				if (!(indy.bsmArgs[1] instanceof Handle impl)) continue;
				if (!slashClassName.equals(impl.getOwner())) continue;

				references.add(new ForceLambdaRef(mn, indy, impl,
				 impl.getName() + ":" + stripThisParam(impl.getDesc(), slashClassName), isInstance));
			}
		}

		// 按 key 分组后统一决策
		Map<String, List<ForceLambdaRef>> byKey = new LinkedHashMap<>();
		for (ForceLambdaRef r : references) byKey.computeIfAbsent(r.implKey, k -> new ArrayList<>()).add(r);

		final Set<String> needConversionToStatic = new HashSet<>();
		final Set<String> needForceCaptureThis   = new HashSet<>();
		/** 进入转换循环之前，各 lambda 方法是否已经带有 {@code L<owner>;} 首参数（幂等护栏依据）。 */
		final Map<String, Boolean> preExistingThis = new HashMap<>();

		for (var e : byKey.entrySet()) {
			String key = e.getKey();
			List<ForceLambdaRef> refs = e.getValue();
			if (directlyCalled.contains(key)) continue;
			if (refs.get(0).impl.getName().startsWith("<")) continue;

			if (instanceSyntheticMethods.contains(key)
			    && refs.stream().allMatch(r -> {
			        int t = r.impl.getTag();
			        return t == H_INVOKESPECIAL || t == H_INVOKEVIRTUAL || t == H_INVOKEINTERFACE;
			    })) {
				needConversionToStatic.add(key);
			} else if (staticSyntheticMethods.contains(key)
			           && refs.stream().allMatch(r -> r.impl.getTag() == H_INVOKESTATIC && r.isContainerInstance)) {
				// 核心防护：必须所有引用点都在"可安全取 this"的实例方法里
				needForceCaptureThis.add(key);

				// 记录决策时刻该方法"已经带没带 this 首参数"。必须在这里（改动 mn.desc 之前）
				// 采样，因为下面的转换循环会把 mn.desc 改掉；一旦改掉，
				// "本来就有 this" 与 "刚被我们加上 this" 就再也分不出来了。
				MethodNode target = findMethodByName(cn, refs.get(0).impl.getName());
				preExistingThis.put(key, target != null && hasThisPrefix(target.desc, slashClassName));
			}
		}

		// 幂等护栏（关键）：本方法会在两条路径上被调用——transform() 拦截类加载/重定义时，
		// 以及热更调度层在送入 LambdaAligner.align 之前。若第二次调用再往首参数前插一个
		// `this`，描述符会变成 (LFoo;LFoo;...) 而 lambda 体只认原来的槽位，导致
		// “对齐时看到的字节码”与“JVM 里实际生效的字节码”脱节，也让实参与形参错位。
		//
		// 判定口径：凡是被判定"需要捕获 this"的方法，在本次进入转换循环之前就已经带上了
		// `L<owner>;` 首参数 ⇒ 本方法此前跑过 ⇒ 原样返回。
		//
		// 注意这里特意不去反推 key 的描述符：key 走的是 stripThisParam 规范化口径，
		// 拿它去拼 forcedDesc 会得到 "(Lowner;Lowner;...)" 这种畸形串（第一版实现的 bug），
		// 或者因为多轮 strip 而失去区分能力。直接检查方法自身的 desc 才是有依据的。
		if (needConversionToStatic.isEmpty()) {
			boolean alreadyForced = true;
			for (String key : needForceCaptureThis) {
				if (!Boolean.TRUE.equals(preExistingThis.get(key))) {
					alreadyForced = false;
					break;
				}
			}
			if (alreadyForced) return bytes;
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
				if (!ref.isContainerInstance) continue; // 双重防御
				ref.indy.bsmArgs[1] = handle;

				Type[] captureTypes = Type.getArgumentTypes(ref.indy.desc);
				int    captureCount = captureTypes.length;

				InsnList wrapper = new InsnList();
				if (captureCount > 0) {
					// 直接采用容器方法原始的 maxLocals 作为临时槽起点，安全不冲突
					int baseLocal = ref.container.maxLocals;

					int[] temps     = new int[captureCount];
					int   nextLocal = baseLocal;
					for (int i = 0; i < captureCount; i++) {
						temps[i] = nextLocal;
						nextLocal += captureTypes[i].getSize();
					}

					// 倒序弹出栈顶现有参数
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
	//endregion

	//region Utils
	private static boolean isLambdaMetafactory(Handle h) {
		return "java/lang/invoke/LambdaMetafactory".equals(h.getOwner())
		       && ("metafactory".equals(h.getName()) || "altMetafactory".equals(h.getName()));
	}

	/**
	 * 若描述符的首参数恰好是 {@code L<owner>;}（即本类实例，说明已被强行捕获 this），
	 * 则去掉它，返回"未捕获 this"的规范化描述符；否则原样返回。
	 *
	 * <p>用途：让 {@code forceStaticLambdas} 内部所有以"名字 + 描述符"为键的集合与查找
	 * 都使用同一个规范口径，从而保证本方法对同一份输入幂等 —— 否则第二轮读进来的
	 * 描述符已经带上了 {@code this} 参数，键对不上，幂等护栏与转换决策都会失效。</p>
	 */
	private static String stripThisParam(String desc, String slashClassName) {
		if (desc == null || desc.isEmpty() || desc.charAt(0) != '(') return desc;
		String prefix = "(L" + slashClassName + ";";
		return desc.startsWith(prefix) ? "(" + desc.substring(prefix.length()) : desc;
	}

	/** 描述符首参数是否恰好是 {@code L<owner>;}（说明已被强行捕获 this）。 */
	private static boolean hasThisPrefix(String desc, String slashClassName) {
		return desc != null && desc.startsWith("(L" + slashClassName + ";");
	}

	/** 按名字查找类中的方法（用于在改写 {@code mn.desc} 之前采样其原始形态）。 */
	private static MethodNode findMethodByName(ClassNode cn, String name) {
		for (MethodNode mn : cn.methods) {
			if (mn.name.equals(name)) return mn;
		}
		return null;
	}
	@SuppressWarnings("ResultOfMethodCallIgnored")
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
	public static String getDescriptor(Class<?> cls) {
		return Type.getDescriptor(cls);
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