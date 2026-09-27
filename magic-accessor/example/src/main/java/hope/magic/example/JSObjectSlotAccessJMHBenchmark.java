package hope.magic.example;

import hope.magic.js.runtime.JSObject;
import hope.magic.runtime.Magic;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.*;
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
public class JSObjectSlotAccessJMHBenchmark {

	private static final Unsafe UNSAFE = Magic.unsafe;

	private JSObject jsObj;
	private int counter = 0;

	private static final int PATTERN_SIZE = 1024;
	private final int[] alternatingOffsets = new int[PATTERN_SIZE];
	private final int[] chaoticOffsets = new int[PATTERN_SIZE];
	private final int[] skewedOffsets = new int[PATTERN_SIZE];

	@Setup(Level.Trial)
	public void setup() throws Throwable {
		if (!Magic.isInstalled()) Magic.install();

		jsObj = new JSObject();
		// 初始化 0..7 内置槽位
		for (int i = 0; i < 8; i++) {
			jsObj.setDoubleSlot(i, (double) (i * 10 + 1));
		}

		// 初始化 8..15 溢出槽位 (overflowPrim)
		for (int i = 8; i < 16; i++) {
			jsObj.setDoubleSlot(i, (double) (i * 10 + 1));
		}

		// 构造分支预测压力与访问分布数据
		Random rnd = new Random(42);
		for (int i = 0; i < PATTERN_SIZE; i++) {
			// 1. 严格 50/50 颠簸交替：in-object(0..7) 与 overflow(8..15) 每次交替翻转
			alternatingOffsets[i] = (i & 1) == 0 ? (i & 7) : (8 + (i & 7));
			// 2. 纯伪随机散列（0..15）：彻底击溃 CPU 分支预测历史表 (TAGE/BTB)
			chaoticOffsets[i] = rnd.nextInt(16);
			// 3. 真实 80/20 偏斜分布：80% 访问集中在 slot 0 和 1（JSShape 设计主场），20% 均匀访问 slot 2..7
			if (rnd.nextInt(100) < 80) {
				skewedOffsets[i] = rnd.nextInt(2);
			} else {
				skewedOffsets[i] = 2 + rnd.nextInt(6);
			}
		}
	}

	// =========================================================================
	// 三大技术架构对比实现
	// =========================================================================

	/** 方案 1：经典 8 路 tableswitch 方案（改动前原版实现） */
	public static double getDouble_Switch(JSObject obj, int offset) {
		return switch (offset) {
			case 0 -> Double.longBitsToDouble(obj.prim0);
			case 1 -> Double.longBitsToDouble(obj.prim1);
			case 2 -> Double.longBitsToDouble(obj.prim2);
			case 3 -> Double.longBitsToDouble(obj.prim3);
			case 4 -> Double.longBitsToDouble(obj.prim4);
			case 5 -> Double.longBitsToDouble(obj.prim5);
			case 6 -> Double.longBitsToDouble(obj.prim6);
			case 7 -> Double.longBitsToDouble(obj.prim7);
			default -> obj.getDoubleSlot(offset);
		};
	}

	/** 方案 2：JSShape 分层级联 if 方案（JSShape Tiered-If 风格，每次判 2 个槽位并下沉 Rest） */
	public static double getDouble_JSShape(JSObject obj, int offset) {
		if (offset == 0) return Double.longBitsToDouble(obj.prim0);
		if (offset == 1) return Double.longBitsToDouble(obj.prim1);
		return getDouble_JSShape_Rest1(obj, offset);
	}

	private static double getDouble_JSShape_Rest1(JSObject obj, int offset) {
		if (offset == 2) return Double.longBitsToDouble(obj.prim2);
		if (offset == 3) return Double.longBitsToDouble(obj.prim3);
		return getDouble_JSShape_Rest2(obj, offset);
	}

	private static double getDouble_JSShape_Rest2(JSObject obj, int offset) {
		if (offset == 4) return Double.longBitsToDouble(obj.prim4);
		if (offset == 5) return Double.longBitsToDouble(obj.prim5);
		return getDouble_JSShape_Rest3(obj, offset);
	}

