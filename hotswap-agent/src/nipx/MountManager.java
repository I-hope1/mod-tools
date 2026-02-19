package nipx;

import jdk.internal.loader.BuiltinClassLoader;
import nipx.util.Utils;

import java.io.IOException;
import java.lang.invoke.*;
import java.lang.ref.WeakReference;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static nipx.HotSwapAgent.*;
import static nipx.HotSwapAgent.info;

public class MountManager {
	//region Caches
	/** 一个目录 对应一个 ClassLoader */
	private static final Map<Path, WeakReference<ClassLoader>>   rootToLoader   = new ConcurrentHashMap<>();
	/**
	 * 维护一个 ClassLoader 索引，用于快速查找某个包应该属于哪个 Loader
	 * <code>PackageName (e.g., "com.example.service") --> WeakReference<ClassLoader></code>
	 */
	static final         Map<String, WeakReference<ClassLoader>> packageLoaders = new ConcurrentHashMap<>();
	//endregion

	//region Method Handles for Class Loading
	public static MethodHandle
	 ucpGetter,
	 ucpGetterForApp,
	 pathGetter,
	 loadersGetter,
	 unopenedUrlsGetter,
	 getLoaderHandle;

	static {
		try {
			Class<?> URLClassPath;
			try {
				// java 9+
				URLClassPath = Class.forName("jdk.internal.loader.URLClassPath");
			} catch (ClassNotFoundException ignored) {
				// java 8
				URLClassPath = Class.forName("sun.misc.URLClassPath");
			}

			ucpGetter = Reflect.IMPL_LOOKUP.findGetter(URLClassLoader.class, "ucp", URLClassPath);
			ucpGetterForApp = Reflect.IMPL_LOOKUP.findGetter(BuiltinClassLoader.class, "ucp", URLClassPath);
			pathGetter = Reflect.IMPL_LOOKUP.findGetter(URLClassPath, "path", ArrayList.class);
			loadersGetter = Reflect.IMPL_LOOKUP.findGetter(URLClassPath, "loaders", ArrayList.class);
			unopenedUrlsGetter = Reflect.IMPL_LOOKUP.findGetter(URLClassPath, "unopenedUrls", ArrayDeque.class);
			Class<?> Loader = Class.forName(URLClassPath.getName() + "$Loader");
			getLoaderHandle = Reflect.IMPL_LOOKUP.findVirtual(URLClassPath, "getLoader", MethodType.methodType(Loader, URL.class));
		} catch (Throwable e) {
			error("[Mount] Failed to initialize method handles.", e);
		}
	}
	//endregion

	/**
	 * 核心方法：定向挂载工作目录
	 * @param targetLoader  目标类加载器（通常是某个 ModClassLoader）
	 * @param classFilePath 发生变化的 .class 文件绝对路径
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static void mountForClass(ClassLoader targetLoader, Path classFilePath) {
		if (getLoaderHandle == null) return;
		if (targetLoader == null || classFilePath == null) return;

		// 溯源：找到该 class 文件所属的 watchDir 根目录
		Path rootDir = findMatchingRoot(classFilePath);
		if (rootDir == null) return;

		try {
			// 获取该加载器的 UCP
			Object ucp;
			if (targetLoader instanceof URLClassLoader) {
				ucp = ucpGetter.invoke(targetLoader);
			} else if (targetLoader instanceof BuiltinClassLoader) {
				ucp = ucpGetterForApp.invoke(targetLoader);
			} else {
				return;
			}
			if (ucp == null) return;

			URL rootUrl = rootDir.toUri().toURL();

			ArrayList<URL> path;
			// 执行原子挂载
			synchronized (ucp) {
				path = (ArrayList<URL>) pathGetter.invoke(ucp);
				// 检查是否已存在，避免重复挂载
				if (path.contains(rootUrl)) return;
			}
			Object fileLoader = getLoaderHandle.invoke(ucp, rootUrl);
			synchronized (ucp) {
				var unopenedUrls = (ArrayDeque<URL>) unopenedUrlsGetter.invoke(ucp);
				var loaders      = (ArrayList) loadersGetter.invoke(ucp);

				// 关键：确保挂载目录的优先级高于原始 Jar 包
				path.add(0, rootUrl);

				// 必须锁住 unopenedUrls，参考 URLClassPath 源码安全性要求
				synchronized (unopenedUrls) {
					unopenedUrls.addFirst(rootUrl);
				}

				synchronized (loaders) {
					loaders.add(0, fileLoader);
				}

				info("[MOUNT] Successfully injected priority path: " + rootUrl
				     + " into " + targetLoader);
			}
		} catch (Throwable t) {
			error("[Mount] Failed to mount path: " + classFilePath, t);
		}
	}

	//region ClassLoader Resolution
	/** 在已注册的监控目录中查找匹配的根路径 */
	static Path findMatchingRoot(Path classFile) {
		// 必须转化为绝对路径进行比较
		Path absoluteClass = classFile.toAbsolutePath();
		for (Path root : HotSwapAgent.activeWatchDirs) {
			if (absoluteClass.startsWith(root.toAbsolutePath())) {
				return root;
			}
		}
		return null;
	}

