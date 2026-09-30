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
 * Hotswap时初始化修复器
 * <p>新增字段的初始化表达式如果依赖构造器参数、局部变量、含分支/内联，存量实例不会被初始化。</p>
 * <p>补丁逻辑不再注入被redefine的字节码：改为生成一个hidden nestmate class，
 * 在{@link #afterRedefined}里定义并调用，避免污染diff、避免依赖"允许增方法"的redefine。</p>
 * <p>不再摘除final修饰符：补丁类不是宿主的&lt;init&gt;/&lt;clinit&gt;，
 * 直接PUTFIELD/PUTSTATIC写final字段会抛IllegalAccessError，改为经{@link FinalFieldWriter}
 * 用Unsafe的volatile写入。</p>
 * <p>补丁在热更线程执行，属于不安全发布：final字段走{@link FinalFieldWriter}的volatile写，
 * 非final字段的裸PUTFIELD/PUTSTATIC前各自插入{@link java.lang.invoke.VarHandle#releaseFence()}，
 * 保证构造写入先于发布。读端没有强制 acquire，只是尽力而为的可见性保证。</p>
 * <p>针对Java 8字节码：同一类内私有方法调用生成的是{@code INVOKESPECIAL}，
 * 提取时放行并把补丁里的这类指令原地改写为{@code INVOKEVIRTUAL}（接口私有方法则为{@code INVOKEINTERFACE}）；
 * {@code invokedynamic} 的 {@code bsmArgs} 里指向宿主私有方法的 {@code H_INVOKESPECIAL} handle 同样改写。</p>
 * <p>跨包 protected 成员访问：hidden class 不是宿主的子类，直接调用会因 JVMS §5.4.4 的
 * receiver check 抛 {@code IllegalAccessError}。改写为 {@code invokedynamic}，由
 * {@link ProtectedBridge} 以宿主特权 Lookup 解析。BSM 的 {@code bsmArgs} 显式携带
 * 宿主类（不能依赖 {@code getNestHost()}，嵌套类会倒错到最外层），字段类型从
 * {@code indyType} 派生（基本类型不能进 {@code bsmArgs}）。
 * <b>indy 调用点类型的接收者必须是 host，不是 owner</b>：受保护成员经
 * {@code findVirtual/findGetter/findSetter} 解析时，返回的 MethodHandle 的接收者类型
 * 会被 JVM 收窄为 lookup class（即 host），indyDesc 若用 owner 作为接收者类型，
 * 会因 {@code ConstantCallSite} 类型不匹配抛 {@code WrongMethodTypeException}。</p>
 *
 * <h2>构造器参数回溯</h2>
 * <p>Java 编写的初始化表达式（如 {@code x = s.get(0).getType()}，其中 {@code s} 是
 * 构造器参数）在字节码里是直接加载指令读取（{@code ALOAD/ILOAD/LLOAD/FLOAD/DLOAD n}），
 * 不是 {@code GETFIELD}。若参数在 {@code <init>} 中通过
 * {@code ALOAD 0; XLOAD n; PUTFIELD this.f} 存入字段，就把提取片段里的加载指令替换为
 * {@code ALOAD 0; GETFIELD this.f}。</p>
 *
 * <h3>参数回溯的安全条件</h3>
 * <p>替换正确的前提是"提取片段执行的时点，字段 {@code f} 的值等于参数槽位 {@code n}
 * 的值"。这要求槽位在整个 {@code <init>} 中满足单赋值语义，且字段没有被二次覆写。
 * 因此 {@link #scanParamFields} 对每个候选映射做以下检查，任一命中即弃用：</p>
 * <ul>
 *   <li><b>槽位被 store 覆盖</b>（pattern 之前或之后）：按参数宽度计算区间，
 *       {@code long}/{@code double} 占 2 槽位。</li>
 *   <li><b>槽位被 {@code IINC} 自增</b>：{@code IincInsnNode} 不继承
 *       {@code VarInsnNode}，必须单独判定。</li>
 *   <li><b>字段被二次 {@code PUTFIELD}</b>：pattern 之后同一字段被再次写入。</li>
 * </ul>
 *
 * <h3>伪指令处理</h3>
 * <p>{@code LineNumberNode}/{@code LabelNode}/{@code FrameNode} 的 opcode 为 -1，
 * 按指令下标紧邻匹配会错位。扫描时先过滤掉伪指令，在"真实指令"序列上做三元组匹配。</p>
 *
 * <h3>控制依赖</h3>
 * <p>{@link #checkControlDependency} 要求 put 所在基本块是方法入口的无条件后继：
 * put 之前的跳转不得跳过 put；put 之后的跳转不得回到 put 之前（循环体）；
 * put 之前的 {@code RETURN} 拒绝（提前退出）；put 之前的 {@code ATHROW} 放行
 * （抛出意味着对象未构造完成，不会有实例）。</p>
 * <p>这样既堵住了 {@code if (flag) this.tag = "X";} 这类守卫赋值被无条件重放的漏洞，
 * 又不会误杀构造器体内与初始化表达式无关的 {@code if}/循环/assert。</p>
 *
 * <h3>后续加工</h3>
 * <p>字段被赋值后可能还有加工语句，例如
 * {@code private final List&lt;String&gt; names = new ArrayList&lt;&gt;(); { names.add("x"); }}
 * 或 {@code Foo(int a) { this(); names.add("x"); }}。补丁只重放赋值语句本身，
 * 不会重放加工，存量实例会丢数据。</p>
 * <p>{@link #subsequentProcessingReason} 的判据：字段在所有相关方法
 * （实例字段：所有 {@code <init>}；静态字段：{@code <clinit>} + 所有 {@code <init>}）
 * 里的每次 PUT 必须属于某个"已放行字段的提取指令集"；引用类型字段的每次 GET
 * 也必须属于某个已放行字段的提取指令集。属于则说明这次访问是初始化表达式的一部分，
 * 会随补丁一起重放；不属于则说明是额外的加工，拒绝该字段。</p>
 * <p><b>静态字段的扫描包含 {@code <init>}</b>：静态容器常在实例构造器里被注册
 * （{@code REGISTRY.add(this.name);}），这类加工也会让补丁重放时丢数据。</p>
 * <p>基本类型的 GET 不拒绝：{@code GETFIELD int} 只是把值加载到栈上参与别的计算，
 * 不可能原地修改字段。不可变类型（{@code String}、包装类、{@code Class}、枚举）的
 * GET 也不拒绝：其引用上的任何方法调用都不会改变对象状态。</p>
 *
 * <h3>多构造器等价性</h3>
 * <p>字段可能出现在多个 {@code <init>} 里。三种典型情况：</p>
 * <ul>
 *   <li><b>内联初始化器</b>：{@code private long t = System.currentTimeMillis();}
 *       被 javac 拷贝到每个未 {@code this(...)} 委托的构造器中。</li>
 *   <li><b>构造器参数初始化</b>：{@code this.url = "http://" + port;} 出现在签名不同的
 *       多个构造器里。</li>
 *   <li><b>常量冲突</b>：{@code User()} 里 {@code role = "GUEST"} 与
 *       {@code User(int)} 里 {@code role = "ADMIN"}。</li>
 * </ul>
 *
 * <h3>根构造器与覆盖完整性</h3>
 * <p><b>根构造器</b>指不通过 {@code this(...)} 委托给本类其他构造器的构造器。</p>
 * <p><b>识别 {@code this(...)} 的准确判据</b>：{@code INVOKESPECIAL <init>
 * owner==hostClass.name} 的接收者必须来自 {@code ALOAD 0}。仅判断 owner 会把
 * {@code new Host(...)}（递归数据结构常见，如 {@code TreeNode left = new TreeNode();}）
 * 误判为委托。{@link #isRootConstructor} 用 ASM {@code Analyzer} 精确定位接收者的
 * {@code SourceValue}，只有含 {@code ALOAD 0} 的才视为真正的 {@code this(...)}。</p>
 * <p>补丁对所有存量实例统一执行。字段在存量实例上的正确值取决于创建时用的构造器。
 * 因此字段必须满足以下覆盖完整性才能安全统一执行：</p>
 * <ol>
 *   <li><b>至少在一个根构造器里赋值</b>：委托构造器里的附加赋值只对走过该委托构造器的
 *       实例有效。</li>
 *   <li><b>若 rootCtorCount > 1，须在所有根构造器里赋值</b>。</li>
 *   <li><b>参数依赖 + 多根构造器：拒绝</b>。</li>
 * </ol>
 *
 * <h3>自赋值与新增字段依赖</h3>
 * <p>{@code Foo(int x) { this.x = x; }} 里若 {@code x} 是新增字段，参数回溯会把
 * {@code ILOAD 1} 改写为 {@code GETFIELD this.x}，补丁体成为 {@code this.x = this.x}
 * （无操作）。{@link #extractFieldInits} 检测到该模式后直接拒绝该字段。</p>
 * <p>若字段 A 的表达式读取了新增字段 B（实例或静态），而 B 的补丁被拒绝，
 * A 在补丁执行时读到的是 B 的默认值。{@link #buildPatch} 在单字段判定后进入
 * 依赖闭包迭代：反复移除所有依赖"未被放行新字段"的字段，直到不动点。</p>
 *
 * <h2>已知限制（无法在静态阶段安全处理）</h2>
 * <ul>
 *   <li><b>参数委托给父类构造器</b>：{@code class Sub(samples) : Base(samples)} 里
 *       {@code PUTFIELD samples} 发生在父类，子类字节码看不到。</li>
 *   <li><b>Kotlin 防御式写法与 inline 函数</b>：{@code ?.}、{@code ?:}、
 *       {@code sumOf} 等展开为跳转与局部临时变量，被 {@code checkSafe} 拒绝。</li>
 *   <li><b>运行期状态变迁</b>：所有 {@code GETFIELD} 读旧字段都读出的是当前值
 *       而不是构造时的值。补丁按当前字段值重新求值时可能抛
 *       {@code IndexOutOfBoundsException} 或读到脏数据。</li>
 *   <li><b>this 逃逸</b>：{@code names = new ArrayList<>(); init();} 中 {@code init}
 *       可能通过 {@code this} 访问 {@code names} 并加工，静态上看不出来。属保守误杀。</li>
 *   <li><b>跨实例字段覆写检测</b>：{@code scanParamFields} 对同名字段的二次
 *       {@code PUTFIELD} 一律视为覆写，不区分 receiver。</li>
 * </ul>
 *
 * <p>实例快照：由框架在{@link #transform(Class, byte[], ClassDiff)}里做一次，
 * 并可在 {@code redefineClasses} 前通过 {@link #beforeRedefine(Class)} 覆盖一次。
 * 快照窗口只是被缩小、没有消失。</p>
 * <p>暂存键直接用 {@code Class<?>}：Class 身份已包含加载器，弱引用持有，
 * 类卸载即自动清。</p>
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

	private static final Handle PROTECTED_BSM = new Handle(
	 Opcodes.H_INVOKESTATIC,
	 Type.getInternalName(ProtectedBridge.class),
	 "bootstrap",
	 "(Ljava/lang/invoke/MethodHandles$Lookup;"
	 + "Ljava/lang/String;"
	 + "Ljava/lang/invoke/MethodType;"
	 + "I"
	 + "Ljava/lang/Class;"
	 + "Ljava/lang/Class;"
	 + ")Ljava/lang/invoke/CallSite;",
	 false);

	private record BuiltPatch(byte[] bytes, boolean hasStatic, boolean hasInstance) { }

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

	private record ProtectedAccess(int opcode, String owner, String name, String desc, boolean itf) { }

	/**
	 * 单个字段在一个方法（{@code <init>} 或 {@code <clinit>}）里提取出的初始化信息。
	 *
	 * @param instructions      克隆后的指令序列（已替换参数回溯为 GETFIELD）
	 * @param protectedAccesses 克隆后指令 -> 需要 bridge 化的 protected 访问
	 * @param dependsOnParam    提取过程是否发生了参数回溯
	 * @param fromRootCtor      该提取是否来自根构造器
	 * @param originalInsns     原始方法里构成该提取的指令集合
	 */
	private record FieldExtract(
	 List<AbstractInsnNode> instructions,
	 Map<AbstractInsnNode, ProtectedAccess> protectedAccesses,
	 boolean dependsOnParam,
	 boolean fromRootCtor,
	 Set<AbstractInsnNode> originalInsns) { }

	private record ParamField(FieldNode field, int slot) { }

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
			return;
		}

		String className = diff.newClass.name;
		try {
			BuiltPatch built = buildPatch(host, newBytes, className,
			                              addedStaticFields, addedInstanceFields);
			if (built == null) return;

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

		Map<String, String> unsafeFields = new HashMap<>();
		Map<String, FieldNode> fieldNodes = new HashMap<>();
		for (FieldNode fn : newClass.fields) {
			fieldNodes.put(fn.name, fn);
			if ((fn.access & Opcodes.ACC_FINAL) != 0) {
				unsafeFields.put(fn.name, fn.desc);
			}
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

		// isRootConstructor 的判定需要 Analyzer，缓存避免重复分析
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

		// 静态字段的后续加工检查要同时扫 <clinit> 和所有 <init>
		List<MethodNode> staticScanMethods = new ArrayList<>(initMethods);
		if (clinitMethod != null) staticScanMethods.add(clinitMethod);

		// ==================== 阶段 1：单字段判定（实例） ====================
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
			} else {
				acceptedInstance.add(fieldName);
			}
		}

		// ==================== 静态字段：接受 clinit 的提取结果 ====================
		Set<String> acceptedStatic = new LinkedHashSet<>(staticExtracts.keySet());

		// ==================== 阶段 1.5 + 阶段 2：闭包迭代 ====================
		boolean changed = true;
		while (changed) {
			changed = false;

			// 1. 实例字段后续加工检查（扫所有 <init>）
			for (String f : new ArrayList<>(acceptedInstance)) {
				String reason = subsequentProcessingReason(
				 f, acceptedInstance, instanceExtracts, initMethods,
				 className, false, fieldNodes.get(f));
				if (reason != null) {
					log("Field '" + f + "' refused (subsequent processing): " + reason);
					acceptedInstance.remove(f);
					changed = true;
				}
			}

			// 2. 静态字段后续加工检查（扫所有 <init> + <clinit>）
			for (String f : new ArrayList<>(acceptedStatic)) {
				String reason = subsequentProcessingReason(
				 f, acceptedStatic, staticExtracts, staticScanMethods,
				 className, true, fieldNodes.get(f));
				if (reason != null) {
					log("Static field '" + f + "' refused (subsequent processing): " + reason);
					acceptedStatic.remove(f);
					changed = true;
				}
			}

			// 3. 实例字段依赖闭包
			for (String f : new ArrayList<>(acceptedInstance)) {
				String reason = instanceDepReason(
				 f, acceptedInstance, acceptedStatic,
				 instanceExtracts, addedInstanceFields, addedStaticFields, className);
				if (reason != null) {
					log("Field '" + f + "' refused (dependency): " + reason);
					acceptedInstance.remove(f);
					changed = true;
				}
			}

			// 4. 静态字段依赖闭包
			for (String f : new ArrayList<>(acceptedStatic)) {
				String reason = staticDepReason(
				 f, acceptedStatic, staticExtracts, addedStaticFields, className);
				if (reason != null) {
					log("Static field '" + f + "' refused (dependency): " + reason);
					acceptedStatic.remove(f);
					changed = true;
				}
			}
		}

		// ==================== 阶段 3：写出选中的提取 ====================
		List<AbstractInsnNode> initInsns = new ArrayList<>();
		Map<AbstractInsnNode, ProtectedAccess> initProtected = new HashMap<>();
		for (String fieldName : acceptedInstance) {
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
		for (String fieldName : acceptedStatic) {
			FieldExtract fe = staticExtracts.get(fieldName).get(0);
			clinitInsns.addAll(fe.instructions());
			clinitProtected.putAll(fe.protectedAccesses());
		}
		Set<String> remainingStaticFields = new HashSet<>(addedStaticFields);
		remainingStaticFields.removeAll(acceptedStatic);
		remainingStaticFields.removeAll(staticExtracts.keySet());
		for (FieldNode field : newClass.fields) {
			if ((field.access & Opcodes.ACC_STATIC) != 0
			    && remainingStaticFields.contains(field.name)
			    && field.value != null) {
				clinitInsns.add(new LdcInsnNode(field.value));
				clinitInsns.add(new FieldInsnNode(
				 Opcodes.PUTSTATIC, className, field.name, field.desc));
				log("Extracted constant field init from ConstantValue: "
				    + className + "." + field.name);
			}
		}

		String hostInternal = Type.getInternalName(host);
		initInsns   = rewriteProtectedAccesses(hostInternal, initInsns, initProtected);
		clinitInsns = rewriteProtectedAccesses(hostInternal, clinitInsns, clinitProtected);

		initInsns   = rewritePrivateInvokes(className, initInsns);
		clinitInsns = rewritePrivateInvokes(className, clinitInsns);

		initInsns   = rewriteFinalPuts(className, initInsns, unsafeFields);
		clinitInsns = rewriteFinalPuts(className, clinitInsns, unsafeFields);

		initInsns   = addReleaseFences(initInsns);
		clinitInsns = addReleaseFences(clinitInsns);

		if (initInsns.isEmpty() && clinitInsns.isEmpty()) return null;

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
		return new BuiltPatch(cw.toByteArray(), hasStatic, hasInstance);
	}

	// ==================== 根构造器判定 ====================

	/**
	 * 判定构造器是否为根构造器：不通过 {@code this(...)} 委托给本类其他构造器。
	 *
	 * <p><b>准确判据</b>：{@code INVOKESPECIAL <init> owner==hostClass.name} 的接收者
	 * 必须来自 {@code ALOAD 0}。仅按 owner 判断会把 {@code new Host(...)}
	 * （递归数据结构常见，如 {@code TreeNode left = new TreeNode();}）误判为委托。</p>
	 *
	 * <p>实现方式：用 {@link Analyzer} 跑一遍，在每条 {@code INVOKESPECIAL <init>}
	 * 前取出接收者的 {@code SourceValue.insns}。接收者的栈位是
	 * {@code stackSize - 1 - argCount}。{@code SourceValue.insns} 里含
	 * {@code ALOAD 0} 即视为真正的 {@code this(...)}。</p>
	 *
	 * <p>分析失败时保守判定为根构造器（root 会触发更多拒绝检查，误判为 root
	 * 只是保守方向的过度拒绝，不会漏掉危险场景）。</p>
	 *
	 * @param cache 同一 {@link MethodNode} 的分析结果缓存，避免重复跑 Analyzer
	 */
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
			Analyzer<SourceValue> analyzer = new Analyzer<>(new AliasInterpreter()) {
				@Override
				protected boolean newControlFlowExceptionEdge(int insnIndex, TryCatchBlockNode tcb) {
					return false;
				}
			};
			frames = analyzer.analyze(hostClass.name, init);
		} catch (AnalyzerException e) {
			// 分析失败时保守判定为 root：root 会触发更多检查，属保守方向的误杀
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

			// 接收者来自 ALOAD 0 才是真正的 this(...) 委托
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

	/**
	 * 字段在所有相关方法里的访问是否都归属于"已放行字段的提取指令集"。
	 *
	 * <p><b>判据</b>：</p>
	 * <ul>
	 *   <li>字段的每次 PUT（{@code PUTFIELD}/{@code PUTSTATIC}）必须属于某个已放行
	 *       字段的 extract。不属于则说明有额外写入。</li>
	 *   <li>引用类型字段的每次 GET 必须属于某个已放行字段的 extract。不属于则说明
	 *       有额外读取，可能跟随就地修改（{@code add}/{@code clear}）。</li>
	 *   <li>基本类型字段的 GET 不拒绝：{@code GETFIELD int} 只是把值加载到栈上参与别的
	 *       计算，不可能原地修改字段。</li>
	 *   <li>不可变类型（{@code String}、包装类、{@code Class}、枚举）的 GET 不拒绝：
	 *       其引用上的任何方法调用都不会改变对象状态。</li>
	 * </ul>
	 *
	 * <p><b>静态字段的扫描包含 {@code <init>}</b>：静态容器常在实例构造器里被注册
	 * （{@code REGISTRY.add(this.name);}），这类加工也会让补丁重放时丢数据。</p>
	 *
	 * @param fieldName      待检查字段
	 * @param acceptedFields 当前已放行字段集合（含 {@code fieldName} 自身）
	 * @param allExtracts    字段名 -> List&lt;FieldExtract&gt;（跨方法汇总）
	 * @param methods        要扫描的方法列表
	 * @param className      宿主类内部名
	 * @param isStatic       true 检查 {@code PUTSTATIC/GETSTATIC}；false 检查 {@code PUTFIELD/GETFIELD}
	 * @param fieldNode      字段声明（用于取描述符判定是否基本类型/不可变类型）
	 * @return 拒绝原因，null 表示通过
	 */
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

	private static String instanceDepReason(
	 String f,
	 Set<String> acceptedInstance,
	 Set<String> acceptedStatic,
	 Map<String, List<FieldExtract>> instanceExtracts,
	 Set<String> allNewInstanceFields,
	 Set<String> allNewStaticFields,
	 String className) {

		List<FieldExtract> feList = instanceExtracts.get(f);
		if (feList == null) return null;

		for (FieldExtract fe : feList) {
			for (AbstractInsnNode n : fe.instructions()) {
				if (!(n instanceof FieldInsnNode fld)) continue;
				if (!fld.owner.equals(className)) continue;

				if (fld.getOpcode() == Opcodes.GETFIELD
				    && allNewInstanceFields.contains(fld.name)
				    && !fld.name.equals(f)
				    && !acceptedInstance.contains(fld.name)) {
					return "reads new instance field '" + fld.name
					     + "' which is not patched";
				}
				if (fld.getOpcode() == Opcodes.GETSTATIC
				    && allNewStaticFields.contains(fld.name)
				    && !acceptedStatic.contains(fld.name)) {
					return "reads new static field '" + fld.name
					     + "' which is not patched";
				}
			}
		}
		return null;
	}

	private static String staticDepReason(
	 String f,
	 Set<String> acceptedStatic,
	 Map<String, List<FieldExtract>> staticExtracts,
	 Set<String> allNewStaticFields,
	 String className) {

		List<FieldExtract> feList = staticExtracts.get(f);
		if (feList == null) return null;

		for (FieldExtract fe : feList) {
			for (AbstractInsnNode n : fe.instructions()) {
				if (!(n instanceof FieldInsnNode fld)) continue;
				if (!fld.owner.equals(className)) continue;
				if (fld.getOpcode() == Opcodes.GETSTATIC
				    && allNewStaticFields.contains(fld.name)
				    && !fld.name.equals(f)
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
		List<AbstractInsnNode> real = filterReal(init.instructions);

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
			 pa.opcode(),
			 Type.getObjectType(ownerInternal),
			 hostType
			};

			rewritten.add(new InvokeDynamicInsnNode(pa.name(), indyDesc, PROTECTED_BSM, bsmArgs));
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
		}
		return rewritten;
	}

	private static List<AbstractInsnNode> addReleaseFences(List<AbstractInsnNode> insns) {
		if (insns.isEmpty()) return insns;
		List<AbstractInsnNode> result = new ArrayList<>(insns.size() + 8);
		for (AbstractInsnNode insn : insns) {
			if (insn instanceof FieldInsnNode f
			    && (f.getOpcode() == Opcodes.PUTFIELD || f.getOpcode() == Opcodes.PUTSTATIC)) {
				result.add(new MethodInsnNode(
				 Opcodes.INVOKESTATIC, "java/lang/invoke/VarHandle",
				 "releaseFence", "()V", false));
			}
			result.add(insn);
		}
		return result;
	}

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
			default -> "Object";
		};
	}

	// ==================== 应用与生命周期 ====================

	public static void afterRedefined(Class<?> clazz) {
		if (!HotSwapAgent.HOTSWAP_PLUS || clazz == null) return;

		FinalFieldWriter.clearCache(clazz);

		PendingPatch patch = PENDING.remove(clazz);
		if (patch == null) return;

		try {
			applyPatch(clazz, patch);
		} catch (Throwable e) {
			HotSwapAgent.error("Field init patch failed: " + e.getMessage(), e);
		} finally {
			FinalFieldWriter.clearCache(clazz);
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
			Analyzer<SourceValue> analyzer = new Analyzer<>(new AliasInterpreter()) {
				@Override
				protected boolean newControlFlowExceptionEdge(int insnIndex, TryCatchBlockNode tcb) {
					return false;
				}
			};
			frames = analyzer.analyze(className, method);
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
					unsafeReason = checkSafe(host, className, insns, jumpTargets, receiver,
					 collected, minIdx, i, isStatic, privateMethods, paramFields,
					 localProtected);
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

	private static String checkSafe(Class<?> host, String className, InsnList insns,
	                                Set<LabelNode> jumpTargets, SourceValue receiver,
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
				if (isProtectedCrossPackageAccess(host, m.owner, m.name, m.desc, false)) {
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

			if (n instanceof FieldInsnNode f
			    && (f.getOpcode() == Opcodes.GETFIELD || f.getOpcode() == Opcodes.PUTFIELD
			        || f.getOpcode() == Opcodes.GETSTATIC || f.getOpcode() == Opcodes.PUTSTATIC)) {
				if (isProtectedCrossPackageAccess(host, f.owner, f.name, f.desc, true)) {
					outProtectedAccesses.put(n,
					 new ProtectedAccess(f.getOpcode(), f.owner, f.name, f.desc, false));
				}
			}
			if (n instanceof MethodInsnNode m && !m.owner.startsWith("[")) {
				if (isProtectedCrossPackageAccess(host, m.owner, m.name, m.desc, false)) {
					outProtectedAccesses.put(n,
					 new ProtectedAccess(m.getOpcode(), m.owner, m.name, m.desc, m.itf));
				}
			}
		}
		return null;
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
				// put 之前的 ATHROW 不拒：抛出时对象未构造完成，不会有实例
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

	private static boolean isProtectedCrossPackageAccess(
	 Class<?> host, String ownerInternal,
	 String name, String desc, boolean isField) {

		if (ownerInternal.isEmpty() || ownerInternal.charAt(0) == '[') return false;

		Class<?> owner;
		try {
			owner = Class.forName(ownerInternal.replace('/', '.'),
			                      false, host.getClassLoader());
		} catch (Throwable t) {
			return false;
		}

		try {
			if (isField) {
				for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
					try {
						Field f = c.getDeclaredField(name);
						return Modifier.isProtected(f.getModifiers()) && !sameRuntimePackage(c, host);
					} catch (NoSuchFieldException ignored) {
						// 继续向上
					}
				}
				return false;
			}

			if ("<init>".equals(name)) {
				for (Constructor<?> ctor : owner.getDeclaredConstructors()) {
					if (Type.getConstructorDescriptor(ctor).equals(desc)) {
						return Modifier.isProtected(ctor.getModifiers())
						       && !sameRuntimePackage(owner, host);
					}
				}
				return false;
			}

			for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
				for (Method m : declaredMethodsOf(c)) {
					if (m.getName().equals(name)
					    && Type.getMethodDescriptor(m).equals(desc)) {
						return Modifier.isProtected(m.getModifiers())
						       && !sameRuntimePackage(c, host);
					}
				}
			}
			return false;
		} catch (Throwable t) {
			return false;
		}
	}

	private static final ClassValue<Method[]> DECLARED_METHODS =
	 new ClassValue<>() {
		 @Override
		 protected Method[] computeValue(Class<?> type) {
			 return type.getDeclaredMethods();
		 }
	 };

	private static Method[] declaredMethodsOf(Class<?> c) {
		return DECLARED_METHODS.get(c);
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