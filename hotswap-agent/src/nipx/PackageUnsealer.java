package nipx;

import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.net.URL;

/** 运行时解除 JAR Package 密封（sealed）限制工具类 */
public class PackageUnsealer {
	private static final VarHandle VERSION_INFO_VH;
	private static final VarHandle SEAL_BASE_VH;
	private static final VarHandle SEAL_BASE_DIRECT_VH;

	static {
		VarHandle versionInfoVh = null;
		VarHandle sealBaseVh = null;
		VarHandle sealBaseDirectVh = null;
		try {
			Lookup lookup = Reflect.IMPL_LOOKUP;
			try {
				Class<?> versionInfoClass = Class.forName("java.lang.Package$VersionInfo");
				versionInfoVh = lookup.findVarHandle(Package.class, "versionInfo", versionInfoClass);
				sealBaseVh = lookup.findVarHandle(versionInfoClass, "sealBase", URL.class);
			} catch (ClassNotFoundException ignored) {
				sealBaseDirectVh = lookup.findVarHandle(Package.class, "sealBase", URL.class);
			}
		} catch (Throwable t) {
			HotSwapAgent.error("[PackageUnsealer] Failed to initialize VarHandles.", t);
		}
		VERSION_INFO_VH = versionInfoVh;
		SEAL_BASE_VH = sealBaseVh;
		SEAL_BASE_DIRECT_VH = sealBaseDirectVh;
	}

	/** 解除指定 Package 对象的密封限制 */
	public static boolean unsealPackage(Package pkg) {
		if (pkg == null || !pkg.isSealed()) return false;
		try {
			if (VERSION_INFO_VH != null && SEAL_BASE_VH != null) {
				Object versionInfo = VERSION_INFO_VH.get(pkg);
				if (versionInfo != null) {
					SEAL_BASE_VH.set(versionInfo, null);
					if (HotSwapAgent.DEBUG) HotSwapAgent.info("[PackageUnsealer] Unsealed package: " + pkg.getName());
					return true;
				}
			} else if (SEAL_BASE_DIRECT_VH != null) {
				SEAL_BASE_DIRECT_VH.set(pkg, null);
				if (HotSwapAgent.DEBUG) HotSwapAgent.info("[PackageUnsealer] Unsealed package: " + pkg.getName());
				return true;
			}
		} catch (Throwable t) {
			HotSwapAgent.error("[PackageUnsealer] Failed to unseal package: " + pkg.getName(), t);
		}
		return false;
	}

	/** 解除指定 ClassLoader 及其父加载器下某个包的密封限制 */
	public static void unsealPackage(ClassLoader loader, String packageName) {
		if (loader == null || packageName == null || packageName.isEmpty()) return;
		String normalized = packageName.replace('/', '.').replace('\\', '.');
		if (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
		if (normalized.startsWith(".")) normalized = normalized.substring(1);
		for (ClassLoader cl = loader; cl != null; cl = cl.getParent()) {
			Package pkg = cl.getDefinedPackage(normalized);
			if (pkg != null && pkg.isSealed()) {
				unsealPackage(pkg);
				return;
			}
		}
	}

	/** 根据类全限定名解除其所在包的密封限制 */
	public static void unsealClassPackage(ClassLoader loader, String className) {
		if (loader == null || className == null) return;
		int lastDot = Math.max(className.lastIndexOf('.'), Math.max(className.lastIndexOf('/'), className.lastIndexOf('\\')));
		if (lastDot <= 0) return;
		unsealPackage(loader, className.substring(0, lastDot));
	}

	/** 解除指定 Class 所在包的密封限制 */
	public static void unsealPackage(Class<?> clazz) {
		if (clazz == null) return;
		Package pkg = clazz.getPackage();
		if (pkg != null) {
			unsealPackage(pkg);
		} else {
			unsealClassPackage(clazz.getClassLoader(), clazz.getName());
		}
	}

	/** 解除指定 ClassLoader 中所有已定义包的密封限制 */
	public static void unsealAllPackages(ClassLoader loader) {
		if (loader == null) return;
		for (Package pkg : loader.getDefinedPackages()) {
			unsealPackage(pkg);
		}
	}
}
