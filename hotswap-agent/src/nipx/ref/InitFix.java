package nipx.ref;

import jdk.internal.misc.Unsafe;
import nipx.ClassDiffUtil.ClassDiff;
import nipx.*;
import nipx.jvmti.LibTool;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.tree.analysis.Frame;

import java.lang.invoke.*;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.*;

import static nipx.HotSwapAgent.log;

/**
 * Hotswap 时初始化修复器。
 *
 * <h2>策略</h2>
 * <p>新增字段的初始化表达式如果依赖构造器参数、局部变量、含分支/内联，存量实例不会被初始化。
 * 本类从字节码里反向切片出赋值表达式，生成一个 hidden nestmate class 承载补丁，
 * 在 redefine 之后调用。补丁体是纯直线代码，{@code COMPUTE_MAXS} 足够。</p>
 * <p>整体策略是"宁可拒绝，不可误改"：分支、try/catch 相交、控制依赖、局部变量依赖、
 * 多根构造器不一致、循环依赖，都会拒绝。误补会静默污染对象状态，漏补只是字段保持默认值。</p>
 *
 * <h2>字段写入</h2>
 * <p>所有新增字段的 {@code PUTFIELD}/{@code PUTSTATIC}（final 与非 final 统一）都改写为
 * {@code invokedynamic}，由 {@link HotswapBridge#KIND_CONDITIONAL} 在链接期算好
 * Unsafe offset 并用条件 CAS 写入：仅当字段当前等于该类型默认值才写。这样不会覆盖
 * redefine 之后其他线程赋的新值；代价是字段已是默认值以外时跳过（保守方向）。
 * CAS 本身即 volatile 语义，无需额外 fence。</p>
 *
 * <h2>提取流程</h2>
 * <ol>
 *   <li><b>提取</b>：对每个 {@code <init>}/{@code <clinit>} 里的目标 PUTFIELD/PUTSTATIC，
 *       用 {@link AliasInterpreter} 做反向数据依赖切片，再检查区间内不得有表达式树外的
 *       指令。{@link #checkSafe} 判定控制依赖、try/catch 相交、局部变量依赖、
 *       INVOKESPECIAL/indy handle 的可用性、protected 跨包访问是否可桥接。</li>
 *   <li><b>单字段判定</b>：{@link #fingerprintMismatchReason} 校验多构造器下表达式一致；
 *       根构造器覆盖完整性；参数依赖 + 多根构造器组合被拒绝。</li>
 *   <li><b>闭包迭代</b>：后续加工检查 + 依赖闭包，反复移除不合格字段直到不动点。</li>
 *   <li><b>拓扑排序</b>：{@link #topoSortFields} 按依赖排序，成环则整组拒绝。</li>
 *   <li><b>生成</b>：protected 桥接、私有调用改写、字段写入改写、hidden class 装配。</li>
 * </ol>
 *
 * <h2>构造器参数回溯</h2>
 * <p>Java 里 {@code x = s.get(0)}（{@code s} 是构造器参数）在字节码里是 {@code ALOAD n}
 * 直接读取，不是 {@code GETFIELD}。若 {@code <init>} 里有 {@code ALOAD 0; XLOAD n; PUTFIELD this.f}，
 * 就把提取片段里的加载指令替换为 {@code ALOAD 0; GETFIELD this.f}。</p>
 * <p>安全条件（任一不满足即弃用该映射）：</p>
 * <ul>
 *   <li><b>pattern 无条件执行</b>：复用 {@link #checkControlDependency}。</li>
 *   <li><b>参数类型与字段描述符一致</b>：避免替换后栈类型不匹配 VerifyError。</li>
 *   <li><b>槽位单赋值</b>：pattern 前后都不得被 xSTORE 覆盖或 IINC 自增（long/double 占 2 槽）。</li>
 *   <li><b>字段不被二次写入</b>：pattern 之后同一字段不得再被 PUTFIELD。</li>
 * </ul>
 *
 * <h2>已知限制</h2>
 * <ul>
 *   <li><b>参数委托给父类构造器</b>：{@code Sub(samples) : Base(samples)} 里 PUTFIELD 在父类，
 *       子类看不到。</li>
 *   <li><b>Kotlin 防御式写法与 inline 函数</b>：{@code ?.}/{@code ?:}/{@code sumOf}
 *       展开为跳转与局部临时变量，被 checkSafe 拒绝。</li>
 *   <li><b>运行期状态变迁</b>：GETFIELD 读到的是当前值，不是构造时的值。</li>
 *   <li><b>间接依赖</b>：依赖检查只看提取片段里的直接 GETFIELD/GETSTATIC。
 *       若 {@code a = compute()} 而 {@code compute()} 读了被拒绝的新增字段 b，
 *       a 会静默拿到 b 的默认值。</li>
 *   <li><b>this 逃逸</b>：{@code names = new ArrayList<>(); init();} 中 init 可能通过
 *       this 访问 names 并加工，静态上看不出来。</li>
 *   <li><b>跨实例字段覆写检测</b>：{@link #scanParamFields} 对同名字段的二次 PUTFIELD
 *       一律视为覆写，不区分 receiver。</li>
 *   <li><b>static final 的 JIT 常量折叠</b>：redefine 到补丁执行之间，若新方法恰好被
 *       JIT 编译，static final 的默认值可能被常量折叠。</li>
 *   <li><b>逐实例补丁非原子</b>：循环里抛异常的实例处于部分初始化状态，日志能看到，
 *       但没有标记。</li>
 * </ul>
 *
 * <p>实例快照：由框架在 {@link #transform(Class, byte[], ClassDiff)} 里做一次，
 * 可在 redefine 前通过 {@link #beforeRedefine(Class)} 覆盖。快照窗口只是被缩小、
 * 没有消失。暂存键直接用 {@code Class<?>}，弱引用持有。</p>
 *
 * <h2>报告</h2>
 * <p>{@link #buildPatch} 对每个 {@code addedInstanceFields}/{@code addedStaticFields} 里的
 * 字段都生成一条 {@link FieldDecision}，包括在第一阶段提取就被拒（未进入
 * {@code instanceExtracts}/{@code staticExtracts}）的字段——它们的决策是
 * {@code "no safe initialization expression found in ..."}。</p>
 */
public class InitFix {
	private static final String STATIC_PATCH_METHOD   = "initStatic";
	private static final String INSTANCE_PATCH_METHOD = "initInstance";
	private static final String PATCH_SUFFIX          = "$$HotswapPatch";

	private static final long PENDING_TTL_NANOS = TimeUnit.MINUTES.toNanos(5);
	private static final int  MAX_DETAILED_FAILURES = 5;

	private static final Unsafe UNSAFE = Unsafe.getUnsafe();

	private static final Map<Class<?>, PendingPatch> PENDING =
	 Collections.synchronizedMap(new WeakHashMap<>());

	/**
	 * 最近一次 {@link #transform} 的报告，按宿主类弱键保存。
	 * <p>{@link PatchReport} 本身不持有宿主引用，避免 value 强引用 key
	 * 导致 WeakHashMap 永不回收。</p>
	 */
	private static final Map<Class<?>, PatchReport> REPORTS =
	 Collections.synchronizedMap(new WeakHashMap<>());

	/**
	 * 统一的补丁 bridge bootstrap：{@link HotswapBridge} 按 {@code kind} 分派到
	 * protected 成员桥接或新增字段的条件 CAS 写。
	 * <p>bsmArgs 布局：{@code [int kind, int opcode, Class owner, Class host]}。</p>
	 */
	private static final Handle BRIDGE_BSM = new Handle(
	 Opcodes.H_INVOKESTATIC,
	 Type.getInternalName(HotswapBridge.class),
	 "bootstrap",
	 "(Ljava/lang/invoke/MethodHandles$Lookup;"
	 + "Ljava/lang/String;"
	 + "Ljava/lang/invoke/MethodType;"
	 + "I"
	 + "I"
	 + "Ljava/lang/Class;"
	 + "Ljava/lang/Class;"
	 + ")Ljava/lang/invoke/CallSite;",
	 false);

	private record BuiltPatch(byte[] bytes, boolean hasStatic, boolean hasInstance, PatchReport report) {
		boolean hasPatch() { return bytes != null; }
	}

	private record PendingPatch(
	 byte[] bytes, boolean hasStatic, boolean hasInstance,
	 List<WeakReference<Object>> instanceSnapshot,
	 long createdNanos) {

		PendingPatch {
			if (hasInstance && instanceSnapshot == null) {
				throw new IllegalStateException(
				 "hasInstance requires a non-null instance snapshot");
			}
		}

		PendingPatch withSnapshot(List<WeakReference<Object>> snapshot) {
			return new PendingPatch(
			 bytes, hasStatic, hasInstance,
			 List.copyOf(snapshot),
			 System.nanoTime());
		}
	}

