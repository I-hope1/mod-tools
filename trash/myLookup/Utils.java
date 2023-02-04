package myLookup;

import arc.util.Log;
import mindustry.Vars;
import mindustry.mod.Scripts;
import rhino.Context;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;

import static modtools.utils.MyReflect.unsafe;

public class Utils {
	public static final Lookup lookup;

	static {
		lookup = getLookup();
	}

	public static Lookup getLookup() {
		try {
			if (Vars.mobile) {
				var scripts = new Scripts();
				Field field = (Field) Context.jsToJava(scripts.context.evaluateString(scripts.scope, "Seq([java.lang.invoke.MethodHandles.Lookup]).get(0).getDeclaredFields().find(f=>f.name=='IMPL_LOOKUP')", "none", 1), Field.class);
//				Field field = new Seq<>(Lookup.class.getDeclaredFields()).find(f -> f.getName().equals("IMPL_LOOKUP"));
				field.setAccessible(true);
				return (Lookup) field.get(Lookup.class);
			} else {
				Field field = Lookup.class.getDeclaredField("IMPL_LOOKUP");
				long offset = unsafe.staticFieldOffset(field);
				return (Lookup) unsafe.getObject(Lookup.class, offset);
			}
		} catch (Exception e) {
			Log.err("获取IMPL_LOOKUP异常", e);
		}
		return null;
	}


	public static void setOverdie(AccessibleObject override) throws Throwable {
		if (lookup == null) return;
		MethodHandle handle = lookup.findSetter(AccessibleObject.class, "override", boolean.class);
		handle.invokeExact(override, true);
	}

	public static <T, R> void lookupSetObject(T obj, Class<T> clazz, String key, Class<R> type, R toVal) throws Throwable {
		if (lookup == null) return;
		MethodHandle handle = lookup.findSetter(clazz, key, type);
		handle.invoke(obj, toVal);
	}
}
