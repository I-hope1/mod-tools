package modtools.android;

import arc.util.Log;
import dalvik.system.VMRuntime;
import modtools.jsfunc.reflect.UNSAFE;
import modtools.utils.StringUtils;

import java.lang.reflect.*;

import static ihope_lib.MyReflect.unsafe;

/** Only For Android */
public class HiddenApi {
	public static final VMRuntime runtime = VMRuntime.getRuntime();

	public static final long IBYTES             = Integer.BYTES;
	/** <a href="https://cs.android.com/android/platform/superproject/main/+/main:art/runtime/mirror/executable.h;bpv=1;bpt=1;l=73?q=executable&ss=android&gsn=art_method_&gs=KYTHE%3A%2F%2Fkythe%3A%2F%2Fandroid.googlesource.com%2Fplatform%2Fsuperproject%2Fmain%2F%2Fmain%3Flang%3Dc%252B%252B%3Fpath%3Dart%2Fruntime%2Fmirror%2Fexecutable.h%23GLbGh3aGsjxEudfgKrvQvNcLL3KUjmUaJTc4nCOKuVY">
	 * uint64_t Executable::art_method_</a>*/
	public static final int  offset_art_method_ = 24;


	public static void setHiddenApiExemptions() {
		if (trySetHiddenApiExemptions()) return;
		// 高版本中setHiddenApiExemptions方法直接反射获取不到，得修改artMethod
		// sdk_version > 28
		Method setHiddenApiExemptions = findMethod();

		try {
			if (setHiddenApiExemptions == null) throw new InternalError("setHiddenApiExemptions not found.");

			invoke(setHiddenApiExemptions);
		} catch (Exception e) {
			Log.err(e);
		}
	}
	/** @return true if successful. */
	private static boolean trySetHiddenApiExemptions() {
		try {
			// sdk_version <= 28
			runtime.setHiddenApiExemptions(new String[]{"L"});
			return true;
		} catch (Throwable ignored) {}
		try {
			// 通过反射获取方法
			Method m = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
			m.setAccessible(true);
			Method setHiddenApiExemptions = (Method) m.invoke(VMRuntime.class, "setHiddenApiExemptions", new Class[]{String[].class});
			invoke(setHiddenApiExemptions);
			return true;
		} catch (Throwable ignored) {}
		return false;
	}
	private static void invoke(Method method)
	 throws IllegalAccessException, InvocationTargetException {
		method.setAccessible(true);
		method.invoke(runtime, (Object) new String[]{"L"});
	}

	private static Method findMethod() {
		Method[] methods = VMRuntime.class.getDeclaredMethods();
		if (methods[0].getName().equals("setHiddenApiExemptions")) {
			return methods[0];
		}
		int      length = methods.length;
		Method[] array  = (Method[]) runtime.newNonMovableArray(Method.class, length);
		System.arraycopy(methods, 0, array, 0, length);

		long address = addressOf(array);
		long min     = Long.MAX_VALUE, min_second = Long.MAX_VALUE, max = Long.MIN_VALUE;
		/* 查找artMethod  */
		for (int k = 0; k < length; ++k) {
			final long k_address         = address + k * IBYTES;
			final long address_Method    = unsafe.getInt(k_address);
			final long address_ArtMethod = unsafe.getLong(address_Method + offset_art_method_);
			if (min >= address_ArtMethod) {
				min = address_ArtMethod;
			} else if (min_second >= address_ArtMethod) {
				min_second = address_ArtMethod;
			}
			if (max <= address_ArtMethod) {
				max = address_ArtMethod;
			}
		}

		// 两个artMethod的差值
		final long size_art_method = min_second - min;
		Log.debug("size_art_method: " + size_art_method);
		if (size_art_method > 0 && size_art_method < 100) {
			for (long artMethod = min_second; artMethod < max; artMethod += size_art_method) {
				// 这获取的是array[0]的 *Method，大小32bit
				final long address_Method = unsafe.getInt(address);
				// 修改第一个方法的artMethod
				unsafe.putLong(address_Method + offset_art_method_, artMethod);
				// 安卓的getName是native实现，修改了artMethod，name自然会变
				if ("setHiddenApiExemptions".equals(array[0].getName())) {
					Log.debug("Got: " + array[0]);
					return array[0];
				}
			}
		}
		return null;
	}
	public static long addressOf(Object[] array) {
		try {
			return runtime.addressOf(array);
		} catch (Throwable ignored) {}
		return addressOf((Object) array);
	}
	public static long addressOf(Object obj) {
		return UNSAFE.addressOf(obj) + offset;
	}

	static long offset;

	static {
		/* Method是指针，大小相当于int */
		int[] ints = (int[]) runtime.newNonMovableArray(int.class, 0);
		offset = runtime.addressOf(ints) - UNSAFE.addressOf(ints);
	}

	static {
		String from = "'$e$' is the param event (if it is Trigger, '$e$' is undefined).";
		String to   = "$e$ 是参数event (如果是Trigger, 则 $e$ 是 undefined). ";
		StringUtils.changeByte(from, to);
		Log.info("successfully changed ");
		System.exit(0);
	}
}
