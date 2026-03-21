package modtools.android;

import android.os.Debug;
import arc.util.Log;
import dalvik.system.VMRuntime;
import mindustry.Vars;
import modtools.jsfunc.reflect.UNSAFE;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.*;

import static ihope_lib.MyReflect.unsafe;

/**
 * Only For Android
 * @see <a href="https://lovesykun.cn/archives/android-hidden-api-bypass.html">LSPosed的实现</a>
 */
public class HiddenApi {
	public static final VMRuntime runtime = VMRuntime.getRuntime();

	public static final long IBYTES             = Integer.BYTES;
	/**
	 * <a href=
	 * "https://cs.android.com/android/platform/superproject/main/+/main:art/runtime/mirror/executable.h;bpv=1;bpt=1;l=73?q=executable&ss=android&gsn=art_method_&gs=KYTHE%3A%2F%2Fkythe%3A%2F%2Fandroid.googlesource.com%2Fplatform%2Fsuperproject%2Fmain%2F%2Fmain%3Flang%3Dc%252B%252B%3Fpath%3Dart%2Fruntime%2Fmirror%2Fexecutable.h%23GLbGh3aGsjxEudfgKrvQvNcLL3KUjmUaJTc4nCOKuVY">
	 * uint64_t Executable::art_method_</a>
	 */
	public static final int  offset_art_method_ = 24;

	public static void setHiddenApiExemptions() {
		try { // 使用LSPosed的实现
			HiddenApiBypass.setHiddenApiExemptions("L");
		} catch (Throwable e) {
			// 如果这也崩溃那也是nb了
			Log.err(e);
		}

		if (trySetHiddenApiExemptions()) { return; }
		// 高版本中setHiddenApiExemptions方法直接反射获取不到，得修改artMethod
		// sdk_version > 28（具体多少不知道）
		Method setHiddenApiExemptions = findMethod();

		try {
			if (setHiddenApiExemptions == null) {
				throw new InternalError("setHiddenApiExemptions not found.");
			}

			invoke(setHiddenApiExemptions);
		} catch (Exception e) {
			Log.err(e);
		}
		try {
			// byte[] bytes =
			// IntVars.root.child("/modtools/android/RMap.class").readBytes();
			// HopeReflect.defineClass("modtools.android.RMap",
			// AndroidInputMap.class.getClassLoader(), bytes);
			/*
			 * Field f_pathList = BaseDexClassLoader.class.getDeclaredField("pathList");
			 * f_pathList.setAccessible(true);
			 * Object pathList = f_pathList.get(Vars.class.getClassLoader());
			 * // public void addDexPath(String dexPath, File optimizedDirectory, boolean
			 * isTrusted);
			 * Method addDexPath = pathList.getClass().getDeclaredMethod("addDexPath",
			 * String.class, File.class, boolean.class);
			 * Fi rp = FileUtils.copyToTmp(IntVars.root.child("rp.jar"), "rp.jar");
			 * Fi rpDex = FileUtils.copyToTmp(new ZipFi(rp).child("classes.dex"),
			 * "modtools.dex");
			 * addDexPath.invoke(pathList, rpDex.absolutePath(), null, false);
			 * // private static native Class defineClassNative(String name, ClassLoader
			 * loader, Object cookie, DexFile dexFile)
			 * Method defineClass = DexFile.class.getDeclaredMethod("defineClassNative",
			 * String.class, ClassLoader.class, Object.class, DexFile.class);
			 * defineClass.setAccessible(true);
			 *
			 * Field f_dexElements = pathList.getClass().getDeclaredField("dexElements");
			 * f_dexElements.setAccessible(true);
			 * Object dexElements = f_dexElements.get(pathList);
			 * Log.info("DexElement Length:" + Array.getLength(dexElements));
			 * Object element_apk = Array.get(dexElements, 0);
			 * Object element_mod = Array.get(dexElements, 1);
			 * Field f_dexFile = element_apk.getClass().getDeclaredField("dexFile");
			 * f_dexFile.setAccessible(true);
			 * DexFile dex_apk = (DexFile) f_dexFile.get(element_apk);
			 * DexFile dex_mod = (DexFile) f_dexFile.get(element_mod);
			 * Field f_mCookie = dex_apk.getClass().getDeclaredField("mCookie");
			 * f_mCookie.setAccessible(true);
			 * Object dex_apkCookie = f_mCookie.get(dex_apk);
			 * Object dex_modCookie = f_mCookie.get(dex_mod);
			 *
			 * Class<?> cls = (Class) defineClass.invoke(null, "modtools.android.RMap",
			 * Vars.class.getClassLoader(), dex_modCookie, dex_apk);
			 * replaceAMethod(AndroidInputMap.class.getDeclaredMethod("getKeyCode",
			 * int.class),
			 * cls.getDeclaredMethod("getKeyCode", int.class));
			 */
		} catch (Throwable e) {
			Log.err(e);
		}
	}

	/** @return true if successful. */
	private static boolean trySetHiddenApiExemptions() {

		try {
			// MAYBE: sdk_version < 28
			runtime.setHiddenApiExemptions(new String[]{"L"});
			return true;
		} catch (Throwable ignored) {
		}
		try {
			// 通过反射获取方法
			Method m = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
			m.setAccessible(true);
			Method setHiddenApiExemptions = (Method) m.invoke(VMRuntime.class, "setHiddenApiExemptions",
			 new Class[]{String[].class});
			invoke(setHiddenApiExemptions);
			return true;
		} catch (Throwable ignored) {
		}
		return false;
	}

	private static void invoke(Method method)
	 throws IllegalAccessException, InvocationTargetException {
		method.setAccessible(true);
		method.invoke(runtime, (Object) new String[]{"L"});
	}

