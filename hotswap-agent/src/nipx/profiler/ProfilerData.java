package nipx.profiler;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

import static nipx.HotSwapAgent.info;

public class ProfilerData {

	// ── Flat stats (ProfilerWindow 用) ────────────────────────────────────────
	@SuppressWarnings("ClassCanBeRecord")
	public static final class MethodStats {
		private final LongAdder time;
		private final LongAdder count;
		public MethodStats(LongAdder time, LongAdder count) {
			if (time == null || count == null) throw new NullPointerException("null argument");
			this.time = time; this.count = count;
		}
		public LongAdder time()  { return time; }
		public LongAdder count() { return count; }
		@Override public String toString() {
			return "MethodStats[time=" + time + ", count=" + count + ']';
		}
	}

	public static final Map<String, MethodStats> stats = new ConcurrentHashMap<>();

	public static void record(String method, long nanos) {
		var s = stats.computeIfAbsent(method, k -> new MethodStats(new LongAdder(), new LongAdder()));
		s.time().add(nanos);
		s.count().increment();
	}

	// ── Flame Graph 活树 ──────────────────────────────────────────────────────
	public static final class FlameNode {
		public final String                               name;
		public final LongAdder                            totalNanos = new LongAdder();
		public final ConcurrentHashMap<String, FlameNode> children   = new ConcurrentHashMap<>();

		public FlameNode(String name) { this.name = name; }

		public int maxDepth(int cur) {
			int max = cur;
			for (FlameNode c : children.values()) max = Math.max(max, c.maxDepth(cur + 1));
			return max;
		}
	}

	public static volatile FlameNode flameRoot = new FlameNode("(all)");

	private static final ThreadLocal<ArrayDeque<FlameNode>> nodeStack =
		ThreadLocal.withInitial(ArrayDeque::new);

	// ── 热路径三件套（ASM 注入调用） ──────────────────────────────────────────

	public static void recordEntry(String method) {
		ArrayDeque<FlameNode> stack  = nodeStack.get();
		FlameNode             parent = stack.isEmpty() ? flameRoot : stack.peek();
		FlameNode             node   = parent.children.computeIfAbsent(method, FlameNode::new);
		stack.push(node);
	}

	public static void recordExit(String method, long nanos) {
		ArrayDeque<FlameNode> stack = nodeStack.get();
		FlameNode             node  = stack.isEmpty() ? null : stack.pop();
		if (node != null) node.totalNanos.add(nanos);
		record(method, nanos);
	}


	/**
	 * 返回当前线程插桩栈顶的方法 key，供 {@link GlTimerProfiler} 标记 flush 归属。
	 * 栈为空时返回空串（flush 发生在所有被监控方法之外）。
	 */
	public static String currentFlushKey() {
		ArrayDeque<FlameNode> stack = nodeStack.get();
		return stack.isEmpty() ? "" : stack.peek().name;
	}

	public static void recordCancel(String method) {
		ArrayDeque<FlameNode> stack = nodeStack.get();
		if (!stack.isEmpty() && method.equals(stack.peek().name)) stack.pop();
	}

	// ── 清理 ──────────────────────────────────────────────────────────────────

	public static void clear() {
		info("Clearing profiler data...");
		stats.clear();
		flameRoot = new FlameNode("(all)");
	}

	public static void printReport() {
		info("=== Profiler Report ===");
		stats.forEach((method, s) -> {
			long   nanos = s.time().sum();
			long   count = s.count().sum();
			if (count == 0) return;
			double avgMs = (nanos / 1_000_000.0) / count;
			info(String.format("[%s] Total: %d ms, Calls: %d, Avg: %.4f ms",
				method, nanos / 1_000_000, count, avgMs));
		});
	}
}