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
 * 未充分预热 / 冷启动阶段 JMH 性能测试 (SingleShotTime 模式，0 轮预热，全新 JVM 进程直测首调时延)
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(5)
public class AccessorColdStartBenchmark {

	private TargetObject target;
	private int a = 6;
	private int b = 7;

	// 提前预初始化的对象
	private Field preCachedReflectField;
	private Method preCachedReflectMethod;
	private java.lang.reflect.Constructor<TargetObject> preCachedReflectCtor;
	private MethodHandle preCachedMH;

	@Setup(Level.Trial)
	public void setup() throws Throwable {
		Magic.install();
		target = new TargetObject();

		// 提前缓存的反射句柄
		preCachedReflectField = TargetObject.class.getDeclaredField("secretCode");
		preCachedReflectField.setAccessible(true);

		preCachedReflectMethod = TargetObject.class.getDeclaredMethod("multiply", int.class, int.class);
		preCachedReflectMethod.setAccessible(true);

		preCachedReflectCtor = TargetObject.class.getDeclaredConstructor(int.class, String.class);
		preCachedReflectCtor.setAccessible(true);

		// 提前缓存的 MethodHandle
		preCachedMH = Magic.lookup.findSpecial(
			TargetObject.class,
			"multiply",
			MethodType.methodType(int.class, int.class, int.class),
			TargetObject.class
		);
	}

	//region 字段 Getter 冷调用
	@Benchmark
	public int baseline_direct_field_get() {
		return target.getSecretCode();
	}

	@Benchmark
	public int dynamic_lookup_and_reflect_field_get() throws Exception {
		Field f = TargetObject.class.getDeclaredField("secretCode");
		f.setAccessible(true);
		return (int) f.get(target);
	}

	@Benchmark
	public int pre_cached_reflect_field_get() throws Exception {
		return (int) preCachedReflectField.get(target);
	}

	@Benchmark
	public int plan1_magic_accessor_field_get() {
		return LegacyMagicAccessorSample.getSecretCode(target);
	}

	@Benchmark
	public int plan2A_linkTo_unsafe_field_get() {
		return MagicAccessorSample.getSecretCode(target);
	}
	//endregion

	//region 方法调用冷调用
	@Benchmark
	public int baseline_direct_math_compute() {
		return a * b;
	}

	@Benchmark
	public int dynamic_lookup_and_reflect_method_invoke() throws Exception {
		Method m = TargetObject.class.getDeclaredMethod("multiply", int.class, int.class);
		m.setAccessible(true);
		return (int) m.invoke(target, a, b);
	}

	@Benchmark
	public int pre_cached_reflect_method_invoke() throws Exception {
		return (int) preCachedReflectMethod.invoke(target, a, b);
	}

	@Benchmark
	public int dynamic_lookup_and_raw_mh_invokeExact() throws Throwable {
		MethodHandle mh = Magic.lookup.findSpecial(
			TargetObject.class,
			"multiply",
			MethodType.methodType(int.class, int.class, int.class),
			TargetObject.class
		);
		return (int) mh.invokeExact(target, a, b);
	}

	@Benchmark
	public int pre_cached_raw_mh_invokeExact() throws Throwable {
		return (int) preCachedMH.invokeExact(target, a, b);
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
	//endregion

	//region 私有构造器冷调用
	@Benchmark
	public TargetObject dynamic_lookup_and_reflect_constructor_newInstance() throws Exception {
		java.lang.reflect.Constructor<TargetObject> c = TargetObject.class.getDeclaredConstructor(int.class, String.class);
		c.setAccessible(true);
		return c.newInstance(a, "cold_reflect");
	}

	@Benchmark
	public TargetObject pre_cached_reflect_constructor_newInstance() throws Exception {
		return preCachedReflectCtor.newInstance(a, "cold_cached_reflect");
	}

	@Benchmark
	public TargetObject plan1_magic_accessor_constructor() {
		return LegacyMagicAccessorSample.newTargetObject(a, "cold_magic");
	}

	@Benchmark
	public TargetObject plan2A_linkTo_constructor() {
		return MagicAccessorSample.newTargetObject(a, "cold_linkTo");
	}

	@Benchmark
	public TargetObject plan2B_indy_constructor() {
		return IndyAccessorSample.newTargetObject(a, "cold_indy");
	}

	@Benchmark
	public TargetObject plan2C_android_constructor() {
		return AndroidAccessorSample.newTargetObject(a, "cold_android");
	}
	//endregion

	public static void main(String[] args) throws Exception {
		Options opt = new OptionsBuilder()
			.include(AccessorColdStartBenchmark.class.getSimpleName())
			.forks(5)
			.warmupIterations(0)
			.measurementIterations(1)
			.mode(Mode.SingleShotTime)
			.timeUnit(TimeUnit.MICROSECONDS)
			.build();
		new Runner(opt).run();
	}
}
