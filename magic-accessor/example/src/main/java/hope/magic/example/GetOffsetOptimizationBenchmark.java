package hope.magic.example;

import hope.magic.js.runtime.JSShape;
import hope.magic.js.runtime.SymbolTable;
import hope.magic.runtime.BootStableHolder;
import hope.magic.runtime.LinkerHelper;
import hope.magic.runtime.Magic;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import sun.misc.Unsafe;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {
	"-XX:+UnlockDiagnosticVMOptions"
})
@SuppressWarnings("removal")
public class GetOffsetOptimizationBenchmark {

	private static final Unsafe UNSAFE = Magic.unsafe;
	private static long K0_OFFSET;
	private static long K2_OFFSET;

	private static final int PATTERN_SIZE = 1024;
	private final int[] randomSlotProps = new int[PATTERN_SIZE];
	private final int[] missingProps = new int[PATTERN_SIZE];
	private final int[] mixedProps = new int[PATTERN_SIZE];

	private JSShape testShape4; // 包含 4 个属性 (k0..k3)
	private JSShape testShape8; // 包含 8 个属性 (k0..k3 + 4 overflow)
	private long shape4Mask;
	private long shape8Mask;

	private int counter = 0;

	private static int scanOverflow(int[] of, int propId) {
		if (of == null) return -1;
		for (int i = of.length - 1; i >= 0; i--) {
			if (of[i] == propId) return i + 4;
		}
		return -1;
	}

	@Setup(Level.Trial)
	public void setup() throws Throwable {
		if (!Magic.isInstalled()) Magic.install();

		K0_OFFSET = LinkerHelper.getFieldOffset(JSShape.class, "k0");
		K2_OFFSET = LinkerHelper.getFieldOffset(JSShape.class, "k2");

		int p0 = SymbolTable.symbolId("p0");
		int p1 = SymbolTable.symbolId("p1");
		int p2 = SymbolTable.symbolId("p2");
		int p3 = SymbolTable.symbolId("p3");
		int p4 = SymbolTable.symbolId("p4");
		int p5 = SymbolTable.symbolId("p5");
		int p6 = SymbolTable.symbolId("p6");
		int p7 = SymbolTable.symbolId("p7");

		testShape4 = JSShape.ROOT
			.addProperty(p0, JSShape.TYPE_INT)
			.addProperty(p1, JSShape.TYPE_DOUBLE)
			.addProperty(p2, JSShape.TYPE_OBJECT)
			.addProperty(p3, JSShape.TYPE_INT);

		testShape8 = testShape4
			.addProperty(p4, JSShape.TYPE_DOUBLE)
			.addProperty(p5, JSShape.TYPE_INT)
			.addProperty(p6, JSShape.TYPE_OBJECT)
			.addProperty(p7, JSShape.TYPE_INT);

		shape4Mask = (1L << (p0 & 63)) | (1L << (p1 & 63)) | (1L << (p2 & 63)) | (1L << (p3 & 63));
		shape8Mask = shape4Mask | (1L << (p4 & 63)) | (1L << (p5 & 63)) | (1L << (p6 & 63)) | (1L << (p7 & 63));

		int[] pList = { p0, p1, p2, p3 };
		Random rnd = new Random(42);
		for (int i = 0; i < PATTERN_SIZE; i++) {
			randomSlotProps[i] = pList[rnd.nextInt(4)];
			missingProps[i] = SymbolTable.symbolId("missing_" + i);
			// 80% 命中，20% 不存在
			mixedProps[i] = (rnd.nextInt(10) < 8) ? pList[rnd.nextInt(4)] : missingProps[i];
		}
	}

	// =========================================================================
	// 5 种算法对比实现
	// =========================================================================

	// 1. 基线：当前代码（阶梯分拆 getOffset -> getOffsetRest）
	public static int getOffset_1_Tiered(JSShape s, int propId) {
		return s.getOffset(propId);
	}

	// 2. 扁平 4 路 if (单方法完整展开，无额外 rest 调用)
	public static int getOffset_2_Flat(JSShape s, int propId) {
		if (s.k0 == propId) return 0;
		if (s.k1 == propId) return 1;
		if (s.k2 == propId) return 2;
		if (s.k3 == propId) return 3;
		int[] of = s.overflowKeys;
		return of == null ? -1 : scanOverflow(of, propId);
	}

