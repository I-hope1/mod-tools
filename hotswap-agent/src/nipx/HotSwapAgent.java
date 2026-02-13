package nipx;

import jdk.internal.loader.URLClassPath;
import nipx.annotation.*;
import org.objectweb.asm.ClassReader;

import java.io.*;
import java.lang.instrument.*;
import java.lang.invoke.MethodHandle;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * HotSwap Agent
 * 其由Bootstrap加载，属于java.base模块，可以访问其他java.base模块中的类。
 */
public class HotSwapAgent {
	public static boolean      DEBUG         = Boolean.parseBoolean(System.getenv("nipx.agent.debug"));
	public static boolean      UCP_APPEND    = Boolean.parseBoolean(System.getProperty("nipx.agent.ucp_append", "true"));
	public static int          FILE_SHAKE_MS = 1500;
	public static RedefineMode REDEFINE_MODE;
	public static String[]     HOTSWAP_BLACKLIST;
	public static boolean      ENABLE_HOTSWAP_EVENT;
	public static boolean      LAMBDA_ALIGN;

	private static       Instrumentation     inst;
	private static       Set<Path>           activeWatchDirs = new CopyOnWriteArraySet<>();
	private static final List<WatcherThread> activeWatchers  = new ArrayList<>();

	static final Map<String, byte[]> bytecodeCache = new ConcurrentHashMap<>();

	// 记录磁盘上文件的哈希，用于过滤“伪修改”（时间变了但内容没变）
	private static final Map<String, byte[]> fileDiskHashes = new ConcurrentHashMap<>();

	// 维护一个 ClassLoader 索引，用于快速查找某个包应该属于哪个 Loader
	// Key: PackageName (e.g., "com.example.service"), Value: WeakReference<ClassLoader>
	private static final Map<String, WeakReference<ClassLoader>> packageLoaders = new ConcurrentHashMap<>();

