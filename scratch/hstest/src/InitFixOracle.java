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
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

	/** 被静音掉的 agent 日志里的 error 计数，失败时打印出来便于定位。 */
	static final List<String> agentErrors = new ArrayList<>();

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
			public void warn(String msg) { }
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

		System.out.println();
		System.out.println("通过 " + passed + " 条；失败 " + failed + " 条；合计 " + (passed + failed) + " 条");
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
		expect(r1, false, "sb", InitFix.FieldStatus.REJECTED, "field read outside any accepted extraction");
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
		check(r1.patchGenerated(), "CaseN1：@HotswapReinit 解锁了构造器读取检查，补丁照常生成");
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

	/** 正向值比对：被补丁字段必须等于控制组构造器算出的值。 */
	static void expectValue(Fixture fx, Object subject, Object control, String field) {
		Object want = read(fx.host, control, field);
		Object got  = read(fx.host, subject, field);
		check(Objects.equals(want, got), fx.dotName + "." + field
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
