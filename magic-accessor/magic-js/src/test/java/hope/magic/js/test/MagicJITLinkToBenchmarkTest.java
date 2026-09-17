package hope.magic.js.test;

import hope.magic.annotation.AccessMode;
import hope.magic.js.runtime.*;
import hope.magic.runtime.Magic;
import org.junit.jupiter.api.Test;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.text.DecimalFormat;

public class MagicJITLinkToBenchmarkTest {

	public static final int    WARMUP_TIMES     = 2_000_000;
	public static final int    ITERATIONS_TIMES = 100_000_000;
	public static final double doubleIterations = ITERATIONS_TIMES;

	public static final class BenchmarkTarget {
		private final int    id;
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

	private static final DecimalFormat DF     = new DecimalFormat("#,##0.00");
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
		Method          m      = BenchmarkTarget.class.getDeclaredMethod("multiply", int.class, int.class);
		m.setAccessible(true);
		MethodHandle                     mh      = Magic.lookup.unreflect(m);
		MagicJIT.MagicInvoker            invoker = MagicJIT.getMethodInvoker(BenchmarkTarget.class, "multiply", 2, false);
		MagicJIT.MagicConstructorInvoker ctor    = MagicJIT.getConstructorInvoker(BenchmarkTarget.class, 2);

		for (int i = 0; i < WARMUP_TIMES; i++) {
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
		System.out.println("\n【基准 1】私有方法调用 (multiply: int * int) " + ITERATIONS_TIMES + " 次");
		System.out.printf("%-42s | %-12s | %-16s | %-10s%n", "调用方式", "耗时 (ms)", "吞吐量 (ops/ms)", "相对基准");
		System.out.println("-------------------------------------------+--------------+------------------+-----------");

		BenchmarkTarget target = new BenchmarkTarget(42, "target");
		Method          m      = BenchmarkTarget.class.getDeclaredMethod("multiply", int.class, int.class);
		m.setAccessible(true);

		// 1. 原生直接调用（公共代理方法模拟基准）
		long start0 = System.nanoTime();
		long sum0   = 0;
		for (int i = 0; i < ITERATIONS_TIMES; i++) {
			sum0 += (i * 2);
		}
		double time0 = (System.nanoTime() - start0) / doubleIterations;
		printRow("1. Java Direct (原生基准)", time0, ITERATIONS_TIMES, 1.0);

		// 2. 传统反射 Method.invoke
		long startReflect = System.nanoTime();
		long sumReflect   = 0;
		for (int i = 0; i < ITERATIONS_TIMES; i++) {
			sumReflect += ((Number) m.invoke(target, i, 2)).intValue();
		}
		double timeReflect = (System.nanoTime() - startReflect) / doubleIterations;
		printRow("2. java.lang.reflect.Method.invoke", timeReflect, ITERATIONS_TIMES, timeReflect / time0);

		// 3. 原生 MethodHandle.invokeExact
		MethodHandle mh      = Magic.lookup.unreflect(m);
		long         startMh = System.nanoTime();
		long         sumMh   = 0;
		for (int i = 0; i < ITERATIONS_TIMES; i++) {
			sumMh += (int) mh.invokeExact(target, i, 2);
		}
		double timeMh = (System.nanoTime() - startMh) / doubleIterations;
		printRow("3. MethodHandle.invokeExact", timeMh, ITERATIONS_TIMES, timeMh / time0);

		// 4. 旧式 MH + asSpreader (数组中转包装)
		MethodHandle spreader = mh.asType(MethodType.methodType(Object.class, BenchmarkTarget.class, Object.class, Object.class))
		 .asSpreader(Object[].class, 2);
		long     startSpreader = System.nanoTime();
		long     sumSpreader   = 0;
		Object[] arr           = new Object[2];
		for (int i = 0; i < ITERATIONS_TIMES; i++) {
			arr[0] = i;
			arr[1] = 2;
			sumSpreader += ((Number) (Object) spreader.invokeExact(target, arr)).intValue();
		}
		double timeSpreader = (System.nanoTime() - startSpreader) / doubleIterations;
		printRow("4. MH + asSpreader (数组中转)", timeSpreader, ITERATIONS_TIMES, timeSpreader / time0);

		// 5. 新架构 MagicInvoker.invoke(target, Object[]) (通用数组调用)
		MagicJIT.MagicInvoker invoker         = MagicJIT.getMethodInvoker(BenchmarkTarget.class, "multiply", 2, false);
		long                  startInvokerArr = System.nanoTime();
		long                  sumInvokerArr   = 0;
		for (int i = 0; i < ITERATIONS_TIMES; i++) {
			arr[0] = i;
			arr[1] = 2;
			sumInvokerArr += ((Number) invoker.invoke(target, arr)).intValue();
		}
		double timeInvokerArr = (System.nanoTime() - startInvokerArr) / doubleIterations;
		printRow("5. MagicInvoker.invoke (Object[])", timeInvokerArr, ITERATIONS_TIMES, timeInvokerArr / time0);

		// 6. 新架构 MagicInvoker.invoke2 (零 MH、零数组分配特化直调，含传参装箱)
		long startInvoker2 = System.nanoTime();
		long sumInvoker2   = 0;
		for (int i = 0; i < ITERATIONS_TIMES; i++) {
			sumInvoker2 += ((Number) invoker.invoke2(target, i, 2)).intValue();
		}
		double timeInvoker2 = (System.nanoTime() - startInvoker2) / doubleIterations;
		printRow("6. MagicInvoker.invoke2 (含基本类型装箱)", timeInvoker2, ITERATIONS_TIMES, timeInvoker2 / time0);

		// 6.1 新架构 MagicInvoker.invoke2 (零装箱纯调度分发测试: 复用对象传参)
		Integer boxA              = 6, boxB = 7;
		long    startInvokerNoBox = System.nanoTime();
		long    sumInvokerNoBox   = 0;
		for (int i = 0; i < ITERATIONS_TIMES; i++) {
			sumInvokerNoBox += ((Number) invoker.invoke2(target, boxA, boxB)).intValue();
		}
		double timeInvokerNoBox = (System.nanoTime() - startInvokerNoBox) / doubleIterations;
		printRow("6.1 MagicInvoker.invoke2 (零装箱纯直调)", timeInvokerNoBox, ITERATIONS_TIMES, timeInvokerNoBox / time0);

		// 6.2 新架构 MagicInvoker.invokeInt2 (原生类型 100% 零装箱直调通道)
		long startInvokerInt2 = System.nanoTime();
		long sumInvokerInt2   = 0;
		for (int i = 0; i < ITERATIONS_TIMES; i++) {
			sumInvokerInt2 += invoker.invokeInt2(target, i, 2);
		}
		double timeInvokerInt2 = (System.nanoTime() - startInvokerInt2) / doubleIterations;
		printRow("6.2 MagicInvoker.invokeInt2 (零装箱原生直调)", timeInvokerInt2, ITERATIONS_TIMES, timeInvokerInt2 / time0);

		// 7. 新架构 ExactMethodStub (JIT CallSite 优化路径)
		MethodHandle exactStub  = MagicJIT.createExactMethodStub(BenchmarkTarget.class, m);
		long         startExact = System.nanoTime();
		long         sumExact   = 0;
		for (int i = 0; i < ITERATIONS_TIMES; i++) {
			sumExact += ((Number) exactStub.invoke(target, i, 2)).intValue();
		}
		double timeExact = (System.nanoTime() - startExact) / doubleIterations;
		printRow("7. MagicJIT.createExactMethodStub", timeExact, ITERATIONS_TIMES, timeExact / time0);
	}

