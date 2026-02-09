package modtools.unsupported;

import com.sun.tools.attach.VirtualMachine;
import jdk.internal.misc.Unsafe;
import modtools.jsfunc.reflect.UNSAFE;
import modtools.utils.reflect.FieldUtils;
import sun.tools.attach.HotSpotVirtualMachine;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;

public class UtilsAgent {
	public static final  String  AGENT_NAME          = "utils-agent";
	private static final String  AGENT_RESOURCE_PATH = "/libs/" + AGENT_NAME + ".jar";
	private static       String  agentPathCache      = null;
	private static       boolean initialized         = false;

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
	/** 添加类路径到启动类路径  */
	public static void appendToBootstrap(String path) throws Throwable {
		attachAgent(getAgentPath(), true, "appendToBootstrap\n" + path);
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
			if (HotSwapManager.DEBUG) {
				System.out.println("[Agent] Attaching to process " + pid);
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
		if (initialized) return; // 避免重复
		initialized = true;
		UNSAFE.openModule(VirtualMachine.class, "sun.tools.attach");
		Unsafe unsafe = Unsafe.getUnsafe();
		unsafe.ensureClassInitialized(HotSpotVirtualMachine.class);
		unsafe.putBoolean(HotSpotVirtualMachine.class,
		 unsafe.staticFieldOffset(FieldUtils.getFieldAccess(HotSpotVirtualMachine.class, "ALLOW_ATTACH_SELF")),
		 true);
	}
}
