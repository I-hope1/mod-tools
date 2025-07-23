package modtools.unsupported;

import com.sun.tools.attach.VirtualMachine;
import ihope_lib.MyReflect;
import jdk.internal.misc.Unsafe;
import modtools.utils.reflect.FieldUtils;
import sun.tools.attach.HotSpotVirtualMachine;

import java.io.*;
import java.lang.management.ManagementFactory;

public class TestVM {
	public static void main() throws Throwable {
		// 获取当前JVM的PID
		String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
		System.out.println("[ModEntry] Current JVM PID: " + pid);


		String agentPath = "E:/Users/ASUS/Desktop/Mods/mod-tools136/testAgent/build/libs/testAgent-1.0.jar";
		//agentJarFile.absolutePath();
		System.out.println("[ModEntry] Attempting to attach agent from: " + agentPath);

		MyReflect.openModule(VirtualMachine.class.getModule(), "sun.tools.attach");
		Unsafe.getUnsafe().ensureClassInitialized(HotSpotVirtualMachine.class);
		Unsafe.getUnsafe().putBoolean(HotSpotVirtualMachine.class,
		 Unsafe.getUnsafe().staticFieldOffset(FieldUtils.getFieldAccess(HotSpotVirtualMachine.class, "ALLOW_ATTACH_SELF")),
		 true);
		// new Thread(() -> System.out.println("ALLOW_ATTACH_SELF = " + Unsafe.getUnsafe().getBoolean(HotSpotVirtualMachine.class,
		//  Unsafe.getUnsafe().staticFieldOffset(FieldUtils.getFieldAccess(HotSpotVirtualMachine.class, "ALLOW_ATTACH_SELF"))))).start();

		VirtualMachine vm = VirtualMachine.attach(pid); // 调用 VirtualMachine.attach(pid)
		vm.loadAgent(agentPath);      // 调用 vm.loadAgent(agentPath)
		// vm.detach();                    // 调用 vm.detach()

		System.out.println("[ModEntry] Agent attached successfully.");
	}
	private static void testTmp() {
		String tempDir = System.getProperty("java.io.tmpdir");
		System.out.println("[ModEntry] Java temporary directory: " + tempDir);
		File testFile = new File(tempDir, "mod_attach_test_" + System.currentTimeMillis());
		try {
			if (testFile.createNewFile()) {
				System.out.println("[ModEntry] Successfully created test file in temp dir: " + testFile.getAbsolutePath());
				testFile.delete(); // 清理
				System.out.println("[ModEntry] Test file deleted.");
			} else {
				System.err.println("[ModEntry] Failed to create test file in temp dir, may be a permission issue: " + testFile.getAbsolutePath());
			}
		} catch (IOException ioException) {
			System.err.println("[ModEntry] IOException while testing temp dir write: " + ioException.getMessage());
			ioException.printStackTrace();
		}
	}
}
