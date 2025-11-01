package modtools.unsupported;

import arc.util.serialization.Jval.JsonArray;
import com.sun.tools.attach.VirtualMachine;
import ihope_lib.MyReflect;
import jdk.internal.misc.Unsafe;
import modtools.IntVars;
import modtools.utils.MySettings.Data;
import modtools.utils.reflect.*;
import sun.tools.attach.HotSpotVirtualMachine;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;

import static modtools.utils.MySettings.SETTINGS;

/**
 * 负责执行热更新的核心逻辑。
 * 这个类是无状态的，可以被重复调用。
 */
public class HotSwapManager {
	public static final Data      HOT_SWAP   = SETTINGS.child("hot-swap");
	public static final JsonArray watchPaths = HOT_SWAP.getArray("watch-paths");
	public static final boolean   DEBUG      = Boolean.parseBoolean(System.getProperty("nipx.agent.debug", "false"));

	private static final String  AGENT_RESOURCE_PATH = "/libs/hotswap-agent.jar";
	private static       String  agentPathCache      = null;
	private static       boolean inited              = false;

	public static void start() {
		if (!inited) {
			HOT_SWAP.onChanged("watch-paths", () -> {
				hotswap(watchPaths.toString(File.pathSeparator));
			});
			inited = true;
		}
		hotswap(watchPaths.toString(File.pathSeparator));
	}
	public static void hotswap(String watchPaths) {
		try {
			// 参数是类目录，Agent会自行处理
			String agentPath = getAgentPath();

			attachAgent(agentPath, watchPaths);
		} catch (Throwable e) {
			System.err.println("[HotSwapManager] An error occurred during the hotswap process.");
			e.printStackTrace();
		}
	}

	private static void attachAgent(String agentPath, String agentArgs) throws Throwable {
		String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];

		// 允许自我附加，这段代码可以不用每次都执行，但为了简单和健壮性，我们保留它
		prepareSelfAttach();

		VirtualMachine vm = null;
		try {
			if (DEBUG) {
				System.out.println("[HotSwapManager] Attaching to process " + pid);
			}
			vm = VirtualMachine.attach(pid);
			vm.loadAgent(agentPath, agentArgs);
		} finally {
			if (vm != null) {
				vm.detach();
			}
		}
	}

	private static void prepareSelfAttach() throws Throwable {
		MyReflect.openModule(VirtualMachine.class.getModule(), "sun.tools.attach");
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
		return IntVars.isDesktop() && ClassUtils.exists("sun.tools.attach.HotSpotVirtualMachine");
	}
}