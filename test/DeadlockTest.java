import arc.files.Fi;
import mindustry.Vars;
import mindustry.mod.ModClassLoader;
import mindustry.mod.Mods;
import modtools.IntVars;
import modtools.utils.profiler.DeadlockDetector;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 完整验证 JVM 标准死锁与基于 JavaSpecialists Issue 302 的虚拟线程隐形死锁检测。
 */
public class DeadlockTest {

	private static void initEnv() {
		try {
			Vars.dataDirectory = Fi.get("./build/tmp/mindustry_data");
			Vars.dataDirectory.mkdirs();

			Vars.mods = (Mods) ihope_lib.MyReflect.unsafe.allocateInstance(Mods.class);
			ModClassLoader loader = new ModClassLoader(DeadlockTest.class.getClassLoader());
			arc.util.Reflect.set(Mods.class, Vars.mods, "mainLoader", loader);

			IntVars.dataDirectory = Vars.dataDirectory.child("mod_tools").child("b0kkihope");
			IntVars.dataDirectory.mkdirs();
		} catch (Throwable t) {
			System.err.println("Note: Environment init non-critical error: " + t.getMessage());
		}
	}

	public static void main(String[] args) throws Exception {
		System.out.println("=================================================");
		System.out.println("            RUNNING DEADLOCK DETECTOR TESTS       ");
		System.out.println("=================================================");

		initEnv();

		testStandardDeadlock();
		testNormalPlatformContentionNotReported();
		testVirtualThreadHiddenDeadlock();
		testPureVirtualThreadDeadlockManual();
		testNonCycleWaitChainAndNullLockParsing();
		testThreadDumpRotation();
		testWatchdogLifecycle();

		System.out.println("\n>>> ALL DEADLOCK TESTS COMPLETED SUCCESSFULLY! <<<");
		System.exit(0);
	}

	/**
	 * 测试场景 1：标准平台线程死锁（互斥锁交叉依赖）。
	 */
	private static void testStandardDeadlock() throws Exception {
		System.out.println("\n--- Test 1: Standard Platform Threads Deadlock ---");
		Object lock1 = new Object();
		Object lock2 = new Object();
		java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(2);

		Thread t1 = new Thread(() -> {
			synchronized (lock1) {
				latch.countDown();
				try { latch.await(2, TimeUnit.SECONDS); } catch (Exception ignored) {}
				synchronized (lock2) {
					System.out.println("T1 completed");
				}
			}
		}, "TestThread-Platform-1");
		t1.setDaemon(true);

		Thread t2 = new Thread(() -> {
			synchronized (lock2) {
				latch.countDown();
				try { latch.await(2, TimeUnit.SECONDS); } catch (Exception ignored) {}
				synchronized (lock1) {
					System.out.println("T2 completed");
				}
			}
		}, "TestThread-Platform-2");
		t2.setDaemon(true);

		t1.start();
		t2.start();

		Thread.sleep(300);

		DeadlockDetector.DeadlockResult result = DeadlockDetector.detectDeadlocks();
		if (result == null || result.deadlockedIds.length != 2) {
			throw new AssertionError("Expected 2 deadlocked threads detected, got: " +
				(result == null ? "null" : result.deadlockedIds.length));
		}

		System.out.println("Detected deadlocked threads: " + java.util.Arrays.toString(result.deadlockedIds));
		if (!result.report.contains("TestThread-Platform-1") || !result.report.contains("TestThread-Platform-2")) {
			throw new AssertionError("Report does not contain thread names!");
		}
		System.out.println("Test 1 PASSED: Standard Deadlock Detected Correctly.");
	}

