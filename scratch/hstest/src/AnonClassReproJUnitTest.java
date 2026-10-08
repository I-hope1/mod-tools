import nipx.AnonClassAligner;
import nipx.AnnotationTransformer;
import nipx.HotSwapAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code AnonClassReproTest} 27 个场景的 JUnit 化。
 *
 * <p>调用的还是 {@code AnonClassReproTest.testScenarioN(javac, baseDir)}，用的是同样的
 * javac 21/8，条数 184 通过 + 1 KNOWN。</p>
 *
 * <p><b>javac 来源</b>：由 Gradle toolchain 解析后经系统属性 {@code hstest.javac8/17/21} 传入，
 * 不再用 {@code F:/...} 默认值。每个版本都会跑 {@code javac -version} 校验实际版本 —— 解析到
 * 别的 JDK 时<b>直接失败</b>，绝不 assumeTrue 跳过（跳过会变成"全绿但什么都没跑"）。</p>
 *
 * <p><b>顺序</b>：默认 {@code MethodName}（由 {@code hstestJunit} 设
 * {@code junit.jupiter.testmethod.order.default}）；传 {@code -Dhstest.order=random} 改为
 * {@code Random} 以浸泡隐式依赖，种子经 {@code -Dhstest.seed=NNN} 传入
 * {@code junit.jupiter.execution.order.random.seed}。因夹具已解耦，顺序不再是正确性前提。</p>
 *
 * <p><b>静态状态</b>：对齐器/代理有可变静态（{@code TEST_REVERSE_ORDER} 等）与全局 map
 * （{@code pendingAlignedClasses} / {@code bytecodeCache}），场景 8/11/15/16/21/23/26/27 会动它们。
 * {@code @BeforeEach} 快照默认值、{@code @AfterEach} 写回，且前后都清那两个 map（同 JVM 内其它
 * 测试类共用）。不硬编码复位值，改默认值不必同步改测试。</p>
 */
class AnonClassReproJUnitTest {

	@BeforeAll
	static void reportOrder() {
		System.out.println("[hstest] AnonClassReproJUnitTest method order="
			+ System.getProperty("junit.jupiter.testmethod.order.default", "(default)")
			+ " random.seed=" + System.getProperty("junit.jupiter.execution.order.random.seed", "(none)"));
	}

	/**
	 * 硬失败：按 {@code javac -version} 校验还不够，这里再编译一个平凡类，断言产物的 class 文件
	 * major 与"请求的 JDK"一致（21→65、8→52、11→55、17→61）。toolchain 解析到别的 JDK 时
	 * 直接让整个类失败，绝不跳过。
	 */
	@BeforeAll
	static void assertEffectiveJavacMajors() throws Exception {
		assertJavacMajor(21, 65);
		assertJavacMajor(17, 61);
		assertJavacMajor(11, 55);
		assertJavacMajor(8, 52);
	}

	private static void assertJavacMajor(int requested, int wantMajor) throws Exception {
		String path = javac(requested);
		Path d = Files.createTempDirectory("jmajor");
		Path src = d.resolve("P.java");
		Files.writeString(src, "public class P {}");
		Process p = new ProcessBuilder(path, "-d", d.toString(), src.toString())
			.redirectErrorStream(true).start();
		p.waitFor();
		byte[] b = Files.readAllBytes(d.resolve("P.class"));
		int major = ((b[6] & 0xff) << 8) | (b[7] & 0xff);
		assertEquals(wantMajor, major, "javac(" + requested + ") 产物 class 文件 major（证明真的用了该 JDK）");
	}

	/** 全类共享的临时目录（每类一次）。夹具已由场景内的幂等 builder 生成，顺序不再是前提。 */
	@TempDir
	static Path base;

	/** 默认值快照：[TEST_REVERSE_ORDER, MAX_ANON_PER_HOST, ALIGN_TIMEOUT_MS, ANON_STRICT, ANON_LAYOUT_GATE, ANON_DEBUG, FIXTURE_JDK_MAJOR]。 */
	private Object[] snap;

	/** javac 路径缓存（每版本只做一次 -version 校验）。 */
	private static final Map<Integer, String> JAVAC = new HashMap<>();

	@BeforeEach
	void saveStatics() {
		AnonClassReproTest.reset();
		snap = new Object[] {
			AnonClassAligner.TEST_REVERSE_ORDER,
			AnonClassAligner.MAX_ANON_PER_HOST,
			AnonClassAligner.ALIGN_TIMEOUT_MS,
			HotSwapAgent.ANON_STRICT,
			HotSwapAgent.ANON_LAYOUT_GATE,
			HotSwapAgent.ANON_DEBUG,
			AnonClassReproTest.FIXTURE_JDK_MAJOR,
		};
		// 快照后再置默认：参数化测试会在方法体内改成参数值，@AfterEach 用快照还原，
		// 否则 Random 顺序下参数化场景会把 21 单版本场景带到别的 JDK 夹具目录。
		AnonClassReproTest.FIXTURE_JDK_MAJOR = 21;
		AnnotationTransformer.pendingAlignedClasses.clear();
		HotSwapAgent.bytecodeCache.clear();
	}

