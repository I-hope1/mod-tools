package nipx.uihook;

import arc.Core;
import arc.scene.ui.layout.*;
import nipx.Injector;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.tree.*;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;

import static nipx.AnnotationTransformer.internalName;
import static nipx.HotSwapAgent.*;
import static org.objectweb.asm.Opcodes.*;

/** Cell 属性追踪器 */
public class CellPropertyRef {

	public static final String CL_TABLE = "arc/scene/ui/layout/Table";
	public static final String CL_CELL = "arc/scene/ui/layout/Cell";
	//region 数据结构
	public record PropertyCall(String method, String desc, Object[] args, int line) { }

	public record CellIdentity(String hostClass, String hostMethod, String hostDesc, int sequence) { }
	//endregion

	//region 全局状态
	/** Cell → 唯一标识（使用 WeakHashMap，防止 Cell 内存泄漏） */
	private static final Map<Cell<?>, CellIdentity> cellToId = new WeakHashMap<>();

	/** 唯一标识 → Cell 弱引用（实现 O(1) 级别的高性能反向查找） */
	private static final Map<CellIdentity, WeakReference<Cell<?>>> idToCell = new ConcurrentHashMap<>();

	/** 标识 → 当前属性调用列表 */
	private static final Map<CellIdentity, List<PropertyCall>> records = new ConcurrentHashMap<>();

	/** 宿主类 → 该类的所有 Cell 标识 */
	private static final Map<String, List<CellIdentity>> classToCells = new ConcurrentHashMap<>();

	/** 每个 (宿主类, 方法, 描述符) 的序号计数器 */
	private static final Map<String, int[]> methodCounters = new ConcurrentHashMap<>();

	/** 是否启用 */
	private static volatile boolean enabled = false;
	//endregion

	//region 运行时记录
	public static void registerCell(Cell<?> cell, String hostClass, String hostMethod, String hostDesc) {
		if (!enabled || cell == null) return;

		String key = hostClass + "#" + hostMethod + ":" + hostDesc;
		int    seq;
		synchronized (methodCounters) {
			int[] counter = methodCounters.computeIfAbsent(key, _ -> new int[]{0});
			seq = counter[0]++;
		}

		CellIdentity id = new CellIdentity(hostClass, hostMethod, hostDesc, seq);

		synchronized (cellToId) {
			cellToId.put(cell, id);
		}
		idToCell.put(id, new WeakReference<>(cell));

		classToCells.computeIfAbsent(hostClass, _ -> new CopyOnWriteArrayList<>()).add(id);

		if (DEBUG) {
			log("[CellProperty] Registered " + id.hostClass + "#"
			    + id.hostMethod + "[" + id.sequence + "]");
		}
	}

	public static void recordPropertyCall(Cell<?> cell, String method, String desc, Object[] args) {
		if (!enabled || cell == null) return;

		CellIdentity id;
		synchronized (cellToId) {
			id = cellToId.get(cell);
		}
		if (id == null) {
			// 未注册的 Cell — 尝试栈推断
			id = inferCellIdentity(cell);
			if (id == null) return;

			synchronized (cellToId) {
				cellToId.put(cell, id);
			}
			idToCell.put(id, new WeakReference<>(cell));

			// 将推断出的 id 注册进宿主类的 Cell 列表中
			classToCells.computeIfAbsent(id.hostClass, _ -> new CopyOnWriteArrayList<>()).add(id);
		}

		int line = -1;
		if (DEBUG) {
			line = inferLineNumber();
		}

		PropertyCall call = new PropertyCall(method, desc, args, line);
		records.computeIfAbsent(id, _ -> new CopyOnWriteArrayList<>()).add(call);

		if (DEBUG) {
			log("[CellProperty] " + id.hostClass + "#" + id.hostMethod + "[" + id.sequence
			    + "] → " + method + "(" + argsString(args) + ")");
		}
	}
	//endregion

