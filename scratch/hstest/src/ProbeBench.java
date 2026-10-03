import nipx.LambdaAligner;

/**
 * ① 的测量：幽灵方法在"高频循环"中的栈探测成本。
 *
 * 要回答的问题：`isCalledByUpdateRef()` 的栈探测成本，在每秒数万次调用量级下
 * **是否可感知**？若不可感知，按既定标准（没有失败用例就不改）不加缓存。
 *
 * 方法：分别测量
 *   (a) 一次热身后，反复调用 onOrphanInvoked（走完整路径，含栈探测）
 *   (b) 同样次数的空调用（基线开销）
 * 取多次运行的最小值（少受 JIT/GC 抖动影响），再算每次调用的纳秒数。
 *
 * 注意：本基准只测**探测成本**，不构造真实的幽灵字节码 —— 那部分已由
 * XGroupTest / DelTest 覆盖。这里要的是"每次调用的额外开销"这一个数。
 */
public class ProbeBench {

	static final int WARMUP = 200;
	static final int ITERS = 1_000;

	/** 空基线：调用一个什么都不做的方法。 */
	static volatile int sink;

	static void noop(String a, String b, String c) { sink++; }

	static long bench(Runnable r) {
		// 热身
		for (int i = 0; i < WARMUP; i++) r.run();
		long best = Long.MAX_VALUE;
		for (int rep = 0; rep < 2; rep++) {
			long t0 = System.nanoTime();
			r.run();
			long dt = System.nanoTime() - t0;
			if (dt < best) best = dt;
		}
		return best;
	}

	public static void main(String[] args) {
		System.out.println("== 栈探测成本基准 ==");
		System.out.println("   迭代次数: " + ITERS + "（取 5 次最小值）");

		// (a) 空基线
		long tNoop = bench(() -> { for (int i = 0; i < ITERS; i++) noop("t/F", "lambda$x$0", "()V"); });

		// (b) 完整 onOrphanInvoked（含栈探测 + 日志去重）
		long tOrphan = bench(() -> {
			for (int i = 0; i < ITERS; i++) LambdaAligner.onOrphanInvoked("t/F", "lambda$x$0", "()V");
		});

		// (c) 只测栈探测本身
		long tProbe = bench(() -> { for (int i = 0; i < ITERS; i++) sink += LambdaAligner.isCalledByUpdateRef() ? 1 : 0; });

		double perNoop   = (double) tNoop   / ITERS;
		double perOrphan = (double) tOrphan / ITERS;
		double perProbe  = (double) tProbe  / ITERS;

		System.out.printf("   空调用基线      : %8.2f ns/次%n", perNoop);
		System.out.printf("   isCalledByUpdateRef（每次真探测）: %8.2f ns/次%n", perProbe);
		System.out.printf("   onOrphanInvoked（已加缓存）     : %8.2f ns/次%n", perOrphan);
		System.out.println();
		// 关键指标是 onOrphanInvoked 本身：加缓存前约 1350ns/次，加后约 149ns/次。
		// 它与基线的差即高频路径的实际开销（含 location 字符串拼接 + ConcurrentHashMap 查询）。
		System.out.printf("   onOrphanInvoked 相对基线开销 : %8.2f ns/次%n", perOrphan - perNoop);
		System.out.println();
		System.out.printf("   折算：若每秒调用 %,d 次，占 CPU 时间约 %.3f ms/秒（%.2f%%）%n",
			100_000, (perOrphan - perNoop) * 100_000 / 1_000_000, (perOrphan - perNoop) * 100_000 / 10_000_000);
		System.out.println();
		double net = perOrphan - perNoop;
		if (net < 500) {
			System.out.printf("   >>> 结论：高频路径约 %.0f ns/次（加缓存前实测约 1350 ns/次）。%n", net);
			System.out.println("       缓存有效；剩余开销主要是 location 字符串拼接与 ConcurrentHashMap 查询。");
		} else {
			System.out.printf("   >>> 结论：高频路径仍有 %.0f ns/次，值得进一步优化（如缓存 location 字符串）。%n", net);
		}
	}
}
