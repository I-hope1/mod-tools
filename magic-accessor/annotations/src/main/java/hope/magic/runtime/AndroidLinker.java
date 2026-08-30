package hope.magic.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class AndroidLinker {
	private static final int            INVOKE_DIRECT     = 2; // Android ART 核心非虚调用类型
	private static final int            INVOKE_STATIC     = 3;
	/**
	 * <a href=
	 * "https://cs.android.com/android/platform/superproject/main/+/main:art/runtime/mirror/executable.h;bpv=1;bpt=1;l=73?q=executable&ss=android&gsn=art_method_&gs=KYTHE%3A%2F%2Fkythe%3A%2F%2Fandroid.googlesource.com%2Fplatform%2Fsuperproject%2Fmain%2F%2Fmain%3Flang%3Dc%252B%252B%3Fpath%3Dart%2Fruntime%2Fmirror%2Fexecutable.h%23GLbGh3aGsjxEudfgKrvQvNcLL3KUjmUaJTc4nCOKuVY">
	 * uint64_t Executable::art_method_</a>
	 */
	private static final long           ART_METHOD_OFFSET = 24; // 取决于具体 Android 版本
	private static final Constructor<?> MH_IMPL_CTOR;

	static {
		try {
			// MethodHandleImpl(long artFieldOrMethod, int handleKind, MethodType type)
			Class<?> mhImplClass = Class.forName("java.lang.invoke.MethodHandleImpl");
			MH_IMPL_CTOR = mhImplClass.getDeclaredConstructor(long.class, int.class, MethodType.class);
			MH_IMPL_CTOR.setAccessible(true);
		} catch (Throwable e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	/* 虚实例方法获取 */
	public static MethodHandle createVirtualInstanceHandle(Class<?> targetClass, Method method) throws Exception {
		long artMethod = getArtMethod(method);
		MethodType type = MethodType.methodType(method.getReturnType(), method.getParameterTypes())
		 .insertParameterTypes(0, targetClass);
		// 直接构造 INVOKE_DIRECT 句柄
		return (MethodHandle) MH_IMPL_CTOR.newInstance(artMethod, INVOKE_DIRECT, type);
	}

	/** 私有实例方法获取 */
	public static MethodHandle createPrivateInstanceHandle(Class<?> targetClass, Method method) throws Exception {
		long artMethod = getArtMethod(method);
		MethodType type = MethodType.methodType(method.getReturnType(), method.getParameterTypes())
		 .insertParameterTypes(0, targetClass);
		// 直接构造 INVOKE_DIRECT 句柄
		return (MethodHandle) MH_IMPL_CTOR.newInstance(artMethod, INVOKE_DIRECT, type);
	}

	/** 原地 <init> 构造函数调用句柄 (直接得到 (Target, args...) -> void) */
	public static MethodHandle createInitInPlaceHandle(Class<?> targetClass, Constructor<?> ctor) throws Exception {
		long artMethod = getArtMethod(ctor);
		// 插入targetClass
		MethodType initType = MethodType.methodType(void.class, ctor.getParameterTypes())
		 .insertParameterTypes(0, targetClass);
		// 原生直接支持原地调用
		return (MethodHandle) MH_IMPL_CTOR.newInstance(artMethod, INVOKE_DIRECT, initType);
	}

	/** 私有静态方法极速获取 */
	public static MethodHandle createStaticHandle(Method method) throws Exception {
		long       artMethod = getArtMethod(method);
		MethodType type      = MethodType.methodType(method.getReturnType(), method.getParameterTypes());
		return (MethodHandle) MH_IMPL_CTOR.newInstance(artMethod, INVOKE_STATIC, type);
	}

	private static long getArtMethod(Object executable) {
		// 通过 Unsafe 提取 Executable 内部的 artMethod 64位指针
		return Magic.unsafe.getLong(executable, ART_METHOD_OFFSET);
	}
	static MethodHandle getMethodHandle(Class<?> clazz, String methodName, Class<?>[] parameterTypes,
	                                    boolean isStatic, boolean isSpecial) {
		try {
			Method method = "<init>".equals(methodName) ? null :
			 clazz.getDeclaredMethod(methodName, parameterTypes == null ? new Class<?>[0] : parameterTypes);
			if (isStatic) {
				return createStaticHandle(method);
			} else if (isSpecial) {
				if (method == null) {
					Constructor<?> ctor = clazz.getDeclaredConstructor(parameterTypes == null ? new Class<?>[0] : parameterTypes);
					return createInitInPlaceHandle(clazz, ctor);
				}
				return createPrivateInstanceHandle(clazz, method);
			} else {
				return createVirtualInstanceHandle(clazz, method);
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}