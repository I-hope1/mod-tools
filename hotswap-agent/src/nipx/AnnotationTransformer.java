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

	/**
	 * 对齐后待注入的类字节码缓存（类内部名 / 点分名 -> 对齐后的字节码）。
	 * 用于在未加载类首次被 JVM ClassLoader 加载时（classBeingRedefined == null），
	 * 拦截并返回对齐后的字节码，防止类加载器从磁盘读取未对齐的原始编号产物。
	 */
	public static final Map<String, byte[]> pendingAlignedClasses = new ConcurrentHashMap<>();

	//region ClassFileTransformer Core
	@Override
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
	                        ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (className == null) return null;

		if (loader == null) return null;
		if (className.startsWith("org/objectweb/asm/")) return null;
		if (className.startsWith("nipx/")) return null;

		// 注册到继承树
		try {
			HierarchyTree.register(classfileBuffer); // TODO: 如果父类是系统类，可能会出错
		} catch (Throwable ignored) {
		}

		String dotClassName = className.replace('/', '.');
		if (HotSwapAgent.isBlacklisted(dotClassName)) return null;

		boolean modified = false;

		// 加载期拦截：若属于已对齐但尚未加载的类，优先使用对齐后的字节码
		byte[] pending = null;
		if (classBeingRedefined == null) {
			pending = pendingAlignedClasses.remove(className);
			if (pending == null) {
				pending = pendingAlignedClasses.remove(dotClassName);
			} else {
				pendingAlignedClasses.remove(dotClassName);
			}
			if (pending != null) {
				classfileBuffer = pending;
				modified = true;
			}
		}

		byte[] bytes = classfileBuffer;  // 不clone，用引用做"是否修改"判断

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

				byte[] resultBytes = modified ? bytes : classfileBuffer;
				if (classBeingRedefined == null && pending != null) {
					bytecodeCache.put(dotClassName, resultBytes);
				}
				return modified ? bytes : null;
			}
		} catch (Throwable t) {
			error("Transformer crashed for class: " + dotClassName, t);
			return null;
		}

		byte[] resultBytes = modified ? bytes : classfileBuffer;
		if (classBeingRedefined == null && pending != null) {
			bytecodeCache.put(dotClassName, resultBytes);
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

		// 0. 幂等标记：本方法必须能被安全地重复调用。
		//
		// 为什么不能靠"描述符形状"推断是否已处理过：强转本身就是在首参数前插一个
		// `L<owner>;`，而 lambda 完全可能**本来就有**一个类型为 owner 的显式首参数
		// （例如 `void observe(Foo other) { run(() -> this.y + other.x); }` 编译出
		// `lambda$observe$0(LFoo;)V`）。这两种形态在字节码里一模一样，任何基于形状的
		// 判断都会把"本来就长这样"误判成"已经转换过"，或者反过来把真参数当成幻影 this
		// 剥掉，从而产出指向不存在方法的 indy（BootstrapMethodError）。
		//
		// 也试过用"传入的 byte[] 实例身份"做标记：实测 redefineClasses 会让 transformer
		// 收到字节码的**副本**（同一实例假设不成立），故不可用。
		//
		// 因此改用显式标记字段：它跟着字节码走，不依赖任何推断，且对所有调用路径统一生效。
		if (hasForcedMarker(cn)) return bytes;

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
			// 一律使用"方法名 + 该类里真实的原始描述符"作为键。
			//
			// 曾有版本在这里把首参数（若等于 owner 类型）剥掉，想用它消除"幻影 this"带来的
			// 形态差异。那是错的：剥掉之后键就不再唯一对应一个方法，一个**本来就带**
			// `L<owner>;` 显式首参数的真 lambda 会被误剥。例如
			//   void observe(Foo other) { run(() -> this.y + other.x); }   ->  lambda$observe$0(LFoo;)V
			// 决策时键变成 `lambda$observe$0:()V`，而下面改写句柄用的是 impl 的原始描述符，
			// 结果句柄被改成 `(LFoo;LFoo;)V` 而方法定义仍是 `(LFoo;)V` —— 链接期直接
			// BootstrapMethodError，恰是本方案要消除的错误。幂等性现在由标记字段负责，
			// 键不需要再承担这个职责。
			String key = mn.name + ":" + mn.desc;
			if ((mn.access & ACC_STATIC) == 0) instanceSyntheticMethods.add(key);
			else staticSyntheticMethods.add(key);
		}

		for (MethodNode mn : cn.methods) {
			// 排除构造函数，避免在 super() 之前访问 uninitializedThis 导致 VerifyError
			boolean isInstance = (mn.access & ACC_STATIC) == 0 && !mn.name.equals("<init>");
			for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode m && m.owner.equals(slashClassName)) {
					directlyCalled.add(m.name + ":" + m.desc);
					continue;
				}
				if (!(insn instanceof InvokeDynamicInsnNode indy)) continue;
				if (!isLambdaMetafactory(indy.bsm)) continue;
				if (indy.bsmArgs == null || indy.bsmArgs.length < 2) continue;
				if (!(indy.bsmArgs[1] instanceof Handle impl)) continue;
				if (!slashClassName.equals(impl.getOwner())) continue;

				references.add(new ForceLambdaRef(mn, indy, impl,
				 impl.getName() + ":" + impl.getDesc(), isInstance));
			}
		}

		// 按 key 分组后统一决策
		Map<String, List<ForceLambdaRef>> byKey = new LinkedHashMap<>();
		for (ForceLambdaRef r : references) byKey.computeIfAbsent(r.implKey, k -> new ArrayList<>()).add(r);

		final Set<String> needConversionToStatic = new HashSet<>();
		final Set<String> needForceCaptureThis   = new HashSet<>();

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
			}
		}

		if (needConversionToStatic.isEmpty() && needForceCaptureThis.isEmpty()) {
			return bytes;
		}

		// 修改目标 lambda 方法定义。
		//
		// 注意查找方式：不再拿 key 去拼期望描述符（那正是上面注释里那类 bug 的来源），
		// 而是拿"该类里真实声明的名字 + 原始描述符"直接定位。这样：
		//   • 它一定命中真实存在的方法，不可能拼出一个不存在的方法；
		//   • 是否已经前置过 this，用"当前描述符是否等于原始描述符"判断 —— 这是无歧义的，
		//     因为原始描述符就是从这颗 ClassNode 上读出来的。标记字段是第一重幂等保证，
		//     这里是第二重。
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

		// 幂等护栏（第二重，第一重是入口处的标记字段）：方法定义刚刚才改过 desc，
		// 所以这里不可能再重复前置 this —— 上面的判断用的是"当前描述符 == 原始描述符"。
		// 保留 needConversionToStatic.isEmpty() && needForceCaptureThis.isEmpty() 的提前返回，
		// 让"无需转换"的类不产生任何字段/标记副作用。

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

		// 打上幂等标记：务必在真正改写之后、写盘之前添加，
		// 这样"本轮什么都没改"的类不会平白多出一个字段。
		addForcedMarker(cn);

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

	/**
	 * 幂等标记字段名。带 {@code $nipx$} 前缀是为了避开与业务字段重名的可能。
	 * <p>声明为 {@code private static final synthetic} 且<b>不</b>赋值：{@code InitFix}
	 * 对"在 {@code <clinit>} 里找不到安全初始化表达式"的新增字段一律弃权（见
	 * {@code InitFix.buildPatch}），因此不会产生初始化代码；它也不参与任何语义。
	 * 之所以不用自定义 class attribute：那需要额外的读写与兼容处理，而字段在所有
	 * ASM 路径上天然可见，判断只需一次遍历。</p>
	 */
	private static final String FORCED_MARKER = "$nipx$lambdasForced";

	/** 判断该类是否已被 {@link #forceStaticLambdas} 处理过。 */
	private static boolean hasForcedMarker(ClassNode cn) {
		for (FieldNode f : cn.fields) {
			if (FORCED_MARKER.equals(f.name)) return true;
		}
		return false;
	}

	/** 给类打上幂等标记（已存在则不重复添加）。 */
	private static void addForcedMarker(ClassNode cn) {
		if (hasForcedMarker(cn)) return;
		// JVMS 4.5：接口字段必须是 public static final，private 会 ClassFormatError (0x101A)
    int access = (cn.access & ACC_INTERFACE) != 0
        ? ACC_PUBLIC  | ACC_STATIC | ACC_FINAL | ACC_SYNTHETIC   // 0x1019
        : ACC_PRIVATE | ACC_STATIC | ACC_FINAL | ACC_SYNTHETIC;  // 0x101A
		cn.fields.add(new FieldNode(
			access, FORCED_MARKER, "Z", null, null));
	}

	private static boolean isLambdaMetafactory(Handle h) {
		return "java/lang/invoke/LambdaMetafactory".equals(h.getOwner())
		       && ("metafactory".equals(h.getName()) || "altMetafactory".equals(h.getName()));
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
		private static final ConcurrentHashMap<String, HierarchyNode> tree = new ConcurrentHashMap<>();

		private static final class HierarchyNode {
			final String superName;
			final String[] interfaces;
			final int access;

			HierarchyNode(String superName, String[] interfaces, int access) {
				this.superName = superName;
				this.interfaces = interfaces;
				this.access = access;
			}
		}

		public static void register(byte[] classfileBuffer) {
			try {
				ClassReader reader = new ClassReader(classfileBuffer);
				tree.put(reader.getClassName(), new HierarchyNode(
					reader.getSuperName(), reader.getInterfaces(), reader.getAccess()));
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
				HierarchyNode node = getNode(current, loader);

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
		private static HierarchyNode getNode(String slashName, ClassLoader loader) {
			HierarchyNode node = tree.get(slashName);
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
			HierarchyNode node = getNode(slashName, loader);
			return node != null && (node.access & ACC_INTERFACE) != 0;
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
				HierarchyTree.HierarchyNode node = HierarchyTree.getNode(type1Super, targetLoader);
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