package nipx;

import jdk.internal.misc.Unsafe;
import sun.reflect.ReflectionFactory;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodHandles.Lookup.ClassOption;
import java.lang.reflect.*;
import java.security.ProtectionDomain;

public class Reflect {
	public static final Lookup IMPL_LOOKUP;
	public static final Unsafe UNSAFE = Unsafe.getUnsafe();

	static {
		try {
			Constructor<?> constructor = ReflectionFactory.getReflectionFactory().newConstructorForSerialization(Lookup.class, Lookup.class.getDeclaredConstructor(Class.class));
			Lookup         lookup      = (Lookup) constructor.newInstance(Lookup.class);
			IMPL_LOOKUP = (Lookup) lookup.findStaticGetter(Lookup.class, "IMPL_LOOKUP", Lookup.class).invokeExact();
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	public static Class<?> defineClass(String className, byte[] bytes, int i, int length, ClassLoader loader,
	                                   ProtectionDomain o) {
		return UNSAFE.defineClass(className, bytes, i, length, loader, o);
	}

	static final Method legacyDefineClassMethod;

	static {
		Method m = null;
		try {
			m = Unsafe.class.getMethod("defineAnonymousClass", Class.class, byte[].class, Object[].class);
			m.setAccessible(true);
		} catch (Throwable ignored) { }
		legacyDefineClassMethod = m;
	}

	/**
	 * 自适应轻量级动态类定义：
	 * 1. 高版本 (JDK 15+): 优先使用 {@code MethodHandles.Lookup.defineHiddenClass} (无 ClassLoader 字典锁，支持 Metaspace 独立 GC 卸载)
	 * 2. 低版本 (JDK 8~14): 使用 {@code Unsafe.defineAnonymousClass} (轻量级 VM 宿主匿名类，支持独立卸载)
	 * @param hostClass 宿主类 (决定隐藏类的包名空间与类加载器)
	 * @param bytes     类字节码 (字节码内的包名需与宿主类包名一致，或由系统自动适配)
	 * @return 定义成功生成的 Class 对象
	 */

	public static Lookup defineHiddenClass(Class<?> hostClass, byte[] bytes) {
		try {
			return MethodHandles.privateLookupIn(hostClass, IMPL_LOOKUP).defineHiddenClass(bytes, true, ClassOption.NESTMATE);
		} catch (IllegalAccessException e) {
			if (legacyDefineClassMethod == null) throw new RuntimeException(e);
			try {
				return MethodHandles.privateLookupIn((Class<?>) legacyDefineClassMethod.invoke(UNSAFE, hostClass, bytes, null), IMPL_LOOKUP);
			} catch (IllegalAccessException | InvocationTargetException ex) {
				throw new RuntimeException(ex);
			}
		}
	}

	public static final int version = getVersion();
	private static int getVersion() {
		try {
			// JDK 9+ 标准 API
			return Runtime.version().feature();
		} catch (Throwable ignored) { }
		String version = System.getProperty("java.version");
		if (version == null) {
			return -1;
		}

		// 如果是老版本，格式通常为 1.8.0_xxx
		// 如果是新版本，格式通常为 11.0.x 或 12.0.x 或 17...
		String[] parts = version.split("\\.");
		if (parts.length > 0) {
			try {
				int major = Integer.parseInt(parts[0]);
				// 如果主版本号是 1，说明是 1.8 及以下，需要看第二个数字（例如 1.8 则是 8）
				if (major == 1 && parts.length > 1) {
					major = Integer.parseInt(parts[1]);
				}
				return major;
			} catch (NumberFormatException e) {
				// 预防部分非标准 JDK 供应商修改了版本字符串格式
				return -1;
			}
		}
		return -1;
	}
}