	private record ProtectedAccess(int opcode, String owner, String name, String desc) { }

	private record FieldExtract(
	 List<AbstractInsnNode> instructions,
	 Map<AbstractInsnNode, ProtectedAccess> protectedAccesses,
	 boolean dependsOnParam,
	 boolean fromRootCtor,
	 Set<AbstractInsnNode> originalInsns) { }

	private record ParamField(FieldNode field, int slot) { }

	/** 单个字段的放行决策。 */
	public record FieldDecision(boolean accepted, String reason) {
		public static final FieldDecision ACCEPTED = new FieldDecision(true, null);
		public static FieldDecision rejected(String reason) {
			return new FieldDecision(false, reason);
		}
	}

	/**
	 * 一次 transform 的完整报告。
	 * <p>不持有宿主类引用，避免与 {@link #REPORTS} 的弱键形成强引用循环。</p>
	 */
	public record PatchReport(
	 Map<String, FieldDecision> instanceFields,
	 Map<String, FieldDecision> staticFields,
	 boolean patchGenerated
	) { }

	public static PatchReport getLastReport(Class<?> host) {
		if (host == null) return null;
		return REPORTS.get(host);
	}

	public static void transform(Class<?> host, byte[] newBytes, ClassDiff diff) {
		if (!HotSwapAgent.HOTSWAP_PLUS) return;

		PENDING.remove(host);
		cleanupStalePatches();

		Set<String> addedStaticFields   = new HashSet<>();
		Set<String> addedInstanceFields = new HashSet<>();
		for (String change : diff.changedFields) {
			if (change.startsWith("+ *")) {
				addedStaticFields.add(change.substring(3));
			} else if (change.startsWith("+ ")) {
				addedInstanceFields.add(change.substring(2));
			}
		}

		if (addedStaticFields.isEmpty() && addedInstanceFields.isEmpty()) {
			REPORTS.put(host, new PatchReport(Map.of(), Map.of(), false));
			return;
		}

		String className = diff.newClass.name;
		try {
			BuiltPatch built = buildPatch(host, newBytes, className,
			                              addedStaticFields, addedInstanceFields);
			if (built == null) return;

			REPORTS.put(host, built.report());
			if (!built.hasPatch()) return;

			List<WeakReference<Object>> snapshot =
			 built.hasInstance() ? snapshotInstances(host) : null;

			PendingPatch patch = new PendingPatch(
			 built.bytes(), built.hasStatic(), built.hasInstance(),
			 snapshot, System.nanoTime());
			PENDING.put(host, patch);
		} catch (Throwable e) {
			HotSwapAgent.error("Field init patch generation failed for " + className
			                   + ": " + e.getMessage(), e);
		}
	}

	public static void beforeRedefine(Class<?> clazz) {
		if (!HotSwapAgent.HOTSWAP_PLUS || clazz == null) return;

		PendingPatch existing = PENDING.get(clazz);
		if (existing == null || !existing.hasInstance()) return;

		List<WeakReference<Object>> snapshot = snapshotInstances(clazz);
		PENDING.computeIfPresent(clazz, (k, p) -> p.withSnapshot(snapshot));
	}

	private static void cleanupStalePatches() {
		if (PENDING.isEmpty()) return;
		long now = System.nanoTime();
		synchronized (PENDING) {
			PENDING.entrySet().removeIf(e -> {
				Class<?> k = e.getKey();
				if (k == null) return true;
				long age = now - e.getValue().createdNanos();
				if (age > PENDING_TTL_NANOS) {
					log("Dropping stale pending field init patch for " + k.getName()
					    + ", age=" + TimeUnit.NANOSECONDS.toSeconds(age) + "s");
					return true;
				}
				return false;
			});
		}
	}

