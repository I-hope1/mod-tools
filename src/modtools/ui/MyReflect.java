package modtools.ui;

import arc.util.Log;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;

public class MyReflect {
	static HashMap<String, Field> map = new HashMap<>();

	public static Field getField(Class<?> clazz, String name) throws NoSuchFieldException {
		if (map.containsKey(clazz.getName() + "." + name)) return map.get(clazz.getName() + "." + name);
		Field f = clazz.getDeclaredField(name);
		f.setAccessible(true);
		map.put(clazz.getName() + "." + name, f);
		return f;
	}


	/*public static void setValue(Object obj, String name, Object val) throws Throwable {
//		lookup.findSetter(obj.getClass(), name, clazz).invoke(obj, val);
		Field f = getField(obj.getClass(), name);
		f.set(obj, val);
	}*/
	
	public static void setValue(Object obj, String name, Object val) throws Exception {
		setValue(obj, obj.getClass(), name, val);
	}
	public static void setValue(Object obj, Class<?> clazz, String name, Object val) throws Exception {

		getField(clazz, name).set(obj, val);
	}

	public static <T> T getValue(Object obj, String name) throws Exception {
		return getValue(obj, obj.getClass(), name);
	}
	public static <T> T getValue(Object obj, Class<?> clazz, String name) throws Exception {
		Field f = getField(clazz, name);
		return (T) f.get(obj);
	}


	// use unsafe
	public static void setValue(Object obj, Field field, Object val) {
		long offset = Modifier.isStatic(field.getModifiers()) ? unsafe.staticFieldOffset(field) : unsafe.objectFieldOffset(field);
//		Log.info(offset);
		unsafe.putObject(obj, offset, val);
	}

	public static <T> T getValue(Object obj, Field field) {
		long offset = Modifier.isStatic(field.getModifiers()) ? unsafe.staticFieldOffset(field) : unsafe.objectFieldOffset(field);
		return (T)unsafe.getObject(obj, offset);
	}

	private static Unsafe unsafe;

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
			unsafe.putObject(MyReflect.class, offset, Object.class.getModule());

			/*Field field = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
			offset = unsafe.staticFieldOffset(field);
			lookup = (MethodHandles.Lookup) unsafe.getObject(MethodHandles.Lookup.class, offset);*/

		} catch (Exception e) {
			Log.err(e);
		}

	}
}
