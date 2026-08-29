package hope.magic.compiler;

import com.sun.source.util.DocTrees;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.util.Context;
import sun.misc.Unsafe;

import javax.annotation.processing.ProcessingEnvironment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

@SuppressWarnings({"unchecked", "deprecation"})
public class JavacHelper {
	public static final Unsafe unsafe = getUnsafe();
	public static final Lookup lookup = getLookup();

	static {
		try {
			openInternalModules();
		} catch (Throwable ignored) {
		}
	}

	public static Context getContext(ProcessingEnvironment env) {
		if (env instanceof JavacProcessingEnvironment jcEnv) {
			return jcEnv.getContext();
		} else if (Proxy.isProxyClass(env.getClass())) {
			var handler = Proxy.getInvocationHandler(env);
			for (Field field : handler.getClass().getDeclaredFields()) {
				try {
					field.setAccessible(true);
					Object obj = field.get(handler);
					if (obj instanceof ProcessingEnvironment nestedEnv) {
						try {
							return getContext(nestedEnv);
						} catch (Exception ignored) {
						}
					}
				} catch (Throwable ignored) {
				}
			}
			throw new RuntimeException("Failed to unwrap proxy ProcessingEnvironment: " + handler);
		} else {
			try {
				Field f = env.getClass().getDeclaredField("delegate");
				f.setAccessible(true);
				return ((JavacProcessingEnvironment) f.get(env)).getContext();
			} catch (Throwable e) {
				for (Field field : env.getClass().getDeclaredFields()) {
					try {
						field.setAccessible(true);
						Object val = field.get(env);
						if (val instanceof JavacProcessingEnvironment jcEnv) {
							return jcEnv.getContext();
						}
					} catch (Throwable ignored) {
					}
				}
				throw new RuntimeException("Unable to obtain javac Context from " + env.getClass(), e);
			}
		}
	}

	public static void openInternalModules() {
		try {
			Field f = Class.class.getDeclaredField("module");
			long off = unsafe.objectFieldOffset(f);
			Module javaBase = Object.class.getModule();
			unsafe.putObject(JavacHelper.class, off, javaBase);

			MethodHandle openMethod = lookup.findVirtual(
				Module.class, "implAddOpens", MethodType.methodType(Void.TYPE, String.class)
			);
			MethodHandle exportMethod = lookup.findVirtual(
				Module.class, "implAddExports", MethodType.methodType(Void.TYPE, String.class)
			);

			Module everyoneModule = null;
			try {
				everyoneModule = (Module) lookup.findStaticGetter(Module.class, "EVERYONE_MODULE", Module.class).invoke();
			} catch (Throwable ignored) {
			}

			String[] pkgs = {
				"com.sun.tools.javac.api",
				"com.sun.tools.javac.code",
				"com.sun.tools.javac.comp",
				"com.sun.tools.javac.tree",
				"com.sun.tools.javac.main",
				"com.sun.tools.javac.model",
				"com.sun.tools.javac.jvm",
				"com.sun.tools.javac.parser",
				"com.sun.tools.javac.processing",
				"com.sun.tools.javac.util"
			};

			Module compilerModule = DocTrees.class.getModule();
			for (String pkg : pkgs) {
				try {
					if (openMethod != null) openMethod.invoke(compilerModule, pkg);
					if (exportMethod != null) exportMethod.invoke(compilerModule, pkg);
				} catch (Throwable ignored) {
				}
			}
		} catch (Throwable ignored) {
		}
	}

	private static Unsafe getUnsafe() {
		try {
			Field f = Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			return (Unsafe) f.get(null);
		} catch (Throwable e) {
			try {
				Field f = Unsafe.class.getDeclaredField("theInternalUnsafe");
				f.setAccessible(true);
				return (Unsafe) f.get(null);
			} catch (Throwable ex) {
				throw new RuntimeException(ex);
			}
		}
	}

	private static Lookup getLookup() {
		try {
			Field implLookupField = Lookup.class.getDeclaredField("IMPL_LOOKUP");
			long offset = unsafe.staticFieldOffset(implLookupField);
			return (Lookup) unsafe.getObject(Lookup.class, offset);
		} catch (Throwable e) {
			try {
				Lookup baseLookup = MethodHandles.lookup();
				Field modesField = Lookup.class.getDeclaredField("allowedModes");
				long offset = unsafe.objectFieldOffset(modesField);
				unsafe.putInt(baseLookup, offset, -1);
				return baseLookup;
			} catch (Throwable ex) {
				throw new RuntimeException(ex);
			}
		}
	}
}
