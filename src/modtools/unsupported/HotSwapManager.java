package modtools.unsupported;

import arc.Core;
import arc.Events;
import arc.files.Fi;
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
			if (loaded()) {
				RemoldAgent.setMainThreadDispatcher(Core.app::post);
				RemoldAgent.registerPlugin(new remold.arc.ArcHotswapPlugin());
			}
			E_Hook.hot_swap_watch_paths.onChange(() -> {
				if (loaded()) {
					RemoldAgent.setMainThreadDispatcher(Core.app::post);
					RemoldAgent.registerPlugin(new remold.arc.ArcHotswapPlugin());
					RemoldAgent.init(E_Hook.hot_swap_watch_paths.getArray().toString(File.pathSeparator), true);
				}
			});

			// HotSwapController.init();
			initialized = true;
		}
		hotswap(E_Hook.hot_swap_watch_paths.getArray().toString(File.pathSeparator));
		if (loaded()) {
			RemoldAgent.setMainThreadDispatcher(Core.app::post);
			RemoldAgent.registerPlugin(new remold.arc.ArcHotswapPlugin());
		}
		UpdateRef.flushDeferredRemovals();
		Events.on(ClientLoadEvent.class, _ -> UpdateRef.flushDeferredRemovals());
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
	public static void hotswap(String watchPaths) {
		try {
			// 参数是类目录，Agent会自行处理
			String agentPath = getAgentPath();
			// UtilsAgentManager.appendToBootstrap(agentPath);
			UtilsAgentManager.attachAgent(agentPath, true, watchPaths);
		} catch (Throwable e) {
			RemoldAgent.error("[HotSwapManager] An error occurred during the hotswap process.", e);
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
		try {
			return MasterKey.isPanamaBackend();
		} catch (NoClassDefFoundError e) {
			return false;
		}
	}
}
