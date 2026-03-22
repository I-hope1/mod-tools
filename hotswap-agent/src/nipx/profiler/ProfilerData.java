package nipx.profiler;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

import static nipx.HotSwapAgent.info;

public class ProfilerData {

	// ── Flat stats (ProfilerWindow 用，保持不变) ───────────────────────────────
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
	/**
	 * 火焰图节点。
	 * <p>children 用 ConcurrentHashMap 保证多线程并发插入安全；
	 * totalNanos 用 LongAdder 保证原子累加无锁竞争。
	 */
	public static final class FlameNode {
		public final String                               name;
		public final LongAdder                            totalNanos = new LongAdder();
		public final ConcurrentHashMap<String, FlameNode> children   = new ConcurrentHashMap<>();

		public FlameNode(String name) { this.name = name; }

		/** 以本节点为根时树的最大深度（含自身，从 0 开始）。仅渲染时调用，不在热路径。 */
		public int maxDepth(int cur) {
			int max = cur;
			for (FlameNode c : children.values()) max = Math.max(max, c.maxDepth(cur + 1));
			return max;
		}
	}

	/**
	 * 全局火焰图树根节点（volatile，clear 时原子替换）。
	 */
	public static volatile FlameNode flameRoot = new FlameNode("(all)");

	/**
	 * 每线程独立的"当前节点"栈。
	 * ArrayDeque 初次 resize 后大小稳定，后续 push/pop 均零 GC。
	 */
	private static final ThreadLocal<ArrayDeque<FlameNode>> nodeStack =
		ThreadLocal.withInitial(ArrayDeque::new);

	// ── 热路径三件套（ASM 注入调用） ──────────────────────────────────────────

	/**
	 * 方法进入时调用——压节点入栈。
	 * <p><b>GC 分析</b>：key 已存在时，computeIfAbsent 仅做 hash 查找，
	 * 不创建任何对象。仅在首次遇到新调用路径时 new FlameNode；
	 * 稳定运行后完全零 GC。
	 */
	public static void recordEntry(String method) {
		ArrayDeque<FlameNode> stack  = nodeStack.get();
		FlameNode             parent = stack.isEmpty() ? flameRoot : stack.peek();
		FlameNode             node   = parent.children.computeIfAbsent(method, FlameNode::new);
		stack.push(node);
	}

	/**
	 * 方法正常退出时调用——弹栈并累加耗时。
	 * <p><b>GC 分析</b>：ArrayDeque.pop 无分配；LongAdder.add 无分配。
	 * 稳定运行后完全零 GC。
	 */
	public static void recordExit(String method, long nanos) {
		ArrayDeque<FlameNode> stack = nodeStack.get();
		FlameNode             node  = stack.isEmpty() ? null : stack.pop();
		if (node != null) node.totalNanos.add(nanos);
		record(method, nanos);
	}

	/**
	 * 方法因异常退出时调用——仅弹栈保持平衡，不记录耗时。
	 */
	public static void recordCancel(String method) {
		ArrayDeque<FlameNode> stack = nodeStack.get();
		if (!stack.isEmpty() && method.equals(stack.peek().name)) stack.pop();
	}

	// ── 清理 ──────────────────────────────────────────────────────────────────

	public static void clear() {
		info("Clearing profiler data...");
		stats.clear();
		// 原子替换根节点；旧树随旧引用 GC，不影响正在执行的线程
		// （线程本地栈中残留的旧节点会把数据写入旧树，新树始终干净）
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