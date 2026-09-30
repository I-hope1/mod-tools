package nipx.ref;

import nipx.*;
import nipx.ClassDiffUtil.ClassDiff;
import nipx.jvmti.LibTool;
import org.objectweb.asm.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.tree.analysis.Frame;

import java.lang.reflect.*;
import java.util.*;

import static nipx.HotSwapAgent.log;

/**
 * Hotswap时初始化修复器
 * <p>新增字段的初始化表达式如果依赖构造器参数、局部变量、含分支/内联，存量实例不会被初始化</p>
 * <p>不再摘除final修饰符：补丁方法不是该类的&lt;init&gt;/&lt;clinit&gt;，
 * 直接PUTFIELD/PUTSTATIC写final字段会抛IllegalAccessError，改为经{@link FinalFieldWriter}用Unsafe写入</p>
 */
public class InitFix {
	private static final String PATCH_METHOD        = "$hotswap$initNewFields$";
	private static final String STATIC_PATCH_METHOD = "$hotswap$initNewStaticFields$";

	public static byte[] transform(byte[] newBytes, ClassDiff diff) {
		if (!HotSwapAgent.HOTSWAP_PLUS) return newBytes;
		Set<String> addedStaticFields   = new HashSet<>();
		Set<String> addedInstanceFields = new HashSet<>();
		for (String change : diff.changedFields) {
			if (change.startsWith("+ *")) {
				addedStaticFields.add(change.substring(3)); // 剥离 "+ *"
			} else if (change.startsWith("+ ")) {
				addedInstanceFields.add(change.substring(2)); // 剥离 "+ "
			}
		}

		if (addedStaticFields.isEmpty() && addedInstanceFields.isEmpty()) {
			return newBytes;
		}
		return injectFieldInitPatch(newBytes, diff.newClass.name, addedStaticFields, addedInstanceFields);
	}

	public static byte[] injectFieldInitPatch(
	 byte[] newBytes, String className, Set<String> addedStaticFields, Set<String> addedInstanceFields) {

		ClassNode newClass = new ClassNode();
		new ClassReader(newBytes).accept(newClass, 0);

		// 补丁方法不是该类的 <init>/<clinit>，直接 PUTFIELD/PUTSTATIC 写 final 字段会抛 IllegalAccessError/静默跳过。
		// 因此保留 final 修饰符，把补丁片段里对这些字段的写入改写成 Unsafe 调用：name -> desc
		Map<String, String> unsafeFields = new HashMap<>();
		for (FieldNode fn : newClass.fields) {
			if ((fn.access & Opcodes.ACC_FINAL) != 0) {
				unsafeFields.put(fn.name, fn.desc);
			}
		}

		// 提取实例字段<init>指令
		List<AbstractInsnNode> initInsns = new ArrayList<>();
		{
			Set<String> remainingFields = new HashSet<>(addedInstanceFields);

			List<MethodNode> initMethods = newClass.methods.stream()
			 .filter(m -> "<init>".equals(m.name))
			 .toList();

			for (MethodNode init : initMethods) {
				if (remainingFields.isEmpty()) {
					break;
				}
				log("Extracting field init for " + className + "." + init.name + "()");
				List<AbstractInsnNode> extracted =
				 extractFieldInits(className, init, remainingFields, false);
				if (!extracted.isEmpty()) {
					initInsns.addAll(extracted);
					for (AbstractInsnNode insn : extracted) {
						if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD) {
							remainingFields.remove(f.name);
						}
					}
				}
			}
		}

