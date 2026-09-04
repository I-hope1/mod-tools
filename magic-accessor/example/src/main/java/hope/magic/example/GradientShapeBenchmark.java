package hope.magic.example;

import hope.magic.js.compiler.JSCompiler;
import hope.magic.js.runtime.*;
import org.graalvm.polyglot.*;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * 梯度多态基准测试 (Gradient Shape Benchmark)
 * 评测 Shape 数在 1 / 2 / 4 / 8 / 64 梯度下，MagicJS vs GraalJS 的单次访问性能演进曲线与 Megamorphic 交叉点。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Thread)
public class GradientShapeBenchmark {

	// 梯度 Shape 测试用例与预编译脚本
	private JSContext magicContext;
	private Context   graalContext;

	private JSScript magicScript_1;
	private JSScript magicScript_2;
	private JSScript magicScript_4;
	private JSScript magicScript_8;
	private JSScript magicScript_64;

	private Source graalScript_1;
	private Source graalScript_2;
	private Source graalScript_4;
	private Source graalScript_8;
	private Source graalScript_64;

	@Setup(Level.Trial)
	public void setup() throws Throwable {
		magicContext = new JSContext();
		graalContext = Context.newBuilder("js").allowAllAccess(true).build();
		graalContext.initialize("js");

		// 1. 在 Setup 阶段一次性执行初始化脚本：构造异构对象池并预先展开填充为 2000 长度的扁平数组
		int[] shapes = {1, 2, 4, 8, 64};
		for (int n : shapes) {
			String initCode  = generateSetupCode(n);
			Object magicData = JSCompiler.compile(initCode).run(magicContext);
			magicContext.set("test_data_" + n, magicData);

			Value graalData = graalContext.eval(Source.newBuilder("js", initCode, "init_" + n + ".js").build());
			graalContext.getBindings("js").putMember("test_data_" + n, graalData);
		}

		// 2. 编译纯访问脚本：0 对象构造、0 取模开销，仅对已准备好的扁平数组执行 2000 次 item.val 访问
		String code1  = generatePureAccessCode(1);
		String code2  = generatePureAccessCode(2);
		String code4  = generatePureAccessCode(4);
		String code8  = generatePureAccessCode(8);
		String code64 = generatePureAccessCode(64);

		magicScript_1 = JSCompiler.compile(code1);
		magicScript_2 = JSCompiler.compile(code2);
		magicScript_4 = JSCompiler.compile(code4);
		magicScript_8 = JSCompiler.compile(code8);
		magicScript_64 = JSCompiler.compile(code64);

		graalScript_1 = Source.newBuilder("js", code1, "pure_s1.js").cached(true).build();
		graalScript_2 = Source.newBuilder("js", code2, "pure_s2.js").cached(true).build();
		graalScript_4 = Source.newBuilder("js", code4, "pure_s4.js").cached(true).build();
		graalScript_8 = Source.newBuilder("js", code8, "pure_s8.js").cached(true).build();
		graalScript_64 = Source.newBuilder("js", code64, "pure_s64.js").cached(true).build();

		// 3. 初始冷态验证：确保初次执行时 MagicJS 与 GraalJS 计算结果与理论期望值完全一致
		verifyAll("Initial-Cold");

		// 4. 显式稳态预热：循环触发 JIT 编译并让 CallSite IC 状态机完成单态/多态/巨态跃迁
		for (int i = 0; i < 5000; i++) {
			magicScript_1.run(magicContext);
			magicScript_2.run(magicContext);
			magicScript_4.run(magicContext);
			magicScript_8.run(magicContext);
			magicScript_64.run(magicContext);

			graalContext.eval(graalScript_1);
			graalContext.eval(graalScript_2);
			graalContext.eval(graalScript_4);
			graalContext.eval(graalScript_8);
			graalContext.eval(graalScript_64);
		}

		// 5. 预热后稳态验证：确保 JIT 优化和内联缓存（IC）稳定后，属性访问计算依然 100% 正确
		verifyAll("Post-Warmup");
	}

	@TearDown(Level.Trial)
	public void tearDown() throws Throwable {
		// 6. 基准测试结束后终态验证：确保上百万次高频压测执行后，数据与状态未发生漂移
		verifyAll("Post-Benchmark");

		if (graalContext != null) {
			graalContext.close();
		}
	}

