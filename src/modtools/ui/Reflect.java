package modtools.ui;

import arc.util.Log;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;

public class Reflect {
	static HashMap<String, Field> map = new HashMap<>();
	public static MethodHandles.Lookup lookup;
	public static MethodHandle modifiers;

	public static Field getField(Class<?> clazz, String name) throws NoSuchFieldException {
		if (map.containsKey(clazz.getName() + "." + name)) return map.get(clazz.getName() + "." + name);
		Field f = clazz.getDeclaredField(name);
		f.setAccessible(true);
		map.put(clazz.getName() + "." + name, f);
		return f;
	}


	/*public static void setValue(Object o, String name, Object val) throws Throwable {
//		lookup.findSetter(o.getClass(), name, clazz).invoke(o, val);
		Field f = getField(o.getClass(), name);
		f.set(o, val);
	}*/
	public static void setValue(Object o, Field field, Object val) {
		long offset = Modifier.isStatic(field.getModifiers()) ? unsafe.staticFieldOffset(field) : unsafe.objectFieldOffset(field);
		Log.info(offset);
		unsafe.putObject(o, offset, val);
	}

	public static <T> T getValue(Object o, String name, Class<?> clazz) throws Throwable {
//		return (T)lookup.findGetter(o.getClass(), name, clazz).invoke(o);
		Field f = getField(o.getClass(), name);
		if (f.getType() != clazz) return null;
		return (T) f.get(o);
	}
	public static <T> T getValue(Object o, Field field) {
		long offset = Modifier.isStatic(field.getModifiers()) ? unsafe.staticFieldOffset(field) : unsafe.objectFieldOffset(field);
		return (T)unsafe.getObject(o, offset);
	}

	public static Unsafe unsafe;

	/*public static void removeFinal(Field field) throws Throwable {
		unsafe.putObject(field, unsafe.objectFieldOffset(field), field.getModifiers() & ~Modifier.FINAL);
	}*/

	// init
	public static void load() {
		try {
			Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
			theUnsafe.setAccessible(true);
			unsafe = (Unsafe) theUnsafe.get(null);

			Field module = Class.class.getDeclaredField("module");
			long offset = unsafe.objectFieldOffset(module);
			unsafe.putObject(Reflect.class, offset, Object.class.getModule());

			/*Field field = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
			offset = unsafe.staticFieldOffset(field);
			lookup = (MethodHandles.Lookup) unsafe.getObject(MethodHandles.Lookup.class, offset);*/

		} catch (Exception e) {
			Log.err(e);
		}

	}
}
