package modtools.unsupported;

import arc.util.Log;
import com.sun.tools.attach.VirtualMachine;
import modtools.events.R_Hook;
import sun.tools.attach.VirtualMachineImpl;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;

public class JDWP {
	public static void load() {
		try {
			String         pid    = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
			VirtualMachine vm     = VirtualMachine.attach(pid);
			Method         method = VirtualMachineImpl.class.getDeclaredMethod("execute", String.class, Object[].class);
			method.setAccessible(true);
			// Properties props = new Properties();
			// props.put("com.sun.management.jmxremote.port", "5000");
			// props.put("com.sun.management.jmxremote.password", "");
			// vm.startManagementAgent(props);
			String arg = "transport=dt_socket,server=y,suspend=n,address=" + R_Hook.dynamic_jdwp_port;
			vm.loadAgentLibrary("jdwp", arg);
			vm.detach();
			Log.info("Loaded jdwp.");
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}
}
