package nipx.ref;

import jdk.internal.misc.Unsafe;
import nipx.ClassDiffUtil.ClassDiff;
import nipx.*;
import nipx.util.LibTool;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.tree.analysis.Frame;

import java.lang.invoke.*;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p>所有目标字段的 {@code PUTFIELD}/{@code PUTSTATIC}（final 与非 final 统一）都改写为
 * {@code invokedynamic}，由 {@link HotswapBridge} 在链接期算好 Unsafe offset 后写入：
 * {@link HotswapBridge#KIND_CONDITIONAL} 条件 CAS —— 仅当字段当前等于该类型默认值才写，
 * 这样不会覆盖 redefine 之后其他线程赋的新值（代价是字段已是默认值以外时跳过，保守方向）；
 * {@link HotswapBridge#KIND_FORCE} 无条件 volatile 写（{@code @HotswapReinit(OVERWRITE)}）。
 * 两者都经 Unsafe，因此 final 字段也适用（补丁是 nestmate，不能直接 {@code putfield} final）。
 * CAS/volatile 本身即带可见性语义，无需额外 fence。</p>
 *
 * <h2>存量重置扩展（§1.1）</h2>
 * <p>默认作用域只覆盖<b>本次新增字段</b>。字段标了 {@code @HotswapReinit} 时按
 * {@link nipx.annotation.HotswapReinit.Mode} 处理：</p>
 * <ul>
 *   <li>候选集从"新增字段"扩展为"新增字段 ∪ 带注解的已有字段"（含静态字段）；</li>
 *   <li>{@code OVERWRITE}（默认）→ {@link HotswapBridge#KIND_FORCE} 无条件覆写；
 *       {@code CONDITIONAL} → 维持条件 CAS；</li>
 *   <li>豁免 §4.1 T0 零值过滤（否则"把已有字段重置成 0/null"会判 NOTHING_TO_PATCH）
 *       与"构造器里读过 / 别处写过该字段"的后续加工检查 —— 已有字段本来就会被各处
 *       读写，那些门按定义不可能满足；</li>
 *   <li>切片本身的安全门<b>不</b>豁免：分支、局部变量、§4.2 效应判定照旧。
 *       覆写只决定"要不要写"，不能让一个读脏的值变正确。</li>
 * </ul>
 * <p>注解按<b>描述符字符串</b>匹配，不加载注解类（§2.1 同一原则）。</p>
 *
 * <h2>提取流程</h2>
 * <ol>
 *   <li><b>T0 零值等价（§4.1）</b>：先判定字段是否"存量本来就是默认值"——显式
 *       {@code = null}/{@code = 0}/{@code = false}、仅声明未赋值、{@code ConstantValue}
 *       按位为零。命中者标记 {@link FieldStatus#NOTHING_TO_PATCH}，零开销放行、不告警、
 *       不生成补丁（浮点按位判定，{@code -0.0f}/{@code NaN} 不算零值）。</li>
 *   <li><b>提取</b>：对每个 {@code <init>}/{@code <clinit>} 里的目标 PUTFIELD/PUTSTATIC，
 *       用 {@link AliasInterpreter} 做反向数据依赖切片，再检查区间内不得有表达式树外的
 *       指令。{@link #checkSafe} 判定控制依赖、try/catch 相交、局部变量依赖、
 *       INVOKESPECIAL/indy handle 的可用性、protected 跨包访问是否可桥接，
 *       以及 §4.2 的<b>最小效应防御</b>（非确定性/环境依赖/IO/日志输出黑名单 +
 *       集合无参构造与 Logger 的白名单）。被拒的真实原因会透传进
 *       {@link PatchReport}，不再是笼统的"no safe initialization expression"。</li>
 *   <li><b>单字段判定</b>：{@link #fingerprintMismatchReason} 校验多构造器下表达式一致；
 *       根构造器覆盖完整性；§4.4 允许"多根构造器 + 参数回溯"在指纹完全一致时放行。</li>
 *   <li><b>闭包迭代</b>：后续加工检查 + 依赖闭包，反复移除不合格字段直到不动点。</li>
 *   <li><b>拓扑排序</b>：{@link #topoSortFields} 按依赖排序，成环则整组拒绝。</li>
 *   <li><b>生成</b>：protected 桥接、私有调用改写、字段写入改写、<b>每字段一个独立静态直线
 *       方法</b>（§5.1）、hidden class 装配。
 *       <p>之所以做成"每字段一个方法"而不是把所有切片串进一个方法：方法边界就是异常边界。
 *       串成一个方法时，其中一个切片抛异常（例如它调的辅助方法炸了）会让<b>其后所有字段</b>
 *       都被跳过；而每字段一个方法后，宿主 {@link PatchPlan 驱动}就能逐字段 try/catch，
 *       失败隔离且能记账。反过来，若想在单方法内做隔离就得写 try/catch → 异常表 →
 *       StackMapTable → 伴生类必须 {@code COMPUTE_FRAMES}，正是 §5.1 要避免的
 *       "类加载期帧计算死锁"。每个方法的体仍是纯直线代码，{@code COMPUTE_MAXS} 足够。</p></li>
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
 *   <li><b>源字段不可变（§4.3 条件 A/B）</b>：{@code f} 带 {@code ACC_FINAL}，或者
 *       {@code f} 是 {@code private} 且全 Nest 范围内除本次 pattern 外不存在第二处
 *       {@code PUTFIELD}（{@link NestView} 只读字节码做证明，不触发类加载）。
 *       补丁在 redefine 之后执行，读到的是字段<b>当前</b>值，所以这条证明是改写等价性的前提。</li>
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
 *   <li><b>运行期状态变迁</b>：GETFIELD 读到的是当前值，不是构造时的值。源字段侧由 §4.3
 *       条件 A/B 证明兜住；但依赖链更深处的字段（见下一条）仍然只是"当前值"。</li>
 *   <li><b>间接依赖</b>：依赖检查只看提取片段里的直接 GETFIELD/GETSTATIC。
 *       若 {@code a = compute()} 而 {@code compute()} 读了被拒绝的新增字段 b，
 *       a 会静默拿到 b 的默认值。</li>
 *   <li><b>this 逃逸</b>：{@code names = new ArrayList<>(); init();} 中 init 可能通过
 *       this 访问 names 并加工，静态上看不出来。</li>
 *   <li><b>跨实例字段覆写检测</b>：{@link #scanParamFields} 的"private 单写"证明覆盖全 Nest，
 *       但不区分 receiver（{@code Outer.this.f} 与 {@code this.f} 一视同仁），
 *       且 Nest 成员字节码读不全时一律按"无法证明"拒绝。</li>
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
 * 字段都生成一条 {@link FieldDecision}：</p>
 * <ul>
 *   <li>{@link FieldStatus#ACCEPTED}：已生成补丁代码；</li>
 *   <li>{@link FieldStatus#NOTHING_TO_PATCH}：§4.1 T0 零值等价，本来就不需要补丁；</li>
 *   <li>{@link FieldStatus#REJECTED}：{@link FieldDecision#reason()} 给出<b>提取期记录的真实原因</b>
 *       （含被拒构造器参数槽位的根因），而不是笼统的
 *       {@code "no safe initialization expression found in ..."}。</li>
 * </ul>
 */
public class InitFix {
	private static final String STATIC_PATCH_PREFIX   = "initStatic$";
	private static final String INSTANCE_PATCH_PREFIX = "init$";
	private static final String PATCH_SUFFIX          = "$$HotswapPatch";

	private static final long PENDING_TTL_NANOS     = TimeUnit.MINUTES.toNanos(5);
	private static final int  MAX_DETAILED_FAILURES = 5;

	/**
	 * 详细失败日志的全局配额：逐实例驱动下同一轮可能有成千上万个实例各自失败，
	 * 逐个打栈会淹没日志（{@code docs/INIT_FIX.md} §5.2 的 {@code handleFieldException}）。
	 */
	private static final AtomicInteger DETAILED_FAILURE_LOGS = new AtomicInteger();

	/** {@code @HotswapReinit} 的描述符（纯字符串匹配，不加载注解类）。 */
	private static final String REINIT_DESC      = "Lnipx/annotation/HotswapReinit;";
	/** {@code @HotswapReinit} 的 {@code mode} 元素类型描述符。 */
	private static final String REINIT_MODE_DESC = "Lnipx/annotation/HotswapReinit$Mode;";

	/** 一个被 {@code @HotswapReinit} 标注的字段及其写入协议。 */
	private record ReinitField(boolean overwrite) { }

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
	 * 待补字段台账 {@code FieldLedger}（{@code docs/INIT_FIX.md} §3.4）：
	 * {@code Class -> (字段名 -> 未补原因)}。
	 *
	 * <p>解决的问题：redefine 一旦成功，类结构就<b>不可逆</b> —— 宿主类已在物理内存里
	 * 携带了新字段，而字段可能没被补上（分析期被拒、运行期抛异常、依赖失败、或 redefine
	 * 本身失败）。如果不记账，下一轮的候选集只看"本轮 Diff 新增字段"，这些字段就会被永久遗忘
	 * （它们已经"存在"了，再也不会出现在 Diff 里）。</p>
	 *
	 * <p>键是弱引用的 {@code Class<?>}，值是纯字符串 —— 与 {@link #PENDING}/{@link #REPORTS}
	 * 同一策略：不阻止类卸载、不给 Metaspace 添强引用。类被回收后台账自动消失。</p>
	 */
	private static final Map<Class<?>, Map<String, String>> LEDGER =
	 Collections.synchronizedMap(new WeakHashMap<>());

	/** 台账里的一条未决字段。 */
	public record UnpatchedField(String fieldName, String reason) { }

	/** 只读视图：当前台账里该宿主的未决字段（测试与诊断用）。 */
	public static Set<UnpatchedField> getUnpatchedFields(Class<?> host) {
		if (host == null) return Set.of();
		synchronized (LEDGER) {
			Map<String, String> m = LEDGER.get(host);
			if (m == null || m.isEmpty()) return Set.of();
			Set<UnpatchedField> out = new LinkedHashSet<>();
			m.forEach((k, v) -> out.add(new UnpatchedField(k, v)));
			return out;
		}
	}

	private static Map<String, String> ledgerSnapshot(Class<?> host) {
		synchronized (LEDGER) {
			Map<String, String> m = LEDGER.get(host);
			return m == null ? Map.of() : new LinkedHashMap<>(m);
		}
	}

	/** 记入台账（同名字段覆盖原因，保留最新一次）。 */
	private static void ledgerPut(Class<?> host, String fieldName, String reason) {
		if (host == null || fieldName == null) return;
		String r = reason == null ? "unpatched" : reason;
		if (r.length() > 200) r = r.substring(0, 200) + "...";
		synchronized (LEDGER) {
			LEDGER.computeIfAbsent(host, k -> new LinkedHashMap<>()).put(fieldName, r);
		}
	}

	/** 出账：字段本轮已解决（补上、或判定无需补）。 */
	private static void ledgerRemove(Class<?> host, String fieldName) {
		if (host == null) return;
		synchronized (LEDGER) {
			Map<String, String> m = LEDGER.get(host);
			if (m == null) return;
			m.remove(fieldName);
			if (m.isEmpty()) LEDGER.remove(host);
		}
	}

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

	private record BuiltPatch(byte[] bytes, PatchPlan plan, PatchReport report) {
		boolean hasPatch() { return bytes != null; }
	}

	/**
	 * 单个字段的补丁任务（{@code docs/INIT_FIX.md} §5.2）。
	 * @param methodName   伴生类里承载该字段的独立静态方法名
	 * @param fieldName    字段名（报告/失败台账的键）
	 * @param isStatic     静态字段（方法无参，整个补丁只调用一次）
	 * @param dependencies 本字段切片读取的其它<b>本轮受补</b>字段
	 */
	public record FieldPatchTask(
	 String methodName, String fieldName, boolean isStatic, Set<String> dependencies) {

		public FieldPatchTask {
			dependencies = dependencies == null ? Set.of() : Set.copyOf(dependencies);
		}
	}

	/**
	 * 一次热更的补丁计划：按拓扑序排列的逐字段任务（§5.1 每字段独立静态方法 +
	 * §5.2 宿主驱动调度）。
	 */
	public record PatchPlan(List<FieldPatchTask> instanceTasks, List<FieldPatchTask> staticTasks) {
		public static final PatchPlan EMPTY = new PatchPlan(List.of(), List.of());

		public PatchPlan {
			instanceTasks = instanceTasks == null ? List.of() : List.copyOf(instanceTasks);
			staticTasks = staticTasks == null ? List.of() : List.copyOf(staticTasks);
		}

		public boolean isEmpty() { return instanceTasks.isEmpty() && staticTasks.isEmpty(); }

		public boolean hasStatic() { return !staticTasks.isEmpty(); }
		public boolean hasInstance() { return !instanceTasks.isEmpty(); }

		public List<FieldPatchTask> allTasks() {
			List<FieldPatchTask> all = new ArrayList<>(staticTasks.size() + instanceTasks.size());
			all.addAll(staticTasks);
			all.addAll(instanceTasks);
			return all;
		}
	}

	private record PendingPatch(
	 PatchPlan plan,
	 byte[] bytes,
	 List<WeakReference<Object>> instanceSnapshot,
	 long createdNanos) {

		PendingPatch {
			if (!plan.instanceTasks().isEmpty() && instanceSnapshot == null) {
				throw new IllegalStateException(
				 "instance tasks require a non-null instance snapshot");
			}
		}

		boolean hasStatic() { return plan.hasStatic(); }
		boolean hasInstance() { return plan.hasInstance(); }

		PendingPatch withSnapshot(List<WeakReference<Object>> snapshot) {
			return new PendingPatch(
			 plan, bytes,
			 new ArrayList<>(snapshot),
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

	/**
	 * 一次构造器参数扫描的结果。
	 * @param accepted 可用作参数回溯的槽位映射
	 * @param rejected 被拒的槽位 -> 拒绝原因（按槽位记录，供 {@link #checkSafe}
	 *                 把"真实原因"透传进 {@link PatchReport}，而不是笼统的
	 *                 "depends on local variables"）
	 */
	private record ParamScan(Map<Integer, ParamField> accepted, Map<Integer, String> rejected) {
		static final ParamScan EMPTY = new ParamScan(Map.of(), Map.of());
	}

	/** 单个字段的放行决策状态。 */
	public enum FieldStatus {
		/** 已生成补丁代码。 */
		ACCEPTED,
		/**
		 * T0 零值等价（{@code docs/INIT_FIX.md} §4.1）：存量实例与静态环境本来就是该类型的
		 * 默认值，零开销放行，不生成补丁，也不告警。
		 */
		NOTHING_TO_PATCH,
		/** 未通过安全门，未生成补丁；{@link FieldDecision#reason()} 给出原因。 */
		REJECTED
	}

	/**
	 * 单个字段的放行决策。
	 * @param status   放行状态
	 * @param reason   被拒时的原因（{@link FieldStatus#REJECTED} 才有意义）
	 * @param warnings 已放行但带已知风险的说明（例如 §1.1 注解豁免了"构造器里用过该字段"
	 *                 这条门时，构造器里的那部分用法不会被补丁重放）。空表示无风险。
	 */
	public record FieldDecision(FieldStatus status, String reason, List<String> warnings) {
		public FieldDecision {
			warnings = warnings == null ? List.of() : List.copyOf(warnings);
		}

		public static final FieldDecision ACCEPTED         = new FieldDecision(FieldStatus.ACCEPTED, null, List.of());
		public static final FieldDecision NOTHING_TO_PATCH =
		 new FieldDecision(FieldStatus.NOTHING_TO_PATCH, null, List.of());

		public static FieldDecision rejected(String reason) {
			return new FieldDecision(FieldStatus.REJECTED, reason, List.of());
		}

		/** 放行但带风险标记（报告里可见，同时会打一条 warn 日志）。 */
		public static FieldDecision accepted(String warning) {
			return new FieldDecision(FieldStatus.ACCEPTED, null,
			 warning == null ? List.of() : List.of(warning));
		}

		/** @return 是否已为该字段生成补丁代码 */
		public boolean accepted() {
			return status == FieldStatus.ACCEPTED;
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

		Set<String> addedStaticFields   = diff.addedStaticFields;
		Set<String> addedInstanceFields = diff.addedInstanceFields;

		Map<String, String> pending = ledgerSnapshot(host);
		if (addedStaticFields.isEmpty() && addedInstanceFields.isEmpty()
		    && !hasReinitAnnotation(newBytes) && pending.isEmpty()) {
			REPORTS.put(host, new PatchReport(Map.of(), Map.of(), false));
			return;
		}
		if (!pending.isEmpty()) {
			log("FieldLedger: retrying " + pending.size() + " unpatched field(s) of "
			    + diff.newClass.name + ": " + pending);
		}

		String className = diff.newClass.name;
		try {
			BuiltPatch built = buildPatch(host, newBytes, className,
			 addedStaticFields, addedInstanceFields, pending);
			// if (built == null) return;

			REPORTS.put(host, built.report());
			// 分析级台账：被拒的记入（下轮重试），放行/零值等价的出账。
			// 运行期失败由 applyPatch 追加记入 —— 两者顺序天然正确（applyPatch 在后）。
			ledgerUpdateFromReport(host, built.report());

			if (!built.hasPatch()) return;

			List<WeakReference<Object>> snapshot =
			 built.plan().hasInstance() ? snapshotInstances(host) : null;

			PendingPatch patch = new PendingPatch(
			 built.plan(), built.bytes(), snapshot, System.nanoTime());
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
	 Set<String> addedStaticFields, Set<String> addedInstanceFields,
	 Map<String, String> pendingLedger) {

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
		int                      rootCtorCount = 0;
		for (MethodNode init : initMethods) {
			if (isRootConstructor(newClass, init, rootCtorCache)) rootCtorCount++;
		}
		if (rootCtorCount > 1) {
			log("Class " + className + " has " + rootCtorCount
			    + " independent root constructors");
		}

		// 宿主 Nest 视图：§4.3 的"全 Nest 单写证明"与 §4.1 T0 的"无写入"判定都基于它。
		// 全程只读字节码，不调用 Class.forName，避免在 transform 线程上触发类加载死锁。
		NestView nest = NestView.of(host, newClass);

		// ==================== §1.1 存量重置扩展：候选集 ====================
		// 默认只处理新增字段；标了 @HotswapReinit 的已有字段按显式声明进入候选集。
		Map<String, ReinitField> reinitFields         = scanReinitFields(newClass);
		Set<String>              targetInstanceFields = new LinkedHashSet<>(addedInstanceFields);
		Set<String>              targetStaticFields   = new LinkedHashSet<>(addedStaticFields);
		Set<String>              forceWriteFields     = new LinkedHashSet<>();
		for (Map.Entry<String, ReinitField> e : reinitFields.entrySet()) {
			FieldNode fn = fieldNodes.get(e.getKey());
			if (fn == null) continue;   // 注解在不存在的字段上（理论上不可能）
			boolean isStatic = (fn.access & Opcodes.ACC_STATIC) != 0;
			(isStatic ? targetStaticFields : targetInstanceFields).add(e.getKey());
			if (e.getValue().overwrite()) forceWriteFields.add(e.getKey());
			log("@HotswapReinit on " + className + "." + e.getKey()
			    + " -> mode=" + (e.getValue().overwrite() ? "OVERWRITE" : "CONDITIONAL"));
		}
		if (!reinitFields.isEmpty()) {
			log("@HotswapReinit 扩展候选集 for " + className
			    + ": instance=" + targetInstanceFields + ", static=" + targetStaticFields);
		}

		// §3.4 台账并集：候选字段集合 = 本轮 Diff 新增字段 ∪ 台账内未决字段（∪ 注解字段）。
		// 这些字段的写入语句仍在构造函数/<clinit> 里，所以仍走同一套提取与安全门；
		// 之所以必须显式并回来，是因为它们已经"存在"于旧字节码，永远不会再出现在 Diff 里。
		int retried = 0;
		for (String f : pendingLedger.keySet()) {
			FieldNode fn = fieldNodes.get(f);
			if (fn == null) {
				// 字段已从新版本里消失：无法再补，按已解决出账（避免台账里留死条目）
				ledgerRemove(host, f);
				log("FieldLedger: dropping " + className + "." + f
				    + " (field no longer declared)");
				continue;
			}
			boolean isStatic = (fn.access & Opcodes.ACC_STATIC) != 0;
			(isStatic ? targetStaticFields : targetInstanceFields).add(f);
			retried++;
		}
		if (retried > 0) {
			log("FieldLedger: " + retried + " field(s) of " + className
			    + " re-entered the candidate set: " + pendingLedger.keySet());
		}

		// ==================== 实例字段提取 ====================
		Map<String, List<FieldExtract>> instanceExtracts   = new LinkedHashMap<>();
		Set<String>                     selfAssignedFields = new HashSet<>();
		Map<String, String>             instanceReasons    = new LinkedHashMap<>();
		for (MethodNode init : initMethods) {
			log("Extracting field init for " + className + "." + init.name + "()");
			boolean                  fromRoot    = isRootConstructor(newClass, init, rootCtorCache);
			ParamScan                scan        = scanParamFields(host, newClass, init, nest);
			Map<Integer, ParamField> paramFields = scan.accepted();
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
			 host, className, init, targetInstanceFields, false, privateMethods,
			 paramFields, scan.rejected(), fromRoot, selfAssignedFields, instanceReasons);

			for (Map.Entry<String, FieldExtract> fe : perField.entrySet()) {
				instanceExtracts
				 .computeIfAbsent(fe.getKey(), x -> new ArrayList<>())
				 .add(fe.getValue());
			}
		}

		// ==================== 静态字段提取 ====================
		Map<String, List<FieldExtract>> staticExtracts = new LinkedHashMap<>();
		Map<String, String>             staticReasons  = new LinkedHashMap<>();
		if (clinitMethod != null) {
			Map<String, FieldExtract> perField = extractFieldInits(
			 host, className, clinitMethod, targetStaticFields,
			 true, privateMethods, Map.of(), Map.of(), true, null, staticReasons);
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
			    && targetStaticFields.contains(field.name)
			    && field.value != null
			    && !staticExtracts.containsKey(field.name)) {
				List<AbstractInsnNode> insns = List.of(
				 new LdcInsnNode(field.value),
				 new FieldInsnNode(Opcodes.PUTSTATIC, className, field.name, field.desc)
				);
				staticExtracts.computeIfAbsent(field.name, x -> new ArrayList<>())
				 .add(new FieldExtract(insns, Map.of(), false, false, new HashSet<>(insns)));
				log("Extracted constant field init from ConstantValue: "
				    + className + "." + field.name);
			}
		}

		List<MethodNode> staticScanMethods = new ArrayList<>(initMethods);
		if (clinitMethod != null) staticScanMethods.add(clinitMethod);

		// ==================== 阶段 0.5：T0 零值等价过滤（§4.1） ====================
		// 显式 = null / = 0 / = false、仅声明未赋值、以及 static ConstantValue 按位为零的字段，
		// 存量实例与静态环境本来就是默认值：零开销放行，不生成补丁（也不会生成恒等 CAS 写）。
		// 浮点按位判定，-0.0f / -0.0d / NaN 的位模式非零，不算零值等价。
		Set<String> zeroInstanceFields = new LinkedHashSet<>();
		for (String f : targetInstanceFields) {
			if (selfAssignedFields.contains(f)) continue;
			if (reinitFields.containsKey(f)) continue;   // §1.1：显式重置请求不受 T0 影响
			if (isZeroEquivalentField(nest, newClass, fieldNodes.get(f), false, instanceExtracts)) {
				zeroInstanceFields.add(f);
			}
		}
		Set<String> zeroStaticFields = new LinkedHashSet<>();
		for (String f : targetStaticFields) {
			if (reinitFields.containsKey(f)) continue;
			if (isZeroEquivalentField(nest, newClass, fieldNodes.get(f), true, staticExtracts)) {
				zeroStaticFields.add(f);
			}
		}
		for (String f : zeroInstanceFields) instanceExtracts.remove(f);
		for (String f : zeroStaticFields) staticExtracts.remove(f);
		if (!zeroInstanceFields.isEmpty() || !zeroStaticFields.isEmpty()) {
			log("Nothing to patch (zero-value equivalent) in " + className
			    + ": instance=" + zeroInstanceFields + ", static=" + zeroStaticFields);
		}

		// ==================== 阶段 1：单字段判定（实例） ====================
		// 先把所有待处理字段标记为拒绝；有 extract 的会在此后覆盖为 ACCEPTED 或被具体原因拒绝。
		// 这样即便某字段在 extractFieldInits 里就被拒（不进 instanceExtracts），
		// PatchReport 里依然有它的一条决策记录，且原因是提取期记录下来的真实原因。
		Map<String, FieldDecision> instanceDecisions = new LinkedHashMap<>();
		for (String f : targetInstanceFields) {
			if (zeroInstanceFields.contains(f)) {
				instanceDecisions.put(f, FieldDecision.NOTHING_TO_PATCH);
				continue;
			}
			String reason = selfAssignedFields.contains(f)
			 ? "self-assignment from constructor parameter (patch would be a no-op)"
			 : instanceReasons.getOrDefault(f,
			 "no safe initialization expression found in constructors");
			instanceDecisions.put(f, FieldDecision.rejected(reason));
		}
		Set<String> acceptedInstance = new LinkedHashSet<>();
		for (Map.Entry<String, List<FieldExtract>> e : instanceExtracts.entrySet()) {
			String             fieldName = e.getKey();
			List<FieldExtract> extracts  = e.getValue();

			int fromRootCount = 0;
			for (FieldExtract fe : extracts) {
				if (fe.fromRootCtor()) fromRootCount++;
			}

			String refuseReason;
			if (fromRootCount == 0) {
				refuseReason = "field is only initialized in delegating constructors";
			} else if (rootCtorCount > 1 && fromRootCount < rootCtorCount) {
				refuseReason = "field is only initialized in " + fromRootCount
				               + " of " + rootCtorCount + " root constructors";
			} else {
				// §4.4 构造器共识放宽：多根构造器 + 参数回溯并非绝对禁止。
				// 上面的分支已保证所有根构造器都覆盖了该字段，只要参数替换完成后的
				// 指令指纹 100% 一致，各构造路径的初始语义就是同构的，可以安全放行。
				// （源字段的不可变性由 §4.3 的证明在 scanParamFields 阶段保证。）
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
		// 同上：先全部标记拒绝（带提取期记录的真实原因），再让 extract 命中的字段覆盖。
		Map<String, FieldDecision> staticDecisions = new LinkedHashMap<>();
		for (String f : targetStaticFields) {
			if (zeroStaticFields.contains(f)) {
				staticDecisions.put(f, FieldDecision.NOTHING_TO_PATCH);
				continue;
			}
			staticDecisions.put(f, FieldDecision.rejected(
			 staticReasons.getOrDefault(f,
				"no safe initialization expression found in <clinit>")));
		}
		Set<String> acceptedStatic = new LinkedHashSet<>();
		for (String f : staticExtracts.keySet()) {
			acceptedStatic.add(f);
			staticDecisions.put(f, FieldDecision.ACCEPTED);
		}

		// ==================== 阶段 1.5 + 阶段 2：闭包迭代 ====================
		// 依赖门禁的判定基准：候选集减去 T0 零值等价字段。
		// 零值字段的存量值本来就是该类型的默认值，补丁不生成也"已经是对的"，
		// 因此它不构成"读到未补的新字段"—— 若不排除，a = f(b)（b = 0）会被误杀：
		// b 在 targetInstanceFields 里、却不在 acceptedInstance 里。
		// 两个集合在循环中都不被修改，故在建表后一次算好、循环内复用。
		Set<String> depInstance = new LinkedHashSet<>(targetInstanceFields);
		depInstance.removeAll(zeroInstanceFields);
		Set<String> depStatic = new LinkedHashSet<>(targetStaticFields);
		depStatic.removeAll(zeroStaticFields);

		// 注解豁免过的字段只告警一次，别在 while 循环里刷屏。
		Set<String> warnedExemptions = new HashSet<>();
		boolean     changed          = true;
		while (changed) {
			changed = false;

			for (String f : new ArrayList<>(acceptedInstance)) {
				String reason = subsequentProcessingReason(
				 f, acceptedInstance, instanceExtracts, initMethods,
				 className, false, fieldNodes.get(f));
				if (reason == null) continue;
				if (reinitFields.containsKey(f)) {
					String warning = warnExemptedReinit(
					 className, f, fieldNodes.get(f), reason, false, warnedExemptions);
					instanceDecisions.put(f, FieldDecision.accepted(warning));
					continue;
				}
				log("Field '" + f + "' refused (subsequent processing): " + reason);
				acceptedInstance.remove(f);
				instanceDecisions.put(f, FieldDecision.rejected("subsequent processing: " + reason));
				changed = true;
			}

			for (String f : new ArrayList<>(acceptedStatic)) {
				String reason = subsequentProcessingReason(
				 f, acceptedStatic, staticExtracts, staticScanMethods,
				 className, true, fieldNodes.get(f));
				if (reason == null) continue;
				if (reinitFields.containsKey(f)) {
					String warning = warnExemptedReinit(
					 className, f, fieldNodes.get(f), reason, true, warnedExemptions);
					staticDecisions.put(f, FieldDecision.accepted(warning));
					continue;
				}
				log("Static field '" + f + "' refused (subsequent processing): " + reason);
				acceptedStatic.remove(f);
				staticDecisions.put(f, FieldDecision.rejected("subsequent processing: " + reason));
				changed = true;
			}

			for (String f : new ArrayList<>(acceptedInstance)) {
				String reason = depReason(
				 f, instanceExtracts.get(f), className,
				 acceptedInstance, acceptedStatic,
				 depInstance, depStatic);
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
				 null, depStatic);
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

		// ==================== 阶段 3：逐字段发射（§5.1 每字段独立静态方法） ====================
		// 每个放行字段单独一个静态直线方法：一行抛异常只影响该字段，不会像"单方法承载全部
		// 字段"那样跳过其后所有字段（§5.2 的驱动依赖这一点做失败隔离）。
		String      hostInternal      = Type.getInternalName(host);
		Set<String> conditionalFields = new HashSet<>(targetInstanceFields);
		conditionalFields.addAll(targetStaticFields);
		conditionalFields.removeAll(forceWriteFields);

		ClassNode patch = new ClassNode();
		patch.version = Math.max(hostVersion, Opcodes.V11);
		patch.access = Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
		patch.name = className + PATCH_SUFFIX;
		patch.superName = "java/lang/Object";

		Set<String>          usedMethodNames = new HashSet<>();
		List<FieldPatchTask> instanceTasks   = new ArrayList<>();
		List<FieldPatchTask> staticTasks     = new ArrayList<>();

		for (String fieldName : orderedInstance) {
			FieldExtract chosen = null;
			for (FieldExtract fe : instanceExtracts.get(fieldName)) {
				if (fe.fromRootCtor()) {
					chosen = fe;
					break;
				}
			}
			if (chosen == null) chosen = instanceExtracts.get(fieldName).get(0);

			String method = uniqueMethodName(INSTANCE_PATCH_PREFIX, fieldName, usedMethodNames);
			List<AbstractInsnNode> body = rewriteFieldSlice(
			 hostInternal, className, new ArrayList<>(chosen.instructions()),
			 chosen.protectedAccesses(), conditionalFields, forceWriteFields);
			patch.methods.add(straightLineMethod(method, "(L" + className + ";)", body));
			instanceTasks.add(new FieldPatchTask(method, fieldName, false,
			 dependencyOf(fieldName, instanceExtracts, className, acceptedInstance)));
		}

		for (String fieldName : orderedStatic) {
			FieldExtract fe     = staticExtracts.get(fieldName).get(0);
			String       method = uniqueMethodName(STATIC_PATCH_PREFIX, fieldName, usedMethodNames);
			List<AbstractInsnNode> body = rewriteFieldSlice(
			 hostInternal, className, new ArrayList<>(fe.instructions()),
			 fe.protectedAccesses(), conditionalFields, forceWriteFields);
			patch.methods.add(straightLineMethod(method, "()", body));
			staticTasks.add(new FieldPatchTask(method, fieldName, true,
			 dependencyOf(fieldName, staticExtracts, className, acceptedStatic)));
		}

		PatchPlan plan = new PatchPlan(instanceTasks, staticTasks);

		PatchReport report = new PatchReport(
		 Collections.unmodifiableMap(instanceDecisions),
		 Collections.unmodifiableMap(staticDecisions),
		 !plan.isEmpty());

		if (plan.isEmpty()) {
			return new BuiltPatch(null, PatchPlan.EMPTY, report);
		}

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		patch.accept(cw);
		return new BuiltPatch(cw.toByteArray(), plan, report);
	}

	/** 一个字段的完整补丁体：protected 桥接 → 私有调用改写 → 写入协议改写。 */
	private static List<AbstractInsnNode> rewriteFieldSlice(
	 String hostInternal, String className, List<AbstractInsnNode> insns,
	 Map<AbstractInsnNode, ProtectedAccess> protectedAccesses,
	 Set<String> conditionalFields, Set<String> forceFields) {

		insns = rewriteProtectedAccesses(hostInternal, insns, protectedAccesses);
		insns = rewritePrivateInvokes(className, insns);
		insns = rewriteFieldPuts(className, insns, conditionalFields, forceFields);
		return insns;
	}

	/** 生成一个静态直线方法：{@code methodName(desc)V}，体内为切片 + RETURN。 */
	private static MethodNode straightLineMethod(
	 String methodName, String argDesc, List<AbstractInsnNode> body) {
		MethodNode m = new MethodNode(
		 Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC,
		 methodName, argDesc + "V", null, null);
		for (AbstractInsnNode i : body) m.instructions.add(i);
		m.instructions.add(new InsnNode(Opcodes.RETURN));
		return m;
	}

	/** 字段名里可能含 {@code . ; [ / < >} 等非法方法名字符（混淆器/字节码注入），替换掉。 */
	private static String uniqueMethodName(String prefix, String fieldName, Set<String> used) {
		StringBuilder sb = new StringBuilder(prefix);
		for (int i = 0; i < fieldName.length(); i++) {
			char c = fieldName.charAt(i);
			sb.append(c == '.' || c == ';' || c == '[' || c == '/' || c == '<' || c == '>' ? '_' : c);
		}
		String base = sb.toString();
		String name = base;
		for (int n = 2; !used.add(name); n++) name = base + "$" + n;
		return name;
	}

	/** 该字段切片读取的、同样在本轮受补的其它字段（§5.2 的依赖失败传播用）。 */
	private static Set<String> dependencyOf(
	 String fieldName, Map<String, List<FieldExtract>> extracts,
	 String className, Set<String> acceptedFields) {

		Set<String>        deps   = new LinkedHashSet<>();
		List<FieldExtract> feList = extracts.get(fieldName);
		if (feList == null) return deps;
		for (FieldExtract fe : feList) {
			for (AbstractInsnNode n : fe.instructions()) {
				if (!(n instanceof FieldInsnNode fld)) continue;
				if (!fld.owner.equals(className)) continue;
				if (fld.name.equals(fieldName) || !acceptedFields.contains(fld.name)) continue;
				int op = fld.getOpcode();
				if (op == Opcodes.GETFIELD || op == Opcodes.GETSTATIC) deps.add(fld.name);
			}
		}
		return deps;
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
			Set<String>        d      = new LinkedHashSet<>();
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

		List<String> order    = new ArrayList<>(fields.size());
		Set<String>  visited  = new HashSet<>();
		Set<String>  visiting = new HashSet<>();
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

	// ==================== §1.1 存量重置扩展：注解扫描 ====================

	/**
	 * 扫描带 {@code @HotswapReinit} 的字段，解析其写入协议。
	 *
	 * <p>只做<b>描述符字符串匹配</b>，不加载注解类 —— 与 §2.1 的离线层级接口同一原则：
	 * 在 transform 线程上 {@code Class.forName} 注解类型可能触发类加载死锁。
	 * 元素缺省时按注解声明取默认值 {@code OVERWRITE}。</p>
	 */
	private static Map<String, ReinitField> scanReinitFields(ClassNode newClass) {
		Map<String, ReinitField> out = new LinkedHashMap<>();
		for (FieldNode f : newClass.fields) {
			AnnotationNode reinit = findReinit(f);
			if (reinit == null) continue;
			out.put(f.name, new ReinitField(readOverwriteMode(reinit)));
		}
		return out;
	}

	private static AnnotationNode findReinit(FieldNode f) {
		AnnotationNode found = findReinit(f.visibleAnnotations);
		return found != null ? found : findReinit(f.invisibleAnnotations);
	}

	private static AnnotationNode findReinit(List<AnnotationNode> annotations) {
		if (annotations == null) return null;
		for (AnnotationNode a : annotations) {
			if (REINIT_DESC.equals(a.desc)) return a;
		}
		return null;
	}

	/**
	 * 读 {@code mode} 元素。ASM 把注解里的枚举值表示成 {@code String[]{描述符, 常量名}}。
	 * @return true 表示 {@code OVERWRITE}（默认值也是它）
	 */
	private static boolean readOverwriteMode(AnnotationNode a) {
		if (a.values == null) return true;
		for (int i = 0; i + 1 < a.values.size(); i += 2) {
			if (!"mode".equals(a.values.get(i))) continue;
			Object v = a.values.get(i + 1);
			if (v instanceof String[] enumValue && enumValue.length == 2
			    && REINIT_MODE_DESC.equals(enumValue[0])) {
				return !"CONDITIONAL".equals(enumValue[1]);
			}
			if (v instanceof String[] other) {
				log("Unexpected @HotswapReinit mode representation: "
				    + Arrays.toString(other) + "; falling back to OVERWRITE");
			}
			return true;
		}
		return true;
	}

	/**
	 * 轻量探测：新字节码里是否存在 {@code @HotswapReinit} 字段注解。
	 *
	 * <p>用途是 {@link #transform} 的快速路径：没有任何新增字段时它本来会直接返回，
	 * 但"改了已有字段初值 + 标注解"恰恰是没有任何新增字段的形态，必须放行到
	 * {@link #buildPatch}。这里跳过全部方法字节码，只读字段表与注解。</p>
	 */
	private static boolean hasReinitAnnotation(byte[] bytes) {
		final boolean[] found = {false};
		try {
			new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public FieldVisitor visitField(int access, String name, String desc,
				                               String signature, Object value) {
					return new FieldVisitor(Opcodes.ASM9) {
						@Override
						public AnnotationVisitor visitAnnotation(String adesc, boolean visible) {
							if (REINIT_DESC.equals(adesc)) found[0] = true;
							return null;
						}
					};
				}
			}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		} catch (Throwable ignored) {
			// 读不了就当没有注解：与"宁可不补"一致
		}
		return found[0];
	}

	/** 分析级台账更新：被拒 → 记入（下轮重试）；放行/T0 零值等价 → 出账。 */
	private static void ledgerUpdateFromReport(Class<?> host, PatchReport report) {
		if (report == null) return;
		for (Map.Entry<String, FieldDecision> e : report.instanceFields().entrySet()) {
			ledgerApply(host, e.getKey(), e.getValue());
		}
		for (Map.Entry<String, FieldDecision> e : report.staticFields().entrySet()) {
			ledgerApply(host, e.getKey(), e.getValue());
		}
	}

	private static void ledgerApply(Class<?> host, String fieldName, FieldDecision decision) {
		if (decision == null) return;
		if (decision.status() == FieldStatus.REJECTED) {
			ledgerPut(host, fieldName, decision.reason());
		} else {
			// ACCEPTED：补丁已生成（真正写没写成功由 applyPatch 决定）
			// NOTHING_TO_PATCH：本来就不需要补 —— 两者都不该留在台账里
			ledgerRemove(host, fieldName);
		}
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
			    || !m.owner.equals(hostClass.name)) { continue; }

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
						String where = className + "." + m.name + " at index "
						               + m.instructions.indexOf(n);
						if (isThreadAffineType(fieldNode.desc)) {
							// 按线程的值类型单独说清楚：补丁在热更线程上单线程执行，
							// 构造器里对它的使用无法重放，重算只会得到另一条线程的副本。
							return "thread-affine field " + fieldNode.desc + " is used outside its "
							       + "extraction in " + where
							       + " (patch runs single-threaded on the hotswap thread; "
							       + "the constructor's use of it cannot be replayed)";
						}
						return "field read outside any accepted extraction in " + where;
					}
				}
			}
		}
		return null;
	}

	// ==================== §1.1 注解豁免的告警 ====================

	/**
	 * {@code @HotswapReinit} 豁免了"构造器里用过该字段"这条门时，把风险显式喊出来。
	 *
	 * <p>豁免本身是用户显式声明的（§1.1），但代价必须可见：</p>
	 * <ul>
	 *   <li>补丁只重建<b>字段初始化式</b>，构造器里对它的其它用法（例如
	 *       {@code sb.get().append("aass")}）<b>不会</b>重放 —— 存量实例与正常构造的
	 *       实例在这个点上必然分叉；</li>
	 *   <li>补丁在<b>热更线程上单线程</b>执行。对 {@code ThreadLocal} 这类按线程的值，
	 *       构造器当时是在应用线程上跑的，per-thread 副本不是同一份；纯分配
	 *       （{@code withInitial}）在哪个线程做都一样，但任何"预置内容"都会丢。</li>
	 * </ul>
	 * <p>需要按实例、按正确线程补齐时，用 {@link nipx.annotation.OnReload}
	 * （跑在 {@code Core.app} 的应用线程上、逐实例调用）。</p>
	 * @param warned 去重集合，避免 while 循环里重复告警
	 * @return 报告里可见的告警文本
	 */
	private static String warnExemptedReinit(
	 String className, String fieldName, FieldNode fieldNode, String gateReason,
	 boolean isStatic, Set<String> warned) {

		String warning = exemptionWarning(fieldNode, gateReason);
		if (warned.add(fieldName)) {
			HotSwapAgent.warn("@HotswapReinit 豁免了 " + className + "."
			                  + fieldName + "（" + (isStatic ? "static" : "instance") + "）的门禁："
			                  + gateReason
			                  + "；补丁只重建字段初始化式、且在热更线程上单线程执行，"
			                  + "构造器里的这部分用法不会被重放");
		}
		return warning;
	}

	/** 报告里那条告警的文本（按类型锐化）。 */
	private static String exemptionWarning(FieldNode fieldNode, String gateReason) {
		StringBuilder sb = new StringBuilder();
		sb.append("@HotswapReinit 豁免了门禁（").append(gateReason).append("）："
		                                                                  + "补丁只重建该字段的初始化式，构造器里对它的这部分用法不会被重放");
		if (fieldNode != null && isThreadAffineType(fieldNode.desc)) {
			sb.append("；该字段是按线程的值（").append(fieldNode.desc)
			 .append("），补丁在热更线程上单线程执行，构造线程的 per-thread 副本无法还原，"
			         + "如需按实例/按应用线程补齐请用 @OnReload");
		}
		return sb.toString();
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
		if (n instanceof FieldInsnNode f) { return f.owner + "." + f.name + ":" + f.desc; }
		if (n instanceof MethodInsnNode m) { return m.owner + "." + m.name + m.desc + (m.itf ? ":itf" : ""); }
		if (n instanceof VarInsnNode v) { return "v" + v.var; }
		if (n instanceof LdcInsnNode l) { return "ldc(" + describeLdcConstant(l.cst) + ")"; }
		if (n instanceof TypeInsnNode t) { return t.desc; }
		if (n instanceof IntInsnNode in) { return "int" + in.operand; }
		if (n instanceof MultiANewArrayInsnNode m) { return "manaa:" + m.desc + ":" + m.dims; }
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
		if (n instanceof IincInsnNode inc) { return "iinc" + inc.var + "+" + inc.incr; }
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
			int           len = Array.getLength(a);
			StringBuilder sb  = new StringBuilder("[");
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

	// ==================== 宿主 Nest 视图（§4.3 / §4.1 的字节码底座） ====================

	/**
	 * 宿主的 Nest 视图：完整成员清单与紧凑的 Nest 字段写入索引。
	 *
	 * <p>用于两件事：{@code docs/INIT_FIX.md} §4.3 的"全 Nest 单写证明"，以及 §4.1 T0
	 * 的"该字段在任何地方都没被写过"判定。</p>
	 *
	 * <p><b>只读字节码，绝不 {@code Class.forName}</b>：优先取
	 * {@link HotSwapAgent#bytecodeCache}（内存里当前生效的新版本），退化为 ClassLoader
	 * 资源流。任一成员读不到时 {@link #complete()} 为 false，调用方必须按"无法证明"处理
	 * （拒绝），而不是当作"没有第二处写入"。</p>
	 */
	private static final class NestView {
		private final List<ClassHierarchyOracle.NestFieldWrite> writes;
		private final boolean complete;

		private NestView(List<ClassHierarchyOracle.NestFieldWrite> writes, boolean complete) {
			this.writes = writes;
			this.complete = complete;
		}

		static NestView of(Class<?> host, ClassNode hostClass) {
			ClassLoader loader = host == null ? null : host.getClassLoader();
			try {
				HierarchyTreeOracle oracle = new HierarchyTreeOracle(loader, hostClass);
				oracle.getNestMembers(hostClass.name);
				return new NestView(oracle.getNestFieldWrites(hostClass.name), true);
			} catch (IllegalStateException unavailable) {
				HotSwapAgent.warn("Nest scan incomplete for " + hostClass.name + ": " + unavailable.getMessage());
				return new NestView(List.of(), false);
			}
		}

		boolean complete() { return complete; }

		/**
		 * 统计 Nest 内针对指定字段的写指令总数。
		 * <p>只有 {@link #complete()} 为 true 时结果才可用于"零写入"断言。</p>
		 */
		int countPuts(String ownerInternal, String name, String desc, int putOp) {
			int n = 0;
			for (ClassHierarchyOracle.NestFieldWrite write : writes) {
				FieldInsnNode field = write.instruction;
				if (field.getOpcode() != putOp) continue;
				if (!field.owner.equals(ownerInternal)
				    || !field.name.equals(name)
				    || !field.desc.equals(desc)) continue;
				n++;
			}
			return n;
		}

		/**
		 * 找出 Nest 内除 {@code self} 之外的第一处写指令。
		 * @return {@code 类.方法描述符} 形式的位置；null 表示不存在第二处写入
		 */
		String firstOtherPut(String ownerInternal, String name, String desc,
		                     int putOp, FieldInsnNode self) {
			for (ClassHierarchyOracle.NestFieldWrite write : writes) {
				FieldInsnNode field = write.instruction;
				if (field.getOpcode() != putOp || field == self) continue;
				if (!field.owner.equals(ownerInternal)
				    || !field.name.equals(name)
				    || !field.desc.equals(desc)) continue;
				return write.className + "." + write.methodName + write.methodDesc;
			}
			return null;
		}
	}

	// ==================== T0 零值等价（§4.1） ====================

	/**
	 * T0 判定：该新增字段是否"零值等价"——存量实例与静态环境本来就已经是默认值。
	 *
	 * <p>判据：</p>
	 * <ol>
	 *   <li>静态字段带 {@code ConstantValue}：按位判零（{@code static final String S = ""}
	 *       <b>不是</b>零值，因为存量静态环境里是 {@code null}）；</li>
	 *   <li>全 Nest 范围内没有任何写指令：仅声明未赋值，类型默认值即最终值；</li>
	 *   <li>该字段的每一次写都能拿到安全切片，且切片都是"压入类型零值 + 写字段"。</li>
	 * </ol>
	 *
	 * <p>浮点按位判定：{@code -0.0f} / {@code -0.0d} / {@code NaN} 的位模式非零，
	 * 因此<b>不</b>算零值等价（§4.1 明确排除）。第 3 条要求"切片数与写指令数相等"，
	 * 避免出现"只看得到零值那次写、看不到另一次危险写"的静默误判。</p>
	 */
	private static boolean isZeroEquivalentField(
	 NestView nest, ClassNode newClass, FieldNode field, boolean isStatic,
	 Map<String, List<FieldExtract>> extracts) {

		if (field == null) return false;

		if (isStatic && field.value != null) {
			return isZeroConstant(field.value);
		}
		if (!nest.complete()) return false;

		int putOp  = isStatic ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD;
		int writes = nest.countPuts(newClass.name, field.name, field.desc, putOp);
		if (writes == 0) return true;

		List<FieldExtract> feList = extracts.get(field.name);
		if (feList == null || feList.size() != writes) return false;

		for (FieldExtract fe : feList) {
			if (!isZeroValueExtract(fe.instructions(), isStatic, field.desc)) return false;
		}
		return true;
	}

	/** 按位判零的 {@code ConstantValue}（引用常量只有 {@code null} 才是零值，而 null 不落属性）。 */
	private static boolean isZeroConstant(Object cst) {
		if (cst instanceof Integer i) return i == 0;
		if (cst instanceof Long l) return l == 0L;
		if (cst instanceof Float f) return Float.floatToRawIntBits(f) == 0;
		if (cst instanceof Double d) return Double.doubleToRawLongBits(d) == 0;
		if (cst instanceof Short s) return s == 0;
		if (cst instanceof Byte b) return b == 0;
		if (cst instanceof Character c) return c == '\u0000';
		if (cst instanceof Boolean b) return !b;
		return false;
	}

	/**
	 * 切片是否就是"压入类型零值 + 写字段"。
	 * <p>实例字段的期望形态是 {@code [ALOAD 0, <零值>, PUTFIELD]}，
	 * 静态字段是 {@code [<零值>, PUTSTATIC]}（切片经过提取器裁剪，已不含 label/frame）。</p>
	 */
	private static boolean isZeroValueExtract(
	 List<AbstractInsnNode> insns, boolean isStatic, String desc) {

		int expected = isStatic ? 2 : 3;
		if (insns.size() != expected) return false;

		int valueIdx = isStatic ? 0 : 1;
		int putIdx   = isStatic ? 1 : 2;

		if (!isStatic) {
			if (!(insns.get(0) instanceof VarInsnNode v
			      && v.getOpcode() == Opcodes.ALOAD && v.var == 0)) { return false; }
		}
		if (!(insns.get(putIdx) instanceof FieldInsnNode f)
		    || f.getOpcode() != (isStatic ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD)) { return false; }

		return isZeroConstantInsn(insns.get(valueIdx), desc);
	}

	/** 单条指令是否是字段描述符对应的类型零值。 */
	private static boolean isZeroConstantInsn(AbstractInsnNode n, String desc) {
		if (n == null || desc == null || desc.isEmpty()) return false;

		char    c   = desc.charAt(0);
		boolean ref = c == 'L' || c == '[';
		boolean isJ = c == 'J';
		boolean isF = c == 'F';
		boolean isD = c == 'D';
		boolean isI = !ref && !isJ && !isF && !isD;   // Z/B/C/S/I

		if (ref) return n.getOpcode() == Opcodes.ACONST_NULL;
		if (n.getOpcode() == Opcodes.ICONST_0) return isI;
		if (n.getOpcode() == Opcodes.LCONST_0) return isJ;
		if (n.getOpcode() == Opcodes.FCONST_0) return isF;
		if (n.getOpcode() == Opcodes.DCONST_0) return isD;

		if (n instanceof LdcInsnNode ldc) {
			Object cst = ldc.cst;
			if (cst instanceof Integer i) return isI && i == 0;
			if (cst instanceof Long l) return isJ && l == 0L;
			if (cst instanceof Float f) return isF && Float.floatToRawIntBits(f) == 0;
			if (cst instanceof Double d) return isD && Double.doubleToRawLongBits(d) == 0;
		}
		return false;
	}

	// ==================== P0 最小效应防御（§4.2 的完整 8 位掩码属于 P2） ====================

	/** 基础集合的无参构造：{@code ALLOC_PURE}，永不拦截（§8 P0-2 要求"严防误杀"）。 */
	private static final Set<String> PURE_NOARG_CTOR_OWNERS = Set.of(
	 "java/util/ArrayList", "java/util/LinkedList", "java/util/Vector", "java/util/Stack",
	 "java/util/HashMap", "java/util/LinkedHashMap", "java/util/TreeMap", "java/util/Hashtable",
	 "java/util/WeakHashMap", "java/util/IdentityHashMap",
	 "java/util/HashSet", "java/util/LinkedHashSet", "java/util/TreeSet",
	 "java/util/ArrayDeque", "java/util/PriorityQueue",
	 "java/util/concurrent/ConcurrentHashMap", "java/util/concurrent/ConcurrentLinkedQueue",
	 "java/util/concurrent/CopyOnWriteArrayList", "java/util/concurrent/CopyOnWriteArraySet",
	 "java/lang/StringBuilder", "java/lang/StringBuffer");

	/** Logger 工厂：新增 {@code private static final Logger LOG = ...} 字段依赖它。 */
	private static final Set<String> LOGGER_FACTORY_OWNERS = Set.of(
	 "org/slf4j/LoggerFactory", "java/util/logging/Logger",
	 "org/apache/logging/log4j/LogManager", "org/apache/commons/logging/LogFactory");

	/** Logger 类型：其 {@code isXxxEnabled}/{@code getXxx} 是纯查询，放行；落地方法走黑名单。 */
	private static final Set<String> LOGGER_TYPES = Set.of(
	 "org/slf4j/Logger", "java/util/logging/Logger",
	 "org/apache/logging/log4j/Logger", "org/apache/commons/logging/Log");

	/** ThreadLocal 家族（{@code InheritableThreadLocal} 复用的是同一套方法名）。 */
	private static final Set<String> THREAD_LOCAL_OWNERS = Set.of(
	 "java/lang/ThreadLocal", "java/lang/InheritableThreadLocal");

	/**
	 * 字段描述符层面的"按线程的值"类型。
	 *
	 * <p>这类字段在构造器里的<b>使用</b>（不只是初始化式）无法被补丁重放：补丁只在
	 * 热更线程上单线程跑，而构造器当时是在应用线程上跑的，per-thread 副本根本不是同一份。
	 * 因此它们默认被"后续加工检查"拒绝；{@code @HotswapReinit} 可以显式解锁，但必须告警。</p>
	 */
	private static final Set<String> THREAD_AFFINE_DESCS = Set.of(
	 "Ljava/lang/ThreadLocal;", "Ljava/lang/InheritableThreadLocal;");

	private static boolean isThreadAffineType(String desc) {
		return desc != null && THREAD_AFFINE_DESCS.contains(desc);
	}

	/**
	 * ThreadLocal 家族里依赖"执行线程"的方法：{@code get}/{@code initialValue}/{@code childValue}
	 * 读的是<b>当前线程</b>的副本（§4.2 bit 4 {@code READS_MUTABLE}），{@code set}/{@code remove}
	 * 变异堆状态（bit 5 {@code MUTATES_HEAP}）。
	 *
	 * <p>补丁永远在热更线程上执行，而实例是在应用线程上构造的 —— 两者不是同一条线程，
	 * 重算必然读到另一份（通常是空的）副本。实测：构造线程上 {@code snapshot="abc"}，
	 * 补丁线程算出 {@code ""} 并静默写入，属"宁可不补"要拦的那类。
	 * {@code withInitial} 只是分配一个新的 ThreadLocal，不读任何线程状态，保持放行。</p>
	 */
	private static final Set<String> THREAD_LOCAL_STATEFUL = Set.of(
	 "get", "set", "remove", "initialValue", "childValue");

	/** 可复用 builder 的类型：两者都是 final 类，不存在"宿主自身即接收者"的情况。 */
	private static final Set<String> BUILDER_OWNERS = Set.of(
	 "java/lang/StringBuilder", "java/lang/StringBuffer");

	/**
	 * builder 的变异方法。这些方法本身必须放行（{@code new StringBuilder().append(..)}
	 * 是 §4.2 明确要求豁免的 {@code ALLOC_PURE}），所以判定落在<b>接收者</b>上，
	 * 见 {@link #builderMutatorReason}。
	 */
	private static final Set<String> BUILDER_MUTATORS = Set.of(
	 "append", "insert", "delete", "deleteCharAt", "replace", "reverse",
	 "setLength", "setCharAt", "ensureCapacity", "trimToSize");

	/**
	 * P0 版最小效应判定。
	 *
	 * <p>判决顺序：</p>
	 * <ol>
	 *   <li><b>精确危险重载</b>：环境依赖的个别重载（无参 {@code String.toUpperCase()}、
	 *       默认字符集的 {@code getBytes()}/{@code new String(byte[])}、{@code String.format}、
	 *       {@code String.intern()}）；</li>
	 *   <li><b>白名单</b>：基础集合无参构造、Logger 工厂与纯查询、{@code Objects.requireNonNull}、
	 *       Kotlin {@code Intrinsics}、不可变集合工厂 —— 优先级高于第 3 步的包级黑名单，
	 *       这正是"严防误杀基础集合构造与 Logger"的落点；</li>
	 *   <li><b>黑名单</b>：非确定性（时间/随机/identityHashCode/默认时区与字符集）、
	 *       线程局部堆状态（ThreadLocal 的 get/set/remove）、反射与动态调用、
	 *       进程与类加载、文件/网络 IO、日志输出；</li>
	 *   <li><b>接收者敏感的可复用 builder 判定</b>：{@code StringBuilder}/{@code StringBuffer}
	 *       的变异方法，接收者不是本切片内 {@code NEW} 出来的对象时拒绝
	 *       （见 {@link #builderMutatorReason}）；</li>
	 *   <li>未命中者 P0 一律放行 —— 这一步只做"明确危险"的负向拦截，
	 *       真正的白名单准入（{@code ALLOWED_MASK}）在 P2 落地。</li>
	 * </ol>
	 * @return null 表示放行；否则返回拒绝原因
	 */
	private static String effectReason(AbstractInsnNode n) {
		if (n instanceof MethodInsnNode m) {
			if (isImpureOverload(m)) {
				return "environment-dependent call " + m.owner + "." + m.name + m.desc;
			}
			if (isWhitelistedCall(m)) return null;
			return blacklistedCallReason(m);
		}
		if (n instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC) {
			if ("java/lang/System".equals(f.owner)
			    && ("out".equals(f.name) || "err".equals(f.name) || "in".equals(f.name))) {
				return "reads process-global stream java/lang/System." + f.name;
			}
			if ("java/io/File".equals(f.owner) && f.name.startsWith("separator")) {
				return "reads platform-dependent java/io/File." + f.name;
			}
		}
		return null;
	}

	/** §4.2 bit 3 的典型例子：同名重载里依赖默认 Locale / 默认 Charset 的那几个。 */
	private static boolean isImpureOverload(MethodInsnNode m) {
		if (!"java/lang/String".equals(m.owner)) return false;
		String name = m.name, desc = m.desc;
		if ("<init>".equals(name)) {
			return desc.equals("([B)V") || desc.equals("([BII)V");
		}
		if ("toUpperCase".equals(name) || "toLowerCase".equals(name)) {
			return desc.equals("()Ljava/lang/String;");
		}
		if ("getBytes".equals(name)) return desc.equals("()[B");
		if ("format".equals(name) || "formatted".equals(name)) return true;
		return "intern".equals(name);
	}

	/** 误杀白名单：命中即放行，优先于包级黑名单。 */
	private static boolean isWhitelistedCall(MethodInsnNode m) {
		if ("<init>".equals(m.name) && "()V".equals(m.desc)
		    && PURE_NOARG_CTOR_OWNERS.contains(m.owner)) { return true; }

		if ("java/util/Objects".equals(m.owner) && m.name.startsWith("requireNonNull")) return true;
		if ("java/lang/Object".equals(m.owner) && "getClass".equals(m.name)) return true;
		if ("java/lang/System".equals(m.owner) && "arraycopy".equals(m.name)) return true;
		if (m.owner.startsWith("kotlin/jvm/internal/Intrinsics")) return true;
		if ("java/util/Collections".equals(m.owner)
		    && (m.name.startsWith("empty") || m.name.startsWith("singleton")
		        || m.name.startsWith("unmodifiable"))) { return true; }
		if ("java/util/Arrays".equals(m.owner)
		    && (m.name.startsWith("asList") || m.name.startsWith("copyOf"))) { return true; }

		// Logger：工厂与纯查询放行；落地方法（info/debug/log/...）不在此列，走黑名单。
		if (m.getOpcode() == Opcodes.INVOKESTATIC && LOGGER_FACTORY_OWNERS.contains(m.owner)
		    && ("getLogger".equals(m.name) || "getLog".equals(m.name))) { return true; }
		if (LOGGER_TYPES.contains(m.owner)
		    && (m.name.startsWith("is") || m.name.startsWith("get"))) { return true; }
		return false;
	}

	/** 黑名单：非确定性 / 线程局部状态 / 环境依赖 / 反射 / 进程 / IO / 日志输出。 */
	private static String blacklistedCallReason(MethodInsnNode m) {
		String owner = m.owner, name = m.name;

		if (THREAD_LOCAL_OWNERS.contains(owner) && THREAD_LOCAL_STATEFUL.contains(name)) {
			return "thread-local heap state " + owner + "." + name
			       + " (值取决于执行线程，补丁在热更线程上重算会读脏)";
		}

		if ("java/lang/System".equals(owner)) {
			switch (name) {
				case "currentTimeMillis", "nanoTime", "identityHashCode", "lineSeparator",
				     "getProperty", "getProperties", "getenv", "console", "gc", "exit",
				     "getSecurityManager", "setProperty", "setProperties":
					return "non-deterministic/environment-dependent call java/lang/System." + name;
				default:
					break;
			}
			return null;
		}
		if ("java/lang/Math".equals(owner) && "random".equals(name)) {
			return "non-deterministic random source java/lang/Math.random";
		}
		if ("java/util/Random".equals(owner) || "java/util/SplittableRandom".equals(owner)
		    || "java/util/concurrent/ThreadLocalRandom".equals(owner)
		    || "java/security/SecureRandom".equals(owner) || owner.startsWith("java/security/")) {
			return "non-deterministic random source " + owner;
		}
		if ("java/util/UUID".equals(owner) && "randomUUID".equals(name)) {
			return "non-deterministic java/util/UUID.randomUUID";
		}
		if ("java/lang/Thread".equals(owner)) {
			return "thread/environment state " + owner + "." + name;
		}
		if ("java/lang/Object".equals(owner) && ("hashCode".equals(name) || "toString".equals(name))) {
			return "identity-dependent dispatch java/lang/Object." + name;
		}
		if ("java/util/Date".equals(owner) || "java/util/Calendar".equals(owner)
		    || "java/time/Clock".equals(owner)) {
			return "reads wall clock " + owner + "." + name;
		}
		if (isWallClockNow(owner, name)) {
			return "reads wall clock " + owner + "." + name;
		}
		if ("java/util/Locale".equals(owner) && "getDefault".equals(name)) {
			return "reads default locale java/util/Locale.getDefault";
		}
		if ("java/util/TimeZone".equals(owner) && "getDefault".equals(name)) {
			return "reads default time zone java/util/TimeZone.getDefault";
		}
		if ("java/nio/charset/Charset".equals(owner) && "defaultCharset".equals(name)) {
			return "reads default charset java/nio/charset/Charset.defaultCharset";
		}
		if (("java/lang/Integer".equals(owner) || "java/lang/Long".equals(owner)
		     || "java/lang/Boolean".equals(owner))
		    && ("getInteger".equals(name) || "getLong".equals(name) || "getBoolean".equals(name))) {
			return "reads system property " + owner + "." + name;
		}

		if (owner.startsWith("java/lang/reflect/") || owner.startsWith("java/lang/invoke/")) {
			return "reflective/dynamic call " + owner + "." + name;
		}
		if ("java/lang/Class".equals(owner)
		    && ("forName".equals(name) || "newInstance".equals(name)
		        || name.startsWith("getResource") || "getClassLoader".equals(name)
		        || "getProtectionDomain".equals(name) || "desiredAssertionStatus".equals(name))) {
			return "reflective/environment-dependent call java/lang/Class." + name;
		}
		if ("java/lang/Runtime".equals(owner) || "java/lang/ProcessBuilder".equals(owner)
		    || "java/lang/Process".equals(owner) || "java/lang/ClassLoader".equals(owner)) {
			return "process/loader call " + owner + "." + name;
		}
		if (owner.startsWith("java/io/") || owner.startsWith("java/net/")
		    || owner.startsWith("java/nio/file/") || owner.startsWith("java/nio/channels/")) {
			return "I/O call " + owner + "." + name;
		}
		if (owner.startsWith("java/util/logging/") || owner.startsWith("org/slf4j/")
		    || owner.startsWith("org/apache/logging/")
		    || owner.startsWith("org/apache/commons/logging/")) {
			return "logging output (IO_SYS) " + owner + "." + name;
		}
		return null;
	}

	/** {@code LocalDate.now()} / {@code Instant.now()} 之类的挂钟读取。 */
	private static boolean isWallClockNow(String owner, String name) {
		if (!"now".equals(name)) return false;
		return owner.startsWith("java/time/") || owner.startsWith("java/time/chrono/");
	}

	/**
	 * §4.2「接收者敏感的堆读取判定」+「局部逃逸豁免严格边界」的最小落地。
	 *
	 * <p>两种形态在指令层面只差<b>接收者从哪来</b>：</p>
	 * <ul>
	 *   <li>{@code this.key = new StringBuilder().append(a).toString()}：接收者是切片内
	 *       {@code NEW} 出来的对象，从未逃逸 → {@code ALLOC_PURE}，<b>放行</b>；</li>
	 *   <li>{@code this.key = BUF.append(name).toString()}（{@code BUF} 是可复用缓存字段）：
	 *       接收者来自字段/参数 → 读到的是别人留下的内容，且会把外部堆状态改脏
	 *       （§4.2 bit 4 + bit 5）。更隐蔽的是 {@code setLength(0)} 这类重置语句通常写在
	 *       <b>切片之外</b>，提取时看不见，于是补丁重放会得到 {@code "abcabc"} 这种累积值。
	 *       实测确认过这条路径，<b>拒绝</b>。</li>
	 * </ul>
	 *
	 * <p>完整的逃逸证明（对象是否被赋值给外部字段、是否传给了非纯调用）属于 P2 的代数模型；
	 * 这里只排除"接收者不是切片内新建对象"这一档已实测会读脏的形态。</p>
	 * @return null 表示放行；否则返回拒绝原因
	 */
	private static String builderMutatorReason(
	 MethodInsnNode m, InsnList insns, Frame<SourceValue>[] frames) {

		if (m.getOpcode() == Opcodes.INVOKESTATIC) return null;
		if (!BUILDER_OWNERS.contains(m.owner) || !BUILDER_MUTATORS.contains(m.name)) return null;
		if (frames == null) return null;

		int idx = insns.indexOf(m);
		if (idx < 0 || idx >= frames.length) return null;
		Frame<SourceValue> fr = frames[idx];
		if (fr == null) return null;

		int argCount = Type.getArgumentTypes(m.desc).length;
		int recvIdx  = fr.getStackSize() - 1 - argCount;
		if (recvIdx < 0) return null;

		SourceValue recv = fr.getStack(recvIdx);
		if (recv == null) return null;

		StringBuilder origin = new StringBuilder();
		for (AbstractInsnNode src : recv.insns) {
			if (src instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW) {
				if (t.desc.equals(m.owner)) return null;   // 局部逃逸豁免
				appendOrigin(origin, "new " + t.desc);
			} else if (src instanceof FieldInsnNode f) {
				appendOrigin(origin, f.owner + "." + f.name);
			} else if (src instanceof VarInsnNode v) {
				appendOrigin(origin, v.var == 0 ? "this" : "slot" + v.var);
			}
		}
		return "mutates a reusable " + m.owner + " obtained outside the slice"
		       + " (接收者来源: " + (origin.length() == 0 ? "未知" : origin) + ")";
	}

	private static void appendOrigin(StringBuilder sb, String what) {
		if (sb.length() > 0) sb.append(", ");
		sb.append(what);
	}

	// ==================== 构造器参数 -> 字段扫描 ====================

	/**
	 * 扫描 {@code <init>} 里 {@code ALOAD 0; XLOAD n; PUTFIELD this.f} 形态的
	 * "参数持久化到字段"模式，建立 {@code slot -> field} 回溯映射。
	 *
	 * <p><b>§4.3 源字段不可变证明</b>：映射成立后，切片段里的 {@code ALOAD n} 会被改写为
	 * {@code ALOAD 0; GETFIELD this.f}。补丁在 redefine 之后才执行，读到的是字段
	 * <b>当前</b>值而非构造时的值，所以只有当 {@code f} 在构造完成后不可能再变时改写才等价：</p>
	 * <ol>
	 *   <li><b>条件 A</b>：{@code f} 带 {@code ACC_FINAL}（JVM 只允许在构造器内写入）；</li>
	 *   <li><b>条件 B</b>：{@code f} 非 final，但为 {@code private}，且{@link NestView 全 Nest 范围内}
	 *       除本次 pattern 外不存在第二处 {@code PUTFIELD}（排除 setter 二次修改、其它构造器写入、
	 *       内部类跨实例写入）。证明通过后该字段的读取在效应审查中按 {@code READS_FINAL} 等价处理。</li>
	 * </ol>
	 * <p>二者都不满足时拒绝映射 —— 宁可不补，也不能拿被改过的值去算。</p>
	 * @param host 宿主类（仅用于取 ClassLoader 读 Nest 成员资源，<b>不做</b> {@code Class.forName}）
	 * @param nest 宿主 Nest 视图
	 * @return 可用映射 + 被拒槽位的真实原因（原因会被透传进 {@link PatchReport}）
	 */
	private static ParamScan scanParamFields(
	 Class<?> host, ClassNode hostClass, MethodNode init, NestView nest) {

		Map<Integer, ParamField> map      = new HashMap<>();
		Map<Integer, String>     rejected = new LinkedHashMap<>();
		InsnList                 insns    = init.instructions;
		List<AbstractInsnNode>   real     = filterReal(insns);

		for (int i = 0; i + 2 < real.size(); i++) {
			AbstractInsnNode a = real.get(i);
			AbstractInsnNode b = real.get(i + 1);
			AbstractInsnNode c = real.get(i + 2);

			if (!(a instanceof VarInsnNode va)
			    || va.getOpcode() != Opcodes.ALOAD || va.var != 0) { continue; }
			if (!(b instanceof VarInsnNode vb)
			    || !isLoadOfParam(vb)) { continue; }
			if (!(c instanceof FieldInsnNode fc)
			    || fc.getOpcode() != Opcodes.PUTFIELD) { continue; }
			if (!fc.owner.equals(hostClass.name)) continue;

			int slot = vb.var;
			if (map.containsKey(slot)) continue;

			int    startOrig  = insns.indexOf(a);
			int    putOrig    = insns.indexOf(c);
			String ctrlReason = checkControlDependency(insns, startOrig, putOrig);
			if (ctrlReason != null) {
				rejectParamSlot(rejected, slot, "pattern has control dependency: " + ctrlReason);
				log("Skipping constructor param slot " + slot + " in "
				    + hostClass.name + ".<init>: pattern has control dependency: "
				    + ctrlReason);
				continue;
			}

			// 参数类型必须与字段描述符严格一致，否则替换成 GETFIELD 后
			// 后续指令期望的类型会不匹配，触发 VerifyError
			if (!paramTypeMatches(init.desc, slot, fc.desc)) {
				rejectParamSlot(rejected, slot, "parameter type != field type " + fc.desc);
				log("Skipping constructor param slot " + slot + " in "
				    + hostClass.name + ".<init>: parameter type != field type "
				    + fc.desc);
				continue;
			}

			// §4.3：源字段必须可证明不可变
			FieldNode source = null;
			for (FieldNode fn : hostClass.fields) {
				if (fn.name.equals(fc.name) && fn.desc.equals(fc.desc)) {
					source = fn;
					break;
				}
			}
			if (source == null) {
				rejectParamSlot(rejected, slot, "source field declaration not found");
				continue;
			}
			String immutableReason = sourceFieldNotImmutableReason(hostClass, source, fc, nest);
			if (immutableReason != null) {
				rejectParamSlot(rejected, slot, "source field '" + source.name
				                                + "' is not provably immutable: " + immutableReason);
				log("Skipping constructor param slot " + slot + " in "
				    + hostClass.name + ".<init>: source field '" + source.name
				    + "' is not provably immutable: " + immutableReason);
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
				rejectParamSlot(rejected, slot, unsafeReason);
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
				rejectParamSlot(rejected, slot, unsafeReason);
				log("Skipping constructor param slot " + slot + " in "
				    + hostClass.name + ".<init>: " + unsafeReason);
				continue;
			}

			map.put(slot, new ParamField(source, slot));
		}
		return new ParamScan(map, rejected);
	}

	/** 记录被拒槽位的原因（首次为准，保留最贴近根因的那条）。 */
	private static void rejectParamSlot(Map<Integer, String> rejected, int slot, String reason) {
		if (rejected != null) rejected.putIfAbsent(slot, reason);
	}

	/** 记录字段被拒的真实原因（首次为准）。 */
	private static void recordReason(Map<String, String> outReasons, String field, String reason) {
		if (outReasons != null) outReasons.putIfAbsent(field, reason);
	}

	/**
	 * §4.3 源字段不可变证明。
	 * @param pattern 本次触发映射的 {@code PUTFIELD}（Nest 扫描时按对象身份排除）
	 * @return null 表示可证明构造后不再变化；否则返回拒绝原因
	 */
	private static String sourceFieldNotImmutableReason(
	 ClassNode hostClass, FieldNode field, FieldInsnNode pattern, NestView nest) {

		// 条件 A：final（JVM 只允许在声明类构造器内写入）
		if ((field.access & Opcodes.ACC_FINAL) != 0) return null;

		// 条件 B：private + 全 Nest 单写
		if ((field.access & Opcodes.ACC_PRIVATE) == 0) {
			return "not final and not private (条件 A/B 均不满足)";
		}
		if (!nest.complete()) {
			return "cannot read every nest member to prove a single write";
		}
		String where = nest.firstOtherPut(hostClass.name, field.name, field.desc,
		 Opcodes.PUTFIELD, pattern);
		if (where != null) {
			return "private but written again at " + where;
		}
		return null;
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
			String indyDesc      = indyDescFor(pa, hostInternal);

			Object[] bsmArgs = new Object[]{
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
			case Opcodes.GETFIELD -> "(L" + hostInternal + ";)" + pa.desc();
			case Opcodes.PUTFIELD -> "(L" + hostInternal + ";" + pa.desc() + ")V";
			case Opcodes.GETSTATIC -> "()" + pa.desc();
			case Opcodes.PUTSTATIC -> "(" + pa.desc() + ")V";
			case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE -> "(L" + hostInternal + ";" + pa.desc().substring(1);
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
	 * 把补丁片段里对目标字段的 {@code PUTFIELD}/{@code PUTSTATIC} 改写为
	 * {@code invokedynamic}，由 {@link HotswapBridge} 在链接期算好 Unsafe offset 后写入：
	 * {@link HotswapBridge#KIND_CONDITIONAL} 条件 CAS（仅当字段仍是类型默认值），
	 * {@link HotswapBridge#KIND_FORCE} 无条件写（{@code @HotswapReinit(mode = OVERWRITE)}）。
	 * <p>final 与非 final 走同一条路径，语义统一。</p>
	 * @param conditionalFields 走条件 CAS 的字段
	 * @param forceFields       走强制写的字段（两者不相交；force 优先）
	 */
	private static List<AbstractInsnNode> rewriteFieldPuts(
	 String className, List<AbstractInsnNode> insns,
	 Set<String> conditionalFields, Set<String> forceFields) {
		if (insns.isEmpty() || (conditionalFields.isEmpty() && forceFields.isEmpty())) return insns;

		List<AbstractInsnNode> rewritten = new ArrayList<>(insns.size());
		for (AbstractInsnNode insn : insns) {
			if (!(insn instanceof FieldInsnNode f)
			    || !f.owner.equals(className)
			    || (f.getOpcode() != Opcodes.PUTFIELD && f.getOpcode() != Opcodes.PUTSTATIC)) {
				rewritten.add(insn);
				continue;
			}
			boolean forced = forceFields.contains(f.name);
			if (!forced && !conditionalFields.contains(f.name)) {
				rewritten.add(insn);
				continue;
			}

			boolean isStatic = f.getOpcode() == Opcodes.PUTSTATIC;
			String  indyDesc = (isStatic ? "(" : "(L" + className + ";") + f.desc + ")V";
			Type    hostType = Type.getObjectType(className);

			Object[] bsmArgs = new Object[]{
			 forced ? HotswapBridge.KIND_FORCE : HotswapBridge.KIND_CONDITIONAL,
			 f.getOpcode(),
			 hostType,
			 hostType
			};

			rewritten.add(new InvokeDynamicInsnNode(f.name, indyDesc, BRIDGE_BSM, bsmArgs));
			log("Rewriting field write via indy(" + (forced ? "force" : "conditional") + "): "
			    + className + "." + f.name + " " + f.desc);
		}
		return rewritten;
	}

	// ==================== 应用与生命周期 ====================

	public static void afterRedefine(Class<?> clazz) {
		if (!HotSwapAgent.HOTSWAP_PLUS || clazz == null) return;

		PendingPatch patch = PENDING.remove(clazz);
		if (patch == null) return;

		try {
			applyPatch(clazz, patch);
		} catch (Throwable e) {
			// applyPatch 半途夭折（例如 LinkageError 熔断）：我们不知道哪些字段已经写成功，
			// 只能保守地把整个计划记入台账 —— 下一轮条件 CAS 会跳过已写入的值，
			// OVERWRITE 字段重写同一个值也是幂等的。
			for (FieldPatchTask task : patch.plan().allTasks()) {
				ledgerPut(clazz, task.fieldName(), "patch aborted: " + e);
			}
			HotSwapAgent.error("Field init patch failed: " + e.getMessage(), e);
		}
	}

	public static void afterRedefineFailed(Class<?> clazz) {
		if (clazz == null) return;
		PendingPatch dropped = PENDING.remove(clazz);
		if (dropped != null) {
			// §3.4 的核心场景：redefine 失败，但补丁计划已经生成、类结构随时可能推进；
			// 这些字段一个都没补，必须记账，否则它们再也不会出现在 Diff 里。
			for (FieldPatchTask task : dropped.plan().allTasks()) {
				ledgerPut(clazz, task.fieldName(), "redefine failed; patch never applied");
			}
			log("Dropped pending field init patch for " + clazz.getName()
			    + " after redefine failure; " + dropped.plan().allTasks().size()
			    + " field(s) recorded in FieldLedger");
		}
	}

	/**
	 * 宿主侧补丁驱动器（{@code docs/INIT_FIX.md} §5.2）。
	 *
	 * <p>逐字段调度：每个字段一个独立的静态直线方法，失败只影响该字段与其下游，
	 * 不会像"单方法承载全部字段"那样由一行异常跳过其后所有字段。依赖字段失败的
	 * 字段会被显式跳过并记账（{@code FieldLedger} 的雏形，见 P1-③）。</p>
	 *
	 * <p>{@code LinkageError} 仍然上抛熔断（§1.2 不变量 4）：它意味着类元数据假设已被
	 * JVM 打破，继续修补没有意义。</p>
	 */
	private static void applyPatch(Class<?> host, PendingPatch patch) throws Throwable {
		// 详细失败日志配额是"本轮"的（见 DETAILED_FAILURE_LOGS 的 Javadoc）：
		// 不重置的话它是进程级累计，前几轮热更失败满 5 次之后，
		// 之后所有热更都不再打详细栈。
		DETAILED_FAILURE_LOGS.set(0);

		if (!isInitialized(host)) {
			log("Skip field init patch, class not initialized (or initializing/failed): "
			    + host.getName());
			return;
		}

		PatchPlan    plan  = patch.plan();
		List<Object> alive = null;
		if (plan.hasInstance()) {
			alive = collectInstancesForPatch(patch);
			if (alive.isEmpty() && !plan.hasStatic()) {
				log("No live instances and no static patch needed for " + host.getName()
				    + ", skip");
				return;
			}
		}

		Lookup   h  = Reflect.defineHiddenClass(host, patch.bytes());
		Class<?> pc = h.lookupClass();

		// 必须在取 Handle 时就 asType 适配：init$F 的形参是宿主类型，驱动手里只有 Object。
		BoundInstanceTask[] instanceTasks = new BoundInstanceTask[plan.instanceTasks().size()];
		boolean hasInstanceDependencies = false;
		for (int i = 0; i < instanceTasks.length; i++) {
			FieldPatchTask task = plan.instanceTasks().get(i);
			MethodHandle handle = h.findStatic(pc, task.methodName(), MethodType.methodType(void.class, host))
				.asType(MethodType.methodType(void.class, Object.class));
			instanceTasks[i] = new BoundInstanceTask(task.fieldName(), task.dependencies(), handle);
			hasInstanceDependencies |= !task.dependencies().isEmpty();
		}

		Map<String, MethodHandle> staticHandles = new LinkedHashMap<>();
		for (FieldPatchTask task : plan.staticTasks()) {
			staticHandles.put(task.fieldName(),
			 h.findStatic(pc, task.methodName(), MethodType.methodType(void.class)));
		}

		Set<String>         failedFields   = new LinkedHashSet<>();
		Set<String>         skippedFields  = new LinkedHashSet<>();
		Map<String, String> failureReasons = new LinkedHashMap<>();

		// ---- 静态字段：整个补丁只跑一次 ----
		if (!plan.staticTasks().isEmpty()) {
			HotSwapAgent.info("Applying static field init patch to " + host.getName()
			                  + ", fields=" + plan.staticTasks().size());
			long start = System.nanoTime();
			runTasks(host, plan.staticTasks(), staticHandles, null,
			 failedFields, skippedFields, failureReasons);
			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
			HotSwapAgent.info("Static field init patch for " + host.getName()
			                  + " done in " + elapsedMs + "ms");
		}

		// ---- 实例字段：逐实例、逐字段 ----
		if (plan.hasInstance() && alive != null && !alive.isEmpty()) {
			HotSwapAgent.info("Applying instance field init patch to " + host.getName()
			                  + ", count=" + alive.size()
			                  + ", fields=" + plan.instanceTasks().size());

			long start = System.nanoTime();
			if (hasInstanceDependencies) {
				runDependentInstanceTasks(host, alive, instanceTasks,
				 failedFields, skippedFields, failureReasons);
			} else {
				runIndependentInstanceTasks(host, alive, instanceTasks, failedFields, failureReasons);
			}
			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

			StringBuilder sb = new StringBuilder("Instance field init patch for ")
			 .append(host.getName())
			 .append(" done: fields=").append(plan.instanceTasks().size())
			 .append(", failedFields=").append(failedFields)
			 .append(", skippedFields=").append(skippedFields)
			 .append(", elapsed=").append(elapsedMs).append("ms");
			HotSwapAgent.info(sb.toString());

			if (elapsedMs > 1000) {
				HotSwapAgent.warn("Instance field init patch for " + host.getName()
				                  + " took " + elapsedMs
				                  + "ms, hotswap thread was blocked");
			}
		}

		if (!failedFields.isEmpty() || !skippedFields.isEmpty()) {
			// §3.4 待补台账：本轮没补上的字段记入，下一轮候选集自动并回来。
			for (String f : failedFields) {
				ledgerPut(host, f, "runtime failure: "
				                   + failureReasons.getOrDefault(f, "patch execution failed"));
			}
			for (String f : skippedFields) {
				ledgerPut(host, f, "skipped: "
				                   + failureReasons.getOrDefault(f, "a dependency field failed to patch"));
			}
			HotSwapAgent.warn("Field init patch incomplete for " + host.getName()
			                  + ": failed=" + failedFields + ", skipped=" + skippedFields
			                  + "（已记入 FieldLedger，下一轮热更会重新纳入候选）");
		}
	}

	private record BoundInstanceTask(String fieldName, Set<String> dependencies, MethodHandle handle) {}

	private static void runIndependentInstanceTasks(
	 Class<?> host, List<Object> targets, BoundInstanceTask[] tasks,
	 Set<String> failedFields, Map<String, String> failureReasons) {
		for (Object target : targets) {
			for (BoundInstanceTask task : tasks) {
				try {
					task.handle().invokeExact(target);
				} catch (LinkageError le) {
					throw le;
				} catch (Throwable t) {
					recordTaskFailure(host, task.fieldName(), t, failedFields, failureReasons);
				}
			}
		}
	}

	private static void runDependentInstanceTasks(
	 Class<?> host, List<Object> targets, BoundInstanceTask[] tasks,
	 Set<String> failedFields, Set<String> skippedFields, Map<String, String> failureReasons) {
		for (Object target : targets) {
			for (BoundInstanceTask task : tasks) {
				Set<String> dependencies = task.dependencies();
				if (!dependencies.isEmpty()
				    && (!Collections.disjoint(dependencies, failedFields)
				        || !Collections.disjoint(dependencies, skippedFields))) {
					if (skippedFields.add(task.fieldName())) {
						failureReasons.putIfAbsent(task.fieldName(), "dependency failed: "
						                                             + intersection(dependencies, failedFields, skippedFields));
						HotSwapAgent.warn("Skipping field init for " + host.getName() + "."
						                  + task.fieldName() + " because a dependency failed");
					}
					continue;
				}
				try {
					task.handle().invokeExact(target);
				} catch (LinkageError le) {
					throw le;
				} catch (Throwable t) {
					recordTaskFailure(host, task.fieldName(), t, failedFields, failureReasons);
				}
			}
		}
	}

	private static void recordTaskFailure(
	 Class<?> host, String fieldName, Throwable failure,
	 Set<String> failedFields, Map<String, String> failureReasons) {
		failedFields.add(fieldName);
		failureReasons.putIfAbsent(fieldName, String.valueOf(failure));
		if (DETAILED_FAILURE_LOGS.incrementAndGet() <= MAX_DETAILED_FAILURES) {
			HotSwapAgent.error("Field init patch failed for " + host.getName()
			                   + "." + fieldName + ": " + failure.getMessage(), failure);
		}
	}

	/**
	 * 按拓扑序执行一组字段任务。
	 * @param target 实例字段时为对象，静态字段时忽略
	 */
	private static void runTasks(
	 Class<?> host, List<FieldPatchTask> tasks, Map<String, MethodHandle> handles,
	 Object target, Set<String> failedFields, Set<String> skippedFields,
	 Map<String, String> failureReasons) {

		for (FieldPatchTask task : tasks) {
			if (!task.dependencies().isEmpty()
			    && (!Collections.disjoint(task.dependencies(), failedFields)
			        || !Collections.disjoint(task.dependencies(), skippedFields))) {
				if (skippedFields.add(task.fieldName())) {
					failureReasons.putIfAbsent(task.fieldName(), "dependency failed: "
					                                             + intersection(task.dependencies(), failedFields, skippedFields));
					HotSwapAgent.warn("Skipping field init for " + host.getName() + "."
					                  + task.fieldName() + " because a dependency failed");
				}
				continue;
			}
			MethodHandle mh = handles.get(task.fieldName());
			if (mh == null) {
				skippedFields.add(task.fieldName());
				failureReasons.putIfAbsent(task.fieldName(), "no MethodHandle for method");
				continue;
			}
			try {
				if (target == null) { mh.invokeExact(); } else mh.invokeExact(target);
			} catch (LinkageError le) {
				throw le;   // §1.2 熔断：类元数据假设已被打破，继续修补没有意义
			} catch (Throwable t) {
				recordTaskFailure(host, task.fieldName(), t, failedFields, failureReasons);
			}
		}
	}

	private static Set<String> intersection(Set<String> deps, Set<String> a, Set<String> b) {
		Set<String> out = new LinkedHashSet<>();
		for (String d : deps) {
			if (a.contains(d) || b.contains(d)) out.add(d);
		}
		return out;
	}

	private static Set<String> union(Set<String> a, Set<String> b, Set<String> c) {
		Set<String> out = new LinkedHashSet<>();
		if (a != null) out.addAll(a);
		out.addAll(b);
		out.addAll(c);
		return out;
	}
	// (union 由 §3.4 的 FieldLedger 使用；P1-③ 落地前保留为工具方法)

	private static List<Object> collectInstancesForPatch(PendingPatch patch) {
		List<WeakReference<Object>> snapshot = patch.instanceSnapshot();
		List<Object>                alive    = new ArrayList<>(snapshot.size());
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
	 Map<Integer, ParamField> paramFields, Map<Integer, String> rejectedParamSlots,
	 boolean fromRootCtor, Set<String> outSelfAssigned,
	 Map<String, String> outReasons) {

		if (method == null || targetFields.isEmpty()) return Map.of();

		Frame<SourceValue>[] frames;
		try {
			frames = analyze(className, method);
		} catch (AnalyzerException e) {
			HotSwapAgent.warn("Analysis failed for " + method.name + method.desc
			                  + ": " + e.getMessage());
			if (outReasons != null) {
				for (String f : targetFields) {
					outReasons.putIfAbsent(f, "bytecode analysis failed in "
					                          + method.name + method.desc + ": " + e.getMessage());
				}
			}
			return Map.of();
		}

		InsnList                                            insns             = method.instructions;
		Set<LabelNode>                                      jumpTargets       = collectJumpTargets(method);
		Map<String, Set<AbstractInsnNode>>                  perFieldCollected = new LinkedHashMap<>();
		Map<String, Map<AbstractInsnNode, ProtectedAccess>> perFieldProtected = new HashMap<>();
		Set<String>                                         refusedFields     = new HashSet<>();

		for (int i = 0; i < insns.size(); i++) {
			AbstractInsnNode insn = insns.get(i);
			if (!(insn instanceof FieldInsnNode f)) continue;

			int op = f.getOpcode();
			if (isStatic ? op != Opcodes.PUTSTATIC : op != Opcodes.PUTFIELD) continue;
			if (!f.owner.equals(className) || !targetFields.contains(f.name)) continue;

			if (refusedFields.contains(f.name)) continue;

			Frame<SourceValue> frame = frames[i];
			if (frame == null) {
				recordReason(outReasons, f.name,
				 "no stack frame at the write site in " + method.name + method.desc);
				continue;
			}

			int stackSize = frame.getStackSize();
			if (isStatic ? stackSize < 1 : stackSize < 2) {
				recordReason(outReasons, f.name,
				 "unexpected stack shape at the write site in " + method.name + method.desc);
				continue;
			}

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
					 frames, receiver, collected, minIdx, i, isStatic, privateMethods,
					 paramFields, rejectedParamSlots, localProtected);
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
				recordReason(outReasons, f.name,
				 "no safe initialization expression in " + method.name + method.desc
				 + ": " + unsafeReason);
				continue;
			}

			if (perFieldCollected.containsKey(f.name)) {
				String reason = "multiple safe writes to the same field in "
				                + className + "." + method.name + "()";
				log("Field '" + f.name + "' has multiple safe PUTFIELD in "
				    + className + "." + method.name + "(): refusing");
				perFieldCollected.remove(f.name);
				perFieldProtected.remove(f.name);
				refusedFields.add(f.name);
				recordReason(outReasons, f.name, reason);
				continue;
			}
			perFieldCollected.put(f.name, collected);
			perFieldProtected.put(f.name, localProtected);
		}

		Map<String, FieldExtract> perField = new LinkedHashMap<>();
		for (Map.Entry<String, Set<AbstractInsnNode>> e : perFieldCollected.entrySet()) {
			String                fieldName  = e.getKey();
			Set<AbstractInsnNode> fieldInsns = e.getValue();
			Map<AbstractInsnNode, ProtectedAccess> fieldProtected =
			 perFieldProtected.getOrDefault(fieldName, Map.of());

			Map<LabelNode, LabelNode>              labelMap        = new HashMap<>();
			List<AbstractInsnNode>                 cloned          = new ArrayList<>();
			Map<AbstractInsnNode, ProtectedAccess> clonedProtected = new HashMap<>();
			boolean                                dependsOnParam  = false;
			boolean                                selfAssign      = false;

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
				if (outSelfAssigned != null) outSelfAssigned.add(fieldName);
				log("Field '" + fieldName + "' is self-assigned from its own constructor "
				    + "parameter in " + className + "." + method.name
				    + "(); patch would be a no-op. Refusing.");
				recordReason(outReasons, fieldName,
				 "self-assignment from its own constructor parameter");
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
	                                Frame<SourceValue>[] frames,
	                                SourceValue receiver,
	                                Set<AbstractInsnNode> collected,
	                                int minIdx, int putIdx, boolean isStatic,
	                                Set<String> privateMethods,
	                                Map<Integer, ParamField> paramFields,
	                                Map<Integer, String> rejectedParamSlots,
	                                Map<AbstractInsnNode, ProtectedAccess> outProtectedAccesses) {
		if (!isStatic) {
			if (receiver == null || receiver.insns.size() != 1) return "unexpected receiver";
			AbstractInsnNode recv = receiver.insns.iterator().next();
			if (!(recv instanceof VarInsnNode v
			      && v.getOpcode() == Opcodes.ALOAD && v.var == 0)) {
				return "unexpected receiver";
			}
		}

		ClassHierarchyOracle oracle = new HierarchyTreeOracle(host.getClassLoader());
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

		// 按<b>字节码顺序</b>遍历 collected（而不是 HashSet 的迭代顺序）：
		// 同一个切片里可能同时命中多条门（例如"依赖构造器参数"与"IO 调用"），
		// HashSet 的迭代顺序由 identity hash 决定、每次 JVM 运行都可能不同，
		// 会让 PatchReport 里的 reason 飘忽。按序取第一条，报告才稳定且指向最早的问题。
		for (int k = minIdx; k <= putIdx; k++) {
			AbstractInsnNode n = insns.get(k);
			if (!collected.contains(n)) continue;
			if (n instanceof VarInsnNode v) {
				if (isStatic) return "depends on local variables";
				if (v.getOpcode() == Opcodes.ALOAD && v.var == 0) {
					// this，放行
				} else if (isLoadOfParam(v) && paramFields.containsKey(v.var)) {
					// 构造器参数，克隆阶段会替换为 ALOAD 0; GETFIELD
				} else if (isLoadOfParam(v) && rejectedParamSlots != null
				           && rejectedParamSlots.containsKey(v.var)) {
					// 该槽位本来是"参数->字段"模式的候选，但被 §4.3 不可变证明或
					// 单赋值检查拒掉了：把真实原因透传出去，而不是笼统的"局部变量"。
					return "constructor parameter slot " + v.var
					       + " cannot be back-tracked: " + rejectedParamSlots.get(v.var);
				} else {
					return "depends on local variables";
				}
			}
			if (n.getOpcode() == Opcodes.IINC) {
				return "depends on local variables";
			}

			// §4.2 的最小效应防御（P0 版）：明确非确定性 / 环境依赖 / IO 的调用直接拒绝。
			String effect = effectReason(n);
			if (effect != null) return effect;

			// §4.2 局部逃逸豁免的接收者敏感判定：可复用 builder 的接收者必须来自本切片。
			if (n instanceof MethodInsnNode mm) {
				String builderReason = builderMutatorReason(mm, insns, frames);
				if (builderReason != null) return builderReason;
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
				Boolean prot = isProtectedCrossPackageAccess(oracle, host, m.owner, m.name, m.desc, false);
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
				Boolean prot = isProtectedCrossPackageAccess(oracle, host,f.owner, f.name, f.desc, true);
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
				Boolean prot = isProtectedCrossPackageAccess(oracle, host, m.owner, m.name, m.desc, false);
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
			AbstractInsnNode n       = insns.get(i);
			int              op      = n.getOpcode();
			boolean          inRange = i >= minIdx && i <= putIdx;
			boolean          before  = i < minIdx;
			boolean          after   = i > putIdx;

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
	 ClassHierarchyOracle oracle, Class<?> host, String ownerInternal,
	 String name, String desc, boolean isField) {

		if (ownerInternal.isEmpty() || ownerInternal.charAt(0) == '[') return Boolean.FALSE;

		Optional<ClassHierarchyOracle.MemberRef> member =
			oracle.resolveMember(ownerInternal, name, desc, isField);
		if (member.isEmpty()) return null;

		String hostInternal = host.getName().replace('.', '/');
		return Modifier.isProtected(member.get().access)
		       && !packageName(member.get().declaringClass).equals(packageName(hostInternal));
	}

	private static String packageName(String internalName) {
		int separator = internalName.lastIndexOf('/');
		return separator < 0 ? "" : internalName.substring(0, separator);
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