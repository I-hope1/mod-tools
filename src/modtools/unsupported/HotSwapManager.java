package modtools.unsupported;

import com.sun.tools.attach.VirtualMachine;
import ihope_lib.MyReflect;
import jdk.internal.misc.Unsafe;
import modtools.utils.reflect.FieldUtils;
import sun.tools.attach.HotSpotVirtualMachine;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.Set;

/**
 * 负责执行热更新的核心逻辑。
 * 这个类是无状态的，可以被重复调用。
 */
public class HotSwapManager {

    private static final String AGENT_RESOURCE_PATH = "/libs/hotswap-agent.jar";
    private static String agentPathCache = null;

    /**
     * 对指定的一组类执行热更新。
     * @param classNames 要更新的类的全限定名集合。
	     * @param classesRootDir classes 的根目录 (e.g., "build/classes/java/main")
     */
    public static synchronized void hotswap(Set<String> classNames) {
        if (classNames == null || classNames.isEmpty()) {
            System.out.println("[HotSwapManager] No classes to hotswap.");
            return;
        }

        System.out.println("[HotSwapManager] Attempting to hotswap " + classNames.size() + " classes...");
        // System.out.println(String.join("\n", classNames.stream().map(s -> "  - " + s).collect(Collectors.toList())));

        try {
            // 参数是类目录，Agent会自行处理
            String classesPath = findClassesDirectory();
            String agentPath = getAgentPath();

            attachAgent(agentPath, classesPath);

            System.out.println("[HotSwapManager] Hotswap process for " + classNames.size() + " classes completed.");

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

    public static String findClassesDirectory() throws URISyntaxException {
        Path path = Paths.get(HotSwapManager.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isDirectory(path)) {
            return path.toString();
        }
        if (true) return "E:/Users/ASUS/Desktop/Mods/mod-tools136/build/classes/java/main";
        throw new IllegalStateException("Hot-swap is designed for development environments (classes in directories), not for running from a JAR.");
    }
}