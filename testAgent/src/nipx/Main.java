package nipx;

import jdk.internal.misc.Unsafe;

import java.io.*;
import java.lang.instrument.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * 增强版 HotSwap Agent
 * 修复了 NoClassDefFoundError 问题：支持动态注入新类（匿名内部类/Lambda）。
 */
public class Main {
	private static final boolean DEBUG         = Boolean.parseBoolean(System.getProperty("nipx.agent.debug", "false"));
	private static final boolean UCP_APPEND    = Boolean.parseBoolean(System.getProperty("nipx.agent.ucp_append", "true"));
	public static final  int     FILE_SHAKE_MS = 600;

	private static       Instrumentation     inst;
	private static       Set<Path>           activeWatchDirs = new CopyOnWriteArraySet<>();
	private static final List<WatcherThread> activeWatchers  = new ArrayList<>();

	// 缓存类名对应的哈希，防止重复加载
	private static final Map<String, byte[]> classHashes    = new ConcurrentHashMap<>();
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

	public static void agentmain(String agentArgs, Instrumentation inst) {
		Main.inst = inst;

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
			// 1. 获取 protected void addURL(URL url) 方法
			Method addUrlMethod = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
			addUrlMethod.setAccessible(true);

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
					addUrlMethod.invoke(targetLoader, url);
					info("[MOUNT] Successfully mounted directory to ClassLoader: " + dir);
				} else {
					// log("[MOUNT] Directory already mounted: " + dir);
				}
			}
		} catch (Exception e) {
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
					info("Started watcher on: " + dir);
				} catch (IOException e) {
					error("Failed to start watcher for: " + dir, e);
				}
			} else {
				error("Skipping invalid directory: " + dir);
			}
		}
	}

	private static void processChanges(Set<Path> changedFiles) {
		// 更新 ClassLoader 映射，防止遗漏新加载的 Loader
		refreshPackageLoaders();

		// 优先保留“子加载器”加载的类（业务类通常在子加载器）
		Map<String, Class<?>> loadedClassesMap = new HashMap<>();
		for (Class<?> c : inst.getAllLoadedClasses()) {
			String   name     = c.getName();
			Class<?> existing = loadedClassesMap.get(name);
			// 如果 map 里还没这个类，或者新来的类是 existing 的子加载器加载的（说明更具体），则替换
			if (existing == null || isChildClassLoader(c.getClassLoader(), existing.getClassLoader())) {
				loadedClassesMap.put(name, c);
			}
		}

		List<ClassDefinition> definitions = new ArrayList<>();

		int skippedCount  = 0;
		int injectedCount = 0;

		for (Path path : changedFiles) {
			try {
				String className = getClassName(path);
				if (className == null) continue;

				byte[] bytecode = Files.readAllBytes(path);
				byte[] newHash  = calculateHash(bytecode);

				// 检查磁盘文件的上一版本哈希
				boolean isContentChanged = true;
				byte[]  oldDiskHash      = fileDiskHashes.get(className);
				if (oldDiskHash != null && Arrays.equals(oldDiskHash, newHash)) {
					isContentChanged = false;
				}

				// 更新磁盘哈希缓存
				fileDiskHashes.put(className, newHash);

				Class<?> targetClass = loadedClassesMap.get(className);

				if (targetClass != null) {
					if (!isContentChanged) {
						// 双重检查内存哈希
						byte[] oldMemHash = classHashes.get(className);
						if (oldMemHash != null && Arrays.equals(oldMemHash, newHash)) {
							skippedCount++;
							continue;
						}
					}
					// 修改已存在的类 -> Redefine
					classHashes.put(className, newHash);
					log("[MODIFIED] " + className + " (Loader: " + targetClass.getClassLoader() + ")");
					definitions.add(new ClassDefinition(targetClass, bytecode));
				} else {
					// 即使 isContentChanged 为 false，只要它没被加载，我们就要尝试注入！
					// 因为它可能只存在于 WatchDir，而不在 ClassPath 里。

					// 为了避免重复注入(造成 LinkageError)，先检查一下是否真的未加载
					// (loadedClassesMap 已经是最新快照了)

					// 尝试注入
					ClassLoader targetLoader = findTargetClassLoader(className);
					if (targetLoader != null) {
						if (isContentChanged) {
							log("[NEW-CHANGE] Injecting modified new class: " + className);
						} else {
							// 这是修复你报错的关键：内容没变，但是为了让 JVM 看到它，必须注入
							// debug 日志可以降级，避免刷屏
							// log("[NEW-STALE] Injecting unloaded existing class: " + className);
						}
						if (injectNewClass(className, bytecode)) {
							classHashes.put(className, newHash);
							injectedCount++;
						}
					} else {
						// 这是一个还未被使用的类，且找不到亲戚，直接忽略，
						// 等 JVM 自然加载它时，会读取到最新的磁盘文件。
						// log("Skipping unloaded class with no loader context: " + className);
					}
				}
			} catch (Exception e) {
				error("Failed to process " + path, e);
			}
		}

		if (skippedCount > 0) {
			info("Skipped " + skippedCount + " files (timestamp changed but content identical).");
		}
		if (injectedCount > 0) {
			info("Injected/Ensured " + injectedCount + " unloaded classes.");
		}

		// 批量执行重定义
		if (!definitions.isEmpty()) {
			try {
				inst.redefineClasses(definitions.toArray(new ClassDefinition[0]));
				info("HotSwap success: " + definitions.size() + " classes redefined.");
			} catch (Throwable t) {
				error("Redefinition failed.", t);
				// 降级策略：尝试单个重定义，避免一个失败炸全家
				for (ClassDefinition def : definitions) {
					try {
						inst.redefineClasses(def);
					} catch (Throwable singleEx) {
						error("Failed individual redefine: " + def.getDefinitionClass().getName(), singleEx);
					}
				}
			}
		}
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

			Unsafe.getUnsafe().defineClass(className, bytes, 0, bytes.length, loader, null);

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
	 * 启发式算法：为新类寻找“宿主” ClassLoader。
	 */
	private static ClassLoader findTargetClassLoader(String className) {
		// 1. 优先尝试递归查找外部类 (Outer Class)
		// 输入: a.b.Outer$Inner$Deep
		// 尝试1: 找 a.b.Outer$Inner
		// 尝试2: 找 a.b.Outer
		String                candidate = className;
		Map<String, Class<?>> loadedMap = new HashMap<>();
		// 建立临时索引，避免多次遍历数组
		for (Class<?> c : inst.getAllLoadedClasses()) {
			loadedMap.put(c.getName(), c);
		}

		while (candidate.contains("$")) {
			// 剥离最后一层
			candidate = candidate.substring(0, candidate.lastIndexOf('$'));
			Class<?> found = loadedMap.get(candidate);
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
			for (Class<?> c : inst.getAllLoadedClasses()) {
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

	private static String getClassName(Path classFile) {
		for (Path rootDir : activeWatchDirs) {
			if (classFile.startsWith(rootDir)) {
				Path   relativePath = rootDir.relativize(classFile);
				String pathStr      = relativePath.toString();
				// 移除 .class 后缀并转换分隔符
				if (pathStr.endsWith(".class")) {
					return pathStr.substring(0, pathStr.length() - 6).replace(File.separatorChar, '.');
				}
			}
		}
		return null;
	}

	private static void initializeAgentState() {
		info("Scanning initial file state...");
		fileDiskHashes.clear();
		classHashes.clear();

		// 1. 扫描所有监控目录下的 class 文件，记录初始 MD5
		for (Path root : activeWatchDirs) {
			try {
				Files.walk(root)
				 .filter(p -> p.toString().endsWith(".class"))
				 .forEach(path -> {
					 try {
						 String className = getClassName(path);
						 if (className != null) {
							 byte[] bytes = Files.readAllBytes(path);
							 fileDiskHashes.put(className, calculateHash(bytes));
						 }
					 } catch (Exception e) {
						 // ignore
					 }
				 });
			} catch (IOException e) {
				error("Failed to scan root: " + root);
			}
		}

		info("Indexed " + fileDiskHashes.size() + " classes on disk.");

		// 内存状态快照
		classHashes.clear();
		inst.addTransformer(new ClassFileTransformer() {
			@Override
			public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			                        ProtectionDomain protectionDomain, byte[] classfileBuffer) {
				if (className == null) return null;
				String dotName = className.replace('/', '.');
				try {
					classHashes.putIfAbsent(dotName, calculateHash(classfileBuffer));
				} catch (Exception e) { /* ignore */ }
				return null;
			}
		}, true);

		try {
			// 仅对当前已加载的非 JDK 类进行状态快照
			List<Class<?>> targets = new ArrayList<>();
			for (Class<?> c : inst.getAllLoadedClasses()) {
				if (inst.isModifiableClass(c) && c.getClassLoader() != null) {
					targets.add(c);
				}
			}
			if (!targets.isEmpty()) {
				inst.retransformClasses(targets.toArray(new Class[0]));
			}
		} catch (Exception e) {
			error("Init failed", e);
		}
	}

	private static void handleFileChange(Path changedFile) {
		pendingChanges.add(changedFile);
		if (scheduledTask != null && !scheduledTask.isDone()) {
			scheduledTask.cancel(false);
		}
		scheduledTask = scheduler.schedule(Main::triggerHotswap, FILE_SHAKE_MS, TimeUnit.MILLISECONDS);
	}

	private static void triggerHotswap() {
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

	private static void log(String msg) { if (DEBUG) System.out.println("[NIPX] " + msg); }
	private static void info(String msg) { System.out.println("[NIPX] " + msg); }
	private static void error(String msg) { System.err.println("[NIPX] " + msg); }
	private static void error(String msg, Throwable t) {
		System.err.println("[NIPX] " + msg);
		t.printStackTrace(System.err);
	}

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
}