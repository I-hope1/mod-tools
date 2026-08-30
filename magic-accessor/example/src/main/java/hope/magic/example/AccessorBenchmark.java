package hope.magic.example;

import hope.magic.runtime.Magic;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class AccessorBenchmark {

	private TargetObject target;
	private int a = 6;
	private int b = 7;

	// 反射基准
	private Field reflectField;
	private Method reflectMethod;

	// MethodHandle 基准
	private MethodHandle directMH;

	@Setup
	public void setup() throws Throwable {
		// 初始化 Magic 框架基础设施
		Magic.install();

		target = new TargetObject();

		// 反射初始化
		reflectField = TargetObject.class.getDeclaredField("secretCode");
		reflectField.setAccessible(true);

		reflectMethod = TargetObject.class.getDeclaredMethod("multiply", int.class, int.class);
		reflectMethod.setAccessible(true);

		// MethodHandle 初始化
		directMH = Magic.lookup.findSpecial(
			TargetObject.class,
			"multiply",
			java.lang.invoke.MethodType.methodType(int.class, int.class, int.class),
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
			.include(AccessorBenchmark.class.getSimpleName())
			.forks(1)
			.warmupIterations(2)
			.measurementIterations(3)
			.build();
		new Runner(opt).run();
	}
}
