package modtools.unsupported;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.util.Log;
import arc.util.OS;
import mindustry.game.EventType.ClientLoadEvent;
import modtools.IntVars;
import modtools.events.E_Hook;
import modtools.jsfunc.reflect.UNSAFE;
import modtools.utils.io.FileUtils;
import modtools.utils.reflect.ClassUtils;
import remold.RemoldAgent;
import remold.jni.helper.MasterKey;
import remold.ref.UpdateRef;
import remold.util.LibTool;

import java.io.File;

/**
 * 负责执行热更新的核心逻辑。
 * 这个类是无状态的，可以被重复调用。
 */
public class HotSwapManager {
	private static final String  AGENT_NAME          = "remold-agent";
	private static final String  AGENT_RESOURCE_PATH = "/libs/" + AGENT_NAME + ".jar";
	private static       String  agentPathCache      = null;
	private static       boolean initialized         = false;


	public static void start() throws Throwable {
		if (!initialized) {
			/* 模块open（MyReflect里）还不够，还得exports */
			UNSAFE.addExports(Object.class, "jdk.internal.misc");
			UNSAFE.addExports(Object.class, "jdk.internal.reflect");
			UNSAFE.addExports(Object.class, "jdk.internal.loader");
			if (loaded()) setupAgent();
			E_Hook.hot_swap_watch_paths.onChange(() -> {
				if (loaded()) {
					setupAgent();
					RemoldAgent.init(E_Hook.hot_swap_watch_paths.getArray().toString(File.pathSeparator), true);
				}
			});

			// HotSwapController.init();
			initialized = true;
		}
		hotswap(E_Hook.hot_swap_watch_paths.getArray().toString(File.pathSeparator));
		if (loaded()) setupAgent();
		flushDeferredRemovals();
		Events.on(ClientLoadEvent.class, _ -> flushDeferredRemovals());
		try {
			Fi lib = IntVars.libs.child(mapLibraryName("tool"));
			System.setProperty("remold.path.libtool", FileUtils.copyToTmp(lib).absolutePath());
			LibTool.init();
		} catch (Throwable e) {
			RemoldAgent.error("[HotSwapManager] Failed to load libtool.", e);
		}
	}
	/**
	 * Maps a platform independent library name to a platform dependent name.
	 * @see SharedLibraryLoader#mapLibraryName(String)
	 */
	public static String mapLibraryName(String libraryName) {
		if (OS.isWindows) return libraryName + (OS.is64Bit ? "64.dll" : ".dll");
		if (OS.isLinux) return "lib" + libraryName + (OS.isARM ? "arm" : "") + (OS.is64Bit ? "64.so" : ".so");
		if (OS.isMac) return "lib" + libraryName + (OS.isARM ? "arm" : "") + (OS.is64Bit ? "64.dylib" : ".dylib");
		return libraryName;
	}
	public static boolean loaded() {
		return ClassUtils.exists("remold.RemoldAgent");
	}

	/**
	 * 挂 dispatcher 与 arc 插件。
	 *
	 * <p>plugin-arc 缺失时（第三方 {@code -javaagent} 只带 core）只保留 core 能力：
	 * {@code ArcHotswapPlugin} 属于 plugin-arc，直接 new 会以 NoClassDefFoundError
	 * 打断整个 {@link #start()}，连带后面的 libtool 初始化也跑不到。
	 */
	private static void setupAgent() {
		RemoldAgent.setMainThreadDispatcher(Core.app::post);
		if (!RemoldBootstrap.pluginLoaded()) {
			RemoldBootstrap.warnMissingPluginOnce();
			return;
		}
		RemoldAgent.registerPlugin(new remold.arc.ArcHotswapPlugin());
	}

	/** UpdateRef 属于 plugin-arc；缺插件时它没有被注册，也就没有待处理项 */
	private static void flushDeferredRemovals() {
		if (!RemoldBootstrap.pluginLoaded()) return;
		UpdateRef.flushDeferredRemovals();
	}
	public static void hotswap(String watchPaths) {
		try {
			// 参数是类目录，Agent会自行处理
			if (loaded()) {
				// agent 已经在 RemoldBootstrap 里挂上（为了让设置类能链接 remold.*），
				// 同一个 VM 不能再 loadAgent 一次，只能显式初始化
				RemoldAgent.init(watchPaths, true);
			} else {
				UtilsAgentManager.attachAgent(getAgentPath(), true, watchPaths);
			}
		} catch (Throwable e) {
			// 挂载失败时 RemoldAgent 本身不可用，不能用它记日志
			Log.err("[HotSwapManager] An error occurred during the hotswap process.", e);
		}
	}

	static String getAgentPath() {
		if (agentPathCache != null && new File(agentPathCache).exists()) {
			return agentPathCache;
		}
		Fi lib = IntVars.libs.child(AGENT_NAME + ".jar");
		Fi tempFile = FileUtils.copyToTmp(lib);
		tempFile.file().deleteOnExit();
		agentPathCache = tempFile.path();
		return agentPathCache;
	}

	public static boolean valid() {
		return E_Hook.hot_swap.enabled();
	}
	public static boolean jniValid() {
		// 低于 22 时 panama 后端根本没被加载（见 UtilsAgentManager.init），
		// 此时连 MasterKey 都不存在，不要再去碰它
		if (!UtilsAgentManager.JNI_AGENT_SUPPORTED) return false;
		try {
			return MasterKey.isPanamaBackend();
		} catch (NoClassDefFoundError e) {
			return false;
		}
	}
}
