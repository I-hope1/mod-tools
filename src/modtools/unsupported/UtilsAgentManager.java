package modtools.unsupported;

import arc.util.Log;
import com.sun.tools.attach.VirtualMachine;
import jdk.internal.misc.Unsafe;
import modtools.jsfunc.reflect.UNSAFE;
import modtools.utils.reflect.FieldUtils;
import nipx.Utils;
import sun.tools.attach.HotSpotVirtualMachine;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.util.*;
import java.util.jar.JarFile;

public class UtilsAgentManager {
	public static final  String  AGENT_NAME          = "utils-agent";
	private static final String  AGENT_RESOURCE_PATH = "/libs/" + AGENT_NAME + ".jar";
	private static       String  agentPathCache      = null;
	private static       boolean initialized         = false;

	public static String getAgentPath() throws Exception {
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
	/** 添加类路径到启动类路径 */
	public static void appendToBootstrap(String path) throws IOException {
		Utils.inst.appendToSystemClassLoaderSearch(new JarFile(path));
	}
	public static void redefineModule(Module module,
	                                  Set<Module> extraReads,
	                                  Map<String, Set<Module>> extraExports,
	                                  Map<String, Set<Module>> extraOpens,
	                                  Set<Class<?>> extraUses,
	                                  Map<Class<?>, List<Class<?>>> extraProvides) {
		Utils.inst.redefineModule(
		 module,
		 extraReads,
		 extraExports,
		 extraOpens,
		 extraUses,
		 extraProvides
		);
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
	static {
		try {
			init();
		} catch (Throwable e) {
			Log.err("Failed to init utils agent", e);
		}
	}

	public static void init() throws Throwable {
		if (initialized) return; // 避免重复
		initialized = true;
		attachAgent(getAgentPath(), true, "");
	}

	static void prepareSelfAttach() {
		UNSAFE.openModule(VirtualMachine.class, "sun.tools.attach");
		Unsafe unsafe = Unsafe.getUnsafe();
		unsafe.ensureClassInitialized(HotSpotVirtualMachine.class);
		unsafe.putBoolean(HotSpotVirtualMachine.class,
		 unsafe.staticFieldOffset(FieldUtils.getFieldAccess(HotSpotVirtualMachine.class, "ALLOW_ATTACH_SELF")),
		 true);
	}
}