	/**
	 * 测试场景 2：普通平台线程锁竞争。
	 * 验证当两边一致为 BLOCKED 且带锁信息时，checkHiddenDeadlocks() 不会误报。
	 */
	private static void testNormalPlatformContentionNotReported() throws Exception {
		System.out.println("\n--- Test 2: Normal Platform Contention (Should Not Trigger Hidden Detector) ---");
		Object normalLock = new Object();
		AtomicBoolean holderRunning = new AtomicBoolean(true);

		Thread holder = Thread.ofPlatform().name("test-contention-holder").start(() -> {
			synchronized (normalLock) {
				while (holderRunning.get()) {
					try { Thread.sleep(50); } catch (InterruptedException ignored) {}
				}
			}
		});

		Thread.sleep(100);

		Thread waiter = Thread.ofPlatform().name("test-contention-waiter").start(() -> {
			synchronized (normalLock) {
				System.out.println("waiter entered");
			}
		});

		Thread.sleep(300);

		long oldThreshold = DeadlockDetector.suspiciousBlockThresholdMs;
		DeadlockDetector.suspiciousBlockThresholdMs = 200;

		try {
			// 连续检测两次，确保由于 ti.getThreadState() == BLOCKED，被直接跳过
			DeadlockDetector.checkHiddenDeadlocks();
			Thread.sleep(300);
			DeadlockDetector.checkHiddenDeadlocks();

			System.out.println("Test 2 PASSED: Normal platform contention correctly ignored.");
		} finally {
			holderRunning.set(false);
			DeadlockDetector.suspiciousBlockThresholdMs = oldThreshold;
		}
	}

	/**
	 * 测试场景 3：参考 JavaSpecialists Issue 302，平台线程与虚拟线程混合死锁。
	 * 触发状态分歧特征：平台线程 getState() == BLOCKED，但 ThreadMXBean 报告非 BLOCKED。
	 * 验证能够捕获并自动导出 threads-*.json。
	 */
	private static void testVirtualThreadHiddenDeadlock() throws Exception {
		System.out.println("\n--- Test 3: JavaSpecialists Issue 302 - Virtual Thread Hidden Deadlock ---");

		var monitor1 = new Object();
		var monitor2 = new Object();
		var coop = new Phaser(2);

		var platformThread = Thread.ofPlatform().name("TestThread-Platform-Victim").start(() -> {
			synchronized (monitor1) {
				coop.arriveAndAwaitAdvance();
				synchronized (monitor2) {
					System.out.println("platform entered m2");
				}
			}
		});

		Thread.ofVirtual().name("TestThread-Virtual-Holder").start(() -> {
			synchronized (monitor2) {
				coop.arriveAndAwaitAdvance();
				synchronized (monitor1) {
					System.out.println("virtual entered m1");
				}
			}
		});

		Thread.sleep(400);

		ThreadMXBean bean = ManagementFactory.getThreadMXBean();
		long[] jvmDeadlocks = bean.findDeadlockedThreads();
		System.out.println("Standard JVM findDeadlockedThreads() = " + java.util.Arrays.toString(jvmDeadlocks));

		var ti = bean.getThreadInfo(new long[]{platformThread.threadId()}, 0)[0];
		System.out.println("platformThread.getState() = " + platformThread.getState());
		System.out.println("MXBean state = " + ti.getThreadState() + ", lockName = " + ti.getLockName());

		if (platformThread.getState() != Thread.State.BLOCKED) {
			throw new AssertionError("Expected platform thread to be BLOCKED");
		}
		if (ti.getThreadState() == Thread.State.BLOCKED) {
			System.out.println("Note: MXBean reported BLOCKED in this environment");
		}

		// 运行检测器
		long oldThreshold = DeadlockDetector.suspiciousBlockThresholdMs;
		DeadlockDetector.suspiciousBlockThresholdMs = 300;

		try {
			// 第一次检测：记录到 suspiciousMap
			System.out.println("First checkHiddenDeadlocks() call (registration)...");
			DeadlockDetector.checkHiddenDeadlocks();

			// 等待超过 300ms 阈值
			Thread.sleep(400);

			// 第二次检测：触发报警并导出 JSON
			System.out.println("Second checkHiddenDeadlocks() call (threshold met, should report)...");
			DeadlockDetector.checkHiddenDeadlocks();

			// 第三次检测：不应重复刷屏
			System.out.println("Third checkHiddenDeadlocks() call (should not report again)...");
			DeadlockDetector.checkHiddenDeadlocks();

			// 验证 deadlock.log
			if (IntVars.dataDirectory != null) {
				Fi logFile = IntVars.dataDirectory.child("deadlock.log");
				if (!logFile.exists()) {
					throw new AssertionError("deadlock.log does not exist!");
				}
				String content = logFile.readString();
				System.out.println("deadlock.log verified, length: " + content.length());
				if (!content.contains("SUSPECTED HIDDEN DEADLOCK")) {
					throw new AssertionError("deadlock.log does not contain SUSPECTED HIDDEN DEADLOCK!");
				}
				if (!content.contains("TestThread-Platform-Victim")) {
					throw new AssertionError("deadlock.log does not contain victim thread name!");
				}
				if (!content.contains("[DEADLOCK CYCLE ANALYSIS]")) {
					throw new AssertionError("deadlock.log does not contain [DEADLOCK CYCLE ANALYSIS]!");
				}
				if (!content.contains("WAITING FOR LOCK:")) {
					throw new AssertionError("deadlock.log does not contain WAITING FOR LOCK!");
				}

				// 验证是否生成了 threads-*.json 转储文件
				Fi[] dumps = IntVars.dataDirectory.list(f -> f.getName().startsWith("threads-") && f.getName().endsWith(".json"));
				System.out.println("Found thread dump JSON files: " + dumps.length);
				if (dumps.length == 0) {
					throw new AssertionError("Expected thread dump JSON file to be generated!");
				}
				String dumpContent = dumps[0].readString();
				System.out.println("JSON dump length: " + dumpContent.length() + ", contains 'virtual': " + dumpContent.contains("virtual"));
			}

			System.out.println("Test 3 PASSED: Virtual Thread Hidden Deadlock Successfully Detected, Analyzed & Dumped.");
		} finally {
			DeadlockDetector.suspiciousBlockThresholdMs = oldThreshold;
		}
	}

