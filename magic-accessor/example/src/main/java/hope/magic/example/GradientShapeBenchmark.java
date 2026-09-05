package hope.magic.example;

import hope.magic.js.compiler.JSCompiler;
import hope.magic.js.runtime.*;
import org.graalvm.polyglot.*;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * 梯度多态基准测试 (Gradient Shape Benchmark)
 * 评测 Shape 数在 1 / 2 / 4 / 8 / 64 梯度下，MagicJS vs GraalJS 的单次访问性能演进曲线。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Thread)
public class GradientShapeBenchmark {

	@Param({"1", "2", "4", "8", "64"})
	private int shapes;

	private JSContext magicContext;
	private Context   graalContext;

	private JSScript magicScript;
	private Source   graalScript;

	@Setup(Level.Trial)
	public void setup() throws Throwable {
		magicContext = new JSContext();
		graalContext = Context.newBuilder("js")
				.allowAllAccess(true)
				.build();
		graalContext.initialize("js");

		// 1. 初始化数据：生成对应 shapes 的扁平化测试数据 (长度 2000)
		String initCode = generateSetupCode(shapes);
		Object magicData = JSCompiler.compile(initCode).run(magicContext);
		magicContext.set("test_data_" + shapes, magicData);

		Value graalData = graalContext.eval(Source.newBuilder("js", initCode, "init_" + shapes + ".js").build());
		graalContext.getBindings("js").putMember("test_data_" + shapes, graalData);

		// 2. 编译纯访问脚本 (仅做 2000 次 .val 属性读取)
		String accessCode = generatePureAccessCode(shapes);
		magicScript = JSCompiler.compile(accessCode);
		graalScript = Source.newBuilder("js", accessCode, "pure_s" + shapes + ".js")
				.cached(true)
				.build();

		// 3. 初始正确性校验 (只运行 1 次做验证，严禁在此处做耗时上万次的预热循环)
		verifyOnce("Setup-ColdCheck");
	}

	@TearDown(Level.Trial)
	public void tearDown() throws Throwable {
		try {
			verifyOnce("TearDown-FinalCheck");
		} finally {
			if (graalContext != null) {
				graalContext.close();
			}
		}
	}

	private void verifyOnce(String phase) throws Throwable {
		double expected = getExpectedSum(shapes);
		Object mRes = magicScript.run(magicContext);
		double gRes = graalContext.eval(graalScript).asDouble();
		verifyResult(phase + " Shape-" + shapes, mRes, gRes, expected);
	}

	private static double getExpectedSum(int numShapes) {
		return switch (numShapes) {
			case 1 -> 21000.0;
			case 2 -> 22000.0;
			case 4 -> 24000.0;
			case 8 -> 28000.0;
			case 64 -> 83616.0;
			default -> throw new IllegalArgumentException("Unknown shape: " + numShapes);
		};
	}

	private static void verifyResult(String label, Object magicResult, double graalVal, double expectedVal) {
		double magicVal = ((Number) magicResult).doubleValue();
		if (Double.isNaN(magicVal) || Double.isInfinite(magicVal)) {
			throw new IllegalStateException("[" + label + "] MagicJS produced invalid number: " + magicResult);
		}
		if (Math.abs(magicVal - expectedVal) > 1e-6) {
			throw new AssertionError(String.format(
				"[%s] MagicJS result mismatch! Expected: %f, Actual: %f", label, expectedVal, magicVal
			));
		}
		if (Math.abs(graalVal - expectedVal) > 1e-6) {
			throw new AssertionError(String.format(
				"[%s] GraalJS result mismatch! Expected: %f, Actual: %f", label, expectedVal, graalVal
			));
		}
	}

	// ---------------------- 核心测试方法 ----------------------

	@Benchmark
	public Object test_magic() throws Throwable {
		return magicScript.run(magicContext);
	}

	@Benchmark
	public Object test_graal() {
		return graalContext.eval(graalScript);
	}

	// ---------------------- 代码生成辅助 ----------------------

