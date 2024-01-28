package modtools.android;


import arc.files.Fi;
import arc.util.*;
import mindustry.Vars;
import modtools.*;

import java.io.InputStream;
import java.lang.reflect.Method;

/** Only For Android  */
public class HiddenApi {
	static {
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
	public static native void setHiddenApiExemptions();
}
