package modtools.unsupported;

import nipx.jvmti.CrashVariableInterceptor;

public class CrashCaptureHook {
	public static boolean ENABLED = Boolean.parseBoolean(System.getProperty("nipx.agent.capture_exceptions"));
	public static void load() {
		if (ENABLED) {
			// Events.on(ClientLoadEvent.class, _ -> CrashVariableInterceptor.install());
			CrashVariableInterceptor.install();
		}
	}
}