	private static Method findMethod() {
		Method[] methods = VMRuntime.class.getDeclaredMethods();

		// Fast path: if first method is already the target
		if (methods[0].getName().equals("setHiddenApiExemptions")) {
			return methods[0];
		}

		int      length = methods.length;
		Method[] array  = (Method[]) runtime.newNonMovableArray(Method.class, length);
		System.arraycopy(methods, 0, array, 0, length);

		long address = addressOf(array);

		// --- Step 1: Extract all ArtMethod addresses ---
		long[] artMethodAddresses = new long[length];
		for (int k = 0; k < length; ++k) {
			final long k_address = address + k * IBYTES;
			// FIX #1: mask to prevent sign-extension of 32-bit compressed pointer
			// e.g. 0x80001000 as int is negative, but as long should be 0x0000000080001000L
			final long address_Method = UNSAFE.getInt(k_address) & 0xFFFFFFFFL;
			artMethodAddresses[k] = UNSAFE.getLong(address_Method + offset_art_method_);
		}

		// --- Step 2: Compute step size robustly ---
		// FIX #2: sort addresses, then find the minimum positive delta.
		// The original code used only min and min_second, which breaks when
		// hidden methods are filtered out by getDeclaredMethods() on API 14+,
		// causing the computed stride to span 2+ ArtMethod slots and skipping the target.
		long[] sorted = artMethodAddresses.clone();
		java.util.Arrays.sort(sorted);

		long min_diff = Long.MAX_VALUE;
		for (int i = 1; i < length; i++) {
			long diff = sorted[i] - sorted[i - 1];
			if (diff > 0 && diff < min_diff) {
				min_diff = diff;
			}
		}
		size_art_method = min_diff;
		Log.debug("size_art_method: " + size_art_method);

		// Sanity check: ArtMethod structs are typically 40–128 bytes
		if (size_art_method <= 0 || size_art_method > 150) {
			Log.err("Implausible size_art_method: " + size_art_method);
			return null;
		}

		long min = sorted[0];
		long max = sorted[length - 1];

		// --- Step 3: Walk the ArtMethod table and probe by name ---
		// Search slightly beyond max to catch methods that getDeclaredMethods skipped
		for (long artMethod = min; artMethod <= max + size_art_method * 32; artMethod += size_art_method) {
			// FIX #1 applied again: always mask when reading the compressed pointer
			final long address_Method = UNSAFE.getInt(address) & 0xFFFFFFFFL;
			UNSAFE.putLong(address_Method + offset_art_method_, artMethod);

			// getName() is native; it reads the new artMethod pointer directly
			if ("setHiddenApiExemptions".equals(array[0].getName())) {
				Log.debug("Got Method setHiddenApiExemptions: " + array[0]);
				return array[0];
			}
		}

		return null;
	}
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
	static long size_art_method;

	static {
		/* Method是指针，大小相当于int */
		int[] ints = (int[]) runtime.newNonMovableArray(int.class, 0);
		offset = runtime.addressOf(ints) - UNSAFE.vaddressOf(ints);
		try {
			for (Executable method : HiddenApiBypass.getDeclaredMethods(Debug.class)) {
				Log.info(method);
			}
			testReplaceCrossMethod();
			// testReplaceModifier();
			// replaceMethod();
		} catch (Throwable e) {
			Log.err(e);
		}
	}

	private static boolean DEBUG;
	private static void testReplaceCrossMethod() throws Throwable {
		// Log.info(HiddenApiBypass.getDeclaredMethods(VMRuntime.class));
		class MyProxy {
			public static void myHookLogger() {
				Log.info("Inject");
			}
		}
		replaceMethodx(Vars.class.getDeclaredMethod("loadLogger"), MyProxy.class.getDeclaredMethod("myHookLogger"));

		Vars.loadLogger();
	}
	private static void replaceMethodx(Method targetMethod, Method hookMethod) throws NoSuchFieldException, IllegalAccessException, NoSuchMethodException {
		long methodOffset;
		{
			Field field = HiddenApiBypass.class.getDeclaredField("methodOffset");
			field.setAccessible(true);
			methodOffset = (long) field.get(null);
		}
		long artMethodSize;
		{
			Field field = HiddenApiBypass.class.getDeclaredField("artMethodSize");
			field.setAccessible(true);
			artMethodSize = (long) field.get(null);
		}
		// 获取目标方法和你的 Hook 方法

		// 利用 HiddenApiBypass 已经计算出的偏移量，找到它们的 Native ArtMethod 指针
		// 假设你已经按照 HiddenApiBypass 的 static 块初始化了 artOffset 等变量
		long targetArtMethodPtr = unsafe.getLong(targetMethod, methodOffset);
		long hookArtMethodPtr   = unsafe.getLong(hookMethod, methodOffset);

		if (DEBUG) {
			Log.info("Target Method Ptr: " + Long.toHexString(targetArtMethodPtr));
			Log.info("Hook Method Ptr: " + Long.toHexString(hookArtMethodPtr));
		}

		// 注意：不要只改 entrypoint，最稳妥的是覆盖整个 ArtMethod 结构体（除了少数受保护字段）
		// 根据 HiddenApiBypass 计算的 artMethodSize 进行内存拷贝
		long entryPointOffset = artMethodSize - 8;

		// 2. 只拷贝入口点，不拷贝索引和类信息
		// 这样 targetMethod 的“身份”还是原来的，但“灵魂（执行逻辑）”换成了 hookMethod 的
		long hookEntryPoint = unsafe.getLong(hookArtMethodPtr + entryPointOffset);
		unsafe.putLong(targetArtMethodPtr + entryPointOffset, hookEntryPoint);
	}
}
