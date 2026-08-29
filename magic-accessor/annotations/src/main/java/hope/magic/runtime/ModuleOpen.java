package hope.magic.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;

/**
 * 运行期模块权限开放工具。
 */
public class ModuleOpen {
	private static final MethodHandle OPEN_MODULE;

	public static void openModule(Module module, String packageName) {
		if (OPEN_MODULE != null && module != null) {
			try {
				OPEN_MODULE.invokeExact(module, packageName);
			} catch (Throwable ignored) {
			}
		}
	}

	static {
		MethodHandle handle = null;
		try {
			long off = Magic.unsafe.objectFieldOffset(Class.class.getDeclaredField("module"));
			Module javaBase = Object.class.getModule();
			Magic.unsafe.putObject(ModuleOpen.class, off, javaBase);
			handle = Magic.lookup.findVirtual(Module.class, "implAddOpens", MethodType.methodType(Void.TYPE, String.class));
		} catch (Throwable ignored) {
		}
		OPEN_MODULE = handle;
	}
}
