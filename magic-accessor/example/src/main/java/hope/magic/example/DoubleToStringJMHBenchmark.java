package hope.magic.example;

import hope.magic.js.runtime.doubleconv.DoubleConversion;
import hope.magic.runtime.Magic;
import hope.magic.runtime.Schubfach;
import jdk.internal.math.DoubleToDecimal;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 浮点转十进制字符串（Float/Double to Decimal String）JMH 基准测试。
 * <p>全面量化对比：
 * <ul>
 *   <li>1. OpenJDK 官方内置 {@link Double#toString(double)}（JDK 25 公共基线）</li>
 *   <li>2. JDK 内部原生 {@link DoubleToDecimal#LATIN1}（JDK 内部 0-Alloc 直写字节数组）</li>
 *   <li>3. 引擎优化版 {@link DoubleConversion#toShortestString(double)}（Schubfach + 2-Digit LUT + 跳跃尾随零剥离）</li>
 *   <li>4. 引擎零堆分配直写路径 {@link DoubleConversion#appendTo(StringBuilder, double)}（0-Alloc）</li>
 *   <li>5. 底层标量核心 {@link Schubfach#toDecimal(double, char[])}（纯寄存器 / 原语缓冲解码开销）</li>
 * </ul>
 * 覆盖场景：1024 混合随机数据分布、纯整数快速路径、常规小数、极小次正规数。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3, jvmArgsAppend = {
	"-XX:+UnlockDiagnosticVMOptions",
	"--add-exports=java.base/jdk.internal.math=ALL-UNNAMED",
	"--add-opens=java.base/jdk.internal.math=ALL-UNNAMED"
})
public class DoubleToStringJMHBenchmark {

	private static final int PATTERN_SIZE = 1024;
	private final double[] randomDoubles = new double[PATTERN_SIZE];
	private final double[] integerDoubles = new double[PATTERN_SIZE];
	private final double[] decimalDoubles = new double[PATTERN_SIZE];
	private final double[] subnormalDoubles = new double[PATTERN_SIZE];

	private final StringBuilder sharedSb = new StringBuilder(64);
	private final char[] rawCharBuffer = new char[32];
	private final byte[] jdkByteBuffer = new byte[32];
	private int counter = 0;

	@Setup(Level.Trial)
	public void setup() throws Throwable {
		if (!Magic.isInstalled()) Magic.install();

		Random rnd = new Random(42);
		for (int i = 0; i < PATTERN_SIZE; i++) {
			// 1. 纯整数浮点数 (1.0 .. 1000000.0) -> 测试 Integer Fast Path
			integerDoubles[i] = (double) (rnd.nextInt(1_000_000) + 1);

			// 2. 普通典型小数 (0.1, 3.14159, 123.456...)
			decimalDoubles[i] = rnd.nextDouble() * 1000.0;

			// 3. 次正规数 (Subnormals)
			subnormalDoubles[i] = Double.MIN_VALUE * (rnd.nextInt(100) + 1);

			// 4. 真实混合分布 (正数、负数、极大极小值、整数与小数)
			double v;
			int type = rnd.nextInt(4);
			switch (type) {
				case 0 -> v = (double) rnd.nextInt(100_000);
				case 1 -> v = rnd.nextDouble() * 100.0;
				case 2 -> v = rnd.nextDouble() * 1e20;
				default -> v = rnd.nextDouble() * 1e-15;
			}
			randomDoubles[i] = (rnd.nextBoolean() ? v : -v);
		}
	}

	// =========================================================================
	// 场景 1：混合真实数据分布 (Mixed Random Doubles)
	// =========================================================================

	@Benchmark
	public void c1_mixed_1_jdk_doubleToString(Blackhole bh) {
		double v = randomDoubles[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(Double.toString(v));
	}

	@Benchmark
	public void c1_mixed_2_jdk_doubleToDecimal_putDecimal(Blackhole bh) {
		double v = randomDoubles[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(DoubleToDecimal.LATIN1.putDecimal(jdkByteBuffer, 0, v));
	}

	@Benchmark
	public void c1_mixed_3_engine_toShortestString(Blackhole bh) {
		double v = randomDoubles[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(DoubleConversion.toShortestString(v));
	}

	@Benchmark
	public void c1_mixed_4_engine_appendTo_zeroAlloc(Blackhole bh) {
		double v = randomDoubles[counter++ & (PATTERN_SIZE - 1)];
		sharedSb.setLength(0);
		DoubleConversion.appendTo(sharedSb, v);
		bh.consume(sharedSb.length());
	}

	@Benchmark
	public void c1_mixed_5_core_schubfachRaw(Blackhole bh) {
		double v = randomDoubles[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(Schubfach.toDecimal(Math.abs(v), rawCharBuffer));
	}

	// =========================================================================
	// 场景 2：整数浮点数快速路径 (Integer Fast Path: 1.0, 42.0, 100000.0)
	// =========================================================================

	@Benchmark
	public void c2_int_1_jdk_doubleToString(Blackhole bh) {
		double v = integerDoubles[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(Double.toString(v));
	}

	@Benchmark
	public void c2_int_2_jdk_doubleToDecimal_putDecimal(Blackhole bh) {
		double v = integerDoubles[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(DoubleToDecimal.LATIN1.putDecimal(jdkByteBuffer, 0, v));
	}

	@Benchmark
	public void c2_int_3_engine_toShortestString(Blackhole bh) {
		double v = integerDoubles[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(DoubleConversion.toShortestString(v));
	}

	@Benchmark
	public void c2_int_4_engine_appendTo_zeroAlloc(Blackhole bh) {
		double v = integerDoubles[counter++ & (PATTERN_SIZE - 1)];
		sharedSb.setLength(0);
		DoubleConversion.appendTo(sharedSb, v);
		bh.consume(sharedSb.length());
	}

	// =========================================================================
	// 场景 3：常规小数值 (Standard Decimals: 3.14159, 123.456)
	// =========================================================================

	@Benchmark
	public void c3_decimal_1_jdk_doubleToString(Blackhole bh) {
		double v = decimalDoubles[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(Double.toString(v));
	}

	@Benchmark
	public void c3_decimal_2_jdk_doubleToDecimal_putDecimal(Blackhole bh) {
		double v = decimalDoubles[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(DoubleToDecimal.LATIN1.putDecimal(jdkByteBuffer, 0, v));
	}

	@Benchmark
	public void c3_decimal_3_engine_toShortestString(Blackhole bh) {
		double v = decimalDoubles[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(DoubleConversion.toShortestString(v));
	}

	@Benchmark
	public void c3_decimal_4_engine_appendTo_zeroAlloc(Blackhole bh) {
		double v = decimalDoubles[counter++ & (PATTERN_SIZE - 1)];
		sharedSb.setLength(0);
		DoubleConversion.appendTo(sharedSb, v);
		bh.consume(sharedSb.length());
	}

	// =========================================================================
	// 场景 4：极小次正规数 (Subnormals: Double.MIN_VALUE ..)
	// =========================================================================

	@Benchmark
	public void c4_subnormal_1_jdk_doubleToString(Blackhole bh) {
		double v = subnormalDoubles[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(Double.toString(v));
	}

	@Benchmark
	public void c4_subnormal_2_jdk_doubleToDecimal_putDecimal(Blackhole bh) {
		double v = subnormalDoubles[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(DoubleToDecimal.LATIN1.putDecimal(jdkByteBuffer, 0, v));
	}

	@Benchmark
	public void c4_subnormal_3_engine_toShortestString(Blackhole bh) {
		double v = subnormalDoubles[counter++ & (PATTERN_SIZE - 1)];
		bh.consume(DoubleConversion.toShortestString(v));
	}

	@Benchmark
	public void c4_subnormal_4_engine_appendTo_zeroAlloc(Blackhole bh) {
		double v = subnormalDoubles[counter++ & (PATTERN_SIZE - 1)];
		sharedSb.setLength(0);
		DoubleConversion.appendTo(sharedSb, v);
		bh.consume(sharedSb.length());
	}

	public static void main(String[] args) throws Exception {
		Options opt = new OptionsBuilder()
			.include(DoubleToStringJMHBenchmark.class.getSimpleName())
			.build();
		new Runner(opt).run();
	}
}
