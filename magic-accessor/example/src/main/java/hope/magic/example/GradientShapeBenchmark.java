package hope.magic.example;

import hope.magic.js.runtime.*;
import org.graalvm.polyglot.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.*;

import java.util.concurrent.TimeUnit;

/**
 * 梯度多态基准测试 (Gradient Shape Benchmark)
 * 评测 Shape 属性在 1 / 2 / 4 / 8 / 64 真正完全独立 offset 梯度下，MagicJS vs GraalJS 的单次访问性能演进曲线。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Thread)
public class GradientShapeBenchmark {

	@Param({"1", "2", "4", "8", "64"})
	private int shapes;

	private JSContext  magicContext;
	private Context    graalContext;

	private JSFunction magicFunc;
	private Value      graalFunc;

	@Setup(Level.Trial)
	public void setup() throws Throwable {
		magicContext = new JSContext();
		graalContext = Context.newBuilder("js")
				.allowAllAccess(true)
				.build();
		graalContext.initialize("js");

		// 1. 初始化数据：生成对应 shapes 的扁平化测试数据 (长度 2000)
		String initCode = generateSetupCode(shapes);
		magicContext.eval(initCode);
		graalContext.eval("js", initCode);

		// 2. 编译纯访问闭包 (仅做 2000 次 .val 属性读取)
		String accessCode = generatePureAccessCode(shapes);
		magicFunc = (JSFunction) magicContext.eval("(function() {\n" + accessCode + "\n})");
		graalFunc = graalContext.eval("js", "(function() {\n" + accessCode + "\n})");

		// 3. 初始正确性校验 (逐 bit 浮点对齐校验)
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
		double mRes = magicFunc.call0Double(magicContext);
		double gRes = graalFunc.execute().asDouble();
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

	private static void verifyResult(String label, double magicVal, double graalVal, double expectedVal) {
		if (Double.isNaN(magicVal) || Double.isInfinite(magicVal)) {
			throw new IllegalStateException("[" + label + "] MagicJS produced invalid number: " + magicVal);
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
	public double test_magic() throws Throwable {
		return magicFunc.call0Double(magicContext);
	}

	@Benchmark
	public double test_graal() {
		return graalFunc.execute().asDouble();
	}

	// ---------------------- 代码生成辅助 ----------------------

	public static String generateSetupCode(int numShapes) {
		StringBuilder sb = new StringBuilder();
		sb.append("pool_").append(numShapes).append(" = [\n");
		for (int i = 0; i < numShapes; i++) {
			sb.append("    { ");
			for (int p = 0; p < i; p++) {
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
		       "return total;\n";
	}

	// ---------------------- 标准 JMH Runner Main ----------------------

	public static void main(String[] args) throws Exception {
		Options opt = new OptionsBuilder()
				.include(GradientShapeBenchmark.class.getSimpleName())
				.forks(1)
				.warmupIterations(2)
				.warmupTime(TimeValue.seconds(1))
				.measurementIterations(3)
				.measurementTime(TimeValue.seconds(1))
				.mode(Mode.AverageTime)
				.timeUnit(TimeUnit.NANOSECONDS)
				.build();
		new Runner(opt).run();
	}
}