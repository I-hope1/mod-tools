package nipx;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldNode;

import java.util.*;

/**
 * 实例状态布局安全门（{@code docs/topology/07-layout-gate-and-risks.md} §1 的精确变体）。
 *
 * <p><b>设计文档与状态索引</b>：
 * 设计文档参见 {@code docs/topology/07-layout-gate-and-risks.md} §1 与 {@code docs/initfix/03-runtime-driver.md} §5；
 * 实现状态参见 {@code docs/status.md} 与 {@code AGENTS.md}。</p>
 *
 * <p><b>守的是什么</b>：字段布局变化后，<b>已经存在的实例</b>不会获得新字段的初始化 ——
 * 它的布局在创建时就定下了。新方法体去读这个字段就会读到零值。
 * 新建实例不受影响（会走新构造器），这一点有真机对照实验支撑，见
 * {@code scratch/layoutprobe/LayoutProbe.java} 的 {@code named} case：
 * 重取构造器 / 字节码里直接 {@code new} / 直读字段三条路径都取到正确值。</p>
 *
 * <p><b>为什么必须是纯函数</b>：对齐器（配对前）与重定义层（配对后）都要用同一套判据，
 * 而 {@code docs/topology/03-cascading-pipeline.md} §3 要求这两层互不调用。因此规则抽在这里，两侧各自调用，谁都不依赖谁。
 * 本类<b>不</b>接触字节码之外的任何状态：不查实例、不打日志、不读系统属性。</p>
 *
 * <p><b>为什么合成字段必须在这里挡住</b>：{@link ClassDiffUtil}
 * 会对称过滤 {@code ACC_SYNTHETIC} 字段，所以 {@link nipx.ref.InitFix} 永远看不到 {@code val$*}、
 * {@code this$0} —— 它们不可能被字段初始化补丁覆盖。详见
 * {@code docs/initfix/03-runtime-driver.md} §5。</p>
 *
 * <p><b>分工与局部类防护</b>：
 * <ul>
 *   <li><b>匿名类合成字段</b>：由本门作为谓词包装在 {@link AnonClassAligner}（Tier 4 配对前）接入
 *       （受 {@link HotSwapAgent#ANON_LAYOUT_GATE} 控制）；</li>
 *   <li><b>具名类/用户显式字段</b>：在重定义层由 {@link HotSwapAgent} 接入 {@link #checkRedefine}
 *       （受 {@link HotSwapAgent#LAYOUT_GATE} 控制）；</li>
 *   <li><b>局部类（{@code Foo$1Local}）</b>：局部类不进匿名类对齐器，其编号漂移已由独立的
 *       {@link LocalClassGuard}（受 {@link HotSwapAgent#LOCAL_CLASS_GUARD} 控制）进行防御。</li>
 * </ul>
 * </p>
 *
 * <p><b>已知限制 ①（竞态窗口）</b>：判定"有无存活实例"与真正 {@code redefineClasses} 之间，
 * 应用线程可能新建一个实例 —— 它会拿到旧布局，重定义后就是零值。
 * 全局锁只覆盖热更入口，管不到应用线程。窗口很小但确实存在，
 * 缓解办法是在 {@code preRegister} 之后、{@code redefineClasses} 之前对被放行的类再扫一次。</p>
 *
 * <p><b>已知限制 ②（陈旧捕获值）</b>：若用户只改了捕获的<b>值</b>而布局不变
 * （例如把 {@code int x = 123} 改成 {@code 456}），匿名类的内容哈希不含捕获值，
 * 本门也看不见 —— 存活实例的 {@code val$x} 仍是旧值。这是独立于本门的一个缺口。</p>
 */
public final class LayoutGate {

	private LayoutGate() { }