	/**
	 * 基准 2：构造器实例化性能对比
	 */
	private void benchmarkConstructorInvocation() throws Throwable {
		System.out.println("\n【基准 2】构造器对象创建 (new BenchmarkTarget(int, String)) " + ITERATIONS_TIMES + " 次");
		System.out.printf("%-42s | %-12s | %-16s | %-10s%n", "创建方式", "耗时 (ms)", "吞吐量 (ops/ms)", "相对基准");
		System.out.println("-------------------------------------------+--------------+------------------+-----------");

		// 1. Java 原生 new
		long start0 = System.nanoTime();
		for (int i = 0; i < ITERATIONS_TIMES; i++) {
			BenchmarkTarget b = new BenchmarkTarget(i, "msg");
		}
		double time0 = (System.nanoTime() - start0) / doubleIterations;
		printRow("1. Java 原生 new BenchmarkTarget(..)", time0, ITERATIONS_TIMES, 1.0);

		// 2. 传统反射 Constructor.newInstance
		Constructor<?> ctor = BenchmarkTarget.class.getDeclaredConstructor(int.class, String.class);
		ctor.setAccessible(true);
		long startReflect = System.nanoTime();
		for (int i = 0; i < ITERATIONS_TIMES; i++) {
			Object o = ctor.newInstance(i, "msg");
		}
		double timeReflect = (System.nanoTime() - startReflect) / doubleIterations;
		printRow("2. Constructor.newInstance(Object[])", timeReflect, ITERATIONS_TIMES, timeReflect / time0);

		// 3. MethodHandle Constructor
		MethodHandle ctorMh  = Magic.lookup.unreflectConstructor(ctor);
		long         startMh = System.nanoTime();
		for (int i = 0; i < ITERATIONS_TIMES; i++) {
			Object o = ctorMh.invoke(i, "msg");
		}
		double timeMh = (System.nanoTime() - startMh) / doubleIterations;
		printRow("3. MethodHandle.invoke(ctor)", timeMh, ITERATIONS_TIMES, timeMh / time0);

		// 4. 新架构 MagicConstructorInvoker.newInstance (Object[])
		MagicJIT.MagicConstructorInvoker invoker         = MagicJIT.getConstructorInvoker(BenchmarkTarget.class, 2);
		long                             startInvokerArr = System.nanoTime();
		Object[]                         arr             = new Object[2];
		arr[1] = "msg";
		for (int i = 0; i < ITERATIONS_TIMES; i++) {
			arr[0] = i;
			Object o = invoker.newInstance(arr);
		}
		double timeInvokerArr = (System.nanoTime() - startInvokerArr) / doubleIterations;
		printRow("4. MagicConstructorInvoker (Object[])", timeInvokerArr, ITERATIONS_TIMES, timeInvokerArr / time0);

		// 5. 新架构 MagicConstructorInvoker.newInstance2 (零MH/零分配/linkToSpecial)
		long startInvoker2 = System.nanoTime();
		for (int i = 0; i < ITERATIONS_TIMES; i++) {
			Object o = invoker.newInstance2(i, "msg");
		}
		double timeInvoker2 = (System.nanoTime() - startInvoker2) / doubleIterations;
		printRow("5. MagicConstructorInvoker.newInstance2", timeInvoker2, ITERATIONS_TIMES, timeInvoker2 / time0);
	}

