package hope.magic.js.test;

import hope.magic.annotation.AccessMode;
import hope.magic.js.runtime.*;
import hope.magic.runtime.Magic;
import org.junit.jupiter.api.Test;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.text.DecimalFormat;

public class MagicJITLinkToBenchmarkTest {

	public static final class BenchmarkTarget {
		private final int id;
		private final String name;

		public BenchmarkTarget() {
			this(0, "default");
		}

		public BenchmarkTarget(int id) {
			this(id, "single");
		}

		public BenchmarkTarget(int id, String name) {
			this.id = id;
			this.name = name;
		}

		private int multiply(int a, int b) {
			return a * b;
		}

		public int getId() {
			return id;
		}

		public String getName() {
			return name;
		}
	}

	private static final DecimalFormat DF = new DecimalFormat("#,##0.00");
	private static final DecimalFormat DF_INT = new DecimalFormat("#,##0");

	@Test
	public void runMagicJITBenchmarks() throws Throwable {
		System.out.println("=========================================================================================");
		System.out.println("               MagicJIT linkTo 原生直调与多方案性能基准测试报告                            ");
		System.out.println("=========================================================================================");

		warmup();

		benchmarkPrivateMethodInvocation();
		benchmarkConstructorInvocation();
		benchmarkAccessModesComparison();
		benchmarkEndToEndJSEngine();

		System.out.println("=========================================================================================");
	}

	private void warmup() throws Throwable {
		BenchmarkTarget target = new BenchmarkTarget(1, "warmup");
		Method m = BenchmarkTarget.class.getDeclaredMethod("multiply", int.class, int.class);
		m.setAccessible(true);
		MethodHandle mh = Magic.lookup.unreflect(m);
		MagicJIT.MagicInvoker invoker = MagicJIT.getMethodInvoker(BenchmarkTarget.class, "multiply", 2, false);
		MagicJIT.MagicConstructorInvoker ctor = MagicJIT.getConstructorInvoker(BenchmarkTarget.class, 2);

		for (int i = 0; i < 200_000; i++) {
			m.invoke(target, i, 2);
			int dummy = (int) mh.invokeExact(target, i, 2);
			if (invoker != null) {
				invoker.invoke2(target, i, 2);
			}
			if (ctor != null) {
				ctor.newInstance2(i, "w");
			}
		}
	}

