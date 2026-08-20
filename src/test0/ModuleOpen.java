package test0;

import java.lang.invoke.*;

import static test0.Magic.*;

public class ModuleOpen {
	private static final MethodHandle OPEN_MODULE;

	public static void openModule(Module module, String pn) throws Throwable {
		OPEN_MODULE.invokeExact(module, pn);
	}

	static {
		try {
			long   off       = unsafe.objectFieldOffset(Class.class.getDeclaredField("module"));
			Module java_base = Object.class.getModule();
			unsafe.putObject(Magic.class, off, java_base);
			OPEN_MODULE = lookup.findVirtual(Module.class, "implAddOpens", MethodType.methodType(Void.TYPE, String.class));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
