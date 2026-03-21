package nipx.profiler;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

import static nipx.HotSwapAgent.info;

public class ProfilerData {
	// 记录方法名 -> 总耗时 (纳秒)
	// 记录方法名 -> 调用次数
	@SuppressWarnings("ClassCanBeRecord")
	public static final class MethodStats {
		private final LongAdder time;
		private final LongAdder count;
		public MethodStats(LongAdder time, LongAdder count) {
			if (time == null || count == null) throw new NullPointerException("null argument");
			this.time = time;
			this.count = count;
		}
		public LongAdder time() { return time; }
		public LongAdder count() { return count; }
		@Override
		public boolean equals(Object obj) {
			if (obj == this) return true;
			if (obj == null || obj.getClass() != this.getClass()) return false;
			var that = (MethodStats) obj;
			return Objects.equals(this.time, that.time) &&
			       Objects.equals(this.count, that.count);
		}
		@Override
		public int hashCode() {
			return Objects.hash(time, count);
		}
		@Override
		public String toString() {
			return "MethodStats[" +
			       "time=" + time + ", " +
			       "count=" + count + ']';
		}
	}
	public static final Map<String, MethodStats> stats = new ConcurrentHashMap<>();

	// ASM 注入的代码会调用这个
	public static void record(String method, long nanos) {
		var s = stats.computeIfAbsent(method, k -> new MethodStats(new LongAdder(), new LongAdder()));
		s.time().add(nanos);
		s.count().increment();
	}

	public static void clear() {
		info("Clearing profiler data...");
		stats.clear();
	}

	// 打印报告
	public static void printReport() {
		info("=== Profiler Report ===");
		stats.forEach((method, s) -> {
			long nanos = s.time().sum();
			long count = s.count().sum();
			if (count == 0) return; // 防御
			double avgMs = (nanos / 1_000_000.0) / count;
			info(String.format("[%s] Total: %d ms, Calls: %d, Avg: %.4f ms%n",
			 method, nanos / 1_000_000, count, avgMs));
		});
	}

}
