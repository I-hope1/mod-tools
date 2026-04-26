package modtools.utils.profiler;

import arc.util.*;
import nipx.jni.JNIEnv;
import nipx.jni.helper.MasterKey;
import nipx.jvmti.*;
import nipx.profiler.*;
import nipx.profiler.ProfilerData.FlameNode;

import java.lang.foreign.Arena;
import java.util.*;

import static nipx.HotSwapAgent.*;

/**
 * 采样式 profiler：独立线程定期抓取目标线程的调用栈，
 * 结果直接写入 {@link ProfilerData#flameRoot}，与插桩模式共用同一棵树和同一个 FlameGraphWindow。
 *
 * <h3>与插桩模式的关系</h3>
 * <ul>
 *   <li>两种模式可同时开启，数据叠加写入同一棵火焰图树。</li>
 *   <li>采样模式不需要 ASM 注入，{@link ProfilerData#stats}（flat 统计）不会被采样模式更新。</li>
 *   <li>GC 完全发生在 profiler 线程，游戏线程唯一的开销是 safepoint 停顿（~10–50 µs/次）。</li>
 * </ul>
 *
 * <h3>safepoint 说明</h3>
 * {@code Thread.getStackTrace()} 需要目标线程进入 safepoint，
 * JVM 通常在方法调用、循环回边等安全点自动处理，停顿极短，对帧率几乎无影响。
 * 若需彻底消除停顿，需 JVMTI AsyncGetCallTrace（需要 native 代码，此处不做）。
 */
public class SamplingProfiler {
	// ── 配置 ─────────────────────────────────────────────────────────────────

	/** 默认采样间隔（毫秒）。200 Hz ≈ 5 ms/sample，对 60 FPS 游戏足够。 */
	public static volatile int intervalMs = 5;

	/**
	 * 过滤前缀：只保留包含这些包名的帧，其余视为"框架噪音"跳过。
	 * 留空表示不过滤（会包含 JDK/Arc 内部帧）。
	 * 例：{"mindustry", "modtools", "nipx"}
	 */
	public static volatile String[] includePackages = {};

	public static boolean captureMethodSignature = true;

	// ── 状态 ─────────────────────────────────────────────────────────────────

	private static volatile Thread  samplerThread = null;
	private static volatile Thread  targetThread  = null;
	private static volatile boolean running       = false;

	// ── 公共 API ──────────────────────────────────────────────────────────────

	/** 启动采样，目标为指定线程。重复调用会先停止旧的采样再重启。 */
	public static synchronized void start(Thread target) {
		stop();
		targetThread = target;
		running = true;
		samplerThread = new Thread(SamplingProfiler::loop, "flame-sampler");
		samplerThread.setDaemon(true);
		samplerThread.setPriority(Thread.MIN_PRIORITY); // 不抢游戏线程的 CPU
		samplerThread.start();
		info("[SamplingProfiler] Started, target=" + target.getName()
		     + ", interval=" + intervalMs + " ms");
	}

	/** 以当前线程为目标启动采样（在游戏主线程调用此方法，然后切回主循环）。 */
	public static void startCurrentThread() {
		start(Thread.currentThread());
	}

	public static synchronized void stop() {
		running = false;
		if (samplerThread != null) {
			samplerThread.interrupt();
			samplerThread = null;
		}
		targetThread = null;
		info("[SamplingProfiler] Stopped");
	}

	public static boolean isRunning() { return running; }

	public static Thread getTargetThread() { return targetThread; }

	// ── 采样循环 ──────────────────────────────────────────────────────────────

