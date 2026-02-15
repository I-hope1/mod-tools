package nipx.profiler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import static nipx.HotSwapAgent.info;

public class ProfilerData {
	// 记录方法名 -> 总耗时 (纳秒)
	public static final Map<String, LongAdder> totalTime = new ConcurrentHashMap<>();
	// 记录方法名 -> 调用次数
	public static final Map<String, LongAdder> callCount = new ConcurrentHashMap<>();

	public static void clear() {
		info("Clearing profiler data...");
		totalTime.clear();
		callCount.clear();
	}

	// 打印报告
	public static void printReport() {
		info("=== Profiler Report ===");
		totalTime.forEach((method, nanosAdder) -> {
			long   nanos = nanosAdder.sum();
			long   count = callCount.computeIfAbsent(method, k -> new LongAdder()).sum();
			double avgMs = (nanos / 1_000_000.0) / count;
			info(String.format("[%s] Total: %d ms, Calls: %d, Avg: %.4f ms%n",
			 method, nanos / 1_000_000, count, avgMs));
		});
	}

	// ASM 注入的代码会调用这个
	public static void record(String method, long nanos) {
		totalTime.computeIfAbsent(method, k -> new LongAdder()).add(nanos);
		callCount.computeIfAbsent(method, k -> new LongAdder()).increment();
	}

	public static void recordBuilding(Object obj, long nanos) {
		String label = obj.getClass().getSimpleName();
		record(label, nanos);
	}
}
