package hope.magic.js.test;

import hope.magic.annotation.AccessMode;
import hope.magic.js.runtime.*;
import hope.magic.runtime.Magic;
import org.junit.jupiter.api.Test;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.text.DecimalFormat;

/**
 * MagicJIT 多方案与原生直调性能微基准测试 (重构严谨版)。
 * <p>
 * 修正了原测试中的四大基准测试硬伤：
 * <ol>
 *   <li><b>单位定义混淆修复：</b>明确区分“总耗时 (ms)”、“单次平均耗时 (ns/op)”与“真实吞吐量 (ops/ms)”；</li>
 *   <li><b>防死代码消除 (Anti-DCE)：</b>循环内所有调用的返回值必须被链式累加并最终写入 volatile 黑洞，杜绝 C2 编译器将无副作用循环整段消除；</li>
 *   <li><b>防标量替换与伪逃逸：</b>构造器测试中强制消费对象属性（如 {@code b.getId()}），确保对象结构被真实实例化；</li>
 *   <li><b>专属预热机制：</b>每个被测通道在正式计时前均进行独立预热，确保 JIT 编译器完成 Tier 4 (C2) 编译。</li>
 * </ol>
 */
public class MagicJITLinkToBenchmarkTest {

	public static final int WARMUP_TIMES     = 1_000_000;
	public static final int METHOD_OPS       = 50_000_000; // 5000万次，保证统计精度的同时避免过长等待
	public static final int CTOR_OPS         = 10_000_000; // 1000万次，避免数亿对象分配引发 GC 停顿失真
	public static final int JS_OPS           = 10_000_000; // 1000万次 JS 端到端

	// 防 DCE 黑洞 (Compiler Blackhole)，阻断 JIT 激进死代码消除
	public static volatile long   BLACKHOLE_LONG;
	public static volatile Object BLACKHOLE_OBJ;

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

		public static int directMultiply(BenchmarkTarget target, int a, int b) {
			return target.multiply(a, b);
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
		System.out.println("=======================================================================================================");
		System.out.println("                 MagicJIT 原生直调与多方案严谨基准测试报告 (含防 DCE 与真实物理单位)                     ");
		System.out.println("=======================================================================================================");

		benchmarkPrivateMethodInvocation();
		benchmarkConstructorInvocation();
		benchmarkAccessModesComparison();
		benchmarkEndToEndJSEngine();

		System.out.println("=======================================================================================================");
	}