	private static double getDouble_JSShape_Rest3(JSObject obj, int offset) {
		if (offset == 6) return Double.longBitsToDouble(obj.prim6);
		if (offset == 7) return Double.longBitsToDouble(obj.prim7);
		return obj.getDoubleSlot(offset);
	}

	/** 方案 3：Unsafe + @Stable 数组方案（当前生产落地实现） */
	public static double getDouble_Unsafe(JSObject obj, int offset) {
		return obj.getDoubleSlot(offset);
	}

	// =========================================================================
	// 场景一：常量槽位读取（测试 JSShape 首位直接命中 vs 末位穿透深层调用的差异）
	// =========================================================================

	@Benchmark
	public void c1_const_slot0_switch(Blackhole bh) {
		bh.consume(getDouble_Switch(jsObj, 0));
	}

	@Benchmark
	public void c1_const_slot0_jsshape(Blackhole bh) {
		bh.consume(getDouble_JSShape(jsObj, 0));
	}

	@Benchmark
	public void c1_const_slot0_unsafe(Blackhole bh) {
		bh.consume(getDouble_Unsafe(jsObj, 0));
	}

	@Benchmark
	public void c2_const_slot7_switch(Blackhole bh) {
		bh.consume(getDouble_Switch(jsObj, 7));
	}

	@Benchmark
	public void c2_const_slot7_jsshape(Blackhole bh) {
		bh.consume(getDouble_JSShape(jsObj, 7));
	}

	@Benchmark
	public void c2_const_slot7_unsafe(Blackhole bh) {
		bh.consume(getDouble_Unsafe(jsObj, 7));
	}

	// =========================================================================
	// 场景二：动态平权槽位轮换（0..7 均匀动态分布，测试通用动态寻址开销）
	// =========================================================================

	@Benchmark
	public void d1_uniform_dynamic_1_switch(Blackhole bh) {
		int idx = counter++ & 7;
		bh.consume(getDouble_Switch(jsObj, idx));
	}

	@Benchmark
	public void d1_uniform_dynamic_2_jsshape(Blackhole bh) {
		int idx = counter++ & 7;
		bh.consume(getDouble_JSShape(jsObj, idx));
	}

	@Benchmark
	public void d1_uniform_dynamic_3_unsafe(Blackhole bh) {
		int idx = counter++ & 7;
		bh.consume(getDouble_Unsafe(jsObj, idx));
	}

	// =========================================================================
	// 场景三：真实 80/20 偏斜访问（80% 访问 slot 0/1，JSShape 的理论主场）
	// =========================================================================

	@Benchmark
	public void s1_skewed_80_20_1_switch(Blackhole bh) {
		int idx = skewedOffsets[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(getDouble_Switch(jsObj, idx));
	}

	@Benchmark
	public void s1_skewed_80_20_2_jsshape(Blackhole bh) {
		int idx = skewedOffsets[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(getDouble_JSShape(jsObj, idx));
	}

	@Benchmark
	public void s1_skewed_80_20_3_unsafe(Blackhole bh) {
		int idx = skewedOffsets[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(getDouble_Unsafe(jsObj, idx));
	}

	// =========================================================================
	// 场景四：极限分支预测压力测试（0..15 纯随机散列，击溃 BTB）
	// =========================================================================

	@Benchmark
	public void m1_chaotic_1_switch(Blackhole bh) {
		int slot = chaoticOffsets[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(getDouble_Switch(jsObj, slot));
	}

	@Benchmark
	public void m1_chaotic_2_jsshape(Blackhole bh) {
		int slot = chaoticOffsets[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(getDouble_JSShape(jsObj, slot));
	}

	@Benchmark
	public void m1_chaotic_3_unsafe(Blackhole bh) {
		int slot = chaoticOffsets[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(getDouble_Unsafe(jsObj, slot));
	}

	public static void main(String[] args) throws Exception {
		Options opt = new OptionsBuilder()
			.include(JSObjectSlotAccessJMHBenchmark.class.getSimpleName())
			.build();
		new Runner(opt).run();
	}
}