	/**
	 * 基准 1：私有方法调用吞吐与延时对比
	 */
	private void benchmarkPrivateMethodInvocation() throws Throwable {
		System.out.println("\n【基准 1】私有方法调用 (multiply: int * int) 10,000,000 次");
		System.out.printf("%-42s | %-12s | %-16s | %-10s%n", "调用方式", "耗时 (ms)", "吞吐量 (ops/ms)", "相对基准");
		System.out.println("-------------------------------------------+--------------+------------------+-----------");

		int iterations = 10_000_000;
		BenchmarkTarget target = new BenchmarkTarget(42, "target");
		Method m = BenchmarkTarget.class.getDeclaredMethod("multiply", int.class, int.class);
		m.setAccessible(true);

		// 1. 原生直接调用（公共代理方法模拟基准）
		long start0 = System.nanoTime();
		long sum0 = 0;
		for (int i = 0; i < iterations; i++) {
			sum0 += (i * 2);
		}
		double time0 = (System.nanoTime() - start0) / 1_000_000.0;
		printRow("1. Java Direct (原生基准)", time0, iterations, 1.0);

		// 2. 传统反射 Method.invoke
		long startReflect = System.nanoTime();
		long sumReflect = 0;
		for (int i = 0; i < iterations; i++) {
			sumReflect += ((Number) m.invoke(target, i, 2)).intValue();
		}
		double timeReflect = (System.nanoTime() - startReflect) / 1_000_000.0;
		printRow("2. java.lang.reflect.Method.invoke", timeReflect, iterations, timeReflect / time0);

		// 3. 原生 MethodHandle.invokeExact
		MethodHandle mh = Magic.lookup.unreflect(m);
		long startMh = System.nanoTime();
		long sumMh = 0;
		for (int i = 0; i < iterations; i++) {
			sumMh += (int) mh.invokeExact(target, i, 2);
		}
		double timeMh = (System.nanoTime() - startMh) / 1_000_000.0;
		printRow("3. MethodHandle.invokeExact", timeMh, iterations, timeMh / time0);

		// 4. 旧式 MH + asSpreader (数组中转包装)
		MethodHandle spreader = mh.asType(MethodType.methodType(Object.class, BenchmarkTarget.class, Object.class, Object.class))
			.asSpreader(Object[].class, 2);
		long startSpreader = System.nanoTime();
		long sumSpreader = 0;
		Object[] arr = new Object[2];
		for (int i = 0; i < iterations; i++) {
			arr[0] = i;
			arr[1] = 2;
			sumSpreader += ((Number) (Object) spreader.invokeExact(target, arr)).intValue();
		}
		double timeSpreader = (System.nanoTime() - startSpreader) / 1_000_000.0;
		printRow("4. MH + asSpreader (数组中转)", timeSpreader, iterations, timeSpreader / time0);

		// 5. 新架构 MagicInvoker.invoke(target, Object[]) (通用数组调用)
		MagicJIT.MagicInvoker invoker = MagicJIT.getMethodInvoker(BenchmarkTarget.class, "multiply", 2, false);
		long startInvokerArr = System.nanoTime();
		long sumInvokerArr = 0;
		for (int i = 0; i < iterations; i++) {
			arr[0] = i;
			arr[1] = 2;
			sumInvokerArr += ((Number) invoker.invoke(target, arr)).intValue();
		}
		double timeInvokerArr = (System.nanoTime() - startInvokerArr) / 1_000_000.0;
		printRow("5. MagicInvoker.invoke (Object[])", timeInvokerArr, iterations, timeInvokerArr / time0);

		// 6. 新架构 MagicInvoker.invoke2 (零 MH、零数组分配特化直调，含传参装箱)
		long startInvoker2 = System.nanoTime();
		long sumInvoker2 = 0;
		for (int i = 0; i < iterations; i++) {
			sumInvoker2 += ((Number) invoker.invoke2(target, i, 2)).intValue();
		}
		double timeInvoker2 = (System.nanoTime() - startInvoker2) / 1_000_000.0;
		printRow("6. MagicInvoker.invoke2 (含基本类型装箱)", timeInvoker2, iterations, timeInvoker2 / time0);

		// 6.1 新架构 MagicInvoker.invoke2 (零装箱纯调度分发测试: 复用对象传参)
		Integer boxA = 6, boxB = 7;
		long startInvokerNoBox = System.nanoTime();
		long sumInvokerNoBox = 0;
		for (int i = 0; i < iterations; i++) {
			sumInvokerNoBox += ((Number) invoker.invoke2(target, boxA, boxB)).intValue();
		}
		double timeInvokerNoBox = (System.nanoTime() - startInvokerNoBox) / 1_000_000.0;
		printRow("6.1 MagicInvoker.invoke2 (零装箱纯直调)", timeInvokerNoBox, iterations, timeInvokerNoBox / time0);

		// 7. 新架构 ExactMethodStub (JIT CallSite 优化路径)
		MethodHandle exactStub = MagicJIT.createExactMethodStub(BenchmarkTarget.class, m);
		long startExact = System.nanoTime();
		long sumExact = 0;
		for (int i = 0; i < iterations; i++) {
			sumExact += ((Number) exactStub.invoke(target, i, 2)).intValue();
		}
		double timeExact = (System.nanoTime() - startExact) / 1_000_000.0;
		printRow("7. MagicJIT.createExactMethodStub", timeExact, iterations, timeExact / time0);
	}

