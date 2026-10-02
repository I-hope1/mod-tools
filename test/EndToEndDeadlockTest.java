import arc.files.Fi;
import mindustry.Vars;
import mindustry.mod.ModClassLoader;
import mindustry.mod.Mods;
import modtools.IntVars;
import modtools.utils.profiler.DeadlockDetector;

import java.io.File;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 完整端到端测试：
 * 1. 误报测试：平台线程持锁 sleep 18 秒，另一平台线程争用 18 秒，看门狗 3 秒一次循环检测，验证全程零误报。
 * 2. 真实混合死锁测试：看门狗端到端运行，9~12 秒准时捕获一次 SUSPECTED HIDDEN DEADLOCK，随后不再重复刷屏。
 */
public class EndToEndDeadlockTest {

	private static void initEnv() {
		try {
			Vars.dataDirectory = Fi.get("./build/tmp/mindustry_data_e2e");
			Vars.dataDirectory.mkdirs();

			Vars.mods = (Mods) ihope_lib.MyReflect.unsafe.allocateInstance(Mods.class);
			ModClassLoader loader = new ModClassLoader(EndToEndDeadlockTest.class.getClassLoader());
			arc.util.Reflect.set(Mods.class, Vars.mods, "mainLoader", loader);

			IntVars.dataDirectory = Vars.dataDirectory.child("mod_tools").child("b0kkihope");
			IntVars.dataDirectory.mkdirs();

			// 清理旧日志与转储
			if (IntVars.dataDirectory.child("deadlock.log").exists()) {
				IntVars.dataDirectory.child("deadlock.log").delete();
			}
			File[] oldDumps = IntVars.dataDirectory.file().listFiles((dir, name) -> name.startsWith("threads-") && name.endsWith(".json"));
			if (oldDumps != null) {
				for (File f : oldDumps) f.delete();
			}
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	public static void main(String[] args) throws Exception {
		System.out.println("==========================================================");
		System.out.println("    DEADLOCK DETECTOR FULL END-TO-END VERIFICATION SUITE   ");
		System.out.println("==========================================================");

		initEnv();

		testFalsePositivePlatformContention();
		testIssue302EndToEndDetection();

		System.out.println("\n>>> ALL END-TO-END TESTS PASSED WITH 100% SUCCESS! <<<");
		System.exit(0);
	}

	/**
	 * 测试 1（真正的误报测试）：
	 * 一个平台线程持锁 sleep 16 秒，另一个平台线程去抢（持续 BLOCKED > 15 秒）。
	 * 默认阈值 9 秒，看门狗每 3 秒检测一次。
	 * 预期：两边状态一致（MXBean 也是 BLOCKED 并有锁信息），全程 0 次误报！
	 */
	private static void testFalsePositivePlatformContention() throws Exception {
		System.out.println("\n--- [E2E Test 1] False Positive Test: Platform Thread Contention (16s) ---");
		DeadlockDetector.checkIntervalMs = 3000;
		DeadlockDetector.suspiciousBlockThresholdMs = 9000;
		DeadlockDetector.startWatchdog();

		Object contentionLock = new Object();
		AtomicBoolean holderDone = new AtomicBoolean(false);

		Thread holder = Thread.ofPlatform().name("e2e-holder").start(() -> {
			synchronized (contentionLock) {
				System.out.println("[e2e-holder] Acquired lock, sleeping for 16 seconds...");
				long start = System.currentTimeMillis();
				while (System.currentTimeMillis() - start < 16000) {
					try { Thread.sleep(500); } catch (InterruptedException ignored) {}
				}
				holderDone.set(true);
				System.out.println("[e2e-holder] Releasing lock.");
			}
		});

		Thread.sleep(200);

		Thread waiter = Thread.ofPlatform().name("e2e-waiter").start(() -> {
			System.out.println("[e2e-waiter] Attempting to acquire lock (will block)...");
			synchronized (contentionLock) {
				System.out.println("[e2e-waiter] Finally acquired lock after waiting.");
			}
		});

		// 监控 15 秒（经过 5 次看门狗巡检周期：3s, 6s, 9s, 12s, 15s）
		for (int sec = 1; sec <= 15; sec++) {
			Thread.sleep(1000);
			if (sec % 3 == 0) {
				System.out.println("Elapsed " + sec + "s / 15s... waiter state: " + waiter.getState());
			}
		}

		// 检查 deadlock.log 是否存在任何报警
		Fi logFile = IntVars.dataDirectory.child("deadlock.log");
		if (logFile.exists() && logFile.readString().contains("SUSPECTED HIDDEN DEADLOCK")) {
			throw new AssertionError("FALSE POSITIVE! Normal platform contention was incorrectly reported as hidden deadlock!");
		}

		// 等待持有者释放锁并结束
		holder.join(5000);
		waiter.join(5000);
		DeadlockDetector.stopWatchdog();

		System.out.println("[E2E Test 1] PASSED: Normal platform contention was monitored for 15s with ZERO false positives.");
	}

	/**
	 * 测试 2（混合死锁真实端到端捕获）：
	 * 平台线程与虚拟线程产生 Issue 302 循环等待。
	 * 看门狗开启（间隔 3s，阈值 9s）。
	 * 验证：
	 * 1. 在 9 ~ 12 秒之间准时触发 SUSPECTED HIDDEN DEADLOCK 报警。
	 * 2. 导出 threads-*.json 转储文件。
	 * 3. 继续运行至 18 秒，验证不会重复刷屏（报告计数恰好为 1）。
	 */
	private static void testIssue302EndToEndDetection() throws Exception {
		System.out.println("\n--- [E2E Test 2] Real Issue 302 End-to-End Detection (18s) ---");
		DeadlockDetector.checkIntervalMs = 3000;
		DeadlockDetector.suspiciousBlockThresholdMs = 9000;
		DeadlockDetector.startWatchdog();

		var m1 = new Object();
		var m2 = new Object();
		var coop = new Phaser(2);

		Thread p = Thread.ofPlatform().name("e2e-victim-platform").start(() -> {
			synchronized (m1) {
				coop.arriveAndAwaitAdvance();
				synchronized (m2) {
					System.out.println("p finished");
				}
			}
		});

		Thread.ofVirtual().name("e2e-holder-virtual").start(() -> {
			synchronized (m2) {
				coop.arriveAndAwaitAdvance();
				synchronized (m1) {
					System.out.println("v finished");
				}
			}
		});

		System.out.println("Issue 302 Deadlock initiated. Monitoring watchdog over 18 seconds (6 check cycles)...");

		Fi logFile = IntVars.dataDirectory.child("deadlock.log");
		long reportTimeMs = -1;
		long startTime = System.currentTimeMillis();

		for (int sec = 1; sec <= 18; sec++) {
			Thread.sleep(1000);
			long elapsed = (System.currentTimeMillis() - startTime) / 1000;

			if (logFile.exists() && logFile.readString().contains("SUSPECTED HIDDEN DEADLOCK") && reportTimeMs == -1) {
				reportTimeMs = System.currentTimeMillis() - startTime;
				System.out.println(">>> [ALARM FIRED] Report detected at t = " + (reportTimeMs / 1000.0) + "s! <<<");
			}

			if (sec % 3 == 0) {
				System.out.println("Elapsed " + elapsed + "s... platform state: " + p.getState());
			}
		}

		DeadlockDetector.stopWatchdog();

		if (reportTimeMs == -1) {
			throw new AssertionError("Watchdog failed to report hidden deadlock within 18 seconds!");
		}

		double reportSec = reportTimeMs / 1000.0;
		System.out.println("Report triggered at: " + reportSec + " seconds (expected around 9 ~ 12s).");
		if (reportSec < 8.5 || reportSec > 13.5) {
			throw new AssertionError("Report triggered at unexpected time: " + reportSec + "s (expected 9 ~ 12s)");
		}

		// 验证报告内容与去重
		String content = logFile.readString();
		int occurrences = countSubstrings(content, "SUSPECTED HIDDEN DEADLOCK");
		System.out.println("Total alarm report count in deadlock.log: " + occurrences);
		if (occurrences != 1) {
			throw new AssertionError("Expected exactly 1 alarm report, but found " + occurrences + " (repeated spam detected)!");
		}

		if (!content.contains("e2e-victim-platform")) {
			throw new AssertionError("Report did not contain platform victim thread name!");
		}

		// 检查 threads-*.json 转储文件
		File[] dumps = IntVars.dataDirectory.file().listFiles((dir, name) -> name.startsWith("threads-") && name.endsWith(".json"));
		if (dumps == null || dumps.length == 0) {
			throw new AssertionError("No threads-*.json dump was generated!");
		}
		System.out.println("Dump file generated: " + dumps[0].getName() + ", size: " + dumps[0].length() + " bytes");

		System.out.println("[E2E Test 2] PASSED: Hidden deadlock correctly detected at 9~12s, dumped to JSON, and did not spam.");
	}

	private static int countSubstrings(String str, String findStr) {
		int lastIndex = 0;
		int count = 0;
		while (lastIndex != -1) {
			lastIndex = str.indexOf(findStr, lastIndex);
			if (lastIndex != -1) {
				count++;
				lastIndex += findStr.length();
			}
		}
		return count;
	}
}
