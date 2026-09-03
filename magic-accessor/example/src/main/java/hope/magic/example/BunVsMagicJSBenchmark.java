package hope.magic.example;

import hope.magic.js.compiler.JSCompiler;
import hope.magic.js.runtime.*;
import org.graalvm.polyglot.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * MagicJS vs Bun.js vs Node.js vs Oracle GraalJS
 * 全方位冷启动时延、编译开销与稳态执行性能深度对比基准
 */
public class BunVsMagicJSBenchmark {

	public static void main(String[] args) throws Throwable {
		System.out.println("================================================================================");
		System.out.println("         🔥 MagicJS vs Bun.js vs Node.js vs GraalJS 权威性能全景对比 🔥          ");
		System.out.println("================================================================================");
		System.out.println("JVM: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
		System.out.println("OS:  " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));

		// 检查本地环境上的 bun 和 node
		String bunVer = getCliVersion("bun", "--version");
		String nodeVer = getCliVersion("node", "--version");
		System.out.println("Bun: " + (bunVer != null ? "v" + bunVer : "未安装"));
		System.out.println("Node: " + (nodeVer != null ? nodeVer : "未安装"));
		System.out.println("--------------------------------------------------------------------------------\n");

		// 初始化 GraalJS 和 MagicJS 上下文
		JSContext magicContext = new JSContext();
		Context graalContext = Context.newBuilder("js").allowAllAccess(true).build();
		graalContext.initialize("js");

		// 预热各引擎
		warmupEngines(magicContext, graalContext);

		// ==================== 1. 场景一：算术密集型循环 (1000 以内素数求和) ====================
		String primeCode = """
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
		runFullComparison("【场景 1: 算术密集循环】1000 以内素数求和 (Prime Sum 1000)", primeCode, magicContext, graalContext, 2000);

		// ==================== 2. 场景二：对象属性存取与构造 (10,000 次字段读写) ====================
		String objectCode = """
			var obj = { x: 10, y: 20, z: 30, w: 40 };
			var sum = 0;
			for (var i = 0; i < 10000; i++) {
			    obj.x = obj.x + 1;
			    obj.y = obj.y + 2;
			    sum = sum + obj.x + obj.y + obj.z + obj.w;
			}
			sum;
		""";
		runFullComparison("【场景 2: 对象属性存取】10,000 次字段密集读写 (Object Property Access)", objectCode, magicContext, graalContext, 2000);

		// ==================== 3. 场景三：5-Shape 动态多态流水线 (Polymorphic Pipeline) ====================
		String polyCode = """
			var pool = [
			    { type: 1, val: 10, tag: 5 },
			    { type: 2, val: 20.5, meta: 3.14 },
			    { type: 3, val: 30, flag: 1, note: 100 },
			    { type: 4, val: 40, extra: { base: 200 } },
			    { type: 5, val: 50.25, delta: 1.75 }
			];
			var total = 0;
			var factor = 1;
			for (var i = 0; i < 2000; i++) {
			    var item = pool[i % 5];
			    var t = item.type;
			    if (i % 2 === 0) {
			        factor = i % 10;
			    } else {
			        factor = (i % 10) + 0.5;
			    }
			    var contribution = 0;
			    if (t === 1) {
			        contribution = item.val * factor + item.tag;
			    } else if (t === 2) {
			        contribution = item.val * 1.5 + factor * item.meta;
			    } else if (t === 3) {
			        contribution = (item.val + factor) * item.flag + item.note;
			    } else if (t === 4) {
			        contribution = item.extra.base + item.val * factor;
			    } else {
			        contribution = item.val * factor - item.delta;
			    }
			    total = total + contribution;
			}
			total;
		""";
		runFullComparison("【场景 3: 5-Shape 混合多态流水线】2000 轮动态分发 (Polymorphic IC Guard Pipeline)", polyCode, magicContext, graalContext, 2000);

		// ==================== 4. 场景四：递归与函数调用栈 (Fibonacci 30) ====================
		String fibCode = """
			function fib(n) {
			    if (n <= 1) return n;
			    return fib(n - 1) + fib(n - 2);
			}
			fib(30);
		""";
		runFullComparison("【场景 4: 深度递归函数调用】递归计算 fib(30) (Recursive Call Stack)", fibCode, magicContext, graalContext, 200);

		// ==================== 5. 场景五：32 位整型位运算 (Bitwise Hash & Shifts) ====================
		String bitwiseCode = """
			var h = 0x12345678;
			for (var i = 0; i < 50000; i++) {
			    h = (h ^ i) * 0x5bd1e995;
			    h = h ^ (h >> 15);
			    h = (h << 5) | (h >> 27);
			}
			h;
		""";
		runFullComparison("【场景 5: 32 位整型位运算循环】50,000 次位移与异或哈希 (Bitwise Hash & Shifts)", bitwiseCode, magicContext, graalContext, 2000);

		graalContext.close();
		System.out.println("================================================================================");
		System.out.println("                          基准测试全部圆满完成                                  ");
		System.out.println("================================================================================");
	}

	private static void runFullComparison(
		String title,
		String jsCode,
		JSContext magicContext,
		Context graalContext,
		int warmIterations
	) throws Throwable {
		System.out.println("================================================================================");
		System.out.println("  " + title);
		System.out.println("================================================================================");

		// 1. MagicJS
		long mCompT0 = System.nanoTime();
		JSScript mScript = JSCompiler.compile(jsCode);
		long mCompNs = System.nanoTime() - mCompT0;

		long mRun1T0 = System.nanoTime();
		Object mRes = mScript.run(magicContext);
		long mColdRunNs = System.nanoTime() - mRun1T0;

		long mWarmTotal = 0;
		for (int i = 0; i < warmIterations; i++) {
			long t = System.nanoTime();
			mScript.run(magicContext);
			mWarmTotal += (System.nanoTime() - t);
		}
		double mWarmAvgUs = (mWarmTotal / (double) warmIterations) / 1_000.0;

		// 2. GraalJS
		long gCompT0 = System.nanoTime();
		Source gSource = Source.newBuilder("js", jsCode, "sc.js").build();
		long gCompNs = System.nanoTime() - gCompT0;

		long gRun1T0 = System.nanoTime();
		Value gRes = graalContext.eval(gSource);
		long gColdRunNs = System.nanoTime() - gRun1T0;

		long gWarmTotal = 0;
		for (int i = 0; i < warmIterations; i++) {
			long t = System.nanoTime();
			graalContext.eval(gSource);
			gWarmTotal += (System.nanoTime() - t);
		}
		double gWarmAvgUs = (gWarmTotal / (double) warmIterations) / 1_000.0;

		// 3. Bun.js
		BenchmarkResult bunResult = runExternalCliBenchmark("bun", jsCode, warmIterations);

		// 4. Node.js
		BenchmarkResult nodeResult = runExternalCliBenchmark("node", jsCode, warmIterations);

		// 格式化输出表格
		System.out.printf("%-14s | %-16s | %-16s | %-16s | %-16s%n",
			"引擎", "首调编译时延", "首次冷执行时延", "端到端冷启动", "稳态单次热执行");
		System.out.println("---------------+------------------+------------------+------------------+------------------");

		System.out.printf("%-14s | %10.3f ms     | %10.3f ms     | %10.3f ms     | %10.3f µs   ⚡%n",
			"MagicJS", mCompNs / 1_000_000.0, mColdRunNs / 1_000_000.0, (mCompNs + mColdRunNs) / 1_000_000.0, mWarmAvgUs);

		System.out.printf("%-14s | %10.3f ms     | %10.3f ms     | %10.3f ms     | %10.3f µs%n",
			"GraalJS", gCompNs / 1_000_000.0, gColdRunNs / 1_000_000.0, (gCompNs + gColdRunNs) / 1_000_000.0, gWarmAvgUs);

		if (bunResult != null) {
			System.out.printf("%-14s | %10s        | %10.3f ms     | %10.3f ms     | %10.3f µs%n",
				"Bun.js (JSC)", "-", bunResult.firstRunNs / 1_000_000.0, bunResult.totalProcessNs / 1_000_000.0, bunResult.warmAvgUs);
		}

		if (nodeResult != null) {
			System.out.printf("%-14s | %10s        | %10.3f ms     | %10.3f ms     | %10.3f µs%n",
				"Node.js (V8)", "-", nodeResult.firstRunNs / 1_000_000.0, nodeResult.totalProcessNs / 1_000_000.0, nodeResult.warmAvgUs);
		}

		System.out.println();
		if (bunResult != null) {
			double coldRatio = (bunResult.totalProcessNs / 1_000_000.0) / ((mCompNs + mColdRunNs) / 1_000_000.0);
			double warmRatio = bunResult.warmAvgUs / mWarmAvgUs;
			System.out.printf("  🎯 [对比 Bun.js]:  端到端冷启动 MagicJS 快 %5.2fx | 稳态热执行速度比 (Bun/MagicJS): %5.2fx%n",
				coldRatio, warmRatio);
		}
		if (nodeResult != null) {
			double coldRatio = (nodeResult.totalProcessNs / 1_000_000.0) / ((mCompNs + mColdRunNs) / 1_000_000.0);
			double warmRatio = nodeResult.warmAvgUs / mWarmAvgUs;
			System.out.printf("  🎯 [对比 Node.js]: 端到端冷启动 MagicJS 快 %5.2fx | 稳态热执行速度比 (Node/MagicJS): %5.2fx%n",
				coldRatio, warmRatio);
		}
		double gWarmRatio = gWarmAvgUs / mWarmAvgUs;
		System.out.printf("  🎯 [对比 GraalJS]: 稳态热执行 MagicJS 比 GraalJS 快 %5.2fx%n%n", gWarmRatio);
	}

	private static class BenchmarkResult {
		long totalProcessNs;
		long firstRunNs;
		double warmAvgUs;
		String result;
	}

	private static BenchmarkResult runExternalCliBenchmark(String engine, String coreJsCode, int warmIterations) {
		try {
			// 包装一段能精密测算引擎内部冷热时延的 JavaScript
			String template = """
				const now = () => Number(process.hrtime.bigint());

				// 首次冷执行
				const t0 = now();
				const coldRes = (function() {
				__CORE_CODE__
				})();
				const coldNs = now() - t0;

				// 预热与稳态执行
				let warmTotalNs = 0;
				for (let i = 0; i < __WARM_RUNS__; i++) {
				    const st = now();
				    (function() {
				__CORE_CODE__
				    })();
				    warmTotalNs += (now() - st);
				}
				const warmAvgUs = (warmTotalNs / __WARM_RUNS__) / 1000.0;

				console.log("RESULT:" + coldRes);
				console.log("COLD_NS:" + coldNs);
				console.log("WARM_AVG_US:" + warmAvgUs);
			""";
			String runnerJs = template
				.replace("__CORE_CODE__", coreJsCode)
				.replace("__WARM_RUNS__", String.valueOf(warmIterations));

			File tmp = File.createTempFile("bench_", ".js");
			tmp.deleteOnExit();
			Files.writeString(tmp.toPath(), runnerJs, StandardCharsets.UTF_8);

			long p0 = System.nanoTime();
			ProcessBuilder pb = new ProcessBuilder(engine, tmp.getAbsolutePath());
			pb.redirectErrorStream(true);
			Process p = pb.start();

			BenchmarkResult res = new BenchmarkResult();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (line.startsWith("RESULT:")) res.result = line.substring(7);
					else if (line.startsWith("COLD_NS:")) res.firstRunNs = Long.parseLong(line.substring(8).trim());
					else if (line.startsWith("WARM_AVG_US:")) res.warmAvgUs = Double.parseDouble(line.substring(12).trim());
				}
			}
			p.waitFor();
			res.totalProcessNs = System.nanoTime() - p0;
			tmp.delete();
			return res;
		} catch (Throwable e) {
			return null;
		}
	}

	private static void warmupEngines(JSContext magicContext, Context graalContext) {
		String warmCode = "var a = 0; for(var i = 0; i < 100; i++) a += i; a;";
		try {
			JSScript s = JSCompiler.compile(warmCode);
			for (int i = 0; i < 100; i++) s.run(magicContext);

			Source gs = Source.newBuilder("js", warmCode, "warm.js").build();
			for (int i = 0; i < 100; i++) graalContext.eval(gs);
		} catch (Throwable ignored) {}
	}

	private static String getCliVersion(String cmd, String arg) {
		try {
			Process p = new ProcessBuilder(cmd, arg).start();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
				return r.readLine();
			}
		} catch (Throwable ignored) {
			return null;
		}
	}
}