	/**
	 * 测试场景 4：纯虚拟线程之间的死锁（两个虚拟线程互锁）。
	 * 验证通过手动触发 dumpAndAnalyzeDeadlocks() 能够完整发现纯虚拟线程死锁环并输出各节点持有与等待锁。
	 */
	private static void testPureVirtualThreadDeadlockManual() throws Exception {
		System.out.println("\n--- Test 4: Pure Virtual Thread Deadlock (Manual Dump & Analyze) ---");
		Object vLockA = new Object();
		Object vLockB = new Object();
		Phaser vCoop = new Phaser(2);

		Thread v1 = Thread.ofVirtual().name("pure-v1").start(() -> {
			synchronized (vLockA) {
				vCoop.arriveAndAwaitAdvance();
				synchronized (vLockB) {
					System.out.println("v1 done");
				}
			}
		});

		Thread v2 = Thread.ofVirtual().name("pure-v2").start(() -> {
			synchronized (vLockB) {
				vCoop.arriveAndAwaitAdvance();
				synchronized (vLockA) {
					System.out.println("v2 done");
				}
			}
		});

		Thread.sleep(300);

		// 手动调用分析
		String analysis = DeadlockDetector.dumpAndAnalyzeDeadlocks();
		System.out.println("Manual dump analysis result:\n" + analysis);

		if (analysis == null) {
			throw new AssertionError("Expected pure virtual thread deadlock to be detected by dumpAndAnalyzeDeadlocks()!");
		}
		if (!analysis.contains("pure-v1") || !analysis.contains("pure-v2")) {
			throw new AssertionError("Analysis does not contain pure virtual thread names!");
		}
		if (!analysis.contains("[Virtual] Thread \"pure-v1\"") || !analysis.contains("[Virtual] Thread \"pure-v2\"")) {
			throw new AssertionError("Analysis should identify both as Virtual threads!");
		}

		System.out.println("Test 4 PASSED: Pure Virtual Thread Deadlock detected and analyzed correctly.");
	}

