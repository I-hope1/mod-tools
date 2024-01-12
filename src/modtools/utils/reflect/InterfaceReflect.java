package modtools.utils.reflect;

import arc.util.Log;
import ihope_lib.MyReflect;
import modtools.utils.SR.CatchSR;

import java.lang.invoke.*;
import java.lang.reflect.*;

public interface InterfaceReflect {
	Field[] getFields(Class<?> cls);
	Method[] getMethods(Class<?> cls);
	Constructor<?>[] getConstructors(Class<?> cls);

	InterfaceReflect impl = CatchSR.apply(() -> CatchSR.<InterfaceReflect>of(DesktopImpl::new).get(DefaultImpl::new));
	class DesktopImpl implements InterfaceReflect {
		MethodHandle getFieldsHandle       = MyReflect.lookup.findVirtual(Class.class, "getDeclaredFields0", MethodType.methodType(Field[].class, boolean.class));
		MethodHandle getMethodsHandle      = MyReflect.lookup.findVirtual(Class.class, "getDeclaredMethods0", MethodType.methodType(Method[].class, boolean.class));
		MethodHandle getConstructorsHandle = MyReflect.lookup.findVirtual(Class.class, "getDeclaredConstructors0", MethodType.methodType(Constructor[].class, boolean.class));
		public DesktopImpl() throws NoSuchMethodException, IllegalAccessException {}
		public Field[] getFields(Class<?> cls) {
			try {
				return (Field[]) getFieldsHandle.invokeExact(cls, false);
			} catch (Throwable e) {
				return new Field[0];
			}
		}
		public Method[] getMethods(Class<?> cls) {
			try {
				return (Method[]) getMethodsHandle.invokeExact(cls, false);
			} catch (Throwable e) {
				return new Method[0];
			}
		}
		public Constructor<?>[] getConstructors(Class<?> cls) {
			try {
				return (Constructor<?>[]) getConstructorsHandle.invokeExact(cls, false);
			} catch (Throwable e) {
				return new Constructor[0];
			}
		}
	}
	class DefaultImpl implements InterfaceReflect {
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
}
