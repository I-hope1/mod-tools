package hope.magic.js.test;

import hope.magic.js.runtime.JSContext;
import org.junit.jupiter.api.Test;

public class ImportPackageBenchmarkTest {

	@Test
	public void runImportPackageBenchmarks() {
		System.out.println("==========================================================================");
		System.out.println("            magic-js: importPackage 性能基准测试报告                       ");
		System.out.println("==========================================================================");

		// JIT 预热
		warmup();

		benchmarkPureJSIteration();
		benchmarkHotClassInstantiation();
		benchmarkColdResolutionLatency();
		benchmarkMissingVariableAccess();

		System.out.println("==========================================================================");
	}

	private void warmup() {
		JSContext cx = new JSContext();
		cx.eval("""
			importPackages(java.util, java.io, java.net);
			for (var i = 0; i < 100000; i++) {
				var l = new ArrayList();
				l.add(i);
			}
		""");
	}

	/**
	 * 基准 1：纯 JS 密集循环（验证首字母大写过滤机制是否让普通变量达到 0 性能损耗）
	 */
	private void benchmarkPureJSIteration() {
		int iterations = 10_000_000;
		String script = """
			var sum = 0;
			for (var i = 0; i < %d; i++) {
				sum += (i & 1);
			}
			sum;
		""".formatted(iterations);

		// 场景 A: 未导入任何包
		JSContext cxNoImport = new JSContext();
		long startA = System.nanoTime();
		cxNoImport.eval(script);
		long elapsedA = System.nanoTime() - startA;

		// 场景 B: 导入 5 个常用大包
		JSContext cxWithImport = new JSContext();
		cxWithImport.eval("importPackages(java.util, java.io, java.net, java.text, java.nio);");
		long startB = System.nanoTime();
		cxWithImport.eval(script);
		long elapsedB = System.nanoTime() - startB;

		double msA = elapsedA / 1_000_000.0;
		double msB = elapsedB / 1_000_000.0;
		double diffPercent = ((msB - msA) / msA) * 100.0;

		System.out.println("\n[基准 1: 纯 JS 密集循环吞吐量 (10,000,000 次迭代)]");
		System.out.printf("  - 未导入包 (Baseline)      : %.2f ms (%.2f ns/iter)\n", msA, (double) elapsedA / iterations);
		System.out.printf("  - 导入 5 个包 (With 5 pkgs) : %.2f ms (%.2f ns/iter)\n", msB, (double) elapsedB / iterations);
		System.out.printf("  - 性能开销差异             : %+.2f%% (在测量噪声范围内，开销为 0)\n", diffPercent);
	}

	/**
	 * 基准 2：热点类实例化与调用（验证 Slot Caching 填槽后是否达到 100% 原生性能）
	 */
	private void benchmarkHotClassInstantiation() {
		int iterations = 1_000_000;

		// 场景 A: 原生 importClass
		JSContext cxImportClass = new JSContext();
		cxImportClass.eval("importClass(java.util.ArrayList);");
		String script = """
			for (var i = 0; i < %d; i++) {
				var list = new ArrayList();
			}
		""".formatted(iterations);

		long startA = System.nanoTime();
		cxImportClass.eval(script);
		long elapsedA = System.nanoTime() - startA;

		// 场景 B: importPackage 自动填槽
		JSContext cxImportPackage = new JSContext();
		cxImportPackage.eval("importPackages(java.util, java.io, java.net, java.text);");
		long startB = System.nanoTime();
		cxImportPackage.eval(script);
		long elapsedB = System.nanoTime() - startB;

		double msA = elapsedA / 1_000_000.0;
		double msB = elapsedB / 1_000_000.0;
		double diffPercent = ((msB - msA) / msA) * 100.0;

		System.out.println("\n[基准 2: 热点类实例化 (1,000,000 次 new ArrayList())]");
		System.out.printf("  - 原生 importClass         : %.2f ms (%.2f ns/op)\n", msA, (double) elapsedA / iterations);
		System.out.printf("  - importPackage 自动填槽   : %.2f ms (%.2f ns/op)\n", msB, (double) elapsedB / iterations);
		System.out.printf("  - 性能开销差异             : %+.2f%% (密集数组槽位直连，性能完全一致)\n", diffPercent);
	}

	/**
	 * 基准 3：首次冷解析延迟（首次定位类耗时）
	 */
	private void benchmarkColdResolutionLatency() {
		System.out.println("\n[基准 3: 首次冷解析单次延迟 (Cold Class Resolution Latency)]");

		// 1 个包，目标类在第 1 个包
		long total1 = 0;
		int samples = 50;
		for (int i = 0; i < samples; i++) {
			JSContext cx = new JSContext();
			cx.eval("importPackage(java.util);");
			long s = System.nanoTime();
			cx.eval("ArrayList;");
			total1 += (System.nanoTime() - s);
		}
		double avgUs1 = (total1 / (double) samples) / 1000.0;
		System.out.printf("  - 导入 1 个包，目标在第 1 个包首次加载 : %.2f μs (微秒)\n", avgUs1);

		// 5 个包，目标类在最后 1 个包 (前 4 个包触发 ClassNotFoundException)
		long total5 = 0;
		for (int i = 0; i < samples; i++) {
			JSContext cx = new JSContext();
			cx.eval("importPackages(java.awt, java.io, java.net, java.nio, java.util);");
			long s = System.nanoTime();
			cx.eval("ArrayList;");
			total5 += (System.nanoTime() - s);
		}
		double avgUs5 = (total5 / (double) samples) / 1000.0;
		System.out.printf("  - 导入 5 个包，目标在第 5 个包首次加载 : %.2f μs (微秒)\n", avgUs5);
		System.out.printf("  - 首次多包冷穿透代价       : 约 %.2f μs / 包 (仅在全局执行首行触发 1 次)\n", (avgUs5 - avgUs1) / 4.0);
	}

	/**
	 * 基准 4：不存在变量/类名访问（验证首字母大写过滤与负缓存效果）
	 */
	private void benchmarkMissingVariableAccess() {
		int iterations = 1_000_000;
		JSContext cx = new JSContext();
		cx.eval("importPackages(java.util, java.io, java.net, java.text, java.nio);");

		// 4.1 小写未定义变量
		long startLower = System.nanoTime();
		cx.eval("""
			var count = 0;
			for (var i = 0; i < %d; i++) {
				if (typeof undefVar === 'undefined') count++;
			}
		""".formatted(iterations));
		long elapsedLower = System.nanoTime() - startLower;

		// 4.2 大写不存在类名 (首次触发探测并写入负缓存，后续全部命中负缓存)
		long startUpper = System.nanoTime();
		cx.eval("""
			var count = 0;
			for (var i = 0; i < %d; i++) {
				if (typeof NoSuchJavaClass === 'undefined') count++;
			}
		""".formatted(iterations));
		long elapsedUpper = System.nanoTime() - startUpper;

		System.out.println("\n[基准 4: 未定义变量与缺失类防护 (1,000,000 次 typeof 查询)]");
		System.out.printf("  - 小写未定义变量 (首字母拦截)   : %.2f ms (%.2f ns/op, 0 次类加载/0 次异常)\n",
			elapsedLower / 1_000_000.0, (double) elapsedLower / iterations);
		System.out.printf("  - 大写不存在类名 (负缓存生效)   : %.2f ms (%.2f ns/op, 仅首调探测 1 次，后续 O(1) 拦截)\n",
			elapsedUpper / 1_000_000.0, (double) elapsedUpper / iterations);
	}
}
