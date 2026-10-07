package nipx;

import nipx.util.CRC64;
import org.objectweb.asm.*;

import java.util.*;

/**
 * 方法指纹生成器。
 *
 * <p>用于为 Java 方法生成唯一的哈希指纹，主要用于热重载时的方法匹配。</p>
 *
 * <p>工作原理：</p>
 * <ol>
 *   <li>继承 ASM 的 {@link MethodVisitor}，遍历方法的所有字节码指令。</li>
 *   <li>将每条指令的特征信息通过 CRC64 算法累积计算哈希值。</li>
 *   <li>忽略调试信息（行号、局部变量等），只关注实际执行逻辑。</li>
 *   <li>相同逻辑的方法会产生相同的哈希值，用于精确匹配。</li>
 * </ol>
 *
 * <p><b>关于 Label 的稳定性</b>：本类只对“逻辑相关”的 Label 参与指纹，即被
 * 跳转指令、switch 分派、异常表引用的 Label。纯调试用途的 Label（行号锚点、
 * 局部变量作用域边界）会被 {@link #visitLabel} 显式忽略，从而保证
 * {@code javac -g} / {@code -g:lines} / {@code -g:none} 编译出的相同逻辑方法
 * 得到相同 hash。有效 Label 集合由调用方通过 {@link #setValidLabels} 传入。</p>
 *
 * <p><b>关于合成方法名</b>：对本类的 <i>lambda 系列</i> 合成方法名用
 * {@code #SYNTHETIC_METHOD#} 占位，避免内层 lambda 改名影响外层 hash；
 * {@code access$} 例外 —— 它在本对齐器中保名不改名，其名字是稳定信息，
 * 保留它才能区分“调用不同 accessor 的两个 lambda”。</p>
 *
 * <p><b>关于匿名嵌套类归一化与已知限制</b>：所有可能引用类名/描述符的入口 —— 指令操作数、
 * 字段/方法 owner、方法描述符、异常表 type、LDC 常量等 —— 都经过
 * {@link #maskAnonymousClass} 或 {@link #maskDescriptor} 将匿名类序号替换为方法内相对 ID
 * （如 {@code #ANON_0#}），防止外部插入匿名类导致序号位移污染指纹。
 * <b>已知限制（过度归一化代价）</b>：若多个 Lambda 方法体结构同构，仅实例化的匿名内部类不同（如各自实例化不同的
 * {@code Runnable} 回调），它们的指纹会被归一化为完全相同的值。当这些 Lambda 本身发生插入、删除或重排时，
 * 对齐器无法仅凭指纹区分它们，需结合对齐器的碰撞排查与告警机制。</p>
 */
@SuppressWarnings("unused")
public final class MethodFingerprinter extends MethodVisitor {
	public static final ThreadLocal<MethodFingerprinter> CONTEXT =
	 ThreadLocal.withInitial(MethodFingerprinter::new);

	//region 上下文
	/** 当前方法的所属类名（内部名），由 {@link #setContext} 设置。 */
	private String currentClassName;

	/**
	 * 有效 Label 集合；为 {@code null} 时不过滤（兼容旧行为 / 便于单测）。
	 * <p>只对逻辑相关的 Label 记账，过滤掉纯调试标签。</p>
	 */
	private Set<Label> validLabels;

	public void setContext(String className) {
		this.currentClassName = className;
	}

	/**
	 * 设置有效 Label 集合。
	 *
	 * <p>由 {@code LambdaAligner.scan} 在 {@link #reset()} 之后调用。集合中的
	 * Label 与 {@code MethodNode.accept(MethodVisitor)} 回放指令流时传入的
	 * Label 是同一实例（{@code LabelNode.getLabel()} 惰性创建并缓存），
	 * 因此可以用 {@code Set} 直接比较身份。</p>
	 *
	 * <p>本类不做“未设置即报错”的强制检查：{@code null} 表示不过滤，仅用于
	 * 单测或明确知晓 {@code SKIP_DEBUG} 已生效的场景。</p>
	 */
	public void setValidLabels(Set<Label> validLabels) {
		this.validLabels = validLabels;
	}