	/** 判定结果。{@code compatible == true} 表示放行。 */
	public enum Verdict {
		/** 字段集合与访问标志完全一致（或只是纯删除）—— 放行。 */
		COMPATIBLE,
		/** 新增了合成捕获字段（{@code val$*}/{@code this$0}）—— 存活实例读它得零值。 */
		ADDED_SYNTHETIC_FIELD,
		/** 合成捕获字段被删除了。见 {@link #check} 的说明，这一档放行。 */
		REMOVED_SYNTHETIC_FIELD,
		/** 同名字段改了类型 —— 存活实例的旧值按旧类型存放，按新类型读会错。 */
		CHANGED_FIELD_TYPE,
		/**
		 * 字段被删除（重定义层入口专有）。
		 *
		 * <p>与 {@link #check} 中"纯删除放行"不同：重定义层面对的是<b>用户显式编辑</b>，
		 * 静默删掉字段会让用户以为无损（旧值随新布局消失）。因此这里保守地拒绝，
		 * 交给用户重新热更一次或重启确认。见 {@code docs/topology/07-layout-gate-and-risks.md} §1 的分级门表。</p>
		 */
		REMOVED_FIELD,
		/**
		 * 同名字段在 static 与实例之间切换。
		 *
		 * <p>单独成档的理由：文档 1 记录的内容哈希只含 {@code name:desc}、<b>不含访问标志</b>，
		 * 所以"同名同描述符、实例↔静态"在 Tier 1~3 里都看不出来，
		 * 而 layoutprobe 实测这种情况旧值会被丢弃。因此这个 static 位只在本门比较，
		 * <b>不得</b>加进哈希 —— 那会扰动所有 Tier 1/2 的配对。</p>
		 */
		CHANGED_STATICNESS;
	}

	/** 一个字段的判定相关属性：名、描述符、是否 static、是否合成。 */
	public record FieldInfo(String name, String desc, boolean isStatic, boolean isSynthetic) {
		@Override
		public String toString() {
			return name + ":" + desc + (isStatic ? ":static" : "") + (isSynthetic ? ":synthetic" : "");
		}
	}

	/** 判定细节：档位 + 人读的原因（用于日志，避免"静默的保守"）。 */
	public record Result(Verdict verdict, String detail) {
		public boolean compatible() {
			return verdict == Verdict.COMPATIBLE;
		}
	}

	/** 从 ASM 字段表构造判定输入。合成判定用 {@code ACC_SYNTHETIC} 加名字前缀双保险。 */
	public static List<FieldInfo> of(List<FieldNode> fields) {
		return of(fields, java.util.function.Function.identity());
	}

	/**
	 * 带描述符屏蔽的变体：对齐器用。{@code this$N:LOuter$K;} 的描述符会随父匿名类位移而变；
	 * javac 18+ 仅在外层实例**未被用到**时才省略 {@code this$N}，使用外层实例时 8/11/17/21 都生成。
	 * 真实重定义时 {@code ClassRemapper} 会把描述符改成目标名、布局其实相同；故比较前先屏蔽，
	 * 免得把"内容未变的位移"读成"改类型"而误拒。
	 */
	public static List<FieldInfo> of(List<FieldNode> fields, java.util.function.Function<String, String> descMask) {
		List<FieldInfo> out = new ArrayList<>();
		if (fields == null) return out;
		for (FieldNode f : fields) {
			out.add(new FieldInfo(f.name, descMask.apply(f.desc),
			 (f.access & Opcodes.ACC_STATIC) != 0, isSyntheticCapture(f)));
		}
		out.sort(Comparator.comparing(FieldInfo::name).thenComparing(FieldInfo::desc));
		return out;
	}

	/**
	 * 是否"合成捕获字段"。
	 *
	 * <p>双保险：{@code ACC_SYNTHETIC} 是权威依据，但某些编译器/后处理会丢这个标志，
	 * 而 javac 的捕获字段命名是固定的（{@code val$<局部变量名>}、{@code this$0}、{@code this$1}…），
	 * 所以名字前缀作为兜底。反过来也成立：只认名字会漏掉 Kotlin 等其它来源的合成字段。</p>
	 */
	public static boolean isSyntheticCapture(FieldNode f) {
		if (f == null || f.name == null) return false;
		if ((f.access & Opcodes.ACC_SYNTHETIC) != 0) return true;
		String n = f.name;
		return n.startsWith("val$") || n.equals("this$0") || n.matches("this\\$\\d+");
	}

