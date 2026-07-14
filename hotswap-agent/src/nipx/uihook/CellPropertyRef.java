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

		classToCells.computeIfAbsent(hostClass, k -> new CopyOnWriteArrayList<>()).add(id);

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

			// 【修复重要 Bug 1】：必须要将推断出的 id 注册进宿主类的 Cell 列表中！
			// 否则在 classRedefined 时该类会被视作“没有任何关联的 Cell”而直接跳过。
			classToCells.computeIfAbsent(id.hostClass, k -> new CopyOnWriteArrayList<>()).add(id);
		}

		int line = -1;
		if (DEBUG) {
			line = inferLineNumber();
		}

		PropertyCall call = new PropertyCall(method, desc, args, line);
		records.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(call);

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

			// 【修复重要 Bug 2】：如果直接匹配描述符失败（比如栈推断写死了 "()V"，实际是带参构造），
			// 我们进行模糊匹配——匹配首个以方法名开头的方法链（忽略参数签名差异）
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
				// 【修复 Bug 3】：ICONST_1 在 ASM 级别被解析成数值，这里强制安全还原为 Boolean
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
				List<List<PropertyCall>> chains = extractFromMethod(mn);
				if (!chains.isEmpty()) {
					result.put(mn.name + ":" + mn.desc, chains);
				}
			}
		} catch (Exception e) {
			error("[CellProperty] Failed to extract chains from bytecode", e);
		}

		return result;
	}

	private static List<List<PropertyCall>> extractFromMethod(MethodNode mn) {
		List<List<PropertyCall>> chains = new ArrayList<>();
		if (mn.instructions == null) return chains;

		List<PropertyCall> currentChain = null;
		boolean            inCell       = false;

		for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode) {
				MethodInsnNode mi = (MethodInsnNode) insn;

				if (isTableCellCreator(mi.owner, mi.name)) {
					if (currentChain != null && !currentChain.isEmpty()) {
						chains.add(currentChain);
					}
					currentChain = new ArrayList<>();
					inCell = true;
				} else if (inCell && isCellProperty(mi.owner, mi.name)) {
					Object[] args = extractArgs(mn.instructions, mi);
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
		return "arc/scene/ui/layout/Table".equals(owner) && TABLE_CELL_CREATORS.contains(name);
	}

	private static boolean isCellProperty(String owner, String name) {
		if ("arc/scene/ui/layout/Cell".equals(owner) && CELL_PROPERTY_METHODS.contains(name)) {
			return true;
		}
		if ("arc/scene/ui/layout/Table".equals(owner) && CELL_PROPERTY_METHODS.contains(name)) {
			return true;
		}
		return false;
	}

	private static Object[] extractArgs(InsnList instructions, MethodInsnNode target) {
		Type[] argTypes = Type.getArgumentTypes(target.desc);
		if (argTypes.length == 0) return new Object[0];

		Object[]         args = new Object[argTypes.length];
		AbstractInsnNode prev = target.getPrevious();

		for (int i = argTypes.length - 1; i >= 0 && prev != null; i--) {
			args[i] = resolveConstant(prev);

			Type t    = argTypes[i];
			int  size = t.getSize();
			for (int s = 1; s < size && prev != null; s++) {
				prev = prev.getPrevious();
			}
			if (prev != null) {
				prev = prev.getPrevious();
			}
		}

		return args;
	}

	private static Object resolveConstant(AbstractInsnNode insn) {
		if (insn instanceof LdcInsnNode) {
			return ((LdcInsnNode) insn).cst;
		}
		if (insn instanceof InsnNode) {
			switch (insn.getOpcode()) {
				case ICONST_M1:
					return -1;
				case ICONST_0:
					return 0;
				case ICONST_1:
					return 1;
				case ICONST_2:
					return 2;
				case ICONST_3:
					return 3;
				case ICONST_4:
					return 4;
				case ICONST_5:
					return 5;
				case FCONST_0:
					return 0.0f;
				case FCONST_1:
					return 1.0f;
				case FCONST_2:
					return 2.0f;
				case DCONST_0:
					return 0.0d;
				case DCONST_1:
					return 1.0d;
				case LCONST_0:
					return 0L;
				case LCONST_1:
					return 1L;
			}
		}
		if (insn instanceof IntInsnNode iin && (insn.getOpcode() == BIPUSH || insn.getOpcode() == SIPUSH)) {
			return iin.operand;
		}
		return null;
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

	/**
	 * 【重大优化 3】：动态精准匹配 Sequence 序号
	 * 结合 Table 物理结构，获取当前 Cell 在其 Table.getCells() 数组中的准确下标（即在 Table 中的创建顺序）。
	 * 如果无法通过 table 获取，则安全回退到计数器递增逻辑。
	 */
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
						// indexOf(element, identity) 快速定位 Cell 的真实创建顺序下标
						seq = cells.indexOf(cell, true);
					}
				}
			} catch (Throwable t) {
				// 忽略不同底层框架版本微调导致的反编译/方法签名异常
			}

			// 如果反射/Table精确查找失败，退化为全局序号生成器
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