	/**
	 * 【基准 1】私有方法调用吞吐与延时对比
	 */
	private void benchmarkPrivateMethodInvocation() throws Throwable {
		System.out.println("\n【基准 1】私有方法调用 (multiply: int * int) " + METHOD_OPS + " 次");
		System.out.printf("%-42s | %-12s | %-14s | %-16s | %-10s%n",
			"调用方式", "总耗时 (ms)", "单次耗时 (ns)", "吞吐量 (ops/ms)", "相对基准");
		System.out.println("-------------------------------------------+--------------+----------------+------------------+-----------");

		BenchmarkTarget target = new BenchmarkTarget(42, "target");
		Method          m      = BenchmarkTarget.class.getDeclaredMethod("multiply", int.class, int.class);
		m.setAccessible(true);

		// 1. Java Direct (原生直接调用基准)
		long sum0 = 0;
		for (int i = 0; i < WARMUP_TIMES; i++) sum0 += BenchmarkTarget.directMultiply(target, i, 2);
		long start0 = System.nanoTime();
		for (int i = 0; i < METHOD_OPS; i++) {
			sum0 += BenchmarkTarget.directMultiply(target, i, 2);
		}
		long totalNs0 = System.nanoTime() - start0;
		BLACKHOLE_LONG = sum0;
		double baseNs = (double) totalNs0 / METHOD_OPS;
		printRow("1. Java Direct (原生直接调用基准)", totalNs0, METHOD_OPS, baseNs);

		// 2. 传统反射 Method.invoke
		long sumReflect = 0;
		for (int i = 0; i < WARMUP_TIMES; i++) sumReflect += ((Number) m.invoke(target, i, 2)).intValue();
		long startReflect = System.nanoTime();
		for (int i = 0; i < METHOD_OPS; i++) {
			sumReflect += ((Number) m.invoke(target, i, 2)).intValue();
		}
		long totalNsReflect = System.nanoTime() - startReflect;
		BLACKHOLE_LONG = sumReflect;
		printRow("2. java.lang.reflect.Method.invoke", totalNsReflect, METHOD_OPS, baseNs);

		// 3. 原生 MethodHandle.invokeExact
		MethodHandle mh = Magic.lookup.unreflect(m);
		long sumMh = 0;
		for (int i = 0; i < WARMUP_TIMES; i++) sumMh += (int) mh.invokeExact(target, i, 2);
		long startMh = System.nanoTime();
		for (int i = 0; i < METHOD_OPS; i++) {
			sumMh += (int) mh.invokeExact(target, i, 2);
		}
		long totalNsMh = System.nanoTime() - startMh;
		BLACKHOLE_LONG = sumMh;
		printRow("3. MethodHandle.invokeExact", totalNsMh, METHOD_OPS, baseNs);

		// 4. MH + asSpreader (数组中转)
		MethodHandle spreader = mh.asType(MethodType.methodType(Object.class, BenchmarkTarget.class, Object.class, Object.class))
			.asSpreader(Object[].class, 2);
		Object[] arr = new Object[2];
		arr[1] = 2;
		long sumSpreader = 0;
		for (int i = 0; i < WARMUP_TIMES; i++) {
			arr[0] = i;
			sumSpreader += ((Number) (Object) spreader.invokeExact(target, arr)).intValue();
		}
		long startSpreader = System.nanoTime();
		for (int i = 0; i < METHOD_OPS; i++) {
			arr[0] = i;
			sumSpreader += ((Number) (Object) spreader.invokeExact(target, arr)).intValue();
		}
		long totalNsSpreader = System.nanoTime() - startSpreader;
		BLACKHOLE_LONG = sumSpreader;
		printRow("4. MH + asSpreader (数组中转)", totalNsSpreader, METHOD_OPS, baseNs);

		// 5. MagicInvoker.invoke (Object[] 数组中转)
		MagicJIT.MagicInvoker invoker = MagicJIT.getMethodInvoker(BenchmarkTarget.class, "multiply", 2, false);
		long sumInvokerArr = 0;
		for (int i = 0; i < WARMUP_TIMES; i++) {
			arr[0] = i;
			sumInvokerArr += ((Number) invoker.invoke(target, arr)).intValue();
		}
		long startInvokerArr = System.nanoTime();
		for (int i = 0; i < METHOD_OPS; i++) {
			arr[0] = i;
			sumInvokerArr += ((Number) invoker.invoke(target, arr)).intValue();
		}
		long totalNsInvokerArr = System.nanoTime() - startInvokerArr;
		BLACKHOLE_LONG = sumInvokerArr;
		printRow("5. MagicInvoker.invoke (Object[])", totalNsInvokerArr, METHOD_OPS, baseNs);

		// 6. MagicInvoker.invoke2 (特化直调，含传参装箱)
		long sumInvoker2 = 0;
		for (int i = 0; i < WARMUP_TIMES; i++) sumInvoker2 += ((Number) invoker.invoke2(target, i, 2)).intValue();
		long startInvoker2 = System.nanoTime();
		for (int i = 0; i < METHOD_OPS; i++) {
			sumInvoker2 += ((Number) invoker.invoke2(target, i, 2)).intValue();
		}
		long totalNsInvoker2 = System.nanoTime() - startInvoker2;
		BLACKHOLE_LONG = sumInvoker2;
		printRow("6. MagicInvoker.invoke2 (含传参装箱)", totalNsInvoker2, METHOD_OPS, baseNs);

		// 6.1 MagicInvoker.invoke2 (零装箱纯调度分发: 复用对象传参)
		Integer boxA = 6, boxB = 7;
		long sumInvokerNoBox = 0;
		for (int i = 0; i < WARMUP_TIMES; i++) sumInvokerNoBox += ((Number) invoker.invoke2(target, boxA, boxB)).intValue();
		long startInvokerNoBox = System.nanoTime();
		for (int i = 0; i < METHOD_OPS; i++) {
			sumInvokerNoBox += ((Number) invoker.invoke2(target, boxA, boxB)).intValue();
		}
		long totalNsInvokerNoBox = System.nanoTime() - startInvokerNoBox;
		BLACKHOLE_LONG = sumInvokerNoBox;
		printRow("6.1 MagicInvoker.invoke2 (复用装箱对象)", totalNsInvokerNoBox, METHOD_OPS, baseNs);

		// 6.2 MagicInvoker.invokeInt2 (原生类型 100% 零装箱直调通道)
		long sumInvokerInt2 = 0;
		for (int i = 0; i < WARMUP_TIMES; i++) sumInvokerInt2 += invoker.invokeInt2(target, i, 2);
		long startInvokerInt2 = System.nanoTime();
		for (int i = 0; i < METHOD_OPS; i++) {
			sumInvokerInt2 += invoker.invokeInt2(target, i, 2);
		}
		long totalNsInvokerInt2 = System.nanoTime() - startInvokerInt2;
		BLACKHOLE_LONG = sumInvokerInt2;
		printRow("6.2 MagicInvoker.invokeInt2 (零装箱原生直调)", totalNsInvokerInt2, METHOD_OPS, baseNs);

		// 7. ExactMethodStub
		MethodHandle exactStub = MagicJIT.createExactMethodStub(BenchmarkTarget.class, m);
		long sumExact = 0;
		for (int i = 0; i < WARMUP_TIMES; i++) sumExact += ((Number) exactStub.invoke(target, i, 2)).intValue();
		long startExact = System.nanoTime();
		for (int i = 0; i < METHOD_OPS; i++) {
			sumExact += ((Number) exactStub.invoke(target, i, 2)).intValue();
		}
		long totalNsExact = System.nanoTime() - startExact;
		BLACKHOLE_LONG = sumExact;
		printRow("7. MagicJIT.createExactMethodStub", totalNsExact, METHOD_OPS, baseNs);
	}