	/**
	 * 基准 2：构造器实例化性能对比
	 */
	private void benchmarkConstructorInvocation() throws Throwable {
		System.out.println("\n【基准 2】构造器对象创建 (new BenchmarkTarget(int, String)) 5,000,000 次");
		System.out.printf("%-42s | %-12s | %-16s | %-10s%n", "创建方式", "耗时 (ms)", "吞吐量 (ops/ms)", "相对基准");
		System.out.println("-------------------------------------------+--------------+------------------+-----------");

		int iterations = 5_000_000;

		// 1. Java 原生 new
		long start0 = System.nanoTime();
		for (int i = 0; i < iterations; i++) {
			BenchmarkTarget b = new BenchmarkTarget(i, "msg");
		}
		double time0 = (System.nanoTime() - start0) / 1_000_000.0;
		printRow("1. Java 原生 new BenchmarkTarget(..)", time0, iterations, 1.0);

		// 2. 传统反射 Constructor.newInstance
		Constructor<?> ctor = BenchmarkTarget.class.getDeclaredConstructor(int.class, String.class);
		ctor.setAccessible(true);
		long startReflect = System.nanoTime();
		for (int i = 0; i < iterations; i++) {
			Object o = ctor.newInstance(i, "msg");
		}
		double timeReflect = (System.nanoTime() - startReflect) / 1_000_000.0;
		printRow("2. Constructor.newInstance(Object[])", timeReflect, iterations, timeReflect / time0);

		// 3. MethodHandle Constructor
		MethodHandle ctorMh = Magic.lookup.unreflectConstructor(ctor);
		long startMh = System.nanoTime();
		for (int i = 0; i < iterations; i++) {
			Object o = ctorMh.invoke(i, "msg");
		}
		double timeMh = (System.nanoTime() - startMh) / 1_000_000.0;
		printRow("3. MethodHandle.invoke(ctor)", timeMh, iterations, timeMh / time0);

		// 4. 新架构 MagicConstructorInvoker.newInstance (Object[])
		MagicJIT.MagicConstructorInvoker invoker = MagicJIT.getConstructorInvoker(BenchmarkTarget.class, 2);
		long startInvokerArr = System.nanoTime();
		Object[] arr = new Object[2];
		arr[1] = "msg";
		for (int i = 0; i < iterations; i++) {
			arr[0] = i;
			Object o = invoker.newInstance(arr);
		}
		double timeInvokerArr = (System.nanoTime() - startInvokerArr) / 1_000_000.0;
		printRow("4. MagicConstructorInvoker (Object[])", timeInvokerArr, iterations, timeInvokerArr / time0);

		// 5. 新架构 MagicConstructorInvoker.newInstance2 (零MH/零分配/linkToSpecial)
		long startInvoker2 = System.nanoTime();
		for (int i = 0; i < iterations; i++) {
			Object o = invoker.newInstance2(i, "msg");
		}
		double timeInvoker2 = (System.nanoTime() - startInvoker2) / 1_000_000.0;
		printRow("5. MagicConstructorInvoker.newInstance2", timeInvoker2, iterations, timeInvoker2 / time0);
	}

	/**
	 * 基准 3：不同 AccessMode 模式对比
	 */
	private void benchmarkAccessModesComparison() throws Throwable {
		System.out.println("\n【基准 3】多 AccessMode 模式特化直调性能对比 (5,000,000 次)");
		System.out.printf("%-32s | %-12s | %-16s | %-10s%n", "访问模式 (AccessMode)", "耗时 (ms)", "吞吐量 (ops/ms)", "相对比率");
		System.out.println("---------------------------------+--------------+------------------+-----------");

		int iterations = 5_000_000;
		BenchmarkTarget target = new BenchmarkTarget(99, "modeTest");

		// 1. UNSAFE_AND_METHODHANDLE
		MagicJIT.MagicInvoker mhInvoker = MagicJIT.getMethodInvoker(BenchmarkTarget.class, "multiply", 2, false, AccessMode.UNSAFE_AND_METHODHANDLE);
		long startMH = System.nanoTime();
		for (int i = 0; i < iterations; i++) {
			mhInvoker.invoke2(target, i, 3);
		}
		double timeMH = (System.nanoTime() - startMH) / 1_000_000.0;
		printRow2("1. UNSAFE_AND_METHODHANDLE", timeMH, iterations, 1.0);

		// 2. UNSAFE_AND_LINKTO (本轮重构核心：<clinit> resolveOrFail + linkTo 原语)
		MagicJIT.MagicInvoker linkToInvoker = MagicJIT.getMethodInvoker(BenchmarkTarget.class, "multiply", 2, false, AccessMode.UNSAFE_AND_LINKTO);
		long startLinkTo = System.nanoTime();
		for (int i = 0; i < iterations; i++) {
			linkToInvoker.invoke2(target, i, 3);
		}
		double timeLinkTo = (System.nanoTime() - startLinkTo) / 1_000_000.0;
		printRow2("2. UNSAFE_AND_LINKTO", timeLinkTo, iterations, timeLinkTo / timeMH);

		// 3. MAGIC_ACCESSOR (经典特权字节码)
		MagicJIT.MagicInvoker accessorInvoker = MagicJIT.getMethodInvoker(BenchmarkTarget.class, "multiply", 2, false, AccessMode.MAGIC_ACCESSOR);
		long startAccessor = System.nanoTime();
		for (int i = 0; i < iterations; i++) {
			accessorInvoker.invoke2(target, i, 3);
		}
		double timeAccessor = (System.nanoTime() - startAccessor) / 1_000_000.0;
		printRow2("3. MAGIC_ACCESSOR", timeAccessor, iterations, timeAccessor / timeMH);
	}