	private static BuiltPatch buildPatch(
	 Class<?> host, byte[] newBytes, String className,
	 Set<String> addedStaticFields, Set<String> addedInstanceFields) {

		ClassNode newClass = new ClassNode();
		new ClassReader(newBytes).accept(newClass, 0);
		int hostVersion = newClass.version;

		Map<String, FieldNode> fieldNodes = new HashMap<>();
		for (FieldNode fn : newClass.fields) {
			fieldNodes.put(fn.name, fn);
		}

		Set<String> privateMethods = new HashSet<>();
		for (MethodNode mn : newClass.methods) {
			if ((mn.access & Opcodes.ACC_PRIVATE) != 0) {
				privateMethods.add(mn.name + mn.desc);
			}
		}

		List<MethodNode> initMethods = newClass.methods.stream()
		 .filter(m -> "<init>".equals(m.name))
		 .toList();
		MethodNode clinitMethod = newClass.methods.stream()
		 .filter(m -> "<clinit>".equals(m.name) && "()V".equals(m.desc))
		 .findFirst().orElse(null);

		Map<MethodNode, Boolean> rootCtorCache = new IdentityHashMap<>();
		int rootCtorCount = 0;
		for (MethodNode init : initMethods) {
			if (isRootConstructor(newClass, init, rootCtorCache)) rootCtorCount++;
		}
		if (rootCtorCount > 1) {
			log("Class " + className + " has " + rootCtorCount
			    + " independent root constructors");
		}

		// ==================== 实例字段提取 ====================
		Map<String, List<FieldExtract>> instanceExtracts = new LinkedHashMap<>();
		for (MethodNode init : initMethods) {
			log("Extracting field init for " + className + "." + init.name + "()");
			boolean fromRoot = isRootConstructor(newClass, init, rootCtorCache);
			Map<Integer, ParamField> paramFields = scanParamFields(newClass, init);
			if (!paramFields.isEmpty()) {
				StringBuilder slots = new StringBuilder();
				for (Map.Entry<Integer, ParamField> en : paramFields.entrySet()) {
					if (slots.length() > 0) slots.append(", ");
					slots.append(en.getKey()).append("->").append(en.getValue().field().name);
				}
				log("Constructor param->field mapping in " + className
				    + "." + init.name + "(): " + slots);
			}
			Map<String, FieldExtract> perField = extractFieldInits(
			 host, className, init, addedInstanceFields, false, privateMethods,
			 paramFields, fromRoot);

			for (Map.Entry<String, FieldExtract> fe : perField.entrySet()) {
				instanceExtracts
				 .computeIfAbsent(fe.getKey(), x -> new ArrayList<>())
				 .add(fe.getValue());
			}
		}

		// ==================== 静态字段提取 ====================
		Map<String, List<FieldExtract>> staticExtracts = new LinkedHashMap<>();
		if (clinitMethod != null) {
			Map<String, FieldExtract> perField = extractFieldInits(
			 host, className, clinitMethod, addedStaticFields,
			 true, privateMethods, Map.of(), true);
			for (Map.Entry<String, FieldExtract> fe : perField.entrySet()) {
				staticExtracts
				 .computeIfAbsent(fe.getKey(), x -> new ArrayList<>())
				 .add(fe.getValue());
			}
		}
		// ConstantValue 静态字段：无需经过 <clinit> 解析，直接生成 LDC + PUTSTATIC 提取片段，
		// 提前进入 staticExtracts，避免依赖闭包误杀依赖该常量的其他字段，并保证拓扑排序优先输出。
		for (FieldNode field : newClass.fields) {
			if ((field.access & Opcodes.ACC_STATIC) != 0
			    && addedStaticFields.contains(field.name)
			    && field.value != null
			    && !staticExtracts.containsKey(field.name)) {
				List<AbstractInsnNode> insns = List.of(
				 new LdcInsnNode(field.value),
				 new FieldInsnNode(Opcodes.PUTSTATIC, className, field.name, field.desc)
				);
				staticExtracts.computeIfAbsent(field.name, x -> new ArrayList<>())
				 .add(new FieldExtract(insns, Map.of(), false, false, Set.copyOf(insns)));
				log("Extracted constant field init from ConstantValue: "
				    + className + "." + field.name);
			}
		}

		List<MethodNode> staticScanMethods = new ArrayList<>(initMethods);
		if (clinitMethod != null) staticScanMethods.add(clinitMethod);

		// ==================== 阶段 1：单字段判定（实例） ====================
		// 先把所有待处理字段标记为拒绝；有 extract 的会在此后覆盖为 ACCEPTED 或被具体原因拒绝。
		// 这样即便某字段在 extractFieldInits 里就被拒（不进 instanceExtracts），
		// PatchReport 里依然有它的一条决策记录。
		Map<String, FieldDecision> instanceDecisions = new LinkedHashMap<>();
		for (String f : addedInstanceFields) {
			instanceDecisions.put(f, FieldDecision.rejected(
			 "no safe initialization expression found in constructors"));
		}
		Set<String> acceptedInstance = new LinkedHashSet<>();
		for (Map.Entry<String, List<FieldExtract>> e : instanceExtracts.entrySet()) {
			String fieldName = e.getKey();
			List<FieldExtract> extracts = e.getValue();

			int fromRootCount = 0;
			boolean anyDependsOnParam = false;
			for (FieldExtract fe : extracts) {
				if (fe.fromRootCtor()) fromRootCount++;
				if (fe.dependsOnParam()) anyDependsOnParam = true;
			}

			String refuseReason = null;
			if (fromRootCount == 0) {
				refuseReason = "field is only initialized in delegating constructors";
			} else if (rootCtorCount > 1 && fromRootCount < rootCtorCount) {
				refuseReason = "field is only initialized in " + fromRootCount
				             + " of " + rootCtorCount + " root constructors";
			} else if (anyDependsOnParam && rootCtorCount > 1) {
				refuseReason = "depends on constructor parameters and class has "
				             + rootCtorCount + " independent root constructors";
			} else {
				refuseReason = fingerprintMismatchReason(extracts);
			}

			if (refuseReason != null) {
				log("Field '" + fieldName + "' appears in " + extracts.size()
				    + " constructors (" + fromRootCount + " root) of " + className
				    + "; refusing patch: " + refuseReason);
				instanceDecisions.put(fieldName, FieldDecision.rejected(refuseReason));
			} else {
				acceptedInstance.add(fieldName);
				instanceDecisions.put(fieldName, FieldDecision.ACCEPTED);
			}
		}

		// ==================== 静态字段：接受 clinit 的提取结果 ====================
		// 同上：先全部标记拒绝，再让 extract 命中的字段覆盖。
		Map<String, FieldDecision> staticDecisions = new LinkedHashMap<>();
		for (String f : addedStaticFields) {
			staticDecisions.put(f, FieldDecision.rejected(
			 "no safe initialization expression found in <clinit>"));
		}
		Set<String> acceptedStatic = new LinkedHashSet<>();
		for (String f : staticExtracts.keySet()) {
			acceptedStatic.add(f);
			staticDecisions.put(f, FieldDecision.ACCEPTED);
		}

		// ==================== 阶段 1.5 + 阶段 2：闭包迭代 ====================
		boolean changed = true;
		while (changed) {
			changed = false;

			for (String f : new ArrayList<>(acceptedInstance)) {
				String reason = subsequentProcessingReason(
				 f, acceptedInstance, instanceExtracts, initMethods,
				 className, false, fieldNodes.get(f));
				if (reason != null) {
					log("Field '" + f + "' refused (subsequent processing): " + reason);
					acceptedInstance.remove(f);
					instanceDecisions.put(f, FieldDecision.rejected("subsequent processing: " + reason));
					changed = true;
				}
			}

			for (String f : new ArrayList<>(acceptedStatic)) {
				String reason = subsequentProcessingReason(
				 f, acceptedStatic, staticExtracts, staticScanMethods,
				 className, true, fieldNodes.get(f));
				if (reason != null) {
					log("Static field '" + f + "' refused (subsequent processing): " + reason);
					acceptedStatic.remove(f);
					staticDecisions.put(f, FieldDecision.rejected("subsequent processing: " + reason));
					changed = true;
				}
			}

			for (String f : new ArrayList<>(acceptedInstance)) {
				String reason = depReason(
				 f, instanceExtracts.get(f), className,
				 acceptedInstance, acceptedStatic,
				 addedInstanceFields, addedStaticFields);
				if (reason != null) {
					log("Field '" + f + "' refused (dependency): " + reason);
					acceptedInstance.remove(f);
					instanceDecisions.put(f, FieldDecision.rejected("dependency: " + reason));
					changed = true;
				}
			}

			for (String f : new ArrayList<>(acceptedStatic)) {
				String reason = depReason(
				 f, staticExtracts.get(f), className,
				 null, acceptedStatic,
				 null, addedStaticFields);
				if (reason != null) {
					log("Static field '" + f + "' refused (dependency): " + reason);
					acceptedStatic.remove(f);
					staticDecisions.put(f, FieldDecision.rejected("dependency: " + reason));
					changed = true;
				}
			}
		}

		// ==================== 阶段 2.5：拓扑排序 ====================
		List<String> orderedInstance;
		if (acceptedInstance.isEmpty()) {
			orderedInstance = List.of();
		} else {
			orderedInstance = topoSortFields(acceptedInstance, instanceExtracts, className);
			if (orderedInstance == null) {
				log("Cyclic dependency among instance fields of " + className
				    + "; refusing all: " + acceptedInstance);
				for (String f : acceptedInstance) {
					instanceDecisions.put(f, FieldDecision.rejected(
					 "cyclic dependency among instance fields"));
				}
				acceptedInstance.clear();
				orderedInstance = List.of();
			}
		}

		List<String> orderedStatic;
		if (acceptedStatic.isEmpty()) {
			orderedStatic = List.of();
		} else {
			orderedStatic = topoSortFields(acceptedStatic, staticExtracts, className);
			if (orderedStatic == null) {
				log("Cyclic dependency among static fields of " + className
				    + "; refusing all: " + acceptedStatic);
				for (String f : acceptedStatic) {
					staticDecisions.put(f, FieldDecision.rejected(
					 "cyclic dependency among static fields"));
				}
				acceptedStatic.clear();
				orderedStatic = List.of();
			}
		}

		// ==================== 阶段 3：写出选中的提取 ====================
		List<AbstractInsnNode> initInsns = new ArrayList<>();
		Map<AbstractInsnNode, ProtectedAccess> initProtected = new HashMap<>();
		for (String fieldName : orderedInstance) {
			List<FieldExtract> extracts = instanceExtracts.get(fieldName);
			FieldExtract chosen = null;
			for (FieldExtract fe : extracts) {
				if (fe.fromRootCtor()) { chosen = fe; break; }
			}
			if (chosen == null) chosen = extracts.get(0);
			initInsns.addAll(chosen.instructions());
			initProtected.putAll(chosen.protectedAccesses());
		}

		List<AbstractInsnNode> clinitInsns = new ArrayList<>();
		Map<AbstractInsnNode, ProtectedAccess> clinitProtected = new HashMap<>();
		for (String fieldName : orderedStatic) {
			FieldExtract fe = staticExtracts.get(fieldName).get(0);
			clinitInsns.addAll(fe.instructions());
			clinitProtected.putAll(fe.protectedAccesses());
		}


		PatchReport report = new PatchReport(
		 Collections.unmodifiableMap(instanceDecisions),
		 Collections.unmodifiableMap(staticDecisions),
		 !initInsns.isEmpty() || !clinitInsns.isEmpty());

		if (initInsns.isEmpty() && clinitInsns.isEmpty()) {
			return new BuiltPatch(null, false, false, report);
		}

		String hostInternal = Type.getInternalName(host);
		initInsns   = rewriteProtectedAccesses(hostInternal, initInsns, initProtected);
		clinitInsns = rewriteProtectedAccesses(hostInternal, clinitInsns, clinitProtected);

		initInsns   = rewritePrivateInvokes(className, initInsns);
		clinitInsns = rewritePrivateInvokes(className, clinitInsns);

		Set<String> allAddedFields = new HashSet<>(addedInstanceFields);
		allAddedFields.addAll(addedStaticFields);
		initInsns   = rewriteFieldPuts(className, initInsns, allAddedFields);
		clinitInsns = rewriteFieldPuts(className, clinitInsns, allAddedFields);

		boolean hasStatic   = !clinitInsns.isEmpty();
		boolean hasInstance = !initInsns.isEmpty();

		ClassNode patch = new ClassNode();
		patch.version = Math.max(hostVersion, Opcodes.V11);
		patch.access = Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
		patch.name = className + PATCH_SUFFIX;
		patch.superName = "java/lang/Object";

		if (hasStatic) {
			MethodNode m = new MethodNode(
			 Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC,
			 STATIC_PATCH_METHOD, "()V", null, null);
			for (AbstractInsnNode i : clinitInsns) m.instructions.add(i);
			m.instructions.add(new InsnNode(Opcodes.RETURN));
			patch.methods.add(m);
		}

		if (hasInstance) {
			MethodNode m = new MethodNode(
			 Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC,
			 INSTANCE_PATCH_METHOD, "(Ljava/lang/Object;)V", null, null);
			m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			m.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, className));
			m.instructions.add(new VarInsnNode(Opcodes.ASTORE, 0));
			for (AbstractInsnNode i : initInsns) m.instructions.add(i);
			m.instructions.add(new InsnNode(Opcodes.RETURN));
			patch.methods.add(m);
		}

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		patch.accept(cw);
		return new BuiltPatch(cw.toByteArray(), hasStatic, hasInstance, report);
	}

	// ==================== 拓扑排序 ====================

	/**
	 * 对一组已放行字段做拓扑排序，保证被依赖字段先执行。
	 * <p>依赖关系从 {@link FieldExtract#instructions()} 里的 GETFIELD/GETSTATIC 派生。
	 * 返回 null 表示存在循环依赖。</p>
	 */
	private static List<String> topoSortFields(
	 Set<String> fields,
	 Map<String, List<FieldExtract>> extracts,
	 String className) {

		Map<String, Set<String>> deps = new LinkedHashMap<>();
		for (String f : fields) {
			Set<String> d = new LinkedHashSet<>();
			List<FieldExtract> feList = extracts.get(f);
			if (feList != null) {
				for (FieldExtract fe : feList) {
					for (AbstractInsnNode n : fe.instructions()) {
						if (!(n instanceof FieldInsnNode fld)) continue;
						if (!fld.owner.equals(className)) continue;
						if (fld.name.equals(f)) continue;
						if (!fields.contains(fld.name)) continue;
						int op = fld.getOpcode();
						if (op == Opcodes.GETFIELD || op == Opcodes.GETSTATIC) {
							d.add(fld.name);
						}
					}
				}
			}
			deps.put(f, d);
		}

		List<String> order = new ArrayList<>(fields.size());
		Set<String> visited  = new HashSet<>();
		Set<String> visiting = new HashSet<>();
		for (String f : fields) {
			if (!topoVisit(f, deps, visited, visiting, order)) return null;
		}
		return order;
	}

	private static boolean topoVisit(
	 String f,
	 Map<String, Set<String>> deps,
	 Set<String> visited,
	 Set<String> visiting,
	 List<String> order) {

		if (visited.contains(f)) return true;
		if (!visiting.add(f)) return false;
		for (String dep : deps.getOrDefault(f, Set.of())) {
			if (!topoVisit(dep, deps, visited, visiting, order)) return false;
		}
		visiting.remove(f);
		visited.add(f);
		order.add(f);
		return true;
	}

	// ==================== Analyzer 封装 ====================

	private static Frame<SourceValue>[] analyze(String className, MethodNode method)
	 throws AnalyzerException {
		Analyzer<SourceValue> analyzer = new Analyzer<>(new AliasInterpreter()) {
			@Override
			protected boolean newControlFlowExceptionEdge(int insnIndex, TryCatchBlockNode tcb) {
				return false;
			}
		};
		return analyzer.analyze(className, method);
	}

	// ==================== 根构造器判定 ====================

	private static boolean isRootConstructor(ClassNode hostClass, MethodNode init,
	                                         Map<MethodNode, Boolean> cache) {
		Boolean cached = cache.get(init);
		if (cached != null) return cached;

		boolean result = computeIsRootConstructor(hostClass, init);
		cache.put(init, result);
		return result;
	}

	private static boolean computeIsRootConstructor(ClassNode hostClass, MethodNode init) {
		Frame<SourceValue>[] frames;
		try {
			frames = analyze(hostClass.name, init);
		} catch (AnalyzerException e) {
			return true;
		}

		InsnList insns = init.instructions;
		for (int i = 0; i < insns.size(); i++) {
			AbstractInsnNode n = insns.get(i);
			if (!(n instanceof MethodInsnNode m)
			    || m.getOpcode() != Opcodes.INVOKESPECIAL
			    || !"<init>".equals(m.name)
			    || !m.owner.equals(hostClass.name)) continue;

			Frame<SourceValue> frame = frames[i];
			if (frame == null) continue;

			int argCount = Type.getArgumentTypes(m.desc).length;
			int recvIdx  = frame.getStackSize() - 1 - argCount;
			if (recvIdx < 0) continue;

			SourceValue sv = frame.getStack(recvIdx);
			if (sv == null) continue;

			for (AbstractInsnNode src : sv.insns) {
				if (src instanceof VarInsnNode v
				    && v.getOpcode() == Opcodes.ALOAD
				    && v.var == 0) {
					return false;
				}
			}
		}
		return true;
	}

	// ==================== 后续加工检查 ====================

	private static String subsequentProcessingReason(
	 String fieldName,
	 Set<String> acceptedFields,
	 Map<String, List<FieldExtract>> allExtracts,
	 List<MethodNode> methods,
	 String className,
	 boolean isStatic,
	 FieldNode fieldNode) {

		if (fieldNode == null) return null;

		Set<AbstractInsnNode> acceptedInsns = new HashSet<>();
		for (String g : acceptedFields) {
			List<FieldExtract> feList = allExtracts.get(g);
			if (feList == null) continue;
			for (FieldExtract fe : feList) {
				acceptedInsns.addAll(fe.originalInsns());
			}
		}

		boolean fieldIsPrimitive = isPrimitiveDesc(fieldNode.desc);
		boolean fieldIsImmutable = isImmutableType(fieldNode.desc);

		int putOp = isStatic ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD;
		int getOp = isStatic ? Opcodes.GETSTATIC : Opcodes.GETFIELD;

		for (MethodNode m : methods) {
			for (AbstractInsnNode n : m.instructions) {
				if (!(n instanceof FieldInsnNode f)) continue;
				if (!f.owner.equals(className) || !f.name.equals(fieldName)) continue;

				if (f.getOpcode() == putOp) {
					if (!acceptedInsns.contains(n)) {
						return "field written outside its extraction in "
						     + className + "." + m.name + " at index "
						     + m.instructions.indexOf(n);
					}
				} else if (f.getOpcode() == getOp) {
					if (fieldIsPrimitive || fieldIsImmutable) continue;
					if (!acceptedInsns.contains(n)) {
						return "field read outside any accepted extraction in "
						     + className + "." + m.name + " at index "
						     + m.instructions.indexOf(n);
					}
				}
			}
		}
		return null;
	}

	// ==================== 依赖闭包 ====================

	private static String depReason(
	 String self,
	 List<FieldExtract> feList,
	 String className,
	 Set<String> acceptedInstance,
	 Set<String> acceptedStatic,
	 Set<String> allNewInstance,
	 Set<String> allNewStatic) {

		if (feList == null) return null;

		for (FieldExtract fe : feList) {
			for (AbstractInsnNode n : fe.instructions()) {
				if (!(n instanceof FieldInsnNode fld)) continue;
				if (!fld.owner.equals(className)) continue;

				int op = fld.getOpcode();
				if (op == Opcodes.GETFIELD
				    && allNewInstance != null
				    && acceptedInstance != null
				    && allNewInstance.contains(fld.name)
				    && !fld.name.equals(self)
				    && !acceptedInstance.contains(fld.name)) {
					return "reads new instance field '" + fld.name
					     + "' which is not patched";
				}
				if (op == Opcodes.GETSTATIC
				    && allNewStatic != null
				    && acceptedStatic != null
				    && allNewStatic.contains(fld.name)
				    && !fld.name.equals(self)
				    && !acceptedStatic.contains(fld.name)) {
					return "reads new static field '" + fld.name
					     + "' which is not patched";
				}
			}
		}
		return null;
	}

	// ==================== 指令指纹 ====================

	private static String fingerprint(List<AbstractInsnNode> insns) {
		StringBuilder sb = new StringBuilder();
		for (AbstractInsnNode n : insns) {
			if (n.getOpcode() == -1) continue;
			sb.append(n.getOpcode()).append(':').append(describe(n)).append(';');
		}
		return sb.toString();
	}

	private static String describe(AbstractInsnNode n) {
		if (n instanceof FieldInsnNode f)
			return f.owner + "." + f.name + ":" + f.desc;
		if (n instanceof MethodInsnNode m)
			return m.owner + "." + m.name + m.desc + (m.itf ? ":itf" : "");
		if (n instanceof VarInsnNode v)
			return "v" + v.var;
		if (n instanceof LdcInsnNode l)
			return "ldc(" + describeLdcConstant(l.cst) + ")";
		if (n instanceof TypeInsnNode t)
			return t.desc;
		if (n instanceof IntInsnNode in)
			return "int" + in.operand;
		if (n instanceof MultiANewArrayInsnNode m)
			return "manaa:" + m.desc + ":" + m.dims;
		if (n instanceof InvokeDynamicInsnNode indy) {
			StringBuilder sb = new StringBuilder("indy:")
			 .append(indy.name).append(indy.desc)
			 .append(":bsm=").append(describeBsmArg(indy.bsm));
			if (indy.bsmArgs != null) {
				for (Object a : indy.bsmArgs) {
					sb.append("|a=").append(describeBsmArg(a));
				}
			}
			return sb.toString();
		}
		if (n instanceof IincInsnNode inc)
			return "iinc" + inc.var + "+" + inc.incr;
		return "";
	}

	private static String describeLdcConstant(Object cst) {
		if (cst instanceof Type t) return t.getDescriptor();
		if (cst instanceof Handle h) return describeBsmArg(h);
		if (cst instanceof ConstantDynamic cd) return describeConstantDynamic(cd);
		return String.valueOf(cst);
	}

	private static String describeConstantDynamic(ConstantDynamic cd) {
		StringBuilder sb = new StringBuilder("cdy:")
		 .append(cd.getName()).append(cd.getDescriptor())
		 .append(":bsm=").append(describeBsmArg(cd.getBootstrapMethod()));
		int n = cd.getBootstrapMethodArgumentCount();
		for (int i = 0; i < n; i++) {
			sb.append("|a=").append(describeBsmArg(cd.getBootstrapMethodArgument(i)));
		}
		return sb.toString();
	}

	private static String describeBsmArg(Object a) {
		if (a == null) return "null";
		if (a instanceof Handle h) {
			return "H[" + h.getTag() + ":"
			       + h.getOwner() + "." + h.getName() + h.getDesc()
			       + (h.isInterface() ? ":itf" : "") + "]";
		}
		if (a instanceof Type t) {
			return "T[" + t.getDescriptor() + "]";
		}
		if (a instanceof ConstantDynamic cd) {
			return describeConstantDynamic(cd);
		}
		if (a.getClass().isArray()) {
			int len = Array.getLength(a);
			StringBuilder sb = new StringBuilder("[");
			for (int i = 0; i < len; i++) {
				if (i > 0) sb.append(',');
				sb.append(describeBsmArg(Array.get(a, i)));
			}
			return sb.append(']').toString();
		}
		return String.valueOf(a);
	}

	private static String fingerprintMismatchReason(List<FieldExtract> extracts) {
		if (extracts.size() < 2) return null;
		String fp = fingerprint(extracts.get(0).instructions());
		for (int k = 1; k < extracts.size(); k++) {
			if (!fp.equals(fingerprint(extracts.get(k).instructions()))) {
				return "different initialization expressions across constructors";
			}
		}
		return null;
	}

	// ==================== 构造器参数 -> 字段扫描 ====================

	private static Map<Integer, ParamField> scanParamFields(
	 ClassNode hostClass, MethodNode init) {

		Map<Integer, ParamField> map = new HashMap<>();
		InsnList insns = init.instructions;
		List<AbstractInsnNode> real = filterReal(insns);

		for (int i = 0; i + 2 < real.size(); i++) {
			AbstractInsnNode a = real.get(i);
			AbstractInsnNode b = real.get(i + 1);
			AbstractInsnNode c = real.get(i + 2);

			if (!(a instanceof VarInsnNode va)
			    || va.getOpcode() != Opcodes.ALOAD || va.var != 0) continue;
			if (!(b instanceof VarInsnNode vb)
			    || !isLoadOfParam(vb)) continue;
			if (!(c instanceof FieldInsnNode fc)
			    || fc.getOpcode() != Opcodes.PUTFIELD) continue;
			if (!fc.owner.equals(hostClass.name)) continue;

			int slot = vb.var;
			if (map.containsKey(slot)) continue;

			int startOrig = insns.indexOf(a);
			int putOrig   = insns.indexOf(c);
			String ctrlReason = checkControlDependency(insns, startOrig, putOrig);
			if (ctrlReason != null) {
				log("Skipping constructor param slot " + slot + " in "
				    + hostClass.name + ".<init>: pattern has control dependency: "
				    + ctrlReason);
				continue;
			}

			// 参数类型必须与字段描述符严格一致，否则替换成 GETFIELD 后
			// 后续指令期望的类型会不匹配，触发 VerifyError
			if (!paramTypeMatches(init.desc, slot, fc.desc)) {
				log("Skipping constructor param slot " + slot + " in "
				    + hostClass.name + ".<init>: parameter type != field type "
				    + fc.desc);
				continue;
			}

			int paramWidth = (vb.getOpcode() == Opcodes.LLOAD
			                  || vb.getOpcode() == Opcodes.DLOAD) ? 2 : 1;

			String unsafeReason = null;

			for (int k = 0; k < i; k++) {
				AbstractInsnNode n = real.get(k);
				if (n instanceof VarInsnNode v && isStore(v.getOpcode())) {
					int storeWidth = (v.getOpcode() == Opcodes.LSTORE
					                  || v.getOpcode() == Opcodes.DSTORE) ? 2 : 1;
					if (slotsOverlap(slot, paramWidth, v.var, storeWidth)) {
						unsafeReason = "slot overwritten before pattern by xSTORE at real index "
						               + k + " (store slot=" + v.var + " width=" + storeWidth + ")";
						break;
					}
				} else if (n instanceof IincInsnNode inc) {
					if (slotsOverlap(slot, paramWidth, inc.var, 1)) {
						unsafeReason = "slot incremented before pattern by IINC at real index "
						               + k + " (iinc slot=" + inc.var + ")";
						break;
					}
				}
			}
			if (unsafeReason != null) {
				log("Skipping constructor param slot " + slot + " in "
				    + hostClass.name + ".<init>: " + unsafeReason);
				continue;
			}

			for (int k = i + 3; k < real.size(); k++) {
				AbstractInsnNode n = real.get(k);

				if (n instanceof VarInsnNode v && isStore(v.getOpcode())) {
					int storeWidth = (v.getOpcode() == Opcodes.LSTORE
					                  || v.getOpcode() == Opcodes.DSTORE) ? 2 : 1;
					if (slotsOverlap(slot, paramWidth, v.var, storeWidth)) {
						unsafeReason = "slot overwritten by xSTORE at real index " + k
						               + " (store slot=" + v.var + " width=" + storeWidth + ")";
						break;
					}
				} else if (n instanceof IincInsnNode inc) {
					if (slotsOverlap(slot, paramWidth, inc.var, 1)) {
						unsafeReason = "slot incremented by IINC at real index " + k
						               + " (iinc slot=" + inc.var + ")";
						break;
					}
				} else if (n instanceof FieldInsnNode f
				           && f.getOpcode() == Opcodes.PUTFIELD
				           && f.owner.equals(hostClass.name)
				           && f.name.equals(fc.name)) {
					unsafeReason = "field " + fc.name + " overwritten by PUTFIELD at real index " + k;
					break;
				}
			}
			if (unsafeReason != null) {
				log("Skipping constructor param slot " + slot + " in "
				    + hostClass.name + ".<init>: " + unsafeReason);
				continue;
			}

			for (FieldNode fn : hostClass.fields) {
				if (fn.name.equals(fc.name) && fn.desc.equals(fc.desc)) {
					map.put(slot, new ParamField(fn, slot));
					break;
				}
			}
		}
		return map;
	}

	/**
	 * 校验方法描述符中 {@code slot} 处的参数类型是否与 {@code fieldDesc} 一致。
	 * <p>slot 0 是 this；long/double 占 2 槽。</p>
	 */
	private static boolean paramTypeMatches(String methodDesc, int slot, String fieldDesc) {
		Type[] args = Type.getArgumentTypes(methodDesc);
		int    cur  = 1;
		for (Type t : args) {
			if (cur == slot) {
				return t.getDescriptor().equals(fieldDesc);
			}
			cur += t.getSize();
			if (cur > slot) return false;
		}
		return false;
	}

	private static List<AbstractInsnNode> filterReal(InsnList insns) {
		List<AbstractInsnNode> real = new ArrayList<>(insns.size());
		for (AbstractInsnNode n : insns) {
			if (n.getOpcode() != -1) real.add(n);
		}
		return real;
	}

	private static boolean slotsOverlap(int s1, int w1, int s2, int w2) {
		return s1 < s2 + w2 && s2 < s1 + w1;
	}

	private static boolean isLoadOfParam(VarInsnNode v) {
		int op = v.getOpcode();
		boolean isLoad = op == Opcodes.ALOAD || op == Opcodes.ILOAD
		              || op == Opcodes.LLOAD || op == Opcodes.FLOAD
		              || op == Opcodes.DLOAD;
		return isLoad && v.var != 0;
	}

	private static boolean isStore(int op) {
		return op == Opcodes.ASTORE || op == Opcodes.ISTORE || op == Opcodes.LSTORE
		    || op == Opcodes.FSTORE || op == Opcodes.DSTORE;
	}

	private static boolean isPrimitiveDesc(String desc) {
		if (desc == null || desc.isEmpty()) return false;
		char c = desc.charAt(0);
		return c == 'Z' || c == 'B' || c == 'C' || c == 'S'
		    || c == 'I' || c == 'J' || c == 'F' || c == 'D';
	}

	private static boolean isImmutableType(String desc) {
		if (desc == null || desc.length() < 3 || desc.charAt(0) != 'L') return false;
		String c = desc.substring(1, desc.length() - 1);
		switch (c) {
			case "java/lang/String":
			case "java/lang/Integer":
			case "java/lang/Long":
			case "java/lang/Short":
			case "java/lang/Byte":
			case "java/lang/Character":
			case "java/lang/Boolean":
			case "java/lang/Float":
			case "java/lang/Double":
			case "java/lang/Class":
			case "java/math/BigDecimal":
			case "java/math/BigInteger":
			case "java/time/Instant":
			case "java/time/Duration":
			case "java/time/LocalDate":
			case "java/time/LocalTime":
			case "java/time/LocalDateTime":
			case "java/time/ZonedDateTime":
			case "java/util/UUID":
				return true;
			default:
				return false;
		}
	}

	// ==================== 指令改写 ====================

	private static List<AbstractInsnNode> rewriteProtectedAccesses(
	 String hostInternal,
	 List<AbstractInsnNode> insns,
	 Map<AbstractInsnNode, ProtectedAccess> accesses) {

		if (accesses.isEmpty()) return insns;

		Type hostType = Type.getObjectType(hostInternal);

		List<AbstractInsnNode> rewritten = new ArrayList<>(insns.size());
		for (AbstractInsnNode insn : insns) {
			ProtectedAccess pa = accesses.get(insn);
			if (pa == null) {
				rewritten.add(insn);
				continue;
			}

			String ownerInternal = pa.owner();
			String indyDesc = indyDescFor(pa, hostInternal);

			Object[] bsmArgs = new Object[] {
			 HotswapBridge.KIND_PROTECTED,
			 pa.opcode(),
			 Type.getObjectType(ownerInternal),
			 hostType
			};

			rewritten.add(new InvokeDynamicInsnNode(pa.name(), indyDesc, BRIDGE_BSM, bsmArgs));
			log("Bridging protected access via indy: "
			    + ownerInternal + "." + pa.name() + " " + pa.desc()
			    + " (opcode=" + pa.opcode() + ", host=" + hostInternal + ")");
		}
		return rewritten;
	}

	private static String indyDescFor(ProtectedAccess pa, String hostInternal) {
		return switch (pa.opcode()) {
			case Opcodes.GETFIELD  -> "(L" + hostInternal + ";)" + pa.desc();
			case Opcodes.PUTFIELD  -> "(L" + hostInternal + ";" + pa.desc() + ")V";
			case Opcodes.GETSTATIC -> "()" + pa.desc();
			case Opcodes.PUTSTATIC -> "(" + pa.desc() + ")V";
			case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE ->
			 "(L" + hostInternal + ";" + pa.desc().substring(1);
			case Opcodes.INVOKESTATIC -> pa.desc();
			default -> throw new IllegalStateException(
			 "unexpected opcode for protected bridge: " + pa.opcode());
		};
	}

	private static List<AbstractInsnNode> rewritePrivateInvokes(
	 String className, List<AbstractInsnNode> insns) {
		if (insns.isEmpty()) return insns;

		List<AbstractInsnNode> rewritten = new ArrayList<>(insns.size());
		for (AbstractInsnNode insn : insns) {
			if (insn instanceof MethodInsnNode m
			    && m.getOpcode() == Opcodes.INVOKESPECIAL
			    && !"<init>".equals(m.name)
			    && m.owner.equals(className)) {
				int newOpcode = m.itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
				rewritten.add(new MethodInsnNode(newOpcode, m.owner, m.name, m.desc, m.itf));

			} else if (insn instanceof InvokeDynamicInsnNode indy) {
				InvokeDynamicInsnNode replaced = rewriteIndyHandles(className, indy);
				rewritten.add(replaced != null ? replaced : indy);

			} else {
				rewritten.add(insn);
			}
		}
		return rewritten;
	}

	private static InvokeDynamicInsnNode rewriteIndyHandles(
	 String className, InvokeDynamicInsnNode indy) {
		Object[] args = indy.bsmArgs;
		if (args == null) return null;
		Object[] copy = null;
		for (int i = 0; i < args.length; i++) {
			if (args[i] instanceof Handle h
			    && h.getTag() == Opcodes.H_INVOKESPECIAL
			    && !"<init>".equals(h.getName())
			    && h.getOwner().equals(className)) {
				if (copy == null) copy = args.clone();
				int newTag = h.isInterface()
				 ? Opcodes.H_INVOKEINTERFACE : Opcodes.H_INVOKEVIRTUAL;
				copy[i] = new Handle(newTag, h.getOwner(), h.getName(), h.getDesc(), h.isInterface());
			}
		}
		if (copy == null) return null;
		return new InvokeDynamicInsnNode(indy.name, indy.desc, indy.bsm, copy);
	}

	/**
	 * 把补丁片段里对新增字段的 {@code PUTFIELD}/{@code PUTSTATIC} 改写为
	 * {@code invokedynamic}，由 {@link HotswapBridge#KIND_CONDITIONAL} 做条件 CAS 写。
	 * <p>final 与非 final 走同一条路径，语义统一。</p>
	 */
	private static List<AbstractInsnNode> rewriteFieldPuts(
	 String className, List<AbstractInsnNode> insns, Set<String> targetFields) {
		if (insns.isEmpty() || targetFields.isEmpty()) return insns;

		List<AbstractInsnNode> rewritten = new ArrayList<>(insns.size());
		for (AbstractInsnNode insn : insns) {
			if (!(insn instanceof FieldInsnNode f)
			    || !f.owner.equals(className)
			    || !targetFields.contains(f.name)
			    || (f.getOpcode() != Opcodes.PUTFIELD && f.getOpcode() != Opcodes.PUTSTATIC)) {
				rewritten.add(insn);
				continue;
			}

			boolean isStatic = f.getOpcode() == Opcodes.PUTSTATIC;
			String  indyDesc = (isStatic ? "(" : "(L" + className + ";") + f.desc + ")V";
			Type    hostType = Type.getObjectType(className);

			Object[] bsmArgs = new Object[] {
			 HotswapBridge.KIND_CONDITIONAL,
			 f.getOpcode(),
			 hostType,
			 hostType
			};

			rewritten.add(new InvokeDynamicInsnNode(f.name, indyDesc, BRIDGE_BSM, bsmArgs));
			log("Rewriting field write via indy(conditional): "
			    + className + "." + f.name + " " + f.desc);
		}
		return rewritten;
	}

	// ==================== 应用与生命周期 ====================

	public static void afterRedefined(Class<?> clazz) {
		if (!HotSwapAgent.HOTSWAP_PLUS || clazz == null) return;

		PendingPatch patch = PENDING.remove(clazz);
		if (patch == null) return;

		try {
			applyPatch(clazz, patch);
		} catch (Throwable e) {
			HotSwapAgent.error("Field init patch failed: " + e.getMessage(), e);
		}
	}

	public static void afterRedefineFailed(Class<?> clazz) {
		if (clazz == null) return;
		PendingPatch dropped = PENDING.remove(clazz);
		if (dropped != null) {
			log("Dropped pending field init patch for " + clazz.getName()
			    + " after redefine failure");
		}
	}

	private static void applyPatch(Class<?> host, PendingPatch patch) throws Throwable {
		if (!isInitialized(host)) {
			log("Skip field init patch, class not initialized (or initializing/failed): "
			    + host.getName());
			return;
		}

		List<Object> alive = null;
		if (patch.hasInstance()) {
			alive = collectInstancesForPatch(patch);
			if (alive.isEmpty() && !patch.hasStatic()) {
				log("No live instances and no static patch needed for " + host.getName()
				    + ", skip");
				return;
			}
		}

		Lookup   h  = Reflect.defineHiddenClass(host, patch.bytes());
		Class<?> pc = h.lookupClass();

		if (patch.hasStatic()) {
			HotSwapAgent.info("Applying static field init patch to " + host.getName());
			MethodHandle mh = h.findStatic(pc, STATIC_PATCH_METHOD,
			 MethodType.methodType(void.class));
			long start = System.nanoTime();
			mh.invoke();
			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
			HotSwapAgent.info("Static field init patch for " + host.getName()
			                  + " done in " + elapsedMs + "ms");
		}

		if (patch.hasInstance()) {
			if (alive.isEmpty()) return;

			HotSwapAgent.info("Applying instance field init patch to " + host.getName()
			                  + ", count=" + alive.size());
			MethodHandle mh = h.findStatic(pc, INSTANCE_PATCH_METHOD,
			 MethodType.methodType(void.class, Object.class));

			long start        = System.nanoTime();
			int  ok           = 0;
			int  failed       = 0;
			int  skipped      = 0;
			int  detailedErrs = 0;
			for (Object ins : alive) {
				try {
					mh.invoke(ins);
					ok++;
				} catch (LinkageError le) {
					HotSwapAgent.error("init patch aborted on LinkageError after "
					                   + ok + " ok, " + failed + " failed: "
					                   + le.getMessage(), le);
					skipped = alive.size() - ok - failed - 1;
					failed++;
					break;
				} catch (Throwable t) {
					failed++;
					if (++detailedErrs <= MAX_DETAILED_FAILURES) {
						HotSwapAgent.error("init patch failed on one instance: "
						                   + t.getMessage(), t);
					}
				}
			}
			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

			StringBuilder sb = new StringBuilder("Instance field init patch for ")
			 .append(host.getName())
			 .append(" done: ok=").append(ok)
			 .append(", failed=").append(failed);
			if (skipped > 0) sb.append(", skipped=").append(skipped);
			if (failed > detailedErrs) {
				sb.append(" (detailed stacks suppressed for ")
				  .append(failed - detailedErrs).append(" of them)");
			}
			sb.append(", elapsed=").append(elapsedMs).append("ms");
			HotSwapAgent.info(sb.toString());

			if (elapsedMs > 1000) {
				HotSwapAgent.warn("Instance field init patch for " + host.getName()
				                  + " took " + elapsedMs
				                  + "ms, hotswap thread was blocked");
			}
		}
	}

	private static List<Object> collectInstancesForPatch(PendingPatch patch) {
		List<WeakReference<Object>> snapshot = patch.instanceSnapshot();
		List<Object> alive = new ArrayList<>(snapshot.size());
		for (WeakReference<Object> ref : snapshot) {
			Object o = ref.get();
			if (o != null) alive.add(o);
		}
		return alive;
	}

	private static List<WeakReference<Object>> snapshotInstances(Class<?> clazz) {
		Object[] arr = LibTool.initialized()
		 ? LibTool.getInstances(clazz)
		 : InstanceTracker.getInstances(clazz).toArray();
		List<WeakReference<Object>> refs = new ArrayList<>(arr.length);
		for (Object o : arr) {
			if (o != null) refs.add(new WeakReference<>(o));
		}
		return refs;
	}

	private static boolean isInitialized(Class<?> clazz) {
		return !UNSAFE.shouldBeInitialized(clazz);
	}

	// ==================== 基于 ASM Analyzer 的字段初始化提取 ====================

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

	private static Map<String, FieldExtract> extractFieldInits(
	 Class<?> host, String className, MethodNode method, Set<String> targetFields,
	 boolean isStatic, Set<String> privateMethods,
	 Map<Integer, ParamField> paramFields, boolean fromRootCtor) {

		if (method == null || targetFields.isEmpty()) return Map.of();

		Frame<SourceValue>[] frames;
		try {
			frames = analyze(className, method);
		} catch (AnalyzerException e) {
			HotSwapAgent.warn("Analysis failed for " + method.name + method.desc
			                  + ": " + e.getMessage());
			return Map.of();
		}

		InsnList              insns       = method.instructions;
		Set<LabelNode>        jumpTargets = collectJumpTargets(method);
		Map<String, Set<AbstractInsnNode>> perFieldCollected = new LinkedHashMap<>();
		Map<String, Map<AbstractInsnNode, ProtectedAccess>> perFieldProtected = new HashMap<>();
		Set<String> refusedFields = new HashSet<>();

		for (int i = 0; i < insns.size(); i++) {
			AbstractInsnNode insn = insns.get(i);
			if (!(insn instanceof FieldInsnNode f)) continue;

			int op = f.getOpcode();
			if (isStatic ? op != Opcodes.PUTSTATIC : op != Opcodes.PUTFIELD) continue;
			if (!f.owner.equals(className) || !targetFields.contains(f.name)) continue;

			if (refusedFields.contains(f.name)) continue;

			Frame<SourceValue> frame = frames[i];
			if (frame == null) continue;

			int stackSize = frame.getStackSize();
			if (isStatic ? stackSize < 1 : stackSize < 2) continue;

			SourceValue value    = frame.getStack(stackSize - 1);
			SourceValue receiver = isStatic ? null : frame.getStack(stackSize - 2);

			Set<AbstractInsnNode> collected = new HashSet<>(value.insns);
			if (receiver != null) collected.addAll(receiver.insns);
			collected.add(insn);

			Map<AbstractInsnNode, ProtectedAccess> localProtected = new HashMap<>();

			String unsafeReason;
			try {
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
					unsafeReason = checkSafe(host, className, method, insns, jumpTargets,
					 receiver, collected, minIdx, i, isStatic, privateMethods,
					 paramFields, localProtected);
					if (unsafeReason == null && !isStackBalanced(frames, minIdx, i, isStatic)) {
						unsafeReason = "unbalanced stack after extraction";
					}
				}
			} catch (RuntimeException e) {
				unsafeReason = "extraction threw " + e.getClass().getSimpleName()
				               + ": " + e.getMessage();
			}

			if (unsafeReason != null) {
				HotSwapAgent.warn("Field '" + f.name + "' initialization skipped: " + unsafeReason);
				continue;
			}

			if (perFieldCollected.containsKey(f.name)) {
				log("Field '" + f.name + "' has multiple safe PUTFIELD in "
				    + className + "." + method.name + "(): refusing");
				perFieldCollected.remove(f.name);
				perFieldProtected.remove(f.name);
				refusedFields.add(f.name);
				continue;
			}
			perFieldCollected.put(f.name, collected);
			perFieldProtected.put(f.name, localProtected);
		}

		Map<String, FieldExtract> perField = new LinkedHashMap<>();
		for (Map.Entry<String, Set<AbstractInsnNode>> e : perFieldCollected.entrySet()) {
			String fieldName = e.getKey();
			Set<AbstractInsnNode> fieldInsns = e.getValue();
			Map<AbstractInsnNode, ProtectedAccess> fieldProtected =
			 perFieldProtected.getOrDefault(fieldName, Map.of());

			Map<LabelNode, LabelNode> labelMap = new HashMap<>();
			List<AbstractInsnNode> cloned = new ArrayList<>();
			Map<AbstractInsnNode, ProtectedAccess> clonedProtected = new HashMap<>();
			boolean dependsOnParam = false;
			boolean selfAssign = false;

			for (int i = 0; i < insns.size(); i++) {
				AbstractInsnNode insn = insns.get(i);
				if (!fieldInsns.contains(insn)) continue;

				if (insn instanceof VarInsnNode v
				    && isLoadOfParam(v)
				    && paramFields.containsKey(v.var)) {
					FieldNode fn = paramFields.get(v.var).field();
					if (fn.name.equals(fieldName)) {
						selfAssign = true;
						break;
					}
					cloned.add(new VarInsnNode(Opcodes.ALOAD, 0));
					cloned.add(new FieldInsnNode(Opcodes.GETFIELD, className, fn.name, fn.desc));
					dependsOnParam = true;
					continue;
				}

				AbstractInsnNode c = insn.clone(labelMap);
				cloned.add(c);
				ProtectedAccess pa = fieldProtected.get(insn);
				if (pa != null) clonedProtected.put(c, pa);
			}

			if (selfAssign) {
				log("Field '" + fieldName + "' is self-assigned from its own constructor "
				    + "parameter in " + className + "." + method.name
				    + "(); patch would be a no-op. Refusing.");
				continue;
			}
			perField.put(fieldName, new FieldExtract(
			 cloned, clonedProtected, dependsOnParam, fromRootCtor, fieldInsns));
		}
		return perField;
	}

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

	private static int wordsOf(int op) {
		return switch (op) {
			case Opcodes.DUP, Opcodes.POP -> 1;
			case Opcodes.DUP_X1, Opcodes.SWAP, Opcodes.DUP2, Opcodes.POP2 -> 2;
			case Opcodes.DUP_X2, Opcodes.DUP2_X1 -> 3;
			case Opcodes.DUP2_X2 -> 4;
			default -> 0;
		};
	}

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

	private static String checkSafe(Class<?> host, String className, MethodNode method,
	                                InsnList insns, Set<LabelNode> jumpTargets,
	                                SourceValue receiver,
	                                Set<AbstractInsnNode> collected,
	                                int minIdx, int putIdx, boolean isStatic,
	                                Set<String> privateMethods,
	                                Map<Integer, ParamField> paramFields,
	                                Map<AbstractInsnNode, ProtectedAccess> outProtectedAccesses) {
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

		for (TryCatchBlockNode tc : method.tryCatchBlocks) {
			int tryStart   = insns.indexOf(tc.start);
			int tryEnd     = insns.indexOf(tc.end);
			int handlerIdx = insns.indexOf(tc.handler);

			boolean overlaps = tryStart <= putIdx && tryEnd > minIdx;
			if (overlaps) {
				return "extraction range overlaps try-catch block";
			}
			if (handlerIdx >= minIdx && handlerIdx <= putIdx) {
				return "extraction range contains exception handler";
			}
		}

		String ctrlReason = checkControlDependency(insns, minIdx, putIdx);
		if (ctrlReason != null) return ctrlReason;

		for (AbstractInsnNode n : collected) {
			if (n instanceof VarInsnNode v) {
				if (isStatic) return "depends on local variables";
				if (v.getOpcode() == Opcodes.ALOAD && v.var == 0) {
					// this，放行
				} else if (isLoadOfParam(v) && paramFields.containsKey(v.var)) {
					// 构造器参数，克隆阶段会替换为 ALOAD 0; GETFIELD
				} else {
					return "depends on local variables";
				}
			}
			if (n.getOpcode() == Opcodes.IINC) {
				return "depends on local variables";
			}

			if (n instanceof MethodInsnNode m
			    && m.getOpcode() == Opcodes.INVOKESPECIAL
			    && !"<init>".equals(m.name)) {
				if (!m.owner.equals(className)) {
					return "invokespecial non-<init> on foreign owner not usable from hidden class";
				}
				if (!privateMethods.contains(m.name + m.desc)) {
					return "invokespecial non-<init> target is not a private method of host";
				}
			}

			if (n instanceof MethodInsnNode m
			    && m.getOpcode() == Opcodes.INVOKESPECIAL
			    && "<init>".equals(m.name)) {
				Boolean prot = isProtectedCrossPackageAccess(host, m.owner, m.name, m.desc, false);
				if (prot == null) {
					return "cannot determine protected status of constructor "
					     + m.owner + "." + m.name + m.desc;
				}
				if (prot) {
					return "protected constructor across packages not bridgeable";
				}
			}

			if (n instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
				for (Object arg : indy.bsmArgs) {
					if (arg instanceof Handle h
					    && h.getTag() == Opcodes.H_INVOKESPECIAL
					    && !"<init>".equals(h.getName())) {
						if (!h.getOwner().equals(className)) {
							return "invokedynamic bsmArgs contains H_INVOKESPECIAL "
							     + "on foreign owner: " + h.getOwner()
							     + "." + h.getName() + h.getDesc();
						}
						if (!privateMethods.contains(h.getName() + h.getDesc())) {
							return "invokedynamic bsmArgs contains H_INVOKESPECIAL "
							     + "target not a private method of host: "
							     + h.getName() + h.getDesc();
						}
					}
				}
			}

			if (n instanceof FieldInsnNode f && isFieldAccessOpcode(f.getOpcode())) {
				Boolean prot = isProtectedCrossPackageAccess(host, f.owner, f.name, f.desc, true);
				if (prot == null) {
					return "cannot determine protected status of field access "
					     + f.owner + "." + f.name + ":" + f.desc;
				}
				if (prot) {
					outProtectedAccesses.put(n,
					 new ProtectedAccess(f.getOpcode(), f.owner, f.name, f.desc));
				}
			}
			if (n instanceof MethodInsnNode m && !m.owner.startsWith("[")) {
				Boolean prot = isProtectedCrossPackageAccess(host, m.owner, m.name, m.desc, false);
				if (prot == null) {
					return "cannot determine protected status of method access "
					     + m.owner + "." + m.name + m.desc;
				}
				if (prot) {
					outProtectedAccesses.put(n,
					 new ProtectedAccess(m.getOpcode(), m.owner, m.name, m.desc));
				}
			}
		}
		return null;
	}

	private static boolean isFieldAccessOpcode(int op) {
		return op == Opcodes.GETFIELD || op == Opcodes.PUTFIELD
		    || op == Opcodes.GETSTATIC || op == Opcodes.PUTSTATIC;
	}

	private static String checkControlDependency(InsnList insns, int minIdx, int putIdx) {
		for (int i = 0; i < insns.size(); i++) {
			AbstractInsnNode n = insns.get(i);
			int op = n.getOpcode();
			boolean inRange = i >= minIdx && i <= putIdx;
			boolean before  = i < minIdx;
			boolean after   = i > putIdx;

			if (n instanceof JumpInsnNode j) {
				if (inRange) return "jump instruction inside extraction range";
				int t = insns.indexOf(j.label);
				if (before && t > putIdx) {
					return "jump at " + i + " skips put (target=" + t + ")";
				}
				if (after && t <= putIdx) {
					return "back-edge at " + i + " into put (target=" + t + ")";
				}
			} else if (n instanceof TableSwitchInsnNode s) {
				if (inRange) return "switch instruction inside extraction range";
				String r = switchTargetCheck(insns, s.dflt, s.labels,
				                             before, after, minIdx, putIdx);
				if (r != null) return r;
			} else if (n instanceof LookupSwitchInsnNode s) {
				if (inRange) return "switch instruction inside extraction range";
				String r = switchTargetCheck(insns, s.dflt, s.labels,
				                             before, after, minIdx, putIdx);
				if (r != null) return r;
			} else if (op == Opcodes.RETURN || op == Opcodes.IRETURN
			        || op == Opcodes.LRETURN || op == Opcodes.FRETURN
			        || op == Opcodes.DRETURN || op == Opcodes.ARETURN) {
				if (inRange) return "return inside extraction range";
				if (before) return "return before put at index " + i;
			} else if (op == Opcodes.ATHROW) {
				if (inRange) return "athrow inside extraction range";
			}
		}
		return null;
	}

	private static String switchTargetCheck(InsnList insns, LabelNode dflt,
	                                        List<LabelNode> labels,
	                                        boolean before, boolean after,
	                                        int minIdx, int putIdx) {
		List<LabelNode> all = new ArrayList<>(labels.size() + 1);
		all.add(dflt);
		all.addAll(labels);
		for (LabelNode l : all) {
			int t = insns.indexOf(l);
			if (before && t > putIdx) {
				return "switch target at " + t + " skips put";
			}
			if (after && t <= putIdx) {
				return "switch back-edge to " + t + " into put";
			}
		}
		return null;
	}

	private static Boolean isProtectedCrossPackageAccess(
	 Class<?> host, String ownerInternal,
	 String name, String desc, boolean isField) {

		if (ownerInternal.isEmpty() || ownerInternal.charAt(0) == '[') return Boolean.FALSE;

		Class<?> owner;
		try {
			owner = Class.forName(ownerInternal.replace('/', '.'),
			                      false, host.getClassLoader());
		} catch (Throwable t) {
			return null;
		}

		try {
			if (isField) {
				for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
					try {
						Field f = c.getDeclaredField(name);
						return Modifier.isProtected(f.getModifiers()) && !sameRuntimePackage(c, host)
						 ? Boolean.TRUE : Boolean.FALSE;
					} catch (NoSuchFieldException ignored) {
						// 继续向上
					}
				}
				return Boolean.FALSE;
			}

			if ("<init>".equals(name)) {
				for (Constructor<?> ctor : owner.getDeclaredConstructors()) {
					if (Type.getConstructorDescriptor(ctor).equals(desc)) {
						return Modifier.isProtected(ctor.getModifiers())
						       && !sameRuntimePackage(owner, host)
						 ? Boolean.TRUE : Boolean.FALSE;
					}
				}
				return Boolean.FALSE;
			}

			for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
				for (Method m : c.getDeclaredMethods()) {
					if (m.getName().equals(name)
					    && Type.getMethodDescriptor(m).equals(desc)) {
						return Modifier.isProtected(m.getModifiers())
						       && !sameRuntimePackage(c, host)
						 ? Boolean.TRUE : Boolean.FALSE;
					}
				}
			}
			return Boolean.FALSE;
		} catch (Throwable t) {
			return null;
		}
	}

	private static boolean sameRuntimePackage(Class<?> a, Class<?> b) {
		if (a == b) return true;
		if (a.getClassLoader() != b.getClassLoader()) return false;
		return Objects.equals(packageNameOf(a), packageNameOf(b));
	}

	private static String packageNameOf(Class<?> c) {
		String n = c.getName();
		int    i = n.lastIndexOf('.');
		return i < 0 ? "" : n.substring(0, i);
	}

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

	private static boolean isNullCheck(MethodInsnNode m) {
		return (m.getOpcode() == Opcodes.INVOKESTATIC
		        && "java/util/Objects".equals(m.owner) && "requireNonNull".equals(m.name))
		       || (m.getOpcode() == Opcodes.INVOKEVIRTUAL
		           && "java/lang/Object".equals(m.owner) && "getClass".equals(m.name));
	}
}