	/**
	 * 【基准 2】构造器实例化性能对比
	 */
	private void benchmarkConstructorInvocation() throws Throwable {
		System.out.println("\n【基准 2】构造器对象创建 (new BenchmarkTarget(int, String)) " + CTOR_OPS + " 次");
		System.out.printf("%-42s | %-12s | %-14s | %-16s | %-10s%n",
			"创建方式", "总耗时 (ms)", "单次耗时 (ns)", "吞吐量 (ops/ms)", "相对基准");
		System.out.println("-------------------------------------------+--------------+----------------+------------------+-----------");

		Constructor<?> ctor = BenchmarkTarget.class.getDeclaredConstructor(int.class, String.class);
		ctor.setAccessible(true);
		MethodHandle ctorMh = Magic.lookup.unreflectConstructor(ctor);
		MagicJIT.MagicConstructorInvoker invoker = MagicJIT.getConstructorInvoker(BenchmarkTarget.class, 2);

		// 1. Java 原生 new
		long sum0 = 0;
		for (int i = 0; i < 500_000; i++) {
			BenchmarkTarget b = new BenchmarkTarget(i, "msg");
			sum0 += b.getId();
		}
		long start0 = System.nanoTime();
		for (int i = 0; i < CTOR_OPS; i++) {
			BenchmarkTarget b = new BenchmarkTarget(i, "msg");
			sum0 += b.getId();
		}
		long totalNs0 = System.nanoTime() - start0;
		BLACKHOLE_LONG = sum0;
		double baseNs = (double) totalNs0 / CTOR_OPS;
		printRow("1. Java 原生 new BenchmarkTarget(..)", totalNs0, CTOR_OPS, baseNs);

		// 2. 传统反射 Constructor.newInstance
		long sumReflect = 0;
		for (int i = 0; i < 500_000; i++) {
			BenchmarkTarget o = (BenchmarkTarget) ctor.newInstance(i, "msg");
			sumReflect += o.getId();
		}
		long startReflect = System.nanoTime();
		for (int i = 0; i < CTOR_OPS; i++) {
			BenchmarkTarget o = (BenchmarkTarget) ctor.newInstance(i, "msg");
			sumReflect += o.getId();
		}
		long totalNsReflect = System.nanoTime() - startReflect;
		BLACKHOLE_LONG = sumReflect;
		printRow("2. Constructor.newInstance(Object[])", totalNsReflect, CTOR_OPS, baseNs);

		// 3. MethodHandle Constructor
		long sumMh = 0;
		for (int i = 0; i < 500_000; i++) {
			BenchmarkTarget o = (BenchmarkTarget) ctorMh.invoke(i, "msg");
			sumMh += o.getId();
		}
		long startMh = System.nanoTime();
		for (int i = 0; i < CTOR_OPS; i++) {
			BenchmarkTarget o = (BenchmarkTarget) ctorMh.invoke(i, "msg");
			sumMh += o.getId();
		}
		long totalNsMh = System.nanoTime() - startMh;
		BLACKHOLE_LONG = sumMh;
		printRow("3. MethodHandle.invoke(ctor)", totalNsMh, CTOR_OPS, baseNs);

		// 4. MagicConstructorInvoker.newInstance (Object[])
		Object[] arr = new Object[2];
		arr[1] = "msg";
		long sumInvokerArr = 0;
		for (int i = 0; i < 500_000; i++) {
			arr[0] = i;
			BenchmarkTarget o = (BenchmarkTarget) invoker.newInstance(arr);
			sumInvokerArr += o.getId();
		}
		long startInvokerArr = System.nanoTime();
		for (int i = 0; i < CTOR_OPS; i++) {
			arr[0] = i;
			BenchmarkTarget o = (BenchmarkTarget) invoker.newInstance(arr);
			sumInvokerArr += o.getId();
		}
		long totalNsInvokerArr = System.nanoTime() - startInvokerArr;
		BLACKHOLE_LONG = sumInvokerArr;
		printRow("4. MagicConstructorInvoker (Object[])", totalNsInvokerArr, CTOR_OPS, baseNs);

		// 5. MagicConstructorInvoker.newInstance2 (特化直调)
		long sumInvoker2 = 0;
		for (int i = 0; i < 500_000; i++) {
			BenchmarkTarget o = (BenchmarkTarget) invoker.newInstance2(i, "msg");
			sumInvoker2 += o.getId();
		}
		long startInvoker2 = System.nanoTime();
		for (int i = 0; i < CTOR_OPS; i++) {
			BenchmarkTarget o = (BenchmarkTarget) invoker.newInstance2(i, "msg");
			sumInvoker2 += o.getId();
		}
		long totalNsInvoker2 = System.nanoTime() - startInvoker2;
		BLACKHOLE_LONG = sumInvoker2;
		printRow("5. MagicConstructorInvoker.newInstance2", totalNsInvoker2, CTOR_OPS, baseNs);
	}

