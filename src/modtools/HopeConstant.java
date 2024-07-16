package modtools;

import arc.KeyBinds.KeybindValue;
import arc.files.Fi;
import arc.graphics.g2d.PixmapPacker;
import arc.struct.*;
import arc.util.Reflect;
import mindustry.Vars;
import mindustry.graphics.MultiPacker;
import mindustry.input.Binding;
import mindustry.mod.Mods;
import modtools.utils.Tools.CProv;
import modtools.utils.reflect.FieldUtils;

import java.lang.invoke.*;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.*;

import static ihope_lib.MyReflect.lookup;

/** 处理一些不安全的常量 */
@SuppressWarnings("unchecked")
public class HopeConstant {
	public interface BINDING {
		/** @see Binding#Binding(KeybindValue, String) */
		// @SuppressWarnings("JavaLangInvokeHandleSignature")
		MethodHandle BINDING_CTOR = nl(() ->
		 null
		 // lookup.findConstructor(Binding.class,
			// MethodType.methodType(void.class, String.class, int.class, KeybindValue.class, String.class))
		);

		long BINDING_VALUES = FieldUtils.fieldOffset(Binding.class, "$VALUES");
	}

	@SuppressWarnings("DataFlowIssue")
	public interface DESKTOP {
		/** @see java.lang.invoke.MemberName */
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

		/** @see MemberName.Factory#resolveOrFail(byte, MemberName, Class, int, Class) */
		Method RESOLVE_OR_FAIL   = nl(() ->
		 Class.forName("java.lang.invoke.MemberName$Factory").getDeclaredMethod("resolveOrFail", byte.class, MEMBER_NAME, Class.class, int.class, Class.class));
		/** @see Lookup#getDirectMethodCommon(byte, Class, MemberName, boolean, boolean, Lookup) */
		Method GET_DIRECT_METHOD = nl(() ->
		 Lookup.class.getDeclaredMethod("getDirectMethodCommon", byte.class, Class.class, MEMBER_NAME, boolean.class, boolean.class, Lookup.class));


		// /** @see MemberName#MemberName(Method, boolean) */
		// MethodHandle MEMBER_NAME_MH_CTOR = nl(() ->
		//  lookup.findConstructor(MEMBER_NAME, MethodType.methodType(void.class, Method.class, boolean.class)));
		//
		// /** @see Method#Method(Class, String, Class[], Class, Class[], int, int, String, byte[], byte[], byte[]) */
		// Constructor<Method> METHOD_CTOR = nl(() ->
		//  Method.class.getDeclaredConstructors()[0]);
		// /** @see DirectMethodHandle#make(byte, Class, MemberName, Class) */
		// MethodHandle METHOD_HANDLE_MAKE = nl(() -> {
		// 	Class<?> direct = Class.forName("java.lang.invoke.DirectMethodHandle");
		// 	return lookup.findStatic(direct, "make", MethodType.methodType(direct, byte.class, Class.class, MEMBER_NAME, Class.class));
		// });
	}
	@SuppressWarnings("JavaReflectionMemberAccess")
	public interface ANDROID {
		/** {@code MethodHandleImpl(long artMethod, int ref, MethodType mt)} */
		Constructor<MethodHandle> HANDLE_CONSTRUCTOR = nl(() ->
		 Class.forName("java.lang.invoke.MethodHandleImpl").getDeclaredConstructor(long.class, int.class, MethodType.class));

		long ART_METHOD = FieldUtils.fieldOffset(nl(() ->
		 Executable.class.getDeclaredField("artMethod")));

		long STRING_COUNT = FieldUtils.fieldOffset(nl(() ->
		 String.class.getDeclaredField("count")));

		// long OBJECT_SIZE = FieldUtils.fieldOffset(nl(() ->
		//  Class.class.getDeclaredField("objectSize")));
	}
	public interface PACKER {
		/** @see Mods#packer  */
		MultiPacker multiPacker = nl(() -> Reflect.get(Mods.class, Vars.mods, "packer"));
		/** @see MultiPacker#packers  */
		PixmapPacker[] packers = nl(() -> Reflect.get(MultiPacker.class, multiPacker, "packers"));
	}

	public interface MODS {
		/** @see mindustry.mod.Mods#bundles */
		ObjectMap<String, Seq<Fi>> bundles = nl(() -> Reflect.get(Mods.class, Vars.mods, "bundles"));
	}
	public interface STRING {
		/** @see String#value */
		long VALUE = FieldUtils.fieldOffset(String.class, "value");
		/** @see String#coder */
		long CODER = FieldUtils.fieldOffset(String.class, "coder");
	}

	public static <R> R nl(CProv<?> prov) {
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