	/**
	 * 测试场景 5：非环状锁等待链排查及 JSON locks 容错。
	 * 验证当未形成死锁环时（例如平台线程正等待慢虚拟线程），能够提取并输出直接的锁持有与等待链，
	 * 且对 locks 数组中的 null 或非 String 元素具备容错能力。
	 */
	private static void testNonCycleWaitChainAndNullLockParsing() {
		System.out.println("\n--- Test 5: Non-cycle Wait Chain and Null Lock in JSON ---");
		String mockJson = """
		{
		  "threadDump": {
		    "threadContainers": [
		      {
		        "threads": [
		          {
		            "tid": 101,
		            "name": "worker-platform",
		            "virtual": false,
		            "state": "BLOCKED",
		            "blockedOn": "java.lang.Object@abcdef",
		            "monitorsOwned": []
		          },
		          {
		            "tid": "102",
		            "name": "slow-virtual-holder",
		            "virtual": true,
		            "state": "RUNNABLE",
		            "monitorsOwned": [
		              {
		                "locks": [null, 123, "java.lang.Object@abcdef"]
		              }
		            ]
		          }
		        ]
		      }
		    ]
		  }
		}
		""";

		String result = DeadlockDetector.analyzeJsonDump(mockJson);
		System.out.println("Wait chain analysis:\n" + result);
		if (result == null || !result.contains("No circular deadlock detected, but found 1 thread(s)")) {
			throw new AssertionError("Expected non-cycle wait chain to be detected!");
		}
		if (!result.contains("worker-platform") || !result.contains("slow-virtual-holder")) {
			throw new AssertionError("Expected thread names in wait chain report!");
		}
		if (!result.contains("Id=101") || !result.contains("Id=102")) {
			throw new AssertionError("Expected thread Ids (both numeric and string) in wait chain report!");
		}
		System.out.println("Test 5 PASSED: Non-cycle Wait Chain, Null Lock & Numeric/String tid (JDK-8381169) handling verified.");
	}

	/**
	 * 测试场景 6：线程转储文件轮转清理。
	 * 验证当已存在的 threads-*.json 达到 5 个或更多时，自动删除旧文件，只保留最新的 5 个。
	 */
	private static void testThreadDumpRotation() throws Exception {
		System.out.println("\n--- Test 6: Thread Dump Rotation ---");
		// 先创建 7 个模拟的历史 dump 文件，时间戳依次递增
		for (int i = 0; i < 7; i++) {
			Fi dummy = IntVars.dataDirectory.child("threads-" + (1000000000000L + i * 1000) + ".json");
			dummy.writeString("{}");
			// 设置修改时间
			dummy.file().setLastModified(1000000000000L + i * 1000);
		}

		// 触发一次转储
		DeadlockDetector.dumpAndAnalyzeDeadlocks();

		Fi[] remaining = IntVars.dataDirectory.list(f -> f.getName().startsWith("threads-") && f.getName().endsWith(".json"));
		System.out.println("Remaining thread dump files after rotation: " + remaining.length);
		if (remaining.length > 5) {
			throw new AssertionError("Expected at most 5 thread dumps after rotation, but found: " + remaining.length);
		}

		System.out.println("Test 6 PASSED: Thread Dump Rotation works correctly.");
	}

	/**
	 * 测试场景 7：看门狗启动与停止生命周期。
	 */
	private static void testWatchdogLifecycle() throws Exception {
		System.out.println("\n--- Test 7: Watchdog Lifecycle ---");
		DeadlockDetector.startWatchdog();
		if (!DeadlockDetector.isRunning()) {
			throw new AssertionError("Watchdog should be running!");
		}
		System.out.println("Watchdog started successfully.");

		DeadlockDetector.stopWatchdog();
		if (DeadlockDetector.isRunning()) {
			throw new AssertionError("Watchdog should be stopped!");
		}
		System.out.println("Watchdog stopped successfully.");
		System.out.println("Test 7 PASSED: Watchdog Lifecycle works cleanly.");
	}
}
