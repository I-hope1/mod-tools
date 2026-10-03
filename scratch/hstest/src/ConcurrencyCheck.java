import nipx.LambdaAligner;

import java.util.*;
import java.util.concurrent.*;

/**
 * 并发正确性验证：`onOrphanInvoked` 会被**任意业务线程**并发调用。
 *
 * 要验证的两点：
 *   1. `LOCATION_KEY` 用 ThreadLocal 后，各线程的 location 不会互相污染
 *      —— 每个线程用**自己唯一的** name，最后统计"是否每个 name 都被记录过"；
 *   2. 两组集合的 check-then-act 已原子化 —— 并发下同一个 location 只应产生一条日志。
 *
 * 判定方式：把 stderr 重定向到文件后，统计每个 location 出现的次数应恰好为 1。
 * 本入口只做调用，由外部脚本核对输出。
 */
public class ConcurrencyCheck {

	static final int THREADS = 16;
	static final int PER_THREAD = 200;

	public static void main(String[] args) throws Exception {
		// 用 LOG_AND_RETURN_DEFAULT：它必然走日志路径（SMART_ADAPTIVE 会先做栈探测，慢且无关）
		LambdaAligner.setOrphanPolicy(LambdaAligner.OrphanPolicy.LOG_AND_RETURN_DEFAULT);

		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> fs = new ArrayList<>();
		for (int t = 0; t < THREADS; t++) {
			final int tid = t;
			fs.add(pool.submit(() -> {
				try { start.await(); } catch (InterruptedException ignored) { }
				for (int i = 0; i < PER_THREAD; i++) {
					// 每个线程有自己唯一的 name；同一 name 重复调用多次
					LambdaAligner.onOrphanInvoked("t.Conc#lambda$t" + tid + "$0()V");
				}
			}));
		}
		start.countDown();                       // 尽量同时起跑，放大竞态
		for (Future<?> f : fs) f.get();
		pool.shutdown();

		System.err.println("CONCURRENCY-DONE threads=" + THREADS
			+ " calls=" + (THREADS * PER_THREAD) + " distinctNames=" + THREADS);
	}
}
