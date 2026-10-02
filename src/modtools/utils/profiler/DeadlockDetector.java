package modtools.utils.profiler;

import arc.Core;
import arc.util.Log;

import java.lang.management.*;
import java.util.*;

/**
 * JVM 死锁检测看门狗与分析工具。
 * 独立于采样分析器运行，避免对 5ms 高频火焰图采样造成时延抖动。
 */
public class DeadlockDetector {
	public static volatile boolean enabled = false;
	public static volatile int checkIntervalMs = 3000;

	private static volatile Thread watchdogThread = null;
	private static volatile boolean running = false;
	private static Set<Long> lastDeadlockedThreadIds = Collections.emptySet();

	public static synchronized void setEnabled(boolean enable) {
		enabled = enable;
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

	public static synchronized void stopWatchdog() {
		running = false;
		if (watchdogThread != null) {
			watchdogThread.interrupt();
			watchdogThread = null;
		}
		lastDeadlockedThreadIds = Collections.emptySet();
	}

	public static boolean isRunning() {
		return running;
	}

	private static void loop() {
		while (running) {
			try {
				Thread.sleep(checkIntervalMs);
			} catch (InterruptedException e) {
				break;
			}
			if (!running || !enabled) break;

			checkAndLog();
		}
	}

	public static void checkAndLog() {
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
			Log.err("[DeadlockDetector] " + result.report);
			if (Core.app != null) {
				Core.app.post(() -> {
					try {
						modtools.ui.IntUI.showInfoFade("Deadlock Detected! Check console.");
					} catch (Throwable ignored) {}
				});
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
	 * @return 如果存在死锁，返回排查报告；否则返回 null。
	 */
	public static String checkDeadlocks() {
		DeadlockResult res = detectDeadlocks();
		return res != null ? res.report : null;
	}
}