	//region 热替换回调
	public static void onClassRedefined(String dotClassName, byte[] newBytecode) {
		if (!enabled) return;

		String slashName = dotClassName.replace('.', '/');
		info("[CellProperty] Class redefined: " + dotClassName);

		List<CellIdentity> cellIds = classToCells.get(slashName);
		if (cellIds == null || cellIds.isEmpty()) {
			if (DEBUG) log("[CellProperty] ℹ No tracked Cells for " + dotClassName);
			return;
		}

		// 提取新字节码中的 Cell 调用链
		Map<String, List<List<PropertyCall>>> newChainsByMethod = extractCellChains(newBytecode);

		int totalUpdated = 0;
		int totalSkipped = 0;

		for (CellIdentity id : cellIds) {
			Cell<?> cell = findCellById(id);
			if (cell == null) {
				totalSkipped++;
				continue;
			}

			String                   methodKey = id.hostMethod + ":" + id.hostDesc;
			List<List<PropertyCall>> newChains = newChainsByMethod.get(methodKey);

			// 模糊匹配：匹配首个以方法名开头的方法链（忽略参数签名差异）
			if (newChains == null) {
				for (Map.Entry<String, List<List<PropertyCall>>> entry : newChainsByMethod.entrySet()) {
					if (entry.getKey().startsWith(id.hostMethod + ":")) {
						newChains = entry.getValue();
						break;
					}
				}
			}

			if (newChains == null || id.sequence >= newChains.size()) {
				totalSkipped++;
				continue;
			}

			List<PropertyCall> newCalls = newChains.get(id.sequence);
			List<PropertyCall> oldCalls = records.get(id);

			if (oldCalls == null) {
				applyAllCalls(cell, newCalls);
				totalUpdated++;
			} else {
				boolean applied = applyDiff(cell, oldCalls, newCalls);
				if (applied) totalUpdated++;
			}
			Core.app.post(cell.get()::invalidateHierarchy);

			records.put(id, newCalls);
		}

		info("[CellProperty] Updated " + totalUpdated + " Cells, skipped "
		     + totalSkipped + " for " + dotClassName);
	}
	//endregion

	//region 属性回放
	private static void applyAllCalls(Cell<?> cell, List<PropertyCall> calls) {
		for (PropertyCall call : calls) {
			invokeCellMethod(cell, call.method, call.args);
		}
	}

	private static boolean applyDiff(Cell<?> cell, List<PropertyCall> oldCalls, List<PropertyCall> newCalls) {
		boolean changed = false;
		int     minLen  = Math.min(oldCalls.size(), newCalls.size());

		for (int i = 0; i < minLen; i++) {
			PropertyCall oldCall = oldCalls.get(i);
			PropertyCall newCall = newCalls.get(i);

			if (!oldCall.method.equals(newCall.method)) {
				if (hasUsableArgs(newCall)) {
					invokeCellMethod(cell, newCall.method, newCall.args);
					changed = true;
					if (DEBUG) log(" " + oldCall.method + " → " + newCall.method);
				}
			} else if (!argsEqual(oldCall.args, newCall.args)) {
				if (hasUsableArgs(newCall)) {
					invokeCellMethod(cell, newCall.method, newCall.args);
					changed = true;
					if (DEBUG) log(" " + newCall.method + " args changed");
				}
			}
		}

		if (newCalls.size() > oldCalls.size()) {
			for (int i = oldCalls.size(); i < newCalls.size(); i++) {
				PropertyCall call = newCalls.get(i);
				if (hasUsableArgs(call)) {
					invokeCellMethod(cell, call.method, call.args);
					changed = true;
					if (DEBUG) log(" new call: " + call.method);
				}
			}
		}

		return changed;
	}

	private static boolean argsEqual(Object[] a, Object[] b) {
		if (a == b) return true;
		if (a == null || b == null) return false;
		if (a.length != b.length) return false;
		for (int i = 0; i < a.length; i++) {
			if (!Objects.equals(a[i], b[i])) return false;
		}
		return true;
	}

	private static boolean hasUsableArgs(PropertyCall call) {
		if (call.args == null) return false;
		for (Object arg : call.args) {
			if (arg == null) return false;
		}
		return true;
	}

