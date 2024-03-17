package modtools.jsfunc.reflect;

import arc.func.Cons;
import arc.util.OS;
import modtools.HopeConstant.ANDROID;
import modtools.utils.Tools.CProvT;

import java.lang.invoke.*;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Constructor;

import static ihope_lib.MyReflect.*;
import static modtools.HopeConstant.DESKTOP.*;

/** 获取类的{@code <init>}句柄 */
public class InitMethodHandle {
	/**
	 * for android<br>
	 * <a href="https://cs.android.com/android/platform/superproject/main/+/main:libcore/ojluni/src/main/java/java/lang/invoke/MethodHandle.java?q=symbol%3A%5Cbjava.lang.invoke.MethodHandle.INVOKE_SUPER%5Cb%20case%3Ayes">
	 * {@code MethodHandle#INVOKE_SUPER}
	 * </a>
	 */
	public static final int  INVOKE_SPECIAL    = 1;
	/**
	 * for window
	 * @see MethodHandleNatives.Constants#REF_invokeSpecial
	 */
	public static final byte REF_invokeSpecial = 7;

	static {
		UNSAFE.openModule(Object.class, "java.lang.invoke");
	}

	public static MethodHandle findInit
	 (Class<?> refc, Constructor<?> ctor) throws Throwable {
		if (OS.isAndroid) return findInitAndroid(refc, ctor);
		return findInitDesktop(refc, ctor, refc);
	}

	// --------android--------
	public static MethodHandle findInitAndroid
	(Class<?> refc, Constructor<?> ctor) throws Exception {
		Class<?>[] params = new Class[ctor.getParameterCount() + 1];
		/* 设置第0个参数为this */
		params[0] = refc;
		System.arraycopy(ctor.getParameterTypes(), 0, params, 1, ctor.getParameterCount());
		return ANDROID.HANDLE_CONSTRUCTOR.newInstance(
		 unsafe.getLong(ctor, ANDROID.ART_METHOD),
		 INVOKE_SPECIAL, MethodType.methodType(Void.TYPE, params));
	}

	// --------desktop--------
	/**
	 * @see MemberName#IS_METHOD
	 * @see MethodHandleNatives.Constants#MN_IS_METHOD
	 */
	static final int MN_IS_METHOD      = 0x00010000;
	/**
	 * @see MemberName#IS_CONSTRUCTOR
	 * @see MethodHandleNatives.Constants#MN_IS_CONSTRUCTOR
	 */
	static final int MN_IS_CONSTRUCTOR = 0x00020000;
	public static MethodHandle findInitDesktop
	 (Class<?> refc, Constructor<?> ctor,
		Class<?> specialCaller) throws Throwable {
		assert MEMBER_NAME_CTOR != null;
		CProvT<Object, Throwable> maker = () -> MEMBER_NAME_CTOR.invoke(ctor);
		/* 将memberName的flags的isConstructor改成isMethod */
		Cons<Object> resolver = o -> {
			int flags = unsafe.getInt(o, MEMBER_NAME_FLAGS);
			unsafe.putInt(o, MEMBER_NAME_FLAGS,
			 flags ^ MN_IS_CONSTRUCTOR // 去除
			 | MN_IS_METHOD // 添加
			);
		};
		return findSpecial(refc, maker, resolver, specialCaller);
	}
	public static MethodHandle findSpecial
	 (Class<?> refc, CProvT<Object, Throwable> maker, Cons<Object> resolver,
		Class<?> specialCaller) throws Throwable {
		Lookup specialLookup = lookup.in(specialCaller);

		assert RESOLVE_OR_FAIL != null;
		Object mb = RESOLVE_OR_FAIL.invoke(FACTORY, REF_invokeSpecial, maker.get(), refc,
		 -1/* LM_TRUSTED */,
		 NoSuchMethodException.class);
		resolver.get(mb);
		assert GET_DIRECT_METHOD != null;
		return (MethodHandle) GET_DIRECT_METHOD.invoke(specialLookup, REF_invokeSpecial, refc, mb, false, true, specialLookup);
	}
}
