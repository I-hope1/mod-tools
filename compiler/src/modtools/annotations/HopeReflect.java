package modtools.annotations;

import com.sun.source.util.DocTrees;
import jdk.internal.module.Modules;
import sun.misc.Unsafe;
import sun.reflect.ReflectionFactory;

import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;

@SuppressWarnings({"unchecked", "deprecation"})
public class HopeReflect {
	public static Unsafe unsafe = getUnsafe();
	public static Lookup lookup = getLookup();

	static {
		try {
			Field  f      = Class.class.getDeclaredField("module");
			long   off    = unsafe.objectFieldOffset(f);
			Module module = Object.class.getModule();

			unsafe.putObject(HopeReflect.class, off, module);

			openModule();
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	private static Unsafe getUnsafe() {
		try {
			Field f = Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			return (Unsafe) f.get(null);
		} catch (Exception e) { throw new RuntimeException(e); }
	}
	private static Lookup getLookup() {
		try {
			Lookup lookup = (Lookup) ReflectionFactory.getReflectionFactory().newConstructorForSerialization(
			 Lookup.class, Lookup.class.getDeclaredConstructor(Class.class)
			).newInstance(Lookup.class);
			lookup = (Lookup) lookup.findStaticVarHandle(Lookup.class, "IMPL_LOOKUP", Lookup.class).get();
			return lookup;
		} catch (Exception e) { throw new RuntimeException(e); }
	}
	static Module EVERYONE_MODULE;
	public static void openModule() throws Throwable {
		lookup.findVirtual(Module.class, "implAddOpens", MethodType.methodType(Void.TYPE, String.class))
		 .invokeExact(Object.class.getModule(), "jdk.internal.module");
		EVERYONE_MODULE = (Module) lookup.findStaticGetter(Module.class, "EVERYONE_MODULE", Module.class).invoke();

		openTrust(Object.class.getModule(),
		 "jdk.internal.misc",
		 "sun.reflect.annotation",
		 "jdk.internal.access",
		 "jdk.internal.org.objectweb.asm"
		);

		openTrust(DocTrees.class.getModule(),
		 "com.sun.tools.javac.api",
		 "com.sun.tools.javac.code",
		 "com.sun.tools.javac.comp",
		 "com.sun.tools.javac.tree",
		 "com.sun.tools.javac.main",
		 "com.sun.tools.javac.model",
		 "com.sun.tools.javac.jvm",
		 "com.sun.tools.javac.parser",
		 "com.sun.tools.javac.processing",
		 "com.sun.tools.javac.resources",
		 "com.sun.tools.javac.util"
		);
		// Modules.addOpens(AttributeTree.class.getModule(), "", MyReflect.class.getModule());
	}
	public static void openTrust(Module module, String... pkgs) {
		for (String pkg : pkgs) {
			Modules.addOpens(module, pkg, EVERYONE_MODULE);
			/* debug模式可能不加载 编译参数（当然在 gradle.properties 里加也可以）  */
			Modules.addExports(module, pkg);
		}
	}


	public static <T> T invoke(Object object, String name, Object[] args, Class<?>... parameterTypes) {
		return invoke(object.getClass(), object, name, args, parameterTypes);
	}
	public static <T> T invoke(Class<?> type, Object object, String name, Object[] args, Class<?>... parameterTypes) {
		try {
			Method method = type.getDeclaredMethod(name, parameterTypes);
			method.setAccessible(true);
			return (T) method.invoke(object, args);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static <T> T getAccess(Class<?> cls, Object obj, String name) {
		try {
			Field field = cls.getDeclaredField(name);
			field.setAccessible(true);
			return (T) field.get(obj);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	public static <T> T get(Object obj, String name) {
		try {
			Field field = obj.getClass().getField(name);
			field.setAccessible(true);
			return (T) field.get(obj);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	public static void replaceAccess(Class<?> clazz, Object obj, String targetName, String withName) {
		try {
			unsafe.putObject(obj, unsafe.objectFieldOffset(clazz.getDeclaredField(targetName)),
			 unsafe.getObject(obj, unsafe.objectFieldOffset(clazz.getDeclaredField(withName))));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static <T> void setAccess(Class<? extends T> clazz, T obj, String name, Object value) {
		Field field;
		try {
			field = clazz.getDeclaredField(name);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
		long offset = fieldOffset(clazz, name);
		if (offset == -1) throw new IllegalArgumentException(clazz + "." + name);
		Object o = Modifier.isStatic(field.getModifiers()) ? clazz : obj;

		if (!field.getType().isPrimitive()) {
			unsafe.putObject(o, offset, value);
			return;
		}

		switch (field.getType().getSimpleName()) {
			case "int" -> unsafe.putInt(o, offset, (int) value);
			case "long" -> unsafe.putLong(o, offset, (long) value);
			case "float" -> unsafe.putFloat(o, offset, (float) value);
			case "double" -> unsafe.putDouble(o, offset, (double) value);
			case "short" -> unsafe.putShort(o, offset, (short) value);
			case "char" -> unsafe.putChar(o, offset, (char) value);
			case "boolean" -> unsafe.putBoolean(o, offset, (boolean) value);
			case "byte" -> unsafe.putByte(o, offset, (byte) value);
			default -> throw new RuntimeException("Unexpected type: " + field.getType().getTypeName());
		}
	}
	public static void set(Class<?> clazz, Object obj, String name, Object value) {
		try {
			Field field = clazz.getField(name);
			unsafe.putObject(obj, unsafe.objectFieldOffset(field), value);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	public static long fieldOffset(Class<?> clazz, String fieldName) {
		return jdk.internal.misc.Unsafe.getUnsafe().objectFieldOffset(clazz, fieldName);
	}
	/** 主要是加载{@code <clinit>} */
	public static void load() { }

	public static void setAccessible(AccessibleObject obj) {
		obj.setAccessible(true);
	}
	public static <T> T nl(SupplierT<T> supplier) {
		try {
			T t = supplier.get();
			if (t instanceof AccessibleObject ac) ac.setAccessible(true);
			return t;
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}
	public static <T> T iv(Method method, Object obj, Object... args) {
		try {
			return (T) method.invoke(obj, args);
		} catch (IllegalAccessException | InvocationTargetException e) {
			throw new RuntimeException(e);
		}
	}
	public interface SupplierT<T> {
		T get() throws Throwable;
	}
}
