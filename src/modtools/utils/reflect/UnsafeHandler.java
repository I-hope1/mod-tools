package modtools.utils.reflect;

import ihope_lib.MyReflect;
import jdk.internal.misc.Unsafe;

import java.lang.reflect.*;
import java.security.ProtectionDomain;

public class UnsafeHandler {
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
	public static Class<?> defineClass(String name, byte[] b, ClassLoader loader) {
		return defineClass(name, b, loader, null);
	}

	public static Class<?> defineClass(String name, byte[] b, ClassLoader loader, ProtectionDomain pd) {
		try {
			return ((Unsafe) unsafe).defineClass(name, b, 0, b.length, loader, pd);
		} catch (ClassCastException | NoClassDefFoundError ignored) {}
		try {
			return (Class<?>) getMethod().invoke(unsafe, name, b, 0, b.length, loader, pd);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