	/**
	 * 重置指纹生成器状态，为下一个方法做准备。
	 *
	 * <p>同时清空 {@code currentClassName} 与 {@code validLabels}，强制下一次
	 * 使用前重新设置，避免因为调用方忘记设置而沿用上一方法的上下文，
	 * 造成 <b>静默错配</b>（比抛异常更危险）。</p>
	 */
	public void reset() {
		crc = CRC64.init();
		labelIds.clear();
		nextLabelId = 0;
		anonClassIds.clear();
		nextAnonId = 0;
		anonMasked = false;
		anonHashes = null;
		validLabels = null;
		currentClassName = null;
	}
	//endregion

	//region 标记常量
	private static final int MARK_LDC             = 0x7F000010;
	private static final int MARK_IINC            = 0x7F000011;
	private static final int MARK_TABLESWITCH     = 0x7F000012;
	private static final int MARK_LOOKUPSWITCH    = 0x7F000013;
	private static final int MARK_TRY_CATCH       = 0x7F000014;
	private static final int MARK_INVOKEDYNAMIC   = 0x7F000015;
	private static final int MARK_MULTIANEWARRAY  = 0x7F000016;
	private static final int MARK_LABEL           = 0x7F000017;
	private static final int MARK_JUMP            = 0x7F000018;
	private static final int MARK_PARAM_ANNOT_CNT = 0x7F000019;
	private static final int MARK_ANNOT_DEFAULT   = 0x7F00001A;
	//endregion

	//region ID 管理
	/** 标签到 ID 的映射，用于统一标识跳转目标。 */
	private final Map<Label, Integer> labelIds    = new IdentityHashMap<>();
	/** 下一个可用的标签 ID。 */
	private       int                 nextLabelId = 0;

	/**
	 * 获取标签的唯一 ID，如果不存在则分配新的 ID。
	 * @param l 标签对象
	 * @return 标签的整数 ID
	 */
	private int getLabelId(Label l) {
		return labelIds.computeIfAbsent(l, k -> nextLabelId++);
	}

	private final Map<String, Integer> anonClassIds = new HashMap<>();
	private       int                  nextAnonId   = 0;
	/** 当前方法指纹计算中是否对匿名类进行了归一化屏蔽。 */
	private       boolean              anonMasked   = false;
	/** 预计算的匿名类内容结构哈希（类内部名 -> 64位哈希），用于增强指纹区分度。 */
	private       Map<String, Long>    anonHashes;

	public boolean hasMaskedAnon() {
		return anonMasked;
	}

	public void setAnonHashes(Map<String, Long> anonHashes) {
		this.anonHashes = anonHashes;
	}

	/**
	 * 核心统一拦截器：处理所有出现的内部类名称。
	 *
	 * <p>仅对“后缀以数字开头”的不稳定嵌套类做归一化，例如
	 * {@code Outer$1}、{@code Outer$1$2}、{@code Outer$bar$1}、{@code Outer$1Local}。
	 * 具名嵌套类（如 {@code Outer$Builder}）保持不变。</p>
	 *
	 * <p>允许 {@code owner} 为 {@code null}（例如 catch-any/finally 的
	 * 异常类型），与 {@link #maskDescriptor} 保持一致：{@code null} 原样返回。</p>
	 * @return 屏蔽编号后的安全描述符
	 */
	private String maskAnonymousClass(String owner) {
		if (owner == null) return null;
		if (currentClassName == null) return owner;
		if (owner.startsWith("[")) {
			int dims = 0;
			while (dims < owner.length() && owner.charAt(dims) == '[') dims++;
			if (dims < owner.length() && owner.charAt(dims) == 'L' && owner.endsWith(";")) {
				String internal = owner.substring(dims + 1, owner.length() - 1);
				String masked   = maskAnonymousClass(internal);
				if (!masked.equals(internal)) {
					return owner.substring(0, dims) + "L" + masked + ";";
				}
			}
			return owner;
		}
		if (!owner.startsWith(currentClassName + "$")) return owner;
		String suffix = owner.substring(currentClassName.length() + 1);
		if (!isUnstableNestedSuffix(suffix)) return owner;
		int relId = anonClassIds.computeIfAbsent(owner, k -> nextAnonId++);
		anonMasked = true;
		Long h = anonHashes != null ? anonHashes.get(owner) : null;
		return h != null ? "#ANON_" + relId + "_" + Long.toHexString(h) + "#"
		 : "#ANON_" + relId + "#";
	}

