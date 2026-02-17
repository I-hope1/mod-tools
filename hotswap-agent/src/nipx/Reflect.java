package nipx;

import jdk.internal.misc.Unsafe;
import sun.reflect.ReflectionFactory;

import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Constructor;
import java.security.ProtectionDomain;

public class Reflect {
	public static final Lookup IMPL_LOOKUP;
	public static final Unsafe UNSAFE = Unsafe.getUnsafe();
	static {
		try {
			Constructor<?> constructor = ReflectionFactory.getReflectionFactory().newConstructorForSerialization(Lookup.class, Lookup.class.getDeclaredConstructor(Class.class));
			Lookup lookup = (Lookup) constructor.newInstance(Lookup.class);
			IMPL_LOOKUP = (Lookup) lookup.findStaticGetter(Lookup.class, "IMPL_LOOKUP", Lookup.class).invokeExact();
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	public static Class<?> defineClass(String className, byte[] bytes, int i, int length, ClassLoader loader, ProtectionDomain o) {
		return UNSAFE.defineClass(className, bytes, i, length, loader, o);
	}
}
