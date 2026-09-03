package hope.magic.runtime;

import hope_android.FieldUtils;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class AndroidLinker {
	private static final int            INVOKE_DIRECT     = 2; // Android ART 核心非虚调用类型
	private static final int            INVOKE_STATIC     = 3;
	private static final long           ART_METHOD_OFFSET = initArtMethodOffset();
	private static final Constructor<?> MH_IMPL_CTOR;


	public static     final Unsafe UNSAFE = Magic.unsafe;
	public static     final Lookup LOOKUP = Magic.lookup;

	static {
		Constructor<?> ctor = null;
		try {
			// MethodHandleImpl(long artFieldOrMethod, int handleKind, MethodType type)
			Class<?> mhImplClass = Class.forName("java.lang.invoke.MethodHandleImpl");
			ctor = mhImplClass.getDeclaredConstructor(long.class, int.class, MethodType.class);
			ctor.setAccessible(true);
		} catch (Throwable ignored) {
		}
		MH_IMPL_CTOR = ctor;
	}

	private static long initArtMethodOffset() {
		try {
			Class<?> executableClass = Class.forName("java.lang.reflect.Executable");
			Field    field           = executableClass.getDeclaredField("artMethod");
			field.setAccessible(true);
			return FieldUtils.getFieldOffset(field);
		} catch (Throwable ignored) {
		}
		return 24; // 默认 64-bit ART fallback
	}

	/* 虚实例方法获取 */
	public static MethodHandle createVirtualInstanceHandle(Class<?> targetClass, Method method) throws Exception {
		if (MH_IMPL_CTOR == null) {
			method.setAccessible(true);
			return LOOKUP.unreflect(method);
		}
		long artMethod = getArtMethod(method);
		MethodType type = MethodType.methodType(method.getReturnType(), method.getParameterTypes())
		 .insertParameterTypes(0, targetClass);
		return (MethodHandle) MH_IMPL_CTOR.newInstance(artMethod, INVOKE_DIRECT, type);
	}

	/** 私有实例方法获取 */
	public static MethodHandle createPrivateInstanceHandle(Class<?> targetClass, Method method) throws Exception {
		if (MH_IMPL_CTOR == null) {
			method.setAccessible(true);
			return LOOKUP.unreflectSpecial(method, targetClass);
		}
		long artMethod = getArtMethod(method);
		MethodType type = MethodType.methodType(method.getReturnType(), method.getParameterTypes())
		 .insertParameterTypes(0, targetClass);
		return (MethodHandle) MH_IMPL_CTOR.newInstance(artMethod, INVOKE_DIRECT, type);
	}

	/** 原地 <init> 构造函数调用句柄 (直接得到 (Target, args...) -> void) */
	public static MethodHandle createInitInPlaceHandle(Class<?> targetClass, Constructor<?> ctor) throws Exception {
		if (MH_IMPL_CTOR == null) {
			ctor.setAccessible(true);
			return LOOKUP.unreflectConstructor(ctor);
		}
		long artMethod = getArtMethod(ctor);
		MethodType initType = MethodType.methodType(void.class, ctor.getParameterTypes())
		 .insertParameterTypes(0, targetClass);
		return (MethodHandle) MH_IMPL_CTOR.newInstance(artMethod, INVOKE_DIRECT, initType);
	}

	/** 私有静态方法极速获取 */
	public static MethodHandle createStaticHandle(Method method) throws Exception {
		if (MH_IMPL_CTOR == null) {
			method.setAccessible(true);
			return LOOKUP.unreflect(method);
		}
		long       artMethod = getArtMethod(method);
		MethodType type      = MethodType.methodType(method.getReturnType(), method.getParameterTypes());
		return (MethodHandle) MH_IMPL_CTOR.newInstance(artMethod, INVOKE_STATIC, type);
	}

	private static long getArtMethod(Object executable) {
		return UNSAFE.getLong(executable, ART_METHOD_OFFSET);
	}

	public static MethodHandle getMethodHandle(Class<?> clazz, String methodName, Class<?>[] parameterTypes,
	                                           boolean isStatic, boolean isSpecial) {
		try {
			if ("<init>".equals(methodName) || "__init__".equals(methodName)) {
				Constructor<?> ctor = clazz.getDeclaredConstructor(parameterTypes == null ? new Class<?>[0] : parameterTypes);
				ctor.setAccessible(true);
				return LOOKUP.unreflectConstructor(ctor);
			}
			Method method = clazz.getDeclaredMethod(methodName, parameterTypes == null ? new Class<?>[0] : parameterTypes);
			method.setAccessible(true);
			if (isStatic) {
				return createStaticHandle(method);
			} else if (isSpecial) {
				return createPrivateInstanceHandle(clazz, method);
			} else {
				return createVirtualInstanceHandle(clazz, method);
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}