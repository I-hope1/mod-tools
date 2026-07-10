package modtools.unsupported;

import nipx.jvmti.CrashVariableInterceptor;

public class CrashCaptureHook {
	public static void load() {
		CrashVariableInterceptor.install();
	}
}