	/**
	 * 【基准 3】不同 AccessMode 模式对比
	 */
	private void benchmarkAccessModesComparison() throws Throwable {
		System.out.println("\n【基准 3】多 AccessMode 模式特化直调性能对比 (" + METHOD_OPS + " 次)");
		System.out.printf("%-36s | %-12s | %-14s | %-16s | %-10s%n",
			"访问模式 (AccessMode)", "总耗时 (ms)", "单次耗时 (ns)", "吞吐量 (ops/ms)", "相对 MH 比");
		System.out.println("-------------------------------------+--------------+----------------+------------------+-----------");

		BenchmarkTarget target = new BenchmarkTarget(99, "modeTest");
		Method multiply = BenchmarkTarget.class.getDeclaredMethod("multiply", int.class, int.class);
		multiply.setAccessible(true);

		// 1. UNSAFE_AND_METHODHANDLE (基准)
		MethodHandle mh = MagicJIT.createExactMethodStub(BenchmarkTarget.class, multiply);
		long sumMH = 0;
		for (int i = 0; i < WARMUP_TIMES; i++) sumMH += ((Number) mh.invoke(target, i, 3)).intValue();
		long startMH = System.nanoTime();
		for (int i = 0; i < METHOD_OPS; i++) {
			sumMH += ((Number) mh.invoke(target, i, 3)).intValue();
		}
		long totalNsMH = System.nanoTime() - startMH;
		BLACKHOLE_LONG = sumMH;
		double baseNs = (double) totalNsMH / METHOD_OPS;
		printRow2("1. UNSAFE_AND_METHODHANDLE", totalNsMH, METHOD_OPS, baseNs);

		// 2. UNSAFE_AND_LINKTO (invoke2)
		MagicJIT.MagicInvoker linkToInvoker = MagicJIT.getMethodInvoker(BenchmarkTarget.class, "multiply", 2, false, AccessMode.UNSAFE_AND_LINKTO);
		long sumLinkTo = 0;
		for (int i = 0; i < WARMUP_TIMES; i++) sumLinkTo += ((Number) linkToInvoker.invoke2(target, i, 3)).intValue();
		long startLinkTo = System.nanoTime();
		for (int i = 0; i < METHOD_OPS; i++) {
			sumLinkTo += ((Number) linkToInvoker.invoke2(target, i, 3)).intValue();
		}
		long totalNsLinkTo = System.nanoTime() - startLinkTo;
		BLACKHOLE_LONG = sumLinkTo;
		printRow2("2. UNSAFE_AND_LINKTO (invoke2)", totalNsLinkTo, METHOD_OPS, baseNs);

		// 2.1 UNSAFE_AND_LINKTO (invokeInt2)
		long sumLinkToInt2 = 0;
		for (int i = 0; i < WARMUP_TIMES; i++) sumLinkToInt2 += linkToInvoker.invokeInt2(target, i, 3);
		long startLinkToInt2 = System.nanoTime();
		for (int i = 0; i < METHOD_OPS; i++) {
			sumLinkToInt2 += linkToInvoker.invokeInt2(target, i, 3);
		}
		long totalNsLinkToInt2 = System.nanoTime() - startLinkToInt2;
		BLACKHOLE_LONG = sumLinkToInt2;
		printRow2("2.1 LINKTO (invokeInt2 零装箱)", totalNsLinkToInt2, METHOD_OPS, baseNs);

		// 3. MAGIC_ACCESSOR (invoke2)
		MagicJIT.MagicInvoker accessorInvoker = MagicJIT.getMethodInvoker(BenchmarkTarget.class, "multiply", 2, false, AccessMode.MAGIC_ACCESSOR);
		if (accessorInvoker != null) {
			long sumAccessor = 0;
			for (int i = 0; i < WARMUP_TIMES; i++) sumAccessor += ((Number) accessorInvoker.invoke2(target, i, 3)).intValue();
			long startAccessor = System.nanoTime();
			for (int i = 0; i < METHOD_OPS; i++) {
				sumAccessor += ((Number) accessorInvoker.invoke2(target, i, 3)).intValue();
			}
			long totalNsAccessor = System.nanoTime() - startAccessor;
			BLACKHOLE_LONG = sumAccessor;
			printRow2("3. MAGIC_ACCESSOR (invoke2)", totalNsAccessor, METHOD_OPS, baseNs);

			// 3.1 MAGIC_ACCESSOR (invokeInt2)
			long sumAccessorInt2 = 0;
			for (int i = 0; i < WARMUP_TIMES; i++) sumAccessorInt2 += accessorInvoker.invokeInt2(target, i, 3);
			long startAccessorInt2 = System.nanoTime();
			for (int i = 0; i < METHOD_OPS; i++) {
				sumAccessorInt2 += accessorInvoker.invokeInt2(target, i, 3);
			}
			long totalNsAccessorInt2 = System.nanoTime() - startAccessorInt2;
			BLACKHOLE_LONG = sumAccessorInt2;
			printRow2("3.1 ACCESSOR (invokeInt2 零装箱)", totalNsAccessorInt2, METHOD_OPS, baseNs);
		}

		// 4. NESTMATE (Plan C 同巢隐藏类 invoke2)
		MagicJIT.MagicInvoker nestmateInvoker = MagicJIT.getMethodInvoker(BenchmarkTarget.class, "multiply", 2, false, AccessMode.NESTMATE);
		if (nestmateInvoker != null) {
			long sumNestmate = 0;
			for (int i = 0; i < WARMUP_TIMES; i++) sumNestmate += ((Number) nestmateInvoker.invoke2(target, i, 3)).intValue();
			long startNestmate = System.nanoTime();
			for (int i = 0; i < METHOD_OPS; i++) {
				sumNestmate += ((Number) nestmateInvoker.invoke2(target, i, 3)).intValue();
			}
			long totalNsNestmate = System.nanoTime() - startNestmate;
			BLACKHOLE_LONG = sumNestmate;
			printRow2("4. NESTMATE (invoke2)", totalNsNestmate, METHOD_OPS, baseNs);

			// 4.1 NESTMATE (invokeInt2 原生零装箱直调)
			long sumNestmateInt2 = 0;
			for (int i = 0; i < WARMUP_TIMES; i++) sumNestmateInt2 += nestmateInvoker.invokeInt2(target, i, 3);
			long startNestmateInt2 = System.nanoTime();
			for (int i = 0; i < METHOD_OPS; i++) {
				sumNestmateInt2 += nestmateInvoker.invokeInt2(target, i, 3);
			}
			long totalNsNestmateInt2 = System.nanoTime() - startNestmateInt2;
			BLACKHOLE_LONG = sumNestmateInt2;
			printRow2("4.1 NESTMATE (invokeInt2 零装箱)", totalNsNestmateInt2, METHOD_OPS, baseNs);
		}
	}

