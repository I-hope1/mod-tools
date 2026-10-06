package nipx;

import arc.Core;
import nipx.annotation.*;
import nipx.profiler.DynamicProfilerAPI;
import nipx.ref.InitFix;
import nipx.uihook.CellPropertyRef;
import nipx.util.*;

import java.io.*;
import java.lang.instrument.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.net.URL;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import java.util.zip.*;

import static nipx.MountManager.*;

/**
 * HotSwap Agent
 * 其由AppLoader加载，但不属于java.base模块
 */
public class HotSwapAgent {
	//region Configuration Fields
	public static boolean      DEBUG              = Boolean.parseBoolean(System.getenv("nipx.agent.debug"));
	public static boolean      UCP_APPEND         = Boolean.parseBoolean(System.getProperty("nipx.agent.ucp_append", "true"));
	public static int          FILE_SHAKE_MS      = 1200;
	public static RedefineMode REDEFINE_MODE;
	public static String[]     HOTSWAP_BLACKLIST  = new String[0];
	public static boolean      RETRANSFORM_LOADED = Boolean.parseBoolean(System.getProperty("nipx.agent.retransform_loaded", "false"));
	public static boolean      ENABLE_HOTSWAP_EVENT;
	public static boolean      FORCE_REINIT;
	public static boolean      LAMBDA_ALIGN;
	public static boolean      HOTSWAP_PLUS;
	public static boolean      UI_HOOK;
	/**
	 * 匿名类对齐总开关（docs/ANONYMOUS_CLASS_TOPOLOGY_PLAN.md §6.4）。默认开启。
	 *
	 * <p><b>关闭语义</b>：不是"按类名照旧重定义"（那正是编号位移篡夺场景本身），而是把含有匿名类的
	 * 宿主组整体移出本批重定义（见 {@link #rejectHostGroup}）。</p>
	 */
	public static boolean      ANON_ALIGN        = boolProp("nipx.agent.anon_align", "nipx.anonAlign.enabled", true);
	/** 严格模式（§6.4）：Tier 4 歧义 / 嵌套深度超限时按 §4.3 拒绝整个宿主组。 */
	public static boolean      ANON_STRICT       = boolProp("nipx.agent.anon_strict", "nipx.anonAlign.strict", false);
	/** 匿名类对齐诊断日志（§6.4）：打印层级决策链。 */
	public static boolean      ANON_DEBUG        = boolProp("nipx.agent.anon_debug", "nipx.anonAlign.debug", false);

	/**
	 * 实例状态布局门模式（§7.2 的精确变体）：{@code reject} / {@code warn} / {@code off}。
	 *
	 * <p>门守的是"字段布局变化后，<b>已存在的实例</b>读新字段得零值"。三种模式：</p>
	 * <ul>
	 *   <li>{@code reject}（默认）—— 布局不兼容<b>且有存活实例</b>时拒绝配对；
	 *       新类分配未占用编号，旧类成为孤儿并保留，存活实例继续跑旧逻辑（§1.2）。</li>
	 *   <li>{@code warn} —— 照旧在 Tier 4 配对并原地重定义，只打强告警。
	 *       给"我就想原地更新、界面马上会重建"的场景用。计数器照记，
	 *       这样用户能看到"本来会被拒绝的有几次"。</li>
	 *   <li>{@code off} —— 完全恢复旧行为：不扫实例、不打日志。</li>
	 * </ul>
	 *
	 * <p>用字符串而非 boolean，是因为将来可能加更多档（如按类注解 {@code @HotswapReinit}），
	 * 那时再加一个值即可，不用再破坏一次属性语义。</p>
	 */
	public static String       ANON_LAYOUT_GATE  = strProp("nipx.agent.anon_layout_gate", "reject");

	/** 读字符串属性并做合法性校验；非法值回退到默认值并告警（不静默接受拼错的开关）。 */
	private static String strProp(String key, String def) {
		String v = System.getProperty(key);
		if (v == null) return def;
		v = v.trim().toLowerCase();
		if (v.equals(LayoutGate.MODE_REJECT) || v.equals(LayoutGate.MODE_WARN) || v.equals(LayoutGate.MODE_OFF)) {
			return v;
		}
		// 复用 error()：拼错的开关值被静默忽略，比"多认一个别名"危险得多
		System.err.println("[NIPX] Unknown " + key + " value '" + v + "'; falling back to '" + def + "'");
		return def;
	}
	//endregion

	//region Core State Management
	static               Instrumentation     inst;
	static final         Set<Path>           activeWatchDirs = new CopyOnWriteArraySet<>();
	/** 直接作为 watch 路径传入的 jar/zip 文件（区别于目录内扫描到的） */
	static final         Set<Path>           activeWatchJars = new CopyOnWriteArraySet<>();
	private static final List<WatcherThread> activeWatchers  = new CopyOnWriteArrayList<>();

	/**
	 * 在不使用retransform的情况下，确保旧bytecode正确的唯一方法
	 * dotClassName -> bytecode
	 */
	public static final Map<String, byte[]> bytecodeCache = new ConcurrentHashMap<>();

	// 仅记录byte的指纹
	private static final LongLongMap fileDiskHashes = new LongLongMap(2048);