	/**
	 * 比较新旧字段表，判断"存活实例是否会读到零值"。
	 *
	 * <p>规则表：</p>
	 * <table>
	 *   <tr><th>字段变化</th><th>判定</th></tr>
	 *   <tr><td>字段集合与属性完全相同</td><td>{@code COMPATIBLE}</td></tr>
	 *   <tr><td>新增合成捕获字段</td><td>{@code ADDED_SYNTHETIC_FIELD}</td></tr>
	 *   <tr><td>新增非合成字段（匿名类体里用户自己声明的）</td><td>{@code COMPATIBLE}，交给 InitFix</td></tr>
	 *   <tr><td><b>纯删除</b>（删掉的都是新增侧不再有的，没有同名改类型）</td>
	 *       <td>{@code COMPATIBLE} — 见下方说明</td></tr>
	 *   <tr><td>同名字段改类型</td><td>{@code CHANGED_FIELD_TYPE}</td></tr>
	 *   <tr><td>同名字段 static 性变化</td><td>{@code CHANGED_STATICNESS}</td></tr>
	 * </table>
	 *
	 * <p><b>为什么纯删除放行</b>：删除意味着新方法体已经不可能引用该字段，
	 * 丢掉的只是"新代码不会读"的状态，不会让任何代码读到零值。
	 * 真正危险的是新增、改类型、static 性变化 —— 它们会让新代码去读一个值为零的槽。
	 * 注意"换了捕获变量"（{@code val$a} 换成 {@code val$b}）仍会被"新增"这一档挡住，
	 * 所以常见编辑不受影响。</p>
	 */
	public static Result check(List<FieldInfo> oldFields, List<FieldInfo> newFields) {
		Map<String, FieldInfo> oldByKey = new HashMap<>();
		for (FieldInfo f : oldFields) oldByKey.put(f.name(), f);
		Map<String, FieldInfo> newByKey = new HashMap<>();
		for (FieldInfo f : newFields) newByKey.put(f.name(), f);

		// 先查同名项的类型与 static 性变化（这两档最危险，优先报出）
		List<String> typeChanged   = new ArrayList<>();
		List<String> staticChanged = new ArrayList<>();
		for (Map.Entry<String, FieldInfo> e : newByKey.entrySet()) {
			FieldInfo o = oldByKey.get(e.getKey());
			if (o == null) continue;
			FieldInfo n = e.getValue();
			if (!o.desc().equals(n.desc())) {
				typeChanged.add(n.name() + " " + o.desc() + " -> " + n.desc());
			}
			if (o.isStatic() != n.isStatic()) {
				staticChanged.add(n.name() + " " + (o.isStatic() ? "static" : "instance")
				                  + " -> " + (n.isStatic() ? "static" : "instance"));
			}
		}
		if (!typeChanged.isEmpty()) {
			return new Result(Verdict.CHANGED_FIELD_TYPE, "field type changed: " + typeChanged);
		}
		if (!staticChanged.isEmpty()) {
			return new Result(Verdict.CHANGED_STATICNESS, "field staticness changed: " + staticChanged);
		}

		// 新增字段
		List<String> addedSynthetic = new ArrayList<>();
		List<String> addedPlain     = new ArrayList<>();
		for (Map.Entry<String, FieldInfo> e : newByKey.entrySet()) {
			if (oldByKey.containsKey(e.getKey())) continue;
			(e.getValue().isSynthetic() ? addedSynthetic : addedPlain).add(e.getValue().toString());
		}
		if (!addedSynthetic.isEmpty()) {
			return new Result(Verdict.ADDED_SYNTHETIC_FIELD,
			 "added synthetic capture field: " + addedSynthetic
			 + "  (surviving instances keep 0; InitFix cannot patch these —"
			 + " ClassDiffUtil filters ACC_SYNTHETIC)");
		}

		// 删除字段（合成与否都放行，理由见 javadoc）
		List<String> removed = new ArrayList<>();
		for (Map.Entry<String, FieldInfo> e : oldByKey.entrySet()) {
			if (!newByKey.containsKey(e.getKey())) removed.add(e.getValue().toString());
		}

		StringBuilder detail = new StringBuilder("compatible");
		if (!addedPlain.isEmpty()) detail.append("; added plain field (InitFix handles): ").append(addedPlain);
		if (!removed.isEmpty()) detail.append("; removed field (harmless): ").append(removed);
		return new Result(Verdict.COMPATIBLE, detail.toString());
	}

