import nipx.AnnotationTransformer;
import nipx.ClassDiffUtil;
import nipx.HotSwapAgent;
import nipx.InstanceTracker;
import nipx.Reflect;
import nipx.ref.InitFix;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * InitFix 差分 Oracle（{@code docs/INIT_FIX.md} §8 P0-5）。
 *
 * <h2>为什么是"差分"</h2>
 * <p>每个场景编译**同一类的两个版本**（v1 旧 / v2 新），对两者做 {@link ClassDiffUtil#diff}，
 * 再把 v2 喂给 {@link InitFix#transform}，最后读 {@link InitFix#getLastReport} 做两类断言：</p>
 * <ul>
 *   <li><b>正向值比对</b>：控制组实例由 v2 构造器亲自算出目标值；被测实例的该字段先被
 *       {@code Unsafe} 清零成"存量实例"形态，再走 {@link InitFix#afterRedefine} 打补丁，
 *       最后要求两者的字段值<b>逐位相等</b>。这条链走的是与生产完全相同的公开 API
 *       （{@code InstanceTracker.register} → {@code transform} → {@code afterRedefine} →
 *       hidden class 的 {@code initInstance}），因此不需要真的 redefine 就能验证
 *       "补丁算出来的值 == 构造器本来会算出的值"。</li>
 *   <li><b>负向阻断断言</b>：危险表达式必须落到 {@code REJECTED}，且报告里的原因要指向
 *       <b>真实根因</b>（而不是笼统的"no safe initialization expression"）；
 *       T0 零值等价的字段必须是 {@code NOTHING_TO_PATCH} 且不产生补丁；
 *       条件 CAS 不得覆盖补丁前已被赋成非默认值的字段。</li>
 * </ul>
 *
 * <p>夹具用 {@code javax.tools} 在内存里编译，不依赖仓库外部的 javac 路径；
 * 断言失败 → 非零退出码（与 {@code SemAssert} 同一约定）。</p>
 */
public class InitFixOracle {

	static int passed = 0;
	static int failed = 0;

	/** 被静音掉的 agent 日志里的 error/warn 计数，失败时打印出来便于定位。 */
	static final List<String> agentErrors = new ArrayList<>();
	static final List<String> agentWarnings = new ArrayList<>();

	static void check(boolean ok, String msg) {
		System.out.println((ok ? "   PASS  " : "   FAIL  ") + msg);
		if (ok) passed++;
		else failed++;
	}

	interface Scenario {
		void run() throws Exception;
	}

	static void scenario(String title, Scenario body) {
		System.out.println("--- " + title + " ---");
		try {
			body.run();
		} catch (Throwable t) {
			check(false, title + " 抛出异常：" + t);
			t.printStackTrace(System.err);
		}
	}

	public static void main(String[] args) {
		// InitFix.transform 的第一道开关；顺手把 agent 日志静音，只收集 error。
		System.setProperty("nipx.agent.hotswap_plus", "true");
		HotSwapAgent.logger = new HotSwapAgent.Logger() {
			public void log(String msg) { }
			public void info(String msg) { }
			public void warn(String msg) { agentWarnings.add(msg); }
			public void error(String msg) { agentErrors.add(msg); }
			public void error(String msg, Throwable t) { agentErrors.add(msg + " :: " + t); }
		};
		HotSwapAgent.initConfig();

		scenario("§7.1 案例 A：Kotlin final 参数回溯（正向值比对 + 条件 CAS 不覆盖已赋值）", InitFixOracle::caseA);
		scenario("§4.3 条件 A/B 均不满足：包级私有源字段 → 阻断", InitFixOracle::caseNonPrivateSource);
		scenario("§7.1 案例 B：private 源字段被 setter 二次写入 → 阻断", InitFixOracle::caseSetterSecondWrite);
		scenario("§4.2 bit 6：非确定性调用（System.currentTimeMillis）→ 阻断", InitFixOracle::caseNonDeterministic);
		scenario("§4.2 bit 6：文件 I/O 调用 → 阻断", InitFixOracle::caseIoCall);
		scenario("§4.1 T0 零值等价：null / 0 / 未赋值 → NOTHING_TO_PATCH；-0.0f 按位非零 → 仍需补丁", InitFixOracle::caseZeroEquivalent);
		scenario("§4.4 多根构造器共识：一致放行 / 分歧阻断 / 覆盖不全阻断", InitFixOracle::caseMultiRootConsensus);
		scenario("§8 P0-2 误杀白名单：Logger 工厂新增静态字段 → 放行", InitFixOracle::caseLoggerWhitelist);
		scenario("§4.3 Nest 成员二次写入：内部类改源字段 → 阻断", InitFixOracle::caseNestSecondWrite);
		scenario("§5.3 ClassDiff 合成字段过滤：$nipx$ 标记不入 added*Fields，业务字段仍检出", InitFixOracle::caseInternalMarkerFilter);
		scenario("§4.2 bit 4：ThreadLocal 新增字段可补；读取 ThreadLocal.get() 的切片 → 阻断", InitFixOracle::caseThreadLocal);
		scenario("§4.2 bit 4/5：可复用缓存字段 + setLength(0) → 阻断；切片内 NEW 的 builder → 放行", InitFixOracle::caseReusableBuilderCache);
		scenario("§3.2 边界：构造器里的独立语句不进切片（REJECT 边界在哪）", InitFixOracle::caseStatementOutsideSlice);
		scenario("§1.1 @HotswapReinit：解锁构造器读取 / 强制覆写 / CONDITIONAL 不覆写 / T0 绕过", InitFixOracle::caseHotswapReinit);
		scenario("§5.1/§5.2 逐字段驱动：单字段失败不牵连后续字段，依赖失败显式跳过", InitFixOracle::casePerFieldDriver);
		scenario("§3.2 静态依赖失败必须传播到实例字段：连带跳过并记账", InitFixOracle::caseStaticFailurePropagatesToInstance);
		scenario("§5.2 失败跳过按实例隔离：单个实例失败不牵连其它实例", InitFixOracle::casePerInstanceSkipIsolation);
		scenario("§5.2 确定性失败熔断：N 个实例全失败时日志与重试封顶", InitFixOracle::caseFailureCircuitBreaker);
		scenario("§3.4 TTL 清扫丢弃待补补丁：字段必须写回台账而非永久遗忘", InitFixOracle::caseTtlSweepRecordsLedger);
		scenario("§3.5 成环后依赖方必须重判：静态环与实例环两侧都要连带拒绝", InitFixOracle::caseCycleReevaluatesDependents);
		scenario("§3.5 对照：静态无环时读它的实例字段仍被接受", InitFixOracle::caseAcyclicStaticStillAccepted);
		scenario("§3.5 静态环拒绝后跨组漏网：读环成员的实例字段必须被连带拒绝", InitFixOracle::caseStaticCycleLeaksInstanceDependent);
		scenario("§3.5 成环拒绝收窄：与环无关的字段不应被连带拒绝", InitFixOracle::caseCycleRefusalIsNarrow);
		scenario("§5.3 float/double 条件 CAS：已存在的值（含 -0.0f）不得被覆盖", InitFixOracle::caseFloatDoubleConditionalCas);
		scenario("§4.1+§4.3 T0 零值字段可被依赖：读零值新增字段的切片必须放行", InitFixOracle::caseZeroValueDependency);
		scenario("§4.1 静态同款：读零值静态字段的 <clinit> 切片必须放行", InitFixOracle::caseZeroValueStaticDependency);
		scenario("§3.4 拒绝告警出口：提取期与闭包期的 REJECTED 都必须发 warn", InitFixOracle::caseRejectionWarnings);
		scenario("§3.4 FieldLedger：被拒字段记入台账，下一轮无新增字段时仍被重试", InitFixOracle::caseFieldLedger);

		System.out.println();
		System.out.println("通过 " + passed + " 条；失败 " + failed + " 条；合计 " + (passed + failed) + " 条");
		if (!agentWarnings.isEmpty()) {
			System.out.println("（agent 侧 warn，共 " + agentWarnings.size() + " 条）");
			for (String w : agentWarnings) System.out.println("      WARN  " + w);
		}
		if (failed != 0 && !agentErrors.isEmpty()) {
			System.out.println("（agent 侧 error 记录，共 " + agentErrors.size() + " 条）");
			for (String e : agentErrors) System.out.println("      " + e);
		}
		System.out.println(failed == 0 ? "ALL ASSERTIONS PASSED" : (failed + " ASSERTION(S) FAILED"));
		if (failed != 0) System.exit(1);
	}

	// ==================== 场景 1：§7.1 案例 A ====================

	static final String CASE_A_V1 = """
		package oracle;
		public class CaseA {
			private final String raw;
			public CaseA(String raw) { this.raw = raw; }
			public String raw() { return raw; }
		}
		""";

	static final String CASE_A_V2 = """
		package oracle;
		public class CaseA {
			private final String raw;
			private String clean;
			public CaseA(String raw) { this.raw = raw; this.clean = raw.trim(); }
			public String raw() { return raw; }
			public String clean() { return clean; }
		}
		""";

	static void caseA() throws Exception {
		Fixture fx = loadFixture("oracle.CaseA", CASE_A_V1, CASE_A_V2);

		Object control  = construct(fx.host, "  hello  ");   // 构造器亲自算出的值
		Object subject  = construct(fx.host, "  hello  ");   // 待补丁：clean 先清成 null
		Object occupied = construct(fx.host, "  hello  ");   // 补丁前已被赋值：CAS 必须跳过

		resetToDefault(fx.host, subject, "clean", "Ljava/lang/String;");
		setField(fx.host, occupied, "clean", "SENTINEL");

		InstanceTracker.register(subject);
		InstanceTracker.register(occupied);

		InitFix.PatchReport report = fx.transform();
		expect(report, false, "clean", InitFix.FieldStatus.ACCEPTED, null);
		check(report.patchGenerated(), "CaseA：确实生成了补丁（patchGenerated）");

		fx.apply();

		expectValue(fx, subject, control, "clean");
		check("SENTINEL".equals(read(fx.host, occupied, "clean")),
			"CaseA：条件 CAS 未覆盖补丁前已赋的非默认值（期望 SENTINEL，实际 "
			+ read(fx.host, occupied, "clean") + "）");
	}

	// ==================== 场景 2：§4.3 条件 A/B 均不满足 ====================

	static final String CASE_B_V1 = """
		package oracle;
		public class CaseB {
			String raw;
			public CaseB(String raw) { this.raw = raw; }
		}
		""";

	static final String CASE_B_V2 = """
		package oracle;
		public class CaseB {
			String raw;
			private String clean;
			public CaseB(String raw) { this.raw = raw; this.clean = raw.trim(); }
		}
		""";

	static void caseNonPrivateSource() throws Exception {
		Fixture fx = loadFixture("oracle.CaseB", CASE_B_V1, CASE_B_V2);

		InitFix.PatchReport report = fx.transform();
		expect(report, false, "clean", InitFix.FieldStatus.REJECTED, "not final and not private");
		check(!report.patchGenerated(), "CaseB：未生成任何补丁（参数回溯被 §4.3 拒掉）");
	}

	// ==================== 场景 3：§7.1 案例 B（setter 二次写入） ====================

	static final String CASE_C_V1 = """
		package oracle;
		public class CaseC {
			private String tag;
			public CaseC(String tag) { this.tag = tag; }
			public void setTag(String t) { this.tag = t; }
		}
		""";

	static final String CASE_C_V2 = """
		package oracle;
		public class CaseC {
			private String tag;
			private String cleanTag;
			public CaseC(String tag) { this.tag = tag; this.cleanTag = tag.trim(); }
			public void setTag(String t) { this.tag = t; }
		}
		""";

	static void caseSetterSecondWrite() throws Exception {
		Fixture fx = loadFixture("oracle.CaseC", CASE_C_V1, CASE_C_V2);

		InitFix.PatchReport report = fx.transform();
		expect(report, false, "cleanTag", InitFix.FieldStatus.REJECTED, "written again at oracle/CaseC.setTag");
		check(!report.patchGenerated(), "CaseC：未生成任何补丁（源字段被 setter 改过）");
	}

	// ==================== 场景 4/5：§4.2 bit 6 非确定性与 IO ====================

	static final String CASE_D_V1 = """
		package oracle;
		public class CaseD {
			public CaseD() { }
		}
		""";

	static final String CASE_D_V2 = """
		package oracle;
		public class CaseD {
			private long stamp;
			public CaseD() { this.stamp = System.currentTimeMillis(); }
			public long stamp() { return stamp; }
		}
		""";

	static void caseNonDeterministic() throws Exception {
		Fixture fx = loadFixture("oracle.CaseD", CASE_D_V1, CASE_D_V2);

		InitFix.PatchReport report = fx.transform();
		expect(report, false, "stamp", InitFix.FieldStatus.REJECTED, "non-deterministic");
		check(!report.patchGenerated(), "CaseD：未生成任何补丁");
	}

	static final String CASE_E_V2 = """
		package oracle;
		public class CaseE {
			private final String name;
			private String path;
			public CaseE(String name) {
				this.name = name;
				this.path = new java.io.File(this.name).getAbsolutePath();
			}
			public String path() { return path; }
		}
		""";

	static void caseIoCall() throws Exception {
		Fixture fx = loadFixture("oracle.CaseE",
			"package oracle;\npublic class CaseE { public CaseE(String name) { } }\n", CASE_E_V2);

		InitFix.PatchReport report = fx.transform();
		expect(report, false, "path", InitFix.FieldStatus.REJECTED, "I/O call");
		check(!report.patchGenerated(), "CaseE：未生成任何补丁");
	}

	// ==================== 场景 6：§4.1 T0 零值等价 ====================

	static final String CASE_F_V1 = """
		package oracle;
		public class CaseF {
			public CaseF(String init) { }
		}
		""";

	static final String CASE_F_V2 = """
		package oracle;
		public class CaseF {
			private String  a;
			private int     b;
			private String  c;
			private float   negZero;
			private String  init;
			public CaseF(String init) {
				this.init = init;
				this.a = null;
				this.b = 0;
				this.negZero = -0.0f;
			}
			public float negZero() { return negZero; }
		}
		""";

	static void caseZeroEquivalent() throws Exception {
		Fixture fx = loadFixture("oracle.CaseF", CASE_F_V1, CASE_F_V2);

		Object control = construct(fx.host, "x");
		Object subject = construct(fx.host, "x");
		resetToDefault(fx.host, subject, "negZero", "F");
		InstanceTracker.register(subject);

		InitFix.PatchReport report = fx.transform();

		expect(report, false, "a", InitFix.FieldStatus.NOTHING_TO_PATCH, null);
		expect(report, false, "b", InitFix.FieldStatus.NOTHING_TO_PATCH, null);
		expect(report, false, "c", InitFix.FieldStatus.NOTHING_TO_PATCH, null);
		expect(report, false, "init", InitFix.FieldStatus.REJECTED, "self-assignment");
		expect(report, false, "negZero", InitFix.FieldStatus.ACCEPTED, null);
		check(report.patchGenerated(), "CaseF：仅 negZero 需要补丁，patchGenerated=true");

		fx.apply();

		expectValue(fx, subject, control, "negZero");
		check(Float.floatToRawIntBits((Float) read(fx.host, subject, "negZero"))
		      == Float.floatToRawIntBits(-0.0f),
			"CaseF：-0.0f 没被当成零值等价吞掉（补丁后位模式 = 0x80000000）");
	}

	// ==================== 场景 7：§4.4 多根构造器共识 ====================

	static final String CASE_G1_V1 = """
		package oracle;
		public class CaseG1 {
			private final String id;
			public CaseG1(String id, String extra) { this.id = id; }
			public CaseG1(String id) { this.id = id; }
		}
		""";

	static final String CASE_G1_V2 = """
		package oracle;
		public class CaseG1 {
			private final String id;
			private String token;
			public CaseG1(String id, String extra) { this.id = id; this.token = id.trim(); }
			public CaseG1(String id) { this.id = id; this.token = id.trim(); }
			public String token() { return token; }
		}
		""";

	static final String CASE_G2_V2 = """
		package oracle;
		public class CaseG2 {
			private final String id;
			private String token;
			public CaseG2(String id, String extra) { this.id = id; this.token = id.trim(); }
			public CaseG2(String id) { this.id = id; this.token = "DEFAULT"; }
		}
		""";

	static final String CASE_G3_V2 = """
		package oracle;
		public class CaseG3 {
			private final String id;
			private String token;
			public CaseG3(String id, String extra) { this.id = id; this.token = id.trim(); }
			public CaseG3(String id) { this.id = id; }
		}
		""";

	static void caseMultiRootConsensus() throws Exception {
		// （a）两个根构造器的表达式完全同构 → 放行，且补丁值 == 构造器值
		Fixture fx = loadFixture("oracle.CaseG1", CASE_G1_V1, CASE_G1_V2);
		Object control = construct(fx.host, new Class<?>[]{String.class, String.class}, "  id  ", "x");
		Object subject = construct(fx.host, new Class<?>[]{String.class, String.class}, "  id  ", "x");
		resetToDefault(fx.host, subject, "token", "Ljava/lang/String;");
		InstanceTracker.register(subject);

		InitFix.PatchReport report = fx.transform();
		expect(report, false, "token", InitFix.FieldStatus.ACCEPTED, null);
		fx.apply();
		expectValue(fx, subject, control, "token");

		// （b）两个根构造器意图分歧 → 阻断
		Fixture fx2 = loadFixture("oracle.CaseG2",
			CASE_G1_V1.replace("CaseG1", "CaseG2"), CASE_G2_V2);
		InitFix.PatchReport report2 = fx2.transform();
		expect(report2, false, "token", InitFix.FieldStatus.REJECTED,
			"different initialization expressions across constructors");
		check(!report2.patchGenerated(), "CaseG2：未生成任何补丁");

		// （c）某个根构造器根本没初始化该字段 → 阻断
		Fixture fx3 = loadFixture("oracle.CaseG3",
			CASE_G1_V1.replace("CaseG1", "CaseG3"), CASE_G3_V2);
		InitFix.PatchReport report3 = fx3.transform();
		expect(report3, false, "token", InitFix.FieldStatus.REJECTED,
			"only initialized in 1 of 2 root constructors");
		check(!report3.patchGenerated(), "CaseG3：未生成任何补丁");
	}

	// ==================== 场景 8：§8 P0-2 Logger 误杀白名单 ====================

	static final String CASE_H_V1 = """
		package oracle;
		public class CaseH {
			public CaseH(String name) { }
		}
		""";

	static final String CASE_H_V2 = """
		package oracle;
		public class CaseH {
			private static final java.util.logging.Logger LOG =
				java.util.logging.Logger.getLogger("oracle");
			private final String name;
			public CaseH(String name) { this.name = name; }
		}
		""";

	static void caseLoggerWhitelist() throws Exception {
		Fixture fx = loadFixture("oracle.CaseH", CASE_H_V1, CASE_H_V2);

		InitFix.PatchReport report = fx.transform();
		expect(report, true, "LOG", InitFix.FieldStatus.ACCEPTED, null);
		expect(report, false, "name", InitFix.FieldStatus.REJECTED, "self-assignment");
		check(report.patchGenerated(), "CaseH：Logger 静态字段确实进了补丁");
		fx.apply();
	}

	// ==================== 场景 9：§4.3 Nest 成员二次写入 ====================

	static final String CASE_I_V1 = """
		package oracle;
		public class CaseI {
			private String raw;
			public CaseI(String raw) { this.raw = raw; }
		}
		""";

	static final String CASE_I_V2 = """
		package oracle;
		public class CaseI {
			private String raw;
			private String clean;
			public CaseI(String raw) { this.raw = raw; this.clean = raw.trim(); }
			public static class Helper {
				public static void poke(CaseI target, String v) { target.raw = v; }
			}
		}
		""";

	static void caseNestSecondWrite() throws Exception {
		Fixture fx = loadFixture("oracle.CaseI", CASE_I_V1, CASE_I_V2);

		InitFix.PatchReport report = fx.transform();
		expect(report, false, "clean", InitFix.FieldStatus.REJECTED,
			"written again at oracle/CaseI$Helper.poke");
		check(!report.patchGenerated(), "CaseI：未生成任何补丁（Nest 成员改过源字段）");
	}

	// ==================== 场景 10：§5.3 ClassDiff 合成字段过滤 ====================

	static final String CASE_J_V1 = """
		package oracle;
		public class CaseJ {
			private int count;
			private final Runnable task = () -> System.out.println(count);
			public void run() { task.run(); }
		}
		""";

	static final String CASE_J_V2 = """
		package oracle;
		public class CaseJ {
			private int count;
			private final Runnable task = () -> System.out.println(count);
			private String extra;
			public void run() { task.run(); }
			public String extra() { return extra; }
		}
		""";

	static void caseInternalMarkerFilter() throws Exception {
		String dot = "oracle.CaseJ";
		Map<String, byte[]> v1 = compile(Map.of(dot, CASE_J_V1));
		Map<String, byte[]> v2 = compile(Map.of(dot, CASE_J_V2));

		byte[] forced = AnnotationTransformer.forceStaticLambdas(
			v2.get(dot), dot.replace('.', '/'), new ByteLoader(v2));

		check(hasField(forced, "$nipx$lambdasForced"),
			"前置条件：forceStaticLambdas 确实往新字节码里加了 $nipx$ 标记字段");

		ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(v1.get(dot), forced);

		check(!diff.addedStaticFields.contains("$nipx$lambdasForced")
		      && !diff.addedInstanceFields.contains("$nipx$lambdasForced"),
			"负向：$nipx$ 标记字段没有进 added*Fields（否则 InitFix 会给它做补丁）");
		boolean leakedMarker = false;
		for (String f : diff.changedFields) {
			if (f.contains("$nipx$")) leakedMarker = true;
		}
		check(!leakedMarker, "负向：$nipx$ 也没进 changedFields");
		check(diff.addedInstanceFields.contains("extra"),
			"正向：业务新增字段 extra 仍然被检出（过滤没有误杀），实际 addedInstanceFields="
			+ diff.addedInstanceFields);
	}

	// ==================== 场景 11：§4.2 bit 4 ThreadLocal ====================

	static final String CASE_K_V1 = """
		package oracle;
		public class CaseK {
			public CaseK(String seed) { }
		}
		""";

	static final String CASE_K_V2 = """
		package oracle;
		public class CaseK {
			private ThreadLocal<StringBuilder> buf = ThreadLocal.withInitial(StringBuilder::new);
			public CaseK(String seed) { }
			public StringBuilder buf() { return buf.get(); }
		}
		""";

	static final String CASE_L_V1 = """
		package oracle;
		public class CaseL {
			private final ThreadLocal<StringBuilder> tl = ThreadLocal.withInitial(StringBuilder::new);
			public CaseL(String seed) { tl.get().append(seed); }
		}
		""";

	static final String CASE_L_V2 = """
		package oracle;
		public class CaseL {
			private final ThreadLocal<StringBuilder> tl = ThreadLocal.withInitial(StringBuilder::new);
			private String snapshot;
			public CaseL(String seed) { tl.get().append(seed); this.snapshot = tl.get().toString(); }
		}
		""";

	static void caseThreadLocal() throws Exception {
		// ---- 正向：withInitial 只是分配，不读线程状态，作为新增字段初始化式必须放行 ----
		Fixture fk = loadFixture("oracle.CaseK", CASE_K_V1, CASE_K_V2);
		Object ck = construct(fk.host, "seed");
		Object sk = construct(fk.host, "seed");
		resetToDefault(fk.host, sk, "buf", "Ljava/lang/ThreadLocal;");
		InstanceTracker.register(sk);

		InitFix.PatchReport rk = fk.transform();
		expect(rk, false, "buf", InitFix.FieldStatus.ACCEPTED, null);
		check(rk.patchGenerated(), "CaseK：ThreadLocal 新增字段确实进了补丁");
		fk.apply();

		check(read(fk.host, sk, "buf") instanceof ThreadLocal,
			"CaseK：补丁后 buf 是可用的 ThreadLocal（实际 " + read(fk.host, sk, "buf") + "）");
		StringBuilder gotK  = (StringBuilder) fk.host.getMethod("buf").invoke(sk);
		StringBuilder wantK = (StringBuilder) fk.host.getMethod("buf").invoke(ck);
		check(wantK.toString().equals(gotK.toString()),
			"CaseK：线程副本行为与正常构造实例一致（构造器=\"" + wantK + "\"，补丁=\"" + gotK + "\"）");

		// ---- 负向：切片读取 ThreadLocal.get()，补丁线程 != 构造线程 → 必须阻断 ----
		Fixture fl = loadFixture("oracle.CaseL", CASE_L_V1, CASE_L_V2);
		Object cl = onOtherThread(() -> construct(fl.host, "abc"));   // 线程 A 构造
		Object sl = onOtherThread(() -> construct(fl.host, "abc"));   // 线程 A 构造
		resetToDefault(fl.host, sl, "snapshot", "Ljava/lang/String;");
		InstanceTracker.register(sl);

		check("abc".equals(read(fl.host, cl, "snapshot")),
			"CaseL 前置条件：构造线程上的 snapshot == \"abc\"（实际 "
			+ describe(read(fl.host, cl, "snapshot")) + "）——补丁若在别的线程重算就会拿到空副本");

		InitFix.PatchReport rl = fl.transform();
		expect(rl, false, "snapshot", InitFix.FieldStatus.REJECTED, "thread-local heap state");
		check(!rl.patchGenerated(), "CaseL：线程局部读取不再生成任何补丁");
		fl.apply();
		check(read(fl.host, sl, "snapshot") == null,
			"CaseL：存量实例的 snapshot 保持默认值，未被线程相关的重算污染（实际 "
			+ describe(read(fl.host, sl, "snapshot")) + "）");
	}

	interface ThrowingSupplier {
		Object get() throws Exception;
	}

	/** 在另一条线程上构造实例，用来制造"构造线程 != 补丁线程"，这是生产环境的常态。 */
	static Object onOtherThread(ThrowingSupplier body) throws Exception {
		java.util.concurrent.atomic.AtomicReference<Object> ref = new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.atomic.AtomicReference<Throwable> err = new java.util.concurrent.atomic.AtomicReference<>();
		Thread t = new Thread(() -> {
			try {
				ref.set(body.get());
			} catch (Throwable e) {
				err.set(e);
			}
		}, "oracle-ctor");
		t.start();
		t.join();
		if (err.get() != null) throw new IllegalStateException(err.get());
		return ref.get();
	}

	// ==================== 场景 12：§4.2 bit 4/5 可复用 builder 缓存 ====================

	static final String CASE_Q_V1 = """
		package oracle;
		public class CaseQ {
			private static final StringBuilder BUF = new StringBuilder(64);
			private final String name;
			public CaseQ(String name) { this.name = name; BUF.setLength(0); BUF.append(this.name); }
		}
		""";

	static final String CASE_Q_V2 = """
		package oracle;
		public class CaseQ {
			private static final StringBuilder BUF = new StringBuilder(64);
			private final String name;
			private String key;
			public CaseQ(String name) {
				this.name = name;
				BUF.setLength(0);
				this.key = BUF.append(this.name).toString();
			}
		}
		""";

	static final String CASE_R_V1 = """
		package oracle;
		public class CaseR {
			private final StringBuilder buf = new StringBuilder(64);
			private final String name;
			public CaseR(String name) { this.name = name; this.buf.setLength(0); this.buf.append(this.name); }
		}
		""";

	static final String CASE_R_V2 = """
		package oracle;
		public class CaseR {
			private final StringBuilder buf = new StringBuilder(64);
			private final String name;
			private String key;
			public CaseR(String name) {
				this.name = name;
				this.buf.setLength(0);
				this.key = this.buf.append(this.name).toString();
			}
		}
		""";

	/** 正向对照：builder 在切片内 NEW 出来、从未逃逸 → §4.2 的 ALLOC_PURE 必须继续放行。 */
	static final String CASE_S_V1 = """
		package oracle;
		public class CaseS {
			public CaseS() { }
		}
		""";

	static final String CASE_S_V2 = """
		package oracle;
		public class CaseS {
			private String key;
			public CaseS() { this.key = new StringBuilder().append("a").append(42).toString(); }
		}
		""";

	static void caseReusableBuilderCache() throws Exception {
		// ---- 负向一：静态可复用缓存，值来自字段（入参已持久化） ----
		Fixture fq = loadFixture("oracle.CaseQ", CASE_Q_V1, CASE_Q_V2);
		Object cq = construct(fq.host, "abc");
		Object sq = construct(fq.host, "abc");
		resetToDefault(fq.host, sq, "key", "Ljava/lang/String;");
		InstanceTracker.register(sq);

		InitFix.PatchReport rq = fq.transform();
		expect(rq, false, "key", InitFix.FieldStatus.REJECTED, "mutates a reusable java/lang/StringBuilder");
		check(!rq.patchGenerated(), "CaseQ：不再为「读可复用缓存」的切片生成补丁");
		fq.apply();
		// 拒绝的语义是"宁可不补"：存量实例保持默认值（构造器本会算出 'abc'，但不该在这里猜）
		check(read(fq.host, sq, "key") == null,
			"CaseQ：key 保持默认值，没有被累积成 'abcabc'（实际 "
			+ describe(read(fq.host, sq, "key")) + "）");
		check("abc".equals(String.valueOf(read(fq.host, cq, "BUF"))),
			"CaseQ：共享缓存内容未被补丁污染（期望 abc，实际 "
			+ read(fq.host, cq, "BUF") + "）");

		// ---- 负向二：实例可复用缓存，值来自字段 ----
		Fixture fr = loadFixture("oracle.CaseR", CASE_R_V1, CASE_R_V2);
		Object cr = construct(fr.host, "abc");
		Object sr = construct(fr.host, "abc");
		resetToDefault(fr.host, sr, "key", "Ljava/lang/String;");
		InstanceTracker.register(sr);

		InitFix.PatchReport rr = fr.transform();
		expect(rr, false, "key", InitFix.FieldStatus.REJECTED, "mutates a reusable java/lang/StringBuilder");
		check(!rr.patchGenerated(), "CaseR：不再为「读实例缓存」的切片生成补丁");
		fr.apply();
		check("abc".equals(String.valueOf(read(fr.host, sr, "buf"))),
			"CaseR：被补丁实例的 buf 未被污染（期望 abc，实际 " + read(fr.host, sr, "buf") + "）");
		check(read(fr.host, sr, "key") == null,
			"CaseR：key 保持默认值，没有被累积成 'abcabc'（实际 "
			+ describe(read(fr.host, sr, "key")) + "）");

		// ---- 正向对照：局部 builder 链式调用照旧放行并算出正确值 ----
		Fixture fs = loadFixture("oracle.CaseS", CASE_S_V1, CASE_S_V2);
		Object cs = construct(fs.host);
		Object ss = construct(fs.host);
		resetToDefault(fs.host, ss, "key", "Ljava/lang/String;");
		InstanceTracker.register(ss);

		InitFix.PatchReport rs = fs.transform();
		expect(rs, false, "key", InitFix.FieldStatus.ACCEPTED, null);
		fs.apply();
		expectValue(fs, ss, cs, "key");
		check("a42".equals(String.valueOf(read(fs.host, ss, "key"))),
			"CaseS：切片内 NEW 的 builder 链式调用正确算出 'a42'（实际 "
			+ describe(read(fs.host, ss, "key")) + "）");
	}

	// ==================== 场景 13：构造器里的独立语句不进切片 ====================

	static final String CASE_T1_V1 = """
		package oracle;
		public class CaseT1 {
			public CaseT1() { }
		}
		""";

	static final String CASE_T1_V2 = """
		package oracle;
		public class CaseT1 {
			private ThreadLocal<StringBuilder> sb = ThreadLocal.withInitial(StringBuilder::new);
			public CaseT1() { sb.get().append("aass"); }
			public StringBuilder view() { return sb.get(); }
		}
		""";

	static final String CASE_T2_V2 = """
		package oracle;
		public class CaseT2 {
			private ThreadLocal<StringBuilder> sb = ThreadLocal.withInitial(StringBuilder::new);
			private String key;
			public CaseT2() {
				sb.get().append("aass");
				this.key = sb.get().toString();
			}
		}
		""";

	static final String CASE_T3_V1 = """
		package oracle;
		public class CaseT3 {
			public CaseT3() { }
		}
		""";

	static final String CASE_T3_V2 = """
		package oracle;
		public class CaseT3 {
			private final StringBuilder sb = new StringBuilder();
			public CaseT3() { sb.append("aass"); }
		}
		""";

	/** T3 的注解版：用于验证"非按线程类型也要告警、但文案不误报按线程"。 */
	static final String CASE_T3R_V2 = """
		package oracle;
		public class CaseT3 {
			@nipx.annotation.HotswapReinit
			private final StringBuilder sb = new StringBuilder();
			public CaseT3() { sb.append("aass"); }
		}
		""";

	static final String CASE_T4_V1 = """
		package oracle;
		public class CaseT4 {
			public CaseT4() { }
		}
		""";

	static final String CASE_T4_V2 = """
		package oracle;
		public class CaseT4 {
			private String s = "x";
			public CaseT4() { String t = s + "y"; if (t.isEmpty()) System.out.print(""); }
		}
		""";

	/**
	 * 直接回答"新增 ThreadLocal&lt;StringBuilder&gt; sb，构造器里 sb.get().append("aass"); 会不会 reject"：
	 * <b>会</b>，而且拦它的是既有的"后续加工检查"（{@code subsequentProcessingReason}），
	 * 不是我这两轮新加的效应/接收者规则。
	 *
	 * <p>原因：该检查扫描<b>整个构造器</b>（不只是切片），只要出现对新增字段的
	 * {@code GETFIELD} 而该读取不在提取片段内，且字段类型既非基本类型也不在不可变名单里，
	 * 就判 {@code field read outside any accepted extraction} 拒绝。判据背后的语义是：
	 * 补丁只重放字段初始化式、不会重放构造器里的语句，所以构造器一旦"用过"这个字段，
	 * 存量实例的状态就必然与正常构造的实例分叉 —— 宁可不补。</p>
	 *
	 * <p>代价（实测）：拒绝意味着存量实例的该字段保持 {@code null}，之后调用
	 * {@code sb.get()} 会 NPE。所以对 scratch 缓存，实践建议是<b>不要在构造器里碰它</b>
	 * ——只放在方法里用（见场景 11 的 CaseK，那种形态是 ACCEPTED 的）。</p>
	 *
	 * <p>对照组 CaseT4：同样的"构造器里读新增字段"，但字段类型是 {@code String}
	 * （不可变）→ 豁免放行。这条划清了边界的类型敏感性。</p>
	 */
	static void caseStatementOutsideSlice() throws Exception {
		// T1：ThreadLocal 新增字段 + 构造器里 sb.get().append(...)
		Fixture f1 = loadFixture("oracle.CaseT1", CASE_T1_V1, CASE_T1_V2);
		Object s1 = construct(f1.host);
		resetToDefault(f1.host, s1, "sb", "Ljava/lang/ThreadLocal;");
		InstanceTracker.register(s1);

		InitFix.PatchReport r1 = f1.transform();
		expect(r1, false, "sb", InitFix.FieldStatus.REJECTED, "thread-affine field Ljava/lang/ThreadLocal;");
		check(!r1.patchGenerated(), "CaseT1：构造器里碰过缓存 → 整个字段不再生成补丁");
		f1.apply();
		check(read(f1.host, s1, "sb") == null,
			"CaseT1：拒绝的代价 —— 存量实例的 sb 仍是 null（之后 sb.get() 会 NPE）");

		// T3：StringBuilder 新增字段 + 构造器里 sb.append(...)
		Fixture f3 = loadFixture("oracle.CaseT3", CASE_T3_V1, CASE_T3_V2);
		Object s3 = construct(f3.host);
		resetToDefault(f3.host, s3, "sb", "Ljava/lang/StringBuilder;");
		InstanceTracker.register(s3);

		InitFix.PatchReport r3 = f3.transform();
		expect(r3, false, "sb", InitFix.FieldStatus.REJECTED, "field read outside any accepted extraction");
		check(!r3.patchGenerated(), "CaseT3：同样因构造器里的读取被判拒绝");
		check("aass".contentEquals((StringBuilder) read(f3.host, construct(f3.host), "sb")),
			"CaseT3 前置条件：新实例的 sb == \"aass\"（构造器语句对新实例照常生效）");

		// T2：把缓存读取结果赋给另一个新增字段 → 走效应规则（thread-local）拒绝
		Fixture f2 = loadFixture("oracle.CaseT2",
			CASE_T1_V1.replace("CaseT1", "CaseT2"), CASE_T2_V2);
		InitFix.PatchReport r2 = f2.transform();
		expect(r2, false, "key", InitFix.FieldStatus.REJECTED, "thread-local heap state");
		check(!r2.patchGenerated(), "CaseT2：把缓存值写进新增字段同样不生成补丁");

		// T4：对照 —— 构造器里读新增字段，但类型是 String（不可变）→ 豁免放行
		Fixture f4 = loadFixture("oracle.CaseT4", CASE_T4_V1, CASE_T4_V2);
		InitFix.PatchReport r4 = f4.transform();
		expect(r4, false, "s", InitFix.FieldStatus.ACCEPTED, null);
		check(r4.patchGenerated(), "CaseT4：不可变类型的新增字段被构造器读取 → 仍然放行（边界是类型敏感的）");
	}

	// ==================== 场景 14：§1.1 @HotswapReinit ====================

	/** N1：新增 ThreadLocal 缓存 + 构造器里碰过它 —— 场景 13 的 T1 加注解后应当解锁。 */
	static final String CASE_N1_V1 = """
		package oracle;
		public class CaseN1 {
			public CaseN1() { }
		}
		""";

	static final String CASE_N1_V2 = """
		package oracle;
		public class CaseN1 {
			@nipx.annotation.HotswapReinit
			private ThreadLocal<StringBuilder> sb = ThreadLocal.withInitial(StringBuilder::new);
			public CaseN1() { sb.get().append("aass"); }
		}
		""";

	/** N2/N3/N5：既有字段改了初值（没有任何新增字段）。 */
	static final String CASE_N2_V1 = """
		package oracle;
		public class CaseN2 {
			private String tag = "old";
			public String tag() { return tag; }
		}
		""";

	static final String CASE_N2_V2 = """
		package oracle;
		public class CaseN2 {
			@nipx.annotation.HotswapReinit(mode = nipx.annotation.HotswapReinit.Mode.OVERWRITE)
			private String tag = "new";
			public String tag() { return tag; }
		}
		""";

	static final String CASE_N3_V2 = """
		package oracle;
		public class CaseN3 {
			@nipx.annotation.HotswapReinit(mode = nipx.annotation.HotswapReinit.Mode.CONDITIONAL)
			private String tag = "new";
			public String tag() { return tag; }
		}
		""";

	static final String CASE_N5_V2 = """
		package oracle;
		public class CaseN5 {
			private String tag = "new";
			public String tag() { return tag; }
		}
		""";

	/** N4：既有 int 字段改成零值 —— 注解必须绕过 T0，否则会判 NOTHING_TO_PATCH。 */
	static final String CASE_N4_V1 = """
		package oracle;
		public class CaseN4 {
			private int retries = 7;
			public int retries() { return retries; }
		}
		""";

	static final String CASE_N4_V2 = """
		package oracle;
		public class CaseN4 {
			@nipx.annotation.HotswapReinit
			private int retries = 0;
			public int retries() { return retries; }
		}
		""";

	static void caseHotswapReinit() throws Exception {
		// ---- N1：注解解锁"新增字段 + 构造器里碰过它" ----
		Fixture f1 = loadFixture("oracle.CaseN1", CASE_N1_V1, CASE_N1_V2);
		Object s1 = construct(f1.host);
		resetToDefault(f1.host, s1, "sb", "Ljava/lang/ThreadLocal;");
		InstanceTracker.register(s1);

		InitFix.PatchReport r1 = f1.transform();
		expect(r1, false, "sb", InitFix.FieldStatus.ACCEPTED, null);
		expectWarning(r1, false, "sb", "按线程的值");
		check(r1.patchGenerated(), "CaseN1：@HotswapReinit 解锁了构造器读取检查，补丁照常生成");
		check(agentWarnings.stream().anyMatch(w -> w.contains("CaseN1") && w.contains("热更线程")),
			"CaseN1：同时打了一条 warn 日志（实测 " + agentWarnings.size() + " 条 warn）");
		f1.apply();
		check(read(f1.host, s1, "sb") instanceof ThreadLocal,
			"CaseN1：存量实例拿到了可用的空缓存（实际 " + read(f1.host, s1, "sb") + "）");

		// ---- N2：既有字段 + OVERWRITE → 无条件覆写 ----
		Fixture f2 = loadFixture("oracle.CaseN2", CASE_N2_V1, CASE_N2_V2);
		Object s2 = construct(f2.host);
		setField(f2.host, s2, "tag", "old");        // 模拟"存量实例里还是旧值"
		InstanceTracker.register(s2);

		InitFix.PatchReport r2 = f2.transform();
		expect(r2, false, "tag", InitFix.FieldStatus.ACCEPTED, null);
		check(r2.patchGenerated(), "CaseN2：既有字段（无新增字段）也生成了补丁");
		f2.apply();
		check("new".equals(read(f2.host, s2, "tag")),
			"CaseN2：OVERWRITE 无条件覆写了存量值（期望 new，实际 "
			+ describe(read(f2.host, s2, "tag")) + "）");

		// ---- N3：既有字段 + CONDITIONAL → 非默认值不动 ----
		Fixture f3 = loadFixture("oracle.CaseN3",
			CASE_N2_V1.replace("CaseN2", "CaseN3"), CASE_N3_V2);
		Object s3 = construct(f3.host);
		setField(f3.host, s3, "tag", "old");
		InstanceTracker.register(s3);

		InitFix.PatchReport r3 = f3.transform();
		expect(r3, false, "tag", InitFix.FieldStatus.ACCEPTED, null);
		f3.apply();
		check("old".equals(read(f3.host, s3, "tag")),
			"CaseN3：CONDITIONAL 没覆盖已有非默认值（期望 old，实际 "
			+ describe(read(f3.host, s3, "tag")) + "）");

		// ---- N5：同样的改动但不标注解 → 不产生补丁（对照） ----
		Fixture f5 = loadFixture("oracle.CaseN5",
			CASE_N2_V1.replace("CaseN2", "CaseN5"), CASE_N5_V2);
		Object s5 = construct(f5.host);
		setField(f5.host, s5, "tag", "old");
		InstanceTracker.register(s5);

		InitFix.PatchReport r5 = f5.transform();
		check(!r5.patchGenerated() && r5.instanceFields().isEmpty(),
			"CaseN5 对照：没有注解时既有字段不进候选集（无决策、无补丁）");
		f5.apply();
		check("old".equals(read(f5.host, s5, "tag")),
			"CaseN5 对照：存量值保持 old（实际 " + describe(read(f5.host, s5, "tag")) + "）");

		// ---- N6：注解解锁的是"构造器用法未重放"这条风险，与类型无关；
		//          非按线程类型也应告警，但文案不应误报"按线程的值" ----
		Fixture f6 = loadFixture("oracle.CaseT3", CASE_T3_V1, CASE_T3R_V2);
		InitFix.PatchReport r6 = f6.transform();
		expect(r6, false, "sb", InitFix.FieldStatus.ACCEPTED, null);
		expectWarning(r6, false, "sb", "构造器里对它的这部分用法不会被重放");
		check(r6.instanceFields().get("sb").warnings().stream()
		      .noneMatch(w -> w.contains("按线程的值")),
			"CaseT3+注解：非按线程类型不应误报「按线程的值」");
		f6.apply();

		// ---- N4：既有字段改成零值 + 注解 → 必须绕过 T0 ----
		Fixture f4 = loadFixture("oracle.CaseN4", CASE_N4_V1, CASE_N4_V2);
		Object s4 = construct(f4.host);
		setField(f4.host, s4, "retries", 7);
		InstanceTracker.register(s4);

		InitFix.PatchReport r4 = f4.transform();
		expect(r4, false, "retries", InitFix.FieldStatus.ACCEPTED, null);
		f4.apply();
		check(Integer.valueOf(0).equals(read(f4.host, s4, "retries")),
			"CaseN4：显式重置为零值没有被 T0 吞掉（期望 0，实际 "
			+ describe(read(f4.host, s4, "retries")) + "）");
	}

	// ==================== 场景 15：§5.1/§5.2 逐字段驱动 ====================

	/**
	 * 三个新增字段，其中 {@code bad} 的切片在<b>补丁执行期</b>抛异常（由静态开关控制），
	 * {@code other} 正常，{@code derived} 依赖 {@code bad}。
	 *
	 * <p>这正是"单个方法与逐字段方法"的判别实验：旧实现把三个字段串在同一个静态直线方法里
	 * （顺序 bad → derived → other），{@code bad} 一抛异常，<b>后面的字段全部被跳过</b>；
	 * 逐字段驱动则只有 {@code bad} 与其下游 {@code derived} 失败，{@code other} 照常修补。</p>
	 *
	 * <p>开关放在 {@code pick()} 内部：构造期 {@code FAIL=false} 正常构造，
	 * 打补丁前置 true，于是异常只发生在补丁侧；而且 {@code pick()} 里的分支不在切片内，
	 * 不会破坏直线假设。</p>
	 */
	static final String CASE_U_V1 = """
		package oracle;
		public class CaseU {
			public  static boolean FAIL;
			public CaseU(String seed) { }
			public  static void failNow(boolean v) { FAIL = v; }
			private static String pick() { if (FAIL) throw new IllegalStateException("boom"); return "x"; }
		}
		""";

	static final String CASE_U_V2 = """
		package oracle;
		public class CaseU {
			public  static boolean FAIL;
			private String bad     = pick();
			private String derived = this.bad + "!";
			private String other   = "ok";
			public CaseU(String seed) { }
			public  static void failNow(boolean v) { FAIL = v; }
			private static String pick() { if (FAIL) throw new IllegalStateException("boom"); return "x"; }
		}
		""";

	static void casePerFieldDriver() throws Exception {
		Fixture fx = loadFixture("oracle.CaseU", CASE_U_V1, CASE_U_V2);
		Object subject = construct(fx.host, "seed");
		resetToDefault(fx.host, subject, "bad", "Ljava/lang/String;");
		resetToDefault(fx.host, subject, "derived", "Ljava/lang/String;");
		resetToDefault(fx.host, subject, "other", "Ljava/lang/String;");
		InstanceTracker.register(subject);

		InitFix.PatchReport report = fx.transform();
		// 三个字段都通过了静态审查（能提到安全切片），补丁因此生成
		expect(report, false, "bad", InitFix.FieldStatus.ACCEPTED, null);
		expect(report, false, "derived", InitFix.FieldStatus.ACCEPTED, null);
		expect(report, false, "other", InitFix.FieldStatus.ACCEPTED, null);
		check(report.patchGenerated(), "CaseU：三个字段都进了补丁计划");

		// 打补丁前置开关：bad 的切片将在补丁执行期抛异常
		fx.host.getMethod("failNow", boolean.class).invoke(null, true);

		fx.apply();

		// 核心断言：bad 抛异常，但 other 依然被修补 —— 这正是逐字段驱动的意义
		check(read(fx.host, subject, "bad") == null,
			"CaseU：bad 的补丁抛异常，字段保持默认值（实际 "
			+ describe(read(fx.host, subject, "bad")) + "）");
		check("ok".equals(read(fx.host, subject, "other")),
			"CaseU：bad 抛异常没有牵连 other（期望 ok，实际 "
			+ describe(read(fx.host, subject, "other")) + "）");
		check(read(fx.host, subject, "derived") == null,
			"CaseU：依赖 bad 的 derived 被显式跳过，未用默认值参与计算（实际 "
			+ describe(read(fx.host, subject, "derived")) + "）");
		check(agentWarnings.stream().anyMatch(w -> w.contains("CaseU.derived")
		      && w.contains("dependency failed")),
			"CaseU：依赖失败被显式告警（实际 warns=" + agentWarnings.size() + " 条）");
	}

	// ==================== 场景 16a：静态依赖失败必须传播到实例字段 ====================

	/** V1：原始版本（只有 FAIL 开关与构造器）。 */
	static final String CASE_Y_V1 = """
		package oracle;
		public class CaseY {
			public  static boolean FAIL;
			public CaseY(String seed) { }
			public  static void failNow(boolean v) { FAIL = v; }
			private static int pick() { if (FAIL) throw new IllegalStateException("boom"); return 7; }
		}
		""";

	/**
	 * V2：新增一个<b>静态</b>字段 {@code SBad}（切片执行期抛异常）与一个<b>实例</b>字段
	 * {@code IDerived}，后者读前者算值。
	 *
	 * <p>核心原则（§3.2）：切片不能依赖"未补上的值"。{@code SBad} 补失败后，
	 * {@code IDerived} 必须被<b>连带跳过并记账</b>；若照常执行，它读到的是
	 * {@code SBad} 的默认值 0，会静默写入过期值 —— 这就是待修的静默误补。</p>
	 *
	 * <p>{@code pick()} 里的分支不在切片内（切片只含 GETSTATIC + 算术 + PUTFIELD），
	 * 不破坏直线假设；开关在构造期是 false，打补丁前置 true，异常只发生在补丁侧。</p>
	 */
	static final String CASE_Y_V2 = """
		package oracle;
		public class CaseY {
			public  static boolean FAIL;
			private static int SBad    = pick();
			private int        IDerived = SBad + 1;
			public CaseY(String seed) { }
			public  static void failNow(boolean v) { FAIL = v; }
			private static int pick() { if (FAIL) throw new IllegalStateException("boom"); return 7; }
			public int iDerived() { return IDerived; }
		}
		""";

	static void caseStaticFailurePropagatesToInstance() throws Exception {
		int before = agentWarnings.size();

		Fixture fx = loadFixture("oracle.CaseY", CASE_Y_V1, CASE_Y_V2);

		// 构造存量实例、清零、登记 —— 必须在 transform 之前，
		// 否则实例快照拍不到它（alive=0，实例补丁整块被跳过）。
		Object subject = construct(fx.host, "seed");
		resetToDefault(fx.host, subject, "IDerived", "I");
		InstanceTracker.register(subject);

		// 两个字段都应通过静态审查：SBad 的切片是纯 pick() 调用，
		// IDerived 的切片是 GETSTATIC SBad + 常量 + PUTFIELD
		InitFix.PatchReport report = fx.transform();
		expect(report, true, "SBad", InitFix.FieldStatus.ACCEPTED, null);
		expect(report, false, "IDerived", InitFix.FieldStatus.ACCEPTED, null);

		// 打补丁前置开关：SBad 的静态切片将在执行期抛异常
		fx.host.getMethod("failNow", boolean.class).invoke(null, true);

		fx.apply();

		// 核心断言 1：SBad 补失败 → IDerived 必须被跳过，保持默认值 0
		Object got = read(fx.host, subject, "IDerived");
		check(Integer.valueOf(0).equals(got),
			"CaseY：静态字段补失败后，依赖它的实例字段必须被连带跳过"
			+ "（期望 0，实际 " + describe(got) + "）");

		// 核心断言 2：跳过必须被显式告警
		boolean warned = agentWarnings.stream()
		 .skip(before)
		 .anyMatch(w -> w.contains("CaseY") && w.contains("IDerived")
		                && w.contains("dependency failed"));
		check(warned,
			"CaseY：实例字段的连带跳过被显式告警（实际新增 "
			+ (agentWarnings.size() - before) + " 条 warn）");

		// 核心断言 3：两个字段都必须记入台账（下一轮才可能重试）
		Set<String> unpatched = unpatched(fx.host);
		check(unpatched.contains("SBad"),
			"CaseY：补失败的静态字段记入 FieldLedger（实际 " + unpatched + "）");
		check(unpatched.contains("IDerived"),
			"CaseY：被连带跳过的实例字段记入 FieldLedger（实际 " + unpatched + "）");
	}

	// ==================== 场景 16d：失败跳过必须按实例隔离 ====================

	/** V1：原始版本（只有 ARMED 开关与 seed 源字段）。 */
	static final String CASE_X_V1 = """
		package oracle;
		public class CaseX {
			public  static boolean ARMED;
			private final String seed;
			public CaseX(String seed) { this.seed = seed; }
			public String seed() { return seed; }
			public  static void arm(boolean v) { ARMED = v; }
		}
		""";

	/**
	 * V2：新增两个字段，{@code bad} 的切片读 <b>seed</b>（存量值因实例而异），
	 * {@code derived} 依赖 {@code bad}。
	 *
	 * <p>{@code seed} 是 private final，能通过 §4.3 的不可变证明，因此 {@code bad = seed}
	 * 是可提切片的。{@code pick} 里读<b>静态</b> {@code ARMED}：构造期它为 false（正常构造），
	 * 打补丁前置 true，且只有 seed 等于哨兵值的那个实例抛异常 —— 于是失败精确地
	 * 落在<b>单个实例</b>上。</p>
	 *
	 * <p>{@code ARMED} 声明为 <b>既有</b>字段（V1 里就有），因此不进本轮 Diff 的
	 * added*Fields，也就不会污染候选集与台账。</p>
	 */
	static final String CASE_X_V2 = """
		package oracle;
		public class CaseX {
			public  static boolean ARMED;
			private final String seed;
			private String bad;
			private String derived;
			public CaseX(String seed) { this.seed = seed; this.bad = pick(this.seed); this.derived = this.bad + "!"; }
			public String seed() { return seed; }
			public String bad() { return bad; }
			public String derived() { return derived; }
			public  static void arm(boolean v) { ARMED = v; }
			private static String pick(String s) {
				if (ARMED && "BOOM".equals(s)) throw new IllegalStateException("boom");
				return s;
			}
		}
		""";

	static void casePerInstanceSkipIsolation() throws Exception {
		Fixture fx = loadFixture("oracle.CaseX", CASE_X_V1, CASE_X_V2);

		// 三个存量实例：第 2 个的 seed 将导致 bad 的切片抛异常
		Object ok1 = construct(fx.host, "a");
		Object bad = construct(fx.host, "BOOM");
		Object ok2 = construct(fx.host, "c");

		for (Object o : List.of(ok1, bad, ok2)) {
			resetToDefault(fx.host, o, "bad", "Ljava/lang/String;");
			resetToDefault(fx.host, o, "derived", "Ljava/lang/String;");
			InstanceTracker.register(o);
		}

		InitFix.PatchReport report = fx.transform();
		expect(report, false, "bad", InitFix.FieldStatus.ACCEPTED, null);
		expect(report, false, "derived", InitFix.FieldStatus.ACCEPTED, null);

		// 打补丁前武装开关：只有 seed=BOOM 的那个实例会在切片里抛异常
		fx.host.getMethod("arm", boolean.class).invoke(null, true);

		fx.apply();

		// 失败的那个实例：bad 抛异常保持默认值，derived 被连带跳过
		check(read(fx.host, bad, "bad") == null,
			"CaseX：失败实例的 bad 保持默认值（实际 "
			+ describe(read(fx.host, bad, "bad")) + "）");
		check(read(fx.host, bad, "derived") == null,
			"CaseX：失败实例的 derived 被连带跳过（实际 "
			+ describe(read(fx.host, bad, "derived")) + "）");

		// 核心断言：其余实例的 derived 必须照常补上 —— 它们的 bad 是成功的。
		// 旧实现用共享 failedFields，这里会错误地变成 null。
		check("a!".equals(read(fx.host, ok1, "derived")),
			"CaseX：第 1 个实例的 derived 不受其它实例失败牵连（期望 'a!'，实际 "
			+ describe(read(fx.host, ok1, "derived")) + "）");
		check("c!".equals(read(fx.host, ok2, "derived")),
			"CaseX：第 3 个实例的 derived 不受其它实例失败牵连（期望 'c!'，实际 "
			+ describe(read(fx.host, ok2, "derived")) + "）");

		// 成功实例的 bad 也应正常补上
		check("a".equals(read(fx.host, ok1, "bad")),
			"CaseX：第 1 个实例的 bad 正常补上（实际 "
			+ describe(read(fx.host, ok1, "bad")) + "）");

		// 台账：失败字段与它的下游仍须记账（下一轮重试）
		Set<String> unpatched = unpatched(fx.host);
		check(unpatched.contains("bad"),
			"CaseX：失败的 bad 记入 FieldLedger（实际 " + unpatched + "）");
		check(unpatched.contains("derived"),
			"CaseX：被跳过的 derived 记入 FieldLedger（实际 " + unpatched + "）");
	}

	/**
	 * 熔断场景：{@code bad} 的切片对<b>每个实例</b>都抛异常（确定性失败），
	 * 用来验证 §5.2 的代价控制 —— 失败次数封顶后不再对后续实例重试。
	 *
	 * <p>按实例隔离之后，确定性失败会让每个实例都各自失败一遍，日志与耗时放大成 N 倍；
	 * {@code MAX_INSTANCE_FAILURES_PER_FIELD} 把同一字段的失败次数封顶。</p>
	 */
	static final String CASE_X2_V1 = """
		package oracle;
		public class CaseX2 {
			public  static boolean ARMED;
			public CaseX2(String seed) { }
			public  static void arm(boolean v) { ARMED = v; }
		}
		""";

	static final String CASE_X2_V2 = """
		package oracle;
		public class CaseX2 {
			public  static boolean ARMED;
			private String bad;
			public CaseX2(String seed) { this.bad = pick(); }
			public  static void arm(boolean v) { ARMED = v; }
			public String bad() { return bad; }
			private static String pick() {
				if (ARMED) throw new IllegalStateException("always");
				return "x";
			}
		}
		""";

	static void caseFailureCircuitBreaker() throws Exception {
		Fixture fx = loadFixture("oracle.CaseX2", CASE_X2_V1, CASE_X2_V2);

		// 20 个实例，全部会失败 —— 远超过熔断阈值 8
		List<Object> subjects = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			Object o = construct(fx.host, "seed" + i);
			resetToDefault(fx.host, o, "bad", "Ljava/lang/String;");
			InstanceTracker.register(o);
			subjects.add(o);
		}

		InitFix.PatchReport report = fx.transform();
		expect(report, false, "bad", InitFix.FieldStatus.ACCEPTED, null);

		fx.host.getMethod("arm", boolean.class).invoke(null, true);

		int errorsBefore = agentErrors.size();
		long startNanos = System.nanoTime();
		fx.apply();
		long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
		int detailed = agentErrors.size() - errorsBefore;

		// 注意：DETAILED_FAILURE_LOGS 本身就把详细栈封顶在 5 条，
		// 所以"日志数 < 20"<b>不是</b>熔断的证据（去掉熔断也一样成立）。
		// 真正由熔断决定的是：该字段被尝试的次数被封顶，因此沿用的
		// skippedFields 记账只发生一次，且总耗时不随实例数线性膨胀。
		check(detailed <= 5,
			"CaseX2：详细失败日志被封顶（20 个实例，实际 " + detailed + " 条 error）");

		// 熔断的证据：bad 被记为"失败 N 次后放弃"，而不是逐实例重复失败。
		check(skippedOrFailedReason(fx.host, "bad").contains("giving up")
		      || skippedOrFailedReason(fx.host, "bad").contains("8 times"),
			"CaseX2：熔断生效，bad 的原因记录了「达到失败上限后放弃」（实际："
			+ skippedOrFailedReason(fx.host, "bad") + "）");

		// 所有实例的 bad 都保持默认值
		boolean allDefault = true;
		for (Object o : subjects) if (read(fx.host, o, "bad") != null) allDefault = false;
		check(allDefault, "CaseX2：全部实例的 bad 都保持默认值");

		// 字段记入台账（下一轮重试）
		check(unpatched(fx.host).contains("bad"),
			"CaseX2：熔断字段仍记入 FieldLedger（实际 " + unpatched(fx.host) + "）");
	}

	// ==================== 场景 16e：TTL 清扫丢弃必须记账 ====================

	/** V1：原始版本（只有 raw）。 */
	static final String CASE_TTL_V1 = """
		package oracle;
		public class CaseTtl {
			private final String raw;
			public CaseTtl(String raw) { this.raw = raw; }
			public String raw() { return raw; }
		}
		""";

	/**
	 * V2：新增两个字段，生成补丁后<b>不</b>执行 applyPatch。
	 * <p>两个字段的切片都必须是确定性的（{@code trim()} / {@code length()} 可以，
	 * {@code toUpperCase()} 不行 —— 它依赖默认 Locale，会被效应掩码拒绝）。</p>
	 */
	static final String CASE_TTL_V2 = """
		package oracle;
		public class CaseTtl {
			private final String raw;
			private String clean;
			private int len;
			public CaseTtl(String raw) { this.raw = raw; this.clean = raw.trim(); this.len = this.clean.length(); }
			public String raw() { return raw; }
			public String clean() { return clean; }
			public int len() { return len; }
		}
		""";

	/**
	 * 待补补丁因 TTL 被清扫时，其中的字段必须记入台账。
	 *
	 * <p>场景：{@code transform} 生成了补丁（字段 ACCEPTED、已出账），
	 * 但 {@code applyPatch} 从未执行（例如 redefine 成功而补丁窗口错过）。
	 * TTL 到期后清扫把它们丢弃 —— 若不写回台账，这些字段在下一轮既不在 Diff 里、
	 * 也不在台账里，就<b>永久遗忘</b>了（§3.4 的核心场景）。</p>
	 */
	static void caseTtlSweepRecordsLedger() throws Exception {
		// 把 TTL 设成 0：一经创建即视为过期
		InitFix.setPendingTtlNanos(0);
		try {
			Fixture fx = loadFixture("oracle.CaseTtl", CASE_TTL_V1, CASE_TTL_V2);

			Object subject = construct(fx.host, "  hi  ");
			resetToDefault(fx.host, subject, "clean", "Ljava/lang/String;");
			resetToDefault(fx.host, subject, "len", "I");
			InstanceTracker.register(subject);

			InitFix.PatchReport report = fx.transform();
			expect(report, false, "clean", InitFix.FieldStatus.ACCEPTED, null);
			expect(report, false, "len", InitFix.FieldStatus.ACCEPTED, null);
			check(report.patchGenerated(), "CaseTtl 前置条件：补丁已生成");

			// 关键前置：此刻两个字段都没在台账里（ACCEPTED 已出账），
			// 而补丁还没执行 —— 这正是"不记账就永久遗忘"的窗口。
			check(unpatched(fx.host).isEmpty(),
				"CaseTtl 前置条件：ACCEPTED 字段当前不在台账（实际 " + unpatched(fx.host) + "）");

			// 触发 TTL 清扫（不调用 applyPatch）
			InitFix.sweepStalePatches();

			Set<String> after = unpatched(fx.host);
			check(after.contains("clean"),
				"CaseTtl：TTL 丢弃的 clean 被记入 FieldLedger（实际 " + after + "）");
			check(after.contains("len"),
				"CaseTtl：TTL 丢弃的 len 一并记入 FieldLedger（实际 " + after + "）");

			String reason = skippedOrFailedReason(fx.host, "clean");
			check(reason.contains("expired"),
				"CaseTtl：台账原因指明是待补补丁过期（实际：" + reason + "）");
		} finally {
			InitFix.setPendingTtlNanos(java.util.concurrent.TimeUnit.MINUTES.toNanos(5));
		}
	}

	// ==================== 场景 16f：成环后依赖方必须重判 ====================

	/** V1：原始版本（无环字段）。 */
	static final String CASE_CYC_V1 = """
		package oracle;
		public class CaseCyc {
			private final String raw;
			public CaseCyc(String raw) { this.raw = raw; }
			public String raw() { return raw; }
		}
		""";

	/**
	 * V2：同时构造<b>静态环</b>与<b>实例环</b>，以及各自环外的依赖方。
	 *
	 * <p>Java 语法限制：静态字段不能用简单名字前向引用，所以环写成
	 * {@code CaseCyc.b + 1} / {@code CaseCyc.a + 1} 全限定形式。</p>
	 *
	 * <ul>
	 *   <li><b>静态环</b>：{@code sa ⟷ sb}；{@code si = sa + 1} 依赖环成员。</li>
	 *   <li><b>实例环</b>：{@code ix ⟷ iy}；{@code ij = ix + 1} 依赖环成员。</li>
	 * </ul>
	 *
	 * <p>两者都是同一类缺陷：环成员在拓扑排序阶段才被拒，而依赖方的 {@code depReason}
	 * 早已通过 —— 补丁照常执行，读到未补的默认值，静默写入过期值。</p>
	 */
	static final String CASE_CYC_V2 = """
		package oracle;
		public class CaseCyc {
			private final String raw;
			private static int sa;
			private static int sb;
			private static int si;
			private int ix;
			private int iy;
			private int ij;
			static {
				sa = CaseCyc.sb + 1;
				sb = CaseCyc.sa + 1;
				si = sa + 1;
			}
			public CaseCyc(String raw) {
				this.raw = raw;
				this.ix = this.iy + 1;
				this.iy = this.ix + 1;
				this.ij = this.ix + 1;
			}
			public String raw() { return raw; }
		}
		""";

	static void caseCycleReevaluatesDependents() throws Exception {
		Fixture fx = loadFixture("oracle.CaseCyc", CASE_CYC_V1, CASE_CYC_V2);

		Object subject = construct(fx.host, "r");
		resetToDefault(fx.host, subject, "ix", "I");
		resetToDefault(fx.host, subject, "iy", "I");
		resetToDefault(fx.host, subject, "ij", "I");
		InstanceTracker.register(subject);

		InitFix.PatchReport report = fx.transform();

		// 环成员必须被拒
		boolean staticCycleRejected =
		 report.staticFields().get("sa") != null
		 && report.staticFields().get("sa").status() == InitFix.FieldStatus.REJECTED;
		boolean instanceCycleRejected =
		 report.instanceFields().get("ix") != null
		 && report.instanceFields().get("ix").status() == InitFix.FieldStatus.REJECTED;
		check(staticCycleRejected, "CaseCyc：静态环成员被拒（实际 "
		      + report.staticFields().get("sa") + "）");
		check(instanceCycleRejected, "CaseCyc：实例环成员被拒（实际 "
		      + report.instanceFields().get("ix") + "）");

		// 核心断言：环外的依赖方必须也被拒 —— 它读的是未补的环成员
		boolean siRejected = report.staticFields().get("si") != null
		 && report.staticFields().get("si").status() == InitFix.FieldStatus.REJECTED;
		boolean ijRejected = report.instanceFields().get("ij") != null
		 && report.instanceFields().get("ij").status() == InitFix.FieldStatus.REJECTED;

		check(siRejected, "CaseCyc：依赖静态环成员的 si 必须被连带拒绝（实际 "
		      + report.staticFields().get("si") + "）");
		check(ijRejected, "CaseCyc：依赖实例环成员的 ij 必须被连带拒绝（实际 "
		      + report.instanceFields().get("ij") + "）");

		// 诊断文案必须可辨识（否则只是换了一种静默）
		String siReason = report.staticFields().get("si") == null ? ""
		 : String.valueOf(report.staticFields().get("si").reason());
		String ijReason = report.instanceFields().get("ij") == null ? ""
		 : String.valueOf(report.instanceFields().get("ij").reason());
		check(siReason.contains("cycle") || siReason.contains("depends on rejected")
		      || siReason.contains("dependency"),
			"CaseCyc：si 的拒绝原因可辨识（实际：" + siReason + "）");
		check(ijReason.contains("cycle") || ijReason.contains("depends on rejected")
		      || ijReason.contains("dependency"),
			"CaseCyc：ij 的拒绝原因可辨识（实际：" + ijReason + "）");

		fx.apply();

		// 端到端：ij 不得被写成过期值 1
		Object ij = read(fx.host, subject, "ij");
		check(Integer.valueOf(0).equals(ij),
			"CaseCyc：存量实例的 ij 保持默认值 0，不得被写成过期值（实际 " + describe(ij) + "）");

		// 台账：三边都要记账
		Set<String> unpatched = unpatched(fx.host);
		check(unpatched.contains("si"), "CaseCyc：si 记入台账（实际 " + unpatched + "）");
		check(unpatched.contains("ij"), "CaseCyc：ij 记入台账（实际 " + unpatched + "）");
	}

	/**
	 * 对照场景：静态字段<b>无环</b>时，读取它的实例字段必须照常放行。
	 * <p>防止修复退化成"见到静态依赖就拒绝"。</p>
	 */
	static final String CASE_CYC2_V1 = """
		package oracle;
		public class CaseCyc2 {
			private final String raw;
			public CaseCyc2(String raw) { this.raw = raw; }
			public String raw() { return raw; }
		}
		""";

	static final String CASE_CYC2_V2 = """
		package oracle;
		public class CaseCyc2 {
			private final String raw;
			private static int sa;
			private int si;
			static { sa = 41; }
			public CaseCyc2(String raw) { this.raw = raw; this.si = sa + 1; }
			public String raw() { return raw; }
			public int si() { return si; }
		}
		""";

	static void caseAcyclicStaticStillAccepted() throws Exception {
		Fixture fx = loadFixture("oracle.CaseCyc2", CASE_CYC2_V1, CASE_CYC2_V2);

		Object control = construct(fx.host, "r");
		Object subject = construct(fx.host, "r");
		resetToDefault(fx.host, subject, "si", "I");
		InstanceTracker.register(subject);

		InitFix.PatchReport report = fx.transform();
		expect(report, true, "sa", InitFix.FieldStatus.ACCEPTED, null);
		expect(report, false, "si", InitFix.FieldStatus.ACCEPTED, null);

		fx.apply();

		expectValue(fx.host, subject, control, "si");
	}

	// ==================== 场景 16g：静态环拒绝后，实例依赖方漏网 ====================

	/** V1：原始版本（无环字段）。 */
	static final String CASE_CYC3_V1 = """
		package oracle;
		public class CaseCyc3 {
			private final String raw;
			public CaseCyc3(String raw) { this.raw = raw; }
			public String raw() { return raw; }
		}
		""";

	/**
	 * V2：<b>静态环</b> + 一个读取环成员的<b>实例</b>字段。
	 *
	 * <p>这是 §3.5 真正的漏网形态，与 CaseCyc 的关键区别在于
	 * <b>依赖方不在同一个接受集合里</b>：</p>
	 * <ol>
	 *   <li>{@code i} 是实例字段，{@code sa} 是静态字段；二者分属
	 *       {@code acceptedInstance} / {@code acceptedStatic}。</li>
	 *   <li>闭包阶段 {@code i} 的 {@code depReason} 通过（此时 {@code sa} 还在 {@code acceptedStatic}）。</li>
	 *   <li>静态拓扑排序发现 {@code sa ⟷ sb} 成环，整组拒绝并 {@code acceptedStatic.clear()}。</li>
	 *   <li>{@code i} <b>不在</b>被清空的那个集合里，于是补丁照常发射并执行 ——
	 *       读到 {@code sa} 的默认值 0，把 {@code 1} 静默写进存量实例。</li>
	 * </ol>
	 *
	 * <p>对比：静态依赖方 {@code si} 与被清空的集合同属一组，会被整组拒绝连带覆盖，
	 * 因此在修复前就是安全的。这正是"整组拒绝偏保守但安全"的边界所在 ——
	 * 它覆盖同组，不覆盖跨组。</p>
	 */
	static final String CASE_CYC3_V2 = """
		package oracle;
		public class CaseCyc3 {
			private final String raw;
			private static int sa;
			private static int sb;
			private int i;
			static {
				sa = CaseCyc3.sb + 1;
				sb = CaseCyc3.sa + 1;
			}
			public CaseCyc3(String raw) { this.raw = raw; this.i = sa + 1; }
			public String raw() { return raw; }
			public int i() { return i; }
		}
		""";

	static void caseStaticCycleLeaksInstanceDependent() throws Exception {
		Fixture fx = loadFixture("oracle.CaseCyc3", CASE_CYC3_V1, CASE_CYC3_V2);

		Object subject = construct(fx.host, "r");
		resetToDefault(fx.host, subject, "i", "I");
		InstanceTracker.register(subject);

		InitFix.PatchReport report = fx.transform();

		// 前置：静态环成员确实被拒了（否则下面的漏网就无从谈起）
		boolean saRejected = report.staticFields().get("sa") != null
		 && report.staticFields().get("sa").status() == InitFix.FieldStatus.REJECTED;
		check(saRejected, "CaseCyc3 前置条件：静态环成员 sa 被拒（实际 "
		      + report.staticFields().get("sa") + "）");

		// 核心断言：读静态环成员的实例字段 i 必须也被拒
		InitFix.FieldDecision d = report.instanceFields().get("i");
		check(d != null && d.status() == InitFix.FieldStatus.REJECTED,
			"CaseCyc3：读静态环成员的实例字段 i 必须被连带拒绝（实际 " + d + "）");

		fx.apply();

		// 端到端：i 不得被写成过期值 1
		Object got = read(fx.host, subject, "i");
		check(Integer.valueOf(0).equals(got),
			"CaseCyc3：存量实例的 i 保持默认值 0，不得被静默写成过期值（实际 " + describe(got) + "）");

		// 台账：i 必须记账（否则下一轮遗忘）
		check(unpatched(fx.host).contains("i"),
			"CaseCyc3：i 记入 FieldLedger（实际 " + unpatched(fx.host) + "）");
	}

	// ==================== 场景 16h：成环拒绝应收窄到环成员 + 闭包连带 ====================

	/** V1：原始版本（无环字段）。 */
	static final String CASE_CYC4_V1 = """
		package oracle;
		public class CaseCyc4 {
			private final String raw;
			public CaseCyc4(String raw) { this.raw = raw; }
			public String raw() { return raw; }
		}
		""";

	/**
	 * V2：成环成员 + <b>与环无关</b>的独立字段。
	 *
	 * <p>静态侧：{@code sa ⟷ sb} 成环，{@code sd} 完全独立。
	 * 实例侧：{@code ix ⟷ iy} 成环，{@code iz} 完全独立。</p>
	 *
	 * <p>期望（收窄后）：环成员与"依赖环成员"的字段被拒；{@code sd}/{@code iz}
	 * 与环无关，应当<b>正常放行</b>。整组拒绝会连带拒掉它们 ——
	 * 安全但白白损失热更覆盖面。</p>
	 */
	static final String CASE_CYC4_V2 = """
		package oracle;
		public class CaseCyc4 {
			private final String raw;
			private static int sa;
			private static int sb;
			private static int sd;
			private int ix;
			private int iy;
			private int iz;
			static {
				sa = CaseCyc4.sb + 1;
				sb = CaseCyc4.sa + 1;
				sd = 7;
			}
			public CaseCyc4(String raw) {
				this.raw = raw;
				this.ix = this.iy + 1;
				this.iy = this.ix + 1;
				this.iz = 9;
			}
			public String raw() { return raw; }
			public int sd() { return sd; }
			public int iz() { return iz; }
		}
		""";

	static void caseCycleRefusalIsNarrow() throws Exception {
		Fixture fx = loadFixture("oracle.CaseCyc4", CASE_CYC4_V1, CASE_CYC4_V2);

		Object subject = construct(fx.host, "r");
		resetToDefault(fx.host, subject, "ix", "I");
		resetToDefault(fx.host, subject, "iy", "I");
		resetToDefault(fx.host, subject, "iz", "I");
		InstanceTracker.register(subject);

		InitFix.PatchReport report = fx.transform();

		// 环成员必须被拒（这是安全底线，收窄不能破坏它）
		boolean saRejected = report.staticFields().get("sa") != null
		 && report.staticFields().get("sa").status() == InitFix.FieldStatus.REJECTED;
		boolean ixRejected = report.instanceFields().get("ix") != null
		 && report.instanceFields().get("ix").status() == InitFix.FieldStatus.REJECTED;
		check(saRejected, "CaseCyc4：静态环成员 sa 被拒（实际 "
		      + report.staticFields().get("sa") + "）");
		check(ixRejected, "CaseCyc4：实例环成员 ix 被拒（实际 "
		      + report.instanceFields().get("ix") + "）");

		// 收窄的核心：与环无关的独立字段必须放行
		InitFix.FieldDecision sd = report.staticFields().get("sd");
		InitFix.FieldDecision iz = report.instanceFields().get("iz");
		check(sd != null && sd.status() == InitFix.FieldStatus.ACCEPTED,
			"CaseCyc4：与环无关的静态字段 sd 应放行（实际 " + sd + "）");
		check(iz != null && iz.status() == InitFix.FieldStatus.ACCEPTED,
			"CaseCyc4：与环无关的实例字段 iz 应放行（实际 " + iz + "）");

		fx.apply();

		// 独立字段必须真的补上正确值（不是"放行了但没生效"）
		Object izVal = read(fx.host, subject, "iz");
		check(Integer.valueOf(9).equals(izVal),
			"CaseCyc4：独立实例字段 iz 补上正确值 9（实际 " + describe(izVal) + "）");
	}

	// ==================== 场景 16i：float/double 的条件 CAS 必须保持条件语义 ====================

	/** V1：原始版本（只有 raw）。 */
	static final String CASE_FP_V1 = """
		package oracle;
		public class CaseFp {
			private final String raw;
			public CaseFp(String raw) { this.raw = raw; }
			public String raw() { return raw; }
		}
		""";

	/**
	 * V2：新增 float / double 字段，初始化式非零（因此不是 T0 零值等价，会生成条件 CAS）。
	 *
	 * <p>判别点：存量实例上这些字段<b>已被别的线程赋成非默认值</b>时，补丁必须
	 * <b>不覆盖</b>它 —— 条件 CAS 的语义就是"仅当仍是类型默认零值才写"。
	 * 若 float/double 降级成无条件 volatile 写，就会把已写入的值冲掉。</p>
	 *
	 * <p>用 {@code -0.0f} 作为"已存在的值"更有鉴别力：它的位模式非零
	 * （{@code 0x80000000}），按 §4.1 T0 的按位判零口径**不是**零值等价，
	 * 因此条件 CAS 必须判定为"已有值"而跳过。整数比较 {@code == 0.0f} 的实现
	 * 会误判 {@code -0.0f == 0.0f} 为真并覆盖它。</p>
	 */
	static final String CASE_FP_V2 = """
		package oracle;
		public class CaseFp {
			private final String raw;
			private float  f;
			private double d;
			public CaseFp(String raw) { this.raw = raw; this.f = 1.5f; this.d = 2.5d; }
			public String raw() { return raw; }
			public float f() { return f; }
			public double d() { return d; }
		}
		""";

	static void caseFloatDoubleConditionalCas() throws Exception {
		Fixture fx = loadFixture("oracle.CaseFp", CASE_FP_V1, CASE_FP_V2);

		// 三个存量实例，补丁前把它们置成不同的"已有值"
		Object occupiedF = construct(fx.host, "r");
		Object occupiedD = construct(fx.host, "r");
		Object occupiedNegZero = construct(fx.host, "r");

		setFieldF(fx.host, occupiedF, "f", 9.0f);          // 明显的非默认值
		setFieldD(fx.host, occupiedD, "d", 9.0d);
		setFieldF(fx.host, occupiedNegZero, "f", -0.0f);   // 位模式非零，按 T0 口径算"已有值"

		InstanceTracker.register(occupiedF);
		InstanceTracker.register(occupiedD);
		InstanceTracker.register(occupiedNegZero);

		InitFix.PatchReport report = fx.transform();
		expect(report, false, "f", InitFix.FieldStatus.ACCEPTED, null);
		expect(report, false, "d", InitFix.FieldStatus.ACCEPTED, null);

		fx.apply();

		// 核心断言：已存在的非默认值不得被覆盖
		Object gotF = read(fx.host, occupiedF, "f");
		check(Float.floatToRawIntBits(9.0f) == Float.floatToRawIntBits((Float) gotF),
			"CaseFp：已存在的 float 值不得被补丁覆盖（期望 9.0，实际 " + describe(gotF) + "）");

		Object gotD = read(fx.host, occupiedD, "d");
		check(Double.doubleToRawLongBits(9.0d) == Double.doubleToRawLongBits((Double) gotD),
			"CaseFp：已存在的 double 值不得被补丁覆盖（期望 9.0，实际 " + describe(gotD) + "）");

		// 按位判零口径：-0.0f 不算零值等价，必须视为"已有值"而跳过
		Object gotNZ = read(fx.host, occupiedNegZero, "f");
		check(Float.floatToRawIntBits(-0.0f) == Float.floatToRawIntBits((Float) gotNZ),
			"CaseFp：-0.0f 应被视为已有值而跳过（位模式须保持 0x80000000，实际 "
			+ describe(gotNZ) + "）");
	}

	/** 反射写 float（{@code setField} 走 Object，基本类型需要显式装箱路径）。 */
	static void setFieldF(Class<?> host, Object target, String field, float value) {
		try {
			Field f = host.getDeclaredField(field);
			f.setAccessible(true);
			f.setFloat(target, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	static void setFieldD(Class<?> host, Object target, String field, double value) {
		try {
			Field f = host.getDeclaredField(field);
			f.setAccessible(true);
			f.setDouble(target, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** V1：原始版本（只有 raw）。 */
	static final String CASE_Z_V1 = """
		package oracle;
		public class CaseZ {
			private final String raw;
			public CaseZ(String raw) { this.raw = raw; }
			public String raw() { return raw; }
		}
		""";

	/**
	 * V2：新增两个字段，`zero` 是 T0 零值等价（构造器里根本不写它 —— {@code writes == 0}
	 * 即"仅声明未赋值"），`derived` 读它算值。
	 * <p>关键点：`zero` 不进 acceptedInstance（T0 不生成补丁），但它也不是"未补的危险新字段"
	 * —— 存量实例上它本来就是 0。所以 `derived = zero + 1` 必须放行。</p>
	 */
	static final String CASE_Z_V2 = """
		package oracle;
		public class CaseZ {
			private final String raw;
			private int zero;
			private int derived;
			public CaseZ(String raw) { this.raw = raw; this.derived = this.zero + 1; }
			public String raw() { return raw; }
			public int zero() { return zero; }
			public int derived() { return derived; }
		}
		""";

	static void caseZeroValueDependency() throws Exception {
		Fixture fx = loadFixture("oracle.CaseZ", CASE_Z_V1, CASE_Z_V2);

		InitFix.PatchReport report = fx.transform();

		// zero 是零值等价：不生成补丁，但也不算"未补"
		expect(report, false, "zero", InitFix.FieldStatus.NOTHING_TO_PATCH, null);
		// 核心断言：读零值字段的 derived 必须放行，而不是被依赖门禁误杀
		expect(report, false, "derived", InitFix.FieldStatus.ACCEPTED, null);

		Object control = construct(fx.host, "  abc  ");
		Object subject = construct(fx.host, "  abc  ");
		resetToDefault(fx.host, subject, "zero", "I");
		resetToDefault(fx.host, subject, "derived", "I");
		InstanceTracker.register(subject);

		fx.transform();
		fx.apply();

		expectValue(fx.host, subject, control, "derived");
	}

	/** V1：静态版本（只有 raw）。 */
	static final String CASE_Z2_V1 = """
		package oracle;
		public class CaseZ2 {
			public static final String RAW = "r";
		}
		""";

	/** V2：新增零值静态字段（未写、无 ConstantValue）+ 读它的静态字段。 */
	static final String CASE_Z2_V2 = """
		package oracle;
		public class CaseZ2 {
			public static final String RAW = "r";
			public static int zero;
			public static int derived;
			static { derived = zero + 1; }
		}
		""";

	static void caseZeroValueStaticDependency() throws Exception {
		Fixture fx = loadFixture("oracle.CaseZ2", CASE_Z2_V1, CASE_Z2_V2);

		InitFix.PatchReport report = fx.transform();

		expect(report, true, "zero", InitFix.FieldStatus.NOTHING_TO_PATCH, null);
		expect(report, true, "derived", InitFix.FieldStatus.ACCEPTED, null);

		// 关键：把 derived 清零，模拟"存量类里 derived 还是默认值"。
		// 不这样做的话，类初始化时 <clinit> 早已把 derived 算成 1，
		// 补丁有没有跑都看不出区别 —— 断言就失去了鉴别力。
		Field f = fx.host.getDeclaredField("derived");
		f.setAccessible(true);
		Reflect.UNSAFE.putInt(fx.host, Reflect.UNSAFE.staticFieldOffset(f), 0);

		fx.apply();

		check(f.getInt(null) == 1,
			"CaseZ2：静态 derived 依赖零值静态字段被放行并补成 1（实际 " + f.getInt(null) + "）");
	}

	// ==================== 场景 16c：拒绝告警出口 ====================

	/** V1：原始版本（只有 raw）。 */
	static final String CASE_W_V1 = """
		package oracle;
		public class CaseW {
			private final String raw;
			public CaseW(String raw) { this.raw = raw; }
			public String raw() { return raw; }
		}
		""";

	/**
	 * V2：新增一个走非确定性调用（{@code System.currentTimeMillis()}）的字段。
	 * <p>它在<b>提取期</b>就被拒，因此历史上只发一条 warn —— 用来说明两条告警都存在时
	 * 不会互相吞掉，且闭包阶段的拒绝也有出口。</p>
	 */
	static final String CASE_W_V2 = """
		package oracle;
		public class CaseW {
			private final String raw;
			private long stamp;
			public CaseW(String raw) { this.raw = raw; this.stamp = System.currentTimeMillis(); }
			public String raw() { return raw; }
			public long stamp() { return stamp; }
		}
		""";

	/**
	 * V2'：新增一个字段，它在<b>闭包阶段</b>才被拒（依赖一个被拒的字段）。
	 * <p>{@code a} 读 {@code b}，而 {@code b} 因非确定性被拒 → {@code a} 走 depReason 拒绝，
	 * 这个拒绝历史上只调 {@code log()}（默认静默），必须出现在 warn 里。</p>
	 */
	static final String CASE_W2_V1 = """
		package oracle;
		public class CaseW2 {
			private final String raw;
			public CaseW2(String raw) { this.raw = raw; }
			public String raw() { return raw; }
		}
		""";

	static final String CASE_W2_V2 = """
		package oracle;
		public class CaseW2 {
			private final String raw;
			private long b;
			private long a;
			public CaseW2(String raw) { this.raw = raw; this.b = System.nanoTime(); this.a = this.b + 1; }
			public String raw() { return raw; }
		}
		""";

	static void caseRejectionWarnings() throws Exception {
		int before = agentWarnings.size();

		// ---- 提取期拒绝：本来就发 warn ----
		Fixture fx1 = loadFixture("oracle.CaseW", CASE_W_V1, CASE_W_V2);
		InitFix.PatchReport r1 = fx1.transform();
		expect(r1, false, "stamp", InitFix.FieldStatus.REJECTED, "non-deterministic");

		boolean extractionWarned = agentWarnings.stream()
		 .skip(before)
		 .anyMatch(w -> w.contains("stamp") && w.contains("initialization skipped"));
		check(extractionWarned,
			"CaseW：提取期拒绝发出 warn（实际新增 " + (agentWarnings.size() - before) + " 条）");

		// 统一出口也覆盖它：报告里的 REJECTED 必有对应 warn
		boolean refusedWarned = agentWarnings.stream()
		 .skip(before)
		 .anyMatch(w -> w.contains("stamp") && w.contains("patch refused"));
		check(refusedWarned,
			"CaseW：提取期拒绝同样经统一出口告警（不因两条通道而漏掉）");

		// ---- 闭包期拒绝：历史上只调 log()，默认静默 ----
		int before2 = agentWarnings.size();
		Fixture fx2 = loadFixture("oracle.CaseW2", CASE_W2_V1, CASE_W2_V2);
		InitFix.PatchReport r2 = fx2.transform();
		expect(r2, false, "b", InitFix.FieldStatus.REJECTED, "non-deterministic");
		expect(r2, false, "a", InitFix.FieldStatus.REJECTED, "dependency");

		boolean closureWarned = agentWarnings.stream()
		 .skip(before2)
		 .anyMatch(w -> w.contains("a") && w.contains("patch refused")
		                && w.contains("dependency"));
		check(closureWarned,
			"CaseW2：闭包期（依赖）拒绝发出 warn，而不是静默进报告"
			+ "（实际新增 " + (agentWarnings.size() - before2) + " 条）");
	}

	// ==================== 场景 16：§3.4 FieldLedger ====================

	/** V1：原始版本（`raw` 是包级私有、非 final）。 */
	static final String CASE_V_V1 = """
		package oracle;
		public class CaseV {
			String raw;
			public CaseV(String raw) { this.raw = raw; }
			public String raw() { return raw; }
		}
		""";

	/** V2：新增 `clean = raw.trim()` —— 源字段不可证明不可变（§4.3 条件 A/B 均不满足）→ 拒绝。 */
	static final String CASE_V_V2 = """
		package oracle;
		public class CaseV {
			String raw;
			private String clean;
			public CaseV(String raw) { this.raw = raw; this.clean = raw.trim(); }
			public String raw() { return raw; }
		}
		""";

	/** V3：用户把 `raw` 修成 `private final` —— **没有任何新增字段**，只有台账能让 clean 重试。 */
	static final String CASE_V_V3 = """
		package oracle;
		public class CaseV {
			private final String raw;
			private String clean;
			public CaseV(String raw) { this.raw = raw; this.clean = raw.trim(); }
			public String raw() { return raw; }
		}
		""";

	static void caseFieldLedger() throws Exception {
		String dot = "oracle.CaseV";
		byte[] b1 = compile(Map.of(dot, CASE_V_V1)).get(dot);
		byte[] b2 = compile(Map.of(dot, CASE_V_V2)).get(dot);
		byte[] b3 = compile(Map.of(dot, CASE_V_V3)).get(dot);

		Class<?> host = Class.forName(dot, true, new ByteLoader(Map.of(dot, b2)));
		HotSwapAgent.bytecodeCache.put(dot, b2);

		// ---- 第 1 轮：clean 被拒 → 必须记入台账 ----
		InitFix.transform(host, b2, ClassDiffUtil.diff(b1, b2));
		InitFix.PatchReport r1 = InitFix.getLastReport(host);
		expect(r1, false, "clean", InitFix.FieldStatus.REJECTED, "not final and not private");
		check(!r1.patchGenerated(), "CaseV 第 1 轮：未生成补丁");
		check(unpatched(host).contains("clean"),
			"CaseV 第 1 轮：clean 已记入 FieldLedger（实际 " + InitFix.getUnpatchedFields(host) + "）");

		// ---- 前置条件：第 2 轮的 Diff 里没有任何新增字段 ----
		ClassDiffUtil.ClassDiff d23 = ClassDiffUtil.diff(b2, b3);
		check(d23.addedInstanceFields.isEmpty() && d23.addedStaticFields.isEmpty(),
			"CaseV 前置条件：第 2 轮 Diff 无新增字段 —— 没有台账就永远不会再看 clean 一眼");

		// ---- 第 2 轮：只有台账把 clean 并回候选集 ----
		Object control = construct(host, "  abc  ");
		Object subject = construct(host, "  abc  ");
		resetToDefault(host, subject, "clean", "Ljava/lang/String;");
		InstanceTracker.register(subject);

		InitFix.transform(host, b3, ClassDiffUtil.diff(b2, b3));
		InitFix.PatchReport r2 = InitFix.getLastReport(host);
		expect(r2, false, "clean", InitFix.FieldStatus.ACCEPTED, null);
		check(r2.patchGenerated(), "CaseV 第 2 轮：台账让 clean 重新进入候选集并生成补丁");

		InitFix.afterRedefine(host);
		expectValue(host, subject, control, "clean");
		check(InitFix.getUnpatchedFields(host).isEmpty(),
			"CaseV 第 2 轮：成功后已出账（实际 " + InitFix.getUnpatchedFields(host) + "）");
	}

	static Set<String> unpatched(Class<?> host) {
		Set<String> out = new LinkedHashSet<>();
		for (InitFix.UnpatchedField u : InitFix.getUnpatchedFields(host)) out.add(u.fieldName());
		return out;
	}

	/** 台账里某字段的原因文案（不在台账里返回空串）。 */
	static String skippedOrFailedReason(Class<?> host, String field) {
		for (InitFix.UnpatchedField u : InitFix.getUnpatchedFields(host)) {
			if (u.fieldName().equals(field)) return u.reason() == null ? "" : u.reason();
		}
		return "";
	}

	// ==================== 夹具装配 ====================

	static final class Fixture {
		final String           dotName;
		final byte[]           v1;
		final byte[]           v2;
		final Class<?>         host;
		InitFix.PatchReport    report;

		Fixture(String dotName, byte[] v1, byte[] v2, Class<?> host) {
			this.dotName = dotName;
			this.v1 = v1;
			this.v2 = v2;
			this.host = host;
		}

		InitFix.PatchReport transform() {
			ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(v1, v2);
			InitFix.transform(host, v2, diff);
			report = InitFix.getLastReport(host);
			if (report == null) throw new IllegalStateException(dotName + " 没有拿到 PatchReport");
			return report;
		}

		void apply() {
			InitFix.afterRedefine(host);
		}
	}

	static Fixture loadFixture(String dotName, String v1Source, String v2Source) throws Exception {
		Map<String, byte[]> v1Classes = compile(Map.of(dotName, v1Source));
		Map<String, byte[]> v2Classes = compile(Map.of(dotName, v2Source));

		byte[] v1 = v1Classes.get(dotName);
		byte[] v2 = v2Classes.get(dotName);
		if (v1 == null || v2 == null) throw new IllegalStateException("编译产物缺失：" + dotName);

		Class<?> host = Class.forName(dotName, true, new ByteLoader(v2Classes));
		// 生产环境里 transform 目标的字节码本来就在 bytecodeCache 中（§2.1 "优先读取新版本"），
		// Nest 成员则走 ClassLoader 资源流兜底。
		HotSwapAgent.bytecodeCache.put(dotName, v2);
		return new Fixture(dotName, v1, v2, host);
	}

	/** 只从内存字节码 defineClass 的类加载器；资源流也要能读到 Nest 成员。 */
	static final class ByteLoader extends ClassLoader {
		private final Map<String, byte[]> classes;

		ByteLoader(Map<String, byte[]> classes) {
			super(InitFixOracle.class.getClassLoader());
			this.classes = classes;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] bytes = classes.get(name);
			if (bytes == null) throw new ClassNotFoundException(name);
			return defineClass(name, bytes, 0, bytes.length);
		}

		@Override
		public InputStream getResourceAsStream(String name) {
			if (name.endsWith(".class")) {
				byte[] bytes = classes.get(name.substring(0, name.length() - 6).replace('/', '.'));
				if (bytes != null) return new ByteArrayInputStream(bytes);
			}
			return super.getResourceAsStream(name);
		}
	}

	/** 用系统编译器在内存里编译夹具，不依赖外部 javac 路径。 */
	static Map<String, byte[]> compile(Map<String, String> sources) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) throw new IllegalStateException("需要 JDK 运行（找不到系统 Java 编译器）");

		Map<String, byte[]> out = new LinkedHashMap<>();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		StandardJavaFileManager standard =
			compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);

		JavaFileManager memory = new ForwardingJavaFileManager<StandardJavaFileManager>(standard) {
			@Override
			public JavaFileObject getJavaFileForOutput(Location location, String className,
			                                           JavaFileObject.Kind kind, FileObject sibling) {
				URI uri = URI.create("mem:///" + className.replace('.', '/') + kind.extension);
				return new SimpleJavaFileObject(uri, kind) {
					@Override
					public OutputStream openOutputStream() {
						return new ByteArrayOutputStream() {
							@Override
							public void close() throws IOException {
								super.close();
								out.put(className, toByteArray());
							}
						};
					}
				};
			}
		};

		List<JavaFileObject> units = new ArrayList<>();
		for (Map.Entry<String, String> e : sources.entrySet()) {
			URI uri = URI.create("string:///" + e.getKey().replace('.', '/') + ".java");
			String content = e.getValue();
			units.add(new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
				@Override
				public CharSequence getCharContent(boolean ignoreEncodingErrors) {
					return content;
				}
			});
		}

		boolean ok = Boolean.TRUE.equals(compiler.getTask(null, memory, diagnostics,
			List.of("-proc:none", "-nowarn"), null, units).call());
		if (!ok) {
			StringBuilder sb = new StringBuilder("夹具编译失败：");
			for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
				sb.append("\n      ").append(d.getKind()).append(' ').append(d.getMessage(null));
			}
			throw new IllegalStateException(sb.toString());
		}
		return out;
	}

	// ==================== 断言与反射小工具 ====================

	static void expect(InitFix.PatchReport report, boolean isStatic, String field,
	                   InitFix.FieldStatus expected, String reasonPart) {
		Map<String, InitFix.FieldDecision> decisions =
			isStatic ? report.staticFields() : report.instanceFields();
		InitFix.FieldDecision d = decisions.get(field);
		String tag = (isStatic ? "static " : "") + field;

		if (d == null) {
			check(false, tag + "：报告里缺少该字段的决策记录");
			return;
		}
		check(d.status() == expected, tag + " 期望 " + expected + "，实际 " + d.status()
		      + (d.reason() == null ? "" : "（原因：" + d.reason() + "）"));
		if (reasonPart != null) {
			check(d.reason() != null && d.reason().contains(reasonPart),
				tag + " 原因应包含 \"" + reasonPart + "\"，实际：" + d.reason());
		}
	}

	/** 断言某字段带风险告警（报告里可见）。 */
	static void expectWarning(InitFix.PatchReport report, boolean isStatic, String field, String part) {
		Map<String, InitFix.FieldDecision> decisions =
			isStatic ? report.staticFields() : report.instanceFields();
		InitFix.FieldDecision d = decisions.get(field);
		String tag = (isStatic ? "static " : "") + field;
		if (d == null) {
			check(false, tag + "：报告里缺少该字段的决策记录");
			return;
		}
		boolean hit = false;
		for (String w : d.warnings()) {
			if (part == null || w.contains(part)) hit = true;
		}
		check(hit, tag + " 应带风险告警（含 \"" + part + "\"），实际 warnings=" + d.warnings());
	}

	/** 正向值比对：被补丁字段必须等于控制组构造器算出的值。 */
	static void expectValue(Fixture fx, Object subject, Object control, String field) {
		expectValue(fx.host, subject, control, field);
	}

	static void expectValue(Class<?> host, Object subject, Object control, String field) {
		Object want = read(host, control, field);
		Object got  = read(host, subject, field);
		check(Objects.equals(want, got), host.getName() + "." + field
		      + " 补丁值 == 构造器值（构造器=" + describe(want) + "，补丁=" + describe(got) + "）");
	}

	static String describe(Object o) {
		if (o == null) return "null";
		if (o instanceof Float f) return f + "(bits=0x" + Integer.toHexString(Float.floatToRawIntBits(f)) + ")";
		if (o instanceof Double d) return d + "(bits=0x" + Long.toHexString(Double.doubleToRawLongBits(d)) + ")";
		return "'" + o + "'";
	}

	static Object construct(Class<?> host, Object... args) throws Exception {
		Class<?>[] types = new Class<?>[args.length];
		for (int i = 0; i < args.length; i++) types[i] = args[i].getClass();
		if (args.length == 1 && args[0] instanceof String) types[0] = String.class;
		return construct(host, types, args);
	}

	static Object construct(Class<?> host, Class<?>[] types, Object... args) throws Exception {
		Constructor<?> c = host.getDeclaredConstructor(types);
		c.setAccessible(true);
		return c.newInstance(args);
	}

	static Object read(Class<?> host, Object target, String field) {
		try {
			Field f = host.getDeclaredField(field);
			f.setAccessible(true);
			return f.get(target);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	static void setField(Class<?> host, Object target, String field, Object value) {
		try {
			Field f = host.getDeclaredField(field);
			f.setAccessible(true);
			f.set(target, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * 把新增字段按 {@code Unsafe} 清零，模拟"redefine 之前就存在的存量实例"。
	 * <p>final 字段无法用反射写，所以这里直接走字段偏移。</p>
	 */
	@SuppressWarnings({"removal", "deprecation"})
	static void resetToDefault(Class<?> host, Object target, String field, String desc) {
		try {
			Field f = host.getDeclaredField(field);
			long off = Reflect.UNSAFE.objectFieldOffset(f);
			char c = desc.charAt(0);
			if (c == 'L' || c == '[') {
				Reflect.UNSAFE.putObject(target, off, null);
				return;
			}
			switch (desc) {
				case "I" -> Reflect.UNSAFE.putInt(target, off, 0);
				case "J" -> Reflect.UNSAFE.putLong(target, off, 0L);
				case "Z" -> Reflect.UNSAFE.putBoolean(target, off, false);
				case "F" -> Reflect.UNSAFE.putFloat(target, off, 0.0f);
				case "D" -> Reflect.UNSAFE.putDouble(target, off, 0.0d);
				default -> throw new IllegalArgumentException("resetToDefault 不支持 " + desc);
			}
		} catch (NoSuchFieldException e) {
			throw new IllegalStateException(e);
		}
	}

	static boolean hasField(byte[] bytes, String name) {
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		for (FieldNode f : cn.fields) {
			if (name.equals(f.name)) return true;
		}
		return false;
	}
}