	@AfterEach
	void restoreStatics() {
		AnonClassAligner.TEST_REVERSE_ORDER = (Boolean) snap[0];
		AnonClassAligner.MAX_ANON_PER_HOST = (Integer) snap[1];
		AnonClassAligner.ALIGN_TIMEOUT_MS   = (Long)    snap[2];
		HotSwapAgent.ANON_STRICT            = (Boolean) snap[3];
		HotSwapAgent.ANON_LAYOUT_GATE       = (String)  snap[4];
		HotSwapAgent.ANON_DEBUG             = (Boolean) snap[5];
		AnonClassReproTest.FIXTURE_JDK_MAJOR = (Integer) snap[6];
		AnnotationTransformer.pendingAlignedClasses.clear();
		HotSwapAgent.bytecodeCache.clear();
	}

	private void expect(int pass) { expect(pass, 0); }

	/** 校验通过条数 + KNOWN 条数：KNOWN 被修好（多出/少掉）时必须变红，逼人更新。 */
	private void expect(int pass, int known) {
		assertEquals(List.of(), AnonClassReproTest.failures, "失败的断言");
		assertEquals(pass,  AnonClassReproTest.passed, "通过条数");
		assertEquals(known, AnonClassReproTest.known,  "KNOWN 条数");
	}

	/** 解析并校验某个主版本的真实 javac；缺失或版本不符直接失败（不跳过）。 */
	private static String javac(int major) throws Exception {
		if (JAVAC.containsKey(major)) return JAVAC.get(major);
		String p = System.getProperty("hstest.javac" + major);
		if (p == null) throw new IllegalStateException("缺少 -Dhstest.javac" + major + "（Gradle toolchain 未注入？）");
		String out = runCapturing(p, "-version");
		// 机器上可能设了 JAVA_TOOL_OPTIONS，javac 会先打印一行 "Picked up ..."，取真正的 javac 行。
		String ver = null;
		for (String line : out.split("\\R")) {
			String t = line.trim();
			if (t.startsWith("javac")) { ver = t; break; }
		}
		String want = major == 8 ? "javac 1.8" : "javac " + major;
		assertNotNull(ver, p + " -version 输出里找不到 javac 行：[" + out + "]");
		assertTrue(ver.startsWith(want), p + " 版本是 [" + ver + "]，期望以 [" + want + "] 开头");
		JAVAC.put(major, p);
		return p;
	}

