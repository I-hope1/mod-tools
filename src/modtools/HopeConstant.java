package modtools;

import arc.KeyBinds.KeybindValue;
import arc.files.Fi;
import arc.struct.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.input.Binding;
import mindustry.mod.Mods;
import modtools.utils.Tools.CProv;
import modtools.utils.reflect.FieldUtils;

import java.lang.invoke.*;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.*;

import static ihope_lib.MyReflect.lookup;

/** 处理一些不安全的常量 */
public class HopeConstant {
	public interface BINDING {
		MethodHandle BINDING_CTOR   = nl(() ->
		 lookup.findConstructor(Binding.class,
			MethodType.methodType(void.class, String.class, int.class, KeybindValue.class, String.class)));
		long         BINDING_VALUES = FieldUtils.fieldOffset(Binding.class, "$VALUES");
	}

	public interface DESKTOP {
		long MEMBER_NAME_FLAGS =
		 FieldUtils.fieldOffset(nl(() -> Class.forName("java.lang.invoke.MemberName")), "flags");

		/** @see java.lang.invoke.MemberName.Factory#INSTANCE */
		Object       FACTORY          = nl(() ->
		 Reflect.get(Class.forName("java.lang.invoke.MemberName$Factory"), "INSTANCE"));
		/** @see java.lang.invoke.MemberName#MemberName(Constructor) */
		MethodHandle MEMBER_NAME_CTOR = nl(() ->
		 lookup.findConstructor(Class.forName("java.lang.invoke.MemberName"), MethodType.methodType(void.class, Constructor.class)));

		/** @see java.lang.invoke.MemberName.Factory#resolveOrFail(byte, MemberName, Class, int, Class)  */
		Method RESOLVE_OR_FAIL   = nl(() ->
		 Class.forName("java.lang.invoke.MemberName$Factory").getDeclaredMethod("resolveOrFail", byte.class, Class.forName("java.lang.invoke.MemberName"), Class.class, int.class, Class.class));
		/** @see Lookup#getDirectMethod(byte, Class, MemberName, Lookup)   */
		Method GET_DIRECT_METHOD = nl(() ->
		 Lookup.class.getDeclaredMethod("getDirectMethod", byte.class, Class.class, Class.forName("java.lang.invoke.MemberName"), Lookup.class));
	}
	public interface ANDROID {
		Constructor<MethodHandle> HANDLE_CONSTRUCTOR = (Constructor) nl(() ->
		 Class.forName("java.lang.invoke.MethodHandleImpl").getDeclaredConstructor(long.class, int.class, MethodType.class));

		long ART_METHOD = FieldUtils.fieldOffset(nl(() ->
		 Class.forName("java.lang.reflect.Executable").getDeclaredField("artMethod")));
	}

	public interface MODS {
		/** @see mindustry.mod.Mods#bundles */
		ObjectMap<String, Seq<Fi>> bundles = nl(() -> Reflect.get(Mods.class, Vars.mods, "bundles"));
	}
	public interface STRING {
		long VALUE = FieldUtils.fieldOffset(String.class, "value");
		long CODER = FieldUtils.fieldOffset(String.class, "coder");
	}

	static <R> R nl(CProv<R> prov) {
		try {
			R r = prov.get();
			if (r instanceof AccessibleObject ao) ao.setAccessible(true);
			return r;
		} catch (Throwable e) {
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