	private static final Set<Path>                pendingChanges = Collections.synchronizedSet(new HashSet<>());
	private static final ScheduledExecutorService scheduler      = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "HotSwap-Scheduler");
		t.setDaemon(true);
		return t;
	});
	private static       ScheduledFuture<?>       scheduledTask;
	public static final  MethodHandle             ucpHandle;

	static {
		try {
			ucpHandle = Reflect.IMPL.findGetter(URLClassLoader.class, "ucp", URLClassPath.class);
		} catch (NoSuchFieldException | IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	static MyClassFileTransformer transformer;
	public static void agentmain(String agentArgs, Instrumentation inst) {
		HotSwapAgent.inst = inst;
		try {
			Class.forName("org.objectweb.asm.ClassReader");
		} catch (ClassNotFoundException e) { }
		init(agentArgs, false);
	}
	public static void init(String agentArgs, boolean reinit) {
		initConfig();

		if (transformer == null) {
			transformer = new MyClassFileTransformer();
			inst.addTransformer(transformer, true); // canRetransform = true
		}
		// 初始化当前所有已加载类的 ClassLoader 映射关系
		refreshPackageLoaders();

		// 解析传入的监控路径 (支持分号或冒号分割)
		Set<Path> newWatchDirs = Arrays.stream(agentArgs.split(File.pathSeparator))
		 .filter(s -> !s.trim().isEmpty())
		 .map(Paths::get)
		 .map(Path::toAbsolutePath) // 统一转绝对路径
		 .collect(Collectors.toSet());

		if (newWatchDirs.isEmpty()) {
			error("No valid watch paths provided.");
			return;
		}

		transformLoaded();

		// 重启监控线程
		if (!activeWatchDirs.equals(newWatchDirs)) {
			info("Watch paths updated: " + newWatchDirs);
			activeWatchDirs = newWatchDirs;
			initializeAgentState();
			restartWatchers();
		} else {
			triggerHotswap();
		}
	}
	public static void initConfig() {
		log("DEBUG: " + DEBUG);
		REDEFINE_MODE = RedefineMode.valueOfFail(System.getProperty("nipx.agent.redefine_mode", "inject"), RedefineMode.inject);
		info("RedefineMode: " + REDEFINE_MODE);
		HOTSWAP_BLACKLIST = System.getProperty("nipx.agent.hotswap_blacklist", "").split(",");
		info("InjectionBlacklist: " + String.join(",", HOTSWAP_BLACKLIST));
		ENABLE_HOTSWAP_EVENT = Boolean.parseBoolean(System.getProperty("nipx.agent.hotswap_event", "false"));
		info("HotSwapEvent: " + ENABLE_HOTSWAP_EVENT);
		LAMBDA_ALIGN = Boolean.parseBoolean(System.getProperty("nipx.agent.lambda_align", "false"));
		info("LambdaAlign: " + LAMBDA_ALIGN);
	}
	private static void transformLoaded() {
		List<Class<?>> candidates = new ArrayList<>();
		for (Class<?> loadedClass : loadedClassesMap.values()) {
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
			// 捕获可能的 LinkageError 或 VerifyError
			error("Critical error during retransform", t);
		}
	}

	/**
	 * 强行将工作目录挂载到指定的 ClassLoader 搜索路径中。
	 * 针对 URLClassLoader (及其子类，如 ModClassLoader) 有效。
	 */
	private static void mountWorkDir(ClassLoader targetLoader, Set<Path> watchDirs) {
		if (!(targetLoader instanceof URLClassLoader)) {
			// 如果 Mindustry 升级了 Java 版本且不再继承 URLClassLoader，这里会失效
			// 但目前的 ModClassLoader 通常都是 URLClassLoader 的子类
			log("[WARN] Target loader is NOT a URLClassLoader: " + targetLoader.getClass().getName());
			return;
		}

		try {
			// 1. 获取 ucp
			URLClassPath ucp = (URLClassPath) ucpHandle.invoke(targetLoader);

			for (Path dir : watchDirs) {
				// 2. 将目录转换为 URL
				// 注意：目录的 URL 必须以 "/" 结尾，toURI().toURL() 会自动处理
				URL url = dir.toUri().toURL();

				// 3. 检查是否已经存在 (避免重复添加)
				boolean alreadyExists = false;
				for (URL existing : ((URLClassLoader) targetLoader).getURLs()) {
					if (existing.equals(url)) {
						alreadyExists = true;
						break;
					}
				}

				if (!alreadyExists) {
					// 4. 调用 addURL
					ucp.addURL(url);
					info("[MOUNT] Successfully mounted directory to ClassLoader: " + dir);
				} else {
					log("[MOUNT] Directory already mounted: " + dir);
				}
			}
		} catch (Throwable e) {
			error("Failed to mount working directory to ClassLoader", e);
		}
	}

	/**
	 * 扫描内存中已加载的类，建立 "包名 -> ClassLoader" 的映射。
	 * 这对于解决 NoClassDefFoundError 至关重要。
	 */
	private static void refreshPackageLoaders() {
		for (Class<?> clazz : inst.getAllLoadedClasses()) {
			ClassLoader cl = clazz.getClassLoader();
			if (cl != null && clazz.getPackage() != null) {
				// 简单策略：记录该包最后一次出现的 ClassLoader
				// 在 Spring Boot 中，这通常会覆盖 AppClassLoader，指向 RestartClassLoader
				packageLoaders.put(clazz.getPackage().getName(), new WeakReference<>(cl));
			}
		}
	}

	private static void restartWatchers() {
		activeWatchers.forEach(Thread::interrupt);
		activeWatchers.clear();

		for (Path dir : activeWatchDirs) {
			if (Files.isDirectory(dir)) {
				try {
					WatcherThread watcher = new WatcherThread(dir);
					watcher.setDaemon(true);
					watcher.start();
					activeWatchers.add(watcher);
				} catch (IOException e) {
					error("Failed to start watcher for: " + dir, e);
				}
			} else {
				error("Skipping invalid directory: " + dir);
			}
		}
	}

	public static ConcurrentHashMap<String, Class<?>> loadedClassesMap = new ConcurrentHashMap<>();
	;
	/**
	 * 处理文件变化的核心逻辑
	 */
	private static void processChanges(Set<Path> changedFiles) {
		refreshPackageLoaders();

		// 获取当前所有已加载类的快照
		loadedClassesMap.clear();
		for (Class<?> c : inst.getAllLoadedClasses()) {
			String   name     = c.getName();
			Class<?> existing = loadedClassesMap.get(name);
			if (existing == null || isChildClassLoader(c.getClassLoader(), existing.getClassLoader())) {
				loadedClassesMap.put(name, c);
			}
		}

		List<ClassDefinition> definitions   = new ArrayList<>();
		int                   skippedCount  = 0;
		int                   injectedCount = 0;

		for (Path path : changedFiles) {
			try {
				String className = getClassName(path);
				if (className == null) continue;

				if (isBlacklisted(className)) {
					if (DEBUG) log("[SKIP-BLACKLIST] " + className);
					continue;
				}

				byte[] bytecode = Files.readAllBytes(path);
				byte[] newHash  = calculateHash(bytecode);

				// 检查磁盘内容是否真的改变（过滤时间戳伪触发）
				byte[] oldDiskHash = fileDiskHashes.get(className);
				if (oldDiskHash != null && Arrays.equals(oldDiskHash, newHash)) {
					log("[SKIP] " + className + " hash=" + bytesToHex(newHash).substring(0, 8) + " old=" + bytesToHex(oldDiskHash).substring(0, 8));
					skippedCount++;
					continue;
				}
				fileDiskHashes.put(className, newHash);

				Class<?> targetClass = loadedClassesMap.get(className);

				if (targetClass != null) {
					// 1. 类已加载：无论模式，都必须执行 redefinition
					log("[MODIFIED] " + className);

					@SuppressWarnings("UnnecessaryLocalVariable")
					byte[] newBytecode = bytecode;
					byte[] oldBytecode = bytecodeCache.get(className);

					// 2. 如果缓存里没有（说明Agent attach晚了，或者还没触发过transform），
					//    主动触发一次 retransform 来“偷”取字节码
					if (oldBytecode == null) {
						try {
							// 这会触发上面的 transformer，填充 bytecodeCache
							inst.retransformClasses(targetClass);
							oldBytecode = bytecodeCache.get(className);
						} catch (Exception e) {
							error("Failed to capture old bytecode for diff: " + className, e);
						}
					}

					// 3. 执行 ASM Diff
					if (oldBytecode != null) {
						// 现在我们是 byte[] vs byte[]，可以检测方法体了
						ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(oldBytecode, newBytecode);
						ClassDiffUtil.logDiff(className, diff);

						if (LAMBDA_ALIGN) {
							LambdaAligner.align(oldBytecode, newBytecode);
						}

						// 可选：如果没有结构性变化且没有方法体变化，是否跳过 redefine？
						// 为了保险起见，只要文件变了，还是建议 redefine，防止漏掉细微变化
					} else {
						log("[WARN] Cannot diff " + className + " (missing old bytecode). Proceeding with redefine.");
					}

					definitions.add(new ClassDefinition(targetClass, newBytecode));

					// 更新缓存，以便下次对比
					bytecodeCache.put(className, newBytecode);
				} else {
					// 2. 类尚未加载：根据 REDEFINE_MODE 处理
					if (REDEFINE_MODE == RedefineMode.inject) {
						// 注入模式：强行让 JVM 认识这个类
						if (injectNewClass(className, bytecode)) {
							injectedCount++;
						}
					} else if (REDEFINE_MODE == RedefineMode.lazy_load) {
						// 延迟加载：确保路径已挂载
						ClassLoader loader = findTargetClassLoader(className);
						if (loader != null) {
							ensureMounted(loader);
							log("[LAZY-LOAD] Path ensured for: " + className);
						}
					}
				}
			} catch (Exception e) {
				error("Failed to process " + path, e);
			}
		}

		// 批量执行重定义（针对已加载类）
		applyRedefinitions(definitions);

		processAnnotations(definitions);

		if (skippedCount > 0) info("Skipped " + skippedCount + " unchanged classes.");
		if (injectedCount > 0) info("Injected " + injectedCount + " new classes.");
	}
	/** 在 applyRedefinitions(definitions) 后调用 */
	private static void processAnnotations(List<ClassDefinition> definitions) {
		if (!ENABLE_HOTSWAP_EVENT) return;

		for (ClassDefinition def : definitions) {
			Class<?> clazz = def.getDefinitionClass();

			// 1. 检查类是否有 @Reloadable (虽然我们只注入了带这个的，但双重检查无害)
			// 注意：这里用反射检查，因为类已经定义好了
			if (clazz.isAnnotationPresent(Reloadable.class)) {

				// 2. 获取所有实例
				List<Object> instances = InstanceTracker.getInstances(clazz);
				if (instances.isEmpty()) continue;

				// 3. 查找回调方法 @OnReload
				Method reloadMethod = null;
				for (Method m : clazz.getDeclaredMethods()) {
					if (m.isAnnotationPresent(OnReload.class)) {
						reloadMethod = m;
						reloadMethod.setAccessible(true);
						break; // 假设只有一个
					}
				}

				if (reloadMethod != null) {
					for (Object obj : instances) {
						try {
							reloadMethod.invoke(obj);
							log("[Reload] Invoked @OnReload on " + obj);
						} catch (Exception e) {
							error("Error invoking @OnReload", e);
						}
					}
				}
			}
		}
	}
	static boolean isBlacklisted(String className) {
		for (String prefix : HOTSWAP_BLACKLIST) {
			if (className.startsWith(prefix)) return true;
		}
		return false;
	}

	/**
	 * 直接定义类，而不是被动加载
	 */
	private static boolean injectNewClass(String className, byte[] bytes) {
		try {
			// 寻找合适的 ClassLoader
			ClassLoader loader = findTargetClassLoader(className);
			if (loader == null) {
				error("Could not find a suitable ClassLoader for new class: " + className);
				return false;
			}
			try {
				// 尝试用标准方式加载。如果成功，说明它在 ClassPath 里。
				// 既然它在 ClassPath 里，我们就不应该用 Unsafe 注入！
				loader.loadClass(className);
				if (DEBUG) log("[SKIP-EXISTING] " + className + " is visible in " + loader);
				return false;
			} catch (ClassNotFoundException ignored) {
				// 只有抛出 ClassNotFoundException，才说明 ClassLoader 真的找不到它
				// 这时我们才有资格用 Unsafe 注入
			}

			Reflect.defineClass(className, bytes, 0, bytes.length, loader, null);

			info("[INJECTED] Successfully defined new class: " + className + " into " + loader);
			return true;

		} catch (LinkageError le) {
			// 类可能已经存在（并发情况），忽略
			log("Class already loaded: " + className);
			return true;
		} catch (Exception e) {
			error("Failed to inject new class: " + className + ". CAUTION: This may cause NoClassDefFoundError.", e);
			return false;
		}
	}
	/**
	 * 分块执行 Redefine，防止其中一个类出错导致所有类失败
	 */
	private static void applyRedefinitions(List<ClassDefinition> definitions) {
		if (definitions.isEmpty()) return;
		try {
			inst.redefineClasses(definitions.toArray(new ClassDefinition[0]));
			info("HotSwap successful: " + definitions.size() + " classes updated.");
		} catch (Throwable t) {
			error("Bulk Redefine failed, switching to individual mode...");
			for (ClassDefinition def : definitions) {
				try {
					inst.redefineClasses(def);
					log("[OK] " + def.getDefinitionClass().getName());
				} catch (Throwable e) {
					error("[FAIL] " + def.getDefinitionClass().getName() + ": " + e.getMessage());
				}
			}
		}
	}

	/**
	 * 启发式算法：为新类寻找“宿主” ClassLoader。
	 */
	private static ClassLoader findTargetClassLoader(String className) {
		// 1. 优先尝试递归查找外部类 (Outer Class)
		// 输入: a.b.Outer$Inner$Deep
		// 尝试1: 找 a.b.Outer$Inner
		// 尝试2: 找 a.b.Outer
		String candidate = className;

		while (candidate.contains("$")) {
			// 剥离最后一层
			candidate = candidate.substring(0, candidate.lastIndexOf('$'));
			Class<?> found = loadedClassesMap.get(candidate);
			if (found != null) {
				log("[FOUND-PARENT] Found parent class " + candidate + " for " + className);
				return found.getClassLoader();
			}
		}

		// 2. 如果外部类都没加载 (极端情况)，尝试同包查找
		// 比如 modtools.android.SomeOtherUtil 可能是加载过的
		if (className.contains(".")) {
			String packageName = className.substring(0, className.lastIndexOf('.'));
			// 遍历所有已加载类，找同包的
			for (Class<?> c : loadedClassesMap.values()) {
				if (c.getName().startsWith(packageName + ".")) {
					// 排除系统类加载器加载的类（如果目标是应用类），除非只有系统类加载器
					// 这里直接返回找到的第一个同包类的加载器
					return c.getClassLoader();
				}
			}

			// 3. 尝试查之前缓存的 packageLoaders (从 Main.refreshPackageLoaders 填充的)
			WeakReference<ClassLoader> ref = packageLoaders.get(packageName);
			if (ref != null && ref.get() != null) {
				return ref.get();
			}
		}

		return null;
	}

	// 判断 child 是否真的是 parent 的子加载器
	private static boolean isChildClassLoader(ClassLoader child, ClassLoader parent) {
		if (child == null) return false;
		if (parent == null) return true; // Bootstrap 是所有人的祖先
		if (child == parent) return false;

		ClassLoader current = child;
		while (current != null) {
			if (current == parent) return true;
			current = current.getParent();
		}
		return false;
	}
	/**
	 * 确保目标 ClassLoader 的 URL 搜索路径包含了我们的监控目录
	 * TODO: ucp没实现
	 */
	private static void ensureMounted(ClassLoader loader) {
		if (!UCP_APPEND) return;

		// 只有 URLClassLoader 及其子类支持 addURL
		if (loader instanceof URLClassLoader) {
			mountWorkDir(loader, activeWatchDirs);
		} else {
			// 如果是普通的 ClassLoader，但在 Java 9+ 中，可以通过反射拿到 ucp (URLClassPath)
			// 这里根据需求可以进一步扩展
			log("[SKIP-MOUNT] Loader is not URLClassLoader: " + loader.getClass().getSimpleName());
		}
	}

	private static String getClassName(Path classFile) {
		try (InputStream is = Files.newInputStream(classFile)) {
			// 解析类文件头，只读不处理，性能开销极小
			ClassReader cr = new ClassReader(is);
			return cr.getClassName().replace('/', '.');
		} catch (IOException e) {
			return null; // 不是合法的 class 文件
		}
	}
	private static void initializeAgentState() {
		info("Scanning files...");
		// 不要盲目 clear，或者 clear 后立即进行差异检查
		for (Path root : activeWatchDirs) {
			try (Stream<Path> walk = Files.walk(root)) {
				walk.filter(p -> p.toString().endsWith(".class")).forEach(path -> {
					String cName = getClassName(path);
					if (cName == null || isBlacklisted(cName)) return;

					try {
						byte[] diskBytes = Files.readAllBytes(path);
						byte[] diskHash  = calculateHash(diskBytes);

						// 获取内存中的字节码（这是 transformLoaded 偷出来的）
						byte[] memBytes = bytecodeCache.get(cName);
						if (memBytes != null) {
							byte[] memHash = calculateHash(memBytes);
							if (!Arrays.equals(diskHash, memHash)) {
								// 发现磁盘和内存不一致，手动加入待处理队列
								log("[INIT-SYNC] " + cName + " is out of sync. Reloading...");
								pendingChanges.add(path);
							}
						}
						// 只有在这里才记录磁盘哈希
						fileDiskHashes.put(cName, diskHash);
					} catch (Exception _) { }
				});
			} catch (IOException _) { }
		}
		// 触发一次同步
		if (!pendingChanges.isEmpty()) triggerHotswap();
	}
	private static void handleFileChange(Path changedFile) {
		pendingChanges.add(changedFile);
		if (scheduledTask != null && !scheduledTask.isDone()) {
			scheduledTask.cancel(false);
		}
		scheduledTask = scheduler.schedule(HotSwapAgent::triggerHotswap, FILE_SHAKE_MS, TimeUnit.MILLISECONDS);
	}

	public static void triggerHotswap() {
		Set<Path> changes;
		synchronized (pendingChanges) {
			if (pendingChanges.isEmpty()) return;
			changes = new HashSet<>(pendingChanges);
			pendingChanges.clear();
		}
		processChanges(changes);
	}

	private static byte[] calculateHash(byte[] data) throws NoSuchAlgorithmException {
		return MessageDigest.getInstance("MD5").digest(data);
	}

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
		void error(String msg);
		void error(String msg, Throwable t);
	}
	public static void log(String msg) { logger.log(msg); }
	public static void info(String msg) { logger.info(msg); }
	public static void error(String msg) { logger.error(msg); }
	public static void error(String msg, Throwable t) { logger.error(msg, t); }

	/**
	 * 文件监控线程，这个类的设计本身就是可复用的
	 */
	private static class WatcherThread extends Thread {
		private final Path         root;
		private final WatchService watchService;

		WatcherThread(Path root) throws IOException {
			super("HotSwap-FileWatcher-" + root.getFileName()); // 给线程一个有意义的名字
			this.root = root;
			this.watchService = FileSystems.getDefault().newWatchService();
		}

		@Override
		public void run() {
			info("File watcher started for: " + root);
			try {
				registerAll(root);
				while (!Thread.currentThread().isInterrupted()) {
					WatchKey key          = watchService.take();
					Path     triggeredDir = (Path) key.watchable();

					for (WatchEvent<?> event : key.pollEvents()) {
						log("[EVENT] " + triggeredDir + ": " + event.kind());
						if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;

						Path context  = (Path) event.context();
						Path fullPath = triggeredDir.resolve(context);

						if (context.toString().endsWith(".class")) {
							handleFileChange(triggeredDir.resolve(context));
						}
						if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(fullPath)) {
							registerAll(fullPath);
						}
					}
					if (!key.reset()) {
						log("WatchKey no longer valid: " + triggeredDir);
					}
				}
			} catch (IOException e) {
				error("File watcher encountered an error in " + getName(), e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (UncheckedIOException e) {
				error("File watcher encountered an unchecked IO error, possibly due to a directory being deleted during a scan.", e);
			} finally {
				info("File watcher stopped for: " + root);
				try {
					watchService.close();
				} catch (IOException e) {
					error("Error closing watch service for " + root, e);
				}
			}
		}

		private void registerAll(Path startDir) throws IOException {
			if (!Files.exists(startDir)) {
				log("Skipping registration for non-existent directory: " + startDir);
				return;
			}
			try (Stream<Path> stream = Files.walk(startDir)) {
				stream.filter(Files::isDirectory).forEach(dir -> {
					try {
						dir.register(watchService,
						 StandardWatchEventKinds.ENTRY_CREATE,
						 StandardWatchEventKinds.ENTRY_MODIFY);
					} catch (IOException e) {
						log("Failed to register directory: " + dir);
					}
				});
			}
		}
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
}