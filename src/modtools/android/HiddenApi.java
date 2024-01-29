package modtools.android;


import arc.util.Log;
import dalvik.system.VMRuntime;
import modtools.jsfunc.reflect.UNSAFE;

import java.lang.reflect.*;

import static ihope_lib.MyReflect.unsafe;

/** Only For Android  */
public class HiddenApi {
	public static void setHiddenApiExemptions() throws Throwable {
		VMRuntime runtime = VMRuntime.getRuntime();
		try {
			// sdk_version <= 28
			runtime.setHiddenApiExemptions(new String[]{"L"});
			return;
		} catch (Throwable ignored) {}
		// sdk_version > 28
		Method setHiddenApiExemptions = null;

		Method[] declaredMethods = VMRuntime.class.getDeclaredMethods();
		int      length          = declaredMethods.length;
		Method[] array           = (Method[]) runtime.newNonMovableArray(Method.class, length);
		System.arraycopy(declaredMethods, 0, array, 0, length);

		// http://aosp.opersys.com/xref/android-11.0.0_r3/xref/art/runtime/mirror/executable.h
		// uint64_t Executable::art_method_
		final int offset_art_method_ = 24;

		// Field field_artMethod = Executable.class.getDeclaredField("artMethod");
		// field_artMethod.setAccessible(true);
		// Log.info("array[0].artMethod = " + field_artMethod.get(array[0]));
		// long min_Address = field_artMethod.getLong(array[0]);
		Log.info(runtime.addressOf(new int[]{1}));
		final long address = UNSAFE.addressOf(array);
		Log.info("address = " + address);
		long       min     = Long.MAX_VALUE, min_second = Long.MAX_VALUE, max = Long.MIN_VALUE;
		/* 查找artMethod，(min, min_second)  */
		for (int k = 0; k < length; ++k) {
			final long address_Method     = unsafe.getInt(address + k * Integer.BYTES);
			final long address_art_method = unsafe.getLong(address_Method + offset_art_method_);
			if (min >= address_art_method) {
				min = address_art_method;
			} else if (min_second >= address_art_method) {
				min_second = address_art_method;
			}
			if (max <= address_art_method) {
				max = address_art_method;
			}
		}

		final long size_art_method = min_second - min;
		if (size_art_method > 0 && size_art_method < 100) {
			for (min += size_art_method; min < max; min += size_art_method) {
				final long address_Method = unsafe.getInt(address);
				unsafe.putLong(address_Method + offset_art_method_, min);
				final String name = array[0].getName();
				if ("setHiddenApiExemptions".equals(name)) {
					setHiddenApiExemptions = array[0];
					break;
				}
			}
		}

		try {
			if (setHiddenApiExemptions == null) {
				throw new InternalError("setHiddenApiExemptions not found.");
			}
			setHiddenApiExemptions.setAccessible(true);
			setHiddenApiExemptions.invoke(runtime, new Object[]{new String[]{"L"}});
		} catch (Exception e) {
			Log.err(e);
		}
	}
	/* static {
		// loadLibrary("hope");
		Fi dest = OS.getAppDataDirectory(Vars.appName).child("libhope.so");
		IntVars.root.child("libhope.so").copyTo(dest);
		try {
			Method load0 = Runtime.class.getDeclaredMethod("nativeLoad", String.class, ClassLoader.class);
			load0.setAccessible(true);
			Object err = load0.invoke(Runtime.getRuntime(), dest.absolutePath(), HiddenApi.class.getClassLoader());
			if (err != null) Log.err("" + err);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		setHiddenApiExemptions();
	}

	static void loadLibrary(String libraryName) {
		new SharedLibraryLoader() {
			protected InputStream readFile(String path) {
				return IntVars.root.child("libhope.so").readByteStream();
			}
			public void load(String libraryName) {
				try {
					OS.isAndroid = false;
					super.load(libraryName);
				} finally {
					OS.isAndroid = true;
				}
			}
		}.load(libraryName);
	}
	public static native void setHiddenApiExemptions(); */
}
