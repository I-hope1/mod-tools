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
 * 保证构造写入先于发布。</p>
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
 * <p>实例快照：由框架在{@link #transform(Class, byte[], ClassDiff)}里做一次，
 * 并可在 {@code redefineClasses} 前通过 {@link #beforeRedefine(Class)} 覆盖一次，
 * 尽量把 redefine 之后新建的实例排除在外。快照窗口只是被缩小、没有消失：
 * 从 {@link #beforeRedefine(Class)} 返回到 redefine 完成之间，用旧 &lt;init&gt;
 * 创建的实例既不在快照里、又不会被补丁命中，其新字段会停在默认值。</p>
 * <p>暂存键直接用 {@code Class<?>}：Class 身份已包含加载器，弱引用持有，
 * 类卸载即自动清；不做 loader 弱引用、不需要哨兵。</p>
 */
public class InitFix {
	private static final String STATIC_PATCH_METHOD   = "initStatic";
	private static final String INSTANCE_PATCH_METHOD = "initInstance";
	private static final String PATCH_SUFFIX          = "$$HotswapPatch";

	/** 暂存补丁的存活时间。超过该时长仍未被消费，则视为 transform 与 redefine 脱节，下次 transform 时清除。 */
	private static final long PENDING_TTL_NANOS = TimeUnit.MINUTES.toNanos(5);

	/**
	 * 逐实例补丁时，普通异常最多打多少条完整栈；超出后只累加计数。
	 * {@link LinkageError} 另行处理（遇到第一条即中断整个循环）。
	 */
	private static final int MAX_DETAILED_FAILURES = 5;

	/**
	 * 用于判断宿主是否已经完成类初始化。{@link Unsafe#shouldBeInitialized(Class)} 不会触发初始化。
	 */
	private static final Unsafe UNSAFE = Unsafe.getUnsafe();

	/**
	 * redefine 前暂存的补丁类字节，afterRedefined 取出后即删。
	 * <p>键是宿主 {@code Class<?>}：Class 身份天然携带加载器，弱引用持有，
	 * 类卸载即由 {@link WeakHashMap} 自动清理，不需要 loader 哨兵或 loader 弱引用。</p>
	 * <p>同步由 {@link Collections#synchronizedMap} 提供，迭代需在 {@code synchronized} 块内。</p>
	 */
	private static final Map<Class<?>, PendingPatch> PENDING =
	 Collections.synchronizedMap(new WeakHashMap<>());

	/**
	 * {@link ProtectedBridge#bootstrap} 的方法句柄。
	 * <p>签名：{@code (Lookup, String, MethodType, int opcode, Class owner, Class host) -> CallSite}。</p>
	 * <p><b>不传 fieldType</b>：字段类型从 {@code indyType} 派生，避免 ASM 把基本类型
	 * 序列化成 {@code CONSTANT_Class_info "I"} 之类导致运行时 {@code NoClassDefFoundError}。</p>
	 * <p><b>显式传 host</b>：不能用 {@code getNestHost()}，嵌套类的 nest host 是最外层类，
	 * 而非真正持有继承权的类。</p>
	 */
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

	/**
	 * buildPatch 的中间产物：不含实例快照，因为快照是 {@link #transform} 在拿到
	 * host 之后才做的。最终由 transform 组装成 {@link PendingPatch}。
	 */
	private record BuiltPatch(byte[] bytes, boolean hasStatic, boolean hasInstance) { }

	/**
	 * @param instanceSnapshot redefine 之前的存量实例快照，弱引用避免长时滞留。
	 *                         {@code hasInstance()==true} 时必非 null，构造器有断言。
	 */
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

		/**
		 * 返回一份带实例快照的新 PendingPatch；其余字段不变，时间戳刷新为当前时刻。
		 * <p>刷新时间戳是为了让 TTL 从最近一次快照开始计算，避免 transform 与 redefine
		 * 之间隔得太久时，条目被下一次 transform 的 TTL 扫描误清。</p>
		 */
		PendingPatch withSnapshot(List<WeakReference<Object>> snapshot) {
			return new PendingPatch(
			 bytes, hasStatic, hasInstance,
			 List.copyOf(snapshot),
			 System.nanoTime());
		}
	}

	/**
	 * 需要 bridge 化的成员访问。
	 *
	 * @param opcode ASM 操作码（GETFIELD/PUTFIELD/GETSTATIC/PUTSTATIC/INVOKEVIRTUAL/INVOKEINTERFACE/INVOKESTATIC）
	 * @param owner  成员声明类（或接收者的静态类型）；仅作为 {@code findXxx} 的 refc 用
	 * @param name   成员名
	 * @param desc   字段类型描述符（字段访问）或方法描述符（方法访问）
	 * @param itf    目标是否为接口方法；仅方法访问有效
	 */
	private record ProtectedAccess(int opcode, String owner, String name, String desc, boolean itf) { }

	/** {@link #extractFieldInits} 的返回：克隆后的指令 + 其中需要 bridge 化的访问。 */
	private record ExtractResult(List<AbstractInsnNode> instructions,
	                             Map<AbstractInsnNode, ProtectedAccess> protectedAccesses) { }

	/**
	 * 在 redefine 之前被调用，生成补丁类字节并暂存。
	 * <p>显式接收宿主 {@code Class<?>}：多 loader 场景下 key 里的身份必须准确，
	 * 用 TCCL 或其他 loader 猜测同名类会取错类、取错实例、甚至触发递归加载。</p>
	 * <p>本方法同时做一次存量实例快照（此刻 redefine 还没发生）。框架如果想进一步
	 * 缩小快照与 redefine 之间的窗口，可以在 redefine 前再调一次
	 * {@link #beforeRedefine(Class)}。</p>
	 */
	public static void transform(Class<?> host, byte[] newBytes, ClassDiff diff) {
		if (!HotSwapAgent.HOTSWAP_PLUS) return;

		// 覆盖"redefine 被框架过滤、afterRedefined/afterRedefineFailed 都未触发"的残留
		cleanupStalePatches();

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
			return;
		}

		String className = diff.newClass.name;
		try {
			BuiltPatch built = buildPatch(host, newBytes, className,
			                              addedStaticFields, addedInstanceFields);
			if (built == null) return;

			// 此刻 redefine 还没发生，快照是准确的
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

	/**
	 * 由框架在{@code Instrumentation.redefineClasses()}之前调用，刷新实例快照。
	 * <p>作用是把快照窗口从"上次 {@link #transform} 时刻"收窄到"现在"，
	 * 减少 transform 到 redefine 之间新建实例被纳入补丁的概率。</p>
	 * <p>注意窗口只是缩小、没有消失：本方法返回到 redefine 完成之间，用旧 &lt;init&gt;
	 * 创建的实例既不在快照里、又不会被补丁命中，其新字段会停在默认值。</p>
	 * <p>本方法幂等；对同一次 redefine 多次调用以最后一次为准。</p>
	 */
	public static void beforeRedefine(Class<?> clazz) {
		if (!HotSwapAgent.HOTSWAP_PLUS || clazz == null) return;

		PendingPatch existing = PENDING.get(clazz);
		if (existing == null || !existing.hasInstance()) return;

		// 先在锁外做快照：snapshotInstances 内部会做 JVMTI 堆遍历，不能在 computeIfPresent 里跑
		List<WeakReference<Object>> snapshot = snapshotInstances(clazz);
		PENDING.computeIfPresent(clazz, (k, p) -> p.withSnapshot(snapshot));
	}

	/**
	 * 清理超时未消费的补丁条目。
	 * <p>正常流程下条目在 {@code afterRedefined} 或 {@code afterRedefineFailed} 里被
	 * 立即移除；只有这两条路径都没被触发（例如框架在 redefine 前过滤掉了该类），
	 * 才会成为残留。TTL 兜底保证长期运行时不会无限累积。类卸载由 {@link WeakHashMap} 自动清理。</p>
	 * <p>{@link WeakHashMap} 的 Entry 继承自 {@link WeakReference}：GC 清掉 referent 后、
	 * 内部尚未 expunge 的窗口内，{@code e.getKey()} 可能返回 null。因此先取 key 并做非空
	 * 防御，避免 {@code k.getName()} 触发 NPE。</p>
	 */
	private static void cleanupStalePatches() {
		if (PENDING.isEmpty()) return;
		long now = System.nanoTime();
		synchronized (PENDING) {
			PENDING.entrySet().removeIf(e -> {
				Class<?> k = e.getKey();
				if (k == null) return true; // key 已被 GC，直接清理该 Entry
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

		// 补丁类不是宿主的 <init>/<clinit>，对 final 字段的直接写入会失败，
		// 需改写成 Unsafe 调用。这里收集所有 final 字段的 name -> desc
		Map<String, String> unsafeFields = new HashMap<>();
		for (FieldNode fn : newClass.fields) {
			if ((fn.access & Opcodes.ACC_FINAL) != 0) {
				unsafeFields.put(fn.name, fn.desc);
			}
		}

		// 宿主自身全部私有方法的 name+desc（含接口的私有方法）。
		// 用于收紧 INVOKESPECIAL / H_INVOKESPECIAL 的放行条件。
		Set<String> privateMethods = new HashSet<>();
		for (MethodNode mn : newClass.methods) {
			if ((mn.access & Opcodes.ACC_PRIVATE) != 0) {
				privateMethods.add(mn.name + mn.desc);
			}
		}

		// 提取实例字段<init>指令
		List<AbstractInsnNode> initInsns = new ArrayList<>();
		Map<AbstractInsnNode, ProtectedAccess> initProtected = new HashMap<>();
		{
			Set<String> remainingFields = new HashSet<>(addedInstanceFields);

			List<MethodNode> initMethods = newClass.methods.stream()
			 .filter(m -> "<init>".equals(m.name))
			 .toList();

			for (MethodNode init : initMethods) {
				if (remainingFields.isEmpty()) break;
				log("Extracting field init for " + className + "." + init.name + "()");
				ExtractResult er = extractFieldInits(host, className, init, remainingFields,
				                                     false, privateMethods);
				initInsns.addAll(er.instructions());
				initProtected.putAll(er.protectedAccesses());
				for (AbstractInsnNode insn : er.instructions()) {
					if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD) {
						remainingFields.remove(f.name);
					}
				}
			}
		}

		// 提取静态字段<clinit>指令
		List<AbstractInsnNode> clinitInsns = new ArrayList<>();
		Map<AbstractInsnNode, ProtectedAccess> clinitProtected = new HashMap<>();
		{
			Set<String> remainingStaticFields = new HashSet<>(addedStaticFields);

			MethodNode clinit = newClass.methods.stream()
			 .filter(m -> "<clinit>".equals(m.name) && "()V".equals(m.desc))
			 .findFirst().orElse(null);
			if (clinit != null) {
				ExtractResult er = extractFieldInits(host, className, clinit, remainingStaticFields,
				                                     true, privateMethods);
				clinitInsns.addAll(er.instructions());
				clinitProtected.putAll(er.protectedAccesses());
				for (AbstractInsnNode insn : er.instructions()) {
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

		// 跨包 protected 访问改写为 indy，交由 ProtectedBridge 以宿主特权 Lookup 解析。
		// 必须在 rewritePrivateInvokes 之前：bridge 生成的 indy bsmArgs 不含 Handle，
		// rewriteIndyHandles 对它无操作，但顺序明确更清晰。
		String hostInternal = Type.getInternalName(host);
		initInsns   = rewriteProtectedAccesses(hostInternal, initInsns, initProtected);
		clinitInsns = rewriteProtectedAccesses(hostInternal, clinitInsns, clinitProtected);

		// Java 8 字节码里同类私有方法调用生成的是 INVOKESPECIAL，
		// nestmate 环境下必须改成 INVOKEVIRTUAL（类）/ INVOKEINTERFACE（接口）才能过验证。
		initInsns   = rewritePrivateInvokes(className, initInsns);
		clinitInsns = rewritePrivateInvokes(className, clinitInsns);

		// final 字段的写入改走 FinalFieldWriter（volatile 语义）
		initInsns   = rewriteFinalPuts(className, initInsns, unsafeFields);
		clinitInsns = rewriteFinalPuts(className, clinitInsns, unsafeFields);

		// 在每个非 final 字段写入之前插入 releaseFence，
		// 保证"先于写发生的构造/计算"对读到该字段的线程可见。
		// 放在方法末尾无效：末尾之后没有任何 store 需要被保护。
		initInsns   = addReleaseFences(initInsns);
		clinitInsns = addReleaseFences(clinitInsns);

		if (initInsns.isEmpty() && clinitInsns.isEmpty()) return null;

		boolean hasStatic   = !clinitInsns.isEmpty();
		boolean hasInstance = !initInsns.isEmpty();

		// 生成补丁类，名字与宿主同包，hidden class 定义时才能作为 nestmate 加入宿主 nest
		ClassNode patch = new ClassNode();
		patch.version = Math.max(hostVersion, Opcodes.V11); // nestmate 需 >= 55
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
			// Object -> 宿主类型：之后提取指令里的 ALOAD 0 即宿主实例
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

	// ==================== 指令改写 ====================

	/**
	 * 把需要 bridge 化的成员访问指令替换成 {@code invokedynamic}。
	 * <p>indy 的调用点类型包含 receiver（若为实例成员），BSM 解析时由
	 * {@code privateLookupIn(host, patchLookup)} 的 host 特权 Lookup 完成访问权限判定，
	 * 等价于"以宿主身份访问"，从而绕过 hidden class 不是宿主子类导致的 receiver check。</p>
	 * <p><b>indy 描述符里的接收者类型必须是 host，不是 owner</b>：受保护成员经
	 * {@code findVirtual/findGetter/findSetter} 解析时，按 Javadoc
	 * "the first argument (the receiver) will be restricted in type to the lookup class"，
	 * 返回的 MethodHandle 类型是 {@code (Host, args)ret}。若 indyDesc 用 owner 作接收者，
	 * 会与 {@code ConstantCallSite} 的目标类型不匹配，链接时抛
	 * {@code WrongMethodTypeException}。栈上的接收者是补丁方法里的 {@code this}
	 * （已 CHECKCAST 成 host），用 host 作接收者类型与 verifier 一致。</p>
	 * <p>栈形状与原指令一致：GETFIELD/PUTFIELD/GETSTATIC/PUTSTATIC/INVOKEVIRTUAL/
	 * INVOKEINTERFACE/INVOKESTATIC 的栈增量不变。</p>
	 * <p><b>{@code bsmArgs} 只含 {@code int opcode} + {@code Class owner} + {@code Class host}。</b>
	 * 不含 fieldType——基本类型在常量池里无法表示，ASM 会错误地编码为
	 * {@code CONSTANT_Class_info "I"}，JVM 链接 indy 时尝试加载名为 {@code I} 的类而抛
	 * {@code NoClassDefFoundError}。字段类型从 {@code indyType} 派生。</p>
	 */
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
			// 接收者类型用 host，不用 owner：受保护成员的 MethodHandle 接收者被
			// 收窄到 lookup class，indy 描述符必须与之一致
			String indyDesc = indyDescFor(pa, hostInternal);

			// bsmArgs: (int opcode, Class owner, Class host)
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

	/**
	 * 生成 bridge 后的 indy 调用点类型。
	 * <p><b>实例成员的接收者类型用 {@code hostInternal}</b>，不是 {@code pa.owner()}。
	 * 受保护成员的 MethodHandle 由 JVM 自动收窄接收者到 lookup class（即 host），
	 * indyDesc 与之对齐才能通过 {@code ConstantCallSite} 的类型检查。</p>
	 * <ul>
	 *   <li>GETFIELD：{@code (Lhost;)LfieldType;}</li>
	 *   <li>PUTFIELD：{@code (Lhost;LfieldType;)V}</li>
	 *   <li>GETSTATIC：{@code ()LfieldType;}</li>
	 *   <li>PUTSTATIC：{@code (LfieldType;)V}</li>
	 *   <li>INVOKEVIRTUAL/INVOKEINTERFACE：{@code (Lhost;args...)ret}</li>
	 *   <li>INVOKESTATIC：{@code (args...)ret}</li>
	 * </ul>
	 * <p>字段类型直接写进 indy 描述符：{@code MethodType} 原生支持基本类型，
	 * 不像 {@code bsmArgs} 会触发常量池的 Class 解析。</p>
	 */
	private static String indyDescFor(ProtectedAccess pa, String hostInternal) {
		return switch (pa.opcode()) {
			case Opcodes.GETFIELD  -> "(L" + hostInternal + ";)" + pa.desc();
			case Opcodes.PUTFIELD  -> "(L" + hostInternal + ";" + pa.desc() + ")V";
			case Opcodes.GETSTATIC -> "()" + pa.desc();
			case Opcodes.PUTSTATIC -> "(" + pa.desc() + ")V";
			case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE ->
			 // 原 desc 形如 "(args...)ret"，去掉开头的 '(' 并前置 receiver（host）
			 "(L" + hostInternal + ";" + pa.desc().substring(1);
			case Opcodes.INVOKESTATIC -> pa.desc();
			default -> throw new IllegalStateException(
			 "unexpected opcode for protected bridge: " + pa.opcode());
		};
	}

	private static boolean isFieldOp(int opcode) {
		return opcode == Opcodes.GETFIELD || opcode == Opcodes.PUTFIELD
		    || opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
	}

	/**
	 * 把补丁片段里"指向宿主私有方法"的特殊调用原地改写。
	 * <ul>
	 *   <li>{@code INVOKESPECIAL owner==className, name!=<init>}：
	 *       Java 8 编译目标下同类私有方法调用一律生成此形态；{@code INVOKESPECIAL}
	 *       只在调用方是当前类或其子类时合法，hidden class 两者都不是，必须改写。</li>
	 *   <li>{@code invokedynamic} 的 {@code bsmArgs} 里的 {@code H_INVOKESPECIAL} handle：
	 *       Java 8 里实例捕获型 lambda 的 bootstrap 参数会带一个指向 {@code lambda$new$N}
	 *       的 {@code H_INVOKESPECIAL} handle。{@code LambdaMetafactory} 会把它当
	 *       {@code findSpecial} 的 specialCaller 使用，hidden class 不是宿主，
	 *       specialCaller 检查会失败。改成 {@code H_INVOKEVIRTUAL}/{@code H_INVOKEINTERFACE} 后，
	 *       LMF 走普通虚拟调用即可。</li>
	 * </ul>
	 * <p>JVMS §4.10.1.9：{@code INVOKEVIRTUAL} 指向的常量池项必须是 {@code CONSTANT_Methodref}；
	 * 接口方法用 {@code CONSTANT_InterfaceMethodref}，必须配 {@code INVOKEINTERFACE}。
	 * 因此按 {@code itf} 分流，而不是一律用 {@code INVOKEVIRTUAL}。</p>
	 * <p>能走到这一步的特殊调用已在 {@code checkSafe} 里校验过
	 * {@code owner==className} 且目标方法带 {@code ACC_PRIVATE}。</p>
	 */
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
				log("Rewriting private invokespecial -> "
				    + (m.itf ? "invokeinterface" : "invokevirtual")
				    + ": " + className + "." + m.name + m.desc);
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

	/**
	 * 改写 {@code invokedynamic} 的 {@code bsmArgs} 里指向宿主私有方法的
	 * {@code H_INVOKESPECIAL} handle。返回 null 表示没有改动。
	 * <p>必须构造新的 {@code bsmArgs} 数组和新的 {@link InvokeDynamicInsnNode}，
	 * 不能就地改：ASM 的 {@code InvokeDynamicInsnNode.clone} 是浅拷贝，直接改
	 * {@code bsmArgs[i]} 会穿透到原始 {@code MethodNode}。</p>
	 * <p>对本方法 bridge 生成的 indy：{@code bsmArgs} 里只有 {@code Integer} 和 {@code Type}，
	 * 没有 {@code Handle}，循环不做任何事，原样返回 null。</p>
	 */
	private static InvokeDynamicInsnNode rewriteIndyHandles(
	 String className, InvokeDynamicInsnNode indy) {
		Object[] args = indy.bsmArgs;
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
				log("Rewriting H_INVOKESPECIAL in invokedynamic bsmArgs -> "
				    + (h.isInterface() ? "H_INVOKEINTERFACE" : "H_INVOKEVIRTUAL")
				    + ": " + className + "." + h.getName() + h.getDesc()
				    + " (indy " + indy.name + indy.desc + ")");
			}
		}
		if (copy == null) return null;
		return new InvokeDynamicInsnNode(indy.name, indy.desc, indy.bsm, copy);
	}

	/**
	 * 把补丁片段里对本类 final 字段的直接写入改写为 {@link FinalFieldWriter} 调用。
	 * <p>指令形态：实例字段原本是 {@code [obj, value] + PUTFIELD}，把 PUTFIELD 换成
	 * {@code ldc class; ldc name; invokestatic} 后，栈自底向上恰好是
	 * {@code (obj, value, class, name)}，与 {@code putXxx(Object, X, Class, String)} 的实参顺序一致；
	 * 静态字段原本是 {@code [value] + PUTSTATIC}，同理对应 {@code putStaticXxx(X, Class, String)}。</p>
	 * <p>栈平衡校验作用在提取阶段的原始 {@code Frame} 上，与本次改写无关，
	 * 改写前后净栈增量一致（实例 -2，静态 -1）。</p>
	 * <p>{@link FinalFieldWriter} 的写入本身是 volatile 写（强于 release），此处无需再额外加 fence。</p>
	 * <p>本类 final 字段若跨包 protected，会先经 bridge 化（见 {@link #rewriteProtectedAccesses}），
	 * 变成 indy 后不再走这里。</p>
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

	/**
	 * 在每条非 final 的 PUTFIELD/PUTSTATIC 之前插入 {@link java.lang.invoke.VarHandle#releaseFence()}。
	 * <p>fence 的语义是"fence 之前的访问先于 fence 之后的访问可见"。放在写之前，
	 * 保证对象的构造写入先于发布；放在方法末尾没有意义——末尾之后没有需要被保护的 store。</p>
	 * <p>{@code releaseFence} 无参无返回，插在栈中间不影响栈形状。</p>
	 * <p>调用时机：{@link #rewriteFinalPuts} 之后，此时剩下的 PUTFIELD/PUTSTATIC 都是非 final 的；
	 * final 字段已被替换为 {@code putXxxVolatile} 调用，不会再被误加 fence。
	 * 跨包 protected 的写已被 bridge 成 indy 调用，也不再触发此处的 fence。</p>
	 */
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
			default -> "Object"; // L...; 与 [
		};
	}

	// ==================== 应用与生命周期 ====================

	/**
	 * redefine 成功后由框架调用，取出补丁并应用。
	 * <p>前后各清一次 {@link FinalFieldWriter} 的偏移缓存：前清保证补丁用 redefine 后的新布局；
	 * 后清覆盖补丁期间重新填充的条目，避免跨到下一次 redefine。</p>
	 */
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

	/**
	 * Redefine 失败时由宿主框架调用，清理暂存的补丁字节，避免内存泄漏。
	 * <p>{@code Instrumentation.redefineClasses()} 抛异常时，{@link #afterRedefined}
	 * 不会被执行，{@code PENDING} 会一直持有该类对应的字节码数组。宿主框架
	 * 在 redefine 的外层 {@code catch}/{@code finally} 里调用本方法即可释放。</p>
	 * <p>即便框架未调用此方法，{@link #cleanupStalePatches()} 也会在下次 transform 时兜底。</p>
	 */
	public static void afterRedefineFailed(Class<?> clazz) {
		if (clazz == null) return;
		PendingPatch dropped = PENDING.remove(clazz);
		if (dropped != null) {
			log("Dropped pending field init patch for " + clazz.getName()
			    + " after redefine failure");
		}
	}

	private static void applyPatch(Class<?> host, PendingPatch patch) throws Throwable {
		// 宿主未初始化时，redefine 后 <clinit> 会用新字节码自然执行，新字段初始化本来就包含在内；
		// 若此时调用静态补丁，会提前触发类初始化、导致 <clinit> 与补丁体重复执行同一表达式
		// （副作用、注册、计数等会被执行两次），还会改变初始化时机引发死锁/顺序问题。
		// 类未初始化也不可能有实例，所以实例补丁同样直接跳过。
		if (!isInitialized(host)) {
			log("Skip field init patch, class not initialized (or initializing/failed): "
			    + host.getName());
			return;
		}

		// 先取实例列表：无实例且无静态补丁时不必定义 hidden class
		List<Object> alive = null;
		if (patch.hasInstance()) {
			alive = collectInstancesForPatch(patch);
			if (alive.isEmpty() && !patch.hasStatic()) {
				log("No live instances and no static patch needed for " + host.getName()
				    + ", skip");
				return;
			}
		}

		// hidden class 必须带 NESTMATE 选项，ProtectedBridge.bootstrap 里的
		// privateLookupIn(host, patchLookup) 依赖这一点。注意：NESTMATE 让 hidden class
		// 加入 host 的 nest host（可能是最外层类），而不是 host 本身的 nest。
		// BSM 里用显式传入的 host 而非 getNestHost()，就是因为这个差别。
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
			if (alive.isEmpty()) return; // 有静态补丁但无实例，实例部分跳过

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
					// 链接类错误（IllegalAccessError / NoClassDefFoundError /
					// BootstrapMethodError）对所有实例结果相同：报一次完整栈后中断，
					// 剩余实例计入 skipped，避免刷屏。
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

	/**
	 * 展开 redefine 前的实例快照。
	 * <p>不变式：{@link #transform} 在 {@code hasInstance()} 为真时必然写入非 null 快照
	 * （{@link PendingPatch} 构造器有断言），因此这里不会出现 null 快照，
	 * 不再保留旧的实时查询回退路径。</p>
	 */
	private static List<Object> collectInstancesForPatch(PendingPatch patch) {
		List<WeakReference<Object>> snapshot = patch.instanceSnapshot();
		List<Object> alive = new ArrayList<>(snapshot.size());
		for (WeakReference<Object> ref : snapshot) {
			Object o = ref.get();
			if (o != null) alive.add(o);
		}
		return alive;
	}

	/** 在 redefine 前快照实例，统一包成弱引用，避免失败路径下被 PENDING 强引用滞留。 */
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

	/**
	 * 类是否已经完成初始化。
	 * <p>{@link Unsafe#shouldBeInitialized(Class)} 对"未初始化/正在初始化/初始化失败"
	 * 三种状态都返回 true（即 {@code shouldBeInitialized=true}），本方法对这三种都返回 false。</p>
	 * <p>跳过补丁的三种情况：</p>
	 * <ul>
	 *   <li><b>还没开始</b>：redefine 后新 &lt;clinit&gt; 会自然跑，新字段初值已就位，跳过正确。</li>
	 *   <li><b>正在初始化</b>：旧 &lt;clinit&gt; 可能已跑过部分；新静态字段和它创建的实例
	 *       都不会被补丁命中，会停在默认值。这是已知窗口——{@code shouldBeInitialized}
	 *       无法区分"还没开始"与"正在跑"，JVMTI {@code GetClassStatus} 也只给 PREPARED 位，
	 *       无法区分。</li>
	 *   <li><b>初始化失败</b>：类不可用，跳过即可。</li>
	 * </ul>
	 */
	private static boolean isInitialized(Class<?> clazz) {
		return !UNSAFE.shouldBeInitialized(clazz);
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
	 * <p>返回的 {@link ExtractResult#protectedAccesses()} 是克隆后指令到原始
	 * {@link ProtectedAccess} 的映射，供 {@link #rewriteProtectedAccesses} 使用。</p>
	 */
	private static ExtractResult extractFieldInits(
	 Class<?> host, String className, MethodNode method, Set<String> targetFields,
	 boolean isStatic, Set<String> privateMethods) {

		if (method == null || targetFields.isEmpty()) {
			return new ExtractResult(List.of(), Map.of());
		}

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
			return new ExtractResult(List.of(), Map.of());
		}

		InsnList              insns         = method.instructions;
		Set<LabelNode>        jumpTargets   = collectJumpTargets(method);
		Set<AbstractInsnNode> safeCollected = new LinkedHashSet<>();
		// 累积"已通过 checkSafe"的 protected 访问；未通过的那次提取的条目会被丢弃
		Map<AbstractInsnNode, ProtectedAccess> allProtected = new HashMap<>();

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

			// 本次提取产生的 protected 访问；仅当 checkSafe 通过时才并入 allProtected
			Map<AbstractInsnNode, ProtectedAccess> localProtected = new HashMap<>();

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
					unsafeReason = checkSafe(host, className, insns, jumpTargets, receiver,
					 collected, minIdx, i, isStatic, privateMethods, localProtected);
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
				allProtected.putAll(localProtected);
			} else {
				HotSwapAgent.warn("Field '" + f.name + "' initialization skipped: " + unsafeReason);
			}
		}

		// 共享 labelMap，保持克隆后指令间的跳转标签拓扑一致；
		// 同时把原始 insn 的 protected 访问映射到克隆后的 insn。
		Map<LabelNode, LabelNode>              labelMap       = new HashMap<>();
		List<AbstractInsnNode>                 result         = new ArrayList<>();
		Map<AbstractInsnNode, ProtectedAccess> clonedAccesses = new HashMap<>();
		for (int i = 0; i < insns.size(); i++) {
			AbstractInsnNode insn = insns.get(i);
			if (!safeCollected.contains(insn)) continue;
			AbstractInsnNode cloned = insn.clone(labelMap);
			result.add(cloned);
			ProtectedAccess pa = allProtected.get(insn);
			if (pa != null) clonedAccesses.put(cloned, pa);
		}
		return new ExtractResult(result, clonedAccesses);
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
	 * <p>包含三类处理：</p>
	 * <ul>
	 *   <li>非 {@code <init>} 的 {@code INVOKESPECIAL}：仅当 {@code owner==className}
	 *       且目标在 {@code privateMethods} 中才放行，交由 {@code rewritePrivateInvokes} 改写；
	 *       否则拒绝。</li>
	 *   <li>{@code invokedynamic} 的 {@code bsmArgs} 里带 {@code H_INVOKESPECIAL} handle：
	 *       与上一条同样的条件，否则 {@code LambdaMetafactory} 会因 specialCaller
	 *       校验失败而链接报错。指向外部 owner 的 {@code super::foo} 这类 handle 直接拒绝。</li>
	 *   <li>跨包 {@code protected} 成员访问：不再拒绝，而是登记到
	 *       {@code outProtectedAccesses}，由 {@link #rewriteProtectedAccesses} 改写成
	 *       indy，通过 {@link ProtectedBridge} 以宿主特权 Lookup 解析。</li>
	 * </ul>
	 */
	private static String checkSafe(Class<?> host, String className, InsnList insns,
	                                Set<LabelNode> jumpTargets, SourceValue receiver,
	                                Set<AbstractInsnNode> collected,
	                                int minIdx, int putIdx, boolean isStatic,
	                                Set<String> privateMethods,
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

		for (AbstractInsnNode n : collected) {
			if (n instanceof VarInsnNode v
			    && (isStatic || v.getOpcode() != Opcodes.ALOAD || v.var != 0)) {
				return "depends on local variables";
			}
			if (n.getOpcode() == Opcodes.IINC) {
				return "depends on local variables";
			}

			// 非 <init> 的 INVOKESPECIAL 只在"同类私有方法调用"时放行
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

			// invokedynamic 的 bsmArgs：只拒绝"指向外部 owner 或非私有宿主方法"的 H_INVOKESPECIAL，
			// 其余的 tag（H_INVOKEVIRTUAL / H_INVOKESTATIC / H_NEWINVOKESPECIAL 等）本来就不需要
			// specialCaller，从 hidden class 调用合法
			if (n instanceof InvokeDynamicInsnNode indy) {
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

			// 跨包 protected 访问：登记待 bridge 化，不拒绝
			if (n instanceof FieldInsnNode f
			    && (f.getOpcode() == Opcodes.GETFIELD || f.getOpcode() == Opcodes.PUTFIELD
			        || f.getOpcode() == Opcodes.GETSTATIC || f.getOpcode() == Opcodes.PUTSTATIC)) {
				if (isProtectedCrossPackageAccess(host, className,
				                                  f.owner, f.name, f.desc, true)) {
					outProtectedAccesses.put(n,
					 new ProtectedAccess(f.getOpcode(), f.owner, f.name, f.desc, false));
				}
			}
			if (n instanceof MethodInsnNode m && !m.owner.startsWith("[")) {
				if (isProtectedCrossPackageAccess(host, className,
				                                  m.owner, m.name, m.desc, false)) {
					outProtectedAccesses.put(n,
					 new ProtectedAccess(m.getOpcode(), m.owner, m.name, m.desc, m.itf));
				}
			}
		}
		return null;
	}

	/**
	 * 判断对某成员的访问在 hidden class 中是否会因"跨包 protected"而失败，
	 * 失败时由 {@link #rewriteProtectedAccesses} 走 {@link ProtectedBridge}。
	 * <p>正确判定必须做三件事：</p>
	 * <ol>
	 *   <li><b>沿继承链找声明类</b>：字节码里的 owner 只是引用点的静态类型。
	 *       {@code this.foo()} / 无前缀 {@code foo()} / {@code this.field} 生成的
	 *       owner 就是宿主自身，但成员可能声明在跨包父类上；{@code INVOKEVIRTUAL Parent.foo}
	 *       的 owner 是 Parent，但 {@code foo} 也可能声明在 Parent 的某个父类上。
	 *       {@code getDeclaredMethods} 只返回本类直接声明的成员，因此不能只看 owner，
	 *       也不能因为 owner==host 就提前返回。</li>
	 *   <li><b>比较声明类与宿主的包</b>：同包（且同一个类加载器，即运行时包相同）时
	 *       protected 成员可在补丁类中直接访问，receiver check 不触发；
	 *       跨包时 protected 实例成员会走 JVMS §5.4.4 的 receiver check，而补丁类
	 *       不是宿主的子类，必须经 bridge 才能通过。比较的必须是声明类的包，而不是 owner 的包。</li>
	 *   <li><b>一直查到 {@code Object}</b>：{@code Object} 自身声明了
	 *       {@code protected Object clone()} 与 {@code protected void finalize()}。
	 *       补丁类虽然也是 {@code Object} 的子类，但接收者是宿主实例，同样过不了
	 *       receiver check。因此不能把 {@code Object.class} 排除在外。</li>
	 * </ol>
	 * <p>构造器不参与继承，只在 owner 自身查找即可。</p>
	 * <p>owner 解析不到时返回 false（视为不触发 protected），让运行时去报错；
	 * 不为了静态检查引入加载副作用。</p>
	 */
	private static boolean isProtectedCrossPackageAccess(
	 Class<?> host, String hostInternal, String ownerInternal,
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
						// 当前类未声明，继续向上
					}
				}
				return false;
			}

			if ("<init>".equals(name)) {
				// 构造器不继承，只在 owner 自身查找
				for (Constructor<?> ctor : owner.getDeclaredConstructors()) {
					if (Type.getConstructorDescriptor(ctor).equals(desc)) {
						return Modifier.isProtected(ctor.getModifiers())
						       && !sameRuntimePackage(owner, host);
					}
				}
				return false;
			}

			// 方法沿父类链一直查到 Object（clone/finalize 的 protected 访问必须拦截）
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

	/**
	 * {@link Class#getDeclaredMethods()} 每次调用都会复制一份数组。本方法在提取阶段
	 * 会对 collected 里的每条指令调用一次，按 Class 缓存可以避免重复复制。
	 */
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

	/**
	 * 运行时包判定：同一个类加载器且包名相同。仅比包名不够——不同 loader 可能
	 * 定义同名包（split package），此时 protected 访问仍然失败。
	 */
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