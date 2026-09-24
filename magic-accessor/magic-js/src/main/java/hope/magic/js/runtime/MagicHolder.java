package hope.magic.js.runtime;

import java.lang.invoke.MethodHandle;

/**
 * 集中式静态分发 Holder (Inspired by OpenJDK {@code DirectMethodHandle$Holder} 与 {@code Invokers$Holder})。
 * <p>
 * 彻底消除为每个方法动态生成 ASM 字节码类的沉重负担 (Class 森林与元空间污染)，
 * 将通用及基础类型特化分发汇聚到固定的静态方法上。
 * HotSpot C2 编译器能将此类中的静态分发方法直接内联到调用点，
 * 实现零类加载开销与接近 DirectMethodHandle 原生极速执行。
 */
public final class MagicHolder {
	private MagicHolder() {}

	// --- 统一通用分发 ---
	public static Object invoke0(MethodHandle mh, Object target) throws Throwable {
		return mh.invokeExact(target);
	}

	public static Object invoke1(MethodHandle mh, Object target, Object a0) throws Throwable {
		return mh.invokeExact(target, a0);
	}

	public static Object invoke2(MethodHandle mh, Object target, Object a0, Object a1) throws Throwable {
		return mh.invokeExact(target, a0, a1);
	}

	public static Object invoke3(MethodHandle mh, Object target, Object a0, Object a1, Object a2) throws Throwable {
		return mh.invokeExact(target, a0, a1, a2);
	}

	// --- 零装箱基础类型快速路径 ---
	public static int invokeInt0(MethodHandle mh, Object target) throws Throwable {
		return (int) mh.invokeExact(target);
	}

	public static int invokeInt1(MethodHandle mh, Object target, int a0) throws Throwable {
		return (int) mh.invokeExact(target, a0);
	}

	public static int invokeInt2(MethodHandle mh, Object target, int a0, int a1) throws Throwable {
		return (int) mh.invokeExact(target, a0, a1);
	}

	public static int invokeInt3(MethodHandle mh, Object target, int a0, int a1, int a2) throws Throwable {
		return (int) mh.invokeExact(target, a0, a1, a2);
	}

	public static boolean invokeBoolean0(MethodHandle mh, Object target) throws Throwable {
		return (boolean) mh.invokeExact(target);
	}

	public static boolean invokeBoolean1(MethodHandle mh, Object target, Object a0) throws Throwable {
		return (boolean) mh.invokeExact(target, a0);
	}

	public static boolean invokeBoolean2(MethodHandle mh, Object target, Object a0, Object a1) throws Throwable {
		return (boolean) mh.invokeExact(target, a0, a1);
	}

	public static double invokeDouble0(MethodHandle mh, Object target) throws Throwable {
		return (double) mh.invokeExact(target);
	}

	public static double invokeDouble1(MethodHandle mh, Object target, double a0) throws Throwable {
		return (double) mh.invokeExact(target, a0);
	}

	public static double invokeDouble2(MethodHandle mh, Object target, double a0, double a1) throws Throwable {
		return (double) mh.invokeExact(target, a0, a1);
	}

	public static double invokeDouble3(MethodHandle mh, Object target, double a0, double a1, double a2) throws Throwable {
		return (double) mh.invokeExact(target, a0, a1, a2);
	}

	public static long invokeLong0(MethodHandle mh, Object target) throws Throwable {
		return (long) mh.invokeExact(target);
	}

	public static long invokeLong1(MethodHandle mh, Object target, long a0) throws Throwable {
		return (long) mh.invokeExact(target, a0);
	}

	public static long invokeLong2(MethodHandle mh, Object target, long a0, long a1) throws Throwable {
		return (long) mh.invokeExact(target, a0, a1);
	}

	// --- 构造器分发 ---
	public static Object new0(MethodHandle mh) throws Throwable {
		return mh.invokeExact();
	}

	public static Object new1(MethodHandle mh, Object a0) throws Throwable {
		return mh.invokeExact(a0);
	}

	public static Object new2(MethodHandle mh, Object a0, Object a1) throws Throwable {
		return mh.invokeExact(a0, a1);
	}

	public static Object new3(MethodHandle mh, Object a0, Object a1, Object a2) throws Throwable {
		return mh.invokeExact(a0, a1, a2);
	}

	public static Object newInt1(MethodHandle mh, int a0) throws Throwable {
		return mh.invokeExact(a0);
	}

	public static Object newIntString2(MethodHandle mh, int a0, String a1) throws Throwable {
		return mh.invokeExact(a0, a1);
	}
}
