package nipx;

import java.io.*;
import java.lang.instrument.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * 一个支持多路径、集成了文件监控功能的、常驻式的 HotSwap Agent.
 * 它会自动监控指定目录下的.class文件变化，并对已加载的类进行热更新。
 */
public class Main {
	private static final boolean DEBUG         = false;
	private static final boolean FORCE_RESTART = true;

	// Agent 核心状态
	private static       Instrumentation     inst;
	// [MODIFIED] 从单个Path变为Set，以支持多路径
	private static       Set<Path>           activeWatchDirs = new CopyOnWriteArraySet<>();
	// [MODIFIED] 管理一个WatcherThread列表，每个路径一个
	private static final List<WatcherThread> activeWatchers  = new ArrayList<>();

	// 存储已加载类的字节码哈希，用于比对变更
	private static final Map<String, byte[]> classHashes = new ConcurrentHashMap<>();
	private static final Map<String, byte[]> unloadedClasses = new ConcurrentHashMap<>();

	// 变更处理调度器，用于"防抖"
	private static final Set<Path>                pendingChanges = Collections.synchronizedSet(new HashSet<>());
	private static final ScheduledExecutorService scheduler      = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "HotSwap-Scheduler");
		t.setDaemon(true);
		return t;
	});
	private static       ScheduledFuture<?>       scheduledTask;

	/**
	 * Agent 的主入口方法.
	 * @param agentArgs 监控的类路径根目录, 多个路径使用系统路径分隔符 (';' on Windows, ':' on Linux) 分隔.
	 * @param inst      由 JVM 传入的 Instrumentation 实例.
	 */
	public static void agentmain(String agentArgs, Instrumentation inst) {
		Main.inst = inst;

		// [MODIFIED] 解析多个路径
		Set<Path> newWatchDirs = Arrays.stream(agentArgs.split(File.pathSeparator))
		 .filter(s -> s != null && !s.trim().isEmpty())
		 .map(Paths::get)
		 .collect(Collectors.toSet());

		if (newWatchDirs.isEmpty()) {
			error("No valid watch paths provided.");
			return;
		}

		// 检查是否有任何一个路径不是目录
		for (Path dir : newWatchDirs) {
			if (!Files.isDirectory(dir)) {
				error("Provided path is not a directory: " + dir);
				return;
			}
		}

		// [MODIFIED] 比较新旧路径集合，如果不同则重启监控
		if (FORCE_RESTART || !activeWatchDirs.equals(newWatchDirs)) {
			info("Watch paths have changed. Restarting watchers...");
			info("New paths: " + newWatchDirs);
			activeWatchDirs = newWatchDirs;
			initializeAgentState(); // 重新初始化哈希表
			restartWatchers(); // 重启所有监控
		} else {
			info("Watch paths are unchanged. Manually triggering hotswap for pending changes.");
			triggerHotswap();
		}
	}

	/**
	 * 停止所有当前正在运行的监控线程，并为新的路径集合启动新的线程。
	 */
	private static void restartWatchers() {
		// 1. 停止所有旧的 Watcher
		if (!activeWatchers.isEmpty()) {
			info("Stopping " + activeWatchers.size() + " existing watcher(s)...");
			activeWatchers.forEach(Thread::interrupt);
			activeWatchers.clear();
		}

		// 2. 为每个新路径启动一个新的 Watcher
		for (Path dir : activeWatchDirs) {
			try {
				WatcherThread watcher = new WatcherThread(dir);
				watcher.setDaemon(true);
				watcher.start();
				activeWatchers.add(watcher);
			} catch (IOException e) {
				error("Failed to start file watcher for: " + dir, e);
			}
		}
	}

	/**
	 * 核心处理逻辑：比对变更的类文件，并对已修改的类执行重定义。
	 */
	private static void processChanges(Set<Path> changedFiles) {
		Map<String, Class<?>> loadedClassesMap = Arrays.stream(inst.getAllLoadedClasses())
		 .collect(Collectors.toMap(Class::getName, c -> c, (a, b) -> a));

		List<ClassDefinition> definitions = new ArrayList<>();

		for (Path path : changedFiles) {
			try {
				// [MODIFIED] 需要找到这个文件属于哪个监控根目录
				String className = getClassName(path);
				if (className == null) {
					log("Could not determine class name for path: " + path);
					continue;
				}

				Class<?> targetClass = loadedClassesMap.get(className);

				if (targetClass != null && Files.exists(path)) {
					// 如果有写锁，则等待
					// while (!Files.isReadable(path)) { Thread.sleep(100); }
					byte[] newBytecode = Files.readAllBytes(path);
					byte[] newHash     = calculateHash(newBytecode);
					byte[] oldHash     = classHashes.get(className);

					if (oldHash == null || !Arrays.equals(oldHash, newHash)) {
						classHashes.put(className, newHash);
						log("[MODIFIED] " + className);
						definitions.add(new ClassDefinition(targetClass, newBytecode));
					} else {
						log("[UNCHANGED] Hash is the same for: " + className);
					}
				} else if (targetClass == null) {
					log("[NOT LOADED] Class file changed but class is not loaded: " + className);
				}
			} catch (IOException | NoSuchAlgorithmException e) {
				error("Failed to process file: " + path, e);
			}
		}

		if (definitions.isEmpty()) {
			info("No loaded classes were modified. Hotswap finished.");
			return;
		}

		try {
			inst.redefineClasses(definitions.toArray(new ClassDefinition[0]));

			info("Successfully redefined " + definitions.size() + " classes. Hotswap finished.");
		} catch (ClassNotFoundException | UnmodifiableClassException e) {
			error("Error redefining " + definitions.size() + " classes.", e);
		} catch (UnsupportedOperationException e) {
			// [MODIFIED] 增强错误报告：当批量重定义失败时，尝试逐个重定义以找出问题类
			error("Failed to redefine classes due to incompatible changes (e.g., method signature change).", e);
			info("Attempting to redefine one by one to identify the faulty class...");
			for (ClassDefinition def : definitions) {
				try {
					inst.redefineClasses(def);
					info(" -> Successfully redefined: " + def.getDefinitionClass().getName());
				} catch (Exception ex) {
					error(" -> FAILED to redefine: " + def.getDefinitionClass().getName(), ex);
				}
			}
		}catch (Throwable t) {
			error("An unexpected error occurred during redefinition.", t);
		}
	}

	// [NEW] 辅助方法，用于从一个绝对路径找到它对应的监控根目录，并计算类名
	private static String getClassName(Path classFile) {
		for (Path rootDir : activeWatchDirs) {
			if (classFile.startsWith(rootDir)) {
				Path   relativePath = rootDir.relativize(classFile);
				String pathStr      = relativePath.toString();
				return pathStr.substring(0, pathStr.length() - ".class".length()).replace(File.separatorChar, '.');
			}
		}
		return null; // 如果文件不属于任何一个监控目录
	}

	// ================== 以下方法与单路径版本基本一致 ==================

	/**
	 * 文件监控线程，这个类的设计本身就是可复用的，无需修改。
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
						if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;

						Path context  = (Path) event.context();
						Path fullPath = triggeredDir.resolve(context);

						// 对删除事件的处理
						WatchEvent.Kind<?> kind = event.kind();

						if (kind == StandardWatchEventKinds.ENTRY_CREATE || kind == StandardWatchEventKinds.ENTRY_MODIFY) {
							if (context.toString().endsWith(".class")) {
								handleFileChange(fullPath);
							} else if (Files.isDirectory(fullPath)) {
								registerAll(fullPath);
							}
						} else if (kind == StandardWatchEventKinds.ENTRY_DELETE && context.toString().endsWith(".class")) {
							info("[DELETED] Class file was deleted: " + fullPath);
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
			try (Stream<Path> stream = Files.walk(startDir)) {
				stream.filter(Files::isDirectory).forEach(dir -> {
					try {
						dir.register(watchService,
						 StandardWatchEventKinds.ENTRY_CREATE,
						 StandardWatchEventKinds.ENTRY_MODIFY,
						 StandardWatchEventKinds.ENTRY_DELETE);
					} catch (IOException e) {
						log("Failed to register directory: " + dir);
					}
				});
			}
		}
	}
	/*  这个 Transformer 用于转换还未加载类的字节码 */
	private static final ClassFileTransformer CLASS_TRANSFORMER      = new ClassFileTransformer() {
		public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
		                        ProtectionDomain protectionDomain, byte[] classfileBuffer) {
			// className 使用 internal name (e.g., "java/lang/String")，需要转换
			String dotClassName = className.replace('/', '.');
			return unloadedClasses.remove(dotClassName);
		}
	};
	/**
	 * [NEW] 这个 Transformer 只用于在 Agent 启动时捕获所有已加载类的原始字节码。
	 * 它不进行任何实际的类转换，只是一个“窃听器”。
	 */
	private static final ClassFileTransformer INITIAL_STATE_CAPTURER = new ClassFileTransformer() {
		@Override
		public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
		                        ProtectionDomain protectionDomain, byte[] classfileBuffer) {
			// className 使用 internal name (e.g., "java/lang/String")，需要转换
			String dotClassName = className.replace('/', '.');

			// info(dotClassName);
			// 只有当这个类我们还没记录过哈希时，才记录它
			// 这是为了防止重复计算和潜在的并发问题
			classHashes.computeIfAbsent(dotClassName, key -> {
				try {
					log("Captured initial state for: " + key);
					return calculateHash(classfileBuffer);
				} catch (NoSuchAlgorithmException e) {
					error("Failed to calculate initial hash for " + key, e);
					return null; // or some other marker
				}
			});

			// 我们不修改任何字节码，返回 null 表示不进行转换
			return null;
		}
	};


	/**
	 * [MODIFIED] 使用 ClassFileTransformer 和 retransformClasses 来精确地初始化类哈希状态。
	 */
	private static void initializeAgentState() {
		info("Initializing class state using in-memory bytecode...");
		classHashes.clear();

		// inst.addTransformer(CLASS_TRANSFORMER, true);
		inst.addTransformer(INITIAL_STATE_CAPTURER, true);

		try {
			List<Class<?>> classesToRetransform = new ArrayList<>();
			for (Class<?> clazz : inst.getAllLoadedClasses()) {
				// 筛选出可重定义的、且属于我们可能关心的类加载器的类
				if (inst.isModifiableClass(clazz) && clazz.getClassLoader() != null) {
					classesToRetransform.add(clazz);
				}
			}
			if (!classesToRetransform.isEmpty()) {
				inst.retransformClasses(classesToRetransform.toArray(new Class[0]));
				info("Captured initial state for " + classHashes.size() + " modifiable classes.");
			} else {
				info("No modifiable classes found to capture initial state from.");
			}

		} catch (UnmodifiableClassException e) {
			// 这个异常理论上不应该发生，因为我们已经用 isModifiableClass 检查过了
			error("Failed during initial state capture.", e);
		} finally {
			// 不行，还有未加载的类，没计算hash
			// // 初始化完成后，这个 Transformer 的使命就结束了，移除它
			// inst.removeTransformer(INITIAL_STATE_CAPTURER);
		}
	}

	private static void handleFileChange(Path changedFile) {
		pendingChanges.add(changedFile);
		if (scheduledTask != null && !scheduledTask.isDone()) {
			scheduledTask.cancel(false);
		}
		scheduledTask = scheduler.schedule(Main::triggerHotswap, 500, TimeUnit.MILLISECONDS);
	}

	private static void triggerHotswap() {
		Set<Path> changesToProcess;
		synchronized (pendingChanges) {
			if (pendingChanges.isEmpty()) return;
			changesToProcess = new HashSet<>(pendingChanges);
			pendingChanges.clear();
		}
		info("\nDetected " + changesToProcess.size() + " file change(s). Starting hotswap...");
		processChanges(changesToProcess);
	}

	private static byte[] calculateHash(byte[] bytecode) throws NoSuchAlgorithmException {
		return MessageDigest.getInstance("MD5").digest(bytecode);
	}

	private static void log(String message) { if (DEBUG) System.out.println("[HotSwapAgent] " + message); }
	private static void info(String message) { System.out.println("[HotSwapAgent] " + message); }
	private static void error(String message) { System.err.println("[HotSwapAgent] " + message); }
	private static void error(String message, Throwable t) {
		System.err.println("[HotSwapAgent] " + message);
		t.printStackTrace(System.err);
	}
}