	/**
	 * 重定义层入口：从 {@link ClassDiffUtil.ClassDiff#changedFields} 判定（{@code docs/topology/07-layout-gate-and-risks.md} §1）。
	 *
	 * <p><b>为什么需要第二个入口</b>：{@code changedFields} 已经被
	 * {@code ClassDiffUtil.isInternalMarkerField} 过滤掉合成字段，因此本入口只覆盖
	 * <b>非合成</b>字段（具名类与匿名类里用户自己声明的字段）。匿名类的合成捕获字段
	 * （{@code val$*} / {@code this$0}）由对齐器的 Tier 4 门经 {@link #check} 挡住 ——
	 * 两个入口互补，判据同源。</p>
	 *
	 * <p><b>条目格式</b>：{@code "+ name"} / {@code "- name"}，静态字段名带 {@code *} 前缀
	 * （如 {@code "- *a"}）。{@code ClassDiffUtil} 的映射销毁法保证了四种情形：</p>
	 * <ul>
	 *   <li>纯新增 → 只有 {@code "+ name"} → <b>放行</b>（InitFix 初始化存活实例的新字段）；</li>
	 *   <li>纯删除 → 只有 {@code "- name"} → {@link Verdict#REMOVED_FIELD}；</li>
	 *   <li>同名改类型 → {@code "- a"} + {@code "+ a"}（desc 不同使旧 key 不匹配）→
	 *       {@link Verdict#CHANGED_FIELD_TYPE}；</li>
	 *   <li>静态性变更 → {@code "- a"} + {@code "+ *a"}（或反向）→
	 *       {@link Verdict#CHANGED_STATICNESS}。</li>
	 * </ul>
	 *
	 * <p>按字段名把 {@code +}/{@code -} 配对即可区分，比重写一个字段布局比较器便宜。</p>
	 */
	public static Result checkChangedFields(List<String> changedFields) {
		if (changedFields == null || changedFields.isEmpty()) {
			return new Result(Verdict.COMPATIBLE, "compatible");
		}
		Map<String, Boolean> added   = new LinkedHashMap<>();
		Map<String, Boolean> removed = new LinkedHashMap<>();
		for (String raw : changedFields) {
			if (raw == null || raw.length() < 2) continue;
			char   sign   = raw.charAt(0);
			String body   = raw.substring(1).trim();
			boolean statik = body.startsWith("*");
			String  name   = statik ? body.substring(1) : body;
			if (sign == '+')      added.put(name, statik);
			else if (sign == '-') removed.put(name, statik);
		}
		if (removed.isEmpty()) {
			return new Result(Verdict.COMPATIBLE,
			 "added field(s) only (InitFix initializes surviving instances): " + added.keySet());
		}
		List<String> typeChanged   = new ArrayList<>();
		List<String> staticChanged = new ArrayList<>();
		List<String> pureRemoved   = new ArrayList<>();
		for (Map.Entry<String, Boolean> e : removed.entrySet()) {
			String  name = e.getKey();
			Boolean neu  = added.get(name);
			if (neu == null) {
				pureRemoved.add(name);
			} else if (!neu.equals(e.getValue())) {
				staticChanged.add(name);
			} else {
				typeChanged.add(name);
			}
		}
		if (!typeChanged.isEmpty()) {
			return new Result(Verdict.CHANGED_FIELD_TYPE, "field type changed: " + typeChanged);
		}
		if (!staticChanged.isEmpty()) {
			return new Result(Verdict.CHANGED_STATICNESS, "field staticness changed: " + staticChanged);
		}
		return new Result(Verdict.REMOVED_FIELD, "field(s) removed: " + pureRemoved);
	}

	/**
	 * 该组 {@code changedFields} 是否涉及<b>静态字段</b>（条目名带 {@code *} 前缀）。
	 *
	 * <p>用于存活实例分级：实例字段的旧值只存在于实例上，没有实例就无所谓"读到旧值/零值"；
	 * 而静态字段的值是<b>类级别共享</b>的，与有无实例无关——类只要加载过就可能已被写入。
	 * 因此静态字段的增删改<b>不能</b>因"无存活实例"而放行。</p>
	 */
	public static boolean hasStaticFieldChange(List<String> changedFields) {
		if (changedFields == null) return false;
		for (String raw : changedFields) {
			if (raw == null || raw.length() < 2 || raw.charAt(0) == ' ') continue;
			String body = raw.substring(1).trim();
			if (body.startsWith("*")) return true;
		}
		return false;
	}

	/** 命名常量：默认模式。{@code warn}/{@code off} 见 {@code HotSwapAgent.ANON_LAYOUT_GATE}。 */
	public static final String MODE_REJECT = "reject";
	public static final String MODE_WARN   = "warn";
	public static final String MODE_OFF    = "off";
}