	/**
	 * 基准 3：不同 AccessMode 模式对比
	 */
	private void benchmarkAccessModesComparison() throws Throwable {
		System.out.println("\n【基准 3】多 AccessMode 模式特化直调性能对比 (" + ITERATIONS_TIMES + " 次)");
		System.out.printf("%-32s | %-12s | %-16s | %-10s%n", "访问模式 (AccessMode)", "耗时 (ms)", "吞吐量 (ops/ms)", "相对比率");
		System.out.println("---------------------------------+--------------+------------------+-----------");

		BenchmarkTarget target           = new BenchmarkTarget(99, "modeTest");

		// 1. UNSAFE_AND_METHODHANDLE
		Method       multiply = BenchmarkTarget.class.getDeclaredMethod("multiply", int.class, int.class);
		MethodHandle mh       = MagicJIT.createExactMethodStub(BenchmarkTarget.class, multiply);
		long         startMH  = System.nanoTime();
		for (int i = 0; i < ITERATIONS_TIMES; i++) {
			mh.invoke(target, i, 3);
		}
		double timeMH = (System.nanoTime() - startMH) / doubleIterations;
		printRow2("1. UNSAFE_AND_METHODHANDLE", timeMH, ITERATIONS_TIMES, 1.0);

		// 2. UNSAFE_AND_LINKTO (本轮重构核心：<clinit> resolveOrFail + linkTo 原语)
		MagicJIT.MagicInvoker linkToInvoker = MagicJIT.getMethodInvoker(BenchmarkTarget.class, "multiply", 2, false, AccessMode.UNSAFE_AND_LINKTO);
		long                  startLinkTo   = System.nanoTime();
		for (int i = 0; i < ITERATIONS_TIMES; i++) {
			linkToInvoker.invoke2(target, i, 3);
		}
		double timeLinkTo = (System.nanoTime() - startLinkTo) / doubleIterations;
		printRow2("2. UNSAFE_AND_LINKTO", timeLinkTo, ITERATIONS_TIMES, timeLinkTo / timeMH);

		// 2.1 UNSAFE_AND_LINKTO (invokeInt2 原生零装箱直调)
		long startLinkToInt2 = System.nanoTime();
		for (int i = 0; i < ITERATIONS_TIMES; i++) {
			linkToInvoker.invokeInt2(target, i, 3);
		}
		double timeLinkToInt2 = (System.nanoTime() - startLinkToInt2) / doubleIterations;
		printRow2("2.1 LINKTO invokeInt2 (零装箱)", timeLinkToInt2, ITERATIONS_TIMES, timeLinkToInt2 / timeMH);

		// 3. MAGIC_ACCESSOR (经典特权字节码)
		MagicJIT.MagicInvoker accessorInvoker = MagicJIT.getMethodInvoker(BenchmarkTarget.class, "multiply", 2, false, AccessMode.MAGIC_ACCESSOR);
		long                  startAccessor   = System.nanoTime();
		for (int i = 0; i < ITERATIONS_TIMES; i++) {
			accessorInvoker.invoke2(target, i, 3);
		}
		double timeAccessor = (System.nanoTime() - startAccessor) / doubleIterations;
		printRow2("3. MAGIC_ACCESSOR", timeAccessor, ITERATIONS_TIMES, timeAccessor / timeMH);

		// 3.1 MAGIC_ACCESSOR (invokeInt2 原生零装箱直调)
		long startAccessorInt2 = System.nanoTime();
		for (int i = 0; i < ITERATIONS_TIMES; i++) {
			accessorInvoker.invokeInt2(target, i, 3);
		}
		double timeAccessorInt2 = (System.nanoTime() - startAccessorInt2) / doubleIterations;
		printRow2("3.1 ACCESSOR invokeInt2 (零装箱)", timeAccessorInt2, ITERATIONS_TIMES, timeAccessorInt2 / timeMH);

		// 4. NESTMATE (同巢隐藏类原生字节码直调)
		MagicJIT.MagicInvoker nestmateInvoker = MagicJIT.getMethodInvoker(BenchmarkTarget.class, "multiply", 2, false, AccessMode.NESTMATE);
		if (nestmateInvoker != null) {
			long startNestmate = System.nanoTime();
			for (int i = 0; i < ITERATIONS_TIMES; i++) {
				nestmateInvoker.invoke2(target, i, 3);
			}
			double timeNestmate = (System.nanoTime() - startNestmate) / doubleIterations;
			printRow2("4. NESTMATE (Plan C 同巢隐藏类)", timeNestmate, ITERATIONS_TIMES, timeNestmate / timeMH);

			// 4.1 NESTMATE (invokeInt2 原生零装箱直调)
			long startNestmateInt2 = System.nanoTime();
			for (int i = 0; i < ITERATIONS_TIMES; i++) {
				nestmateInvoker.invokeInt2(target, i, 3);
			}
			double timeNestmateInt2 = (System.nanoTime() - startNestmateInt2) / doubleIterations;
			printRow2("4.1 NESTMATE invokeInt2 (零装箱)", timeNestmateInt2, ITERATIONS_TIMES, timeNestmateInt2 / timeMH);
		}
	}