	/** 执行命令并把 stdout+stderr 合并捕获（javac -version 走 stderr）。 */
	private static String runCapturing(String exe, String... args) throws Exception {
		List<String> cmd = new ArrayList<>();
		cmd.add(exe);
		cmd.addAll(Arrays.asList(args));
		Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		StringBuilder sb = new StringBuilder();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) sb.append(line).append('\n');
		}
		proc.waitFor();
		return sb.toString().trim();
	}

	// ---------------- 27 场景（条数与 suite.sh 逐项一致）----------------

	@Test void s01_insertInFront() throws Exception {
		AnonClassReproTest.testScenario1_InsertInFront(javac(21), base.toFile()); expect(11);
	}

	@Test void s02_swapPositions() throws Exception {
		AnonClassReproTest.testScenario2_SwapPositions(javac(21), base.toFile()); expect(6);
	}

	@Test void s03_modifyBody() throws Exception {
		AnonClassReproTest.testScenario3_ModifyBody(javac(21), base.toFile()); expect(3);
	}

	@Test void s04_deleteClass() throws Exception {
		AnonClassReproTest.testScenario4_DeleteClass(javac(21), base.toFile()); expect(4);
	}

	@Test void s05_deleteAndReAdd() throws Exception {
		AnonClassReproTest.testScenario5_DeleteAndReAdd(javac(21), base.toFile()); expect(3);
	}

	@Test void s06_unloadedNewClass() throws Exception {
		AnonClassReproTest.testScenario6_UnloadedNewClass(javac(21), base.toFile()); expect(3);
	}

	@Test void s07_identityAndIdempotence() throws Exception {
		AnonClassReproTest.testScenario7_IdentityAndIdempotence(javac(21), base.toFile()); expect(12);
	}

	@Test void s08_orderIndependenceReverseHook() throws Exception {
		AnonClassReproTest.testScenario8_OrderIndependenceWithReverseHook(javac(21), base.toFile()); expect(3);
	}

	@Test void s09_lambdaEnclosingMethodUnified() throws Exception {
		AnonClassReproTest.testScenario9_LambdaEnclosingMethodUnified(javac(21), base.toFile()); expect(3);
	}

	@Test void s10_anonWithInnerLambda() throws Exception {
		AnonClassReproTest.testScenario10_AnonymousClassWithInnerLambda(javac(21), base.toFile()); expect(3);
	}

	@Test void s11_swapWithUnloadedClass() throws Exception {
		AnonClassReproTest.testScenario11_SwapWithUnloadedClass(javac(21), base.toFile()); expect(7);
	}

	@Test void s12_javac8NestedLambdaNullEnclosingMethod() throws Exception {
		AnonClassReproTest.testScenario12_Javac8NestedLambdaNullEnclosingMethod(javac(8), base.toFile()); expect(3);
	}

	@Test void s13_combinedShiftAndHasherTableHit() throws Exception {
		AnonClassReproTest.testScenario13_CombinedShiftAndHasherTableHit(javac(21), base.toFile()); expect(11);
	}

	@Test void s14_nestedAnonymousClasses() throws Exception {
		AnonClassReproTest.testScenario14_NestedAnonymousClasses(javac(21), base.toFile()); expect(4);
	}

	@Test void s15_transactionRollbackAndPinning() throws Exception {
		AnonClassReproTest.testScenario15_TransactionRollbackAndPinning(javac(21), base.toFile()); expect(7);
	}

	@Test void s16_bidirectionalMatchingAndOrderIndependence() throws Exception {
		AnonClassReproTest.testScenario16_BidirectionalMatchingAndOrderIndependence(javac(21), base.toFile()); expect(3);
	}

	@Test void s17_callerTracingOwnerValidation() throws Exception {
		AnonClassReproTest.testScenario17_CallerTracingOwnerValidation(javac(21), base.toFile()); expect(1);
	}

	@Test void s18_nestedPrefixRetention() throws Exception {
		AnonClassReproTest.testScenario18_NestedAnonymousClassPrefixRetention(javac(21), base.toFile()); expect(3);
	}

	/**
	 * 版本敏感的场景（s19/s22/s26）在 **8/11/17/21 四个 JDK** 上各跑一次：夹具由传入的 javac
	 * 现编，且 {@code FIXTURE_JDK_MAJOR} 同步为参数值，使夹具目录带 JDK 维度。其余场景仍单跑
	 * javac 21（只参数化受 JDK 生成策略影响的场景，以控时）。
	 */
	@ParameterizedTest(name = "jdk{0}")
	@ValueSource(ints = { 8, 11, 17, 21 })
	void s19_cascadingTreeAndMetamorphicSuite(int jdk) throws Exception {
		AnonClassReproTest.FIXTURE_JDK_MAJOR = jdk;
		AnonClassReproTest.testScenario19_CascadingTreeAndMetamorphicSuite(javac(jdk), base.toFile()); expect(11);
	}

	@Test void s20_whiteboxVulnerabilityReproAndDefense() throws Exception {
		AnonClassReproTest.testScenario20_WhiteboxVulnerabilityReproAndDefense(javac(21), base.toFile()); expect(7);
	}

	@Test void s21_safetyGatesAndCircuitBreaker() throws Exception {
		AnonClassReproTest.testScenario21_SafetyGatesAndCircuitBreaker(javac(21), base.toFile()); expect(9);
	}

	@ParameterizedTest(name = "jdk{0}")
	@ValueSource(ints = { 8, 11, 17, 21 })
	void s22_nestedContentHashAvailability(int jdk) throws Exception {
		AnonClassReproTest.FIXTURE_JDK_MAJOR = jdk;
		AnonClassReproTest.testScenario22_NestedContentHashAvailability(javac(jdk), base.toFile()); expect(12);
	}

	@Test void s23_strictIsSafetyGateNotPolicy() throws Exception {
		AnonClassReproTest.testScenario23_StrictIsSafetyGateNotPolicy(javac(21), base.toFile()); expect(13);
	}

	@Test void s24_javac8ParentScopedFallback() throws Exception {
		AnonClassReproTest.testScenario24_Javac8ParentScopedFallback(javac(8), base.toFile()); expect(6);
	}

	@Test void s25_designInvariants() throws Exception {
		AnonClassReproTest.testScenario25_DesignInvariants(javac(21), base.toFile()); expect(6);
	}

	@ParameterizedTest(name = "jdk{0}")
	@ValueSource(ints = { 8, 11, 17, 21 })
	void s26_tier3TopologyFilter(int jdk) throws Exception {
		AnonClassReproTest.FIXTURE_JDK_MAJOR = jdk;
		AnonClassReproTest.testScenario26_Tier3TopologyFilter(javac(jdk), base.toFile()); expect(19, 1);
	}

	@Test void s27_layoutGateForAnonClasses() throws Exception {
		AnonClassReproTest.testScenario27_LayoutGateForAnonClasses(javac(21), base.toFile()); expect(20);
	}
}
