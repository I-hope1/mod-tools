package modtools.utils.reflect;

import arc.Core;
import arc.math.geom.Vec2;
import arc.util.*;
import dalvik.system.VMStack;
import mindustry.Vars;
import mindustry.android.AndroidRhinoContext;
import mindustry.android.AndroidRhinoContext.AndroidContextFactory;
import modtools.jsfunc.reflect.UNSAFE;
import rhino.*;

import java.io.File;
import java.lang.reflect.*;

public class HopeReflect {

	//region 安卓黑科技
	public static void changeClass(Object obj, Class<?> clazz) {
		if (!OS.isAndroid) return;
		class $ {
			static final long offset = FieldUtils.fieldOffset(Object.class, "shadow$_klass_");
		}
		UNSAFE.putObject(obj, $.offset, clazz);
	}
	/** 同时去除final  */
	public static <T extends Class<?>> void setPublic(T obj, Class<T> cls) {
		setPublic0(obj, cls);
	}
	/** 只在Android上可用 （Android Only） **/
	public static <T extends Member> void setPublic(T obj, Class<T> cls) {
		setPublic0(obj, cls);
	}
	private static void setPublic0(Object obj, Class<?> cls) {
		if (!OS.isAndroid) return;
		try {
			Field f = cls.getDeclaredField("accessFlags");
			f.setAccessible(true);
			int flags = f.getInt(obj);
			flags &= 0xFFFF;
			flags &= ~Modifier.FINAL;
			flags &= ~Modifier.PRIVATE;
			flags |= Modifier.PUBLIC;
			f.setInt(obj, flags & 0xFFFF);
		} catch (Exception ignored) { }
	}
	//endregion

	public static Class<?> defineClass(String name, ClassLoader loader, byte[] bytes) {
		if (OS.isAndroid) return defineClassAndroid(name, loader, bytes);
		return UnsafeHandler.defineClass(name, bytes, loader);
	}
	private static Class<?> defineClassAndroid(String name, ClassLoader loader, byte[] bytes) {
		if (!(ContextFactory.getGlobal() instanceof AndroidContextFactory)) {
			AndroidRhinoContext.enter(new File(Core.settings.getDataDirectory() + "/rhino/"));
		}
		return ((GeneratedClassLoader) ((AndroidContextFactory) ContextFactory.getGlobal())
		 .createClassLoader(loader))
		 .defineClass(name, bytes);
	}

	public static Class<?> getCaller() {
		if (OS.isAndroid) return VMStack.getStackClass2();
		// if (IntVars.javaVersion >= 9) return StackWalker.getInstance().getCallerClass();
		Thread              thread = Thread.currentThread();
		StackTraceElement[] trace  = thread.getStackTrace();
		try {
			return Class.forName(trace[3].getClassName(), false, Vars.mods.mainLoader());
		} catch (ClassNotFoundException e) {
			return null;
		}
	}
	public static boolean isSameVal(Object val1, Object val2, Class<?> valType) {
		if (val1 == val2) {
			if (valType.isPrimitive() || Reflect.isWrapper(valType) || valType == String.class
			    || valType == Class.class || (val1 != null && val1.getClass() == Object.class)) {
				return true;
			}
		}

		if (val2 != null && val1 != null &&
		    val2.getClass() == Vec2.class && val1.getClass() == Vec2.class &&
		    val2.equals(val1)) { return true; }

		return false;
	}

	/* @ModuleOpen
	public static void moduleOpen() {

	} */
}
