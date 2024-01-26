package modtools.jsfunc.reflect;

import arc.struct.ObjectMap;
import ihope_lib.MyReflect;
import mindustry.Vars;
import modtools.ui.content.debug.Tester;
import modtools.utils.JSFunc;
import rhino.Scriptable;

import java.lang.invoke.MethodHandle;

@SuppressWarnings("unchecked")
public interface REFLECT {
	ObjectMap<String, Scriptable> classes = new ObjectMap<>();

	/** 如果不用Object，安卓上会出问题 */
	static <T> T invoke(Object l, Object... args) throws Throwable {
		return (T) ((MethodHandle) l).invokeWithArguments(args);
	}
	static <T> T invoke(Object l, Object arg1) throws Throwable {
		return (T) ((MethodHandle) l).invoke(arg1);
	}
	static <T> T invoke(Object l, Object arg1, Object arg2) throws Throwable {
		return (T) ((MethodHandle) l).invoke(arg1, arg2);
	}
	static <T> T invoke(Object l, Object arg1, Object arg2, Object arg3) throws Throwable {
		return (T) ((MethodHandle) l).invoke(arg1, arg2, arg3);
	}
	// --------------
	static Scriptable findClass(String name) throws ClassNotFoundException {
		if (classes.containsKey(name)) {
			return classes.get(name);
		} else {
			Scriptable clazz = Tester.cx.getWrapFactory().wrapJavaClass(Tester.cx, JSFunc.scope, JSFunc.main.loadClass(name));
			classes.put(name, clazz);
			return clazz;
		}
	}
	static Class<?> forName(String name) throws ClassNotFoundException {
		return Class.forName(name, false, Vars.mods.mainLoader());
	}
}
