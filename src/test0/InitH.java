package test0;

import arc.func.*;
import arc.util.*;
import ihope_lib.MyReflect;
import modtools.HopeConstant.ANDROID;
import modtools.utils.JSFunc;
import modtools.utils.Tools.*;
import modtools.utils.jsfunc.UNSAFE;

import java.lang.invoke.*;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.*;

import static ihope_lib.MyReflect.unsafe;
import static modtools.HopeConstant.DESKTOP.*;

public class InitH {
	/** for android */
	public static final int  INVOKE_SPECIAL    = 1;
	/** for window */
	public static final byte REF_invokeSpecial = 7;

	static {
		try {
			UNSAFE.openModule(Object.class.getModule(), "java.lang.invoke");
		} catch (Throwable ignored) {}
	}

	public static MethodHandle findInit
	 (Class<?> refc, Constructor<?> ctor) throws Throwable {
		if (OS.isAndroid) return findInitAndroid(refc, ctor);
		return findInitDesktop(refc, ctor, refc);
	}
	public static MethodHandle findInitAndroid
	 (Class<?> refc, Constructor<?> ctor) throws Exception {
		Class<?>[] params = new Class[ctor.getParameterCount() + 1];
		params[0] = refc;
		System.arraycopy(ctor.getParameterTypes(), 0, params, 1, ctor.getParameterCount());
		return ANDROID.HANDLE_CONSTRUCTOR.newInstance(
		 unsafe.getLong(ctor, ANDROID.ART_METHOD),
		 INVOKE_SPECIAL, MethodType.methodType(Void.TYPE, params));
	}
	public static MethodHandle findInitDesktop
	 (Class<?> refc, Constructor<?> ctor,
		Class<?> specialCaller) throws Throwable {
		assert MEMBER_NAME_CTOR != null;
		CProvT<Object, Throwable> maker = () -> MEMBER_NAME_CTOR.invoke(ctor);
		Cons<Object> resolver = o -> {
			int flags = unsafe.getInt(o, MEMBER_NAME_FLAGS);
			unsafe.putInt(o, MEMBER_NAME_FLAGS, flags ^ 131072 | 65536);
		};
		return findSpecial(refc, maker, resolver, specialCaller);
	}
	public static <E extends Throwable> MethodHandle findSpecial
	 (Class<?> refc, CProvT<Object, E> maker, Cons<Object> resolver,
		Class<?> specialCaller)
	 throws IllegalAccessException, InvocationTargetException, E {
		Lookup specialLookup = MyReflect.lookup.in(specialCaller);

		assert RESOLVE_OR_FAIL != null;
		Object mb = RESOLVE_OR_FAIL.invoke(FACTORY, REF_invokeSpecial, maker.get(), refc, -1, NoSuchMethodException.class);
		resolver.get(mb);
		assert GET_DIRECT_METHOD != null;
		return (MethodHandle) GET_DIRECT_METHOD.invoke(specialLookup, REF_invokeSpecial, refc, mb, specialLookup);
		// return null;
	}
}
