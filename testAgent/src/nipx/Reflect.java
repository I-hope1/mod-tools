package nipx;

import sun.reflect.ReflectionFactory;

import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Constructor;

public class Reflect {
	public static final Lookup IMPL;
	static {
		try {
			Constructor<?> constructor = ReflectionFactory.getReflectionFactory().newConstructorForSerialization(Lookup.class, Lookup.class.getDeclaredConstructor(Class.class));
			Lookup lookup = (Lookup) constructor.newInstance(Lookup.class);
			IMPL = (Lookup) lookup.findStaticGetter(Lookup.class, "IMPL_LOOKUP", Lookup.class).invokeExact();
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}
}
