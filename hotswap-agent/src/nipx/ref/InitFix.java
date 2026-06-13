package nipx.ref;

import nipx.ClassDiffUtil.ClassDiff;
import nipx.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Method;
import java.util.*;

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

		// 提取实例字段<init>指令
		MethodNode init = newClass.methods.stream()
		 .filter(m -> "<init>".equals(m.name) && "()V".equals(m.desc))
		 .findFirst().orElse(null);
		List<AbstractInsnNode> initInsns = extractFieldInits(init, addedInstanceFields, false);
		// 提取静态字段<clinit>指令
		MethodNode clinit = newClass.methods.stream()
		 .filter(m -> "<clinit>".equals(m.name) && "()V".equals(m.desc))
		 .findFirst().orElse(null);
		List<AbstractInsnNode> clinitInsns = extractFieldInits(clinit, addedStaticFields, true);

		if (initInsns.isEmpty() && clinitInsns.isEmpty()) return newBytes;

		if (!initInsns.isEmpty()) {
			String desc = "(L" + className + ";)V";
			MethodNode patch = new MethodNode(
			 Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC,
			 PATCH_METHOD, desc, null, null
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
	public static void afterRedefined(Class<?> clazz) {
		if (!HotSwapAgent.HOTSWAP_PLUS) return;

		try {
			Method staticPatch = clazz.getDeclaredMethod(STATIC_PATCH_METHOD);
			staticPatch.setAccessible(true);
			HotSwapAgent.info("Applying static field init patch to " + clazz.getName());
			staticPatch.invoke(null);
		} catch (NoSuchMethodException e) {
			// 无新增静态字段
		} catch (Throwable e) {
			HotSwapAgent.error("Static field init patch failed: " + e.getMessage());
		}

		try {
			List<?> instances = InstanceTracker.getInstances(clazz);
			if (instances.isEmpty()) return;
			HotSwapAgent.info("Applying instance field init patch to " + clazz.getName());
			// String desc  = "(L" + clazz.getName().replace('.', '/') + ";)V";
			Method patch = clazz.getDeclaredMethod(PATCH_METHOD, clazz);
			patch.setAccessible(true);
			for (Object instance : instances) {
				patch.invoke(null, instance);
			}
		} catch (NoSuchMethodException e) {
			// 无新增实例字段，跳过
		} catch (Throwable e) {
			HotSwapAgent.error("Field init patch failed: " + e.getMessage());
		}
	}
	/**
	 * 基于操作数栈模拟（微型 AST）的高健壮性指令提取算法
	 * 自动识别并排除任何依赖局部变量（除了 ALOAD 0）的危险赋值
	 */
	private static List<AbstractInsnNode> extractFieldInits(
	 MethodNode method, Set<String> targetFields, boolean isStatic) {
		if (method == null || targetFields.isEmpty()) return Collections.emptyList();

		InsnList        insns = method.instructions;
		Stack<ExprNode> stack = new Stack<>();

		// 记录最终需要保留的、安全的指令
		Set<AbstractInsnNode> safeCollected = new LinkedHashSet<>();

		try {
			for (int i = 0; i < insns.size(); i++) {
				AbstractInsnNode insn   = insns.get(i);
				int              opcode = insn.getOpcode();
				if (opcode == -1) continue; // 跳过虚节点 (Labels, Frames, LineNumber等)

				int popCount  = getPopCount(insn);
				int pushCount = getPushCount(insn);

				ExprNode node = new ExprNode(insn);
				for (int j = 0; j < popCount; j++) {
					if (!stack.isEmpty()) {
						node.children.add(0, stack.pop()); // 逆序挂载子表达式
					}
				}

				// 判定是否是目标字段的写入
				boolean isTargetPut = false;
				if (isStatic && opcode == Opcodes.PUTSTATIC) {
					FieldInsnNode f = (FieldInsnNode) insn;
					isTargetPut = targetFields.contains(f.name);
				} else if (!isStatic && opcode == Opcodes.PUTFIELD) {
					FieldInsnNode f = (FieldInsnNode) insn;
					isTargetPut = targetFields.contains(f.name);
				}

				if (isTargetPut) {
					// 临时收集当前字段赋值所关联的所有前置指令
					Set<AbstractInsnNode> tempCollected = new HashSet<>();
					node.collect(tempCollected);

					// 审查这些指令中是否包含局部变量读写
					boolean hasIllegalVar = false;
					for (AbstractInsnNode collectedInsn : tempCollected) {
						if (collectedInsn instanceof VarInsnNode v) {
							if (isStatic) {
								// 静态字段初始化：绝对不能包含任何局部变量操作
								hasIllegalVar = true;
								break;
							} else {
								// 实例字段初始化：只允许 ALOAD 0 (即获取 this/instance 引用)，其他一律视为非法
								if (v.getOpcode() != Opcodes.ALOAD || v.var != 0) {
									hasIllegalVar = true;
									break;
								}
							}
						}
						// IINC 指令也是对局部变量的操作，直接判定为非法
						if (collectedInsn.getOpcode() == Opcodes.IINC) {
							hasIllegalVar = true;
							break;
						}
					}

					// 3. 只有完全不依赖外部局部变量的纯净赋值，我们才将其安全加入补丁包
					if (!hasIllegalVar) {
						safeCollected.addAll(tempCollected);
					} else {
						// 打印一条温和的警告，说明该字段因为复杂的局部变量依赖被安全忽略
						FieldInsnNode f = (FieldInsnNode) insn;
						HotSwapAgent.log("Field '" + f.name + "' initialization skipped: depends on local variables.");
					}
				}

				for (int j = 0; j < pushCount; j++) {
					stack.push(node);
				}
			}
		} catch (Throwable e) {
			// 发生任意回溯分析异常，退回到安全状态，防止热重载机制本身崩溃
			HotSwapAgent.error("Analysis stack failed, fallback to empty: " + e.getMessage());
			return Collections.emptyList();
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
	/**
	 * 内部辅助类：微型表达式树节点（用于追踪数据流向）
	 */
	private static class ExprNode {
		final AbstractInsnNode insn;
		final List<ExprNode>   children = new ArrayList<>();

		ExprNode(AbstractInsnNode insn) {
			this.insn = insn;
		}

		void collect(Set<AbstractInsnNode> out) {
			out.add(insn);
			for (ExprNode child : children) {
				child.collect(out);
			}
		}
	}

	// ==================== JVM 栈计算映射表 ====================
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
			case Opcodes.DUP_X2 -> 4;
			case Opcodes.DUP2 -> 4;
			case Opcodes.SWAP -> 2;
			case Opcodes.IADD, Opcodes.LADD, Opcodes.FADD, Opcodes.DADD, Opcodes.ISUB, Opcodes.LSUB, Opcodes.FSUB,
			     Opcodes.DSUB, Opcodes.IMUL, Opcodes.LMUL, Opcodes.FMUL, Opcodes.DMUL, Opcodes.IDIV, Opcodes.LDIV,
			     Opcodes.FDIV, Opcodes.DDIV, Opcodes.IREM, Opcodes.LREM, Opcodes.FREM, Opcodes.DREM, Opcodes.INEG,
			     Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG, Opcodes.ISHL, Opcodes.LSHL, Opcodes.ISHR, Opcodes.LSHR,
			     Opcodes.IUSHR, Opcodes.LUSHR, Opcodes.IAND, Opcodes.LAND, Opcodes.IOR, Opcodes.LOR, Opcodes.IXOR,
			     Opcodes.LXOR -> 1;
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
}
