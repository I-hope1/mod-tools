package modtools.utils.reflect;

import arc.func.Prov;
import arc.util.Log;
import ihope_lib.MyReflect;
import modtools.utils.SR.CatchSR;
import modtools.utils.Tools.CProv;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.Locale;

public interface IReflect {
	Field[] getFields(Class<?> cls);
	Method[] getMethods(Class<?> cls);
	Constructor<?>[] getConstructors(Class<?> cls);

	IReflect impl = CatchSR.apply(() ->
	 CatchSR.<IReflect>of(DesktopImpl::new)
		.get(AndroidImpl::new)
		.get(DefaultImpl::new)
	);
	class DesktopImpl implements IReflect {
		MethodHandle getFieldsHandle       = MyReflect.lookup.findVirtual(Class.class, "getDeclaredFields0", MethodType.methodType(Field[].class, boolean.class));
		MethodHandle getMethodsHandle      = MyReflect.lookup.findVirtual(Class.class, "getDeclaredMethods0", MethodType.methodType(Method[].class, boolean.class));
		MethodHandle getConstructorsHandle = MyReflect.lookup.findVirtual(Class.class, "getDeclaredConstructors0", MethodType.methodType(Constructor[].class, boolean.class));
		public DesktopImpl() throws NoSuchMethodException, IllegalAccessException {}
		public Field[] getFields(Class<?> cls) {
			return nls(() -> getFieldsHandle.invokeExact(cls, false), () -> new Field[0]);
		}
		public Method[] getMethods(Class<?> cls) {
			return nls(() -> getMethodsHandle.invokeExact(cls, false), () -> new Method[0]);
		}
		public Constructor<?>[] getConstructors(Class<?> cls) {
			return nls(() -> (Constructor<?>[]) getConstructorsHandle.invokeExact(cls, false), () -> new Constructor[0]);
		}
	}
	class AndroidImpl implements IReflect {
		Method fs = Class.class.getDeclaredMethod("getDeclaredFields");
		Method ms = Class.class.getDeclaredMethod("getDeclaredMethods");
		Method cs = Class.class.getDeclaredMethod("getDeclaredConstructors");
		public AndroidImpl() throws NoSuchMethodException {}
		public Field[] getFields(Class<?> cls) {
			return nls(() -> fs.invoke(cls), () -> new Field[0]);
		}
		public Method[] getMethods(Class<?> cls) {
			return nls(() -> ms.invoke(cls), () -> new Method[0]);
		}
		public Constructor<?>[] getConstructors(Class<?> cls) {
			return nls(() -> cs.invoke(cls), () -> new Constructor[0]);
		}
	}
	class DefaultImpl implements IReflect {
		public Field[] getFields(Class<?> cls) {
			return MyReflect.lookupGetFields(cls);
		}
		public Method[] getMethods(Class<?> cls) {
			return MyReflect.lookupGetMethods(cls);
		}
		public Constructor<?>[] getConstructors(Class<?> cls) {
			return MyReflect.lookupGetConstructors(cls);
		}
	}

	static <T> T nls(CProv prov, Prov def) {
		return (T) nl(prov, def);
	}

	static <T> T nl(CProv<T> prov, Prov<T> def) {
		try {
			return prov.get();
		} catch (Throwable e) {
			return def.get();
		}
	}
}
