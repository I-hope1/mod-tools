package modtools.utils.profiler;

import arc.Core;
import arc.files.Fi;
import arc.struct.LongMap;
import arc.util.Log;
import arc.util.serialization.Jval;
import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.HotSpotDiagnosticMXBean.ThreadDumpFormat;
import modtools.IntVars;
import modtools.ui.IntUI;

import java.lang.management.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * JVM 死锁检测看门狗与排查工具。
 * <p>独立于火焰图采样分析器运行，避免对 5ms 高频 CPU 采样造成时延抖动。
 *
 * <p><b>已知限制（Known Limitations）：</b>
 * <ul>
 *   <li><b>隐形死锁检测（分歧判据）覆盖范围与局限：</b>
 *     <ul>
 *       <li><b>判据特征：</b>Java 层 {@link Thread#getState()} 显示为 {@link Thread.State#BLOCKED}，
 *           但 {@link ThreadMXBean#getThreadInfo(long)} 报告的状态非 BLOCKED（如 RUNNABLE 且无锁信息）。
 *           这是 Java 24+ (JEP 491) 下监视器由虚拟线程持有且平台线程争用时的典型特征。
 *           同时自动过滤普通平台线程争用（两边一致为 BLOCKED 且带锁信息），交由标准死锁检测处理。</li>
 *       <li><b>自动转储与死锁环解析：</b>报警时自动调用 {@code HotSpotDiagnosticMXBean.dumpThreads(..., JSON)}
 *           导出包含虚拟线程在内的完整线程转储，并自动解析其中的 {@code blockedOn} 与 {@code monitorsOwned}，
 *           在报告中重构闭环等待图（标明各线程名、平台/虚拟类型、持有锁与等待锁）。</li>
 *       <li><b>纯虚拟线程死锁支持：</b>纯虚拟线程间的循环互锁因平台线程未受阻塞，无法触发 3 秒周期的后台巡检；
 *           但可通过手动调用 {@link #dumpAndAnalyzeDeadlocks()} 导出全量转储并自动分析死锁环
 *           （在 Java 24/25 JEP 491 环境下已实测验证转储 JSON 完整包含虚拟线程的 {@code blockedOn} 与 {@code monitorsOwned}）。</li>
 *       <li><b>无法检测：</b>虚拟线程持有 {@code ReentrantLock} 导致等待线程处于 {@link Thread.State#WAITING}（而非 BLOCKED）；
 *           以及调用 {@code wait()}、{@code park()}、{@code join()} 的协作式等待。</li>
 *     </ul>
 *   </li>
 *   <li><b>主线程死锁时 UI 无响应：</b>若死锁涉及游戏主线程（渲染/事件分发线程），整个 UI 将冻结，
 *       界面按钮无法点击，{@link Core#app} post 投递的桌面通知也将无法被消费。
 *       此时主要依赖后台守护线程向控制台输出，以及写入磁盘 {@code deadlock.log} 文件。</li>
 *   <li><b>仅支持 Java 层互斥锁：</b>基于 {@link ThreadMXBean#findDeadlockedThreads()} 实现，
 *       仅能检测 Java 内置对象监视器（{@code synchronized}）及 JUC 独占同步器（如 {@code ReentrantLock}）
 *       构成的循环等待死锁。</li>
 *   <li><b>不支持 Native 死锁检测：</b>无法探测 JNI/C++ 层的原生锁死锁（如 {@code std::mutex}、
 *       POSIX 互斥锁、Windows CriticalSection 或处于 native 状态的死锁）。</li>
 *   <li><b>不支持逻辑死锁与假死：</b>因条件变量未触发（如 {@code Object.wait()}、{@code CountDownLatch.await()}、
 *       {@code CompletableFuture.join()}）导致的永久挂起或阻塞 IO，不属于资源循环互锁，无法被探测。</li>
 *   <li><b>读写锁共享模式限制：</b>部分 JVM 实现下，{@link java.util.concurrent.locks.ReentrantReadWriteLock}
 *       在共享读锁等待引起的死锁可能不会被报告。</li>
 *   <li><b>JVM 全局排查开销：</b>构建死锁环与抓取全量栈需要遍历 JVM 内部线程栈，因此检测间隔应保持在秒级
 *       （默认 3000ms，代码强制最低 500ms），切勿调为毫秒级以免引起周期性停顿。</li>
 * </ul>
 */
public class DeadlockDetector {
	public static volatile int checkIntervalMs = 3000;
	/** 判定隐形死锁/异常挂起的持续时间阈值（毫秒），默认 9000ms */
	public static volatile long suspiciousBlockThresholdMs = 9000;

	private static volatile Thread watchdogThread = null;
	private static volatile boolean running = false;
	private static volatile Set<Long> lastDeadlockedThreadIds = Collections.emptySet();

	public static boolean isEnabled() {
		return running;
	}

	public static synchronized void setEnabled(boolean enable) {
		if (enable) {
			startWatchdog();
		} else {
			stopWatchdog();
		}
	}

	public static synchronized void startWatchdog() {
		if (running) return;
		running = true;
		watchdogThread = new Thread(DeadlockDetector::loop, "deadlock-detector");
		watchdogThread.setDaemon(true);
		watchdogThread.setPriority(Thread.MIN_PRIORITY);
		watchdogThread.start();
	}

	// 记录连续处于异常 BLOCKED 的平台线程: threadId -> SuspiciousBlock
	private static final LongMap<SuspiciousBlock> suspiciousMap = new LongMap<>();

	private static class SuspiciousBlock {
		final StackTraceElement[] stack;
		final long firstSeenNanos;
		boolean reported;

		SuspiciousBlock(StackTraceElement[] stack, long now) {
			this.stack = stack;
			this.firstSeenNanos = now;
			this.reported = false;
		}
	}

	public static synchronized void stopWatchdog() {
		running = false;
		if (watchdogThread != null) {
			watchdogThread.interrupt();
			watchdogThread = null;
		}
		lastDeadlockedThreadIds = Collections.emptySet();
		synchronized (suspiciousMap) {
			suspiciousMap.clear();
		}
	}

	public static boolean isRunning() {
		return running;
	}

	private static void loop() {
		Thread self = Thread.currentThread();
		while (running && watchdogThread == self) {
			try {
				Thread.sleep(Math.max(500, checkIntervalMs));
			} catch (InterruptedException e) {
				break;
			}
			if (!running || watchdogThread != self) break;

			try {
				checkAndLog();
			} catch (Throwable t) {
				Log.err("[DeadlockDetector] Check failed", t);
			}
		}
	}

	public static void checkAndLog() {
		checkStandardDeadlocks();
		checkHiddenDeadlocks();
	}

	/**
	 * 统一记录死锁日志：安全控制台格式化输出、磁盘追加写入（带时间戳）、主线程 UI 提示。
	 */
	private static void writeLog(String report, String uiNotice) {
		// 1. 控制台输出
		Log.err("[DeadlockDetector] @", report);

		// 2. 磁盘文件追加写入，带时间戳分隔
		try {
			if (IntVars.dataDirectory != null) {
				String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
				IntVars.dataDirectory.child("deadlock.log").writeString(
					"\n\n==================== [" + timestamp + "] ====================\n" + report, true
				);
			}
		} catch (Throwable t) {
			Log.err("[DeadlockDetector] Failed to write deadlock.log", t);
		}

		// 3. 若主线程仍然存活，向顶层弹出浮动提示
		if (Core.app != null) {
			Core.app.post(() -> {
				try {
					IntUI.showInfoFade(uiNotice);
				} catch (Throwable ignored) {}
			});
		}
	}

	public static void writeLog(String report) {
		writeLog(report, "Deadlock Detected! Check console or deadlock.log");
	}

	private static void checkStandardDeadlocks() {
		DeadlockResult result = detectDeadlocks();
		if (result == null || result.deadlockedIds.length == 0) {
			lastDeadlockedThreadIds = Collections.emptySet();
			return;
		}

		Set<Long> currentIds = new HashSet<>();
		for (long id : result.deadlockedIds) {
			currentIds.add(id);
		}

		// 仅在死锁线程发生变化（如首次发生，或又卷入了新线程）时报警，避免每隔几秒狂刷日志
		if (!currentIds.equals(lastDeadlockedThreadIds)) {
			lastDeadlockedThreadIds = currentIds;
			writeLog(result.report, "Deadlock Detected! Check console or deadlock.log");
		}
	}

	/**
	 * 格式化单个线程的完整状态、持有锁、等待锁及全量栈帧（避免 toString() 仅打印 8 帧的限制）。
	 */
	public static void formatThreadInfo(StringBuilder sb, ThreadInfo ti) {
		sb.append(String.format("-> Thread \"%s\" (Id=%d) State: %s\n", ti.getThreadName(), ti.getThreadId(), ti.getThreadState()));
		if (ti.getLockName() != null) {
			sb.append(String.format("   Waiting for lock: %s\n", ti.getLockName()));
		}
		if (ti.getLockOwnerName() != null) {
			sb.append(String.format("   Lock owned by thread: \"%s\" (Id=%d)\n", ti.getLockOwnerName(), ti.getLockOwnerId()));
		}

		MonitorInfo[] monitors = ti.getLockedMonitors();
		if (monitors != null && monitors.length > 0) {
			sb.append("   Locked monitors:\n");
			for (MonitorInfo mi : monitors) {
				sb.append(String.format("     - %s at %s\n", mi.getClassName(), mi.getLockedStackFrame()));
			}
		}

		LockInfo[] synchronizers = ti.getLockedSynchronizers();
		if (synchronizers != null && synchronizers.length > 0) {
			sb.append("   Locked synchronizers:\n");
			for (LockInfo li : synchronizers) {
				sb.append(String.format("     - %s\n", li));
			}
		}

		sb.append("   Stack Trace:\n");
		StackTraceElement[] stack = ti.getStackTrace();
		if (stack != null) {
			for (StackTraceElement ste : stack) {
				sb.append("     at ").append(ste).append("\n");
			}
		}
	}

	/**
	 * 专门检测被虚拟线程（Virtual Thread）卡死或存在状态分歧的平台线程。
	 * <p>核心判据：Java 层 {@link Thread#getState()} 显示为 BLOCKED，但 {@link ThreadMXBean} 报告的状态非 BLOCKED（如 RUNNABLE 且无锁信息）。
	 * 普通平台线程争用时两边状态一致（均为 BLOCKED 且带锁信息），此处自动放行。
	 */
	public static void checkHiddenDeadlocks() {
		ThreadMXBean bean = ManagementFactory.getThreadMXBean();
		Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();

		List<Thread> blocked = new ArrayList<>();
		for (Thread t : all.keySet()) {
			if (t.getState() == Thread.State.BLOCKED) blocked.add(t);
		}

		List<String> reports = new ArrayList<>();
		long now = System.nanoTime();
		long thresholdNanos = suspiciousBlockThresholdMs * 1_000_000L;

		synchronized (suspiciousMap) {
			Set<Long> alive = new HashSet<>();
			if (!blocked.isEmpty()) {
				long[] ids = new long[blocked.size()];
				for (int i = 0; i < ids.length; i++) ids[i] = blocked.get(i).getId();
				ThreadInfo[] infos = bean.getThreadInfo(ids, 0);

				for (int i = 0; i < ids.length; i++) {
					ThreadInfo ti = infos[i];
					if (ti == null) continue;                                   // 线程已退出
					if (ti.getThreadState() == Thread.State.BLOCKED) continue;  // 两边一致：普通竞争

					Thread t = blocked.get(i);
					StackTraceElement[] st = all.get(t);
					long tid = ids[i];
					alive.add(tid);

					SuspiciousBlock b = suspiciousMap.get(tid);
					if (b == null || !Arrays.equals(b.stack, st)) {             // 新出现，或栈变了
						suspiciousMap.put(tid, new SuspiciousBlock(st, now));
						continue;
					}
					if (!b.reported && now - b.firstSeenNanos >= thresholdNanos) {
						b.reported = true;
						StringBuilder sb = new StringBuilder();
						sb.append("\n=========== [SUSPECTED HIDDEN DEADLOCK] ===========\n")
						  .append("Platform thread \"").append(t.getName()).append("\" (Id=").append(tid)
						  .append(") is BLOCKED (Thread.getState) for >= ").append(suspiciousBlockThresholdMs / 1000).append("s, but ThreadMXBean reports ")
						  .append(ti.getThreadState()).append(" with no lock info.\n")
						  .append("The monitor is probably held by a virtual thread.\n");
						if (st != null) {
							for (StackTraceElement e : st) sb.append("  at ").append(e).append('\n');
						}
						reports.add(sb.toString());
					}
				}
			}

			// 移出已恢复正常或已退出的线程
			var entries = suspiciousMap.entries().iterator();
			while (entries.hasNext()) {
				var entry = entries.next();
				if (!alive.contains(entry.key)) {
					entries.remove();
				}
			}
		}

		// 在锁外输出，避免持有私有锁时做 IO；合并多线程报告，防止重复写日志与弹窗
		if (!reports.isEmpty()) {
			String dumpPath = dumpAllThreadsJson();
			String cycleAnalysis = dumpPath != null ? analyzeDump(dumpPath) : null;
			StringBuilder full = new StringBuilder();
			for (String r : reports) full.append(r);
			if (cycleAnalysis != null) {
				full.append("\n----------- [DEADLOCK CYCLE ANALYSIS] -----------\n")
					.append(cycleAnalysis).append("\n");
			}
			if (dumpPath != null) {
				full.append("Full thread dump (incl. virtual): ").append(dumpPath).append("\n");
			}
			full.append("===================================================");
			writeLog(full.toString(), "Hidden Deadlock Suspected! Check console or deadlock.log");
		}
	}

	/**
	 * 手动导出全局线程转储（含虚拟线程）并执行死锁环图分析。
	 * <p>注意：全量转储涉及虚拟线程遍历，在大规模并发下开销较高，仅建议手动触发排查纯虚拟线程死锁，
	 * 切勿调入高频周期性循环中。
	 * @return 分析报告字符串，若未检测到死锁环返回 null。
	 */
	public static String dumpAndAnalyzeDeadlocks() {
		String dumpPath = dumpAllThreadsJson();
		if (dumpPath == null) return null;
		return analyzeDump(dumpPath);
	}

	/**
	 * 解析指定线程转储 JSON 文件中的死锁等待环。
	 */
	public static String analyzeDump(String dumpPath) {
		try {
			String jsonContent = new Fi(dumpPath).readString();
			return analyzeJsonDump(jsonContent);
		} catch (Throwable t) {
			Log.err("[DeadlockDetector] Failed to parse thread dump JSON", t);
			return null;
		}
	}

	public static class DumpedThread {
		public final String tid;
		public final String name;
		public final boolean isVirtual;
		public final String state;
		public final String blockedOn;
		public final List<String> ownedLocks = new ArrayList<>();

		public DumpedThread(String tid, String name, boolean isVirtual, String state, String blockedOn) {
			this.tid = tid;
			this.name = name;
			this.isVirtual = isVirtual;
			this.state = state;
			this.blockedOn = blockedOn;
		}
	}

	/**
	 * 纯数据解析方法：通过 blockedOn 与 monitorsOwned 构建等待图，拓扑检测死锁环。
	 */
	public static String analyzeJsonDump(String jsonContent) {
		if (jsonContent == null || jsonContent.isEmpty()) return null;
		Jval root = Jval.read(jsonContent);
		Jval threadDump = root.get("threadDump");
		if (threadDump == null || !threadDump.has("threadContainers")) return null;

		Map<String, DumpedThread> threadsById = new HashMap<>();
		Map<String, DumpedThread> lockToOwner = new HashMap<>();

		for (Jval container : threadDump.get("threadContainers").asArray()) {
			if (!container.has("threads")) continue;
			for (Jval t : container.get("threads").asArray()) {
				String tid = t.getString("tid", "");
				String name = t.getString("name", "");
				boolean isVirtual = t.getBool("virtual", false);
				String state = t.getString("state", "");
				String blockedOn = t.has("blockedOn") ? t.getString("blockedOn", null) : null;

				DumpedThread dt = new DumpedThread(tid, name, isVirtual, state, blockedOn);
				threadsById.put(tid, dt);

				if (t.has("monitorsOwned")) {
					for (Jval m : t.get("monitorsOwned").asArray()) {
						if (m.has("locks")) {
							for (Jval lock : m.get("locks").asArray()) {
								if (lock == null || !lock.isString()) continue;
								String lockStr = lock.asString();
								dt.ownedLocks.add(lockStr);
								lockToOwner.put(lockStr, dt);
							}
						}
					}
				}
			}
		}

		// 构建死锁等待有向图并寻找简单环
		// 边：waiter -> owner (通过 blockedOn 查持有者)
		List<List<DumpedThread>> cycles = new ArrayList<>();
		Set<String> visitedInCycle = new HashSet<>();

		for (DumpedThread start : threadsById.values()) {
			if (start.blockedOn == null || !lockToOwner.containsKey(start.blockedOn)) continue;
			if (visitedInCycle.contains(start.tid)) continue;

			List<DumpedThread> path = new ArrayList<>();
			Set<String> onPath = new HashSet<>();
			DumpedThread curr = start;

			while (curr != null && curr.blockedOn != null && lockToOwner.containsKey(curr.blockedOn)) {
				if (onPath.contains(curr.tid)) {
					// 发现环
					int cycleStartIdx = -1;
					for (int i = 0; i < path.size(); i++) {
						if (path.get(i).tid.equals(curr.tid)) {
							cycleStartIdx = i;
							break;
						}
					}
					if (cycleStartIdx != -1) {
						List<DumpedThread> cycle = new ArrayList<>(path.subList(cycleStartIdx, path.size()));
						boolean newCycle = false;
						for (DumpedThread node : cycle) {
							if (visitedInCycle.add(node.tid)) {
								newCycle = true;
							}
						}
						if (newCycle) {
							cycles.add(cycle);
						}
					}
					break;
				}

				path.add(curr);
				onPath.add(curr.tid);
				curr = lockToOwner.get(curr.blockedOn);
			}
		}

		if (cycles.isEmpty()) {
			// 未成环，但排查是否有线程正被其他线程（如虚拟线程）持有的锁长时间阻塞（锁等待链）
			StringBuilder waitChains = new StringBuilder();
			int count = 0;
			for (DumpedThread waiter : threadsById.values()) {
				if (waiter.blockedOn != null && lockToOwner.containsKey(waiter.blockedOn)) {
					DumpedThread owner = lockToOwner.get(waiter.blockedOn);
					count++;
					waitChains.append(String.format("  [%s] Thread \"%s\" (Id=%s, State=%s)\n",
						waiter.isVirtual ? "Virtual" : "Platform", waiter.name, waiter.tid, waiter.state));
					waitChains.append(String.format("    - WAITING FOR LOCK: %s (held by [%s] \"%s\", Id=%s, State=%s)\n",
						waiter.blockedOn, owner.isVirtual ? "Virtual" : "Platform", owner.name, owner.tid, owner.state));
				}
			}
			if (count > 0) {
				return "No circular deadlock detected, but found " + count + " thread(s) waiting on known lock holder(s):\n" + waitChains;
			}
			return null;
		}

		StringBuilder sb = new StringBuilder();
		sb.append("Detected ").append(cycles.size()).append(" suspected deadlocked cycle(s) from thread dump (locks identified by Class@identityHash):\n");

		for (int c = 0; c < cycles.size(); c++) {
			List<DumpedThread> cycle = cycles.get(c);
			sb.append(String.format("\nSuspected Cycle #%d (%d threads in cycle):\n", c + 1, cycle.size()));
			for (int i = 0; i < cycle.size(); i++) {
				DumpedThread waiter = cycle.get(i);
				DumpedThread nextOwner = cycle.get((i + 1) % cycle.size());
				sb.append(String.format("  [%s] Thread \"%s\" (Id=%s, State=%s)\n",
					waiter.isVirtual ? "Virtual" : "Platform", waiter.name, waiter.tid, waiter.state));
				sb.append(String.format("    - WAITING FOR LOCK: %s (held by [%s] \"%s\")\n",
					waiter.blockedOn, nextOwner.isVirtual ? "Virtual" : "Platform", nextOwner.name));
				if (!waiter.ownedLocks.isEmpty()) {
					sb.append(String.format("    - HOLDING LOCK(S): %s\n", waiter.ownedLocks));
				}
			}
		}

		return sb.toString();
	}

	private static String dumpAllThreadsJson() {
		try {
			if (IntVars.dataDirectory == null) return null;

			// 简单的文件轮转清理：只保留最新的 5 个 threads-*.json，清理旧文件避免占满磁盘
			try {
				Fi[] oldDumps = IntVars.dataDirectory.list(f -> f.getName().startsWith("threads-") && f.getName().endsWith(".json"));
				if (oldDumps != null && oldDumps.length >= 5) {
					Arrays.sort(oldDumps, Comparator.comparingLong(Fi::lastModified));
					for (int i = 0; i <= oldDumps.length - 5; i++) {
						oldDumps[i].delete();
					}
				}
			} catch (Throwable t) {
				Log.warn("[DeadlockDetector] Failed to clean old thread dumps", t);
			}

			var f = IntVars.dataDirectory.child("threads-" + System.currentTimeMillis() + ".json");
			ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
				.dumpThreads(f.file().getAbsolutePath(),
					ThreadDumpFormat.JSON);
			return f.file().getAbsolutePath();
		} catch (Throwable t) {
			Log.err("[DeadlockDetector] Thread dump failed", t);
			return null;
		}
	}

	public static class DeadlockResult {
		public final long[] deadlockedIds;
		public final String report;

		public DeadlockResult(long[] deadlockedIds, String report) {
			this.deadlockedIds = deadlockedIds;
			this.report = report;
		}
	}

	/**
	 * 执行一次全局死锁检测。
	 * @return 如果存在死锁，返回包含死锁线程 ID 和格式化报告的结构体；否则返回 null。
	 */
	public static DeadlockResult detectDeadlocks() {
		ThreadMXBean bean = ManagementFactory.getThreadMXBean();
		long[] deadlockedIds;

		// 优先检测包括 java.util.concurrent (ReentrantLock) 在内的所有独占锁
		if (bean.isSynchronizerUsageSupported()) {
			deadlockedIds = bean.findDeadlockedThreads();
		} else {
			deadlockedIds = bean.findMonitorDeadlockedThreads();
		}

		if (deadlockedIds == null || deadlockedIds.length == 0) {
			return null;
		}

		// 获取包含堆栈、锁持有者等完整信息的 ThreadInfo
		ThreadInfo[] infos = bean.getThreadInfo(deadlockedIds, true, true);
		StringBuilder sb = new StringBuilder();
		sb.append("\n===================== [DEADLOCK DETECTED] =====================\n");
		sb.append("Detected ").append(deadlockedIds.length).append(" deadlocked thread(s):\n\n");

		for (ThreadInfo ti : infos) {
			if (ti == null) continue;
			formatThreadInfo(sb, ti);
			sb.append("\n");
		}
		sb.append("===============================================================");
		return new DeadlockResult(deadlockedIds, sb.toString());
	}

	/**
	 * 手动执行一次死锁探查并返回报告字符串。
	 * <p>注意：仅能检测调用瞬时的 Java 互斥锁死锁，不能排查 Native 锁死锁与假死阻塞。
	 * @return 如果存在死锁，返回排查报告；否则返回 null。
	 */
	public static String checkDeadlocks() {
		DeadlockResult res = detectDeadlocks();
		return res != null ? res.report : null;
	}
}
