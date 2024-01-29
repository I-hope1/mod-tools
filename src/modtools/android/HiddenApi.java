package modtools.android;


import arc.util.Log;
import dalvik.system.VMRuntime;
import modtools.jsfunc.reflect.UNSAFE;

import java.lang.reflect.*;

import static ihope_lib.MyReflect.unsafe;

/** Only For Android  */
public class HiddenApi {
	public static final long IBYTES = Integer.BYTES;
	public static final int offset_art_method_ = 24;
	public static void setHiddenApiExemptions() {
		try {
			// sdk_version <= 28
			VMRuntime.getRuntime().setHiddenApiExemptions(new String[]{"L"});
			return;
		} catch (Throwable ignored) {}
		// sdk_version > 28
		Method setHiddenApiExemptions = findMethod(VMRuntime.class, "setHiddenApiExemptions");

		try {
			if (setHiddenApiExemptions == null) {
				throw new InternalError("setHiddenApiExemptions not found.");
			}
			setHiddenApiExemptions.setAccessible(true);
			setHiddenApiExemptions.invoke(VMRuntime.getRuntime(), new Object[]{new String[]{"L"}});
		} catch (Exception e) {
			Log.err(e);
		}
	}
	private static Method findMethod(Class<?> lookupClass, String lookupName) {
		VMRuntime runtime         = VMRuntime.getRuntime();
		Method[]  declaredMethods = lookupClass.getDeclaredMethods();
		int       length          = declaredMethods.length;
		Method[]  array           = (Method[]) runtime.newNonMovableArray(Method.class, length);
		System.arraycopy(declaredMethods, 0, array, 0, length);

		// https://cs.android.com/android/platform/superproject/main/+/main:art/runtime/mirror/executable.h;bpv=1;bpt=1;l=73?q=executable&ss=android&gsn=art_method_&gs=KYTHE%3A%2F%2Fkythe%3A%2F%2Fandroid.googlesource.com%2Fplatform%2Fsuperproject%2Fmain%2F%2Fmain%3Flang%3Dc%252B%252B%3Fpath%3Dart%2Fruntime%2Fmirror%2Fexecutable.h%23GLbGh3aGsjxEudfgKrvQvNcLL3KUjmUaJTc4nCOKuVY
		// uint64_t Executable::art_method_

		long address = addressOf(array);
		long min     = Long.MAX_VALUE, min_second = Long.MAX_VALUE, max = Long.MIN_VALUE;
		/* 查找artMethod，(min, min_second)  */
		for (int k = 0; k < length; ++k) {
			final long k_address      = address + k * IBYTES;
			final long address_Method = unsafe.getInt(k_address);
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

		final long size_art_method = min_second - min;
		if (size_art_method > 0 && size_art_method < 100) {
			for (min += size_art_method; min < max; min += size_art_method) {
				final long address_Method = unsafe.getInt(address);
				unsafe.putLong(address_Method + offset_art_method_, min);
				if (lookupName.equals(array[0].getName())) {
					return array[0];
				}
			}
		}
		return null;
	}
	public static long addressOf(Method[] array) {
		VMRuntime runtime = VMRuntime.getRuntime();
		try {
			return runtime.addressOf(array);
		} catch (Throwable ignored) {}
		long[] longs = (long[]) runtime.newNonMovableArray(long.class, 0);
		long offset  = UNSAFE.addressOf(longs) - runtime.addressOf(longs);
		return UNSAFE.addressOf(array) - offset - IBYTES;
	}
}