	private static void invokeCellMethod(Cell<?> cell, String methodName, Object[] args) {
		if (args == null) args = new Object[0];

		try {
			Method method = findMatchingMethod(methodName, args.length);
			if (method == null) {
				error("[CellProperty] No matching method: " + methodName
				      + "(" + args.length + " args)");
				return;
			}

			Object[] converted = convertArgs(method.getParameterTypes(), args);
			method.invoke(cell, converted);

		} catch (Exception e) {
			error("[CellProperty] Failed to invoke " + methodName, e);
		}
	}

	private static Method findMatchingMethod(String name, int paramCount) {
		for (Method m : Cell.class.getMethods()) {
			if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
				return m;
			}
		}
		for (Method m : Table.class.getMethods()) {
			if (m.getName().equals(name) && m.getParameterCount() == paramCount
			    && Cell.class.isAssignableFrom(m.getReturnType())) {
				return m;
			}
		}
		return null;
	}

	private static Object[] convertArgs(Class<?>[] paramTypes, Object[] args) {
		Object[] result = new Object[args.length];
		for (int i = 0; i < args.length; i++) {
			if (args[i] == null) {
				result[i] = null;
				continue;
			}
			Class<?> target = paramTypes[i];
			if (target == float.class) {
				result[i] = ((Number) args[i]).floatValue();
			} else if (target == int.class) {
				result[i] = ((Number) args[i]).intValue();
			} else if (target == boolean.class || target == Boolean.class) {
				if (args[i] instanceof Number) {
					result[i] = ((Number) args[i]).intValue() != 0;
				} else {
					result[i] = args[i];
				}
			} else if (target == String.class) {
				result[i] = args[i].toString();
			} else {
				result[i] = args[i];
			}
		}
		return result;
	}
	//endregion

	//region ASM 字节码分析
	private static final Set<String> CELL_PROPERTY_METHODS = new HashSet<>(Arrays.asList(
	 "size", "width", "height",
	 "minSize", "minWidth", "minHeight",
	 "maxSize", "maxWidth", "maxHeight",
	 "pad", "padTop", "padLeft", "padBottom", "padRight",
	 "fill", "fillX", "fillY",
	 "align", "center", "top", "left", "bottom", "right",
	 "grow", "growX", "growY",
	 "expand", "expandX", "expandY",
	 "colspan",
	 "uniform", "uniformX", "uniformY",
	 "color",
	 "margin", "marginTop", "marginLeft", "marginBottom", "marginRight",
	 "name", "disabled", "touchable", "visible", "scaling",
	 "wrap", "ellipsis", "labelAlign", "fontScale",
	 "scrollX", "scrollY", "maxTextLength", "valid",
	 "tooltip", "style", "checked"
	));

	private static final Set<String> TABLE_CELL_CREATORS = new HashSet<>(Arrays.asList(
	 "add", "button", "image", "label", "textButton",
	 "imageButton", "area", "table", "pane", "stack",
	 "toggleButton", "imageTextButton", "checkBox", "slider",
	 "textField", "selectBox", "list", "tree"
	));

	public static Map<String, List<List<PropertyCall>>> extractCellChains(byte[] bytecode) {
		Map<String, List<List<PropertyCall>>> result = new HashMap<>();
		if (bytecode == null) return result;

		try {
			ClassReader cr = new ClassReader(bytecode);
			ClassNode   cn = new ClassNode();
			cr.accept(cn, ClassReader.EXPAND_FRAMES);

			for (MethodNode mn : cn.methods) {
				List<List<PropertyCall>> chains = extractFromMethod(cn, mn);
				if (!chains.isEmpty()) {
					result.put(mn.name + ":" + mn.desc, chains);
				}
			}
		} catch (Exception e) {
			error("[CellProperty] Failed to extract chains from bytecode", e);
		}

		return result;
	}

	private static List<List<PropertyCall>> extractFromMethod(ClassNode cn, MethodNode mn) {
		List<List<PropertyCall>> chains = new ArrayList<>();
		if (mn.instructions == null) return chains;

		List<PropertyCall> currentChain = null;
		boolean            inCell       = false;

		for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode mi) {

				if (isTableCellCreator(mi.owner, mi.name)) {
					if (currentChain != null && !currentChain.isEmpty()) {
						chains.add(currentChain);
					}
					currentChain = new ArrayList<>();
					inCell = true;
				} else if (inCell && isCellProperty(mi.owner, mi.name)) {
					Object[] args = extractArgs(cn, mn, mi);
					currentChain.add(new PropertyCall(mi.name, mi.desc, args, -1));
				} else {
					if (inCell && !currentChain.isEmpty()) {
						chains.add(currentChain);
					}
					currentChain = null;
					inCell = false;
				}
			}
		}

		if (currentChain != null && !currentChain.isEmpty()) {
			chains.add(currentChain);
		}

		return chains;
	}

	private static boolean isTableCellCreator(String owner, String name) {
		return CL_TABLE.equals(owner) && TABLE_CELL_CREATORS.contains(name);
	}

	private static boolean isCellProperty(String owner, String name) {
		if (CL_CELL.equals(owner) && CELL_PROPERTY_METHODS.contains(name)) {
			return true;
		}
		if (CL_TABLE.equals(owner) && CELL_PROPERTY_METHODS.contains(name)) {
			return true;
		}
		return false;
	}

	private static int getPushes(AbstractInsnNode insn) {
		int opcode = insn.getOpcode();
		if (opcode == ACONST_NULL) return 1;
		if (opcode >= ICONST_M1 && opcode <= DCONST_1) return 1; // 修复：扩大至 DCONST_1，正确支持 FCONST 和 DCONST
		if (opcode == BIPUSH || opcode == SIPUSH || opcode == LDC) return 1;
		if (opcode >= ILOAD && opcode <= ALOAD) return 1;
		if (opcode >= IADD && opcode <= DREM) return 1;
		if (opcode >= ISHL && opcode <= LXOR) return 1;
		if (opcode >= INEG && opcode <= DNEG) return 1;
		if (opcode >= I2L && opcode <= I2S) return 1;
		if (opcode >= LCMP && opcode <= DCMPG) return 1;
		if (opcode == GETSTATIC) return 1;
		if (opcode == GETFIELD) return 1;
		if (opcode == NEW) return 1;
		if (opcode == DUP) return 1;
		if (opcode == ARRAYLENGTH || opcode == INSTANCEOF) return 1;
		if (opcode >= IALOAD && opcode <= SALOAD) return 1; // 新增支持：数组读取入栈 1
		if (insn instanceof MethodInsnNode mi) {
			Type retType = Type.getReturnType(mi.desc);
			return retType.getSort() == Type.VOID ? 0 : 1;
		}
		if (insn instanceof InvokeDynamicInsnNode indy) {
			Type retType = Type.getReturnType(indy.desc);
			return retType.getSort() == Type.VOID ? 0 : 1;
		}
		return 0;
	}

	private static int getPops(AbstractInsnNode insn) {
		int opcode = insn.getOpcode();
		if (opcode >= ISTORE && opcode <= ASTORE) return 1;
		if (opcode >= IADD && opcode <= DREM) return 2;
		if (opcode >= ISHL && opcode <= LXOR) return 2;
		if (opcode >= INEG && opcode <= DNEG) return 1;
		if (opcode >= I2L && opcode <= I2S) return 1;
		if (opcode >= LCMP && opcode <= DCMPG) return 2;
		if (opcode == GETFIELD) return 1;
		if (opcode == PUTFIELD) return 2;
		if (opcode == PUTSTATIC) return 1;
		if (opcode == DUP) return 1;
		if (opcode == ARRAYLENGTH || opcode == INSTANCEOF || opcode == ATHROW || opcode == MONITORENTER || opcode == MONITOREXIT) {
			return 1;
		}
		if (opcode >= IFEQ && opcode <= IFLE) return 1;
		if (opcode >= IF_ICMPEQ && opcode <= IF_ACMPNE) return 2;
		if (opcode == IFNULL || opcode == IFNONNULL) return 1;
		if (opcode >= IALOAD && opcode <= SALOAD) return 2;   // 新增支持：数组读取消耗 2
		if (opcode >= IASTORE && opcode <= SASTORE) return 3; // 新增支持：数组写入消耗 3
		if (insn instanceof MethodInsnNode mi) {
			int pops = Type.getArgumentTypes(mi.desc).length;
			if (opcode != INVOKESTATIC) {
				pops += 1;
			}
			return pops;
		}
		if (insn instanceof InvokeDynamicInsnNode indy) {
			return Type.getArgumentTypes(indy.desc).length;
		}
		return 0;
	}

	private static AbstractInsnNode skipExpression(AbstractInsnNode insn) {
		if (insn == null) return null;
		int              needed = 1;
		AbstractInsnNode curr   = insn;
		while (curr != null) {
			int pops   = getPops(curr);
			int pushes = getPushes(curr);
			needed -= pushes;
			needed += pops;
			if (needed <= 0) {
				return curr.getPrevious();
			}
			curr = curr.getPrevious();
		}
		return null;
	}

	private static AbstractInsnNode getPrevArgInsn(AbstractInsnNode start, Type[] argTypes, int targetIdx) {
		AbstractInsnNode prev = start;
		for (int i = argTypes.length - 1; i >= 0 && prev != null; i--) {
			if (i == targetIdx) {
				return prev;
			}
			prev = skipExpression(prev);
		}
		return null;
	}

	private static Object[] extractArgs(ClassNode cn, MethodNode mn, MethodInsnNode target) {
		Type[] argTypes = Type.getArgumentTypes(target.desc);
		if (argTypes.length == 0) return new Object[0];

		Object[]    args            = new Object[argTypes.length];
		Set<String> visitingMethods = new HashSet<>();
		for (int i = 0; i < argTypes.length; i++) {
			AbstractInsnNode argInsn = getPrevArgInsn(target.getPrevious(), argTypes, i);
			args[i] = resolveConstant(cn, mn, argInsn, visitingMethods);
		}

		return args;
	}

	private static Object evaluateBinaryOp(int opcode, Object left, Object right) {
		if (left == null || right == null) return null;
		if (!(left instanceof Number l) || !(right instanceof Number r)) return null;

		return switch (opcode) {
			case IADD -> l.intValue() + r.intValue();
			case ISUB -> l.intValue() - r.intValue();
			case IMUL -> l.intValue() * r.intValue();
			case IDIV -> r.intValue() == 0 ? null : l.intValue() / r.intValue();
			case LADD -> l.longValue() + r.longValue();
			case LSUB -> l.longValue() - r.longValue();
			case LMUL -> l.longValue() * r.longValue();
			case LDIV -> r.longValue() == 0L ? null : l.longValue() / r.longValue();
			case FADD -> l.floatValue() + r.floatValue();
			case FSUB -> l.floatValue() - r.floatValue();
			case FMUL -> l.floatValue() * r.floatValue();
			case FDIV -> r.floatValue() == 0.0f ? null : l.floatValue() / r.floatValue();
			case DADD -> l.doubleValue() + r.doubleValue();
			case DSUB -> l.doubleValue() - r.doubleValue();
			case DMUL -> l.doubleValue() * r.doubleValue();
			case DDIV -> r.doubleValue() == 0.0d ? null : l.doubleValue() / r.doubleValue();
			default -> null;
		};
	}

	private static Object evaluateUnaryOp(int opcode, Object val) {
		if (val == null) return null;
		if (!(val instanceof Number n)) return null;
		return switch (opcode) {
			case INEG -> -n.intValue();
			case LNEG -> -n.longValue();
			case FNEG -> -n.floatValue();
			case DNEG -> -n.doubleValue();
			default -> null;
		};
	}

	private static Object resolveConstant(ClassNode cn, MethodNode mn, AbstractInsnNode insn,
	                                      Set<String> visitingMethods) {
		switch (insn) {
			case null -> {
				return null;
			}
			case LdcInsnNode ldcInsnNode -> {
				return ldcInsnNode.cst;
			}
			case InsnNode insnNode -> {
				int opcode = insnNode.getOpcode();
				switch (opcode) {
					case ICONST_M1 -> { return -1; }
					case ICONST_0 -> { return 0; }
					case ICONST_1 -> { return 1; }
					case ICONST_2 -> { return 2; }
					case ICONST_3 -> { return 3; }
					case ICONST_4 -> { return 4; }
					case ICONST_5 -> { return 5; }
					case FCONST_0 -> { return 0.0f; }
					case FCONST_1 -> { return 1.0f; }
					case FCONST_2 -> { return 2.0f; }
					case DCONST_0 -> { return 0.0d; }
					case DCONST_1 -> { return 1.0d; }
					case LCONST_0 -> { return 0L; }
					case LCONST_1 -> { return 1L; }

					// 新增：安全支持基础基本类型的 JVM 强制或隐式数值转换（例如 I2F、I2D）
					case I2L, I2F, I2D, L2I, L2F, L2D, F2I, F2L, F2D, D2I, D2L, D2F, I2B, I2C, I2S -> {
						AbstractInsnNode prev = insn.getPrevious();
						if (prev == null) break;

						Object val = resolveConstant(cn, mn, prev, visitingMethods);
						if (val instanceof Number num) {
							switch (opcode) {
								case I2L, F2L, D2L:
									return num.longValue();
								case I2F, L2F, D2F:
									return num.floatValue();
								case I2D, L2D, F2D:
									return num.doubleValue();
								case L2I, F2I, D2I:
									return num.intValue();
								case I2B:
									return num.byteValue();
								case I2C:
									return (char) num.intValue();
								case I2S:
									return num.shortValue();
							}
						}
					}
					case IADD, ISUB, IMUL, IDIV, LADD, LSUB, LMUL, LDIV, FADD, FSUB, FMUL, FDIV, DADD, DSUB, DMUL, DDIV -> {
						AbstractInsnNode rightInsn = insn.getPrevious();
						if (rightInsn != null) {
							AbstractInsnNode leftInsn = skipExpression(rightInsn);
							Object           rightVal = resolveConstant(cn, mn, rightInsn, visitingMethods);
							Object           leftVal  = resolveConstant(cn, mn, leftInsn, visitingMethods);
							return evaluateBinaryOp(opcode, leftVal, rightVal);
						}
					}
					case INEG, LNEG, FNEG, DNEG -> {
						AbstractInsnNode prev = insn.getPrevious();
						if (prev != null) {
							Object val = resolveConstant(cn, mn, prev, visitingMethods);
							return evaluateUnaryOp(opcode, val);
						}
					}
				}
			}
			default -> { }
		}
		if (insn instanceof IntInsnNode iin && (insn.getOpcode() == BIPUSH || insn.getOpcode() == SIPUSH)) {
			return iin.operand;
		}
		if (insn instanceof VarInsnNode vin) {
			int opcode = vin.getOpcode();
			if (opcode == ILOAD || opcode == FLOAD || opcode == LLOAD || opcode == DLOAD || opcode == ALOAD) {
				return resolveVar(cn, mn, vin.var, visitingMethods);
			}
		}
		return null;
	}

	private static boolean isConstantType(Object val) {
		return val instanceof String || val instanceof Number || val instanceof Boolean || val instanceof Character;
	}

	private static Object resolveVar(ClassNode cn, MethodNode mn, int varIndex, Set<String> visitingMethods) {
		// 修复方案：将检测粒度细化至 槽位级别，防止同一个方法中多级解析常数时误判为无限递归。
		String visitKey = mn.name + ":" + mn.desc + "#" + varIndex;
		if (visitingMethods.contains(visitKey)) {
			return null;
		}
		visitingMethods.add(visitKey);
		tryLabel:
		try {
			int              writes      = 0;
			AbstractInsnNode singleStore = null;
			for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof VarInsnNode vin) {
					int opcode = vin.getOpcode();
					if (opcode == ISTORE || opcode == LSTORE || opcode == FSTORE || opcode == DSTORE || opcode == ASTORE) {
						if (vin.var == varIndex) {
							writes++;
							singleStore = insn;
						}
					}
				} else if (insn instanceof IincInsnNode iin) {
					if (iin.var == varIndex) {
						writes++;
					}
				}
			}

			if (writes == 1 && singleStore != null) {
				AbstractInsnNode prev = singleStore.getPrevious();
				if (prev != null) {
					Object val = resolveConstant(cn, mn, prev, visitingMethods);
					if (isConstantType(val)) {
						return val;
					}
				}
				break tryLabel;
			}
			if (writes != 0) break tryLabel;

			int    localIndex = ((mn.access & ACC_STATIC) != 0) ? 0 : 1;
			Type[] args       = Type.getArgumentTypes(mn.desc);
			int    argIdx     = -1;
			for (int i = 0; i < args.length; i++) {
				if (localIndex == varIndex) {
					argIdx = i;
					break;
				}
				localIndex += args[i].getSize();
			}

			if (argIdx < 0 || (!mn.name.startsWith("lambda$") && !mn.name.contains("$lambda"))) break tryLabel;

			for (MethodNode parentMn : cn.methods) {
				for (AbstractInsnNode insn = parentMn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (!(insn instanceof InvokeDynamicInsnNode indy) || indy.bsmArgs == null || indy.bsmArgs.length <= 1) {
						continue;
					}
					for (Object bsmArg : indy.bsmArgs) {
						if (!(bsmArg instanceof Handle h) || !h.getName().equals(mn.name) || !h.getOwner().equals(cn.name)) {
							continue;
						}
						Type[] indyArgs = Type.getArgumentTypes(indy.desc);
						if (argIdx >= indyArgs.length) continue;

						Object val = extractIndyArg(cn, parentMn, indy, argIdx, visitingMethods);
						if (isConstantType(val)) {
							return val;
						}
					}
				}
			}
		} finally {
			visitingMethods.remove(visitKey);
		}
		return null;
	}

	private static Object extractIndyArg(ClassNode cn, MethodNode mn, InvokeDynamicInsnNode indy, int argIdx,
	                                     Set<String> visitingMethods) {
		Type[]           argTypes = Type.getArgumentTypes(indy.desc);
		AbstractInsnNode argInsn  = getPrevArgInsn(indy.getPrevious(), argTypes, argIdx);
		return resolveConstant(cn, mn, argInsn, visitingMethods);
	}
	//endregion

	//region ASM 注入
	public static void redefineCellProperties() {
		Class<?> cellClass = Cell.class;
		byte[]   bytes     = fetchCurrentBytecode(cellClass);
		if (bytes == null) {
			error("[CellProperty] Cannot fetch Cell bytecode");
			return;
		}

		bytes = CellPropertyRef.injectCell(bytes);
		if (bytes == null) return;

		Injector.redefineOneClass(cellClass, bytes);
		info("[CellProperty] Cell class redefined with property tracking");
	}

	public static byte[] injectCell(byte[] bytes) {
		ClassReader cr = new ClassReader(bytes);
		ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

		ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
			                                 String signature, String[] exceptions) {
				MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

				if (name.startsWith("<")) return mv;
				if (!CELL_PROPERTY_METHODS.contains(name)) return mv;
				if (!descriptor.endsWith(")Larc/scene/ui/layout/Cell;")) return mv;

				return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
					@Override
					protected void onMethodExit(int opcode) {
						if (opcode == ATHROW) return;

						mv.visitVarInsn(ALOAD, 0);
						mv.visitLdcInsn(name);
						mv.visitLdcInsn(descriptor);

						Type[] argTypes = Type.getArgumentTypes(descriptor);
						pushArgsArray(mv, argTypes);

						mv.visitMethodInsn(
						 INVOKESTATIC,
						 internalName(CellPropertyRef.class),
						 "recordPropertyCall",
						 "(Larc/scene/ui/layout/Cell;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)V",
						 false
						);
					}
				};
			}
		};

		try {
			cr.accept(cv, ClassReader.EXPAND_FRAMES);
			return cw.toByteArray();
		} catch (Exception e) {
			error("[CellProperty] Failed to inject Cell class", e);
			return bytes;
		}
	}

	private static void pushArgsArray(MethodVisitor mv, Type[] argTypes) {
		pushInt(mv, argTypes.length);
		mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");

		int localIdx = 1;
		for (int i = 0; i < argTypes.length; i++) {
			mv.visitInsn(DUP);
			pushInt(mv, i);

			Type t = argTypes[i];
			switch (t.getSort()) {
				case Type.INT:
					mv.visitVarInsn(ILOAD, localIdx);
					mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
					localIdx++;
					break;
				case Type.FLOAT:
					mv.visitVarInsn(FLOAD, localIdx);
					mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
					localIdx++;
					break;
				case Type.BOOLEAN:
					mv.visitVarInsn(ILOAD, localIdx);
					mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
					localIdx++;
					break;
				case Type.LONG:
					mv.visitVarInsn(LLOAD, localIdx);
					mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
					localIdx += 2;
					break;
				case Type.DOUBLE:
					mv.visitVarInsn(DLOAD, localIdx);
					mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
					localIdx += 2;
					break;
				default:
					mv.visitVarInsn(ALOAD, localIdx);
					localIdx++;
					break;
			}

			mv.visitInsn(AASTORE);
		}
	}

	private static void pushInt(MethodVisitor mv, int value) {
		if (value >= -1 && value <= 5) {
			mv.visitInsn(ICONST_0 + value);
		} else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
			mv.visitIntInsn(BIPUSH, value);
		} else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
			mv.visitIntInsn(SIPUSH, value);
		} else {
			mv.visitLdcInsn(value);
		}
	}
	//endregion

	//region 辅助方法
	private static Cell<?> findCellById(CellPropertyRef.CellIdentity target) {
		WeakReference<Cell<?>> ref = idToCell.get(target);
		return ref != null ? ref.get() : null;
	}

	private static CellIdentity inferCellIdentity(Cell<?> cell) {
		StackTraceElement[] stack = Thread.currentThread().getStackTrace();
		for (StackTraceElement ste : stack) {
			String cn = ste.getClassName();
			if (cn.startsWith("nipx.") || cn.startsWith("arc.") || cn.startsWith("java.")) continue;
			String slash      = cn.replace('.', '/');
			String hostMethod = ste.getMethodName();

			int seq = -1;
			try {
				Table table = cell.getTable();
				if (table != null) {
					var cells = table.getCells();
					if (cells != null) {
						seq = cells.indexOf(cell, true);
					}
				}
			} catch (Throwable t) {
				// 忽略不同底层框架版本微调导致的异常
			}

			if (seq < 0) {
				String key = slash + "#" + hostMethod + ":()V";
				synchronized (methodCounters) {
					int[] counter = methodCounters.computeIfAbsent(key, _ -> new int[]{0});
					seq = counter[0]++;
				}
			}

			return new CellIdentity(slash, hostMethod, "()V", seq);
		}
		return null;
	}

	private static int inferLineNumber() {
		StackTraceElement[] stack = Thread.currentThread().getStackTrace();
		for (StackTraceElement ste : stack) {
			String cn = ste.getClassName();
			if (!cn.startsWith("nipx.") && !cn.startsWith("arc.") && !cn.startsWith("java.")) {
				return ste.getLineNumber();
			}
		}
		return -1;
	}

	private static String argsString(Object[] args) {
		if (args == null) return "null";
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < args.length; i++) {
			if (i > 0) sb.append(", ");
			sb.append(args[i]);
		}
		return sb.append("]").toString();
	}
	//endregion

	//region 生命周期管理
	public static void enable() {
		enabled = true;
		info("[CellProperty] Enabled");
	}

	public static void disable() {
		enabled = false;
		info("[CellProperty] Disabled");
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static void clearAll() {
		synchronized (cellToId) {
			cellToId.clear();
		}
		idToCell.clear();
		records.clear();
		classToCells.clear();
		methodCounters.clear();
		info("[CellProperty] 🗑 Cleared all records");
	}

	public static void removeCell(Cell<?> cell) {
		CellIdentity id;
		synchronized (cellToId) {
			id = cellToId.remove(cell);
		}
		if (id != null) {
			idToCell.remove(id);
			records.remove(id);
			List<CellIdentity> list = classToCells.get(id.hostClass);
			if (list != null) list.remove(id);
		}
	}

	public static String getStats() {
		int cellToIdSize;
		synchronized (cellToId) {
			cellToIdSize = cellToId.size();
		}
		return "CellPropertyRef:\n"
		       + "  Enabled: " + enabled + "\n"
		       + "  Tracked Cells: " + cellToIdSize + "\n"
		       + "  Records: " + records.size() + "\n"
		       + "  Host Classes: " + classToCells.size();
	}
	//endregion
}