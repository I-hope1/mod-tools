package modtools.jsfunc.reflect;

import arc.func.Cons;
import arc.util.*;
import modtools.Constants.ANDROID_INIT;
import modtools.annotations.asm.CopyConstValue;
import modtools.utils.Tools.CProvT;

import java.lang.invoke.*;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Constructor;

import static ihope_lib.MyReflect.*;
import static modtools.Constants.DESKTOP_INIT.*;

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
	 * for window (value: {@value MethodHandleNatives.Constants#REF_invokeSpecial})
	 * @see MethodHandleNatives.Constants#REF_invokeSpecial
	 */
	// @CopyConstValue
	public static final byte REF_invokeSpecial = 7;

	static {
		UNSAFE.openModule(Object.class, "java.lang.invoke");
	}

	public static MethodHandle findInit
	 (Constructor<?> ctor) throws Throwable {
		if (OS.isAndroid) return findInitAndroid(ctor.getDeclaringClass(), ctor);
		return findInitDesktop(ctor.getDeclaringClass(), ctor, ctor.getDeclaringClass());
	}

	// --------android--------
	private static MethodHandle findInitAndroid
	(Class<?> refc, Constructor<?> ctor) throws Exception {
		Class<?>[] params = new Class[ctor.getParameterCount() + 1];
		/* 设置第0个参数为this */
		params[0] = refc;
		System.arraycopy(ctor.getParameterTypes(), 0, params, 1, ctor.getParameterCount());
		return ANDROID_INIT.HANDLE_CONSTRUCTOR.newInstance(
		 unsafe.getLong(ctor, ANDROID_INIT.ART_METHOD),
		 INVOKE_SPECIAL, MethodType.methodType(Void.TYPE, params));
	}

	// --------desktop--------
	/**
	 * @see MemberName#IS_METHOD
	 * @see MethodHandleNatives.Constants#MN_IS_METHOD
	 */
	@CopyConstValue
	static final int MN_IS_METHOD      = -1;
	/**
	 * @see MemberName#IS_CONSTRUCTOR
	 * @see MethodHandleNatives.Constants#MN_IS_CONSTRUCTOR
	 */
	@CopyConstValue
	static final int MN_IS_CONSTRUCTOR = -1;


	public static MethodHandle findInitDesktop
	 (Class<?> refc, Constructor<?> ctor,
	  Class<?> specialCaller) throws Throwable {
		assert MEMBER_NAME_CTOR != null;
		CProvT<Object, Throwable> maker = () -> MEMBER_NAME_CTOR.invoke(ctor);
		/* 将memberName的flags的isConstructor改成isMethod */
		Cons<Object> resolver = o -> {
			int flags = unsafe.getInt(o, MEMBER_NAME_FLAGS);
			unsafe.putInt(o, MEMBER_NAME_FLAGS,
			 flags ^ MN_IS_CONSTRUCTOR // 去除MN_IS_CONSTRUCTOR
			 | MN_IS_METHOD // 添加MN_IS_METHOD
			);
		};
		return findSpecial(refc, maker, resolver, specialCaller);
	}
	private static MethodHandle findSpecial
	 (Class<?> refc, CProvT<Object, Throwable> maker, Cons<Object> resolver,
	  Class<?> specialCaller) throws Throwable {
		Lookup specialLookup = lookup.in(specialCaller);

		assert RESOLVE_OR_FAIL != null;
		Object mb = RESOLVE_OR_FAIL.invoke(FACTORY, REF_invokeSpecial, maker.get(), refc,
		 -1/* LM_TRUSTED */,
		 NoSuchMethodException.class);
		resolver.get(mb);
		assert GET_DIRECT_METHOD != null;
		return (MethodHandle) GET_DIRECT_METHOD.invoke(
		 specialLookup,
		 REF_invokeSpecial, // refKind
		 refc,// ReferringClass
		 mb, // method
		 false, // checkSecurity
		 true, // doRestrict
		 specialLookup // boundCaller
		);
	}
}
