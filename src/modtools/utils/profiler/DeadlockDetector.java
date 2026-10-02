package modtools.utils.profiler;

import arc.Core;
import arc.struct.LongMap;
import arc.struct.LongSeq;
import arc.util.Log;
import modtools.IntVars;
import modtools.ui.IntUI;

import java.lang.management.*;
import java.util.*;

/**
 * JVM 死锁检测看门狗与排查工具。
 * <p>独立于火焰图采样分析器运行，避免对 5ms 高频 CPU 采样造成时延抖动。
 *
 * <p><b>已知限制（Known Limitations）：</b>
 * <ul>
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
 *   <li><b>JVM 全局排查开销：</b>构建死锁环需要遍历 JVM 内部锁图与线程栈，因此检测间隔应保持在秒级
 *       （默认 3000ms，代码强制最低 500ms），切勿调为毫秒级以免引起周期性停顿。</li>
 * </ul>
 */
public class DeadlockDetector {
	public static volatile int checkIntervalMs = 3000;

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
		String lockName;
		int hits;
		boolean reported;

		SuspiciousBlock(String lockName) {
			this.lockName = lockName;
			this.hits = 1;
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
		suspiciousMap.clear();
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

			// 使用占位符格式化，避免报告内容里的 '@' (如 Object@1a2b3c) 被错误解析
			Log.err("[DeadlockDetector] @", result.report);

			// 写入磁盘文件，非终端启动也能在事后排查
			try {
				if (IntVars.dataDirectory != null) {
					IntVars.dataDirectory.child("deadlock.log").writeString(result.report, false);
				}
			} catch (Throwable t) {
				Log.err("[DeadlockDetector] Failed to write deadlock.log", t);
			}

			// 若主线程还活着，在游戏顶层飘字提示
			if (Core.app != null) {
				Core.app.post(() -> {
					try {
						IntUI.showInfoFade("Deadlock Detected! Check console or deadlock.log");
					} catch (Throwable ignored) {}
				});
			}
		}
	}

	/**
	 * 专门检测被虚拟线程（Virtual Thread）或隐形持有者长时间卡死的平台线程。
	 * 核心特征：正在争抢 monitor (BLOCKED)，但底层找不到持有者 (lockOwnerId == -1 且 lockName != null)。
	 */
	public static void checkHiddenDeadlocks() {
		ThreadMXBean bean = ManagementFactory.getThreadMXBean();
		long[] allIds = bean.getAllThreadIds();
		// 批量轻量拉取平台线程状态，maxDepth = 0 不抓取堆栈以将常规开销降到最低
		ThreadInfo[] infos = bean.getThreadInfo(allIds, 0);
		LongSeq currentBlockedIds = new LongSeq();

		for (ThreadInfo ti : infos) {
			if (ti == null) continue;

			if (ti.getThreadState() == Thread.State.BLOCKED && ti.getLockOwnerId() == -1 && ti.getLockName() != null) {
				long tid = ti.getThreadId();
				currentBlockedIds.add(tid);

				SuspiciousBlock block = suspiciousMap.get(tid);
				if (block != null && Objects.equals(block.lockName, ti.getLockName())) {
					block.hits++;
					// 连续 3 次检测（约 9 秒）都在等同一个无主锁，且未报警过，判定为严重挂起/隐形死锁
					if (block.hits >= 3 && !block.reported) {
						block.reported = true;
						ThreadInfo fullInfo = bean.getThreadInfo(tid, Integer.MAX_VALUE);
						String report = String.format(
							"\n================= [SUSPECTED HIDDEN DEADLOCK] =================\n" +
							"Platform thread \"%s\" (Id=%d) has been BLOCKED on '%s' for a long time,\n" +
							"but the lock owner is invisible! (Likely held by a Virtual Thread or native monitor)\n\n" +
							"%s\n" +
							"===============================================================",
							ti.getThreadName(), tid, ti.getLockName(), fullInfo != null ? fullInfo.toString() : "<no stack>"
						);

						Log.err("[DeadlockDetector] @", report);

						try {
							if (IntVars.dataDirectory != null) {
								IntVars.dataDirectory.child("deadlock.log").writeString(report, false);
							}
						} catch (Throwable ignored) {}

						if (Core.app != null) {
							Core.app.post(() -> {
								try {
									IntUI.showInfoFade("Hidden Deadlock Detected! Check console or deadlock.log");
								} catch (Throwable ignored) {}
							});
						}
					}
				} else {
					suspiciousMap.put(tid, new SuspiciousBlock(ti.getLockName()));
				}
			}
		}

		// 移出已恢复正常或已退出的线程
		var entries = suspiciousMap.entries().iterator();
		while (entries.hasNext()) {
			var entry = entries.next();
			if (!currentBlockedIds.contains(entry.key)) {
				entries.remove();
			}
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
