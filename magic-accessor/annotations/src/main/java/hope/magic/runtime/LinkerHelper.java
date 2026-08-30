package hope.magic.runtime;

import hope_android.FieldUtils;
import sun.misc.Unsafe;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提供 Unsafe 字段偏移查找、trusted lookup MethodHandle 缓存、以及 MemberName 解析与 invokedynamic 引导方法支持。
 */
public class LinkerHelper {
	public static final boolean FAST_OFFSET = true; // 是否使用 jdk的Unsafe 直接获取 offset

	static {
		if (FAST_OFFSET) Magic.openModule();
	}

	public static final  Unsafe       UNSAFE                  = Magic.unsafe;
	public static final  boolean      IS_ANDROID              = isAndroid();
	private static final MethodHandle INTERNAL_MEMBER_NAME_MH = initInternalMemberName();

	private static final Map<String, MethodHandle> METHOD_HANDLE_CACHE = new ConcurrentHashMap<>();
	private static final Map<String, Object>       MEMBER_NAME_CACHE   = new ConcurrentHashMap<>();

	private static MethodHandle initInternalMemberName() {
		try {
			Class<?> memberNameClass = Class.forName("java.lang.invoke.MemberName");
			return Magic.lookup.findVirtual(MethodHandle.class, "internalMemberName", MethodType.methodType(memberNameClass));
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static boolean isAndroid() {
		try {
			Class.forName("android.os.Build");
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}

	public static long getFieldOffset(Class<?> clazz, String fieldName) {
		try {
			if (FAST_OFFSET) {
				return jdk.internal.misc.Unsafe.getUnsafe().objectFieldOffset(clazz, fieldName);
			}
			Field field = getDeclaredFieldRecursive(clazz, fieldName);
			return IS_ANDROID ? FieldUtils.getFieldOffset(field) : UNSAFE.objectFieldOffset(field);
		} catch (Throwable e) {
			throw new RuntimeException("Failed to get field offset for " + clazz.getName() + "#" + fieldName, e);
		}
	}

	public static long getStaticFieldOffset(Class<?> clazz, String fieldName) {
		try {
			if (FAST_OFFSET) {
				// 这里 objectFieldOffset 也可以获取静态字段的偏移量，返回的是静态字段在类对象中的偏移量
				return jdk.internal.misc.Unsafe.getUnsafe().objectFieldOffset(clazz, fieldName);
			}
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
			boolean isStatic  = (flags & 1) != 0;
			boolean isSpecial = (flags & 2) != 0;

			Class<?>   returnType = type.returnType();
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

	/**
	 * 基于 trusted lookup (IMPL_LOOKUP) 获取 MethodHandle 并缓存。
	 */
	public static MethodHandle getMethodHandle(
	 Class<?> clazz,
	 String methodName,
	 Class<?> returnType,
	 Class<?>[] parameterTypes,
	 boolean isStatic,
	 boolean isSpecial
	) {
		if (isAndroid()) return AndroidLinker.getMethodHandle(clazz, methodName, parameterTypes, isStatic, isSpecial);

		byte   refKind = (byte) (isStatic ? 6 : (isSpecial ? 7 : 5));
		String key     = makeMethodKey(clazz, methodName, returnType, parameterTypes, refKind);
		return METHOD_HANDLE_CACHE.computeIfAbsent(key, k -> {
			try {
				MethodType methodType = MethodType.methodType(returnType, parameterTypes == null ? new Class<?>[0] : parameterTypes);
				if (isStatic) {
					return Magic.lookup.findStatic(clazz, methodName, methodType);
				} else if (isSpecial) {
					return Magic.lookup.findSpecial(clazz, methodName, methodType, clazz);
				} else {
					try {
						return Magic.lookup.findVirtual(clazz, methodName, methodType);
					} catch (Throwable t) {
						Method m = getDeclaredMethodRecursive(clazz, methodName, parameterTypes == null ? new Class<?>[0] : parameterTypes);
						m.setAccessible(true);
						return Magic.lookup.unreflect(m);
					}
				}
			} catch (Throwable e) {
				throw new RuntimeException("Failed to resolve MethodHandle for " + clazz.getName() + "#" + methodName, e);
			}
		});
	}

	private static final MethodHandle RESOLVE_OR_FAIL_MH = initResolveOrFail();

	private static MethodHandle initResolveOrFail() {
		try {
			Method m = MethodHandles.Lookup.class.getDeclaredMethod("resolveOrFail", byte.class, Class.class, String.class, MethodType.class);
			m.setAccessible(true);
			return Magic.lookup.unreflect(m);
		} catch (Throwable ignored) {
			return null;
		}
	}

	/**
	 * 获取底层 DirectMethodHandle 中的 MemberName 并缓存（优先使用 JVM 内部极速 resolveOrFail 直调）。
	 */
	public static Object resolveMemberName(
	 Class<?> clazz,
	 String methodName,
	 Class<?> returnType,
	 Class<?>[] parameterTypes,
	 byte refKind
	) {
		String key = makeMethodKey(clazz, methodName, returnType, parameterTypes, refKind);
		return MEMBER_NAME_CACHE.computeIfAbsent(key, k -> {
			MethodType methodType = MethodType.methodType(returnType, parameterTypes == null ? new Class<?>[0] : parameterTypes);
			if (RESOLVE_OR_FAIL_MH != null) {
				try {
					Object mn = RESOLVE_OR_FAIL_MH.invoke(Magic.lookup, refKind, clazz, methodName, methodType);
					if (mn != null) return mn;
				} catch (Throwable ignored) {
				}
			}

			boolean      isStatic  = (refKind == 6);
			boolean      isSpecial = (refKind == 7);
			MethodHandle mh        = getMethodHandle(clazz, methodName, returnType, parameterTypes, isStatic, isSpecial);
			return extractMemberName(mh);
		});
	}

	private static volatile long MEMBER_FIELD_OFFSET = -2;

	public static Object extractMemberName(MethodHandle mh) {
		if (mh == null) return null;
		if (INTERNAL_MEMBER_NAME_MH != null) {
			try {
				Object mn = INTERNAL_MEMBER_NAME_MH.invoke(mh);
				if (mn != null) return mn;
			} catch (Throwable ignored) {
			}
		}
		try {
			long off = MEMBER_FIELD_OFFSET;
			if (off >= 0) {
				Object mn = UNSAFE.getObject(mh, off);
				if (mn != null) return mn;
			}

			Class<?> current = mh.getClass();
			while (current != null && current != Object.class) {
				try {
					Field field = current.getDeclaredField("member");
					off = UNSAFE.objectFieldOffset(field);
					MEMBER_FIELD_OFFSET = off;
					return UNSAFE.getObject(mh, off);
				} catch (NoSuchFieldException ignored) {
					current = current.getSuperclass();
				}
			}
			for (Field field : mh.getClass().getDeclaredFields()) {
				if (field.getType().getName().equals("java.lang.invoke.MemberName")) {
					off = UNSAFE.objectFieldOffset(field);
					MEMBER_FIELD_OFFSET = off;
					return UNSAFE.getObject(mh, off);
				}
			}
			throw new NoSuchFieldException("MemberName field not found in " + mh.getClass());
		} catch (Throwable e) {
			throw new RuntimeException("Failed to extract MemberName from MethodHandle", e);
		}
	}

	private static String makeMethodKey(
	 Class<?> clazz,
	 String methodName,
	 Class<?> returnType,
	 Class<?>[] parameterTypes,
	 byte refKind
	) {
		StringBuilder sb = new StringBuilder(clazz.getName()).append('#').append(methodName).append('#').append(refKind).append('(');
		if (parameterTypes != null) {
			for (Class<?> p : parameterTypes) {
				sb.append(p.getName()).append(';');
			}
		}
		sb.append(')').append(returnType == null ? "V" : returnType.getName());
		return sb.toString();
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

	private static Method getDeclaredMethodRecursive(Class<?> clazz, String name, Class<?>[] params)
	 throws NoSuchMethodException {
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
