package hope.magic.example;

import hope.magic.js.runtime.MagicJIT;
import hope.magic.runtime.BootTestStableHolder;
import hope.magic.runtime.Magic;
import hope.magic.runtime.MagicInvoker;
import jdk.internal.vm.annotation.Stable;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {
	"-XX:+UnlockDiagnosticVMOptions"
})
public class MethodHandleIndirectCallJMHBenchmark {

	// --- 1. 单目标类体系 ---
	public static class SingleTarget {
		public int calculate(int a, int b) {
			return a * 3 + b;
		}
	}

	public final SingleTarget singleTarget = new SingleTarget();

	public static final MethodHandle STATIC_FINAL_MH;
	static {
		try {
			if (!Magic.isInstalled()) Magic.install();
			STATIC_FINAL_MH = Magic.lookup.findVirtual(SingleTarget.class, "calculate",
				MethodType.methodType(int.class, int.class, int.class));
		} catch (Throwable e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	// 位于 AppClassLoader 的字段（不受 C2 信任的 @Stable）
	@Stable
	public final MethodHandle appStableMh = STATIC_FINAL_MH;

	// 非常量（非 @Stable，普通 volatile 字段），模拟运行期动态变化的非常量句柄引用
	public volatile MethodHandle variableMh = STATIC_FINAL_MH;

	@Stable
	public final MagicInvoker appStableInvoker = MagicJIT.getMethodInvoker(SingleTarget.class, "calculate", 2, false);

	// 位于 BootstrapClassLoader 的 Holder 实例（其实例字段带有受 C2 信任的 @Stable）
	public BootTestStableHolder bootHolder;

	// --- 2. 8 类巨态 (Megamorphic) 测试目标体系 ---
	public interface TaskContract {
		int compute(int a, int b);
	}

	public static class Task0 implements TaskContract { @Override public int compute(int a, int b) { return (a + b) ^ 0x01; } }
	public static class Task1 implements TaskContract { @Override public int compute(int a, int b) { return (a + b) ^ 0x02; } }
	public static class Task2 implements TaskContract { @Override public int compute(int a, int b) { return (a + b) ^ 0x03; } }
	public static class Task3 implements TaskContract { @Override public int compute(int a, int b) { return (a + b) ^ 0x04; } }
	public static class Task4 implements TaskContract { @Override public int compute(int a, int b) { return (a + b) ^ 0x05; } }
	public static class Task5 implements TaskContract { @Override public int compute(int a, int b) { return (a + b) ^ 0x06; } }
	public static class Task6 implements TaskContract { @Override public int compute(int a, int b) { return (a + b) ^ 0x07; } }
	public static class Task7 implements TaskContract { @Override public int compute(int a, int b) { return (a + b) ^ 0x08; } }

	@Stable
	public final TaskContract[] tasks = new TaskContract[]{
		new Task0(), new Task1(), new Task2(), new Task3(),
		new Task4(), new Task5(), new Task6(), new Task7()
	};

	// 位于 AppClassLoader 的表（未受信 @Stable）
	@Stable
	public final MethodHandle[] appMhTable = new MethodHandle[8];

	@Stable
	public final MagicInvoker[] appInvokerTable = new MagicInvoker[8];

	@Stable
	public final Method[] appMethodTable = new Method[8];

	private int counter = 0;

	@Setup(Level.Trial)
	public void setup() throws Throwable {
		if (!Magic.isInstalled()) Magic.install();

		bootHolder = new BootTestStableHolder();
		bootHolder.instanceStableMh = STATIC_FINAL_MH;
		bootHolder.instanceStableInvoker = appStableInvoker;

		BootTestStableHolder.STATIC_STABLE_MH = STATIC_FINAL_MH;
		BootTestStableHolder.STATIC_STABLE_INVOKER = appStableInvoker;

		for (int i = 0; i < 8; i++) {
			Class<?> clazz = tasks[i].getClass();
			Method m = clazz.getMethod("compute", int.class, int.class);
			m.setAccessible(true);
			appMethodTable[i] = m;

			// 统一转换为 (Object, int, int)int 签名供 invokeExact 调用
			MethodHandle rawMh = Magic.lookup.findVirtual(clazz, "compute", MethodType.methodType(int.class, int.class, int.class));
			MethodHandle exactMh = rawMh.asType(MethodType.methodType(int.class, Object.class, int.class, int.class));

			appMhTable[i] = exactMh;
			MagicInvoker invoker = MagicJIT.getMethodInvoker(clazz, m);
			appInvokerTable[i] = invoker;

			// 填充 BootLoader 受信 Holder 表
			BootTestStableHolder.STATIC_STABLE_TABLE[i] = exactMh;
			BootTestStableHolder.STATIC_STABLE_INVOKER_TABLE[i] = invoker;
			bootHolder.instanceStableTable[i] = exactMh;
			bootHolder.instanceStableInvokerTable[i] = invoker;
		}
	}

	// =========================================================================
	// 场景一：单目标调用（对比 AppClassLoader vs BootLoader @Stable）
	// =========================================================================

	@Benchmark
	public void single_1_javaDirect(Blackhole bh) {
		int i = counter++;
		bh.consume(singleTarget.calculate(i, 2));
	}

	@Benchmark
	public void single_2_constantMhInvokeExact(Blackhole bh) throws Throwable {
		int i = counter++;
		bh.consume((int) STATIC_FINAL_MH.invokeExact(singleTarget, i, 2));
	}

	@Benchmark
	public void single_3_appStableFieldMh(Blackhole bh) throws Throwable {
		int i = counter++;
		// AppClassLoader 下的 @Stable：C2 不信任，无法折叠
		bh.consume((int) appStableMh.invokeExact(singleTarget, i, 2));
	}

	@Benchmark
	public void single_4_bootStableStaticMh(Blackhole bh) throws Throwable {
		int i = counter++;
		// BootLoader 下的 @Stable static 字段：C2 信任，可直接折叠为常量
		bh.consume((int) BootTestStableHolder.STATIC_STABLE_MH.invokeExact(singleTarget, i, 2));
	}

	@Benchmark
	public void single_5_bootStableInstanceMh(Blackhole bh) throws Throwable {
		int i = counter++;
		// BootLoader 类里的 @Stable 实例字段（但 receiver 为堆上的 bootHolder 实例）
		bh.consume((int) bootHolder.instanceStableMh.invokeExact(singleTarget, i, 2));
	}

	@Benchmark
	public void single_6_appMagicInvoker(Blackhole bh) throws Throwable {
		int i = counter++;
		// AppClassLoader 引用指向 MagicInvoker
		bh.consume(appStableInvoker.invokeInt2(singleTarget, i, 2));
	}

	@Benchmark
	public void single_7_bootStableStaticInvoker(Blackhole bh) throws Throwable {
		int i = counter++;
		// BootLoader @Stable static 引用指向 MagicInvoker
		bh.consume(BootTestStableHolder.STATIC_STABLE_INVOKER.invokeInt2(singleTarget, i, 2));
	}

	@Benchmark
	public void single_8_variableMhInvokeExact(Blackhole bh) throws Throwable {
		int i = counter++;
		// volatile 动态变量
		bh.consume((int) variableMh.invokeExact(singleTarget, i, 2));
	}

	// =========================================================================
	// 场景二：常量索引查表（测试 C2 对受信任 @Stable 数组元素的折叠能力）
	// =========================================================================

	@Benchmark
	public void const_idx_1_appTable(Blackhole bh) throws Throwable {
		int i = counter++;
		// AppClassLoader 数组 + 常量下标 0
		bh.consume((int) appMhTable[0].invokeExact((Object) tasks[0], i, 2));
	}

	@Benchmark
	public void const_idx_2_bootStaticTable(Blackhole bh) throws Throwable {
		int i = counter++;
		// BootLoader @Stable 数组 + 常量下标 0（验证 C2 是否能折叠数组元素并内联）
		bh.consume((int) BootTestStableHolder.STATIC_STABLE_TABLE[0].invokeExact((Object) tasks[0], i, 2));
	}

	// =========================================================================
	// 场景三：8 类巨态轮换分发（Megamorphic Table Dispatch，动态下标 i & 7）
	// =========================================================================

	@Benchmark
	public void mega_1_javaInterface(Blackhole bh) {
		int i = counter++;
		int idx = i & 7;
		bh.consume(tasks[idx].compute(i, 2));
	}

	@Benchmark
	public void mega_2_appMhTable(Blackhole bh) throws Throwable {
		int i = counter++;
		int idx = i & 7;
		bh.consume((int) appMhTable[idx].invokeExact((Object) tasks[idx], i, 2));
	}

	@Benchmark
	public void mega_3_bootStaticMhTable(Blackhole bh) throws Throwable {
		int i = counter++;
		int idx = i & 7;
		// 动态下标访问 BootLoader @Stable 数组
		bh.consume((int) BootTestStableHolder.STATIC_STABLE_TABLE[idx].invokeExact((Object) tasks[idx], i, 2));
	}

	@Benchmark
	public void mega_4_appInvokerTable(Blackhole bh) throws Throwable {
		int i = counter++;
		int idx = i & 7;
		bh.consume(appInvokerTable[idx].invokeInt2(tasks[idx], i, 2));
	}

	@Benchmark
	public void mega_5_bootStaticInvokerTable(Blackhole bh) throws Throwable {
		int i = counter++;
		int idx = i & 7;
		bh.consume(BootTestStableHolder.STATIC_STABLE_INVOKER_TABLE[idx].invokeInt2(tasks[idx], i, 2));
	}

	@Benchmark
	public void mega_6_methodTableReflection(Blackhole bh) throws Throwable {
		int i = counter++;
		int idx = i & 7;
		bh.consume(((Number) appMethodTable[idx].invoke(tasks[idx], i, 2)).intValue());
	}

	public static void main(String[] args) throws Exception {
		Options opt = new OptionsBuilder()
			.include(MethodHandleIndirectCallJMHBenchmark.class.getSimpleName())
			.build();
		new Runner(opt).run();
	}
}
