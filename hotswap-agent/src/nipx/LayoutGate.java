package nipx;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldNode;

import java.util.*;

/**
 * 实例状态布局安全门（{@code docs/ANONYMOUS_CLASS_TOPOLOGY_PLAN.md} §7.2 的精确变体）。
 *
 * <p><b>守的是什么</b>：字段布局变化后，<b>已经存在的实例</b>不会获得新字段的初始化 ——
 * 它的布局在创建时就定下了。新方法体去读这个字段就会读到零值。
 * 新建实例不受影响（会走新构造器），这一点有真机对照实验支撑，见
 * {@code scratch/layoutprobe/LayoutProbe.java} 的 {@code named} case：
 * 重取构造器 / 字节码里直接 {@code new} / 直读字段三条路径都取到正确值。</p>
 *
 * <p><b>为什么必须是纯函数</b>：对齐器（配对前）与重定义层（配对后）都要用同一套判据，
 * 而 §3.6 要求这两层互不调用。因此规则抽在这里，两侧各自调用，谁都不依赖谁。
 * 本类<b>不</b>接触字节码之外的任何状态：不查实例、不打日志、不读系统属性。</p>
 *
 * <p><b>为什么合成字段必须在这里挡住</b>：{@code ClassDiffUtil.isInternalMarkerField}
 * 会对称过滤 {@code ACC_SYNTHETIC} 字段，所以 {@code InitFix} 永远看不到 {@code val$*}、
 * {@code this$0} —— 它们不可能被字段初始化补丁覆盖。详见
 * {@code docs/INIT_FIX.md} §5.3。</p>
 *
 * <p><b>已知覆盖缺口</b>：局部类（{@code Foo$1Local}）不进对齐器 ——
 * {@link AnonClassAligner#isAnonymousClassName} 要求 {@code $} 后的后缀只含数字与 {@code $}，
 * 而局部类名带字母，判定为 false。因此本门在 Tier 4 只覆盖匿名类。
 * 局部类需要在重定义层另行接入（本项目实测有 5 个局部类）。</p>
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
		List<FieldInfo> out = new ArrayList<>();
		if (fields == null) return out;
		for (FieldNode f : fields) {
			out.add(new FieldInfo(f.name, f.desc,
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
		List<String> typeChanged = new ArrayList<>();
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
		List<String> addedPlain = new ArrayList<>();
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
		if (!removed.isEmpty())   detail.append("; removed field (harmless): ").append(removed);
		return new Result(Verdict.COMPATIBLE, detail.toString());
	}

	/** 命名常量：默认模式。{@code warn}/{@code off} 见 {@code HotSwapAgent.ANON_LAYOUT_GATE}。 */
	public static final String MODE_REJECT = "reject";
	public static final String MODE_WARN   = "warn";
	public static final String MODE_OFF    = "off";
}
