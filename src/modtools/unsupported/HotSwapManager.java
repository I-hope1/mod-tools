package modtools.unsupported;

import arc.util.Log;
import com.sun.tools.attach.VirtualMachine;
import ihope_lib.MyReflect;
import jdk.internal.access.*;
import jdk.internal.misc.Unsafe;
import jdk.internal.module.Modules;

import modtools.events.E_Hook;
import modtools.jsfunc.reflect.UNSAFE;
import modtools.utils.reflect.*;
import sun.tools.attach.HotSpotVirtualMachine;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;

/**
 * 负责执行热更新的核心逻辑。
 * 这个类是无状态的，可以被重复调用。
 */
public class HotSwapManager {
	public static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("nipx.agent.debug", "false"));

	private static final String  AGENT_NAME          = "hotswap-agent";
	private static final String  AGENT_RESOURCE_PATH = "/libs/"+AGENT_NAME+".jar";
	private static       String  agentPathCache      = null;
	private static       boolean inited              = false;

	public static void start() throws Throwable {
		if (!inited) {
			/* 模块open（MyReflect里）还不够，还得exports */
			UNSAFE.addExports(Object.class, "jdk.internal.misc");
			UNSAFE.addExports(Object.class, "jdk.internal.reflect");
			UNSAFE.addExports(Object.class, "jdk.internal.org.objectweb.asm");
			E_Hook.hot_swap_watch_paths.onChange(() -> {
				hotswap(E_Hook.hot_swap_watch_paths.getArray().toString(File.pathSeparator));
			});
			inited = true;
		}
		hotswap(E_Hook.hot_swap_watch_paths.getArray().toString(File.pathSeparator));
	}
	public static void hotswap(String watchPaths) {
		try {
			// 参数是类目录，Agent会自行处理
			String agentPath = getAgentPath();

			attachAgent(agentPath, true, watchPaths);
		} catch (Throwable e) {
			System.err.println("[HotSwapManager] An error occurred during the hotswap process.");
			e.printStackTrace();
		}
	}
	public static void attachAgent(String agentPath, String agentArgs) throws Throwable {
		attachAgent(agentPath, false, agentArgs);
	}
	public static void attachAgent(String agentPath, boolean isAbsolute, String agentArgs) throws Throwable {
		String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];

		// 允许自我附加，这段代码可以不用每次都执行，但为了简单和健壮性，我们保留它
		prepareSelfAttach();

		VirtualMachine vm = null;
		try {
			if (DEBUG) {
				System.out.println("[HotSwapManager] Attaching to process " + pid);
			}
			vm = VirtualMachine.attach(pid);
			if (isAbsolute) {
				vm.loadAgent(agentPath, agentArgs);
			} else {
				vm.loadAgentLibrary(agentPath, agentArgs);
			}
		} finally {
			if (vm != null) {
				vm.detach();
			}
		}
	}

	private static void prepareSelfAttach() {
		UNSAFE.openModule(VirtualMachine.class, "sun.tools.attach");
		Unsafe unsafe = Unsafe.getUnsafe();
		unsafe.ensureClassInitialized(HotSpotVirtualMachine.class);
		unsafe.putBoolean(HotSpotVirtualMachine.class,
		 unsafe.staticFieldOffset(FieldUtils.getFieldAccess(HotSpotVirtualMachine.class, "ALLOW_ATTACH_SELF")),
		 true);
	}

	private static String getAgentPath() throws Exception {
		if (agentPathCache != null && new File(agentPathCache).exists()) {
			return agentPathCache;
		}
		try (InputStream is = HotSwapManager.class.getResourceAsStream(AGENT_RESOURCE_PATH)) {
			if (is == null) {
				throw new RuntimeException("Agent JAR not found in resources: " + AGENT_RESOURCE_PATH);
			}
			File tempFile = File.createTempFile("hotswap-agent-", ".jar");
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
