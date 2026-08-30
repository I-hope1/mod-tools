package hope.magic.example;

import hope.magic.runtime.Magic;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * 仅预热 1 轮 (Warmup = 1 Iteration) 的 JMH 性能测试
 * 用于验证：只要经过 1 轮极简预热（类加载与基础 JIT 阶段），各方案在后续调用中的实际性能表现。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class AccessorWarmupOnceBenchmark {

	private TargetObject target;
	private int a = 6;
	private int b = 7;

	private Field reflectField;
	private Method reflectMethod;
	private MethodHandle directMH;

	@Setup(Level.Trial)
	public void setup() throws Throwable {
		Magic.install();
		target = new TargetObject();

		reflectField = TargetObject.class.getDeclaredField("secretCode");
		reflectField.setAccessible(true);

		reflectMethod = TargetObject.class.getDeclaredMethod("multiply", int.class, int.class);
		reflectMethod.setAccessible(true);

		directMH = Magic.lookup.findSpecial(
			TargetObject.class,
			"multiply",
			MethodType.methodType(int.class, int.class, int.class),
			TargetObject.class
		);
	}

	// ==================== 字段 Getter 测试 ====================

	@Benchmark
	public int baseline_direct_field_get() {
		return target.getSecretCode();
	}

	@Benchmark
	public int reflect_field_get() throws Exception {
		return (int) reflectField.get(target);
	}

	@Benchmark
	public int plan1_magic_accessor_field_get() {
		return LegacyMagicAccessorSample.getSecretCode(target);
	}

	@Benchmark
	public int plan2A_linkTo_unsafe_field_get() {
		return MagicAccessorSample.getSecretCode(target);
	}

	// ==================== 方法调用测试 ====================

	@Benchmark
	public int baseline_direct_math_compute() {
		return a * b;
	}

	@Benchmark
	public int reflect_method_invoke() throws Exception {
		return (int) reflectMethod.invoke(target, a, b);
	}

	@Benchmark
	public int raw_method_handle_invokeExact() throws Throwable {
		return (int) directMH.invokeExact(target, a, b);
	}

	@Benchmark
	public int plan1_magic_accessor_method() {
		return LegacyMagicAccessorSample.callMultiply(target, a, b);
	}

	@Benchmark
	public int plan2A_linkTo_method() {
		return MagicAccessorSample.callMultiply(target, a, b);
	}

	@Benchmark
	public int plan2B_indy_method() {
		return IndyAccessorSample.callMultiply(target, a, b);
	}

	@Benchmark
	public int plan2C_android_methodhandle_method() {
		return AndroidAccessorSample.callMultiply(target, a, b);
	}

	public static void main(String[] args) throws Exception {
		Options opt = new OptionsBuilder()
			.include(AccessorWarmupOnceBenchmark.class.getSimpleName())
			.forks(1)
			.warmupIterations(1)
			.measurementIterations(3)
			.mode(Mode.AverageTime)
			.timeUnit(TimeUnit.NANOSECONDS)
			.build();
		new Runner(opt).run();
	}
}
