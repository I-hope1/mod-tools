package modtools.annotations.unsafe;

import modtools.annotations.HopeReflect;

import java.lang.invoke.*;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.*;
import java.util.function.Consumer;

import static modtools.annotations.HopeReflect.*;
import static modtools.annotations.unsafe.InitHandleC.DESKTOP.*;

/* 获取<init>的方法句柄  */
@SuppressWarnings("unchecked")
public class InitHandleC {
	public static MethodHandle findInitDesktop
	 (Class<?> refc, Constructor<?> ctor,
	  Class<?> specialCaller) throws Throwable {
		assert MEMBER_NAME_CTOR != null;
		CProv<Object> maker = () -> MEMBER_NAME_CTOR.invoke(ctor);
		Consumer<Object> resolver = o -> {
			int flags = unsafe.getInt(o, MEMBER_NAME_FLAGS);
			unsafe.putInt(o, MEMBER_NAME_FLAGS, flags ^ 131072 | 65536);
		};
		return findSpecial(refc, maker, resolver, specialCaller);
	}
	public static MethodHandle findSpecial
	 (Class<?> refc, CProv<Object> maker, Consumer<Object> resolver,
	  Class<?> specialCaller) throws Throwable {
		Lookup specialLookup = lookup.in(specialCaller);

		assert RESOLVE_OR_FAIL != null;
		Object mb = RESOLVE_OR_FAIL.invoke(FACTORY, REF_invokeSpecial, maker.get(), refc, -1, NoSuchMethodException.class);
		resolver.accept(mb);
		assert GET_DIRECT_METHOD != null;
		return (MethodHandle) GET_DIRECT_METHOD.invoke(specialLookup, REF_invokeSpecial, refc, mb, false, true, specialLookup);
	}

	public interface DESKTOP {
		Class<?> MEMBER_NAME = nl(() -> Class.forName("java.lang.invoke.MemberName"));

		/** @see MemberName#flags */
		long MEMBER_NAME_FLAGS = HopeReflect.fieldOffset(MEMBER_NAME, "flags");

		/** @see MemberName.Factory#INSTANCE */
		Object       FACTORY          = nl(() -> HopeReflect.getAccess(Class.forName("java.lang.invoke.MemberName$Factory"), null, "INSTANCE"));
		/** @see MemberName#MemberName(Constructor) */
		MethodHandle MEMBER_NAME_CTOR = nl(() ->
		 lookup.findConstructor(MEMBER_NAME, MethodType.methodType(void.class, Constructor.class)));

		/** @see MemberName.Factory#resolveOrFail(byte, MemberName, Class, int, Class) */
		Method RESOLVE_OR_FAIL   = nl(() ->
		 Class.forName("java.lang.invoke.MemberName$Factory").getDeclaredMethod("resolveOrFail", byte.class, MEMBER_NAME, Class.class, int.class, Class.class));
		/** @see Lookup#getDirectMethodCommon(byte, Class, MemberName, boolean, boolean, Lookup) */
		Method GET_DIRECT_METHOD = nl(() ->
		 Lookup.class.getDeclaredMethod("getDirectMethodCommon", byte.class, Class.class, MEMBER_NAME, boolean.class, boolean.class, Lookup.class));
	}
	/**
	 * for window (value: {@value MethodHandleNatives.Constants#REF_invokeSpecial})
	 * @see MethodHandleNatives.Constants#REF_invokeSpecial
	 */
	public static final byte REF_invokeSpecial = 7;

	public interface CProv<T> {
		T get() throws Throwable;
	}

	static <R> R nl(CProv<?> prov) {
		try {
			Object r = prov.get();
			if (r instanceof AccessibleObject ao) ao.setAccessible(true);
			return (R) r;
		} catch (Throwable e) {
			return null;
		}
	}
}