package nipx;

import nipx.annotation.*;
import nipx.util.*;

import java.io.*;
import java.lang.instrument.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import java.util.zip.*;

import static nipx.MountManager.*;

/**
 * HotSwap Agent
 * 其由AppLoader加载，但不属于java.base模块
 */
@SuppressWarnings("StringTemplateMigration")
public class HotSwapAgent {
	//region Configuration Fields
	public static boolean      DEBUG              = Boolean.parseBoolean(System.getenv("nipx.agent.debug"));
	public static boolean      UCP_APPEND         = Boolean.parseBoolean(System.getProperty("nipx.agent.ucp_append", "true"));
	public static int          FILE_SHAKE_MS      = 1200;
	public static RedefineMode REDEFINE_MODE;
	public static String[]     HOTSWAP_BLACKLIST;
	public static boolean      RETRANSFORM_LOADED = Boolean.parseBoolean(System.getProperty("nipx.agent.retransform_loaded", "false"));
	public static boolean      ENABLE_HOTSWAP_EVENT;
	public static boolean      LAMBDA_ALIGN;
	public static boolean      UI_HOOK;
	//endregion

	//region Core State Management
	static               Instrumentation     inst;
	static final         Set<Path>           activeWatchDirs = new CopyOnWriteArraySet<>();
	/** 直接作为 watch 路径传入的 jar/zip 文件（区别于目录内扫描到的） */
	static final         Set<Path>           activeWatchJars = new CopyOnWriteArraySet<>();
	private static final List<WatcherThread> activeWatchers  = new CopyOnWriteArrayList<>();

	/** 在不使用retransform的情况下，确保旧bytecode正确的唯一方法 */
	static final Map<String, byte[]> bytecodeCache = new ConcurrentHashMap<>();

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
	//endregion

	//region Agent Initialization
	static MyClassFileTransformer transformer;

	public static void agentmain(String agentArgs, Instrumentation inst) {
		HotSwapAgent.inst = inst;
		try {
			init(agentArgs, false);
		} catch (Throwable t) {
			error("Critical error during agent initialization", t);
		}
	}