	public static String generateSetupCode(int numShapes) {
		StringBuilder sb = new StringBuilder();
		sb.append("pool_").append(numShapes).append(" = [\n");
		for (int i = 0; i < numShapes; i++) {
			sb.append("    { ");
			for (int p = 0; p < (i % 4); p++) {
				sb.append("dummy_").append(p).append(": 0, ");
			}
			sb.append("val: ").append(10.5 + i)
			  .append(", prop_").append(i).append(": ").append(i * 10)
			  .append(" }");
			if (i < numShapes - 1) sb.append(",\n");
		}
		sb.append("\n];\n\n");

		sb.append("test_data_").append(numShapes).append(" = [];\n");
		sb.append("for (var i = 0; i < 2000; i++) {\n");
		sb.append("    test_data_").append(numShapes).append("[i] = pool_").append(numShapes).append("[i % ").append(numShapes).append("];\n");
		sb.append("}\n");
		sb.append("test_data_").append(numShapes).append(";\n");
		return sb.toString();
	}

	public static String generatePureAccessCode(int numShapes) {
		return "var data = test_data_" + numShapes + ";\n" +
		       "var total = 0;\n" +
		       "for (var i = 0; i < 2000; i++) {\n" +
		       "    var item = data[i];\n" +
		       "    total = total + item.val;\n" +
		       "}\n" +
		       "total;\n";
	}

	// ---------------------- 独立调试 Main ----------------------

	public static record StatResult(double meanUs, double stdDevUs, double p50Us, double minUs, double maxUs) { }

	public static StatResult measureStats(Runnable task, int warmupRuns, int batchCount, int runsPerBatch) {
		for (int w = 0; w < warmupRuns; w++) {
			task.run();
		}
		double[] batchMeans = new double[batchCount];
		for (int b = 0; b < batchCount; b++) {
			long t0 = System.nanoTime();
			for (int r = 0; r < runsPerBatch; r++) {
				task.run();
			}
			long elapsed = System.nanoTime() - t0;
			batchMeans[b] = (elapsed / (double) runsPerBatch) / 1000.0;
		}

		double sum = 0;
		double min = Double.MAX_VALUE;
		double max = Double.MIN_VALUE;
		for (double v : batchMeans) {
			sum += v;
			if (v < min) min = v;
			if (v > max) max = v;
		}
		double mean = sum / batchCount;
		double varSum = 0;
		for (double v : batchMeans) {
			varSum += (v - mean) * (v - mean);
		}
		double stdDev = Math.sqrt(varSum / batchCount);
		java.util.Arrays.sort(batchMeans);
		double p50 = batchMeans[batchCount / 2];
		return new StatResult(mean, stdDev, p50, min, max);
	}

	public static void main(String[] args) throws Throwable {
		System.out.println("--------------------------------------------------------------------------------");
		System.out.println("【纯多态属性访问基准 (2000 次 item.val 读取)】[5 次采样批次 x 200 轮稳态]");
		System.out.println("--------------------------------------------------------------------------------");
		System.out.printf("%-14s | %-20s | %-20s | %-16s | %-14s%n",
				"Shape 数量", "MagicJS (Mean ± Std)", "GraalJS (Mean ± Std)", "MagicJS 单次/op", "性能对比");
		System.out.println("--------------------------------------------------------------------------------");

		int[] gradients = {1, 2, 4, 8, 64};
		for (int n : gradients) {
			GradientShapeBenchmark bench = new GradientShapeBenchmark();
			bench.shapes = n;
			bench.setup();

			Runnable magicTask = () -> {
				try {
					bench.test_magic();
				} catch (Throwable t) {
					throw new RuntimeException(t);
				}
			};
			Runnable graalTask = bench::test_graal;

			StatResult mStat = measureStats(magicTask, 200, 5, 200);
			StatResult gStat = measureStats(graalTask, 200, 5, 200);

			double ratio = gStat.meanUs / mStat.meanUs;
			String compareStr = ratio >= 1.0
					? String.format("MagicJS 快 %.2fx", ratio)
					: String.format("GraalJS 快 %.2fx", 1.0 / ratio);

			String shapeLabel = switch (n) {
				case 1 -> "1 (单态)";
				case 2 -> "2 (双态)";
				case 4 -> "4 (多态-4)";
				case 8 -> "8 (多态-8)";
				case 64 -> "64 (巨态-64)";
				default -> String.valueOf(n);
			};

			System.out.printf("%-14s | %8.2f ± %5.2f µs | %8.2f ± %5.2f µs | %10.2f ns/op   | %s%n",
					shapeLabel, mStat.meanUs, mStat.stdDevUs, gStat.meanUs, gStat.stdDevUs, (mStat.meanUs * 1000.0 / 2000.0), compareStr);

			bench.tearDown();
		}
	}
}