	/** 启发式算法：为新类寻找"宿主" ClassLoader */
	static ClassLoader findTargetClassLoader(String className) {
		// 优先尝试递归查找外部类 (Outer Class)
		// 输入: a.b.Outer$Inner$Deep
		// 尝试1: 找 a.b.Outer$Inner
		// 尝试2: 找 a.b.Outer
		String candidate = className;

		while (candidate.contains("$")) {
			// 剥离最后一层
			candidate = candidate.substring(0, candidate.lastIndexOf('$'));
			Class<?> found = loadedClassesMap.get(candidate);
			if (found != null) {
				if (DEBUG) log("[FOUND-PARENT] Found parent class " + candidate + " for " + className);
				return found.getClassLoader();
			}
		}

		// 如果外部类都没加载 (极端情况)，尝试同包查找
		// 比如 modtools.android.SomeOtherUtil 可能是加载过的
		if (className.contains(".")) {
			String packageName = className.substring(0, className.lastIndexOf('.'));
			for (Class<?> c : loadedClassesMap.values()) {
				if (c.getName().startsWith(packageName + ".")) {
					return c.getClassLoader();
				}
			}

			// 尝试查之前缓存的 packageLoaders
			WeakReference<ClassLoader> ref = packageLoaders.get(packageName);
			if (ref != null && ref.get() != null) {
				return ref.get();
			}
		}

		return null;
	}
	static ClassLoader findTargetClassLoader(String className, Path classFilePath) {
		// 1. 尝试从已有的包名映射查找
		ClassLoader loader = findTargetClassLoader(className); // 原有的逻辑
		if (loader != null) {
			// 发现踪迹，立即绑定根路径
			bindRoot(classFilePath, loader);
			return loader;
		}

		// 2. 如果包名没见过，检查该文件所属的根目录是否已经绑定过
		Path root = findMatchingRoot(classFilePath);
		if (root != null) {
			WeakReference<ClassLoader> ref = rootToLoader.get(root);
			if (ref != null && ref.get() != null) {
				return ref.get();
			}

			// 3. 如果根目录也没绑定过，执行“深度物理勘探”
			loader = surveyWatchDir(root);
			if (loader != null) {
				rootToLoader.put(root, new WeakReference<>(loader));
				return loader;
			}
		}

		return null;
	}

	/**
	 * 深度物理勘探：扫描该目录下是否有其他类已经被加载
	 * 只要找到一个“邻居”被加载了，就能确定整个目录的归属
	 */
	private static ClassLoader surveyWatchDir(Path root) {
		try (Stream<Path> stream = Files.walk(root)) {
			// 限制扫描深度和数量，避免 IO 爆炸
			Optional<ClassLoader> found = stream
			 .filter(p -> p.toString().endsWith(".class"))
			 .limit(50)
			 .map(p -> {
				 String cName = Utils.getClassName(p);
				 // 看看这个邻居类是否在内存中
				 return cName != null ? loadedClassesMap.get(cName) : null;
			 })
			 .filter(Objects::nonNull)
			 .map(Class::getClassLoader)
			 .findFirst();

			return found.orElse(null);
		} catch (IOException e) {
			return null;
		}
	}

	private static void bindRoot(Path classFilePath, ClassLoader loader) {
		Path root = findMatchingRoot(classFilePath);
		if (root != null && loader != null) {
			rootToLoader.putIfAbsent(root, new WeakReference<>(loader));
		}
	}

	/** 判断 child 是否真的是 parent 的子加载器 */
	static boolean isChildClassLoader(ClassLoader child, ClassLoader parent) {
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
	 * 扫描内存中已加载的类，建立 "包名 -> ClassLoader" 的映射。
	 * 这对于解决 NoClassDefFoundError 至关重要。
	 */
	static void refreshPackageLoaders(Class<?>[] classes) {
		for (Class<?> clazz : classes) {
			ClassLoader cl = clazz.getClassLoader();
			if (cl != null && clazz.getPackage() != null) {
				// 简单策略：记录该包最后一次出现的 ClassLoader
				packageLoaders.put(clazz.getPackage().getName(), new WeakReference<>(cl));
			}
		}
	}

	//endregion
}