		// 提取静态字段<clinit>指令
		List<AbstractInsnNode> clinitInsns = new ArrayList<>();
		{
			Set<String> remainingStaticFields = new HashSet<>(addedStaticFields);

			MethodNode clinit = newClass.methods.stream()
			 .filter(m -> "<clinit>".equals(m.name) && "()V".equals(m.desc))
			 .findFirst().orElse(null);
			if (clinit != null) {
				List<AbstractInsnNode> extracted =
				 extractFieldInits(className, clinit, remainingStaticFields, true);
				clinitInsns.addAll(extracted);
				for (AbstractInsnNode insn : extracted) {
					if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC) {
						remainingStaticFields.remove(f.name);
					}
				}
			}
			if (!remainingStaticFields.isEmpty()) {
				for (FieldNode field : newClass.fields) {
					if ((field.access & Opcodes.ACC_STATIC) != 0
					    && remainingStaticFields.contains(field.name)) {
						if (field.value != null) {
							clinitInsns.add(new LdcInsnNode(field.value));
							clinitInsns.add(new FieldInsnNode(
							 Opcodes.PUTSTATIC, className, field.name, field.desc));
							remainingStaticFields.remove(field.name);
							log("Extracted constant field init from ConstantValue: "
							    + className + "." + field.name);
						}
					}
				}
			}
		}

		// final 字段的写入改走 FinalFieldWriter（Unsafe）
		initInsns   = rewriteFinalPuts(className, initInsns,   unsafeFields);
		clinitInsns = rewriteFinalPuts(className, clinitInsns, unsafeFields);

		if (initInsns.isEmpty() && clinitInsns.isEmpty()) return newBytes;

		if (!initInsns.isEmpty()) {
			MethodNode patch = new MethodNode(
			 Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC,
			 PATCH_METHOD, "(L" + className + ";)V", null, null
			);
			for (AbstractInsnNode insn : initInsns) {
				patch.instructions.add(insn);
			}
			patch.instructions.add(new InsnNode(Opcodes.RETURN));
			newClass.methods.add(patch);
		}

		if (!clinitInsns.isEmpty()) {
			MethodNode staticPatch = new MethodNode(
			 Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC,
			 STATIC_PATCH_METHOD, "()V", null, null
			);
			for (AbstractInsnNode insn : clinitInsns) {
				staticPatch.instructions.add(insn);
			}
			staticPatch.instructions.add(new InsnNode(Opcodes.RETURN));
			newClass.methods.add(staticPatch);
		}

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		newClass.accept(cw);
		return cw.toByteArray();
	}

	// ==================== final 字段 -> Unsafe 写入 ====================

	/**
	 * 把补丁片段里对本类 final 字段的直接写入改写为 {@link FinalFieldWriter} 调用。
	 * <p>指令形态：实例字段原本是 {@code [obj, value] + PUTFIELD}，把 PUTFIELD 换成
	 * {@code ldc class; ldc name; invokestatic} 后，栈自底向上恰好是
	 * {@code (obj, value, class, name)}，与 {@code putXxx(Object, X, Class, String)} 的实参顺序一致；
	 * 静态字段原本是 {@code [value] + PUTSTATIC}，同理对应 {@code putStaticXxx(X, Class, String)}。</p>
	 * <p>栈平衡校验作用在提取阶段的原始 {@code Frame} 上，与本次改写无关，
	 * 改写前后净栈增量一致（实例 -2，静态 -1）。</p>
	 */
	private static List<AbstractInsnNode> rewriteFinalPuts(
	 String className, List<AbstractInsnNode> insns, Map<String, String> unsafeFields) {
		if (insns.isEmpty() || unsafeFields.isEmpty()) return insns;

		List<AbstractInsnNode> rewritten = new ArrayList<>(insns.size() + 8);
		for (AbstractInsnNode insn : insns) {
			if (!(insn instanceof FieldInsnNode f)
			    || !f.owner.equals(className)
			    || !unsafeFields.containsKey(f.name)
			    || (f.getOpcode() != Opcodes.PUTFIELD && f.getOpcode() != Opcodes.PUTSTATIC)) {
				rewritten.add(insn);
				continue;
			}

			boolean isStatic = f.getOpcode() == Opcodes.PUTSTATIC;
			String  method   = (isStatic ? "putStatic" : "put") + typeSuffix(f.desc);

			String valDesc = (f.desc.charAt(0) == 'L' || f.desc.charAt(0) == '[')
			 ? "Ljava/lang/Object;" : f.desc;

			String desc = (isStatic ? "(" : "(Ljava/lang/Object;")
			              + valDesc + "Ljava/lang/Class;Ljava/lang/String;)V";

			rewritten.add(new LdcInsnNode(Type.getObjectType(className)));
			rewritten.add(new LdcInsnNode(f.name));
			rewritten.add(new MethodInsnNode(
			 Opcodes.INVOKESTATIC, Type.getInternalName(FinalFieldWriter.class), method, desc, false));
			log("Rewriting final field write to Unsafe: " + className + "." + f.name + " " + f.desc);
		}
		return rewritten;
	}

	/** 字段描述符 -> {@link FinalFieldWriter} 方法名后缀 */
	private static String typeSuffix(String desc) {
		return switch (desc.charAt(0)) {
			case 'Z' -> "Boolean";
			case 'B' -> "Byte";
			case 'C' -> "Char";
			case 'S' -> "Short";
			case 'I' -> "Int";
			case 'J' -> "Long";
			case 'F' -> "Float";
			case 'D' -> "Double";
			default -> "Object"; // L...; 与 [...
		};
	}

	public static void afterRedefined(Class<?> clazz, byte[] newBytes) {
		if (!HotSwapAgent.HOTSWAP_PLUS) return;

		l:
		try {
			if (!hasStaticMethodAsm(newBytes, STATIC_PATCH_METHOD, "()V")) break l;
			Method staticPatch = clazz.getDeclaredMethod(STATIC_PATCH_METHOD);
			staticPatch.setAccessible(true);
			HotSwapAgent.info("Applying static field init patch to " + clazz.getName());
			staticPatch.invoke(null);
		} catch (NoSuchMethodException ignored) {
			// 无新增静态字段
		} catch (Throwable e) {
			e.printStackTrace();
			HotSwapAgent.error("Static field init patch failed: " + e.getMessage());
		}

		l:
		try {
			if (!hasStaticMethodAsm(newBytes, PATCH_METHOD,
			                        "(" + AnnotationTransformer.typeToNative(clazz) + ")V")) break l;
			Object[] instances = LibTool.initialized()
			 ? LibTool.getInstances(clazz)
			 : InstanceTracker.getInstances(clazz).toArray();
			int length = instances.length;
			if (length == 0) break l;
			HotSwapAgent.info("Applying instance field init patch to " + clazz.getName()
			                  + ", count=" + length);
			Method patch = clazz.getDeclaredMethod(PATCH_METHOD, clazz);
			patch.setAccessible(true);
			for (Object ins : instances) {
				try {
					patch.invoke(null, ins);
				} catch (InvocationTargetException e) {
					HotSwapAgent.error("init patch failed on one instance: " + e.getCause());
				}
			}
		} catch (NoSuchMethodException _) {
			// 无新增实例字段，跳过
		} catch (Throwable e) {
			e.printStackTrace();
			HotSwapAgent.error("Field init patch failed: " + e.getMessage());
		}
	}

	public static boolean hasStaticMethodAsm(byte[] classBytes, String methodName, String desc) {
		ClassReader cr = new ClassReader(classBytes);
		boolean[] found = {false};
		cr.accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
			                                 String signature, String[] exceptions) {
				if (found[0] || (access & Opcodes.ACC_STATIC) == 0) return null;
				if (name.equals(methodName) && descriptor.equals(desc)) {
					found[0] = true;
				}
				return null;
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
		return found[0];
	}

	// ==================== 基于 ASM Analyzer 的字段初始化提取 ====================

	/**
	 * 让 {@link SourceValue#insns} 成为完整的数据依赖闭包（不只是直接生产者）。
	 * <p>ASM 默认的 {@link SourceInterpreter} 在 {@code unaryOperation/binaryOperation/naryOperation}
	 * 里只返回 {@code new SourceValue(size, insn)}，集合里只有这条指令自身，操作数来源不在其中。
	 * 旧版靠 {@code ExprNode.collect} 递归子节点，这里用覆写等效实现：把操作数的 {@code insns}
	 * 一并并入返回值，形成传递闭包。</p>
	 * <p>{@code copyOperation} 对 DUP* / SWAP 保持别名（返回源值），使副本与源共享同一
	 * {@code SourceValue}；其它 copy（ILOAD/ALOAD 等）走 {@code super}，只含指令自身——这正是
	 * 局部变量读取被登记为"来源"的关键。</p>
	 * <p>闭包化之后，{@link #expandAssociatedCalls} 只需处理"没有产出值的副作用消费者"
	 * （{@code <init>}、Intrinsics、数组 store、POP、DUP），职责比之前清晰得多。</p>
	 */
	private static class AliasInterpreter extends SourceInterpreter {
		AliasInterpreter() { super(Opcodes.ASM9); }

		@Override
		public SourceValue copyOperation(AbstractInsnNode insn, SourceValue v) {
			int op = insn.getOpcode();
			if (op >= Opcodes.DUP && op <= Opcodes.SWAP) return v;
			return super.copyOperation(insn, v);
		}

		@Override
		public SourceValue unaryOperation(AbstractInsnNode insn, SourceValue v) {
			return withOperands(super.unaryOperation(insn, v), List.of(v));
		}

		@Override
		public SourceValue binaryOperation(AbstractInsnNode insn, SourceValue a, SourceValue b) {
			return withOperands(super.binaryOperation(insn, a, b), List.of(a, b));
		}

		@Override
		public SourceValue naryOperation(AbstractInsnNode insn, List<? extends SourceValue> vs) {
			return withOperands(super.naryOperation(insn, vs), vs);
		}

		private static SourceValue withOperands(SourceValue base,
		                                        List<? extends SourceValue> ops) {
			if (ops.isEmpty()) return base;
			Set<AbstractInsnNode> s = new HashSet<>(base.insns);
			for (SourceValue o : ops) s.addAll(o.insns);
			return new SourceValue(base.size, s);
		}
	}

	/**
	 * 基于 ASM {@link Analyzer} + {@link AliasInterpreter} 的字段初始化提取。
	 * <p>{@code frames[i]} 是第 i 条指令 <b>执行前</b> 的状态；对 PUTFIELD/PUTSTATIC 而言，
	 * 栈顶即要写入的值，栈顶下一格即接收者。</p>
	 */
	private static List<AbstractInsnNode> extractFieldInits(
	 String className, MethodNode method, Set<String> targetFields, boolean isStatic) {

		if (method == null || targetFields.isEmpty()) return Collections.emptyList();

		Frame<SourceValue>[] frames;
		try {
			Analyzer<SourceValue> analyzer = new Analyzer<>(new AliasInterpreter()) {
				@Override
				protected boolean newControlFlowExceptionEdge(int insnIndex, TryCatchBlockNode tcb) {
					// 不追踪异常边：补丁片段不会把 tryCatchBlocks 搬过去，异常边只会污染来源闭包
					return false;
				}
			};
			frames = analyzer.analyze(className, method);
		} catch (AnalyzerException e) {
			HotSwapAgent.warn("Analysis failed for " + method.name + method.desc
			                  + ": " + e.getMessage());
			return Collections.emptyList();
		}

		InsnList insns = method.instructions;
		Set<LabelNode> jumpTargets = collectJumpTargets(method);
		Set<AbstractInsnNode> safeCollected = new LinkedHashSet<>();

		for (int i = 0; i < insns.size(); i++) {
			AbstractInsnNode insn = insns.get(i);
			if (!(insn instanceof FieldInsnNode f)) continue;

			int op = f.getOpcode();
			if (isStatic ? op != Opcodes.PUTSTATIC : op != Opcodes.PUTFIELD) continue;
			if (!f.owner.equals(className) || !targetFields.contains(f.name)) continue;

			Frame<SourceValue> frame = frames[i];
			if (frame == null) continue; // 死代码

			int stackSize = frame.getStackSize();
			if (isStatic ? stackSize < 1 : stackSize < 2) continue;

			SourceValue value    = frame.getStack(stackSize - 1);
			SourceValue receiver = isStatic ? null : frame.getStack(stackSize - 2);

			Set<AbstractInsnNode> collected = new HashSet<>(value.insns);
			if (receiver != null) collected.addAll(receiver.insns);
			collected.add(insn); // put 指令自身算在表达式树区间内

			String unsafeReason;
			try {
				// 只扫 [0, i)：避免把 put 之后的指令收进来，也省时间
				expandAssociatedCalls(collected, insns, frames, i);

				int minIdx = Integer.MAX_VALUE, maxIdx = -1;
				for (AbstractInsnNode n : collected) {
					int idx = insns.indexOf(n);
					if (idx < 0) continue;
					if (idx < minIdx) minIdx = idx;
					if (idx > maxIdx) maxIdx = idx;
				}

				if (minIdx == Integer.MAX_VALUE) {
					unsafeReason = "empty collection";
				} else if (maxIdx > i) {
					unsafeReason = "collected instructions after the put";
				} else {
					unsafeReason = checkSafe(insns, jumpTargets, receiver, collected, minIdx, i, isStatic);
					if (unsafeReason == null && !isStackBalanced(frames, minIdx, i, isStatic)) {
						unsafeReason = "unbalanced stack after extraction";
					}
				}
			} catch (RuntimeException e) {
				unsafeReason = "extraction threw " + e.getClass().getSimpleName()
				               + ": " + e.getMessage();
			}

			if (unsafeReason == null) {
				safeCollected.addAll(collected);
			} else {
				HotSwapAgent.warn("Field '" + f.name + "' initialization skipped: " + unsafeReason);
			}
		}

		// 共享 labelMap，保持克隆后指令间的跳转标签拓扑一致
		Map<LabelNode, LabelNode> labelMap = new HashMap<>();
		List<AbstractInsnNode> result = new ArrayList<>();
		for (int i = 0; i < insns.size(); i++) {
			AbstractInsnNode insn = insns.get(i);
			if (safeCollected.contains(insn)) {
				result.add(insn.clone(labelMap));
			}
		}
		return result;
	}

	/**
	 * 把没有产出值的"副作用消费者"按数据流依赖补进 {@code collected}：
	 * {@code <init>}、Intrinsics 检查、空检查、结果被 {@code POP/POP2} 丢弃的调用、
	 * DUP* / SWAP 别名指令、数组 store。
	 * <p>普通指令（常量、算术、字段访问、方法调用等）通过 {@link AliasInterpreter}
	 * 的操作数闭包已经进入 {@code collected}，这里只需管这几类。</p>
	 * <p>反复迭代直到不动点：加入某条指令时，同步把它的输入来源（{@code SourceValue.insns}）
	 * 并入 {@code collected}，从而覆盖链式 {@code new A(new B(new C()))} 与
	 * {@code outer.new Inner()} 里的空检查前缀。</p>
	 *
	 * @param putIdx 当前 PUTFIELD/PUTSTATIC 的指令下标，扫描范围限定为 {@code [0, putIdx)}
	 */
	private static void expandAssociatedCalls(Set<AbstractInsnNode> collected,
	                                          InsnList insns,
	                                          Frame<SourceValue>[] frames,
	                                          int putIdx) {
		boolean changed = true;
		while (changed) {
			changed = false;
			for (int i = 0; i < putIdx; i++) {
				AbstractInsnNode insn = insns.get(i);
				if (collected.contains(insn)) continue;

				Frame<SourceValue> frame = frames[i];
				if (frame == null) continue;

				int op = insn.getOpcode();
				int ss = frame.getStackSize();

				if (op == Opcodes.INVOKESPECIAL
				    && insn instanceof MethodInsnNode m
				    && "<init>".equals(m.name)) {
					// 只按接收者匹配，避免参数命中扩大误收范围；
					// 但把参数的来源并入 collected，这样链式 new A(new B()) 能在下一轮收敛
					int argCount = Type.getArgumentTypes(m.desc).length;
					int recvIdx  = ss - 1 - argCount;
					if (recvIdx >= 0 && intersects(frame.getStack(recvIdx), collected)) {
						collected.add(insn);
						collected.addAll(frame.getStack(recvIdx).insns);
						for (int k = 0; k < argCount; k++) {
							int idx = recvIdx + 1 + k;
							if (idx < ss) collected.addAll(frame.getStack(idx).insns);
						}
						changed = true;
					}

				} else if (op == Opcodes.INVOKESTATIC
				           && insn instanceof MethodInsnNode m
				           && "kotlin/jvm/internal/Intrinsics".equals(m.owner)
				           && m.desc.endsWith(")V")) {
					// 全部参数的来源都要并入，否则 checkNotNullExpressionValue(x, "expr")
					// 的 LDC "expr" 会成为区间外的指令
					int argCount = Type.getArgumentTypes(m.desc).length;
					int base     = ss - argCount;
					if (argCount > 0 && base >= 0 && intersects(frame.getStack(base), collected)) {
						collected.add(insn);
						for (int k = 0; k < argCount; k++) {
							collected.addAll(frame.getStack(base + k).insns);
						}
						changed = true;
					}

				} else if (insn instanceof MethodInsnNode m && isNullCheck(m)) {
					// Objects.requireNonNull / Object.getClass：结果常被 POP 丢弃，
					// 但只要它的参数/接收者来自 collected，这条空检查就是初始化表达式的一部分
					int args  = Type.getArgumentTypes(m.desc).length;
					int total = args + (op == Opcodes.INVOKESTATIC ? 0 : 1);
					int base  = ss - total;
					if (base < 0) continue;

					boolean matched = false;
					for (int k = 0; k < total; k++) {
						if (intersects(frame.getStack(base + k), collected)) {
							matched = true;
							break;
						}
					}
					if (matched) {
						collected.add(insn);
						for (int k = 0; k < total; k++) {
							collected.addAll(frame.getStack(base + k).insns);
						}
						changed = true;
					}

				} else if (op == Opcodes.POP || op == Opcodes.POP2) {
					// 结果被丢弃：若被丢弃的值来自已收集指令（如 requireNonNull），
					// 则这条 POP 也在表达式树内，否则 checkSafe 会因它落在区间内而误报
					int n = entriesFor(frame, wordsOf(op));
					if (n <= 0) continue;
					boolean matched = false;
					for (int k = 0; k < n && k < ss; k++) {
						if (intersects(frame.getStack(ss - 1 - k), collected)) {
							matched = true;
							break;
						}
					}
					if (matched) {
						collected.add(insn);
						changed = true;
					}

				} else if (op >= Opcodes.DUP && op <= Opcodes.SWAP) {
					// DUP* / SWAP：Frame.execute 会调用 copyOperation，但指令自身不在
					// SourceValue.insns 中，需要按"输入是否已收集"补回
					int n = entriesFor(frame, wordsOf(op));
					if (n <= 0 || ss < n) continue;

					boolean matched = false;
					for (int k = 0; k < n; k++) {
						if (intersects(frame.getStack(ss - 1 - k), collected)) {
							matched = true;
							break;
						}
					}
					if (matched) {
						collected.add(insn);
						for (int k = 0; k < n; k++) {
							collected.addAll(frame.getStack(ss - 1 - k).insns);
						}
						changed = true;
					}

				} else if (op >= Opcodes.IASTORE && op <= Opcodes.SASTORE) {
					// 数组 store：把 arrayref、index、value 三者的来源都并入
					int base = ss - 3;
					if (base < 0) continue;
					if (intersects(frame.getStack(base), collected)) {
						collected.add(insn);
						for (int k = 0; k < 3; k++) {
							collected.addAll(frame.getStack(base + k).insns);
						}
						changed = true;
					}
				}
			}
		}
	}

	/**
	 * DUP* / SWAP / POP* 涉及的字数（JVM words，long/double = 2，其余 = 1）。
	 * 用于按栈上值的实际宽度决定需要参考几项，而不是硬编码项数。
	 */
	private static int wordsOf(int op) {
		return switch (op) {
			case Opcodes.DUP, Opcodes.POP -> 1;
			case Opcodes.DUP_X1, Opcodes.SWAP, Opcodes.DUP2, Opcodes.POP2 -> 2;
			case Opcodes.DUP_X2, Opcodes.DUP2_X1 -> 3;
			case Opcodes.DUP2_X2 -> 4;
			default -> 0;
		};
	}

	/** 自栈顶向下凑够 {@code words} 个字所需的栈项数（long/double 一项占两个字）。 */
	private static int entriesFor(Frame<SourceValue> f, int words) {
		int n = 0, w = 0, ss = f.getStackSize();
		while (w < words && n < ss) {
			w += f.getStack(ss - 1 - n++).getSize();
		}
		return n;
	}

	private static boolean intersects(SourceValue sv, Set<AbstractInsnNode> collected) {
		if (sv == null) return false;
		for (AbstractInsnNode n : sv.insns) {
			if (collected.contains(n)) return true;
		}
		return false;
	}

	/**
	 * 语义安全检查。{@code minIdx} 为 {@code collected} 中指令的最小下标（已由调用方算好）。
	 */
	private static String checkSafe(InsnList insns, Set<LabelNode> jumpTargets,
	                                SourceValue receiver, Set<AbstractInsnNode> collected,
	                                int minIdx, int putIdx, boolean isStatic) {
		if (!isStatic) {
			if (receiver == null || receiver.insns.size() != 1) return "unexpected receiver";
			AbstractInsnNode recv = receiver.insns.iterator().next();
			if (!(recv instanceof VarInsnNode v
			      && v.getOpcode() == Opcodes.ALOAD && v.var == 0)) {
				return "unexpected receiver";
			}
		}

		for (int k = minIdx; k <= putIdx; k++) {
			AbstractInsnNode n = insns.get(k);
			if (n.getOpcode() != -1 && !collected.contains(n)) {
				return "contains instructions outside the expression tree";
			}
		}
		for (int k = minIdx + 1; k <= putIdx; k++) {
			if (insns.get(k) instanceof LabelNode l && jumpTargets.contains(l)) {
				return "contains branch";
			}
		}
		for (AbstractInsnNode n : collected) {
			if (n instanceof VarInsnNode v
			    && (isStatic || v.getOpcode() != Opcodes.ALOAD || v.var != 0)) {
				return "depends on local variables";
			}
			if (n.getOpcode() == Opcodes.IINC) {
				return "depends on local variables";
			}
		}
		return null;
	}

	/**
	 * 栈平衡校验（基于 {@link Frame}，天然支持 long/double 与任意 DUP/POP 组合）。
	 * <p>已知 checkSafe 通过后 {@code [first, putIdx]} 区间内全部是 collected 指令；
	 * 因此只需比较 {@code frames[putIdx]} 与 {@code frames[first]} 的栈项数差，
	 * 应恰好等于 put 消费的项数（实例 2、静态 1）。</p>
	 */
	private static boolean isStackBalanced(Frame<SourceValue>[] frames,
	                                       int first, int putIdx, boolean isStatic) {
		Frame<SourceValue> a = frames[first];
		Frame<SourceValue> b = frames[putIdx];
		if (a == null || b == null) return false;
		return b.getStackSize() - a.getStackSize() == (isStatic ? 1 : 2);
	}

	private static Set<LabelNode> collectJumpTargets(MethodNode m) {
		Set<LabelNode> t = new HashSet<>();
		for (AbstractInsnNode n : m.instructions) {
			if (n instanceof JumpInsnNode j) {
				t.add(j.label);
			} else if (n instanceof TableSwitchInsnNode s) {
				t.add(s.dflt);
				t.addAll(s.labels);
			} else if (n instanceof LookupSwitchInsnNode s) {
				t.add(s.dflt);
				t.addAll(s.labels);
			}
		}
		for (TryCatchBlockNode tc : m.tryCatchBlocks) {
			t.add(tc.start);
			t.add(tc.end);
			t.add(tc.handler);
		}
		return t;
	}

	/**
	 * @see Objects#requireNonNull(Object)
	 * @see Object#getClass()
	 */
	private static boolean isNullCheck(MethodInsnNode m) {
		return (m.getOpcode() == Opcodes.INVOKESTATIC
		        && "java/util/Objects".equals(m.owner) && "requireNonNull".equals(m.name))
		       || (m.getOpcode() == Opcodes.INVOKEVIRTUAL
		           && "java/lang/Object".equals(m.owner) && "getClass".equals(m.name));
	}
}