	private static final Set<Path>                pendingChanges = Collections.synchronizedSet(new HashSet<>());
	/** 待解压的 jar/zip，防抖后统一解压；所有访问均在 synchronized(pendingChanges) 内，无需额外同步 */
	private static final Set<Path>                pendingJars    = new HashSet<>();
	/** 临时解压出的 .class 路径 → 来源 jar 路径，用于 ClassLoader 推断 */
	private static final Map<Path, Path>          tmpToJar       = new ConcurrentHashMap<>();
	private static final ScheduledExecutorService scheduler      = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "HotSwap-Scheduler");
		t.setDaemon(true);
		return t;
	});
	private static       ScheduledFuture<?>       scheduledTask;

	/**
	 * 全局热更独占锁：保证 {@code processChanges} 全流程串行。
	 *
	 * <p><b>为什么需要它</b>：{@code scheduler} 是单线程的，因此"监听线程 → 防抖 → 热更"这条
	 * 路径本来就串行。但存在若干<b>绕过 scheduler 的入口</b>，它们直接在调用方线程上执行热更：</p>
	 * <ul>
	 *   <li>公开的 {@link #triggerHotswap()} —— 例如 {@code HotSwapDialog} 的 refresh 按钮，
	 *       它用 {@code Threads.daemon(...)} 起了<b>第二个线程源</b>，与 scheduler 上正在跑的那一轮重叠；</li>
	 *   <li>{@link #init} 的 else 分支（监控路径未变时直接同步调用）；</li>
	 *   <li>{@link #init} 整体（含 {@code initializeAgentState} / {@code restartWatchers}）—— 旧 watcher
	 *       排进 scheduler 的任务可能尚未跑完；</li>
	 *   <li>公开的 {@link #retransformLoaded()} —— 同样对应一个 UI 按钮。</li>
	 * </ul>
	 *
	 * <p><b>锁序纪律（必须遵守，否则死锁）</b>：</p>
	 * <ol>
	 *   <li><b>{@code AnnotationTransformer.transform} 及类加载路径上永远不得获取这把锁。</b>
	 *       持锁线程在 {@code redefineClasses} / {@code retransformClasses} 期间会触发类加载与 transform，
	 *       若 transform 反向抢锁即形成死锁。</li>
	 *   <li><b>不得在持有 {@code synchronized(pendingChanges)} 时获取这把锁。</b>
	 *       固定顺序为：先取 {@code HOTSWAP_LOCK}，再进 {@code synchronized(pendingChanges)}。
	 *       反向（持 pendingChanges 锁再抢全局锁）会与其他线程形成环路。</li>
	 * </ol>
	 *
	 * <p>可重入：{@link #init} 持锁后会再次经 {@link #triggerHotswap()} 进入，这是预期的。</p>
	 */
	private static final ReentrantLock HOTSWAP_LOCK = new ReentrantLock();

	/**
	 * 并发探针（测试用）：当前处于热更流程内的线程数，以及历史峰值。
	 * 用于"先红后绿"回归 —— 去掉 {@code HOTSWAP_LOCK} 时峰值应为 2，加上后恒为 1。
	 */
	static final AtomicInteger CONCURRENT_HOTSWAP      = new AtomicInteger();
	static final AtomicInteger CONCURRENT_HOTSWAP_PEAK = new AtomicInteger();

	/** 测试用：复位并发探针。 */
	public static void resetConcurrencyProbe() {
		CONCURRENT_HOTSWAP.set(0);
		CONCURRENT_HOTSWAP_PEAK.set(0);
	}

	/** 测试用：读取并发探针峰值。 */
	public static int peakConcurrentHotswaps() {
		return CONCURRENT_HOTSWAP_PEAK.get();
	}
	//endregion

	//region Agent Initialization
	static AnnotationTransformer transformer;

	public static void premain(String agentArgs, Instrumentation inst) {
		agentmain(agentArgs, inst);
	}

	public static void agentmain(String agentArgs, Instrumentation inst) {
		HotSwapAgent.inst = inst;
		try {
			if (agentArgs != null && !agentArgs.trim().isEmpty()) {
				init(agentArgs, false);
			} else {
				initConfig();
				if (transformer == null) {
					transformer = new AnnotationTransformer();
					inst.addTransformer(transformer, true);
					try {
						DynamicProfilerAPI.init();
					} catch (Throwable ignored) {
					}
				}
			}
		} catch (Throwable t) {
			error("Critical error during agent initialization", t);
		}
	}

	public static Instrumentation getInstrumentation() {
		return inst;
	}

	public static void init(String agentArgs, boolean reinit) {
		// 整体持锁：本方法包含 retransformLoaded / initializeAgentState / restartWatchers，
		// 而旧 watcher 排进 scheduler 的任务可能尚未跑完。可重入，末尾 triggerHotswap() 嵌套获取无碍。
		HOTSWAP_LOCK.lock();
		try {
			init0(agentArgs, reinit);
		} finally {
			HOTSWAP_LOCK.unlock();
		}
	}

	private static void init0(String agentArgs, boolean reinit) {
		initConfig();

		if (transformer == null) {
			transformer = new AnnotationTransformer();
			inst.addTransformer(transformer, true);
			// if (UI_HOOK) inst.addTransformer(new UIHookTransformer(), true);
			DynamicProfilerAPI.init();
		}
		var loadedClasses = inst.getAllLoadedClasses();
		refreshPackageLoaders(loadedClasses);

		if (RETRANSFORM_LOADED) {
			retransformLoaded(loadedClasses);
		}
		if (UI_HOOK) {
			LambdaRef.init();
			// CellPropertyRef.enable();
		}

		// 解析传入的监控路径 (支持分号或冒号分割)，按类型分流
		Set<Path> newWatchDirs = new HashSet<>();
		Set<Path> newWatchJars = new HashSet<>();
		for (String s : agentArgs.split(File.pathSeparator)) {
			if (s.trim().isEmpty()) continue;
			Path   p  = Paths.get(s.trim()).toAbsolutePath();
			String ps = p.toString();
			if (Files.isDirectory(p)) {
				newWatchDirs.add(p);
			} else if ((ps.endsWith(".jar") || ps.endsWith(".zip")) && Files.isRegularFile(p)) {
				newWatchJars.add(p);
			} else {
				error("Skipping invalid watch path (not a directory or jar/zip): " + p);
			}
		}

		if (newWatchDirs.isEmpty() && newWatchJars.isEmpty()) {
			error("No valid watch paths provided.");
			return;
		}

		// if (RETRANSFORM_LOADED) retransformLoaded(loadedClasses);
		// 重启监控线程
		if (!activeWatchDirs.equals(newWatchDirs) || !activeWatchJars.equals(newWatchJars)) {
			info("Watch paths updated — dirs: " + newWatchDirs + "  jars: " + newWatchJars);
			activeWatchDirs.clear();
			activeWatchDirs.addAll(newWatchDirs);
			activeWatchJars.clear();
			activeWatchJars.addAll(newWatchJars);
			initializeAgentState(loadedClasses);
			restartWatchers();
		} else {
			triggerHotswap();
		}
	}

	/**
	 * 读取布尔开关：优先 {@code primary}，缺省时回退到 {@code alias}。
	 *
	 * <p>{@code alias} 存在的唯一理由是兼容 {@code docs/ANONYMOUS_CLASS_TOPOLOGY_PLAN.md} §6.4 里
	 * 已经对外公布的 {@code nipx.anonAlign.*} 拼写 —— 对一个"止血开关"而言，
	 * "按文档写下的名字被静默忽略"远比"同一个开关多认一个名字"危险。</p>
	 *
	 * <p><b>注意</b>：本方法会在类初始化期（字段赋值时）被调用，因此**不得**打日志 ——
	 * {@code logger} 字段声明在类体后部，此时仍为 {@code null}。</p>
	 */
	private static boolean boolProp(String primary, String alias, boolean def) {
		String v = System.getProperty(primary);
		if (v == null && alias != null) v = System.getProperty(alias);
		return v == null ? def : Boolean.parseBoolean(v.trim());
	}

	public static void initConfig() {
		info("DEBUG: " + DEBUG);
		REDEFINE_MODE = RedefineMode.valueOfFail(System.getProperty("nipx.agent.redefine_mode", "inject"), RedefineMode.inject);
		info("Redefine Mode: " + REDEFINE_MODE);
		String bl = System.getProperty("nipx.agent.hotswap_blacklist", "").trim();
		HOTSWAP_BLACKLIST = bl.isEmpty() ? new String[0] : bl.split(",");
		info("Injection Blacklist: " + String.join(",", HOTSWAP_BLACKLIST));
		HOTSWAP_PLUS = Boolean.parseBoolean(System.getProperty("nipx.agent.hotswap_plus", "false"));
		info("HotSwap Plus: " + HOTSWAP_PLUS);
		ENABLE_HOTSWAP_EVENT = Boolean.parseBoolean(System.getProperty("nipx.agent.hotswap_event", "false"));
		info("HotSwap Event: " + ENABLE_HOTSWAP_EVENT);
		FORCE_REINIT = Boolean.parseBoolean(System.getProperty("nipx.agent.force_reinit", "true"));
		info("Force Reinit: " + FORCE_REINIT);
		LAMBDA_ALIGN = Boolean.parseBoolean(System.getProperty("nipx.agent.lambda_align", "true"));
		info("Lambda Align: " + LAMBDA_ALIGN);
		if (LAMBDA_ALIGN) {
			info("Lambda Alignment ENABLED. Warning: This may cause logical shifts if lambdas are reordered.");
		}
		UI_HOOK = Boolean.parseBoolean(System.getProperty("nipx.agent.ui_hook", "false"));
		info("UI Hook: " + UI_HOOK);
		boolean cellPropertyRef = Boolean.parseBoolean(System.getProperty("nipx.agent.cell_property_ref", "false"));
		info("Cell Property Ref: " + cellPropertyRef);
		CellPropertyRef.setEnabled(cellPropertyRef);
		info("Anon Align: " + ANON_ALIGN + " (strict=" + ANON_STRICT + ", debug=" + ANON_DEBUG
		     + ", maxPerHost=" + AnonClassAligner.MAX_ANON_PER_HOST
		     + ", timeoutMs=" + AnonClassAligner.ALIGN_TIMEOUT_MS
		     + ", layoutGate=" + ANON_LAYOUT_GATE + ")");
		if (ANON_ALIGN) {
			info("Anonymous Class Alignment ENABLED. Ambiguity is resolved conservatively (never by class name).");
		} else {
			info("Anonymous Class Alignment DISABLED. Host classes containing anonymous classes will be rejected as a whole.");
		}
		info("Structural HotSwap Supported: " + isEnhancedHotswapEnabled());
	}
	//endregion

	//region Class Retransformation
	/** 对外api，刷新已加载的类（UI 按钮 B 会直接调用，必须与热更互斥） */
	public static void retransformLoaded() {
		HOTSWAP_LOCK.lock();
		try {
			retransformLoaded(inst.getAllLoadedClasses());
		} finally {
			HOTSWAP_LOCK.unlock();
		}
	}

	private static void retransformLoaded(Class<?>[] classes) {
		info("Force retransform all loaded classes...");
		List<Class<?>> candidates = new ArrayList<>();
		for (Class<?> loadedClass : classes) {
			if (!inst.isModifiableClass(loadedClass)) continue;
			if (isBlacklisted(loadedClass.getName())) continue;
			if (bytecodeCache.containsKey(loadedClass.getName())) continue;

			candidates.add(loadedClass);
		}
		if (candidates.isEmpty()) return;
		info("Found " + candidates.size() + " classes loaded before Agent start. Retransforming...");
		try {
			inst.retransformClasses(candidates.toArray(new Class[0]));
			info("Retransform complete.");
		} catch (UnmodifiableClassException e) {
			error("Failed to retransform some classes", e);
		} catch (Throwable t) {
			error("Critical error during retransform", t);
		}
	}
	//endregion

	//region HotSwap Core Logic
	public static volatile ConcurrentHashMap<String, Class<?>> loadedClassesMap = new ConcurrentHashMap<>();


	private static void loadClassSnap(Class<?>[] classes) {
		var newMap = new ConcurrentHashMap<String, Class<?>>((int) (classes.length / 0.75f) + 1);
		for (Class<?> c : classes) {
			String   name     = c.getName();
			Class<?> existing = newMap.get(name);
			if (existing == null || isChildClassLoader(c.getClassLoader(), existing.getClassLoader())) {
				newMap.put(name, c);
			}
		}
		loadedClassesMap = newMap;              // 原子切换，读者要么看到完整旧 map，要么完整新 map
	}

	/**
	 * 处理文件变化的核心逻辑
	 */
	private static void processChanges(Set<Path> changedFiles, Class<?>[] classes) {
		refreshPackageLoaders(classes);

		// 获取当前所有已加载类的快照
		loadClassSnap(classes);

		List<ClassDefinition> definitions = new ArrayList<>();
		// List<Class<?>>        unloadedClasses  = new ArrayList<>();
		int skippedCount  = 0;
		int injectedCount = 0;

		Map<String, byte[]> newBatchBytes = new LinkedHashMap<>(changedFiles.size());
		Map<String, Path>   classToPath   = new HashMap<>(changedFiles.size());
		for (Path p : changedFiles) {
			try {
				byte[] bc = Files.readAllBytes(p);
				String cn = Utils.getClassNameASM(bc);
				if (cn != null) {
					newBatchBytes.put(cn, bc);
					classToPath.put(cn, p);
				} else {
					skippedCount++;
					error("[SKIP] No className: " + p);
				}
			} catch (Throwable ignored) { }
		}

		// 收集需要对齐匿名类的所有宿主类
		Set<String> hostClassesToAlign = new LinkedHashSet<>();
		for (String cn : newBatchBytes.keySet()) {
			String host = cn.replaceAll("\\$\\d+(\\$\\d+)*$", "");
			hostClassesToAlign.add(host);
		}

		// 记录所有对齐过程中产生的旧孤儿类（不得在本次重定义中被误更新）
		Set<String> allOrphanClasses = new HashSet<>();
		Map<String, AlignmentTransaction> transactions = new LinkedHashMap<>();

		// 【指纹延后落账】类名 hash → 新磁盘指纹。循环里只登记，不写 fileDiskHashes；
		// 待 applyRedefinitions 完成后，仅对**确实成功**的类落账（见本方法末尾）。
		// 这样失败/被拒的类下次触发时不会被误判为 "file hash unchanged" 而跳过。
		LongLongMap pendingHashes = new LongLongMap(256);
		// 【失败回队】类名 → 源文件路径，用于重定义失败后把文件放回 pendingChanges。
		// 注意：hierarchyChanged 的拒绝**不**回队 —— 那种变更 JVM 层面不支持，重试无意义，
		// 必须重启；回队只会造成"每次触发都失败一次"的噪音。
		Map<String, Path> retryCandidates = new HashMap<>();

		for (String hostName : hostClassesToAlign) {
			String hostSlash = hostName.replace('.', '/');

			// 预热并收集已加载但尚未缓存的旧匿名类字节码
			for (Map.Entry<String, Class<?>> entry : loadedClassesMap.entrySet()) {
				String cName = entry.getKey();
				if (AnonClassAligner.isAnonymousClassName(hostSlash, cName)) {
					if (!bytecodeCache.containsKey(cName)) {
						byte[] bc = fetchOriginalBytecode(entry.getValue());
						if (bc != null) bytecodeCache.put(cName, bc);
					}
				}
			}

			Map<String, byte[]> oldAnon = new HashMap<>();
			for (Map.Entry<String, byte[]> entry : bytecodeCache.entrySet()) {
				if (AnonClassAligner.isAnonymousClassName(hostSlash, entry.getKey())) {
					oldAnon.put(entry.getKey(), entry.getValue());
				}
			}

			Map<String, byte[]> newAnon = new HashMap<>();
			for (Map.Entry<String, byte[]> entry : newBatchBytes.entrySet()) {
				if (AnonClassAligner.isAnonymousClassName(hostSlash, entry.getKey())) {
					newAnon.put(entry.getKey(), entry.getValue());
				}
			}

			if (oldAnon.isEmpty() && newAnon.isEmpty()) {
				continue;
			}

			if (!ANON_ALIGN) {
				// 总开关关闭：**不能**退化成"按类名照旧重定义" —— 那正是编号位移篡夺场景本身。
				// 唯一安全的关闭语义是把该宿主组整体移出本批重定义。
				rejectHostGroup(newBatchBytes, classToPath, hostName, newAnon,
					new AnonClassAligner.AlignmentRejectedException(hostSlash,
						"anonymous class alignment disabled (nipx.agent.anon_align=false)"));
				continue;
			}

			AlignmentTransaction tx = new AlignmentTransaction(hostName);
			for (Map.Entry<String, byte[]> entry : oldAnon.entrySet()) {
				tx.pinOldBytes.put(entry.getKey(), entry.getValue());
			}

			byte[] hostBytes = newBatchBytes.get(hostName);
			if (DEBUG) log("[ANON_ALIGN] Aligning anonymous classes for host: " + hostName + " (old=" + oldAnon.size() + ", new=" + newAnon.size() + ")");

			java.util.function.Function<String, byte[]> oldRes = name -> bytecodeCache.get(name.replace('/', '.'));
			java.util.function.Function<String, byte[]> newRes = name -> newBatchBytes.get(name.replace('/', '.'));

			AnonClassAligner.Result res;
			try {
				res = AnonClassAligner.align(hostSlash, hostBytes, oldAnon, newAnon, oldRes, newRes,
					HotSwapAgent::hasLiveInstances);
			} catch (Throwable t) {
				// §4.3：以「宿主类 + 其下属全部匿名类」为原子单元整体拒绝。
				//
				// 这里**必须** continue 而不能让异常冒泡：冒泡会让整个 processChanges 中断，
				// 结果是"本轮所有类的热更静默失效"（异常最终只留在 ScheduledFuture 里，无人观测）；
				// 而对齐失败也不能只跳过匿名类 —— 该宿主的新字节码会引用到未对齐的 Foo$N。
				// 因此正确做法是连宿主一起移出本批，其余宿主照常处理。
				rejectHostGroup(newBatchBytes, classToPath, hostName, newAnon, t);
				continue;
			}

			if (res.alignedHostBytes != null) {
				newBatchBytes.put(hostName, res.alignedHostBytes);
			}

			for (String orphan : res.orphanOldClasses) {
				allOrphanClasses.add(orphan.replace('/', '.'));
			}

			// 清理已被重命名位移的原编译类名（避免旧名字被当作新类重复处理）
			for (String originalAnonName : newAnon.keySet()) {
				newBatchBytes.remove(originalAnonName);
			}

			// 将对齐重命名后的新匿名类注入批次并登记到事务
			Path hostPath = classToPath.get(hostName);
			for (Map.Entry<String, byte[]> entry : res.alignedAnonClasses.entrySet()) {
				String targetSlash = entry.getKey();
				String targetDot = targetSlash.replace('/', '.');
				byte[] alignedBytes = entry.getValue();

				newBatchBytes.put(targetDot, alignedBytes);
				if (hostPath != null && !classToPath.containsKey(targetDot)) {
					classToPath.put(targetDot, hostPath);
				}

				tx.pendingAdds.put(targetSlash, alignedBytes);
				tx.cacheUpdates.put(targetDot, alignedBytes);
				tx.targetClasses.add(targetDot);
			}
			transactions.put(hostName, tx);
		}

		for (Map.Entry<String, byte[]> batchEntry : newBatchBytes.entrySet()) {
			String className = batchEntry.getKey();
			byte[] bytecode = batchEntry.getValue();
			Path path = classToPath.get(className);
			if (DEBUG) log("Processing changes: " + (path != null ? path : className));

			if (allOrphanClasses.contains(className)) {
				if (DEBUG) log("[ORPHAN-RETAIN] Retaining orphan class: " + className);
				continue;
			}

			try {
				if (isBlacklisted(className)) {
					if (DEBUG) log("[SKIP-BLACKLIST] " + className);
					continue;
				}

				long newHash      = calculateHash(bytecode); // 现在返回 long
				long classNameKey = CRC64.hashString(className); // 类名也转为 long

				// 检查指纹
				synchronized (fileDiskHashes) {
					long oldDiskHash = fileDiskHashes.get(classNameKey);
					if (oldDiskHash != -1 && oldDiskHash == newHash) {
						if (DEBUG) log("[SKIP] " + className + " (file hash unchanged)");
						skippedCount++;
						continue;
					}
					// 【不在此处落账】指纹必须等到该类**确实重定义成功**后才记录。
					//
					// 旧实现把 put 放在这里（重定义之前），于是以下两条"没生效"的路径
					// 也会留下已记账的指纹，导致下一次触发被判为 "file hash unchanged" 而跳过 ——
					// 文件明明改了，却永远不再尝试重试：
					//   • hierarchyChanged 的 continue（本方法下方）—— 需重启，属于已知拒绝；
					//   • applyRedefinitions 批量/单类失败 —— 本轮没生效，但指纹已写。
					// 因此只在这里登记"待落账"，真正的 put 由 applyRedefinitions 之后按成功集合执行。
					pendingHashes.put(classNameKey, newHash);
				}

				Class<?> targetClass = loadedClassesMap.get(className);

				if (targetClass != null) {
					// 类已加载：无论模式，都必须执行 redefinition
					if (DEBUG) log("[MODIFIED] " + className);


					byte[] newBytecode = bytecode;
					byte[] oldBytecode = bytecodeCache.get(className);

					// 如果缓存里没有，主动触发一次 retransform 来"偷"取字节码
					if (oldBytecode == null) {
						oldBytecode = fetchOriginalBytecode(targetClass);
						if (oldBytecode != null) {
							bytecodeCache.put(className, oldBytecode);
						}
					}

					// 执行 ASM Diff
					if (oldBytecode != null) {
						if (LAMBDA_ALIGN) {
							// 【基线一致性】oldBytecode 来自 bytecodeCache，是"上一次 transform 之后
							// JVM 里实际生效"的形态（若开了 HOTSWAP_PLUS，则其中的 lambda 已被
							// forceStaticLambdas 转成"静态 + this 显式首参数"）。而 newBytecode 是刚
							// 从磁盘读到的原始编译产物，还是"实例 lambda"。两者形态不一致时，
							// LambdaAligner 会把同一个 lambda 判成"删除 + 新增"，生成
							// lambda$build$21 这类避障名，随后 redefine 触发的 transform 又会把新类
							// 强制成静态，于是 JVM 里仍持有老 CallSite 的 UI 监听器直接
							// NoSuchMethodError。这里让 newBytecode 先过一遍同样的归一化，
							// 保证比对双方是同一种形态。该方法幂等，即便随后的 transform 再跑一次
							// 也不会二次前置 this。
							if (HOTSWAP_PLUS) {
								String slashClassName = className.replace('.', '/');
								newBytecode = AnnotationTransformer.forceStaticLambdas(
									newBytecode, slashClassName, targetClass.getClassLoader());
							}
							java.util.function.Function<String, byte[]> oldResolver =
								name -> bytecodeCache.get(name.replace('/', '.'));
							java.util.function.Function<String, byte[]> newResolver =
								name -> newBatchBytes.get(name.replace('/', '.'));
							newBytecode = LambdaAligner.align(oldBytecode, newBytecode, oldResolver, newResolver);
						}
					}
					if (oldBytecode != null) {
						ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(oldBytecode, newBytecode);
						ClassDiffUtil.logDiff(className, diff);
						if (diff.hierarchyChanged) {
							error("REJECTED: Class hierarchy change detected for " + className + ". JBR/DCEVM does not support changing superclass/interfaces reliably. Please RESTART application.");
							AlignmentTransaction tx = transactions.get(className);
							if (tx != null) {
								tx.rollback(true);
								transactions.remove(className);
							}
							// 直接跳过该类的重定义，避免抛出 UnsupportedOperationException
							continue;
						}

						if (!diff.structureChanged()) {
							if (!isEnhancedHotswapEnabled()) {
								error("STRUCTURAL CHANGE DETECTED! Field/Method structure changed but DCEVM is NOT active.");
								error("This redefine will likely FAIL. Classes: " + className);
							} else {
								log("[DCEVM] Structure change detected, proceeding with enhanced redefinition.");
							}
						}
						InitFix.transform(targetClass, newBytecode, diff);
					} else {
						log("[WARN] Cannot diff " + className + " (missing old bytecode). Proceeding with redefine.");
					}

					definitions.add(new ClassDefinition(targetClass, newBytecode));
					// 登记为"待重定义"：成败由 applyRedefinitions 决定。
					// 若最终失败，文件会被放回 pendingChanges 以便下次重试。
					//
					// 位置很关键：必须在 hierarchyChanged 的 continue **之后** ——
					// 那种拒绝受 JVM 能力限制，重试永远不会成功，回队只会造成
					// "每次触发都失败一次"的噪音。这里只会登记真正进入 definitions 的类。
					if (path != null) retryCandidates.put(className, path);
				} else {
					if (DEBUG) log("[NEW] " + className);
					// 类尚未加载：根据 REDEFINE_MODE 处理
					// 若 path 来自 jar 临时目录，需还原为 watchDir 内的虚拟路径以正确推断 ClassLoader
					Path clHintPath = tmpToJar.containsKey(path)
					 ? resolveWatchDirPath(tmpToJar.get(path), className)
					 : path;
					// clHintPath == null 表示来自直接传入的 activeWatchJar，无虚拟 watchDir 路径，
					// 退化到纯包名推断（findTargetClassLoader(className)），inject 照常走，lazy_load 挂 tmpDir 路径
					if (REDEFINE_MODE == RedefineMode.inject) {
						if (injectNewClass(className, clHintPath, bytecode)) {
							bytecodeCache.put(className, bytecode);
							injectedCount++;
							// 注入成功即视为生效：指纹可立即落账（不经过 applyRedefinitions）。
							// 失败时不落账，下次触发会重试。
							synchronized (fileDiskHashes) {
								fileDiskHashes.put(CRC64.hashString(className), calculateHash(bytecode));
							}
						}
					} else if (REDEFINE_MODE == RedefineMode.lazy_load && UCP_APPEND) {
						ClassLoader loader = findTargetClassLoader(className, clHintPath);
						// lazy_load + jar：ClassLoader 推断用虚拟路径（clHintPath），
						// 实际挂载用真实 tmpDir 路径，否则 ClassLoader 找不到 class 文件
						Path mountPath = tmpToJar.containsKey(path) ? path : clHintPath;
						if (mountPath != null) {
							PackageUnsealer.unsealClassPackage(loader, className);
							mountForClass(loader, mountPath);
						}
						bytecodeCache.put(className, bytecode);
						// lazy_load 只是挂载路径，类尚未真正加载生效 —— 指纹**不**落账，
						// 等它真正被加载/重定义时再记，否则会漏掉后续的首次加载。
					}
				}
			} catch (Throwable e) {
				error("Failed to process " + path, e);
			}
		}

		// 批量执行重定义（针对已加载类）
		RedefineOutcome outcome = applyRedefinitions(definitions, transactions.values());
		Set<String> redefinedOk  = outcome.successful();
		Set<String> rolledBack    = outcome.rolledBack();

		// ---- 指纹落账 + 失败回队 ----
		//
		// 指纹只对"确实生效"的类记录。未生效的类不落账，因此下次触发不会被
		// "file hash unchanged" 误跳过 —— 这正是本次修复的核心。
		//
		// 同时把重定义失败的类的源文件放回 pendingChanges：失败的文件在
		// triggerHotswapWith0 里已被取走，而 triggerHotswap() 不重扫磁盘，
		// 不回队的话"再热更一次"实际上什么也不会发生（文件不再被写入就不会有新的 watcher 事件）。
		//
		// **例外：被 rollback 的事务组不回队。** 那种情况下宿主可能已生效、而 cache/pending
		// 被钉回旧版本；重试会拿与 JVM 实际状态不一致的基线重新对齐。只提示重启。
		int requeued = 0;
		int notRequeued = 0;
		for (Map.Entry<String, Path> e : retryCandidates.entrySet()) {
			String className = e.getKey();
			if (redefinedOk.contains(className)) {
				// 成功：落账指纹
				synchronized (fileDiskHashes) {
					fileDiskHashes.put(CRC64.hashString(className), pendingHashes.get(CRC64.hashString(className)));
				}
				continue;
			}
			if (rolledBack.contains(className)) {
				// 被 rollback 的组：不落账、也不回队（回队不安全，见上）
				notRequeued++;
				if (DEBUG) log("[RETRY-SKIP] " + className + " in rolled-back group; not requeued.");
				continue;
			}
			// 普通失败：不落账（下次仍会尝试），并回队以便立即重试
			Path p = e.getValue();
			if (p != null && !Files.exists(p)) {
				// jar 来源的类：临时目录会在下一次 extractJarToTemp 时被清空重建，
				// 回队的路径可能已不存在。不报出来这些类会静默丢失重试。
				error("[RETRY-QUEUE] Source file no longer exists for " + className + ": " + p
				      + " (likely a jar temp dir that was rebuilt); this class cannot be retried"
				      + " until its jar is written again.");
				continue;
			}
			pendingChanges.add(p);
			requeued++;
			if (DEBUG) log("[RETRY-QUEUE] " + className + " failed to redefine; requeued " + p);
		}
		if (requeued > 0) {
			warn("[HOTSWAP-RETRY] " + requeued + " class(es) failed to redefine and were requeued; "
			     + "press hot-swap again to retry.");
		}
		if (notRequeued > 0) {
			warn("[HOTSWAP-RETRY] " + notRequeued + " class(es) in an inconsistent group were NOT requeued; "
			     + "RESTART is required for those.");
		}

		processAnnotations(definitions);
		for (ClassDefinition def : definitions) {
			try {
				InitFix.afterRedefine(def.getDefinitionClass());
			} catch (Throwable e) {
				error("Failed to process InitFix.", e);
				InitFix.afterRedefineFailed(def.getDefinitionClass());
			}
		}
		processUIDispatch(definitions);

		if (skippedCount > 0) info("Skipped " + skippedCount + " unchanged classes.");
		if (injectedCount > 0) info("Injected " + injectedCount + " new classes.");
	}


	/**
	 * 布局门用：某个类是否还有存活实例（§7.2 的精确变体）。
	 *
	 * <p><b>判定规则（三条都很关键，别简化）</b>：</p>
	 * <ol>
	 *   <li><b>{@code LibTool} 优先</b> —— 走 JVMTI 的 {@code IterateOverInstancesOfClass}，
	 *       覆盖全部堆实例。</li>
	 *   <li><b>{@code LibTool} 不可用或异常 ⇒ 视为"有实例"。</b>
	 *       刻意<b>不</b>回退到 {@link InstanceTracker}：它只是一个由注入代码
	 *       {@code register()} 填充的弱集合，对匿名类基本是空的，
	 *       信它会误判成"无实例"而放行 —— 那正是我们最不想要的方向。
	 *       保守方向的代价只是少配对，放行的代价是静默读零值。</li>
	 *   <li><b>按类缓存</b> —— 每次扫描都是一次全堆遍历（会触发 safepoint），
	 *       而同一批里可能有多个类命中。</li>
	 * </ol>
	 *
	 * <p>仅用于布局不兼容的类，因此调用频率很低。</p>
	 */
	static boolean hasLiveInstances(String dotClassName) {
		Boolean cached = LIVE_INSTANCE_CACHE.get(dotClassName);
		if (cached != null) return cached;

		boolean result;
		long t0 = System.nanoTime();
		try {
			if (!LibTool.initialized()) {
				// 尝试初始化；失败会抛 UnsatisfiedLinkError
				LibTool.init();
			}
			Class<?> c = loadedClassesMap.get(dotClassName);
			if (c == null) {
				// 旧类未加载 ⇒ 不可能有实例。这是**安全**方向的"无实例"：
				// 未加载的类没有对象可言，放行配对不会让任何东西读到零值。
				result = false;
			} else {
				result = LibTool.getInstances(c).length > 0;
			}
		} catch (Throwable t) {
			// JVMTI 不可用 / 初始化失败 / 扫描异常 —— 一律按"有实例"处理（保守）。
			warn("[ANON-LAYOUT] Cannot scan instances for " + dotClassName
			     + " (" + t.getClass().getSimpleName() + "); assuming LIVE instances exist.");
			result = true;
		}
		long ms = (System.nanoTime() - t0) / 1_000_000;
		LIVE_INSTANCE_SCAN_MILLIS.addAndGet(ms);
		if (DEBUG || ms > 50) {
			log("[ANON-LAYOUT] instance scan " + dotClassName + " -> " + (result ? "LIVE" : "none")
			    + " (" + ms + " ms)");
		}
		LIVE_INSTANCE_CACHE.put(dotClassName, result);
		return result;
	}

	/** 每轮热更开始时清空实例扫描缓存（实例的存活状况会随轮次变化）。 */
	private static void resetLiveInstanceCache() {
		LIVE_INSTANCE_CACHE.clear();
		LIVE_INSTANCE_SCAN_MILLIS.set(0);
	}

	/** 实例判定缓存：点分类名 → 是否存活实例。见 {@link #hasLiveInstances}。 */
	private static final Map<String, Boolean> LIVE_INSTANCE_CACHE = new ConcurrentHashMap<>();
	/** 累计的实例扫描耗时（毫秒），用于观察全堆遍历开销。 */
	static final java.util.concurrent.atomic.AtomicLong LIVE_INSTANCE_SCAN_MILLIS =
	 new java.util.concurrent.atomic.AtomicLong();

	/** 在 applyRedefinitions(definitions) 后调用 */
	private static void processAnnotations(List<ClassDefinition> definitions) {
		if (!ENABLE_HOTSWAP_EVENT) return;

		for (ClassDefinition def : definitions) {
			Class<?> clazz = def.getDefinitionClass();

			if (!(FORCE_REINIT || clazz.isAnnotationPresent(Reloadable.class))) continue;

			Core.app.post(() -> Core.app.post(() -> processAnnotationsInternal(clazz)));
		}
	}
	private static void processAnnotationsInternal(Class<?> clazz) {
		Method reloadMethod = null;
		for (Method m : clazz.getDeclaredMethods()) {
			if (m.isAnnotationPresent(OnReload.class)) {
				reloadMethod = m;
				reloadMethod.setAccessible(true);
				break;
			}
		}
		if (reloadMethod == null) return;
		info("[Reload] Found @OnReload on " + clazz);

		if (Modifier.isStatic(reloadMethod.getModifiers())) {
			try {
				reloadMethod.invoke(null);
				if (DEBUG) log("[Reload] Invoked @OnReload static method.");
			} catch (Exception e) {
				error("Error invoking @OnReload", e);
			}
			if (DEBUG) log("[Reload] Invoked @OnReload on " + clazz);
			return;
		}

		Object[] instances = LibTool.initialized() ? LibTool.getInstances(clazz) : InstanceTracker.getInstances(clazz).toArray();

		for (Object obj : instances) {
			try {
				reloadMethod.invoke(obj);
				if (DEBUG) log("[Reload] Invoked @OnReload on " + obj);
			} catch (Exception e) {
				error("Error invoking @OnReload", e);
			}
		}
	}


	private static void processUIDispatch(List<ClassDefinition> definitions) {
		if (CellPropertyRef.isEnabled()) {
			for (ClassDefinition definition : definitions) {
				Class<?> clazz = definition.getDefinitionClass();
				CellPropertyRef.afterRedefine(AnnotationTransformer.internalName(clazz), definition.getDefinitionClassFile());
			}
		}
	}


	static boolean isBlacklisted(String className) {
		if (HOTSWAP_BLACKLIST == null) return false;
		for (String prefix : HOTSWAP_BLACKLIST) {
			if (!prefix.isEmpty() && className.startsWith(prefix)) return true;
		}
		return false;
	}

	public static byte[] fetchBytecodeMemory(Class<?> clazz) throws UnmodifiableClassException {
		byte[][] bytecode = {null};
		class MyTransformer implements ClassFileTransformer {
			@Override
			public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			                        ProtectionDomain protectionDomain, byte[] classfileBuffer) {
				if (
				 clazz == classBeingRedefined
					/* className.equals(clazz.getName()) && loader == clazz.getClassLoader() */) {
					bytecode[0] = classfileBuffer;
				}
				return null;
			}
		}
		MyTransformer transformer1 = new MyTransformer();
		inst.addTransformer(transformer1, true);
		try {
			inst.retransformClasses(clazz);
			return bytecode[0];
		} finally {
			inst.removeTransformer(transformer1);
		}
	}
	public static byte[] fetchCurrentBytecode(Class<?> clazz) {
		String className = clazz.getName();
		if (bytecodeCache.containsKey(className)) return bytecodeCache.get(className);
		return fetchOriginalBytecode(clazz);
	}
	public static byte[] fetchOriginalBytecode(Class<?> clazz) {
		String      path = clazz.getName().replace('.', '/') + ".class";
		ClassLoader cl   = clazz.getClassLoader();
		if (cl == null) cl = ClassLoader.getSystemClassLoader();

		try {
			// 核心扬弃：获取所有同名资源，执行空间隔离
			var resources = cl.getResources(path);

			while (resources.hasMoreElements()) {
				URL     url            = resources.nextElement();
				boolean isFromWatchDir = false;

				// 异质点校验：判断这个流是否来自我们监控（已被修改）的目录
				if ("file".equals(url.getProtocol())) {
					// 普通 .class 文件：直接判断路径是否在 watchDir 下
					try {
						Path resourcePath = Paths.get(url.toURI()).toAbsolutePath();
						for (Path watchDir : activeWatchDirs) {
							if (resourcePath.startsWith(watchDir.toAbsolutePath())) {
								isFromWatchDir = true;
								break;
							}
						}
					} catch (Exception ignored) {
						// URI格式异常，防守性跳过
					}
				} else if ("jar".equals(url.getProtocol())) {
					// jar 内 .class：URL 形如 jar:file:/watchdir/foo.jar!/com/Foo.class
					// 需要把宿主 jar 的路径单独解析出来再做判断
					try {
						String jarUrlStr = url.getPath();          // file:/watchdir/foo.jar!/com/Foo.class
						int    bangIdx   = jarUrlStr.indexOf("!/");
						if (bangIdx != -1) {
							String jarFileStr = jarUrlStr.substring(0, bangIdx); // file:/watchdir/foo.jar
							Path   jarPath    = Paths.get(new java.net.URI(jarFileStr)).toAbsolutePath();
							for (Path watchDir : activeWatchDirs) {
								if (jarPath.startsWith(watchDir.toAbsolutePath())) {
									isFromWatchDir = true;
									break;
								}
							}
						}
					} catch (Exception ignored) {
						// URI格式异常，防守性跳过
					}
				}

				// 只要不是来自监控目录（包括目录内的 jar），就视为未被污染的原始字节码
				if (!isFromWatchDir) {
					try (InputStream is = url.openStream()) {
						return is.readAllBytes();
					}
				} else {
					if (DEBUG) log(" Ignored polluted mount path: " + url);
				}
			}
		} catch (Throwable t) {
			// 吞掉异常，退化到盲狙
		}
		return null;
	}
	//endregion

	//region Class Injection and Redefinition
	/**
	 * 直接定义类，而不是被动加载
	 */
	private static boolean injectNewClass(String className, Path path, byte[] bytes) {
		try {
			ClassLoader loader = findTargetClassLoader(className, path);
			if (loader == null) {
				error("Could not find a suitable ClassLoader for new class: " + className);
				return false;
			}

			PackageUnsealer.unsealClassPackage(loader, className);
			Reflect.defineClass(className, bytes, 0, bytes.length, loader, null);
			info("[INJECTED] Successfully defined new class: " + className + " into " + loader);
			return true;
		} catch (LinkageError le) {
			if (DEBUG) error("Class already loaded: " + className, le);
			return true;
		} catch (Exception e) {
			error("Failed to inject new class: " + className + ". CAUTION: This may cause NoClassDefFoundError.", e);
			return false;
		}
	}

	/**
	 * 一次对齐操作的事务封装。
	 *
	 * <p>保证已加载类、未加载类的 pendingAlignedClasses 注入与 bytecodeCache 的提交具有原子性与一致性：
	 * <ul>
	 *   <li>重定义成功：整体提交（commit），将对齐类写入 pending 并更新 cache；</li>
	 *   <li>重定义失败或跳过：整体回滚（rollback），撤销新版本的 pending 注入，
	 *       并将生效旧版本字节码钉在 pending 中（{@code pinOldToPending}），防止旧宿主未来首次加载时读到磁盘上位移后的错误内容。</li>
	 * </ul>
	 */
	public static class AlignmentTransaction {
		public final String hostName;
		public final Map<String, byte[]> pendingAdds = new LinkedHashMap<>();
		public final Map<String, byte[]> cacheUpdates = new LinkedHashMap<>();
		public final Map<String, byte[]> pinOldBytes = new LinkedHashMap<>();
		public final Set<String> targetClasses = new LinkedHashSet<>();
		public boolean preRegistered = false;
		public boolean committed = false;

		public AlignmentTransaction(String hostName) {
			this.hostName = hostName;
		}

		public void preRegister() {
			if (preRegistered || committed) return;
			preRegistered = true;
			for (Map.Entry<String, byte[]> entry : pendingAdds.entrySet()) {
				String targetSlash = entry.getKey().replace('.', '/');
				String targetDot = entry.getKey().replace('/', '.');
				byte[] bytes = entry.getValue();
				AnnotationTransformer.pendingAlignedClasses.put(targetSlash, bytes);
				AnnotationTransformer.pendingAlignedClasses.put(targetDot, bytes);
			}
		}

		public void commit() {
			if (committed) return;
			preRegister();
			committed = true;
			for (Map.Entry<String, byte[]> entry : cacheUpdates.entrySet()) {
				bytecodeCache.put(entry.getKey().replace('/', '.'), entry.getValue());
			}
		}

		public void rollback(boolean pinOldToPending) {
			if (committed) return;
			for (String key : pendingAdds.keySet()) {
				AnnotationTransformer.pendingAlignedClasses.remove(key.replace('.', '/'));
				AnnotationTransformer.pendingAlignedClasses.remove(key.replace('/', '.'));
			}
			if (pinOldToPending) {
				// 将被磁盘新文件覆盖但重定义未生效的旧版本字节码钉在 pending 中
				for (Map.Entry<String, byte[]> entry : pinOldBytes.entrySet()) {
					if (entry.getValue() != null) {
						String slash = entry.getKey().replace('.', '/');
						String dot = entry.getKey().replace('/', '.');
						AnnotationTransformer.pendingAlignedClasses.put(slash, entry.getValue());
						AnnotationTransformer.pendingAlignedClasses.put(dot, entry.getValue());
					}
				}
			}
		}
	}

	/**
	 * §4.3 宿主级拒绝：把「宿主类 + 其下属全部匿名类」整体移出本批重定义。
	 *
	 * <p><b>为什么必须连新侧匿名类的原始类名一起移除</b>：一旦不做对齐，新编译产物的 {@code Foo$2}
	 * 与 JVM 中已加载的旧 {@code Foo$2} 同名但语义不同，把它送进 redefinition 就是把老实例的方法表
	 * 交给无关的新类 —— 正是本模块存在的理由。同理，宿主本身也必须一起移除，否则它的新字节码会
	 * 引用到没有被对齐过的 {@code Foo$N}。</p>
	 *
	 * <p>因此拒绝的语义是"这一组本轮完全不动"，与 {@code docs/ANONYMOUS_CLASS_TOPOLOGY_PLAN.md}
	 * §4.3 的"以宿主 + 其下属全部匿名类为原子单元整体拒绝回滚"一致。</p>
	 *
	 * @param t 触发拒绝的异常；{@link AnonClassAligner.AlignmentRejectedException} 视为**预期**拒绝
	 *          （打 {@code [HOTSWAP-REJECT]} 告警），其它异常视为对齐器缺陷（打 error + 堆栈）
	 */
	private static void rejectHostGroup(Map<String, byte[]> newBatchBytes, Map<String, Path> classToPath,
	                                    String hostName, Map<String, byte[]> newAnon, Throwable t) {
		boolean expected = t instanceof AnonClassAligner.AlignmentRejectedException;
		String reason = expected
		 ? ((AnonClassAligner.AlignmentRejectedException) t).reason
		 : (t.getClass().getSimpleName() + ": " + t.getMessage());
		if (expected) {
			warn("[HOTSWAP-REJECT] Structural ambiguity detected in " + hostName
			     + ". Redefine skipped safely. Reason: " + reason
			     + ". Please hot-swap again or restart.");
		} else {
			error("[HOTSWAP-REJECT] Anonymous class alignment failed in " + hostName
			      + ". Redefine skipped safely. Reason: " + reason
			      + ". Please hot-swap again or restart.", t);
		}
		dropFromBatch(newBatchBytes, classToPath, hostName);
		for (String anonName : newAnon.keySet()) {
			dropFromBatch(newBatchBytes, classToPath, anonName);
		}
	}

	/** 从批次中移除一个类（点分与斜杠两种键形态都移除，避免因键写法不同而漏删）。 */
	private static void dropFromBatch(Map<String, byte[]> newBatchBytes, Map<String, Path> classToPath, String className) {
		newBatchBytes.remove(className);
		classToPath.remove(className);
		String alt = className.indexOf('/') >= 0 ? className.replace('/', '.') : className.replace('.', '/');
		newBatchBytes.remove(alt);
		classToPath.remove(alt);
	}

	/**
	 * 分块执行 Redefine，防止其中一个类出错导致所有类失败，并依据重定义成败驱动事务提交或回滚。
	 *
	 * <p>注：真正的多类原子一致性依赖 JVM 批量 {@code inst.redefineClasses(definitions)} 的原子调用；
	 * 当批量失败切换到单类模式时，属于尽力挽救兜底，客观上存在短暂的类间不一致时间窗口。</p>
	 *
	 * @return 确实重定义成功的类名集合。调用方据此决定是否落账文件指纹、以及失败类是否回队重试。
	 *         <p>另有两条必须区分的语义，通过 {@link RedefineOutcome} 一并返回：</p>
	 *         <ul>
	 *           <li>{@code rejectedHierarchy} —— 因 {@code hierarchyChanged} 被拒的类。
	 *               受 JVM 能力限制，重试永不会成功，<b>不得回队</b>。</li>
	 *           <li>{@code rolledBackGroups} —— 因事务组不一致而 {@code rollback} 的类。
	 *               这些类的宿主可能<b>已经在 JVM 里生效</b>，而 cache/pending 被钉回旧版本；
	 *               此时重试会拿"基线与 JVM 实际状态不一致"的数据重新对齐，<b>不得回队</b>。</li>
	 *         </ul>
	 */
	private static RedefineOutcome applyRedefinitions(List<ClassDefinition> definitions, Collection<AlignmentTransaction> transactions) {
		Set<String> successfulClasses = new HashSet<>();
		Set<String> rolledBackClasses = new HashSet<>();
		if (definitions.isEmpty()) {
			for (AlignmentTransaction tx : transactions) {
				tx.commit();
			}
			return new RedefineOutcome(successfulClasses, rolledBackClasses);
		}

		// 关键竞态消除：在调用 redefineClasses 之前先乐观登记新的 pending 注入！
		// 避免宿主重定义成功到事务提交之间，并发线程或初始化方法首次加载未加载类时读到磁盘错位产物
		for (AlignmentTransaction tx : transactions) {
			tx.preRegister();
		}

		// 排序：被引用的匿名类优先重定义，宿主类最后重定义，降低单类重定义模式下的半生效风险
		definitions.sort((d1, d2) -> {
			boolean a1 = d1.getDefinitionClass().getName().contains("$");
			boolean a2 = d2.getDefinitionClass().getName().contains("$");
			if (a1 && !a2) return -1;
			if (!a1 && a2) return 1;
			return 0;
		});
		try {
			inst.redefineClasses(definitions.toArray(new ClassDefinition[0]));
			info("HotSwap successful: " + definitions.size() + " classes redefined.");
			for (ClassDefinition def : definitions) {
				bytecodeCache.put(def.getDefinitionClass().getName(), def.getDefinitionClassFile());
				successfulClasses.add(def.getDefinitionClass().getName());
			}
			for (AlignmentTransaction tx : transactions) {
				tx.commit();
			}
		} catch (Throwable t) {
			error("Bulk Redefine failed, switching to individual mode...", t);
			for (ClassDefinition def : definitions) {
				// 批量删除缓存
				InitFix.afterRedefineFailed(def.getDefinitionClass());
				try {
					inst.redefineClasses(def);
					bytecodeCache.put(def.getDefinitionClass().getName(), def.getDefinitionClassFile());
					successfulClasses.add(def.getDefinitionClass().getName());
					if (DEBUG) log("[OK] " + def.getDefinitionClass().getName());
				} catch (Throwable e) {
					error("[FAIL] " + def.getDefinitionClass().getName(), e);
					InitFix.afterRedefineFailed(def.getDefinitionClass());
				}
			}
			// 校验事务组的一致性
			for (AlignmentTransaction tx : transactions) {
				boolean hostOk = successfulClasses.contains(tx.hostName);
				boolean allAnonsOk = true;
				for (String target : tx.targetClasses) {
					if (loadedClassesMap.containsKey(target) && !successfulClasses.contains(target)) {
						allAnonsOk = false;
					}
				}
				if (hostOk && allAnonsOk) {
					tx.commit();
				} else {
					if (hostOk != allAnonsOk) {
						error("[HOTSWAP-PARTIAL] Host " + tx.hostName + " and its anonymous classes redefined inconsistently! Note: classes already applied in JVM cannot be un-redefined; rolling back pending injections and cache, and pinning old bytecode in pending to protect future class loading.");
						error("[HOTSWAP-PARTIAL] Host " + tx.hostName
						      + ": NOT requeued for retry — a retry would re-align against a baseline"
						      + " that no longer matches the JVM's actual state. RESTART is required.");
					}
					tx.rollback(true);
					// 记录整组（宿主 + 其匿名类），供调用方排除出"失败回队"。
					// 原因：宿主可能已生效、而 cache/pending 被钉回旧版本，重试基线不一致。
					rolledBackClasses.add(tx.hostName);
					rolledBackClasses.addAll(tx.targetClasses);
				}
			}
		}
		return new RedefineOutcome(successfulClasses, rolledBackClasses);
	}

	/**
	 * {@link #applyRedefinitions} 的结果。
	 *
	 * @param successful    确实重定义成功的类名
	 * @param rolledBack    因事务组不一致被 rollback 的类名（宿主 + 其匿名类）——
	 *                      这些类<b>不得回队重试</b>，理由见该方法 javadoc
	 */
	private record RedefineOutcome(Set<String> successful, Set<String> rolledBack) { }
	//endregion

	//region File Processing Utilities

	private static void initializeAgentState(Class<?>[] classes) {
		info("Scanning files...");
		// 直接传入的 jar/zip 文件：立即解压扫描
		for (Path jar : activeWatchJars) {
			String stem = jar.getFileName().toString().replaceAll("[^a-zA-Z0-9_-]", "_");
			Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"), "nipx-hotswap",
			 stem + "-" + Long.toHexString(CRC64.hashString(jar.toString())));
			List<Path> outOfSync = new ArrayList<>();
			try {
				Files.createDirectories(tmpDir);
				tmpToJar.entrySet().removeIf(e -> e.getValue().equals(jar));
				try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(jar)))) {
					ZipEntry entry;
					while ((entry = zis.getNextEntry()) != null) {
						try {
							if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
								byte[] diskBytes = zis.readAllBytes();
								String cName     = Utils.getClassNameASM(diskBytes);
								if (cName == null || isBlacklisted(cName)) continue;
								long diskHash = calculateHash(diskBytes);
								Path outPath  = tmpDir.resolve(entry.getName().replace('/', File.separatorChar));
								Files.createDirectories(outPath.getParent());
								Files.write(outPath, diskBytes);
								tmpToJar.put(outPath, jar);
								byte[] memBytes = bytecodeCache.get(cName);
								if (memBytes != null && calculateHash(memBytes) != diskHash) {
									if (DEBUG) log("[INIT-SYNC] " + cName + " (direct jar) out of sync.");
									outOfSync.add(outPath);
								}
								synchronized (fileDiskHashes) {
									fileDiskHashes.put(CRC64.hashString(cName), diskHash);
								}
							}
						} catch (Exception _) {
						} finally {
							zis.closeEntry();
						}
					}
				}
				if (!outOfSync.isEmpty()) pendingChanges.addAll(outOfSync);
			} catch (IOException e) {
				error("Failed to scan direct jar: " + jar, e);
			}
		}
		for (Path root : activeWatchDirs) {
			try (Stream<Path> walk = Files.walk(root)) {
				walk.filter(p -> {
					String s = p.toString();
					// jar/zip 只取 root 直属子文件（depth == 1），.class 全量递归
					if (s.endsWith(".jar") || s.endsWith(".zip")) return p.getParent().equals(root);
					return s.endsWith(".class");
				}).forEach(path -> {
					String ps = path.toString();
					if (ps.endsWith(".jar") || ps.endsWith(".zip")) {
						// 对 jar/zip：解压后做 hash 对比，out-of-sync 的类批量加入 pendingChanges
						Path   absJar = path.toAbsolutePath();
						String stem   = absJar.getFileName().toString().replaceAll("[^a-zA-Z0-9_-]", "_");
						Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"), "nipx-hotswap",
						 stem + "-" + Long.toHexString(CRC64.hashString(absJar.toString())));
						List<Path> outOfSync = new ArrayList<>();
						try {
							Files.createDirectories(tmpDir);
							try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(absJar)))) {
								ZipEntry entry;
								while ((entry = zis.getNextEntry()) != null) {
									try {
										if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
											byte[] diskBytes = zis.readAllBytes();
											String cName     = Utils.getClassNameASM(diskBytes);
											if (cName == null || isBlacklisted(cName)) continue;
											long diskHash = calculateHash(diskBytes);

											byte[] memBytes = bytecodeCache.get(cName);
											if (memBytes != null && calculateHash(memBytes) != diskHash) {
												if (DEBUG) log("[INIT-SYNC] " + cName + " (in jar) is out of sync. Reloading...");
												Path outPath = tmpDir.resolve(entry.getName().replace('/', File.separatorChar));
												Files.createDirectories(outPath.getParent());
												Files.write(outPath, diskBytes);
												tmpToJar.put(outPath, absJar);
												outOfSync.add(outPath);
											}
											synchronized (fileDiskHashes) {
												fileDiskHashes.put(CRC64.hashString(cName), diskHash);
											}
										}
									} catch (Exception _) {
									} finally {
										zis.closeEntry();
									}
								}
							}
							// 批量加入，只锁一次
							if (!outOfSync.isEmpty()) pendingChanges.addAll(outOfSync);
						} catch (IOException _) { }
						return;
					}
					// 原始 .class 文件逻辑（保持不变）
					try {
						byte[] diskBytes = Files.readAllBytes(path);
						String cName     = Utils.getClassNameASM(diskBytes);
						if (cName == null || isBlacklisted(cName)) return;

						long diskHash = calculateHash(diskBytes);

						// 获取内存中的字节码（这是 transformLoaded 偷出来的）
						byte[] memBytes = bytecodeCache.get(cName);
						if (memBytes != null) {
							long memHash = calculateHash(memBytes);
							if (diskHash != memHash) {
								// 发现磁盘和内存不一致，手动加入待处理队列
								if (DEBUG) log("[INIT-SYNC] " + cName + " is out of sync. Reloading...");
								pendingChanges.add(path);
							}
						}
						// 只有在这里才记录磁盘哈希
						synchronized (fileDiskHashes) {
							fileDiskHashes.put(CRC64.hashString(cName), diskHash);
						}
					} catch (Exception _) { }
				});
			} catch (IOException _) { }
		}
		// 入参 classes 是调用方在 restartWatchers 之前取的快照，此刻可能已过期
		// （旧 watcher 排进 scheduler 的任务期间可能又有类被加载）。改走 triggerHotswap()，
		// 由它在锁内重新取一份新鲜快照。
		if (!pendingChanges.isEmpty()) triggerHotswap();
	}

	private static void handleFileChange(Path changedFile) {
		synchronized (pendingChanges) {
			pendingChanges.add(changedFile);
			if (scheduledTask != null && !scheduledTask.isDone()) {
				scheduledTask.cancel(false);
			}
			scheduledTask = scheduler.schedule(HotSwapAgent::triggerHotswap, FILE_SHAKE_MS, TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * 处理 jar/zip 变化：仅入队，与 .class 共用防抖，防止读到写了一半的 jar。
	 * 真正的解压在防抖结束后由 triggerHotswapWith → extractJarToTemp 完成。
	 */
	private static void handleJarChange(Path jarPath) {
		if (DEBUG) log("[JAR] Queued for extraction: " + jarPath);
		synchronized (pendingChanges) {
			pendingJars.add(jarPath.toAbsolutePath());
			if (scheduledTask != null && !scheduledTask.isDone()) {
				scheduledTask.cancel(false);
			}
			scheduledTask = scheduler.schedule(HotSwapAgent::triggerHotswap, FILE_SHAKE_MS, TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * 将 jar/zip 内所有 .class 解压到固定临时目录（基于 jar 路径 hash 保持稳定），
	 * 每次更新前先清空旧内容，避免 tmpdir 无限膨胀。
	 * 解压结果批量加入 pendingChanges，并维护 tmpToJar 映射。
	 */
	private static void extractJarToTemp(Path jarPath) {
		try {
			// 固定路径：<tmpdir>/nipx-hotswap/<stem>-<jarPathHash>
			String stem = jarPath.getFileName().toString().replaceAll("[^a-zA-Z0-9_-]", "_");
			Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"), "nipx-hotswap",
			 stem + "-" + Long.toHexString(CRC64.hashString(jarPath.toString())));

			// 清空旧内容（稳定路径，不删目录本身）
			if (Files.exists(tmpDir)) {
				try (Stream<Path> s = Files.walk(tmpDir)) {
					s.sorted(Comparator.reverseOrder())
					 .filter(p -> !p.equals(tmpDir))
					 .forEach(p -> { try { Files.delete(p); } catch (IOException _) { } });
				}
			}
			Files.createDirectories(tmpDir);

			// 清理该 jar 上一次留下的 tmpToJar 条目，防止 map 无限增长
			tmpToJar.entrySet().removeIf(e -> e.getValue().equals(jarPath));

			List<Path> extracted = new ArrayList<>();
			try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(jarPath)))) {
				ZipEntry entry;
				while ((entry = zis.getNextEntry()) != null) {
					try {
						if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
							Path outPath = tmpDir.resolve(entry.getName().replace('/', File.separatorChar));
							Files.createDirectories(outPath.getParent());
							Files.write(outPath, zis.readAllBytes());
							tmpToJar.put(outPath, jarPath);
							extracted.add(outPath);
						}
					} finally {
						zis.closeEntry();
					}
				}
			}

			if (extracted.isEmpty()) {
				if (DEBUG) log("[JAR] No .class entries in: " + jarPath);
				return;
			}
			info("[JAR] Extracted " + extracted.size() + " classes from " + jarPath.getFileName() + " → " + tmpDir);
			// 批量加入，只锁一次
			synchronized (pendingChanges) {
				pendingChanges.addAll(extracted);
			}
		} catch (IOException e) {
			error("Failed to extract jar/zip: " + jarPath, e);
		}
	}

	/**
	 * 将临时 .class 路径映射回 watchDir 内的虚拟路径，
	 * 供 findTargetClassLoader / mountForClass 正确推断 ClassLoader。
	 */
	private static Path resolveWatchDirPath(Path jarPath, String className) {
		// 情况一：jar 在某个 watchDir 目录下（目录模式）
		for (Path watchDir : activeWatchDirs) {
			if (jarPath.startsWith(watchDir)) {
				return watchDir.resolve(className.replace('.', File.separatorChar) + ".class");
			}
		}
		// 情况二：jar 本身就是直接传入的 activeWatchJar。
		// 此时没有对应的 watchDir，不需要虚拟路径，返回 null 让调用方
		// 退化到 findTargetClassLoader(className) 纯包名推断即可。
		if (activeWatchJars.contains(jarPath)) {
			return null;
		}
		error("[WARN] resolveWatchDirPath: jar not under any watchDir or watchJar: " + jarPath);
		return null;
	}

	private static void triggerHotswapWith(Class<?>[] classes) {
		// 锁不变量：任何进入 processChanges 的路径都必须持有 HOTSWAP_LOCK。
		// 用抛异常而非 assert —— assert 默认关闭（需 -ea），而这条不变量一旦被破坏就是
		// 静默的并发数据竞争，必须无条件暴露。将来有人新增绕过锁的入口时会立刻在这里炸掉。
		if (!HOTSWAP_LOCK.isHeldByCurrentThread()) {
			throw new IllegalStateException(
				"triggerHotswapWith called without HOTSWAP_LOCK on thread "
				+ Thread.currentThread().getName()
				+ " — all hot-swap entry points must acquire HOTSWAP_LOCK first");
		}

		// 并发探针：测试用，记录同时处于热更流程内的线程数及其历史峰值。
		// 不加锁时应观察到峰值 2（scheduler 一轮 + 按钮线程一轮），加锁后恒为 1。
		// getAndAccumulate 本身是原子的，无需再补一次 set。
		int concurrent = CONCURRENT_HOTSWAP.getAndIncrement();
		CONCURRENT_HOTSWAP_PEAK.getAndAccumulate(concurrent, Math::max);
		try {
			triggerHotswapWith0(classes);
		} finally {
			CONCURRENT_HOTSWAP.decrementAndGet();
		}
	}

	private static void triggerHotswapWith0(Class<?>[] classes) {
		// 实例存活状况每轮都可能变（对象被创建/回收），缓存必须按轮清空
		resetLiveInstanceCache();
		// 先把所有待处理的 jar 解压（防抖已结束，文件写入完毕）
		Set<Path> jars;
		synchronized (pendingChanges) {
			jars = new HashSet<>(pendingJars);
			pendingJars.clear();
		}
		jars.forEach(HotSwapAgent::extractJarToTemp);

		Set<Path> changes;
		synchronized (pendingChanges) {
			if (pendingChanges.isEmpty()) return;
			changes = new HashSet<>(pendingChanges);
			pendingChanges.clear();
		}
		processChanges(changes, classes);
	}

	/** 对外api，触发热更新 */
	public static void triggerHotswap() {
		HOTSWAP_LOCK.lock();
		try {
			// 快照必须在锁内取：排队等待期间 getAllLoadedClasses() 的结果会过期
			triggerHotswapWith(inst.getAllLoadedClasses());
		} finally {
			HOTSWAP_LOCK.unlock();
		}
	}

	private static long calculateHash(byte[] data) {
		return CRC64.update(data);
	}
	//endregion

	//region Logging System
	public static Logger logger = new DefaultLogger();


	public static class DefaultLogger implements Logger {
		@Override
		public void log(String msg) {
			if (DEBUG) System.out.println("[NIPX] " + msg);
		}

		@Override
		public void info(String msg) {
			System.out.println("[NIPX] " + msg);
		}

		@Override
		public void warn(String msg) {
			System.out.println("[NIPX] [WARN] " + msg);
		}

		@Override
		public void error(String msg) {
			System.err.println("[NIPX] " + msg);
		}

		@Override
		public void error(String msg, Throwable t) {
			System.err.println("[NIPX] " + msg);
			t.printStackTrace(System.err);
		}
	}

	public interface Logger {
		void log(String msg);
		void info(String msg);
		void warn(String msg);
		void error(String msg);
		void error(String msg, Throwable t);
	}

	public static void log(String msg) { logger.log(msg); }
	public static void info(String msg) { logger.info(msg); }
	public static void warn(String s) { logger.warn(s); }
	public static void error(String msg) { logger.error(msg); }
	public static void error(String msg, Throwable t) { logger.error(msg, t); }
	//endregion

	//region Environment Detection
	static Class<?> cl_Element;

	private static final boolean ENHANCED_HOTSWAP;

	static {
		boolean enhanced = false;
		try {
			List<String> inputArguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
			for (String arg : inputArguments) {
				// info("Input Argument: " + arg);
				if (arg.equals("-XX:+AllowEnhancedClassRedefinition")) {
					// info("[OK] Enhanced HotSwap is enabled.");
					enhanced = true;
					break;
				}
			}
		} catch (Throwable _) { }
		ENHANCED_HOTSWAP = enhanced;
	}

	/**
	 * 判断当前环境是否支持增强型热重载（增加字段/方法等）
	 * 支持 DCEVM 且 显式开启了 AllowEnhancedClassRedefinition 的现代 OpenJDK
	 */
	public static boolean isEnhancedHotswapEnabled() {
		return ENHANCED_HOTSWAP;
	}
	//endregion

	//region File Watcher Implementation
	private static void restartWatchers() {
		activeWatchers.forEach(Thread::interrupt);
		activeWatchers.clear();

		for (Path dir : activeWatchDirs) {
			try {
				WatcherThread watcher = new WatcherThread(dir, null);
				watcher.setDaemon(true);
				watcher.start();
				activeWatchers.add(watcher);
			} catch (IOException e) {
				error("Failed to start watcher for: " + dir, e);
			}
		}

		// 直接传入的 jar/zip：监控其父目录，但只响应该文件本身的事件
		for (Path jar : activeWatchJars) {
			Path parentDir = jar.getParent();
			if (parentDir == null) {
				error("Cannot determine parent directory for jar: " + jar);
				continue;
			}
			try {
				WatcherThread watcher = new WatcherThread(parentDir, jar);
				watcher.setDaemon(true);
				watcher.start();
				activeWatchers.add(watcher);
				info("[Watch] Watching jar: " + jar);
			} catch (IOException e) {
				error("Failed to start watcher for jar: " + jar, e);
			}
		}
	}
	/** 文件监控线程，这个类的设计本身就是可复用的 */
	private static class WatcherThread extends Thread {
		private final Path         root;
		private final Path         specificJar; // 非 null 时只响应该文件的事件（直接传入的 jar 模式）
		private final WatchService watchService;

		WatcherThread(Path root, Path specificJar) throws IOException {
			super("HotSwap-FileWatcher-" + root.getFileName()
			      + (specificJar != null ? "[" + specificJar.getFileName() + "]" : ""));
			this.root = root;
			this.specificJar = specificJar != null ? specificJar.toAbsolutePath() : null;
			this.watchService = FileSystems.getDefault().newWatchService();
		}

		@Override
		public void run() {
			if (DEBUG) log("[Watch] File watcher started for: " + root);
			try {
				registerAll(root);
				while (!Thread.currentThread().isInterrupted()) {
					WatchKey key          = watchService.take();
					Path     triggeredDir = (Path) key.watchable();

					for (WatchEvent<?> event : key.pollEvents()) {
						if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;

						Path context  = (Path) event.context();
						Path fullPath = triggeredDir.resolve(context);

						// specificJar 模式：只响应目标 jar 文件本身，忽略其他一切
						if (specificJar != null) {
							if (fullPath.toAbsolutePath().equals(specificJar)) {
								handleJarChange(fullPath);
							}
							continue;
						}

						// 处理普通类文件变化
						String name = fullPath.toString();
						if (name.endsWith(".class")) {
							handleFileChange(fullPath);
						}

						// 处理 jar / zip 包变化（仅监控 root 一级，子目录不处理）
						if ((name.endsWith(".jar") || name.endsWith(".zip")) && triggeredDir.equals(root)) {
							handleJarChange(fullPath);
						}

						// 处理新目录创建
						if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(fullPath)) {
							if (DEBUG) log("[WATCH] New directory detected: " + fullPath);

							registerAll(fullPath);

							// 立即扫描该目录下现有的 .class 文件（jar/zip 仅在 root 一级处理，新子目录不扫）
							try (Stream<Path> subFiles = Files.walk(fullPath)) {
								subFiles.filter(p -> p.toString().endsWith(".class"))
								 .forEach(p -> {
									 if (DEBUG) log("[WATCH] Found existing file in new dir: " + p);
									 handleFileChange(p);
								 });
							} catch (IOException e) {
								error("Failed to scan new directory: " + fullPath, e);
							}
						}
					}
					if (!key.reset()) {
						if (DEBUG) log("WatchKey no longer valid: " + triggeredDir);
					}
				}
			} catch (IOException e) {
				error("File watcher encountered an error in " + getName(), e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (UncheckedIOException e) {
				error("File watcher encountered an unchecked IO error", e);
			} finally {
				if (DEBUG) log("[Watch] File watcher stopped for: " + root);
				try {
					watchService.close();
				} catch (IOException e) {
					error("Error closing watch service for " + root, e);
				}
			}
		}

		private void registerAll(Path startDir) throws IOException {
			if (!Files.exists(startDir)) {
				if (DEBUG) log("Skipping registration for non-existent directory: " + startDir);
				return;
			}
			try (Stream<Path> stream = Files.walk(startDir)) {
				stream.filter(Files::isDirectory).forEach(dir -> {
					try {
						dir.register(watchService,
						 StandardWatchEventKinds.ENTRY_CREATE,
						 StandardWatchEventKinds.ENTRY_MODIFY);
					} catch (IOException e) {
						if (DEBUG) log("Failed to register directory: " + dir);
					}
				});
			}
		}
	}
	//endregion

	//region Utility Methods
	public static Instrumentation getInst() {
		return inst;
	}
	public static String bytesToHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	/** @see E_Hook.RedefineMode */
	public enum RedefineMode {
		inject,
		lazy_load,
		;
		public static RedefineMode valueOfFail(String inject, RedefineMode def) {
			try {
				return RedefineMode.valueOf(inject);
			} catch (Exception e) {
				return def;
			}
		}
	}
	//endregion
}