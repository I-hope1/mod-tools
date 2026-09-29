package nipx.ref;

import nipx.*;
import nipx.ClassDiffUtil.ClassDiff;
import nipx.jvmti.LibTool;
import org.objectweb.asm.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Collectors;

import static nipx.HotSwapAgent.log;

/**
 * Hotswap时初始化修复器
 * <p>新增字段的初始化表达式如果依赖构造器参数、局部变量、含分支/内联，存量实例不会被初始化</p>
 * <p>注意：会去除final字段，可能会改变语义</p>
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
		// extractFieldInits(diff.newClass, addedFields);
		return injectFieldInitPatch(newBytes, diff.newClass.name, addedStaticFields, addedInstanceFields);
	}

	public static byte[] injectFieldInitPatch(
	 byte[] newBytes, String className, Set<String> addedStaticFields, Set<String> addedInstanceFields) {

		ClassNode newClass = new ClassNode();
		new ClassReader(newBytes).accept(newClass, 0);

		Set<String> added = new HashSet<>(addedInstanceFields);
		added.addAll(addedStaticFields);
		for (FieldNode fn : newClass.fields) {
			if (added.contains(fn.name) && (fn.access & Opcodes.ACC_FINAL) != 0) {
				fn.access &= ~Opcodes.ACC_FINAL;
			}
		}

		// 提取实例字段<init>指令
		List<AbstractInsnNode> initInsns = new ArrayList<>();
		{
			Set<String> remainingFields = new HashSet<>(addedInstanceFields);

			// 筛选出所有的构造函数（包括带参数的）
			List<MethodNode> initMethods = newClass.methods.stream()
			 .filter(m -> "<init>".equals(m.name))
			 .toList();

			for (MethodNode init : initMethods) {
				if (remainingFields.isEmpty()) {
					break;
				}
				log("Extracting field init for " + className + "." + init.name + "()");
				// 针对当前构造函数，尝试提取剩余未解析字段的初始化指令
				List<AbstractInsnNode> extracted = extractFieldInits(className, init, remainingFields, false);
				if (!extracted.isEmpty()) {
					initInsns.addAll(extracted);
					// 遍历已提取的指令，将成功解析的 PUTFIELD 字段从 remainingFields 中移除，防止后续构造函数重复提取
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
				List<AbstractInsnNode> extracted = extractFieldInits(className, clinit, remainingStaticFields, true);
				clinitInsns.addAll(extracted);
				for (AbstractInsnNode insn : extracted) {
					if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC) {
						remainingStaticFields.remove(f.name);
					}
				}
			}
			if (!remainingStaticFields.isEmpty()) {
				for (FieldNode field : newClass.fields) {
					if ((field.access & Opcodes.ACC_STATIC) != 0 && remainingStaticFields.contains(field.name)) {
						if (field.value != null) {
							clinitInsns.add(new LdcInsnNode(field.value));
							clinitInsns.add(new FieldInsnNode(Opcodes.PUTSTATIC, className, field.name, field.desc));
							remainingStaticFields.remove(field.name);
							log("Extracted constant field init from ConstantValue: " + className + "." + field.name);
						}
					}
				}
			}
		}

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

	public static void afterRedefined(Class<?> clazz, byte[] newBytes) {
		if (!HotSwapAgent.HOTSWAP_PLUS) return;

		l:
		try {
			if (!hasStaticMethodAsm(newBytes, STATIC_PATCH_METHOD, "()V")) break l;
			Method staticPatch = clazz.getDeclaredMethod(STATIC_PATCH_METHOD);
			staticPatch.setAccessible(true);
			HotSwapAgent.info("Applying static field init patch to " + clazz.getName());
			staticPatch.invoke(null);
		} catch (NoSuchMethodException _) {
			// 无新增静态字段
		} catch (Throwable e) {
			e.printStackTrace();
			HotSwapAgent.error("Static field init patch failed: " + e.getMessage());
		}

		l:
		try {
			if (!hasStaticMethodAsm(newBytes, PATCH_METHOD, "(" + AnnotationTransformer.typeToNative(clazz) + ")V")) break l;
			Object[] instances = LibTool.initialized() ? LibTool.getInstances(clazz) : InstanceTracker.getInstances(clazz).toArray();
			int      length    = instances.length;
			if (length == 0) break l;
			HotSwapAgent.info("Applying instance field init patch to " + clazz.getName() + ", count=" + length);
			// String desc  = "(L" + clazz.getName().replace('.', '/') + ";)V";
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
		// 使用 ClassVisitor 只访问方法，轻量扫描
		boolean[] found = {false};
		cr.accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
			                                 String signature, String[] exceptions) {
				if (found[0] || (access & Opcodes.ACC_STATIC) == 0) return null;
				if (name.equals(methodName) && descriptor.equals(desc)) {
					found[0] = true;
				}
				return null; // 不深入方法体
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG); // 跳过方法体，更快
		return found[0];
	}

	/**
	 * 基于操作数栈模拟（微型 AST）的高健壮性指令提取算法
	 * 自动识别并排除任何依赖局部变量（除了 ALOAD 0）的危险赋值
	 */
	private static List<AbstractInsnNode> extractFieldInits(
	 String className, MethodNode method, Set<String> targetFields, boolean isStatic) {
		if (method == null || targetFields.isEmpty()) return Collections.emptyList();

		InsnList        insns = method.instructions;
		Stack<ExprNode> stack = new Stack<>();

		// 记录最终需要保留的、安全的指令
		Set<AbstractInsnNode> safeCollected = new LinkedHashSet<>();

		try {
			final Set<LabelNode> jumpTargets = collectJumpTargets(method); // 预先收集跳转目标

			for (int i = 0; i < insns.size(); i++) {
				AbstractInsnNode insn   = insns.get(i);
				int              opcode = insn.getOpcode();
				if (opcode == -1) continue; // 跳过虚节点 (Labels, Frames, LineNumber等)

				// 纯栈重排指令：不能套用通用 pop-N/push-N 模型
				switch (opcode) {
					case Opcodes.DUP_X1 -> {
						if (stack.size() < 2) throw new IllegalStateException("stack underflow: DUP_X1");
						ExprNode v1 = stack.pop(), v2 = stack.pop(); // v1=栈顶, v2=次顶
						ExprNode dup = new ExprNode(insn); // 代表这条 DUP_X1 指令本身
						dup.children.add(v2);
						dup.children.add(v1);
						v1.attached.add(dup);  // 谁被收集到，就把 DUP_X1 这条指令带上
						v2.attached.add(dup);
						stack.push(v1);
						stack.push(v2);
						stack.push(v1); // [v1, v2, v1]
						continue;
					}
					case Opcodes.SWAP -> {
						if (stack.size() < 2) throw new IllegalStateException("stack underflow: SWAP");
						ExprNode v1 = stack.pop(), v2 = stack.pop();
						stack.push(v1);
						stack.push(v2);
						continue;
					}
					case Opcodes.POP -> {
						if (stack.isEmpty()) throw new IllegalStateException("stack underflow: POP");
						stack.pop(); // 丢弃的值不再参与后续
						continue;
					}
					// DUP_X2、DUP2、DUP2_X1、DUP2_X2、POP2 仍然抛异常——它们依赖操作数是否为
					// category-2（long/double），当前模型不区分类别宽度，硬做容易出错
					case Opcodes.DUP_X2, Opcodes.DUP2, Opcodes.DUP2_X1, Opcodes.DUP2_X2, Opcodes.POP2 ->
						throw new IllegalStateException("unsupported " + opcode);
				}

				int popCount  = getPopCount(insn);
				int pushCount = getPushCount(insn);

				ExprNode node = new ExprNode(insn);
				for (int j = 0; j < popCount; j++) {
					if (stack.isEmpty()) throw new IllegalStateException("Stack underflow");
					node.children.add(0, stack.pop()); // 逆序挂载子表达式
				}

				// 构造器调用
				if (opcode == Opcodes.INVOKESPECIAL
				    && "<init>".equals(((MethodInsnNode) insn).name)
				    && !node.children.isEmpty()) {
					node.children.get(0).attached.add(node); // 接收者与栈上剩余的那份是同一个对象
				}

				// 数组赋值：IASTORE ~ SASTORE 挂载到数组引用上（children[0] 为 arrayref）
				if (opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE && !node.children.isEmpty()) {
					node.children.get(0).attached.add(node);
				}

				// Kotlin Intrinsics 运行时非空校验调用：挂载到被校验的目标对象引用上（children[0] 为 target
				if (opcode == Opcodes.INVOKESTATIC && !node.children.isEmpty()) {
					MethodInsnNode m = (MethodInsnNode) insn;
					if ("kotlin/jvm/internal/Intrinsics".equals(m.owner) && m.desc.endsWith(")V")) {
						node.children.get(0).attached.add(node);
					}
				}

				// 判定是否是目标字段的写入
				boolean isTargetPut = false;
				if (isStatic && opcode == Opcodes.PUTSTATIC) {
					FieldInsnNode f = (FieldInsnNode) insn;
					isTargetPut = targetFields.contains(f.name) && f.owner.equals(className);
				} else if (!isStatic && opcode == Opcodes.PUTFIELD) {
					FieldInsnNode f = (FieldInsnNode) insn;
					isTargetPut = targetFields.contains(f.name) && f.owner.equals(className);
				}

				if (isTargetPut) {
					FieldInsnNode f = (FieldInsnNode) insn;

					// 临时收集当前字段赋值所关联的所有前置指令
					Set<AbstractInsnNode> tempCollected = new HashSet<>();
					node.collect(tempCollected);

					// 安全性检查：仅针对当前字段进行判定，不满足则跳过该字段，而不中断整个方法
					String unsafeReason = checkSafe(method, insns, jumpTargets, node, tempCollected, i, isStatic);

					// 栈平衡兜底：像 update(rebuild = lambda) 这种“赋值当参数用”的写法，
					// 提取出的片段可能在操作数栈上留下残余值。
					if (unsafeReason == null) {
						List<AbstractInsnNode> ordered = tempCollected.stream()
						 .sorted(Comparator.comparingInt(insns::indexOf))
						 .collect(Collectors.toList());
						if (!isStackBalanced(ordered)) {
							unsafeReason = "unbalanced stack after extraction";
						}
					}

					if (unsafeReason == null) {
						safeCollected.addAll(tempCollected);
					} else {
						HotSwapAgent.warn("Field '" + f.name + "' initialization skipped: " + unsafeReason);
					}
				}

				for (int j = 0; j < pushCount; j++) {
					stack.push(node);
				}
			}
		} catch (Throwable e) {
			HotSwapAgent.error(
			 "Analysis stopped early at " + method.name + method.desc
			 + ", reason: " + e.getMessage()
			 + " -- fields already resolved before this point are kept: "
			 + safeCollected.stream().filter(n -> n instanceof FieldInsnNode)
			 .map(n -> ((FieldInsnNode) n).name).collect(Collectors.joining(", ")));
			// 不要 return emptyList，用已收集的结果继续走后面的流程
		}

		// 保持原指令在代码中的自然物理顺序输出
		List<AbstractInsnNode> result = new ArrayList<>();
		for (int i = 0; i < insns.size(); i++) {
			AbstractInsnNode insn = insns.get(i);
			if (safeCollected.contains(insn)) {
				result.add(insn.clone(new HashMap<>()));
			}
		}
		return result;
	}

	private static String checkSafe(MethodNode m, InsnList insns, Set<LabelNode> jumpTargets,
	                                ExprNode put, Set<AbstractInsnNode> collected, int putIdx, boolean isStatic) {
		if (!isStatic) {
			if (put.children.isEmpty()) return "missing receiver";
			AbstractInsnNode recv = put.children.get(0).insn;
			if (!(recv instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 0)) {
				return "unexpected receiver";
			}
		}

		int min = Integer.MAX_VALUE;
		for (AbstractInsnNode n : collected) {
			min = Math.min(min, insns.indexOf(n));
		}
		for (int k = min; k <= putIdx; k++) {
			AbstractInsnNode n = insns.get(k);
			if (n.getOpcode() != -1 && !collected.contains(n)) {
				return "contains instructions outside the expression tree";
			}
		}
		for (int k = min + 1; k <= putIdx; k++) {
			if (insns.get(k) instanceof LabelNode l && jumpTargets.contains(l)) {
				return "contains branch";
			}
		}

		for (AbstractInsnNode n : collected) {
			if (n instanceof VarInsnNode v && (isStatic || v.getOpcode() != Opcodes.ALOAD || v.var != 0)) {
				return "depends on local variables";
			}
			if (n.getOpcode() == Opcodes.IINC) {
				return "depends on local variables";
			}
		}
		return null;
	}

	/** 栈平衡校验：生成指令列表后，先自己算一遍净栈增量，不平衡就直接放弃这个字段。 */
	private static boolean isStackBalanced(List<AbstractInsnNode> insns) {
		int depth = 0;
		for (AbstractInsnNode insn : insns) {
			if (insn.getOpcode() == -1) continue;
			depth += getPushCount(insn) - getPopCount(insn);
		}
		return depth == 0;
	}

	/** 内部辅助类：微型表达式树节点（用于追踪数据流向） */
	private static class ExprNode {
		final AbstractInsnNode insn;
		final List<ExprNode>   children = new ArrayList<>();
		final List<ExprNode>   attached = new ArrayList<>();

		ExprNode(AbstractInsnNode insn) {
			this.insn = insn;
		}

		void collect(Set<AbstractInsnNode> out) {
			if (!out.add(insn)) return;
			for (ExprNode c : children) c.collect(out);
			for (ExprNode a : attached) a.collect(out);
		}
	}

	// ==================== JVM 栈计算映射表 ====================
	@SuppressWarnings("DuplicateBranchesInSwitch")
	private static int getPopCount(AbstractInsnNode insn) {
		int opcode = insn.getOpcode();
		return switch (opcode) {
			case Opcodes.NOP -> 0;
			case Opcodes.ACONST_NULL, Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
			     Opcodes.ICONST_3,
			     Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.FCONST_0, Opcodes.FCONST_1,
			     Opcodes.FCONST_2, Opcodes.DCONST_0, Opcodes.DCONST_1, Opcodes.BIPUSH, Opcodes.SIPUSH, Opcodes.LDC -> 0;
			case Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD -> 0;
			case Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD, Opcodes.AALOAD, Opcodes.BALOAD,
			     Opcodes.CALOAD,
			     Opcodes.SALOAD -> 2;
			case Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE -> 1;
			case Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.AASTORE, Opcodes.BASTORE,
			     Opcodes.CASTORE, Opcodes.SASTORE -> 3;
			case Opcodes.POP -> 1;
			case Opcodes.POP2 -> 2;
			case Opcodes.DUP -> 1;
			case Opcodes.DUP_X1 -> 2;
			case Opcodes.DUP_X2 -> 3;
			case Opcodes.DUP2 -> 2;
			case Opcodes.DUP2_X1 -> 3;
			case Opcodes.DUP2_X2 -> 4;
			case Opcodes.SWAP -> 2;
			case Opcodes.IADD, Opcodes.LADD, Opcodes.FADD, Opcodes.DADD, Opcodes.ISUB, Opcodes.LSUB, Opcodes.FSUB,
			     Opcodes.DSUB,
			     Opcodes.IMUL, Opcodes.LMUL, Opcodes.FMUL, Opcodes.DMUL, Opcodes.IDIV, Opcodes.LDIV, Opcodes.FDIV,
			     Opcodes.DDIV,
			     Opcodes.IREM, Opcodes.LREM, Opcodes.FREM, Opcodes.DREM -> 2;
			case Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG -> 1;
			case Opcodes.ISHL, Opcodes.LSHL, Opcodes.ISHR, Opcodes.LSHR, Opcodes.IUSHR, Opcodes.LUSHR, Opcodes.IAND,
			     Opcodes.LAND, Opcodes.IOR, Opcodes.LOR, Opcodes.IXOR, Opcodes.LXOR -> 2;
			case Opcodes.IINC -> 0;
			case Opcodes.I2L, Opcodes.I2F, Opcodes.I2D, Opcodes.L2I, Opcodes.L2F, Opcodes.L2D, Opcodes.F2I, Opcodes.F2L,
			     Opcodes.F2D, Opcodes.D2I, Opcodes.D2L, Opcodes.D2F, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S -> 1;
			case Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG -> 2;
			case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE -> 1;
			case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT,
			     Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> 2;
			case Opcodes.GOTO, Opcodes.JSR -> 0;
			case Opcodes.RET -> 0;
			case Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> 1;
			case Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN -> 1;
			case Opcodes.RETURN -> 0;
			case Opcodes.GETSTATIC -> 0;
			case Opcodes.PUTSTATIC -> 1;
			case Opcodes.GETFIELD -> 1;
			case Opcodes.PUTFIELD -> 2;
			case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE -> {
				MethodInsnNode minsn = (MethodInsnNode) insn;
				int            args  = Type.getArgumentTypes(minsn.desc).length;
				int            extra = (opcode == Opcodes.INVOKESTATIC) ? 0 : 1;
				yield args + extra;
			}
			case Opcodes.INVOKEDYNAMIC -> {
				InvokeDynamicInsnNode idinsn = (InvokeDynamicInsnNode) insn;
				yield Type.getArgumentTypes(idinsn.desc).length;
			}
			case Opcodes.NEW -> 0;
			case Opcodes.NEWARRAY, Opcodes.ANEWARRAY -> 1;
			case Opcodes.ARRAYLENGTH -> 1;
			case Opcodes.ATHROW -> 1;
			case Opcodes.CHECKCAST, Opcodes.INSTANCEOF -> 1;
			case Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> 1;
			case Opcodes.MULTIANEWARRAY -> ((MultiANewArrayInsnNode) insn).dims;
			case Opcodes.IFNULL, Opcodes.IFNONNULL -> 1;
			default -> 0;
		};
	}

	private static Set<LabelNode> collectJumpTargets(MethodNode m) {
		Set<LabelNode> t = new HashSet<>();
		for (AbstractInsnNode n : m.instructions) {
			if (n instanceof JumpInsnNode j) { t.add(j.label); } else if (n instanceof TableSwitchInsnNode s) {
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

	@SuppressWarnings("DuplicateBranchesInSwitch")
	private static int getPushCount(AbstractInsnNode insn) {
		int opcode = insn.getOpcode();
		return switch (opcode) {
			case Opcodes.ACONST_NULL, Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
			     Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.FCONST_0,
			     Opcodes.FCONST_1, Opcodes.FCONST_2, Opcodes.DCONST_0, Opcodes.DCONST_1, Opcodes.BIPUSH, Opcodes.SIPUSH,
			     Opcodes.LDC -> 1;
			case Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD -> 1;
			case Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD, Opcodes.AALOAD, Opcodes.BALOAD,
			     Opcodes.CALOAD, Opcodes.SALOAD -> 1;
			case Opcodes.DUP -> 2;
			case Opcodes.DUP_X1 -> 3;
			case Opcodes.SWAP -> 2;
			case Opcodes.POP -> 0;
			case Opcodes.DUP_X2, Opcodes.DUP2, Opcodes.DUP2_X1, Opcodes.DUP2_X2, Opcodes.POP2 ->
				throw new IllegalStateException("unsupported " + opcode);
			case Opcodes.IADD, Opcodes.LADD, Opcodes.FADD, Opcodes.DADD, Opcodes.ISUB, Opcodes.LSUB, Opcodes.FSUB,
			     Opcodes.DSUB, Opcodes.IMUL, Opcodes.LMUL, Opcodes.FMUL, Opcodes.DMUL, Opcodes.IDIV, Opcodes.LDIV,
			     Opcodes.FDIV, Opcodes.DDIV, Opcodes.IREM, Opcodes.LREM, Opcodes.FREM, Opcodes.DREM, Opcodes.INEG,
			     Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG, Opcodes.ISHL, Opcodes.LSHL, Opcodes.ISHR, Opcodes.LSHR,
			     Opcodes.IUSHR, Opcodes.LUSHR, Opcodes.IAND, Opcodes.LAND, Opcodes.IOR, Opcodes.LOR, Opcodes.IXOR,
			     Opcodes.LXOR -> 1;
			case Opcodes.MULTIANEWARRAY -> 1;
			case Opcodes.I2L, Opcodes.I2F, Opcodes.I2D, Opcodes.L2I, Opcodes.L2F, Opcodes.L2D, Opcodes.F2I, Opcodes.F2L,
			     Opcodes.F2D, Opcodes.D2I, Opcodes.D2L, Opcodes.D2F, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S, Opcodes.LCMP,
			     Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG -> 1;
			case Opcodes.GETSTATIC -> 1;
			case Opcodes.GETFIELD -> 1;
			case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE -> {
				MethodInsnNode minsn = (MethodInsnNode) insn;
				yield Type.getReturnType(minsn.desc) == Type.VOID_TYPE ? 0 : 1;
			}
			case Opcodes.INVOKEDYNAMIC -> {
				InvokeDynamicInsnNode idinsn = (InvokeDynamicInsnNode) insn;
				yield Type.getReturnType(idinsn.desc) == Type.VOID_TYPE ? 0 : 1;
			}
			case Opcodes.NEW -> 1;
			case Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.ARRAYLENGTH -> 1;
			case Opcodes.CHECKCAST, Opcodes.INSTANCEOF -> 1;
			default -> 0;
		};
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