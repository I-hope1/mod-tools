package modtools.utils.reflect;

import arc.util.OS;
import mindustry.Vars;
import mindustry.android.AndroidRhinoContext.AndroidContextFactory;
import rhino.*;

import java.lang.reflect.*;
import java.security.ProtectionDomain;

import static jdk.internal.misc.Unsafe.getUnsafe;

public class IReflect {
	// public static final Lookup lookup = MethodHandles.lookup();
	public static final MyClassLoader loader = new MyClassLoader(IReflect.class.getClassLoader());
	public static ClassLoader IMPL_LOADER;

	// private static final Constructor<?> IMPL_CONS;

	static {
		try {
			Constructor<?> cons = Class.forName("jdk.internal.reflect.DelegatingClassLoader")
					.getDeclaredConstructor(ClassLoader.class);
			cons.setAccessible(true);
			// IMPL_CONS = cons;
			IMPL_LOADER = (ClassLoader) cons.newInstance(loader);
		} catch (Exception ignored) {
		}
	}

	/**
	 * only for android
	 **/
	public static <T> void setPublic(T obj, Class<T> cls) {
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
		/*try {
			Class.forName(superClass.getName(), false, loader);
		} catch (Throwable e) {
			loader.addChild(superClass.getClassLoader());
			Log.info("ok");
		}*/
		if (Vars.mobile) {
			int mod = superClass.getModifiers();
			if (/*Modifier.isFinal(mod) || */Modifier.isPrivate(mod)) {
				setPublic(superClass, Class.class);
			}
			try {
				return ((GeneratedClassLoader) ((AndroidContextFactory) ContextFactory.getGlobal())
						.createClassLoader(superClass.getClassLoader()))
						.defineClass(name, bytes);
			} catch (Throwable e) {
				throw new RuntimeException(e);
			}
		} else {
			try {
				return getUnsafe().defineClass0(null, bytes, 0, bytes.length, superClass.getClassLoader(), null);
			} catch (Exception ex) {
				throw new RuntimeException(ex);
			}
		}
		// return unsafe.defineAnonymousClass(superClass, bytes, null);
	}

	public static Class<?> defineClass(String name, ClassLoader loader, byte[] bytes) {
		if (OS.isAndroid) {
			try {
				return ((GeneratedClassLoader) ((AndroidContextFactory) ContextFactory.getGlobal())
						.createClassLoader(loader))
						.defineClass(name, bytes);
			} catch (Throwable e) {
				throw new RuntimeException(e);
			}
		} else {
			try {
				return getUnsafe().defineClass0(null, bytes, 0, bytes.length, loader, null);
			} catch (Exception ex) {
				throw new RuntimeException(ex);
			}
		}
	}


	public static Class<?> defineClass(ClassLoader loader, byte[] bytes, ProtectionDomain pd) {
		try {
			return getUnsafe().defineClass0(null, bytes, 0, bytes.length,
					loader, pd);
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	public static Class<?> defineClass(ClassLoader loader, byte[] bytes) {
		return defineClass(loader, bytes, null);
	}

	public static Class<?> getCaller() {
		Thread thread = Thread.currentThread();
		StackTraceElement[] trace = thread.getStackTrace();
		try {
			return Class.forName(trace[3].getClassName(), false, Vars.mods.mainLoader());
		} catch (ClassNotFoundException e) {
			return null;
		}
	}
}
