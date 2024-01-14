package modtools.utils.reflect;

import arc.util.OS;
import dalvik.system.VMRuntime;
import mindustry.Vars;
import mindustry.android.AndroidRhinoContext.AndroidContextFactory;
import rhino.*;

import java.lang.reflect.*;
import java.security.ProtectionDomain;

public class HopeReflect {
	static {
		try {
			clearReflectionFilter();
		} catch (Throwable ignored) {}
	}

	public static void clearReflectionFilter() throws Throwable {
		if (!OS.isAndroid) return;
		// code: VMRuntime.getRuntime().setHiddenApiExemptions(new String[]{"L"});
		Method methodM = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
		methodM.setAccessible(true);
		Method m2 = (Method) methodM.invoke(VMRuntime.class,
		 "setHiddenApiExemptions", new Class[]{String[].class});
		m2.setAccessible(true);
		m2.invoke(VMRuntime.getRuntime(), (Object) new String[]{"L"});
	}

	/**
	 * only for android
	 **/
	public static <T> void setPublic(T obj, Class<T> cls) {
		// MyReflect.setPublic(cls);
		try {
			Field f = cls.getDeclaredField("accessFlags");
			f.setAccessible(true);
			int flags = f.getInt(obj);
			flags &= 0xFFFF;
			flags &= ~Modifier.FINAL;
			flags &= ~Modifier.PRIVATE;
			flags |= Modifier.PUBLIC;
			f.setInt(obj, flags & 0xFFFF);
		} catch (Exception ignored) {}
	}

	public static Class<?> defineClass(String name, Class<?> superClass, byte[] bytes) {
		if (OS.isAndroid) {
			int mod = superClass.getModifiers();
			if (/*Modifier.isFinal(mod) || */Modifier.isPrivate(mod)) {
				setPublic(superClass, Class.class);
			}
			return androidDefineClass(name, superClass.getClassLoader(), bytes);
		}
		return UnsafeHandler.defineClass(null, bytes, superClass.getClassLoader());
	}

	public static Class<?> defineClass(String name, ClassLoader loader, byte[] bytes) {
		if (OS.isAndroid) return androidDefineClass(name, loader, bytes);
		return UnsafeHandler.defineClass(null, bytes, loader);
	}
	private static Class<?> androidDefineClass(String name, ClassLoader loader, byte[] bytes) {
		try {
			return ((GeneratedClassLoader) ((AndroidContextFactory) ContextFactory.getGlobal())
			 .createClassLoader(loader))
			 .defineClass(name, bytes);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}


	public static Class<?> defineClass(ClassLoader loader, byte[] bytes, ProtectionDomain pd) {
		return UnsafeHandler.defineClass(null, bytes, loader, pd);
	}

	public static Class<?> defineClass(ClassLoader loader, byte[] bytes) {
		return defineClass(loader, bytes, null);
	}

	public static Class<?> getCaller() {
		Thread              thread = Thread.currentThread();
		StackTraceElement[] trace  = thread.getStackTrace();
		try {
			return Class.forName(trace[3].getClassName(), false, Vars.mods.mainLoader());
		} catch (ClassNotFoundException e) {
			return null;
		}
	}
}
