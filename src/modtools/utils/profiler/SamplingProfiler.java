package modtools.utils.profiler;

import nipx.profiler.*;

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
	public static volatile String[] includePackages = {"mindustry", "arc"};

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
		while (running) {
			try {
				Thread.sleep(intervalMs);
			} catch (InterruptedException e) {
				break;
			}

			Thread target = targetThread;
			if (target == null || !target.isAlive()) continue;

			// ① 抓栈——此处触发目标线程进入 safepoint（~10–50 µs）
			//    分配在 profiler 线程，不影响游戏线程 GC
			StackTraceElement[] stack = target.getStackTrace();
			if (stack.length == 0) continue;

			// ② 过滤 + 写入火焰图树
			sample(stack);
		}
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

		ProfilerData.FlameNode cur = ProfilerData.flameRoot;

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
			cur = cur.children.computeIfAbsent(key, ProfilerData.FlameNode::new);

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


	/* static class LiveThread {
		 *//**
		 * 通过反射获取 {@code LiveStackFrame.getStackWalker()} 返回的 StackWalker。
		 * 需要 {@code --add-opens java.base/java.lang=ALL-UNNAMED}。
		 * 初始化失败时置 null，调用方检查后回退到模式 A。
		 *//*
		private static final java.lang.StackWalker LIVE_WALKER = initLiveWalker();
		private static java.lang.StackWalker initLiveWalker() {
			try {
				Class<?> liveFrameClass = Class.forName("java.lang.LiveStackFrame");
				Method getWalker = liveFrameClass.getMethod("getStackWalker",
				 Set.class);
				getWalker.setAccessible(true);
				// RETAIN_CLASS_REFERENCE 让我们可以访问帧的 Class 对象
				var walker = (java.lang.StackWalker) getWalker.invoke(null,
				 EnumSet.of(java.lang.StackWalker.Option.RETAIN_CLASS_REFERENCE));
				info("[SamplingProfiler] LiveStackFrame available — in-thread sampling enabled");
				return walker;
			} catch (Throwable t) {
				info("[SamplingProfiler] LiveStackFrame unavailable (" + t.getMessage()
				     + ") — cross-thread sampling only");
				return null;
			}
		}
		 *//** LiveStackFrame.getLocals() 方法的反射句柄，初始化时缓存。 *//*
		private static final Method GET_LOCALS = initGetLocals();

		private static Method initGetLocals() {
			try {
				Class<?> c = Class.forName("java.lang.LiveStackFrame");
				Method   m = c.getMethod("getLocals");
				m.setAccessible(true);
				return m;
			} catch (Throwable t) { return null; }
		}
	}
	 *//**
	 * 在游戏线程上直接调用——通过 LiveStackFrame 遍历当前调用栈，
	 * 从每帧的 {@code getLocals()[0]} 读取 {@code this}，
	 * 用 {@link EntityKeyExtractor} 提取实体 key。
	 *
	 * <p>典型调用点：插桩注入到游戏主循环的 update 调度方法，
	 * 例如 {@code Groups.update()} 或 {@code EntityGroup.update()} 的入口处。
	 *
	 * <p>如果 LiveStackFrame 不可用，静默退化为无操作（不影响插桩模式）。
	 *//*
	public static void sampleCurrentThread() {
		if (LiveThread.LIVE_WALKER == null || LiveThread.GET_LOCALS == null) return;
		long     weight = intervalMs * 1_000_000L;
		String[] pkgs   = includePackages;

		// 用一个可变引用在 lambda 里传递当前节点
		ProfilerData.FlameNode[] curHolder = {ProfilerData.flameRoot};

		LiveThread.LIVE_WALKER.forEach(frame -> {
			String className = frame.getClassName();
			if (pkgs != null && pkgs.length > 0 && !matchesAny(className, pkgs)) return;

			// 尝试从 locals[0] 读 this
			String entityPrefix = "";
			try {
				Object[] locals = (Object[]) LiveThread.GET_LOCALS.invoke(frame);
				if (locals != null && locals.length > 0 && locals[0] != null
				    && !(locals[0] instanceof java.lang.StackWalker.StackFrame)) {
					// locals[0] 是 this（实例方法）或 primitive slot（静态方法，此时跳过）
					entityPrefix = EntityKeyExtractor.key(locals[0]) + ".";
				}
			} catch (Throwable ignored) {  *//* primitive slot 等情况直接跳过 *//*  }

			String key = entityPrefix + simpleClass(className) + "." + frame.getMethodName();
			ProfilerData.FlameNode node = curHolder[0].children
			 .computeIfAbsent(key, ProfilerData.FlameNode::new);
			node.totalNanos.add(weight);
			curHolder[0] = node; // 往叶方向移动
		});
	} */

}