	private void verifyAll(String phase) throws Throwable {
		verifyResult(phase + " Shape-1",  magicScript_1.run(magicContext),  graalContext.eval(graalScript_1).asDouble(), 21000.0);
		verifyResult(phase + " Shape-2",  magicScript_2.run(magicContext),  graalContext.eval(graalScript_2).asDouble(), 22000.0);
		verifyResult(phase + " Shape-4",  magicScript_4.run(magicContext),  graalContext.eval(graalScript_4).asDouble(), 24000.0);
		verifyResult(phase + " Shape-8",  magicScript_8.run(magicContext),  graalContext.eval(graalScript_8).asDouble(), 28000.0);
		verifyResult(phase + " Shape-64", magicScript_64.run(magicContext), graalContext.eval(graalScript_64).asDouble(), 83616.0);
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

	/**
	 * 在 Setup 阶段执行的数据准备代码：
	 * 1. 构造 numShapes 个异构对象（故意改变属性顺序使 val 偏移产生漂移）
	 * 2. 预先填充为长度 2000 的扁平数组 test_data_X，避免循环中重复求余数和动态分配
	 */
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

		// 预先将对象引用按轮询顺序扁平展开为 2000 个元素的数组并返回
		sb.append("test_data_").append(numShapes).append(" = [];\n");
		sb.append("for (var i = 0; i < 2000; i++) {\n");
		sb.append("    test_data_").append(numShapes).append("[i] = pool_").append(numShapes).append("[i % ").append(numShapes).append("];\n");
		sb.append("}\n");
		sb.append("test_data_").append(numShapes).append(";\n");
		return sb.toString();
	}

	/**
	 * 纯多态属性访问测试脚本：
	 * 循环体内仅包含简单的线性数组索引访问和 .val 多态属性读取，无任何对象分配或算术取模
	 */
	public static String generatePureAccessCode(int numShapes) {
		return "var data = test_data_" + numShapes + ";\n" +
		       "var total = 0;\n" +
		       "for (var i = 0; i < 2000; i++) {\n" +
		       "    var item = data[i];\n" +
		       "    total = total + item.val;\n" +
		       "}\n" +
		       "total;\n";
	}

	//region 1. Shape = 1 (Monomorphic 单态)
	@Benchmark
	public Object magic_shape_01() throws Throwable { return magicScript_1.run(magicContext); }
	@Benchmark
	public Object graal_shape_01() { return graalContext.eval(graalScript_1); }
	//endregion

	//region 2. Shape = 2 (Bimorphic 双态)
	@Benchmark
	public Object magic_shape_02() throws Throwable { return magicScript_2.run(magicContext); }
	@Benchmark
	public Object graal_shape_02() { return graalContext.eval(graalScript_2); }
	//endregion

	//region 3. Shape = 4 (Polymorphic-4 多态)
	@Benchmark
	public Object magic_shape_04() throws Throwable { return magicScript_4.run(magicContext); }
	@Benchmark
	public Object graal_shape_04() { return graalContext.eval(graalScript_4); }
	//endregion

	//region 4. Shape = 8 (Polymorphic-8 链上限)
	@Benchmark
	public Object magic_shape_08() throws Throwable { return magicScript_8.run(magicContext); }
	@Benchmark
	public Object graal_shape_08() { return graalContext.eval(graalScript_8); }
	//endregion

	//region 5. Shape = 64 (Deep Megamorphic 巨态)
	@Benchmark
	public Object magic_shape_64() throws Throwable { return magicScript_64.run(magicContext); }
	@Benchmark
	public Object graal_shape_64() { return graalContext.eval(graalScript_64); }
	//endregion

	public static String generateCompoundGradientCode(int numShapes) {
		StringBuilder sb = new StringBuilder();
		sb.append("var pool = [\n");
		for (int i = 0; i < numShapes; i++) {
			sb.append("    { type: ").append(i)
			 .append(", val: ").append(10.5 + i)
			 .append(", tag: ").append(i * 5)
			 .append(", prop_").append(i).append(": ").append(i * 10)
			 .append(" }");
			if (i < numShapes - 1) sb.append(",\n");
		}
		sb.append("\n];\n\n");
		sb.append("var total = 0;\n");
		sb.append("for (var i = 0; i < 2000; i++) {\n");
		sb.append("    var item = pool[i % ").append(numShapes).append("];\n");
		sb.append("    var factor = (i % 5) + 0.5;\n");
		sb.append("    total = total + item.val * factor + item.tag;\n");
		sb.append("}\n");
		sb.append("total;\n");
		return sb.toString();
	}

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
		double mean   = sum / batchCount;
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
		GradientShapeBenchmark bench = new GradientShapeBenchmark();
		System.out.println(">>> 正在执行 Setup 初始化、冷态断言与 5000 次预热稳态断言...");
		bench.setup();
		System.out.println(">>> [PASS] Setup 初始校验与 5000 次稳态预热校验全部通过！");

		System.out.println(">>> 正在执行单次测试运行与结果输出...");
		System.out.println("Shape-1  MagicJS: " + bench.magic_shape_01() + ", GraalJS: " + bench.graal_shape_01());
		System.out.println("Shape-2  MagicJS: " + bench.magic_shape_02() + ", GraalJS: " + bench.graal_shape_02());
		System.out.println("Shape-4  MagicJS: " + bench.magic_shape_04() + ", GraalJS: " + bench.graal_shape_04());
		System.out.println("Shape-8  MagicJS: " + bench.magic_shape_08() + ", GraalJS: " + bench.graal_shape_08());
		System.out.println("Shape-64 MagicJS: " + bench.magic_shape_64() + ", GraalJS: " + bench.graal_shape_64());

		bench.tearDown();
		System.out.println(">>> [PASS] TearDown 终态校验全部通过！验证大获全胜！");
	}
}