	/**
	 * 基准 4：端到端 JS 引擎中的方法调用与实例化
	 */
	private void benchmarkEndToEndJSEngine() throws Throwable {
		System.out.println("\n【基准 4】端到端 JS 引擎执行性能 (JSContext.eval) " + ITERATIONS_TIMES + " 次");
		System.out.printf("%-42s | %-12s | %-16s%n", "测试场景", "耗时 (ms)", "吞吐量 (ops/ms)");
		System.out.println("-------------------------------------------+--------------+------------------");

		BenchmarkTarget target = new BenchmarkTarget(100, "jsTarget");

		// 场景 A-1: 预编译 JSFunction (0 编译开销，纯字节码执行循环)
		JSContext cxFn = new JSContext();
		cxFn.set("target", target);
		cxFn.eval("function benchMethod(n) { var sum = 0; for (var i = 0; i < n; i++) { sum += target.multiply(i, 2); } return sum; }");
		hope.magic.js.runtime.JSFunction fn        = (hope.magic.js.runtime.JSFunction) cxFn.get("benchMethod");
		Object[]                         warmupArg = new Object[]{100_000};
		Object[]                         iterArg   = new Object[]{ITERATIONS_TIMES};
		// 预热 JIT
		fn.call(cxFn, null, warmupArg);

		long   startFn = System.nanoTime();
		Object resFn   = fn.call(cxFn, null, iterArg);
		double timeFn  = (System.nanoTime() - startFn) / doubleIterations;
		System.out.printf("%-42s | %-12s | %-16s%n",
		 "预编译 JSFunction (0编译开销纯循环调用)",
		 DF.format(timeFn),
		 DF_INT.format(ITERATIONS_TIMES / timeFn)
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
		 """.formatted(ITERATIONS_TIMES);

		long   startA = System.nanoTime();
		Object resA   = cx1.eval(scriptMethod);
		double timeA  = (System.nanoTime() - startA) / doubleIterations;
		System.out.printf("%-42s | %-12s | %-16s%n",
		 "JSContext.eval (含Lexer+Parser+ASM编译)",
		 DF.format(timeA),
		 DF_INT.format(ITERATIONS_TIMES / timeA)
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
		 """.formatted(ITERATIONS_TIMES);

		long   startB = System.nanoTime();
		Object resB   = cx2.eval(scriptCtor);
		double timeB  = (System.nanoTime() - startB) / doubleIterations;
		System.out.printf("%-42s | %-12s | %-16s%n",
		 "JS 循环实例化 Java 对象 (new BenchmarkTarget)",
		 DF.format(timeB),
		 DF_INT.format(ITERATIONS_TIMES / timeB)
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