	/**
	 * 【基准 4】端到端 JS 引擎中的方法调用与实例化
	 */
	private void benchmarkEndToEndJSEngine() throws Throwable {
		System.out.println("\n【基准 4】端到端 JS 引擎执行性能 (JSContext.eval) " + JS_OPS + " 次");
		System.out.printf("%-42s | %-12s | %-14s | %-16s%n", "测试场景", "总耗时 (ms)", "单次耗时 (ns)", "吞吐量 (ops/ms)");
		System.out.println("-------------------------------------------+--------------+----------------+------------------");

		BenchmarkTarget target = new BenchmarkTarget(100, "jsTarget");

		// 场景 A-1: 预编译 JSFunction (0 编译开销，纯字节码执行循环)
		JSContext cxFn = new JSContext();
		cxFn.set("target", target);
		cxFn.eval("function benchMethod(n) { var sum = 0; for (var i = 0; i < n; i++) { sum += target.multiply(i, 2); } return sum; }");
		hope.magic.js.runtime.JSFunction fn = (hope.magic.js.runtime.JSFunction) cxFn.get("benchMethod");
		// 预热 JIT
		fn.call(cxFn, null, new Object[]{100_000});

		long startFn = System.nanoTime();
		Object resFn = fn.call(cxFn, null, new Object[]{JS_OPS});
		long totalNsFn = System.nanoTime() - startFn;
		BLACKHOLE_OBJ = resFn;
		double totalMsFn = totalNsFn / 1_000_000.0;
		double nsPerOpFn = (double) totalNsFn / JS_OPS;
		double opsPerMsFn = (double) JS_OPS / totalMsFn;
		System.out.printf("%-42s | %-12s | %-14s | %-16s%n",
			"预编译 JSFunction (0编译纯字节码循环)",
			DF.format(totalMsFn),
			DF.format(nsPerOpFn),
			DF_INT.format(opsPerMsFn)
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
		""".formatted(JS_OPS);

		long startA = System.nanoTime();
		Object resA = cx1.eval(scriptMethod);
		long totalNsA = System.nanoTime() - startA;
		BLACKHOLE_OBJ = resA;
		double totalMsA = totalNsA / 1_000_000.0;
		double nsPerOpA = (double) totalNsA / JS_OPS;
		double opsPerMsA = (double) JS_OPS / totalMsA;
		System.out.printf("%-42s | %-12s | %-14s | %-16s%n",
			"JSContext.eval (含Lexer+Parser+ASM编译)",
			DF.format(totalMsA),
			DF.format(nsPerOpA),
			DF_INT.format(opsPerMsA)
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
		""".formatted(JS_OPS);

		long startB = System.nanoTime();
		Object resB = cx2.eval(scriptCtor);
		long totalNsB = System.nanoTime() - startB;
		BLACKHOLE_OBJ = resB;
		double totalMsB = totalNsB / 1_000_000.0;
		double nsPerOpB = (double) totalNsB / JS_OPS;
		double opsPerMsB = (double) JS_OPS / totalMsB;
		System.out.printf("%-42s | %-12s | %-14s | %-16s%n",
			"JS 循环实例化 Java 对象 (new BenchmarkTarget)",
			DF.format(totalMsB),
			DF.format(nsPerOpB),
			DF_INT.format(opsPerMsB)
		);
	}

	private void printRow(String name, long totalNs, int ops, double baselineNs) {
		double totalMs = totalNs / 1_000_000.0;
		double nsPerOp = (double) totalNs / ops;
		double opsPerMs = (double) ops / totalMs;
		double ratio = nsPerOp / baselineNs;
		System.out.printf("%-42s | %-12s | %-14s | %-16s | %-10s%n",
			name,
			DF.format(totalMs),
			DF.format(nsPerOp),
			DF_INT.format(opsPerMs),
			DF.format(ratio) + "x"
		);
	}

	private void printRow2(String name, long totalNs, int ops, double baselineNs) {
		double totalMs = totalNs / 1_000_000.0;
		double nsPerOp = (double) totalNs / ops;
		double opsPerMs = (double) ops / totalMs;
		double ratio = nsPerOp / baselineNs;
		System.out.printf("%-36s | %-12s | %-14s | %-16s | %-10s%n",
			name,
			DF.format(totalMs),
			DF.format(nsPerOp),
			DF_INT.format(opsPerMs),
			DF.format(ratio) + "x"
		);
	}
}
