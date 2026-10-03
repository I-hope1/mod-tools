package modtools.unsupported;

import arc.files.Fi;
import arc.util.Log;
import com.sun.tools.attach.VirtualMachine;
import jdk.internal.misc.Unsafe;
import mindustry.Vars;
import modtools.IntVars;
import modtools.utils.io.FileUtils;
import nipx.UtilsAgent;
import nipx.jni.JNIEnv;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.jar.JarFile;

public class UtilsAgentManager {
	public static final boolean DEBUG = Boolean.parseBoolean(System.getenv("nipx.agent.debug"));

	public static final  String  AGENT_NAME          = "utils-agent";
	private static final String  AGENT_RESOURCE_PATH = "/libs/" + AGENT_NAME + ".jar";
	private static       String  agentPathCache      = null;
	private static       boolean initialized         = false;

	public static String getAgentPath() {
		if (agentPathCache != null && new File(agentPathCache).exists()) {
			return agentPathCache;
		}
		Fi lib      = IntVars.libs.child(AGENT_NAME + ".jar");
		Fi tempFile = FileUtils.copyToTmp(lib);
		tempFile.file().deleteOnExit();
		agentPathCache = tempFile.path();
		return agentPathCache;
	}
	/** 添加类路径到启动类路径 */
	public static void appendToBootstrap(String path) throws IOException {
		UtilsAgent.inst.appendToSystemClassLoaderSearch(new JarFile(path));
	}
	public static void redefineModule(Module module,
	                                  Set<Module> extraReads,
	                                  Map<String, Set<Module>> extraExports,
	                                  Map<String, Set<Module>> extraOpens,
	                                  Set<Class<?>> extraUses,
	                                  Map<Class<?>, List<Class<?>>> extraProvides) {
		UtilsAgent.inst.redefineModule(
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
			if (DEBUG) {
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
		Fi fi   = IntVars.libs.child("jni-agent.jar");
		Fi dest = Vars.tmpDirectory.child("jni-agent.jar");
		fi.copyTo(dest);
		appendToBootstrap(dest.absolutePath());

		try {
			JNIEnv.load();
		} catch (Throwable th) {
			Log.err(th);
		}
		// attachAgent(dest.absolutePath(), true, "");
		// JVMTIEnv.getInstance().asyncGetStack();
		// JNIAgent.load();
	}

	static void prepareSelfAttach() {
		// UNSAFE.openModule(VirtualMachine.class, "sun.tools.attach");
		try {
			Unsafe   unsafe              = Unsafe.getUnsafe();
			Class<?> hsvm = Class.forName("sun.tools.attach.HotSpotVirtualMachine");
			unsafe.ensureClassInitialized(hsvm);
			unsafe.putBoolean(hsvm,
			 unsafe.staticFieldOffset(hsvm.getDeclaredField("ALLOW_ATTACH_SELF")),
			 true);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}
}