	/**
	 * 判断嵌套类后缀是否「可能随编译顺序位移」的匿名类。
	 *
	 * <p>取最后一段（以 {@code $} 分隔），如果它完全由数字组成，才视为匿名类：</p>
	 * <ul>
	 *   <li>{@code 1}      → 不稳定（匿名类）</li>
	 *   <li>{@code 1$2}    → 不稳定（嵌套匿名类，末段为 2）</li>
	 *   <li>{@code bar$1}  → 不稳定（Kotlin 的 {@code Foo$bar$1}）</li>
	 *   <li>{@code 1Local} → 具名局部类，保留名字，不按匿名类处理</li>
	 *   <li>{@code Builder}→ 具名内部类，稳定</li>
	 * </ul>
	 */
	public static boolean isUnstableNestedSuffix(String suffix) {
		int lastSep = suffix.lastIndexOf('$');
		int start   = lastSep + 1;
		if (start >= suffix.length()) return false;
		for (int i = start; i < suffix.length(); i++) {
			if (!Character.isDigit(suffix.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 屏蔽描述符中的匿名类引用，例如 {@code (LOuter$1;)V -> (L#ANON_0#;)V}，
	 * 保证匿名类编号位移不会影响指纹。
	 *
	 * <p><b>破坏范围是刻意收窄的</b>：仅当描述符里出现 {@code L<本上下文类名>$<不稳定数字后缀>;}
	 * 时才改写，且前置 {@code desc.indexOf('$') < 0} 快速返回。因此：
	 * <ul>
	 *   <li>{@code ()V} / {@code (I)V} / {@code (Ljava/lang/String;)V} 等<b>不含 {@code $} 的描述符原样返回</b>
	 *       —— 普通重载的语义差异不会被抹掉；</li>
	 *   <li>{@code (LOuter$Inner;)V}（具名内部类）也因为后缀非纯数字而不改写。</li>
	 * </ul>
	 *
	 * <p>包内可见：{@link AnonClassHasher} 组装方法签名时也要用它 —— 嵌套匿名类的构造器描述符形如
	 * {@code (LOuter$1;)V}，父类编号一移位，子类的原始描述符就会变，导致子类内容哈希必然改变、
	 * Tier 1/2 全面失效（详见 {@code DeepNestProbe} 的实测）。</p>
	 */
	String maskDescriptor(String desc) {
		if (desc == null || currentClassName == null || desc.indexOf('$') < 0) return desc;
		String prefix = "L" + currentClassName + "$";
		int    idx    = desc.indexOf(prefix);
		if (idx < 0) return desc;

		StringBuilder sb  = new StringBuilder(desc.length() + 8);
		int           pos = 0;
		while (idx >= 0) {
			sb.append(desc, pos, idx);
			int end = desc.indexOf(';', idx);
			if (end < 0) return desc;
			String owner  = desc.substring(idx + 1, end);
			String masked = maskAnonymousClass(owner);
			sb.append('L').append(masked).append(';');
			pos = end + 1;
			idx = desc.indexOf(prefix, pos);
		}
		sb.append(desc, pos, desc.length());
		return sb.toString();
	}
	//endregion

	//region CRC64 哈希值计算
	/** 当前累积的 CRC64 哈希值。 */
	private long crc = CRC64.init();

	public MethodFingerprinter() {
		super(Opcodes.ASM9);
	}

	/**
	 * 获取最终计算出的哈希值。
	 * @return 64 位 CRC 哈希值
	 */
	public long getHash() {
		return CRC64.finish(crc);
	}
	//endregion

	//region CRC64 更新方法
	private void updateInt(int v) {
		crc = CRC64.updateInt(crc, v);
	}

	private void updateLong(long v) {
		crc = CRC64.updateLong(crc, v);
	}

	/**
	 * 更新 CRC 值与字符串。
	 *
	 * <p>写入长度前缀，避免 {@code (owner="a/B", name="cd")} 与
	 * {@code (owner="a/Bc", name="d")} 这类拼接歧义。{@code null} 用 {@code -1}
	 * 与空串区分。</p>
	 */
	private void updateString(String s) {
		if (s == null) {
			updateInt(-1);
			return;
		}
		updateInt(s.length());
		crc = CRC64.updateStringUTF16(crc, s);
	}

	/**
	 * 更新 CRC 值与方法句柄。
	 *
	 * <p>对本类 <i>lambda 系列</i> 合成方法的名字用占位符替代，避免内层 lambda
	 * 改名连带影响外层 hash。{@code access$} 例外 —— 它在本对齐器中保名不改名，
	 * 名字是稳定信息，保留它才能区分“调用不同 accessor 的两个 lambda”。</p>
	 */
	private void updateHandle(Handle h) {
		updateInt(h.getTag());
		String  owner  = h.getOwner();
		boolean isSelf = owner.equals(currentClassName);

		updateString(isSelf ? "#THIS#" : maskAnonymousClass(owner));

		String name = h.getName();
		if (isSelfSynthetic(owner, name)) {
			updateString("#SYNTHETIC_METHOD#");
			updateString("#SYNTHETIC_DESC#");
		} else {
			updateString(name);
			updateString(maskDescriptor(h.getDesc()));
		}

		updateInt(h.isInterface() ? 1 : 0);
	}

	/**
	 * 共享判定：owner 是本类 且 name 是 <i>lambda 系列</i> 合成名。
	 *
	 * <p>{@code access$} 显式排除：它在本对齐器中保名不改名，其名字作为稳定信息
	 * 参与 hash，抹掉只会让“调用不同 accessor 的两个 lambda”撞 hash。</p>
	 */
	private boolean isSelfSynthetic(String owner, String name) {
		return owner.equals(currentClassName)
		       && isSyntheticName(name)
		       && !name.startsWith("access$");
	}

	/**
	 * 判断方法名是否是随机 / 递增序号的合成名，逻辑上可能发生偏移。
	 */
	static boolean isSyntheticName(String name) {
		return name.contains("lambda$")     // Java / Kotlin Indy
		       || name.contains("$lambda")  // Kotlin
		       || name.contains("$anonfun$")// Scala
		       || name.contains("access$"); // Accessors（内部类访问桩）
	}

	/**
	 * 不参与匹配、重命名、复活的合成方法。
	 *
	 * <p>包内可见，供 {@code LambdaAligner.scan} 调用。</p>
	 */
	static boolean isExcluded(String name) {
		return name.equals("$deserializeLambda$")
		       || name.equals("$values")             // enum 的 synthetic 工厂
		       || name.equals("$jacocoInit")         // 覆盖率插桩
		       || name.startsWith("$SWITCH_TABLE$"); // Eclipse 编译器生成
	}
	//endregion

	//region 字节码指令处理
	@Override
	public void visitInsn(int opcode) {
		updateInt(opcode);
	}

	@Override
	public void visitIntInsn(int opcode, int operand) {
		updateInt(opcode);
		updateInt(operand);
	}

	@Override
	public void visitVarInsn(int opcode, int var) {
		updateInt(opcode);
		updateInt(var);
	}

	@Override
	public void visitTypeInsn(int opcode, String type) {
		updateInt(opcode);
		updateString(maskAnonymousClass(type));
	}

	@Override
	public void visitFieldInsn(int opcode, String owner, String name, String desc) {
		updateInt(opcode);
		updateString(maskAnonymousClass(owner));
		updateString(name);
		updateString(maskDescriptor(desc));
	}

	@Override
	public void visitMethodInsn(int opcode, String owner, String name,
	                            String desc, boolean isInterface) {
		updateInt(opcode);
		updateString(maskAnonymousClass(owner));
		updateString(isSelfSynthetic(owner, name) ? "#SYNTHETIC_METHOD#" : name);
		updateString(maskDescriptor(desc));
		updateInt(isInterface ? 1 : 0);
	}

	@Override
	public void visitInvokeDynamicInsn(String name,
	                                   String desc,
	                                   Handle bsm,
	                                   Object... bsmArgs) {

		updateInt(MARK_INVOKEDYNAMIC);

		updateString(name);
		updateString(maskDescriptor(desc));
		updateHandle(bsm);

		if (bsmArgs == null) {
			updateInt(0);
		} else {
			updateInt(bsmArgs.length);
			for (Object o : bsmArgs) {
				updateConstant(o);
			}
		}
	}

	@Override
	public void visitLdcInsn(Object value) {
		updateInt(MARK_LDC);
		updateConstant(value);
	}

	@Override
	public void visitIincInsn(int var, int increment) {
		updateInt(MARK_IINC);
		updateInt(var);
		updateInt(increment);
	}

	@Override
	public void visitTableSwitchInsn(int min, int max,
	                                 Label dflt, Label... labels) {
		updateInt(MARK_TABLESWITCH);
		updateInt(min);
		updateInt(max);
		updateInt(getLabelId(dflt));

		for (Label l : labels) {
			updateInt(getLabelId(l));
		}
	}

	@Override
	public void visitLookupSwitchInsn(Label dflt,
	                                  int[] keys,
	                                  Label... labels) {

		updateInt(MARK_LOOKUPSWITCH);
		updateInt(getLabelId(dflt));

		for (int i = 0; i < keys.length; i++) {
			updateInt(keys[i]);
			updateInt(getLabelId(labels[i]));
		}
	}

	@Override
	public void visitJumpInsn(int opcode, Label label) {
		updateInt(opcode);
		updateInt(MARK_JUMP);
		updateInt(getLabelId(label));
	}

	/**
	 * 记录 Label。
	 *
	 * <p>只对 {@link #validLabels} 中的 Label 记账 —— 也就是被跳转指令、
	 * switch 分派、异常表引用的 Label。纯调试用途的 Label（行号锚点、局部变量
	 * 作用域边界）会被跳过，从而保证不同 {@code -g} 设置下产生相同 hash。</p>
	 *
	 * <p>{@code validLabels == null} 表示不过滤，兼容单测和明确不带调试信息的
	 * 场景。</p>
	 */
	@Override
	public void visitLabel(Label label) {
		if (validLabels != null && !validLabels.contains(label)) return;
		updateInt(MARK_LABEL);
		updateInt(getLabelId(label));
	}

	/**
	 * 处理异常处理块。
	 *
	 * <p>{@code type} 是异常类的内部名，{@code null} 表示 catch-any / finally。
	 * 它可能引用本类的匿名嵌套类（如 Kotlin 的 {@code SomeSealed$1}），
	 * 因此必须经过 {@link #maskAnonymousClass}，否则“插入匿名 object 导致
	 * 编号位移”会连带污染指纹。</p>
	 */
	@Override
	public void visitTryCatchBlock(Label start,
	                               Label end,
	                               Label handler,
	                               String type) {

		updateInt(MARK_TRY_CATCH);
		updateInt(getLabelId(start));
		updateInt(getLabelId(end));
		updateInt(getLabelId(handler));
		updateString(maskAnonymousClass(type));
	}

	@Override
	public void visitMultiANewArrayInsn(String desc, int dims) {
		updateInt(MARK_MULTIANEWARRAY);
		updateString(maskDescriptor(desc));
		updateInt(dims);
	}
	//endregion

	//region 注解处理
	@Override
	public AnnotationVisitor visitAnnotationDefault() {
		updateInt(MARK_ANNOT_DEFAULT);
		return new FingerprintAnnotationVisitor();
	}

	@Override
	public void visitAnnotableParameterCount(int parameterCount,
	                                         boolean visible) {
		updateInt(MARK_PARAM_ANNOT_CNT);
		updateInt(parameterCount);
		updateInt(visible ? 1 : 0);
	}
	//endregion

	//region 常量处理
	/**
	 * 处理各种类型的常量值。
	 *
	 * <p>未知类型降级为字符串处理，不再抛异常，与“优先不崩溃”的目标保持一致。
	 * {@code Boolean}/{@code Byte}/{@code Short}/{@code Character} 等虽然不会出现
	 * 在 {@code ldc} 中，但注解值里可能有。</p>
	 */
	private void updateConstant(Object cst) {
		switch (cst) {
			case null -> updateInt(0);
			case Integer i -> {
				updateInt(1);
				updateInt(i);
			}
			case Long l -> {
				updateInt(2);
				updateLong(l);
			}
			case Float f -> {
				updateInt(3);
				updateInt(Float.floatToRawIntBits(f));
			}
			case Double d -> {
				updateInt(4);
				updateLong(Double.doubleToRawLongBits(d));
			}
			case String s -> {
				updateInt(5);
				updateString(s);
			}
			case Type t -> {
				updateInt(6);
				updateString(maskDescriptor(t.getDescriptor()));
			}
			case Handle h -> {
				updateInt(7);
				updateHandle(h);
			}
			case ConstantDynamic cd -> {
				updateInt(8);
				updateString(cd.getName());
				updateString(maskDescriptor(cd.getDescriptor()));
				updateHandle(cd.getBootstrapMethod());

				int count = cd.getBootstrapMethodArgumentCount();
				updateInt(count);
				for (int i = 0; i < count; i++) {
					updateConstant(cd.getBootstrapMethodArgument(i));
				}
			}
			default -> {
				updateInt(99);
				updateString(String.valueOf(cst));
			}
		}
	}
	//endregion

	//region 注解访问器
	/**
	 * 指纹注解访问器，用于把注解信息纳入指纹计算。
	 *
	 * <p>当前只覆盖 {@code visitAnnotationDefault} 相关的路径；普通注解
	 * （{@code visitAnnotation}）不会主动进入这里。</p>
	 */
	public final class FingerprintAnnotationVisitor extends AnnotationVisitor {

		public FingerprintAnnotationVisitor() {
			super(Opcodes.ASM9);
		}

		@Override
		public void visit(String name, Object value) {
			updateString(name);
			updateConstant(value);
		}

		@Override
		public void visitEnum(String name, String desc, String value) {
			updateString(name);
			updateString("enum");
			updateString(maskDescriptor(desc));
			updateString(value);
		}

		@Override
		public AnnotationVisitor visitAnnotation(String name, String desc) {
			updateString(name);
			updateString(maskDescriptor(desc));
			return this;
		}

		@Override
		public AnnotationVisitor visitArray(String name) {
			updateString(name);
			updateString("array");
			updateString("[");
			return this;
		}

		/**
		 * 数组/注解的结束标记。
		 *
		 * <p>没有它，扁平形式 {@code [a,b]} 与嵌套形式 {@code [[a,b]]} 会算出同一个指纹
		 * （分隔符缺失导致两次 {@code "array"} 与四个元素无法区分边界）。
		 * 本访问器只服务于 {@link #visitAnnotationDefault}，而 lambda 不会有注解默认值，
		 * 因此这个修正不会改变任何现有 lambda 的指纹。</p>
		 */
		@Override
		public void visitEnd() {
			updateString("]");
		}
	}
	//endregion

	//region 忽略信息
	/** 忽略行号信息（不影响程序逻辑）。 */
	@Override
	public void visitLineNumber(int line, Label start) { }

	/** 忽略局部变量信息（不影响程序逻辑）。 */
	@Override
	public void visitLocalVariable(String name, String desc,
	                               String sig, Label start,
	                               Label end, int index) { }
	//endregion
}