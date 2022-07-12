package modtools.ui;

import arc.util.Log;
import mindustry.Vars;
import mindustry.desktop.DesktopLauncher;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
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
		unsafe.putObject(obj, unsafe.objectFieldOffset(field), val);
	}
	public static void setStaticValue(Object obj, Field field, Object val) {
		unsafe.putObject(obj, unsafe.staticFieldOffset(field), val);
	}

	public static <T> T getValue(Object obj, Field field) {
		return (T) unsafe.getObject(obj, unsafe.objectFieldOffset(field));
	}
	public static <T> T getStaticValue(Object obj, Field field) {
		return (T) unsafe.getObject(obj, unsafe.staticFieldOffset(field));
	}

	public static final Unsafe unsafe;

	static {
		unsafe = getUnsafe();
	}


	/*public static void removeFinal(Field field) throws Throwable {
		unsafe.putObject(field, unsafe.objectFieldOffset(field), field.getModifiers() & ~Modifier.FINAL);
	}*/

	public static Unsafe getUnsafe() {

		try {
			Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
			theUnsafe.setAccessible(true);
			return (Unsafe) theUnsafe.get(null);
		} catch (Throwable e) {
			Log.err("获取Unsafe异常", e);
		}
		return null;
	}

	public static void setModule() throws NoSuchFieldException {
		if (!Vars.mobile) {
			Field module = Class.class.getDeclaredField("module");
			long offset = unsafe.objectFieldOffset(module);
			unsafe.putObject(DesktopLauncher.class, offset, Object.class.getModule());
		}
	}

	// init
	public static void load() {
		try {
			setModule();
		} catch (Exception e) {
			Log.err("设置模块异常", e);
		}

		/*Time.runTask(0f, () -> {
			try {
				Field iunsafe = Unsafe.class.getDeclaredField("theInternalUnsafe");
				long offset2 = unsafe.staticFieldOffset(iunsafe);
				tester.put("unsafe", unsafe.getObject(null, offset2));
			} catch (NoSuchFieldException e) {
				Log.err(e);
			}
		});*/
//			lookup.findGetter(HudFragment.class, "blockfrag", PlacementFragment.class).invoke();

			/*Events.run(EventType.ClientLoadEvent.class, () -> {
				Time.runTask(1f, () -> {
					Log.info("mzikaza-load-scripts");
					JSFunc.eval(Vars.mods.locateMod(IntVars.modName).root
							.child("_scripts").child("main.js").readString());
				});
			});*/

	}
}