	public static void init(String agentArgs, boolean reinit) {
		initConfig();

		if (transformer == null) {
			transformer = new MyClassFileTransformer();
			inst.addTransformer(transformer, true);
		}
		var loadedClasses = inst.getAllLoadedClasses();
		refreshPackageLoaders(loadedClasses);

		if (RETRANSFORM_LOADED) {
			retransformLoaded(loadedClasses);
		}
		if (UI_HOOK) {
			LambdaRef.init();
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

	public static void initConfig() {
		info("DEBUG: " + DEBUG);
		REDEFINE_MODE = RedefineMode.valueOfFail(System.getProperty("nipx.agent.redefine_mode", "inject"), RedefineMode.inject);
		info("Redefine Mode: " + REDEFINE_MODE);
		HOTSWAP_BLACKLIST = System.getProperty("nipx.agent.hotswap_blacklist", "").split(",");
		info("Injection Blacklist: " + String.join(",", HOTSWAP_BLACKLIST));
		ENABLE_HOTSWAP_EVENT = Boolean.parseBoolean(System.getProperty("nipx.agent.hotswap_event", "false"));
		info("HotSwap Event: " + ENABLE_HOTSWAP_EVENT);
		LAMBDA_ALIGN = Boolean.parseBoolean(System.getProperty("nipx.agent.lambda_align", "true"));
		info("Lambda Align: " + LAMBDA_ALIGN);
		if (LAMBDA_ALIGN) {
			info("Lambda Alignment ENABLED. Warning: This may cause logical shifts if lambdas are reordered.");
		}
		UI_HOOK = Boolean.parseBoolean(System.getProperty("nipx.agent.ui_hook", "false"));
		info("UI Hook: " + UI_HOOK);
		info("Structural HotSwap Supported: " + isEnhancedHotswapEnabled());
	}
	//endregion

	//region Class Retransformation
	/** 对外api，刷新已加载的类 */
	public static void retransformLoaded() {
		retransformLoaded(inst.getAllLoadedClasses());
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

		List<ClassDefinition> definitions   = new ArrayList<>();
		int                   skippedCount  = 0;
		int                   injectedCount = 0;

		for (Path path : changedFiles) {
			if (DEBUG) log("Processing changes: " + path);
			try {
				byte[] bytecode  = Files.readAllBytes(path);
				String className = Utils.getClassNameASM(bytecode);
				if (className == null) {
					skippedCount++;
					error("[SKIP] No className: " + path);
					continue;
				}

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
					fileDiskHashes.put(classNameKey, newHash);
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
							newBytecode = LambdaAligner.align(oldBytecode, newBytecode);
						}

						ClassDiffUtil.ClassDiff diff = ClassDiffUtil.diff(oldBytecode, newBytecode);
						ClassDiffUtil.logDiff(className, diff);
						if (diff.hierarchyChanged) {
							error("REJECTED: Class hierarchy change detected for " + className + ". JBR/DCEVM does not support changing superclass/interfaces reliably. Please RESTART application.");
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
					} else {
						log("[WARN] Cannot diff " + className + " (missing old bytecode). Proceeding with redefine.");
					}

					definitions.add(new ClassDefinition(targetClass, newBytecode));
					bytecodeCache.put(className, newBytecode);
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
						}
					} else if (REDEFINE_MODE == RedefineMode.lazy_load && UCP_APPEND) {
						ClassLoader loader = findTargetClassLoader(className, clHintPath);
						// lazy_load + jar：ClassLoader 推断用虚拟路径（clHintPath），
						// 实际挂载用真实 tmpDir 路径，否则 ClassLoader 找不到 class 文件
						Path mountPath = tmpToJar.containsKey(path) ? path : clHintPath;
						if (mountPath != null) mountForClass(loader, mountPath);
						bytecodeCache.put(className, bytecode);
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

			if (!clazz.isAnnotationPresent(Reloadable.class)) continue;

			Method reloadMethod = null;
			for (Method m : clazz.getDeclaredMethods()) {
				if (m.isAnnotationPresent(OnReload.class)) {
					reloadMethod = m;
					reloadMethod.setAccessible(true);
					break;
				}
			}
			if (reloadMethod == null) continue;
			info("[Reload] Found @OnReload on " + clazz);

			if (Modifier.isStatic(reloadMethod.getModifiers())) {
				try {
					reloadMethod.invoke(null);
					if (DEBUG) log("[Reload] Invoked @OnReload static method.");
				} catch (Exception e) {
					error("Error invoking @OnReload", e);
				}
				if (DEBUG) log("[Reload] Invoked @OnReload on " + clazz);
				continue;
			}

			var instances = InstanceTracker.getInstances(clazz);
			if (instances.isEmpty()) continue;


			for (Object obj : instances) {
				try {
					reloadMethod.invoke(obj);
					if (DEBUG) log("[Reload] Invoked @OnReload on " + obj);
				} catch (Exception e) {
					error("Error invoking @OnReload", e);
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

	static byte[] fetchOriginalBytecode(Class<?> clazz) {
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
	 * 分块执行 Redefine，防止其中一个类出错导致所有类失败
	 */
	private static void applyRedefinitions(List<ClassDefinition> definitions) {
		if (definitions.isEmpty()) return;
		try {
			inst.redefineClasses(definitions.toArray(new ClassDefinition[0]));
			info("HotSwap successful: " + definitions.size() + " classes redefined.");
		} catch (Throwable t) {
			error("Bulk Redefine failed, switching to individual mode...", t);
			for (ClassDefinition def : definitions) {
				try {
					inst.redefineClasses(def);
					if (DEBUG) log("[OK] " + def.getDefinitionClass().getName());
				} catch (Throwable e) {
					error("[FAIL] " + def.getDefinitionClass().getName(), e);
				}
			}
		}
	}
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
		if (!pendingChanges.isEmpty()) triggerHotswapWith(classes);
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
		triggerHotswapWith(inst.getAllLoadedClasses());
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
						if (fullPath.toString().endsWith(".class")) {
							handleFileChange(fullPath);
						}

						// 处理 jar / zip 包变化（仅监控 root 一级，子目录不处理）
						String name = fullPath.toString();
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