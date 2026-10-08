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
 * JVM 线程死锁检测看门狗与自动化排查工具。
 * <p>独立于火焰图采样分析器运行，避免对 5ms 高频 CPU 采样造成时延抖动。
 *
 * <h2>一、演进背景：虚拟线程死锁的排查困境（The Virtual Thread Deadlock Dilemma）</h2>
 * <ul>
 *   <li><b>JDK 19 预览期痛点（Heinz Kabutz - The Java Specialists' Newsletter Issue 302）：</b><br>
 *       在虚拟线程（Virtual Thread / Project Loom）早期阶段，定位虚拟线程死锁极为困难：
 *       传统诊断工具（如 {@code jstack}、{@code ThreadMXBean.findDeadlockedThreads()}）仅针对平台线程（Platform Threads），
 *       对虚拟线程完全不可见，或仅能看到载体线程（Carrier Thread）；早期甚至必须显式添加 {@code -Djdk.trackAllThreads=true}
 *       启动参数才记录虚拟线程；且当时的转储完全缺少内置监视器锁（{@code synchronized}）的持锁与等锁信息；
 *       同时载体线程被 {@code synchronized} 钉住（Pinning）还会引发载体池耗尽伪死锁。</li>
 *   <li><b>JDK 21 正式落地（JEP 444）：</b><br>
 *       确立了非 STW（安全点停顿）的异步转储入口 {@code jcmd <pid> Thread.dump_to_file}（以及底层的
 *       {@link HotSpotDiagnosticMXBean#dumpThreads(String, ThreadDumpFormat)}），并默认开启全量线程追踪（JDK-8309406）。
 *       但在 JDK 21~24 中，导出的转储依然缺乏 Object Monitor 锁的持有者与等待者映射。</li>
 *   <li><b>JDK 24 消除 Pinning（JEP 491）：</b><br>
 *       重构了 HotSpot 内部监视器机制，虚拟线程在争用 {@code synchronized} 锁时不再钉住载体线程，允许正常卸载（Unmount）。</li>
 *   <li><b>JDK 25 引入 Monitor 锁转储（JDK-8356870 / JDK-8356872）：</b><br>
 *       在 JDK 25 中，{@code Thread.dump_to_file}（以及底层 Diagnostic MXBean）正式支持输出 Object Monitor 锁信息，
 *       转储 JSON 中新增了 {@code monitorsOwned}（持有的监视器锁）与 {@code blockedOn}（正在等待的锁对象）。</li>
 *   <li><b>本工具的核心定位与闭环补充：</b><br>
 *       虽然 JDK 25 补全了锁的原始转储数据，但 <b>HotSpot 自身至今仍未提供针对虚拟线程的死锁检测算法</b>
 *       （相关功能仍跟踪在 JDK-8365057 等议题中），传统 {@link ThreadMXBean#findDeadlockedThreads()} 依然无法感知虚拟线程死锁。<br>
 *       本工具在此基础上，实现了<b>轻量级分歧启发式秒级监控</b>与<b>基于转储图分析（Cycle Graph Analysis）的有向图找环算法</b>，
 *       闭环了官方“只提供原始数据、不替开发者分析死锁环”的关键一步。</li>
 * </ul>
 *
 * <h2>二、检测架构与工作机制（Detection Architecture）</h2>
 * <ol>
 *   <li><b>第一层：标准平台线程死锁（Standard Deadlock Detection）</b><br>
 *       周期性（默认 3 秒）调用 {@link ThreadMXBean#findDeadlockedThreads()}，检测平台线程间的 {@code synchronized}
 *       及 JUC 独占锁死锁，获取完整线程名、ID、状态与栈帧。</li>
 *   <li><b>第二层：隐形混合死锁启发式（Hidden Hybrid Deadlock Heuristic）</b><br>
 *       由于虚拟线程持锁时 JVM 内部状态映射的分歧，平台线程在等待虚拟线程持有的监视器时，
 *       Java 层 {@link Thread#getState()} 显示为 {@link Thread.State#BLOCKED}，
 *       但 {@link ThreadMXBean#getThreadInfo(long)} 却报告为非 BLOCKED（如 RUNNABLE 且无锁信息）。<br>
 *       本工具秒级扫描此“分歧特征”，并结合持续时间阈值（默认 9 秒）和堆栈帧不变性判定，自动过滤瞬间锁竞争。</li>
 *   <li><b>第三层：自动化死锁环路图分析（Cycle Graph Analysis）</b><br>
 *       当隐形死锁或异常阻塞成立时，自动触发调用 {@code dumpThreads(..., JSON)} 导出全量转储，
 *       自建 {@code Thread -> Lock -> Thread} 有向图，使用 DFS/拓扑算法还原跨平台线程与虚拟线程的死锁闭环；
 *       若无闭环但存在单向等待，自动输出锁等待链（Waiting Chains），直击长时间阻塞卡死根因。</li>
 * </ol>
 *
 * <h2>三、能力矩阵与已知限制（Limitations & Scope）</h2>
 * <ul>
 *   <li><b>支持范围：</b>
 *     <ul>
 *       <li>平台线程 ⟷ 平台线程（{@code synchronized} 与 JUC 独占锁，如 {@code ReentrantLock}）：全自动秒级报警。</li>
 *       <li>平台线程 ⟷ 虚拟线程（{@code synchronized} 混合死锁）：全自动分歧启发式捕获 + 自动转储生成死锁闭环报告。</li>
 *       <li>虚拟线程 ⟷ 虚拟线程（纯虚拟线程死锁）：支持通过 {@link #dumpAndAnalyzeDeadlocks()} 一键手动排查并输出环路分析
 *           （因纯虚拟线程互锁时无平台线程陷入 BLOCKED，后台周期性看门狗不主动高频扫描，以避免大规模虚拟线程下的性能损耗）。</li>
 *     </ul>
 *   </li>
 *   <li><b>暂不支持：</b>
 *     <ul>
 *       <li><b>虚拟线程持有的 JUC 显式锁：</b>等待 JUC 显式锁（{@code ReentrantLock}）处于 WAITING 状态（非 BLOCKED），
 *           且目前 JDK 25 的 dumpThreads JSON 尚未记录 JUC 锁的所有权。</li>
 *       <li><b>协作式逻辑死锁与假死：</b>因条件变量未触发（如 {@code Object.wait()}、{@code CountDownLatch.await()}、
 *           {@code CompletableFuture.join()}）导致的永久等待或阻塞 IO，不属于资源循环互锁，无法被探测。</li>
 *       <li><b>Native 锁：</b>JNI/C++ 层的原生锁死锁（如 {@code std::mutex}、Windows CriticalSection）。</li>
 *     </ul>
 *   </li>
 *   <li><b>主线程死锁时 UI 冻结：</b>若死锁涉及游戏主线程（渲染/事件分发），UI 将冻结且桌面飘字无法显示，
 *       依赖后台守护线程向控制台输出与写入本地磁盘 {@code deadlock.log} 文件。</li>
 *   <li><b>文件保护机制：</b>转储文件 {@code threads-*.json} 自动执行磁盘轮转保留（最多保留最新 5 份），防止占满磁盘空间。</li>
 * </ul>
 *
 * @see <a href="https://www.javaspecialists.eu/archive/Issue302-Virtual-Thread-Deadlocks.html">The Java Specialists' Newsletter Issue 302</a>
 * @see <a href="https://openjdk.org/jeps/444">JEP 444: Virtual Threads</a>
 * @see <a href="https://openjdk.org/jeps/491">JEP 491: Synchronize Virtual Threads without Pinning</a>
 * @see <a href="https://bugs.openjdk.org/browse/JDK-8356870">JDK-8356870: Thread dump enhancements</a>
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
		// 控制台输出
		Log.err("[DeadlockDetector] @", report);

		// 磁盘文件追加写入，带时间戳分隔
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

		// 若主线程仍然存活，向顶层弹出浮动提示
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
	 * 专门检测被虚拟线程（Virtual Thread）阻塞或与虚拟线程发生混合死锁的平台线程。
	 *
	 * <h3>核心判据：JVM 内部状态映射分歧</h3>
	 * 在 Java 24/25（JEP 491）环境下，Object Monitor 的所有权与虚拟线程实例绑定。
	 * 当平台线程试图进入被虚拟线程持有的 {@code synchronized} 块时：
	 * <ul>
	 *   <li>Java 语言层 {@link Thread#getState()} 显示为 {@link Thread.State#BLOCKED}；</li>
	 *   <li>但管理层 {@link ThreadMXBean#getThreadInfo(long)} 报出的状态非 BLOCKED（实测报 RUNNABLE，且 lockName 与 lockOwnerId 均为 null）。</li>
	 * </ul>
	 * 而如果是平台线程之间的普通锁争用，两边状态均一致报告为 BLOCKED 且包含持有者信息，此处自动放行并交由标准死锁检测处理。
	 *
	 * <h3>多重防护与防抖机制</h3>
	 * <ol>
	 *   <li><b>持续性过滤：</b>连续处于该分歧状态达到 {@link #suspiciousBlockThresholdMs}（默认 9 秒）才进入怀疑期。</li>
	 *   <li><b>栈帧稳定性验证：</b>对比 {@link StackTraceElement} 数组，仅当卡在相同栈顶时才累计计时，避免正常锁抢占。</li>
	 *   <li><b>零 STW 与低开销：</b>通过 {@link Thread#getAllStackTraces()} 仅扫描平台线程，未触发异常时不发起全量虚拟线程转储。</li>
	 *   <li><b>合并报警与自动建图：</b>命中怀疑期时，自动触发 {@link #dumpAllThreadsJson()}，解析全量 JSON 拓扑并输出死锁闭环报告。</li>
	 * </ol>
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
				String tid = idOf(t.get("tid"));
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

	/**
	 * 安全提取 JSON 中可能为 String 或 Number 格式的 ID 字段（如线程 ID tid、进程 ID pid 等）。
	 * <p>兼容 JDK-8381169 将 threadDump 中的 tid 从字符串改写为数值的变更。
	 */
	private static String idOf(Jval v) {
		if (v == null || v.isNull()) return "";
		if (v.isNumber()) return String.valueOf(v.asLong());
		if (v.isString()) return v.asString();
		return "";
	}

	/**
	 * 调用 JVM 底层 Diagnostic 机制导出包含虚拟线程在内的全量线程 JSON 转储。
	 * <p>基于 {@link HotSpotDiagnosticMXBean#dumpThreads(String, ThreadDumpFormat)} 实现（JDK 21+ 规范，JDK 25+ 包含 Monitor 锁信息）。
	 * 执行前自动做磁盘轮转清理，保持磁盘中最多保留 5 份最新的 JSON 文件。
	 *
	 * @return 生成的 JSON 文件绝对路径，若失败返回 null。
	 */
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
