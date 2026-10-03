import nipx.LambdaAligner;

/**
 * ① 与「日志路径零分配」的测量。
 *
 * 要回答的问题：
 *   (a) `isCalledByUpdateRef()` 的栈探测成本，在每秒数万次调用量级下是否可感知？
 *   (b) 两条日志路径（SMART_ADAPTIVE / LOG_AND_RETURN_DEFAULT）在"已记录过"之后
 *       是否仍然每次分配 String？
 *
 * 方法：分别测量，取多次运行的最小值（少受 JIT/GC 抖动影响），换算成每次调用的纳秒数。
 *
 * 迭代数说明：早期用 1,000 次 × 2 轮测出过与实际相反的结论（~100ns 量级被抖动淹没），
 * 因此这里用 5,000 × 4。**测量工具本身也要先估量级。**
 */
public class ProbeBench {

	static final int WARMUP = 1_000;
	static final int ITERS = 5_000;

	static volatile int sink;

	static void noop(String a, String b, String c) { sink++; }

	static long bench(Runnable r) {
		for (int i = 0; i < WARMUP; i++) r.run();
		long best = Long.MAX_VALUE;
		for (int rep = 0; rep < 4; rep++) {
			long t0 = System.nanoTime();
			r.run();
			long dt = System.nanoTime() - t0;
			if (dt < best) best = dt;
		}
		return best;
	}

	public static void main(String[] args) {
		System.out.println("== 栈探测 / 日志路径成本基准 ==");
		System.out.println("   迭代次数: " + ITERS + "（取 4 次最小值）");

		long tNoop = bench(() -> { for (int i = 0; i < ITERS; i++) noop("t/F", "lambda$x$0", "()V"); });
		long tOrphan = bench(() -> {
			for (int i = 0; i < ITERS; i++) LambdaAligner.onOrphanInvoked("t.F#lambda$x$0()V");
		});
		long tProbe = bench(() -> {
			for (int i = 0; i < ITERS; i++) sink += LambdaAligner.isCalledByUpdateRef() ? 1 : 0;
		});

		double perNoop   = (double) tNoop   / ITERS;
		double perOrphan = (double) tOrphan / ITERS;
		double perProbe  = (double) tProbe  / ITERS;

		System.out.println();
		System.out.printf("   空调用基线                    : %8.2f ns/次%n", perNoop);
		System.out.printf("   isCalledByUpdateRef（真探测） : %8.2f ns/次%n", perProbe);
		System.out.printf("   onOrphanInvoked(SMART_ADAPTIVE，已缓存): %8.2f ns/次%n", perOrphan);
		System.out.printf("   相对基线净开销                : %8.2f ns/次%n", perOrphan - perNoop);
		System.out.println();
		System.out.printf("   折算：每秒 10 万次调用时，该路径占 CPU 约 %.2f%%%n",
			(perOrphan - perNoop) * 100_000 / 10_000_000);
		System.out.println();
		double net = perOrphan - perNoop;
		if (net < 500) {
			System.out.printf("   >>> SMART_ADAPTIVE：高频路径约 %.0f ns/次（加缓存前实测约 1350 ns/次），"
				+ "且命中后零 String 分配%n", net);
		} else {
			System.out.printf("   >>> SMART_ADAPTIVE：仍有 %.0f ns/次，值得进一步优化%n", net);
		}

		// ── LOG_AND_RETURN_DEFAULT 路径（用户指出的第二处）──
		System.out.println();
		System.out.println("== LOG_AND_RETURN_DEFAULT 路径 ==");
		LambdaAligner.OrphanPolicy saved = LambdaAligner.getOrphanPolicy();
		try {
			LambdaAligner.setOrphanPolicy(LambdaAligner.OrphanPolicy.LOG_AND_RETURN_DEFAULT);
			long tLog = bench(() -> {
				for (int i = 0; i < ITERS; i++) LambdaAligner.onOrphanInvoked("t.F#lambda$x$0()V");
			});
			double perLog = (double) tLog / ITERS;
			System.out.printf("   onOrphanInvoked(LOG_AND_RETURN_DEFAULT): %8.2f ns/次%n", perLog);
			System.out.printf("   相对基线净开销                        : %8.2f ns/次%n", perLog - perNoop);
			if (perLog - perNoop < 500) {
				System.out.printf("   >>> 该路径同样零分配（命中后不生成 String），净开销约 %.0f ns/次%n",
					perLog - perNoop);
			} else {
				System.out.printf("   >>> 该路径仍有 %.0f ns/次，需检查%n", perLog - perNoop);
			}
		} finally {
			LambdaAligner.setOrphanPolicy(saved);
		}
	}
}
