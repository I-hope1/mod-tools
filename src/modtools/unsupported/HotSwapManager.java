package modtools.unsupported;

import modtools.events.E_Hook;
import modtools.jsfunc.reflect.UNSAFE;

import java.io.*;
import java.nio.file.Files;

/**
 * 负责执行热更新的核心逻辑。
 * 这个类是无状态的，可以被重复调用。
 */
public class HotSwapManager {
	public static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("nipx.agent.debug", "false"));

	private static final String  AGENT_NAME          = "hotswap-agent";
	private static final String  AGENT_RESOURCE_PATH = "/libs/"+AGENT_NAME+".jar";
	private static       String  agentPathCache = null;
	private static       boolean initialized    = false;

	public static void start() throws Throwable {
		if (!initialized) {
			/* 模块open（MyReflect里）还不够，还得exports */
			UNSAFE.addExports(Object.class, "jdk.internal.misc");
			UNSAFE.addExports(Object.class, "jdk.internal.reflect");
			UNSAFE.addExports(Object.class, "jdk.internal.org.objectweb.asm");
			UNSAFE.addExports(Object.class, "jdk.internal.org.objectweb.asm.tree");
			UNSAFE.addExports(Object.class, "jdk.internal.org.objectweb.asm.util");
			UNSAFE.addExports(Object.class, "jdk.internal.loader");
			E_Hook.hot_swap_watch_paths.onChange(() -> {
				hotswap(E_Hook.hot_swap_watch_paths.getArray().toString(File.pathSeparator));
			});
			initialized = true;
		}
		hotswap(E_Hook.hot_swap_watch_paths.getArray().toString(File.pathSeparator));
	}
	public static void hotswap(String watchPaths) {
		try {
			// 参数是类目录，Agent会自行处理
			String agentPath = getAgentPath();
			UtilsAgent.appendToBootstrap(agentPath);
			UtilsAgent.attachAgent(agentPath, true, watchPaths);
		} catch (Throwable e) {
			System.err.println("[HotSwapManager] An error occurred during the hotswap process.");
			e.printStackTrace();
		}
	}

	private static String getAgentPath() throws Exception {
		if (agentPathCache != null && new File(agentPathCache).exists()) {
			return agentPathCache;
		}
		try (InputStream is = HotSwapManager.class.getResourceAsStream(AGENT_RESOURCE_PATH)) {
			if (is == null) {
				throw new RuntimeException("Agent JAR not found in resources: " + AGENT_RESOURCE_PATH);
			}
			File tempFile = File.createTempFile(AGENT_NAME + "-", ".jar");
			tempFile.deleteOnExit();

			try (OutputStream os = Files.newOutputStream(tempFile.toPath())) {
				is.transferTo(os);
			}
			agentPathCache = tempFile.getAbsolutePath();
			return agentPathCache;
		}
	}

	public static boolean valid() {
		return E_Hook.hot_swap.enabled();
	}
}
