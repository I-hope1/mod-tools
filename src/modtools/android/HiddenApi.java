package modtools.android;

import arc.util.Log;
import dalvik.system.VMRuntime;
import modtools.jsfunc.reflect.UNSAFE;
import modtools.utils.ByteCodeTools.MyClass;
import rhino.classfile.ByteCode;

import java.lang.reflect.*;

import static modtools.utils.ByteCodeTools.nativeName;

/** Only For Android */
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
		if (methods[0].getName().equals("setHiddenApiExemptions")) {
			return methods[0];
		}
		int      length = methods.length;
		Method[] array  = (Method[]) runtime.newNonMovableArray(Method.class, length);
		System.arraycopy(methods, 0, array, 0, length);

		long address = addressOf(array);
		long min     = Long.MAX_VALUE, min_second = Long.MAX_VALUE, max = Long.MIN_VALUE;
		/* 查找artMethod */
		for (int k = 0; k < length; ++k) {
			final long k_address         = address + k * IBYTES;
			final long address_Method    = UNSAFE.getInt(k_address);
			final long address_ArtMethod = UNSAFE.getLong(address_Method + offset_art_method_);
			if (min >= address_ArtMethod) {
				min = address_ArtMethod;
			} else if (min_second >= address_ArtMethod) {
				min_second = address_ArtMethod;
			}
			if (max <= address_ArtMethod) {
				max = address_ArtMethod;
			}
		}

		// 两个artMethod的差值（因为连续）
		size_art_method = min_second - min;
		Log.debug("size_art_method: " + size_art_method);
		if (size_art_method > 0 && size_art_method < 100) {
			for (long artMethod = min_second; artMethod < max; artMethod += size_art_method) {
				// 这获取的是array[0]的 *Method，大小32bit
				final long address_Method = UNSAFE.getInt(address);
				// 修改第一个方法的artMethod
				UNSAFE.putLong(address_Method + offset_art_method_, artMethod);
				// 安卓的getName是native实现，修改了artMethod，name自然会变
				if ("setHiddenApiExemptions".equals(array[0].getName())) {
					Log.debug("Got Method setHiddenApiExemptions: " + array[0]);
					return array[0];
				}
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
			// testReplaceModifier();
			// replaceMethod();
		} catch (Throwable e) {
			Log.err(e);
		}
	}

	public static class A {
		private A() {
		}

		private void _private() {
			Log.info("private");
		}

		public void _public() {
			Log.info("original");
		}
	}

	public static class Target extends A {
		public void _public() {
			Log.info("successful: @", this);
		}
	}

	static void testReplaceModifier() throws Exception {
		Method aPrivate                  = A.class.getDeclaredMethod("_private");
		Method aPublic                   = A.class.getDeclaredMethod("_public");
		long   address_artMethod_private = UNSAFE.getLong(aPrivate, offset_art_method_);
		long   address_artMethod_public  = UNSAFE.getLong(aPublic, offset_art_method_);
		Log.info("Origin: @", A.class.getDeclaredMethod("_private"));

		UNSAFE.copyMemory(address_artMethod_public + 4, address_artMethod_private + 4, 8);
		Log.info("Result: @", A.class.getDeclaredMethod("_private"));

		MyClass<Object> testA = new MyClass<>("testA", Object.class);
		testA.addInterface(Runnable.class);
		testA.setFunc("run", cfw -> { // new A()._private()
			cfw.add(ByteCode.NEW, nativeName(A.class));
			cfw.add(ByteCode.DUP);
			cfw.addInvoke(ByteCode.INVOKESPECIAL, nativeName(Object.class), "<init>", "()V");
			cfw.addInvoke(ByteCode.INVOKEVIRTUAL, nativeName(A.class), "_private", "()V");
			cfw.add(ByteCode.RETURN);
			return 1;
		}, 1, void.class);

		Runnable r = (Runnable) UNSAFE.allocateInstance(testA.define(A.class));
		Log.info("Runnable: @", r);
		r.run();
		Log.info("After run");
	}

	static void testReplaceMethod() {
		MyClass<Object> testA = new MyClass<>("testA", Object.class);
		testA.addInterface(Runnable.class);
		testA.setFunc("run", cfw -> {
			cfw.add(ByteCode.NEW, nativeName(A.class));
			cfw.add(ByteCode.DUP);
			cfw.addInvoke(ByteCode.INVOKESPECIAL, nativeName(Object.class), "<init>", "()V");
			cfw.addInvoke(ByteCode.INVOKEVIRTUAL, nativeName(A.class), "_public", "()V");
			cfw.add(ByteCode.RETURN);
			return 1;
		}, 1, void.class);

		Runnable r = (Runnable) UNSAFE.allocateInstance(testA.define(A.class));
		Log.info("Runnable: @", r);
		r.run();
		Log.info("After run");
	}

	public static class Super {
		public Class findLoadedClass(String name) {
			System.out.println("Not impl yet");
			return null;
		}
	}

	static Method   vm;
	static Class<?> Exception            = Exception.class;
	static Class<?> NullPointerException = NullPointerException.class;

	public static class Delegator extends Super {
		public Class findLoadedClass(String name) {
			if (name.equals("java.lang.Exception")) { return Exception; }
			if (name.equals("java.lang.NullPointerException")) { return NullPointerException; }

			try {
				Class res = (Class) vm.invoke(this, name);
				Log.info(res);
				return res;
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}

	static void replaceMethod() throws Exception {
		// {
		// new A()._public();
		// replaceAMethod(A.class.getDeclaredMethod("_public"),
		// Target.class.getDeclaredMethod("_public"));
		// new A()._public();
		// }

		{
			/*
			 * MyClass<Object> testA = new MyClass<>("testReplace", Object.class);
			 * testA.addInterface(Runnable.class);
			 * Class<?> error=NoSuchMethodError.class;
			 * testA.setFunc("findLoadedClass", (self, args) -> {
			 * String name = (String) args.get(0);
			 * System.out.println(args);
			 * if (name.equals("java.lang.NoSuchMethodError")) return error;
			 * return Tools.<Super>as(self).findLoadedClass(name);
			 * }, 1, Class.class, String.class);
			 * Class<?> delegator = testA.define(Vars.class.getClassLoader());
			 */
			vm = Class.forName("java.lang.ClassLoader").getDeclaredMethod("findLoadedClass", String.class);
			vm.setAccessible(true);

			// replaceAMethod(Super.class.getDeclaredMethod("findLoadedClass", String.class)
			// , vm);

			replaceAMethod(vm, Delegator.class.getDeclaredMethod("findLoadedClass", String.class));
		}

		// new A()._private();
	}

	private static void replaceAMethod(Method dest, Method src) {
		long artDest = UNSAFE.getLong(dest, offset_art_method_);
		long artSrc  = UNSAFE.getLong(src, offset_art_method_);

		/*
		 * API 33 ArtMethod 结构 (64-bit):
		 * 0-3: declaring_class (uint32)
		 * 4-7: access_flags (uint32)
		 * 8-11: dex_code_item_offset (uint32)
		 * 12-15: dex_method_index (uint32)
		 * 16-17: method_index (uint16)
		 * 18-19: hotness_count (uint16)
		 * 20-23: imt_index (uint32)
		 * 24-31: entry_point_from_quick_compiled_code (uint64)
		 */

		// 我们从偏移 8 开始拷贝，跳过 class 和 flags
		// 这样可以保留原本的类归属关系，避免 NoSuchMethodError
		int skip     = 8;
		int copySize = (int) size_art_method - skip;

		if (copySize > 0) {
			UNSAFE.copyMemory(artSrc + skip, artDest + skip, copySize);
		}

		// 【关键】修改目标方法的 flags，防止它走解释模式或被内联
		// 0x0100 是 kAccCompileDontBother (不要内联此方法)
		// 0x0002 是 kAccPrivate -> 修改为和源方法一致或保留原样
		// 这里我们强制让它看起来像是经过编译的
		int flags = UNSAFE.getInt(artDest + 4);
		flags |= 0x0100; // 禁止内联
		UNSAFE.putInt(artDest + 4, flags);

		Log.info("Hook applied to " + dest.getName());
	}
}
