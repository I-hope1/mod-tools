package modtools.unsupported;

import arc.util.Log;
import remold.jvmti.CrashVariableInterceptor;

public class CrashCaptureHook {
	public static boolean ENABLED = Boolean.parseBoolean(System.getProperty("remold.agent.capture_exceptions"));
	public static void load() {
		if (!ENABLED) return;
		// CrashVariableInterceptor 属于 panama 后端，低于 22 的 JDK 上这些类无法链接
		if (!UtilsAgentManager.JNI_AGENT_SUPPORTED) {
			Log.info("[ExceptionCapturer] Needs JDK 22+. Skipped.");
			return;
		}
		// Events.on(ClientLoadEvent.class, _ -> CrashVariableInterceptor.install());
		CrashVariableInterceptor.install();
	}
}
