package nipx;

import sun.misc.Unsafe;
import sun.reflect.ReflectionFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.*;
import java.security.ProtectionDomain;

public class Reflect {
	public static final Lookup IMPL_LOOKUP;
	public static final Unsafe UNSAFE = getUnsafe0();

	private static Unsafe getUnsafe0() {
		try {
			Field f = Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			return (Unsafe) f.get(null);
		} catch (Throwable t) {
			try {
				Constructor<Unsafe> c = Unsafe.class.getDeclaredConstructor();
				c.setAccessible(true);
				return c.newInstance();
			} catch (Throwable ignored) {
				return null;
			}
		}
	}

	static {
		try {
			Constructor<?> constructor = ReflectionFactory.getReflectionFactory().newConstructorForSerialization(Lookup.class, Lookup.class.getDeclaredConstructor(Class.class));
			Lookup         lookup      = (Lookup) constructor.newInstance(Lookup.class);
			IMPL_LOOKUP = (Lookup) lookup.findStaticGetter(Lookup.class, "IMPL_LOOKUP", Lookup.class).invokeExact();
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	/** {@code ClassLoader.defineClass(String, byte[], int, int, ProtectionDomain)}，形态 {@code (ClassLoader, ...)Class}。 */
	private static final MethodHandle classLoaderDefineClass;
	/** {@code Unsafe.defineClass(String, byte[], int, int, ClassLoader, ProtectionDomain)}，已绑定 Unsafe 实例。 */
	private static final MethodHandle unsafeDefineClass;

	static {
		// 注意：一律经 IMPL_LOOKUP 解析。sun.misc.Unsafe 在 JDK 11+ 已移除 defineClass，
		// 且 ClassLoader.defineClass 是 protected，反射 setAccessible 在 JDK 17+ 会被模块系统拒绝。
		MethodHandle cl = null;
		try {
			cl = IMPL_LOOKUP.findVirtual(ClassLoader.class, "defineClass", MethodType.methodType(
			 Class.class, String.class, byte[].class, int.class, int.class, ProtectionDomain.class));
		} catch (Throwable ignored) { }
		classLoaderDefineClass = cl;

		MethodType ut = MethodType.methodType(Class.class, String.class, byte[].class,
		 int.class, int.class, ClassLoader.class, ProtectionDomain.class);
		MethodHandle ud = null;
		try { // JDK 9+：jdk.internal.misc.Unsafe（经 IMPL_LOOKUP 获取，无需 --add-exports）
			Class<?> iu   = Class.forName("jdk.internal.misc.Unsafe");
			Object   inst = IMPL_LOOKUP.findStatic(iu, "getUnsafe", MethodType.methodType(iu)).invoke();
			ud = IMPL_LOOKUP.findVirtual(iu, "defineClass", ut).bindTo(inst);
		} catch (Throwable ignored) { }
		if (ud == null) {
			try { // JDK 8：sun.misc.Unsafe.defineClass
				ud = IMPL_LOOKUP.findVirtual(Unsafe.class, "defineClass", ut).bindTo(UNSAFE);
			} catch (Throwable ignored) { }
		}
		unsafeDefineClass = ud;
	}

	public static Class<?> defineClass(String className, byte[] bytes, int i, int length, ClassLoader loader,
	                                   ProtectionDomain o) {
		try {
			// 首选 Unsafe.defineClass：允许 loader == null，且不做 java.* / 签名者检查，与原行为一致
			if (unsafeDefineClass != null) {
				return (Class<?>) unsafeDefineClass.invoke(className, bytes, i, length, loader, o);
			}
			if (classLoaderDefineClass != null && loader != null) {
				return (Class<?>) classLoaderDefineClass.invoke(loader, className, bytes, i, length, o);
			}
		} catch (RuntimeException | Error e) {
			// 不能包装：调用方依赖 LinkageError（类已定义）走专门分支
			throw e;
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
		throw new UnsupportedOperationException("defineClass is not available on this JVM runtime");
	}

	static final Method   legacyDefineClassMethod;
	static final Method   privateLookupInMethod;
	static final Method   defineHiddenClassMethod;
	static final Object[] NESTMATE_OPTIONS;

	static {
		Method m = null;
		try {
			m = Unsafe.class.getMethod("defineAnonymousClass", Class.class, byte[].class, Object[].class);
			m.setAccessible(true);
		} catch (Throwable ignored) { }
		legacyDefineClassMethod = m;

		Method   privLookup   = null;
		Method   defHidden    = null;
		Object[] nestmateOpts = null;
		try {
			privLookup = MethodHandles.class.getMethod("privateLookupIn", Class.class, Lookup.class);
			Class<?> classOptionClass = Class.forName("java.lang.invoke.MethodHandles$Lookup$ClassOption");
			@SuppressWarnings({"unchecked", "rawtypes"})
			Object nestmateOption = Enum.valueOf((Class<Enum>) classOptionClass, "NESTMATE");
			Object array = Array.newInstance(classOptionClass, 1);
			Array.set(array, 0, nestmateOption);
			nestmateOpts = (Object[]) array;
			defHidden = Lookup.class.getMethod("defineHiddenClass", byte[].class, boolean.class, array.getClass());
		} catch (Throwable ignored) { }
		privateLookupInMethod = privLookup;
		defineHiddenClassMethod = defHidden;
		NESTMATE_OPTIONS = nestmateOpts;
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
		// 1. 高版本 (JDK 15+): 反射调用 Lookup.defineHiddenClass
		if (defineHiddenClassMethod != null && privateLookupInMethod != null) {
			try {
				Lookup privateLookup = (Lookup) privateLookupInMethod.invoke(null, hostClass, IMPL_LOOKUP);
				return (Lookup) defineHiddenClassMethod.invoke(privateLookup, bytes, Boolean.TRUE, NESTMATE_OPTIONS);
			} catch (Throwable e) {
				throw new RuntimeException("Failed to define hidden class for host: " + hostClass.getName(), e);
			}
		}

		// 2. 低版本 (JDK 8~14): 回退到 Unsafe.defineAnonymousClass
		if (legacyDefineClassMethod != null) {
			try {
				Class<?> anonClass = (Class<?>) legacyDefineClassMethod.invoke(UNSAFE, hostClass, bytes, null);
				if (privateLookupInMethod != null) {
					return (Lookup) privateLookupInMethod.invoke(null, anonClass, IMPL_LOOKUP);
				}
				// JDK 8: 直接用全特权 IMPL_LOOKUP 获取匿名类的 Lookup
				return IMPL_LOOKUP.in(anonClass);
			} catch (Throwable ex) {
				throw new RuntimeException("Failed to define anonymous class for host: " + hostClass.getName(), ex);
			}
		}

		throw new UnsupportedOperationException("Neither defineHiddenClass nor defineAnonymousClass is available.");
	}

	public static final int version = getVersion();
	private static int getVersion() {
		try {
			// JDK 9+ 标准 API (反射探测以避免 JDK 8 链接错误)
			Method versionMethod = Runtime.class.getMethod("version");
			Object v             = versionMethod.invoke(null);
			Method featureMethod = v.getClass().getMethod("feature");
			return ((Integer) featureMethod.invoke(v)).intValue();
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

	public static final boolean isAndroid = isAndroid0();
	private static boolean isAndroid0() {
		try {
			Class.forName("android.os.Build");
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}

}