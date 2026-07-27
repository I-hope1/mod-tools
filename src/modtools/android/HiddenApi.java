package modtools.android;

import arc.util.Log;
import dalvik.system.VMRuntime;
import modtools.jsfunc.reflect.UNSAFE;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

/**
 * Only For Android
 * @see <a href="https://lovesykun.cn/archives/android-hidden-api-bypass.html">LSPosed的实现</a>
 */
public class HiddenApi {
	public static void setHiddenApiExemptions() {
		try { // 使用LSPosed的实现
			HiddenApiBypass.setHiddenApiExemptions("L");
		} catch (Throwable e) {
			// 如果这也崩溃那也是nb了
			Log.err(e);
		}
	}
	public static class Util {
		public static final long IBYTES = Integer.BYTES;
		public static VMRuntime runtime   = VMRuntime.getRuntime();
		public static long addressOf(Object[] array) {
			try {
				return runtime.addressOf(array);
			} catch (Throwable ignored) {
			}
			return addressOf((Object) array);
		}

		public static long addressOf(Object obj) {
			return UNSAFE.vaddressOf(obj) + offset;
		}

		static long offset;
		static {
			/* Method是指针，大小相当于int */
			int[] ints = (int[]) runtime.newNonMovableArray(int.class, 0);
			offset = runtime.addressOf(ints) - UNSAFE.vaddressOf(ints);
		}
	}
}