	/**
	 * 基准 4：端到端 JS 引擎中的方法调用与实例化
	 */
	private void benchmarkEndToEndJSEngine() throws Throwable {
		System.out.println("\n【基准 4】端到端 JS 引擎执行性能 (JSContext.eval) 1,000,000 次");
		System.out.printf("%-42s | %-12s | %-16s%n", "测试场景", "耗时 (ms)", "吞吐量 (ops/ms)");
		System.out.println("-------------------------------------------+--------------+------------------");

		int iterations = 1_000_000;
		BenchmarkTarget target = new BenchmarkTarget(100, "jsTarget");

		// 场景 A-1: 预编译 JSFunction (0 编译开销，纯字节码执行循环)
		JSContext cxFn = new JSContext();
		cxFn.set("target", target);
		cxFn.eval("function benchMethod(n) { var sum = 0; for (var i = 0; i < n; i++) { sum += target.multiply(i, 2); } return sum; }");
		hope.magic.js.runtime.JSFunction fn = (hope.magic.js.runtime.JSFunction) cxFn.get("benchMethod");
		Object[] warmupArg = new Object[]{ 100_000 };
		Object[] iterArg = new Object[]{ iterations };
		// 预热 JIT
		fn.call(cxFn, null, warmupArg);

		long startFn = System.nanoTime();
		Object resFn = fn.call(cxFn, null, iterArg);
		double timeFn = (System.nanoTime() - startFn) / 1_000_000.0;
		System.out.printf("%-42s | %-12s | %-16s%n",
			"预编译 JSFunction (0编译开销纯循环调用)",
			DF.format(timeFn),
			DF_INT.format(iterations / timeFn)
		);

		// 场景 A-2: 全流程 eval 包含完整 compile (词法+语法+ASM编译+类加载)
		JSContext cx1 = new JSContext();
		cx1.set("target", target);
		String scriptMethod = """
			var sum = 0;
			for (var i = 0; i < %d; i++) {
				sum += target.multiply(i, 2);
			}
			sum;
		""".formatted(iterations);

		long startA = System.nanoTime();
		Object resA = cx1.eval(scriptMethod);
		double timeA = (System.nanoTime() - startA) / 1_000_000.0;
		System.out.printf("%-42s | %-12s | %-16s%n",
			"JSContext.eval (含Lexer+Parser+ASM编译)",
			DF.format(timeA),
			DF_INT.format(iterations / timeA)
		);

		// 场景 B: JS 循环构造 Java 对象
		JSContext cx2 = new JSContext();
		cx2.set("BenchmarkTarget", BenchmarkTarget.class);
		String scriptCtor = """
			var sum = 0;
			for (var i = 0; i < %d; i++) {
				var obj = new BenchmarkTarget(i, 'test');
				sum += obj.getId();
			}
			sum;
		""".formatted(iterations);

		long startB = System.nanoTime();
		Object resB = cx2.eval(scriptCtor);
		double timeB = (System.nanoTime() - startB) / 1_000_000.0;
		System.out.printf("%-42s | %-12s | %-16s%n",
			"JS 循环实例化 Java 对象 (new BenchmarkTarget)",
			DF.format(timeB),
			DF_INT.format(iterations / timeB)
		);
	}

	private void printRow(String name, double elapsedMs, int ops, double ratio) {
		double throughput = ops / elapsedMs;
		System.out.printf("%-42s | %-12s | %-16s | %-10s%n",
			name,
			DF.format(elapsedMs),
			DF_INT.format(throughput),
			DF.format(ratio) + "x"
		);
	}

	private void printRow2(String name, double elapsedMs, int ops, double ratio) {
		double throughput = ops / elapsedMs;
		System.out.printf("%-32s | %-12s | %-16s | %-10s%n",
			name,
			DF.format(elapsedMs),
			DF_INT.format(throughput),
			DF.format(ratio) + "x"
		);
	}
}
