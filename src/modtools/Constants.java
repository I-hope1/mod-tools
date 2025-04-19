package modtools;

import arc.KeyBinds.KeybindValue;
import arc.files.Fi;
import arc.graphics.g2d.PixmapPacker;
import arc.graphics.gl.FileTextureData;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import arc.util.Reflect;
import mindustry.Vars;
import mindustry.graphics.MultiPacker;
import mindustry.input.Binding;
import mindustry.mod.Mods;
import modtools.jsfunc.reflect.InitMethodHandle;
import modtools.utils.Tools.CProv;
import rhino.*;

import java.lang.invoke.*;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.*;
import java.net.URL;

import static ihope_lib.MyReflect.lookup;
import static modtools.utils.reflect.FieldUtils.fieldOffset;

/** 处理一些不安全的常量 */
@SuppressWarnings("unchecked")
public class Constants {
	public interface BINDING {
		/** @see Binding#Binding(KeybindValue, String) */
		// @SuppressWarnings("JavaLangInvokeHandleSignature")
		MethodHandle BINDING_CTOR = nl(() ->
			null
		 // lookup.findConstructor(Binding.class,
		 // MethodType.methodType(void.class, String.class, int.class, KeybindValue.class, String.class))
		);

		/** @see Class#getEnumConstantsShared() */
		long BINDING_VALUES = fieldOffset(Binding.class, "$VALUES");
	}

	/** Constants related to desktop JVM internals (java.lang.invoke). Likely fragile. */
	@SuppressWarnings("DataFlowIssue")
	public interface DESKTOP_INIT {
		/** @see java.lang.invoke.MemberName */
		Class<?> MEMBER_NAME = nl("java.lang.invoke.MemberName");

		/** @see MemberName#flags */
		long MEMBER_NAME_FLAGS =
		 fieldOffset(MEMBER_NAME, "flags");

		Class<?> CL_FACTORY = nl("java.lang.invoke.MemberName$Factory");

		/** @see MemberName.Factory#INSTANCE */
		Object       FACTORY          = Reflect.get(CL_FACTORY, "INSTANCE");
		/** @see MemberName#MemberName(Constructor) */
		MethodHandle MEMBER_NAME_CTOR = nl(() ->
		 lookup.findConstructor(MEMBER_NAME, MethodType.methodType(void.class, Constructor.class)));

		/** @see MemberName.Factory#resolveOrFail(byte, MemberName, Class, int, Class) */
		Method RESOLVE_OR_FAIL   = method(CL_FACTORY,
		 "resolveOrFail", byte.class, MEMBER_NAME, Class.class, int.class, Class.class);
		/** @see Lookup#getDirectMethodCommon(byte, Class, MemberName, boolean, boolean, Lookup) */
		Method GET_DIRECT_METHOD = method(
		 Lookup.class, "getDirectMethodCommon",
		 byte.class, Class.class, MEMBER_NAME, boolean.class, boolean.class, Lookup.class);


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
	/** Constants related to Android JVM/ART internals. Highly fragile. */
	public interface ANDROID_INIT {
		/** {@code MethodHandleImpl(long artMethod, int ref, MethodType mt)} */
		Constructor<MethodHandle> HANDLE_CONSTRUCTOR = ctor(
		 "java.lang.invoke.MethodHandleImpl",
		 long.class, int.class, MethodType.class);

		long ART_METHOD = fieldOffset(Executable.class, "artMethod");

		long STRING_COUNT = fieldOffset(String.class, "count");

		// long OBJECT_SIZE = FieldUtils.fieldOffset(nl(() ->
		//  Class.class.getDeclaredField("objectSize")));
	}
	public interface PACKER {
		/** @see Mods#packer */
		MultiPacker    multiPacker = nl(() -> Reflect.get(Mods.class, Vars.mods, "packer"));
		/** @see MultiPacker#packers */
		PixmapPacker[] packers     = nl(() -> Reflect.get(MultiPacker.class, multiPacker, "packers"));
	}
	public interface TABLE {
		long sizeInvalid = fieldOffset(Table.class, "sizeInvalid");
	}

	public interface PIXMAP {
		/** @see FileTextureData#pixmap */
		long PIXMAP = fieldOffset(FileTextureData.class, "pixmap");
	}

	public interface MODS {
		/** @see mindustry.mod.Mods#bundles */
		ObjectMap<String, Seq<Fi>> bundles = Reflect.get(Mods.class, Vars.mods, "bundles");

		/** @see Mods#loadMod(Fi, boolean, boolean) */
		Method loadMod = method(Mods.class, "loadMod", Fi.class, boolean.class, boolean.class);
	}
	public interface STRING {
		/** @see String#value */
		long VALUE = fieldOffset(String.class, "value");
		/** @see String#coder */
		long CODER = fieldOffset(String.class, "coder");
	}
	public interface CURL {
		/** @see URL#host */
		long host = fieldOffset(URL.class, "host");
	}
	public interface RHINO {
		/** @see NativeJavaMethod#methods */
		long methods      = fieldOffset(NativeJavaMethod.class, "methods");
		/** @see rhino.MemberBox#memberObject */
		long memberObject = fieldOffset(nl("rhino.MemberBox"), "memberObject");

		/** @see ImporterTopLevel#importedPackages */
		long importPackages = fieldOffset(ImporterTopLevel.class, "importedPackages");

		/** @see ObjArray#data */
		long objArray_data = fieldOffset(ObjArray.class, "data");


		/** @see NativeJavaObject#NativeJavaObject(Scriptable, Object, Class) */
		MethodHandle initNativeJavaObject = nl(() -> InitMethodHandle.findInit(NativeJavaObject.class.getDeclaredConstructor(Scriptable.class, Object.class, Class.class)));

		/** @see NativeJavaMethod#findCachedFunction(Context, Object[]) */
		Method findCachedFunction = method(NativeJavaMethod.class, "findCachedFunction", Context.class, Object[].class);
	}

	/** Loads a class by name
	 * @throws RuntimeException on failure. */
	public static <R> Class<R> nl(String className) {
		return (Class<R>) nl(() -> Class.forName(className));
	}

	/** Gets a static field value by class and field name
	 * @throws RuntimeException on failure. */
	public static <R> R val(String className, String fieldName) {
		Field field = nl(() -> Class.forName(className).getDeclaredField(fieldName));
		return (R) nl(() -> field.get(null));
	}
		/**Gets a reflected method by class name and method name
		 * @throws RuntimeException on failure. */
	public static Method method(String className, String methodName, Class<?>... params) {
		return method(nl(className), methodName, params);
	}
	public static Method method(Class<?> clazz, String methodName, Class<?>... params) {
		return nl(() -> clazz.getDeclaredMethod(methodName, params));
	}
	public static <R> Constructor<R> ctor(String className, Class<?>... params) {
		return ctor(nl(className), params);
	}
	public static <R> Constructor<R> ctor(Class<R> clazz, Class<?>... params) {
		return nl(() -> clazz.getDeclaredConstructor(params));
	}

	/**
	 * Executes a provider that might throw checked exceptions, wrapping them in RuntimeException.
	 * Ensures accessible objects are made accessible.
	 */
	public static <R> R nl(CProv<R> prov) {
		try {
			Object r = prov.get();
			if (r instanceof AccessibleObject ao) ao.setAccessible(true);
			return (R) r;
		} catch (Throwable e) {
			throw new RuntimeException(e);
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
			throw new RuntimeException(e);
			// return null;
		}
	}
}
