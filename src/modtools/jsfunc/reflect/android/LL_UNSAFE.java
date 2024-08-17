package modtools.jsfunc.reflect.android;

import dalvik.system.VMRuntime;

/**
 * TODO: 这个类使用unsafe修改一些内部字段值
 */
public interface LL_UNSAFE {
	static void setTargetSdkVersion(int version) {
		VMRuntime.getRuntime().setTargetSdkVersion(version);
	}
	static int getTargetSdkVersion() { return VMRuntime.getRuntime().getTargetSdkVersion(); }
	static void startJitCompilation() { VMRuntime.getRuntime().startJitCompilation(); }
	static void disableJitCompilation() { VMRuntime.getRuntime().disableJitCompilation(); }
	static void addressOf(Object obj) { VMRuntime.getRuntime().addressOf(obj); }
}