	// 3. 属性计数守卫 (propertyCount <= 4 时直接返回 -1，免读 overflowKeys)
	public static int getOffset_3_CountGuarded(JSShape s, int propId) {
		if (s.k0 == propId) return 0;
		if (s.k1 == propId) return 1;
		if (s.k2 == propId) return 2;
		if (s.k3 == propId) return 3;
		if (s.propertyCount <= 4) return -1;
		return scanOverflow(s.overflowKeys, propId);
	}

	// 4. 无分支三元表达式 (C2 生成 cmov)
	public static int getOffset_4_Branchless(JSShape s, int propId) {
		int off = (s.k0 == propId) ? 0 :
				  (s.k1 == propId) ? 1 :
				  (s.k2 == propId) ? 2 :
				  (s.k3 == propId) ? 3 : -1;
		if (off >= 0) return off;
		return s.propertyCount <= 4 ? -1 : scanOverflow(s.overflowKeys, propId);
	}

	// 5. Unsafe 双 Long 加载 (每次加载 2 个 int)
	public static int getOffset_5_Unsafe2Long(JSShape s, int propId) {
		long k01 = UNSAFE.getLong(s, K0_OFFSET);
		if ((int) k01 == propId) return 0;
		if ((int) (k01 >>> 32) == propId) return 1;
		long k23 = UNSAFE.getLong(s, K2_OFFSET);
		if ((int) k23 == propId) return 2;
		if ((int) (k23 >>> 32) == propId) return 3;
		return s.propertyCount <= 4 ? -1 : scanOverflow(s.overflowKeys, propId);
	}

	// 6. 位掩码快速过滤 (Bloom Filter 零开销排除未命中)
	public static int getOffset_6_Bloom(JSShape s, int propId, long mask) {
		if ((mask & (1L << (propId & 63))) == 0) return -1;
		if (s.k0 == propId) return 0;
		if (s.k1 == propId) return 1;
		if (s.k2 == propId) return 2;
		if (s.k3 == propId) return 3;
		return s.propertyCount <= 4 ? -1 : scanOverflow(s.overflowKeys, propId);
	}

	// =========================================================================
	// 测试套件：场景 A - 混合 4 槽位随机访问 (模拟分支乱序)
	// =========================================================================

	@Benchmark
	public void a_random_hit_1_tiered(Blackhole bh) {
		int prop = randomSlotProps[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(getOffset_1_Tiered(testShape4, prop));
	}

	@Benchmark
	public void a_random_hit_2_flat(Blackhole bh) {
		int prop = randomSlotProps[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(getOffset_2_Flat(testShape4, prop));
	}

	@Benchmark
	public void a_random_hit_3_count_guarded(Blackhole bh) {
		int prop = randomSlotProps[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(getOffset_3_CountGuarded(testShape4, prop));
	}

	@Benchmark
	public void a_random_hit_4_branchless(Blackhole bh) {
		int prop = randomSlotProps[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(getOffset_4_Branchless(testShape4, prop));
	}

	@Benchmark
	public void a_random_hit_5_unsafe_long(Blackhole bh) {
		int prop = randomSlotProps[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(getOffset_5_Unsafe2Long(testShape4, prop));
	}

	// =========================================================================
	// 测试套件：场景 B - 属性不存在 (查找原型链前置检查，测试排除效率)
	// =========================================================================

	@Benchmark
	public void b_missing_1_tiered(Blackhole bh) {
		int prop = missingProps[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(getOffset_1_Tiered(testShape4, prop));
	}

	@Benchmark
	public void b_missing_2_flat(Blackhole bh) {
		int prop = missingProps[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(getOffset_2_Flat(testShape4, prop));
	}

	@Benchmark
	public void b_missing_3_count_guarded(Blackhole bh) {
		int prop = missingProps[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(getOffset_3_CountGuarded(testShape4, prop));
	}

	@Benchmark
	public void b_missing_6_bloom(Blackhole bh) {
		int prop = missingProps[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(getOffset_6_Bloom(testShape4, prop, shape4Mask));
	}

	public static void main(String[] args) throws Exception {
		Options opt = new OptionsBuilder()
			.include(GetOffsetOptimizationBenchmark.class.getSimpleName())
			.build();
		new Runner(opt).run();
	}
}