	private static void loop() {
		if (!jniValid()) {
			while (running) {
				Threads.sleep(intervalMs);

				Thread target = targetThread;
				if (target == null || !target.isAlive()) continue;

				planA(target);
			}
			return;
		}
		try (Arena arena = Arena.ofConfined()) {
			JNIEnv jniEnv = new JNIEnv(arena);
			while (running) {
				Threads.sleep(intervalMs);

				Thread target = targetThread;
				if (target == null || !target.isAlive()) continue;

				if (captureMethodSignature) {
					planC(jniEnv, target);
				} else {
					planA(target);
				}
				// planC(jniEnv, target);
				// planB(jniEnv, target);
				// planA(target);
			}
		}
	}
	private static boolean jniValid() {
		try {
			return MasterKey.isPanamaBackend();
		} catch (NoClassDefFoundError e) {
			return false;
		}
	}
	private static final StringBuilder keyBuf = new StringBuilder();
	private static void planC(JNIEnv jniEnv, Thread target) {
		FlameNode[] curHolder = {ProfilerData.flameRoot};
		String[]    pkgs      = includePackages;
		try {
			StackCapture.captureInto(jniEnv, target, (className, methodName, methodSig, thisAddr) -> {
				if (isBlacklist(className)) return;
				if (pkgs != null && pkgs.length > 0 && !matchesAny(className, pkgs)) return;
				// Log.info(className + "." + methodName + " " + methodSig);

				// 复用 StringBuilder 拼 key
				keyBuf.setLength(0);
				keyBuf.append(simpleClass(className)).append('.').append(methodName)
				 .append(methodSig);
				// if (!className.startsWith("Larc/scene/") && thisAddr != 0L) keyBuf.append(": ").append(Long.toHexString(thisAddr));
				String key = keyBuf.toString(); // 这里仍有一次 String 分配，但比之前少很多

				curHolder[0] = curHolder[0].children
				 .computeIfAbsent(key, FlameNode::new);
				curHolder[0].totalNanos.add(intervalMs * 1_000_000L);
			});
		} catch (Throwable e) {
			// Log.err(e);
		}
	}
	private static void planB(JNIEnv jniEnv, Thread target) {
		try {
			var      stack = StackCapture.capture(jniEnv, target);
			String[] pkgs  = includePackages;

			ProfilerData.FlameNode cur = ProfilerData.flameRoot;

			// 从栈底（老帧）到栈顶（新帧）遍历
			for (int i = stack.size() - 1; i >= 0; i--) {
				FrameLocals frame     = stack.get(i);
				String      className = frame.className();
				if (isBlacklist(className)) continue;

				// 包名过滤：pkgs 为空则全部接受
				if (pkgs != null && pkgs.length > 0 && !matchesAny(className, pkgs)) continue;

				// key 格式与插桩模式相同："ClassName.methodName"
				var thisLocal = frame.locals().stream().filter(l -> "this".equals(l.name())).findFirst();
				String key = simpleClass(className) + "." + frame.methodName()
				             + frame.methodSignature()
				             + (thisLocal.map(localVariable -> ": " + Long.toHexString(localVariable.getAddress())).orElse(""));
				// Log.info(frame.locals());

				// computeIfAbsent：key 存在时无 GC；首次出现才 new FlameNode
				cur = cur.children.computeIfAbsent(key, ProfilerData.FlameNode::new);

				// 用 intervalMs（转纳秒）作为权重，使采样和插桩的单位统一
				cur.totalNanos.add(intervalMs * 1_000_000L);
			}
		} catch (Throwable e) {
			Log.err(e);
		}
	}
	private static void planA(Thread target) {
		// ① 抓栈——此处触发目标线程进入 safepoint（~10–50 µs）
		//    分配在 profiler 线程，不影响游戏线程 GC
		StackTraceElement[] stack = target.getStackTrace();
		if (stack.length == 0) return;
		// // ② 过滤 + 写入火焰图树
		sample(stack);
	}

	/**
	 * 将一次采样写入 {@link ProfilerData#flameRoot}。
	 *
	 * <p>调用栈 index=0 是最新帧（栈顶），index=last 是最老帧（栈底）。
	 * 火焰图惯例：根（栈底）在上，叶（栈顶）在下，所以从 stack[last] 向 stack[0] 遍历。
	 *
	 * <p>每次采样计 1 纳秒（实际意义是"1 个采样命中"），调用方可根据 intervalMs
	 * 将采样次数换算为估算耗时：估算耗时 = hits × intervalMs。
	 */
	static void sample(StackTraceElement[] stack) {
		String[] pkgs = includePackages;

		FlameNode cur = ProfilerData.flameRoot;

		// 从栈底（老帧）到栈顶（新帧）遍历
		for (int i = stack.length - 1; i >= 0; i--) {
			StackTraceElement frame     = stack[i];
			String            className = frame.getClassName();
			if (isBlacklist(className)) continue;

			// 包名过滤：pkgs 为空则全部接受
			if (pkgs != null && pkgs.length > 0 && !matchesAny(className, pkgs)) continue;

			// key 格式与插桩模式相同："ClassName.methodName"
			String key = simpleClass(className) + "." + frame.getMethodName();

			// computeIfAbsent：key 存在时无 GC；首次出现才 new FlameNode
			cur = cur.children.computeIfAbsent(key, FlameNode::new);

			// 用 intervalMs（转纳秒）作为权重，使采样和插桩的单位统一
			cur.totalNanos.add(intervalMs * 1_000_000L);
		}
	}

	private static boolean isBlacklist(String className) {
		return className.startsWith("nipx.") || className.startsWith("modtools.ui.windows.profile.");
	}

	// ── 辅助 ─────────────────────────────────────────────────────────────────

	private static boolean matchesAny(String className, String[] pkgs) {
		for (String pkg : pkgs) {
			if (className.startsWith(pkg)) return true;
		}
		return false;
	}

	/** "com.example.Foo$Bar" → "Foo$Bar"（保留内部类标记）。 */
	private static String simpleClass(String className) {
		int dot = className.lastIndexOf('.');
		return dot < 0 ? className : className.substring(dot + 1);
	}

	public static void toggleSampling(boolean enable) {
		if (enable) {
			// 以游戏主线程为目标：通常是 "main" 或 "render"，这里找名称含 "main" 的线程
			Thread target = findGameThread();
			if (target == null) {
				error("Cannot find game main thread — start sampling manually via SamplingProfiler.start(thread)");
				return;
			}
			ProfilerData.clear(); // 清空旧数据，避免插桩数据和采样数据混在一起造成混淆
			SamplingProfiler.start(target);
		} else {
			SamplingProfiler.stop();
		}
	}

	/** 启发式查找游戏主线程（优先 "main"，其次 "render"，其次第一个非 daemon）。 */
	static Thread findGameThread() {
		Map<Thread, StackTraceElement[]> all      = Thread.getAllStackTraces();
		Thread                           fallback = null;
		for (Thread t : all.keySet()) {
			String name = t.getName().toLowerCase(Locale.ROOT);
			if (name.equals("main") || name.equals("lwjgl-main") || name.contains("render")) return t;
			if (!t.isDaemon() && fallback == null) fallback = t;
		}
		return fallback;
	}
}