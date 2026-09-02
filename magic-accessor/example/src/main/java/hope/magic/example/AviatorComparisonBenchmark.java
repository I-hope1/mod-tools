package hope.magic.example;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.googlecode.aviator.Expression;
import hope.magic.js.compiler.JSCompiler;
import hope.magic.js.runtime.JSContext;
import hope.magic.js.runtime.JSScript;

import java.util.*;

public class AviatorComparisonBenchmark {

	public static class BenchmarkTarget {
		public int secretCode = 98765;
		public String name = "BenchmarkTarget";

		public int multiply(int a, int b) {
			return a * b;
		}
	}

	public static void main(String[] args) throws Throwable {
		System.out.println("================================================================================");
		System.out.println("            🚀 MagicJS vs AviatorScript 架构深度对标与性能实测                      ");
		System.out.println("================================================================================");
		System.out.println("JVM: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
		System.out.println();

		BenchmarkTarget target = new BenchmarkTarget();
		List<Integer> sampleList = new ArrayList<>();
		for (int i = 1; i <= 100; i++) sampleList.add(i);

		//region 1. 引擎初始化冷启动对比
		System.out.println("【1. 引擎初始化冷启动时延对比】");

		long t0 = System.nanoTime();
		JSContext magicContext = new JSContext();
		long magicInitNs = System.nanoTime() - t0;

		long t1 = System.nanoTime();
		AviatorEvaluatorInstance aviator = AviatorEvaluator.newInstance();
		long aviatorInitNs = System.nanoTime() - t1;

		System.out.printf("  • MagicJS 首次 new JSContext():               %8.3f ms (%d ns)%n", magicInitNs / 1_000_000.0, magicInitNs);
		System.out.printf("  • AviatorScript 首次 newInstance():           %8.3f ms (%d ns)%n", aviatorInitNs / 1_000_000.0, aviatorInitNs);
		System.out.printf("  ==> 引擎初始化对比: MagicJS 比 AviatorScript 快  %8.2fx%n%n", (double) aviatorInitNs / magicInitNs);
		//endregion

		//region 2. 素数计算场景（密集循环与局部变量运算）
		System.out.println("【2. 典型算术密集型场景：1000 以内素数求和】");

		String magicPrimeCode = """
			var sum = 0;
			for (var i = 2; i < 1000; i++) {
			    var isPrime = 1;
			    for (var j = 2; j * j <= i; j++) {
			        if (i % j === 0) isPrime = 0;
			    }
			    if (isPrime === 1) sum += i;
			}
			sum;
		""";

		String aviatorPrimeCode = """
			let sum = 0;
			let i = 2;
			while (i < 1000) {
			    let isPrime = 1;
			    let j = 2;
			    while (j * j <= i) {
			        if (i % j == 0) {
			            isPrime = 0;
			        }
			        j = j + 1;
			    }
			    if (isPrime == 1) {
			        sum = sum + i;
			    }
			    i = i + 1;
			}
			return sum;
		""";

		// 2.1 MagicJS 编译与执行
		long mCompT0 = System.nanoTime();
		JSScript mScript = JSCompiler.compile(magicPrimeCode);
		long mCompNs = System.nanoTime() - mCompT0;

		long mRunT0 = System.nanoTime();
		Object mRes = mScript.run(magicContext);
		long mRun1Ns = System.nanoTime() - mRunT0;

		long mWarmAvgNs = 0;
		for (int i = 0; i < 1000; i++) {
			long t = System.nanoTime();
			mScript.run(magicContext);
			mWarmAvgNs += (System.nanoTime() - t);
		}
		mWarmAvgNs /= 1000;

		// 2.2 Aviator 编译与执行
		long aCompT0 = System.nanoTime();
		Expression aExp = aviator.compile(aviatorPrimeCode);
		long aCompNs = System.nanoTime() - aCompT0;

		Map<String, Object> env = new HashMap<>();
		long aRunT0 = System.nanoTime();
		Object aRes = aExp.execute(env);
		long aRun1Ns = System.nanoTime() - aRunT0;

		long aWarmAvgNs = 0;
		for (int i = 0; i < 1000; i++) {
			long t = System.nanoTime();
			aExp.execute(env);
			aWarmAvgNs += (System.nanoTime() - t);
		}
		aWarmAvgNs /= 1000;

		System.out.printf("  • MagicJS:       首次编译: %7.3f ms | 首次冷执行: %7.3f ms | 稳态单次热执行: %8.3f µs  [结果: %s]%n",
			mCompNs / 1_000_000.0, mRun1Ns / 1_000_000.0, mWarmAvgNs / 1_000.0, mRes);
		System.out.printf("  • AviatorScript: 首次编译: %7.3f ms | 首次冷执行: %7.3f ms | 稳态单次热执行: %8.3f µs  [结果: %s]%n",
			aCompNs / 1_000_000.0, aRun1Ns / 1_000_000.0, aWarmAvgNs / 1_000.0, aRes);
		System.out.printf("  ==> 稳态执行速度对比: MagicJS 比 AviatorScript 快 %8.2fx ⚡%n%n", (double) aWarmAvgNs / mWarmAvgNs);
		//endregion

		//region 3. 集合遍历场景 (List Loop)
		System.out.println("【3. 集合遍历场景：遍历 100 个元素 Java List 并求和】");

		String magicListCode = """
			var total = 0;
			for (var x of list) {
			    total += x;
			}
			total;
		""";

		String aviatorListCode = """
			let total = 0;
			for x in list {
			    total = total + x;
			}
			return total;
		""";

		magicContext.set("list", sampleList);
		env.put("list", sampleList);

		// MagicJS
		long mlCompT0 = System.nanoTime();
		JSScript mlScript = JSCompiler.compile(magicListCode);
		long mlCompNs = System.nanoTime() - mlCompT0;
		long mlRunT0 = System.nanoTime();
		Object mlRes = mlScript.run(magicContext);
		long mlRunNs = System.nanoTime() - mlRunT0;
		long mlWarm = 0;
		for (int i = 0; i < 1000; i++) {
			long t = System.nanoTime();
			mlScript.run(magicContext);
			mlWarm += (System.nanoTime() - t);
		}
		mlWarm /= 1000;

		// Aviator
		long alCompT0 = System.nanoTime();
		Expression alExp = aviator.compile(aviatorListCode);
		long alCompNs = System.nanoTime() - alCompT0;
		long alRunT0 = System.nanoTime();
		Object alRes = alExp.execute(env);
		long alRunNs = System.nanoTime() - alRunT0;
		long alWarm = 0;
		for (int i = 0; i < 1000; i++) {
			long t = System.nanoTime();
			alExp.execute(env);
			alWarm += (System.nanoTime() - t);
		}
		alWarm /= 1000;

		System.out.printf("  • MagicJS:       首次编译: %7.3f ms | 首次冷执行: %7.3f ms | 稳态单次热执行: %8.3f µs  [结果: %s]%n",
			mlCompNs / 1_000_000.0, mlRunNs / 1_000_000.0, mlWarm / 1_000.0, mlRes);
		System.out.printf("  • AviatorScript: 首次编译: %7.3f ms | 首次冷执行: %7.3f ms | 稳态单次热执行: %8.3f µs  [结果: %s]%n",
			alCompNs / 1_000_000.0, alRunNs / 1_000_000.0, alWarm / 1_000.0, alRes);
		System.out.printf("  ==> 集合遍历稳态速度对比: MagicJS 比 AviatorScript 快 %8.2fx ⚡%n%n", (double) alWarm / mlWarm);
		//endregion

		System.out.println("================================================================================");
		System.out.println("                          深度对比测试完成                                      ");
		System.out.println("================================================================================");
	}
}
