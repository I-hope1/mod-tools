package nipx.profiler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static nipx.HotSwapAgent.info;

public class ProfilerData {
	// 记录方法名 -> 总耗时 (纳秒)
	public static final Map<String, Long> totalTime = new ConcurrentHashMap<>();
	// 记录方法名 -> 调用次数
	public static final Map<String, Long> callCount = new ConcurrentHashMap<>();

	// 打印报告
	public static void printReport() {
		info("=== Profiler Report ===");
		totalTime.forEach((method, nanos) -> {
			long   count = callCount.getOrDefault(method, 0L);
			double avgMs = (nanos / 1_000_000.0) / count;
			info(String.format("[%s] Total: %d ms, Calls: %d, Avg: %.4f ms%n",
			 method, nanos / 1_000_000, count, avgMs));
		});
	}

	// ASM 注入的代码会调用这个
	public static void record(String method, long nanos) {
		totalTime.merge(method, nanos, Long::sum);
		callCount.merge(method, 1L, Long::sum);
	}
}
