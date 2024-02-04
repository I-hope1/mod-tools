package modtools;

import static ihope_lib.MyReflect.lookup;

import java.lang.invoke.*;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.*;

import arc.KeyBinds.KeybindValue;
import arc.files.Fi;
import arc.struct.*;
import arc.util.Reflect;
import mindustry.Vars;
import mindustry.input.Binding;
import mindustry.mod.Mods;
import modtools.utils.Tools.CProv;
import modtools.utils.reflect.FieldUtils;

/** 处理一些不安全的常量 */
@SuppressWarnings("unchecked")
public class HopeConstant {
	public interface BINDING {
		@SuppressWarnings("JavaLangInvokeHandleSignature")
		MethodHandle BINDING_CTOR = nl(() ->
		 lookup.findConstructor(Binding.class,
			MethodType.methodType(void.class, String.class, int.class, KeybindValue.class, String.class)));
		long BINDING_VALUES = FieldUtils.fieldOffset(Binding.class, "$VALUES");
	}

	public interface DESKTOP {
		Class<?> MEMBER_NAME = nl(() -> Class.forName("java.lang.invoke.MemberName"));

		/** @see MemberName#flags */
		long MEMBER_NAME_FLAGS =
		 FieldUtils.fieldOffset(MEMBER_NAME, "flags");

		/** @see MemberName.Factory#INSTANCE */
		Object       FACTORY          = nl(() ->
		 Reflect.get(Class.forName("java.lang.invoke.MemberName$Factory"), "INSTANCE"));
		/** @see MemberName#MemberName(Constructor) */
		MethodHandle MEMBER_NAME_CTOR = nl(() ->
		 lookup.findConstructor(MEMBER_NAME, MethodType.methodType(void.class, Constructor.class)));

		/** @see MemberName.Factory#resolveOrFail(byte, MemberName, Class, int, Class)  */
		Method RESOLVE_OR_FAIL   = nl(() ->
		 Class.forName("java.lang.invoke.MemberName$Factory").getDeclaredMethod("resolveOrFail", byte.class, MEMBER_NAME, Class.class, int.class, Class.class));
		/** @see Lookup#getDirectMethodCommon(byte, Class, MemberName, boolean, boolean, Lookup) */
		Method GET_DIRECT_METHOD = nl(() ->
		 Lookup.class.getDeclaredMethod("getDirectMethodCommon", byte.class, Class.class, MEMBER_NAME, boolean.class, boolean.class, Lookup.class));
	}
	@SuppressWarnings("JavaReflectionMemberAccess")
	public interface ANDROID {
		/** MethodHandleImpl(long artMethod, int ref, MethodType mt)  */
		Constructor<MethodHandle> HANDLE_CONSTRUCTOR = nl(() ->
		 Class.forName("java.lang.invoke.MethodHandleImpl").getDeclaredConstructor(long.class, int.class, MethodType.class));

		long ART_METHOD = FieldUtils.fieldOffset(nl(() ->
		 Executable.class.getDeclaredField("artMethod")));

		long STRING_COUNT = FieldUtils.fieldOffset(nl(() ->
		 String.class.getDeclaredField("count")));
		// long OBJECT_SIZE = FieldUtils.fieldOffset(nl(() ->
		//  Class.class.getDeclaredField("objectSize")));
	}

	public interface MODS {
		/** @see mindustry.mod.Mods#bundles */
		ObjectMap<String, Seq<Fi>> bundles = nl(() -> Reflect.get(Mods.class, Vars.mods, "bundles"));
	}
	public interface STRING {
		long VALUE = FieldUtils.fieldOffset(String.class, "value");
		long CODER = FieldUtils.fieldOffset(String.class, "coder");
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
	public static <R> R iv(Method method, Object obj, Object... args) {
		try {
			return (R) method.invoke(obj, args);
		} catch (IllegalAccessException | InvocationTargetException e) {
			return null;
		}
	}

	public static <R> R iv(MethodHandle handle, Object... args) {
		try {
			return (R) handle.invokeWithArguments(args);
		} catch (Throwable e) {
			return null;
		}
	}
}
