package hope.magic.runtime;

import hope_android.FieldUtils;
import sun.misc.Unsafe;

import java.lang.invoke.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 提供 Unsafe 字段偏移查找与 invokedynamic (indy) 引导方法支持。
 */
public class LinkerHelper {
	public static final boolean IS_ANDROID = isAndroid();
	private static boolean isAndroid() {
		try {
			Class.forName("android.os.Build");
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}
	public static final Unsafe UNSAFE = Magic.unsafe;

	public static long getFieldOffset(Class<?> clazz, String fieldName) {
		try {
			Field field = getDeclaredFieldRecursive(clazz, fieldName);
			return IS_ANDROID ? FieldUtils.getFieldOffset(field) : UNSAFE.objectFieldOffset(field);
		} catch (Throwable e) {
			throw new RuntimeException("Failed to get field offset for " + clazz.getName() + "#" + fieldName, e);
		}
	}

	public static long getStaticFieldOffset(Class<?> clazz, String fieldName) {
		try {
			Field field = getDeclaredFieldRecursive(clazz, fieldName);
			return IS_ANDROID ? FieldUtils.getFieldOffset(field) : UNSAFE.staticFieldOffset(field);
		} catch (Throwable e) {
			throw new RuntimeException("Failed to get static field offset for " + clazz.getName() + "#" + fieldName, e);
		}
	}

	public static Object getStaticFieldBase(Class<?> clazz, String fieldName) {
		try {
			Field field = getDeclaredFieldRecursive(clazz, fieldName);
			return field.getDeclaringClass();
		} catch (Throwable e) {
			throw new RuntimeException("Failed to get static field base for " + clazz.getName() + "#" + fieldName, e);
		}
	}

	/**
	 * invokedynamic (indy) 引导方法 (Bootstrap Method)。
	 * 在 call site 首次执行时由 JVM 自动调用并生成 ConstantCallSite，JIT 后续直接内联。
	 */
	public static CallSite bootstrap(
		MethodHandles.Lookup lookup,
		String name,
		MethodType type,
		Class<?> targetClass,
		String targetMethodName,
		int flags
	) {
		try {
			boolean isStatic = (flags & 1) != 0;
			boolean isSpecial = (flags & 2) != 0;

			Class<?> returnType = type.returnType();
			Class<?>[] parameterTypes;
			if (isStatic) {
				parameterTypes = type.parameterArray();
			} else {
				Class<?>[] allParams = type.parameterArray();
				parameterTypes = new Class<?>[allParams.length - 1];
				System.arraycopy(allParams, 1, parameterTypes, 0, parameterTypes.length);
			}

			MethodHandle mh = getMethodHandle(targetClass, targetMethodName, returnType, parameterTypes, isStatic, isSpecial);
			return new ConstantCallSite(mh.asType(type));
		} catch (Throwable t) {
			throw new BootstrapMethodError("Failed to bootstrap indy call site for " + targetClass.getName() + "#" + targetMethodName, t);
		}
	}

	public static MethodHandle getMethodHandle(
		Class<?> clazz,
		String methodName,
		Class<?> returnType,
		Class<?>[] parameterTypes,
		boolean isStatic,
		boolean isSpecial
	) {
		try {
			MethodType methodType = MethodType.methodType(returnType, parameterTypes);
			if (isStatic) {
				return Magic.lookup.findStatic(clazz, methodName, methodType);
			} else if (isSpecial) {
				return Magic.lookup.findSpecial(clazz, methodName, methodType, clazz);
			} else {
				try {
					return Magic.lookup.findVirtual(clazz, methodName, methodType);
				} catch (Throwable t) {
					Method m = getDeclaredMethodRecursive(clazz, methodName, parameterTypes);
					m.setAccessible(true);
					return Magic.lookup.unreflect(m);
				}
			}
		} catch (Throwable e) {
			throw new RuntimeException("Failed to resolve MethodHandle for " + clazz.getName() + "#" + methodName, e);
		}
	}

	public static Object resolveMemberName(
		Class<?> clazz,
		String methodName,
		Class<?> returnType,
		Class<?>[] parameterTypes,
		byte refKind
	) {
		try {
			Class<?> memberNameClass = Class.forName("java.lang.invoke.MemberName");
			MethodType methodType = MethodType.methodType(returnType, parameterTypes);

			java.lang.reflect.Constructor<?> ctor = memberNameClass.getDeclaredConstructor(
				Class.class, String.class, MethodType.class, byte.class
			);
			ctor.setAccessible(true);
			Object memberName = ctor.newInstance(clazz, methodName, methodType, refKind);

			Method resolveOrFail = Class.forName("java.lang.invoke.MethodHandleNatives").getDeclaredMethod(
				"resolve", memberNameClass, Class.class, int.class, boolean.class);
			resolveOrFail.setAccessible(true);
			return resolveOrFail.invoke(null, memberName, clazz, refKind, false);
		} catch (Throwable t) {
			boolean isStatic = (refKind == 6);
			boolean isSpecial = (refKind == 7);
			boolean isInterface = (refKind == 9);
			return getMemberName(clazz, methodName, returnType, parameterTypes, isStatic, isSpecial, isInterface);
		}
	}

	public static Object getMemberName(
		Class<?> clazz,
		String methodName,
		Class<?> returnType,
		Class<?>[] parameterTypes,
		boolean isStatic,
		boolean isSpecial,
		boolean isInterface
	) {
		MethodHandle mh = getMethodHandle(clazz, methodName, returnType, parameterTypes, isStatic, isSpecial);
		return extractMemberName(mh);
	}

	public static Object extractMemberName(MethodHandle mh) {
		try {
			Class<?> current = mh.getClass();
			while (current != null && current != Object.class) {
				try {
					Field field = current.getDeclaredField("member");
					long off = UNSAFE.objectFieldOffset(field);
					return UNSAFE.getObject(mh, off);
				} catch (NoSuchFieldException ignored) {
					current = current.getSuperclass();
				}
			}
			for (Field field : mh.getClass().getDeclaredFields()) {
				if (field.getType().getName().equals("java.lang.invoke.MemberName")) {
					long off = UNSAFE.objectFieldOffset(field);
					return UNSAFE.getObject(mh, off);
				}
			}
			throw new NoSuchFieldException("MemberName field not found in " + mh.getClass());
		} catch (Throwable e) {
			throw new RuntimeException("Failed to extract MemberName from MethodHandle", e);
		}
	}

	private static Field getDeclaredFieldRecursive(Class<?> clazz, String name) throws NoSuchFieldException {
		Class<?> c = clazz;
		while (c != null && c != Object.class) {
			try {
				return c.getDeclaredField(name);
			} catch (NoSuchFieldException e) {
				c = c.getSuperclass();
			}
		}
		throw new NoSuchFieldException(clazz.getName() + "#" + name);
	}

	private static Method getDeclaredMethodRecursive(Class<?> clazz, String name, Class<?>[] params) throws NoSuchMethodException {
		Class<?> c = clazz;
		while (c != null && c != Object.class) {
			try {
				return c.getDeclaredMethod(name, params);
			} catch (NoSuchMethodException e) {
				c = c.getSuperclass();
			}
		}
		throw new NoSuchMethodException(clazz.getName() + "#" + name);
	}
}
