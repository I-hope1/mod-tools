package modtools.utils.reflect;

import arc.util.*;
import ihope_lib.MyReflect;
import jdk.internal.misc.Unsafe;
import modtools.jsfunc.reflect.UNSAFE;

import java.lang.reflect.*;
import java.security.ProtectionDomain;

public class UnsafeHandler {
	static {
		UNSAFE.openModule(Object.class, "jdk.internal.misc");
	}

	private static final Object unsafe = getUnsafe();
	static Object getUnsafe() {
		try {
			return Unsafe.getUnsafe();
		} catch (Throwable e) {
			return MyReflect.unsafe;
		}
	}

	private static Method method;
	static Method getMethod() throws NoSuchMethodException {
		if (method == null) {
			/* java 8 特有 */
			//noinspection JavaReflectionMemberAccess
			method = sun.misc.Unsafe.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class, ClassLoader.class, ProtectionDomain.class);
			method.setAccessible(true);
		}
		return method;
	}
	public static Class<?> defineClass(@Nullable String name, byte[] b, ClassLoader loader) {
		return defineClass(name, b, loader, null);
	}

	private static Boolean hasJDKUnsafe;
	public static Class<?> defineClass(String name, byte[] b, ClassLoader loader, ProtectionDomain pd) {
		if (hasJDKUnsafe == null) {
			try {
				((Unsafe) unsafe).getClass();
				hasJDKUnsafe = true;
			} catch (ClassCastException | NoClassDefFoundError ignored) {hasJDKUnsafe = false;}
		}

		if (hasJDKUnsafe) {
			return ((Unsafe) unsafe).defineClass(name, b, 0, b.length, loader, pd);
		}

		try {
			return (Class<?>) getMethod().invoke(unsafe, name, b, 0, b.length, loader, pd);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
