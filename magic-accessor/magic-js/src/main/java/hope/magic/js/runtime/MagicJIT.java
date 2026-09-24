package hope.magic.js.runtime;

import hope.magic.annotation.AccessMode;
import hope.magic.runtime.*;
import org.objectweb.asm.*;
import org.objectweb.asm.Type;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 DirectMethodHandle 与静态持有者 (MagicHolder) 的特权 JIT 存根生成器。
 * <p>
 * <b>架构升级与 AccessMode 统一说明：</b>
 * <ul>
 *   <li><b>统一架构：</b>参考 OpenJDK 原生 {@code DirectMethodHandle$Holder} 与 {@code Invokers$Holder} 设计，
 *       所有方法与构造器直调全面统一采用<b>基于 HotSpot 原生 DirectMethodHandle + {@link MagicHolder} 静态分派</b>。
 *       HotSpot C2 编译器在内联阶段可直接穿透 {@link MagicHolder} 的 {@code @ForceInline} 入口抵达底层原生机器指令。</li>
 *   <li><b>零动态类生成 (Zero Dynamic Classes)：</b>彻底废弃了早期针对不同 {@link AccessMode}（如 {@code NESTMATE}、
 *       {@code MAGIC_ACCESSOR}、{@code UNSAFE_AND_LINKTO} 动态桥接类）通过 ASM 大量生成动态类和宿主组的设计，
 *       实现 100% 零元空间 (Metaspace) 膨胀、零类加载器泄漏风险。</li>
 *   <li><b>AccessMode 在运行期的状态：</b>在 magic-js 运行期，多模式分派已被统一架构完全取代。
 *       所有接受 {@link AccessMode} 形参的 API 均转为向后兼容保留（No-op），无论传入何种模式，
 *       内部均统一走高性能的 DirectMethodHandle + MagicHolder 路径。</li>
 * </ul>
 */
@SuppressWarnings("removal")
public class MagicJIT implements Opcodes {

	public static final String IN_JSOps = "hope/magic/js/runtime/JSOps";

	/** 供测试与调试注入的字节码转储勾子：(className, classBytes) -> void */
	public static volatile java.util.function.BiConsumer<String, byte[]> CLASS_DUMP_HOOK = null;

	public static String disassemble(byte[] classBytes) {
		var cr  = new org.objectweb.asm.ClassReader(classBytes);
		var sw  = new java.io.StringWriter();
		var pw  = new java.io.PrintWriter(sw);
		var tcv = new org.objectweb.asm.util.TraceClassVisitor(pw);
		cr.accept(tcv, 0);
		return sw.toString();
	}

	private static volatile AccessMode currentMode = initDefaultMode();

	private static AccessMode initDefaultMode() {
		String prop = System.getProperty("magic.mode");
		if (prop == null) prop = System.getProperty("magic.js.mode");
		if (prop != null) {
			try {
				return AccessMode.valueOf(prop.toUpperCase().trim());
			} catch (IllegalArgumentException ignored) {
			}
		}
		return AccessMode.AUTO;
	}

	/**
	 * 获取当前配置的访问模式。
	 *
	 * @return 当前模式（由于运行期已全面统一为 DirectMethodHandle + MagicHolder，该值仅供配置回显）
	 */
	public static AccessMode getMode() {
		return currentMode;
	}

	/**
	 * 设置全局访问模式。
	 * <p>
	 * <b>注意：</b>运行期已全面统一为 DirectMethodHandle + MagicHolder 架构，
	 * 此方法仅用于兼容旧版配置或测试，不再改变底层执行策略。
	 *
	 * @param mode 访问模式
	 */
	public static void setMode(AccessMode mode) {
		currentMode = mode == null ? AccessMode.AUTO : mode;
	}

	/**
	 * 获取当前生效的访问模式。
	 *
	 * @return 生效模式（统一为基于 DirectMethodHandle 的直调模式）
	 */
	public static AccessMode getEffectiveMode() {
		AccessMode m = currentMode;
		if (m == AccessMode.AUTO) {
			return AccessMode.UNSAFE_AND_METHODHANDLE;
		}
		return m;
	}

	private static final class InvokerLookupKey {
		final AccessMode mode;
		final String     methodName;
		final int        arity;
		final boolean    isStatic;
		final int        hash;

		InvokerLookupKey(AccessMode mode, String methodName, int arity, boolean isStatic) {
			this.mode = mode;
			this.methodName = methodName;
			this.arity = arity;
			this.isStatic = isStatic;
			int h = (mode != null ? mode.hashCode() : 0);
			h = 31 * h + methodName.hashCode();
			h = 31 * h + arity;
			h = 31 * h + (isStatic ? 1 : 0);
			this.hash = h;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof InvokerLookupKey that)) return false;
			return arity == that.arity && isStatic == that.isStatic && mode == that.mode && methodName.equals(that.methodName);
		}

		@Override
		public int hashCode() {
			return hash;
		}
	}

	private static final class ExactMethodKey {
		final AccessMode mode;
		final Method     method;
		final int        hash;

		ExactMethodKey(AccessMode mode, Method method) {
			this.mode = mode;
			this.method = method;
			this.hash = 31 * (mode != null ? mode.hashCode() : 0) + method.hashCode();
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ExactMethodKey that)) return false;
			return mode == that.mode && method.equals(that.method);
		}

		@Override
		public int hashCode() {
			return hash;
		}
	}

	private static final class CtorLookupKey {
		final AccessMode mode;
		final int        arity;
		final int        hash;

		CtorLookupKey(AccessMode mode, int arity) {
			this.mode = mode;
			this.arity = arity;
			this.hash = 31 * (mode != null ? mode.hashCode() : 0) + arity;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof CtorLookupKey that)) return false;
			return mode == that.mode && arity == that.arity;
		}

		@Override
		public int hashCode() {
			return hash;
		}
	}

	private static final class ExactCtorKey {
		final AccessMode     mode;
		final Constructor<?> ctor;
		final int            hash;

		ExactCtorKey(AccessMode mode, Constructor<?> ctor) {
			this.mode = mode;
			this.ctor = ctor;
			this.hash = 31 * (mode != null ? mode.hashCode() : 0) + ctor.hashCode();
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ExactCtorKey that)) return false;
			return mode == that.mode && ctor.equals(that.ctor);
		}

		@Override
		public int hashCode() {
			return hash;
		}
	}

	private static final class ClassJITData {
		final Map<InvokerLookupKey, MagicInvoker>         invokerCache       = new ConcurrentHashMap<>();
		final Map<ExactMethodKey, MagicInvoker>           exactInvokerCache  = new ConcurrentHashMap<>();
		final Map<CtorLookupKey, MagicConstructorInvoker> ctorCache          = new ConcurrentHashMap<>();
		final Map<ExactCtorKey, MagicConstructorInvoker>  exactCtorCache     = new ConcurrentHashMap<>();
		final Map<String, MethodHandle>                   getterCache        = new ConcurrentHashMap<>();
		final Map<String, MethodHandle>                   setterCache        = new ConcurrentHashMap<>();
		final Map<ExactMethodKey, MethodHandle>           exactMethodCache   = new ConcurrentHashMap<>();
		final Map<ExactCtorKey, MethodHandle>             exactCtorStubCache = new ConcurrentHashMap<>();

		volatile SwitchPoint switchPoint = new SwitchPoint();
		volatile int         epoch       = 0;
	}

	private static final ClassValue<ClassJITData> JIT_DATA = new ClassValue<>() {
		@Override
		protected ClassJITData computeValue(Class<?> type) {
			return new ClassJITData();
		}
	};

	private static volatile SwitchPoint GLOBAL_SWITCH_POINT = new SwitchPoint();

	public static SwitchPoint getGlobalSwitchPoint() {
		return GLOBAL_SWITCH_POINT;
	}

	public static SwitchPoint getSwitchPoint(Class<?> clazz) {
		if (clazz == null) return null;
		return JIT_DATA.get(clazz).switchPoint;
	}

	public static int getEpoch(Class<?> clazz) {
		if (clazz == null) return 0;
		return JIT_DATA.get(clazz).epoch;
	}

	public static void invalidateClass(Class<?> clazz) {
		if (clazz == null) return;
		ClassJITData data = JIT_DATA.get(clazz);
		SwitchPoint  oldSp;
		synchronized (data) {
			oldSp = data.switchPoint;
			data.epoch++;
			data.invokerCache.clear();
			data.exactInvokerCache.clear();
			data.ctorCache.clear();
			data.exactCtorCache.clear();
			data.getterCache.clear();
			data.setterCache.clear();
			data.exactMethodCache.clear();
			data.exactCtorStubCache.clear();
			data.switchPoint = new SwitchPoint();
		}
		if (oldSp != null) {
			SwitchPoint.invalidateAll(new SwitchPoint[]{oldSp});
		}
		MethodResolver.invalidateClass(clazz);
		JSLinker.invalidateClass(clazz);
	}

	public static void invalidateAll() {
		SwitchPoint oldGlobal = GLOBAL_SWITCH_POINT;
		GLOBAL_SWITCH_POINT = new SwitchPoint();
		if (oldGlobal != null) {
			SwitchPoint.invalidateAll(new SwitchPoint[]{oldGlobal});
		}
	}

	// 架构优化说明：
	// 原各级反射与 JIT 存根缓存采用 ConcurrentHashMap<Key, ...>。
	// 以 Class<?> 为 Key（或持有 Class<?> 强引用）的全局并发 Map 存在两大缺陷：
	// 1. 强引用动态加载的 Class，阻碍其 ClassLoader 垃圾回收，造成元空间（Metaspace）内存泄漏。
	// 2. 并发哈希表读写存在哈希冲突与分段锁/CAS 竞争开销。
	// 改为 JDK 原生 ClassValue<ClassJITData> 后：
	private static final Object[] EMPTY_ARGS = new Object[0];
	private static final AtomicLong COUNTER = new AtomicLong();

	private static String getPackageName(Class<?> cls) {
		String name    = cls.getName();
		int    lastDot = name.lastIndexOf('.');
		return lastDot == -1 ? "" : name.substring(0, lastDot);
	}

	private static boolean isSystemClass(Class<?> cls) {
		String name = cls.getName();
		return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.");
	}

	private static Class<?> getHostClass(Class<?> targetClass) {
		if (targetClass == null || targetClass.getClassLoader() == null || isSystemClass(targetClass)) {
			return MagicJIT.class;
		}
		return targetClass;
	}

	private static String getInvokerClassName(Class<?> hostClass, String simpleName) {
		String pkg    = getPackageName(hostClass);
		String prefix = pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/";
		return prefix + simpleName + "_" + COUNTER.incrementAndGet();
	}

	private static Class<?> defineInvokerClass(Class<?> hostClass, byte[] bytes, ClassLoader fallbackLoader) {
		try {
			return Magic.defineHiddenOrAnonymousClass(hostClass, bytes);
		} catch (Throwable t) {
			return Magic.defineClass(fallbackLoader != null ? fallbackLoader : hostClass.getClassLoader(), bytes);
		}
	}

	private static final ClassValue<MethodHandle> FN_ADAPTER_MH_CACHE  = new ClassValue<>() {
		@Override
		protected MethodHandle computeValue(Class<?> type) {
			return createFunctionAdapterHandle(type);
		}
	};
	private static final ClassValue<MethodHandle> OBJ_ADAPTER_MH_CACHE = new ClassValue<>() {
		@Override
		protected MethodHandle computeValue(Class<?> type) {
			return createObjectAdapterHandle(type);
		}
	};

	@FunctionalInterface
	public interface MagicInvoker extends MagicBootstrapInvoker {
		Object invoke(Object target, Object[] args) throws Throwable;

		default Object invoke0(Object target) throws Throwable {
			return invoke(target, EMPTY_ARGS);
		}

		default Object invoke1(Object target, Object a0) throws Throwable {
			return invoke(target, new Object[]{a0});
		}

		default Object invoke2(Object target, Object a0, Object a1) throws Throwable {
			return invoke(target, new Object[]{a0, a1});
		}

		default Object invoke3(Object target, Object a0, Object a1, Object a2) throws Throwable {
			return invoke(target, new Object[]{a0, a1, a2});
		}

		// --- Primitive Fast-Path (Zero-Boxing Direct Call) ---
		default int invokeInt0(Object target) throws Throwable {
			return ((Number) invoke0(target)).intValue();
		}

		default int invokeInt1(Object target, int a0) throws Throwable {
			return ((Number) invoke1(target, a0)).intValue();
		}

		default int invokeInt2(Object target, int a0, int a1) throws Throwable {
			return ((Number) invoke2(target, a0, a1)).intValue();
		}

		default long invokeLong2(Object target, long a0, long a1) throws Throwable {
			return ((Number) invoke2(target, a0, a1)).longValue();
		}

		default double invokeDouble2(Object target, double a0, double a1) throws Throwable {
			return ((Number) invoke2(target, a0, a1)).doubleValue();
		}
	}

	@FunctionalInterface
	public interface MagicConstructorInvoker extends MagicBootstrapCtorInvoker {
		Object newInstance(Object[] args) throws Throwable;

		default Object newInstance0() throws Throwable {
			return newInstance(EMPTY_ARGS);
		}

		default Object newInstance1(Object a0) throws Throwable {
			return newInstance(new Object[]{a0});
		}

		default Object newInstance2(Object a0, Object a1) throws Throwable {
			return newInstance(new Object[]{a0, a1});
		}

		default Object newInstance3(Object a0, Object a1, Object a2) throws Throwable {
			return newInstance(new Object[]{a0, a1, a2});
		}
	}

	private static final class Arity0Invoker implements MagicInvoker {
		private final MethodHandle mh;
		private final MethodHandle rawIntMh;
		private final MethodHandle rawLongMh;
		private final MethodHandle rawDoubleMh;

		Arity0Invoker(MethodHandle mh, Method targetMethod) {
			this.mh = mh;
			MethodHandle intMh = null;
			MethodHandle longMh = null;
			MethodHandle doubleMh = null;
			if (targetMethod != null) {
				try {
					boolean isStatic = Modifier.isStatic(targetMethod.getModifiers());
					Class<?> ret = targetMethod.getReturnType();
					if (targetMethod.getParameterCount() == 0) {
						MethodHandle raw = Magic.lookup.unreflect(targetMethod);
						if (isStatic) {
							raw = MethodHandles.dropArguments(raw, 0, Object.class);
						}
						if (ret == int.class) {
							intMh = raw.asType(MethodType.methodType(int.class, Object.class));
						} else if (ret == long.class) {
							longMh = raw.asType(MethodType.methodType(long.class, Object.class));
						} else if (ret == double.class) {
							doubleMh = raw.asType(MethodType.methodType(double.class, Object.class));
						}
					}
				} catch (Throwable ignored) {
				}
			}
			this.rawIntMh = intMh;
			this.rawLongMh = longMh;
			this.rawDoubleMh = doubleMh;
		}

		Arity0Invoker(MethodHandle mh) {
			this(mh, null);
		}

		@Override
		public Object invoke(Object target, Object[] args) throws Throwable { return MagicHolder.invoke0(mh, target); }

		@Override
		public Object invoke0(Object target) throws Throwable { return MagicHolder.invoke0(mh, target); }

		@Override
		public int invokeInt0(Object target) throws Throwable {
			if (rawIntMh != null) return MagicHolder.invokeInt0(rawIntMh, target);
			return ((Number) invoke0(target)).intValue();
		}
	}

	private static final class Arity1Invoker implements MagicInvoker {
		private final MethodHandle mh;
		private final MethodHandle spreader;
		private final MethodHandle rawIntMh;
		private final MethodHandle rawLongMh;
		private final MethodHandle rawDoubleMh;

		Arity1Invoker(MethodHandle mh, Method targetMethod) {
			this.mh = mh;
			this.spreader = mh.asSpreader(Object[].class, 1);
			MethodHandle intMh = null;
			MethodHandle longMh = null;
			MethodHandle doubleMh = null;
			if (targetMethod != null) {
				try {
					boolean isStatic = Modifier.isStatic(targetMethod.getModifiers());
					Class<?> ret = targetMethod.getReturnType();
					Class<?>[] p = targetMethod.getParameterTypes();
					if (p.length == 1) {
						MethodHandle raw = Magic.lookup.unreflect(targetMethod);
						if (isStatic) {
							raw = MethodHandles.dropArguments(raw, 0, Object.class);
						}
						if (ret == int.class && p[0] == int.class) {
							intMh = raw.asType(MethodType.methodType(int.class, Object.class, int.class));
						} else if (ret == long.class && p[0] == long.class) {
							longMh = raw.asType(MethodType.methodType(long.class, Object.class, long.class));
						} else if (ret == double.class && p[0] == double.class) {
							doubleMh = raw.asType(MethodType.methodType(double.class, Object.class, double.class));
						}
					}
				} catch (Throwable ignored) {
				}
			}
			this.rawIntMh = intMh;
			this.rawLongMh = longMh;
			this.rawDoubleMh = doubleMh;
		}

		Arity1Invoker(MethodHandle mh) {
			this(mh, null);
		}

		@Override
		public Object invoke(Object target, Object[] args) throws Throwable {
			if (args != null && args.length == 1) return invoke1(target, args[0]);
			return spreader.invoke(target, args == null ? EMPTY_ARGS : args);
		}

		@Override
		public Object invoke1(Object target, Object a0) throws Throwable {
			if (rawIntMh != null && a0 instanceof Number n0) {
				return MagicHolder.invokeInt1(rawIntMh, target, n0.intValue());
			}
			if (rawDoubleMh != null && a0 instanceof Number n0) {
				return (double) rawDoubleMh.invokeExact(target, n0.doubleValue());
			}
			if (rawLongMh != null && a0 instanceof Number n0) {
				return (long) rawLongMh.invokeExact(target, n0.longValue());
			}
			return MagicHolder.invoke1(mh, target, a0);
		}

		@Override
		public int invokeInt1(Object target, int a0) throws Throwable {
			if (rawIntMh != null) return MagicHolder.invokeInt1(rawIntMh, target, a0);
			return ((Number) invoke1(target, a0)).intValue();
		}
	}

	private static final class Arity2Invoker implements MagicInvoker {
		private final MethodHandle mh;
		private final MethodHandle spreader;
		private final MethodHandle rawIntMh;
		private final MethodHandle rawLongMh;
		private final MethodHandle rawDoubleMh;

		Arity2Invoker(MethodHandle mh, Method targetMethod) {
			this.mh = mh;
			this.spreader = mh.asSpreader(Object[].class, 2);
			MethodHandle intMh = null;
			MethodHandle longMh = null;
			MethodHandle doubleMh = null;
			if (targetMethod != null) {
				try {
					boolean isStatic = Modifier.isStatic(targetMethod.getModifiers());
					Class<?> ret = targetMethod.getReturnType();
					Class<?>[] p = targetMethod.getParameterTypes();
					if (p.length == 2) {
						MethodHandle raw = Magic.lookup.unreflect(targetMethod);
						if (isStatic) {
							raw = MethodHandles.dropArguments(raw, 0, Object.class);
						}
						if (ret == int.class && p[0] == int.class && p[1] == int.class) {
							intMh = raw.asType(MethodType.methodType(int.class, Object.class, int.class, int.class));
						} else if (ret == long.class && p[0] == long.class && p[1] == long.class) {
							longMh = raw.asType(MethodType.methodType(long.class, Object.class, long.class, long.class));
						} else if (ret == double.class && p[0] == double.class && p[1] == double.class) {
							doubleMh = raw.asType(MethodType.methodType(double.class, Object.class, double.class, double.class));
						}
					}
				} catch (Throwable ignored) {
				}
			}
			this.rawIntMh = intMh;
			this.rawLongMh = longMh;
			this.rawDoubleMh = doubleMh;
		}

		Arity2Invoker(MethodHandle mh) {
			this(mh, null);
		}

		@Override
		public Object invoke(Object target, Object[] args) throws Throwable {
			if (args != null && args.length == 2) return invoke2(target, args[0], args[1]);
			return spreader.invoke(target, args == null ? EMPTY_ARGS : args);
		}

		@Override
		public Object invoke2(Object target, Object a0, Object a1) throws Throwable {
			if (rawIntMh != null && a0 instanceof Number n0 && a1 instanceof Number n1) {
				return MagicHolder.invokeInt2(rawIntMh, target, n0.intValue(), n1.intValue());
			}
			if (rawDoubleMh != null && a0 instanceof Number n0 && a1 instanceof Number n1) {
				return MagicHolder.invokeDouble2(rawDoubleMh, target, n0.doubleValue(), n1.doubleValue());
			}
			if (rawLongMh != null && a0 instanceof Number n0 && a1 instanceof Number n1) {
				return MagicHolder.invokeLong2(rawLongMh, target, n0.longValue(), n1.longValue());
			}
			return MagicHolder.invoke2(mh, target, a0, a1);
		}

		@Override
		public int invokeInt2(Object target, int a0, int a1) throws Throwable {
			if (rawIntMh != null) return MagicHolder.invokeInt2(rawIntMh, target, a0, a1);
			return ((Number) invoke2(target, a0, a1)).intValue();
		}

		@Override
		public long invokeLong2(Object target, long a0, long a1) throws Throwable {
			if (rawLongMh != null) return MagicHolder.invokeLong2(rawLongMh, target, a0, a1);
			return ((Number) invoke2(target, a0, a1)).longValue();
		}

		@Override
		public double invokeDouble2(Object target, double a0, double a1) throws Throwable {
			if (rawDoubleMh != null) return MagicHolder.invokeDouble2(rawDoubleMh, target, a0, a1);
			return ((Number) invoke2(target, a0, a1)).doubleValue();
		}
	}

	private static final class Arity3Invoker implements MagicInvoker {
		private final MethodHandle mh;
		private final MethodHandle spreader;
		private final MethodHandle rawIntMh;

		Arity3Invoker(MethodHandle mh, Method targetMethod) {
			this.mh = mh;
			this.spreader = mh.asSpreader(Object[].class, 3);
			MethodHandle intMh = null;
			if (targetMethod != null) {
				try {
					boolean isStatic = Modifier.isStatic(targetMethod.getModifiers());
					Class<?> ret = targetMethod.getReturnType();
					Class<?>[] p = targetMethod.getParameterTypes();
					if (p.length == 3 && ret == int.class && p[0] == int.class && p[1] == int.class && p[2] == int.class) {
						MethodHandle raw = Magic.lookup.unreflect(targetMethod);
						if (isStatic) {
							raw = MethodHandles.dropArguments(raw, 0, Object.class);
						}
						intMh = raw.asType(MethodType.methodType(int.class, Object.class, int.class, int.class, int.class));
					}
				} catch (Throwable ignored) {
				}
			}
			this.rawIntMh = intMh;
		}

		Arity3Invoker(MethodHandle mh) {
			this(mh, null);
		}

		@Override
		public Object invoke(Object target, Object[] args) throws Throwable {
			if (args != null && args.length == 3) return invoke3(target, args[0], args[1], args[2]);
			return spreader.invoke(target, args == null ? EMPTY_ARGS : args);
		}

		@Override
		public Object invoke3(Object target, Object a0, Object a1, Object a2) throws Throwable {
			if (rawIntMh != null && a0 instanceof Number n0 && a1 instanceof Number n1 && a2 instanceof Number n2) {
				return MagicHolder.invokeInt3(rawIntMh, target, n0.intValue(), n1.intValue(), n2.intValue());
			}
			return MagicHolder.invoke3(mh, target, a0, a1, a2);
		}
	}

	private static final class GenericInvoker implements MagicInvoker {
		private final MethodHandle spreader;
		GenericInvoker(MethodHandle spreader) { this.spreader = spreader; }
		@Override
		public Object invoke(Object target, Object[] args) throws Throwable {
			return spreader.invoke(target, args == null ? EMPTY_ARGS : args);
		}
	}

	private static final class Arity0CtorInvoker implements MagicConstructorInvoker {
		private final MethodHandle mh;
		Arity0CtorInvoker(MethodHandle mh, Constructor<?> targetCtor) { this.mh = mh; }
		Arity0CtorInvoker(MethodHandle mh) { this(mh, null); }
		@Override
		public Object newInstance(Object[] args) throws Throwable { return MagicHolder.new0(mh); }
		@Override
		public Object newInstance0() throws Throwable { return MagicHolder.new0(mh); }
	}

	private static final class Arity1CtorInvoker implements MagicConstructorInvoker {
		private final MethodHandle mh;
		private final MethodHandle spreader;
		private final MethodHandle rawIntCtorMh;

		Arity1CtorInvoker(MethodHandle mh, Constructor<?> targetCtor) {
			this.mh = mh;
			this.spreader = mh.asSpreader(Object[].class, 1);
			MethodHandle raw = null;
			if (targetCtor != null) {
				try {
					Class<?>[] p = targetCtor.getParameterTypes();
					if (p.length == 1 && p[0] == int.class) {
						MethodHandle unref = Magic.lookup.unreflectConstructor(targetCtor);
						raw = unref.asType(MethodType.methodType(Object.class, int.class));
					}
				} catch (Throwable ignored) {
				}
			}
			this.rawIntCtorMh = raw;
		}

		Arity1CtorInvoker(MethodHandle mh) {
			this(mh, null);
		}

		@Override
		public Object newInstance(Object[] args) throws Throwable {
			if (args != null && args.length == 1) return newInstance1(args[0]);
			return spreader.invoke(args == null ? EMPTY_ARGS : args);
		}

		@Override
		public Object newInstance1(Object a0) throws Throwable {
			if (rawIntCtorMh != null && a0 instanceof Number n0) {
				return MagicHolder.newInt1(rawIntCtorMh, n0.intValue());
			}
			return MagicHolder.new1(mh, a0);
		}
	}

	private static final class Arity2CtorInvoker implements MagicConstructorInvoker {
		private final MethodHandle mh;
		private final MethodHandle spreader;
		private final MethodHandle rawCtorMh;
		private final boolean      p0IsInt;
		private final boolean      p1IsString;

		Arity2CtorInvoker(MethodHandle mh, Constructor<?> targetCtor) {
			this.mh = mh;
			this.spreader = mh.asSpreader(Object[].class, 2);
			MethodHandle raw = null;
			boolean p0Int = false;
			boolean p1Str = false;
			if (targetCtor != null) {
				try {
					Class<?>[] p = targetCtor.getParameterTypes();
					if (p.length == 2 && p[0] == int.class && p[1] == String.class) {
						p0Int = true;
						p1Str = true;
						MethodHandle unref = Magic.lookup.unreflectConstructor(targetCtor);
						raw = unref.asType(MethodType.methodType(Object.class, int.class, String.class));
					}
				} catch (Throwable ignored) {
				}
			}
			this.rawCtorMh = raw;
			this.p0IsInt = p0Int;
			this.p1IsString = p1Str;
		}

		Arity2CtorInvoker(MethodHandle mh) {
			this(mh, null);
		}

		@Override
		public Object newInstance(Object[] args) throws Throwable {
			if (args != null && args.length == 2) return newInstance2(args[0], args[1]);
			return spreader.invoke(args == null ? EMPTY_ARGS : args);
		}

		@Override
		public Object newInstance2(Object a0, Object a1) throws Throwable {
			if (rawCtorMh != null && p0IsInt && p1IsString && a0 instanceof Number n0 && (a1 == null || a1 instanceof String)) {
				return MagicHolder.newIntString2(rawCtorMh, n0.intValue(), (String) a1);
			}
			return MagicHolder.new2(mh, a0, a1);
		}
	}

	private static final class Arity3CtorInvoker implements MagicConstructorInvoker {
		private final MethodHandle mh;
		private final MethodHandle spreader;

		Arity3CtorInvoker(MethodHandle mh, Constructor<?> targetCtor) {
			this.mh = mh;
			this.spreader = mh.asSpreader(Object[].class, 3);
		}

		Arity3CtorInvoker(MethodHandle mh) {
			this(mh, null);
		}

		@Override
		public Object newInstance(Object[] args) throws Throwable {
			if (args != null && args.length == 3) return newInstance3(args[0], args[1], args[2]);
			return spreader.invoke(args == null ? EMPTY_ARGS : args);
		}

		@Override
		public Object newInstance3(Object a0, Object a1, Object a2) throws Throwable { return MagicHolder.new3(mh, a0, a1, a2); }
	}

	private static final class GenericCtorInvoker implements MagicConstructorInvoker {
		private final MethodHandle spreader;
		GenericCtorInvoker(MethodHandle spreader) { this.spreader = spreader; }
		@Override
		public Object newInstance(Object[] args) throws Throwable {
			return spreader.invoke(args == null ? EMPTY_ARGS : args);
		}
	}









	public static MagicInvoker getMethodInvoker(Class<?> clazz, String methodName, int arity, boolean isStatic) {
		return getMethodInvoker(clazz, methodName, arity, isStatic, getEffectiveMode());
	}

	public static MagicInvoker getMethodInvoker(Class<?> clazz, String methodName, int arity, boolean isStatic,
	                                            AccessMode mode) {
		if (mode == AccessMode.AUTO) mode = getEffectiveMode();
		ClassJITData     data   = JIT_DATA.get(clazz);
		InvokerLookupKey key    = new InvokerLookupKey(mode, methodName, arity, isStatic);
		MagicInvoker     cached = data.invokerCache.get(key);
		if (cached != null) return cached;
		MagicInvoker invoker = createMethodInvoker(clazz, methodName, arity, isStatic, mode);
		if (invoker != null) data.invokerCache.put(key, invoker);
		return invoker;
	}

	public static MagicInvoker getMethodInvoker(Class<?> clazz, Method targetMethod) {
		return getMethodInvoker(clazz, targetMethod, getEffectiveMode());
	}

	public static MagicInvoker getMethodInvoker(Class<?> clazz, Method targetMethod, AccessMode mode) {
		if (targetMethod == null) return null;
		if (mode == AccessMode.AUTO) mode = getEffectiveMode();
		ClassJITData   data   = JIT_DATA.get(clazz);
		ExactMethodKey key    = new ExactMethodKey(mode, targetMethod);
		MagicInvoker   cached = data.exactInvokerCache.get(key);
		if (cached != null) return cached;
		MagicInvoker invoker = createMethodInvoker(clazz, targetMethod, mode);
		if (invoker != null) data.exactInvokerCache.put(key, invoker);
		return invoker;
	}

	public static MagicInvoker createMethodInvoker(Class<?> clazz, String methodName, int arity, boolean isStatic) {
		return createMethodInvoker(clazz, methodName, arity, isStatic, getEffectiveMode());
	}

	public static MagicInvoker createMethodInvoker(Class<?> clazz, String methodName, int arity, boolean isStatic,
	                                               AccessMode mode) {
		Method targetMethod = MethodResolver.findMethod(clazz, methodName, arity, isStatic);
		if (targetMethod == null) return null;
		return createMethodInvoker(clazz, targetMethod, mode);
	}

	public static MagicInvoker createMethodInvoker(Class<?> clazz, Method targetMethod) {
		return createMethodInvoker(clazz, targetMethod, getEffectiveMode());
	}

	public static MagicInvoker createMethodInvoker(Class<?> clazz, Method targetMethod, AccessMode mode) {
		if (targetMethod == null) return null;
		if (mode == AccessMode.AUTO) mode = getEffectiveMode();
		targetMethod.setAccessible(true);
		int arity = targetMethod.getParameterCount();
		try {
			MethodHandle exactMh = generateDirectMethodHandleStub(clazz, targetMethod);
			if (exactMh == null) return null;
			return switch (arity) {
				case 0 -> new Arity0Invoker(exactMh, targetMethod);
				case 1 -> new Arity1Invoker(exactMh, targetMethod);
				case 2 -> new Arity2Invoker(exactMh, targetMethod);
				case 3 -> new Arity3Invoker(exactMh, targetMethod);
				default -> new GenericInvoker(exactMh.asSpreader(Object[].class, arity));
			};
		} catch (Throwable e) {
			throw new RuntimeException("Failed to generate MagicInvoker for " + clazz.getName() + "#" + targetMethod.getName() + " (mode=" + mode + ")", e);
		}
	}

	public static MagicConstructorInvoker getConstructorInvoker(Class<?> clazz, int arity) {
		return getConstructorInvoker(clazz, arity, getEffectiveMode());
	}

	public static MagicConstructorInvoker getConstructorInvoker(Class<?> clazz, int arity, AccessMode mode) {
		if (mode == AccessMode.AUTO) mode = getEffectiveMode();
		ClassJITData            data   = JIT_DATA.get(clazz);
		CtorLookupKey           key    = new CtorLookupKey(mode, arity);
		MagicConstructorInvoker cached = data.ctorCache.get(key);
		if (cached != null) return cached;
		MagicConstructorInvoker invoker = createConstructorInvoker(clazz, arity, mode);
		if (invoker != null) data.ctorCache.put(key, invoker);
		return invoker;
	}

	public static MagicConstructorInvoker getConstructorInvoker(Class<?> clazz, Constructor<?> targetCtor) {
		return getConstructorInvoker(clazz, targetCtor, getEffectiveMode());
	}

	public static MagicConstructorInvoker getConstructorInvoker(Class<?> clazz, Constructor<?> targetCtor,
	                                                            AccessMode mode) {
		if (targetCtor == null) return null;
		if (mode == AccessMode.AUTO) mode = getEffectiveMode();
		ClassJITData            data   = JIT_DATA.get(clazz);
		ExactCtorKey            key    = new ExactCtorKey(mode, targetCtor);
		MagicConstructorInvoker cached = data.exactCtorCache.get(key);
		if (cached != null) return cached;
		MagicConstructorInvoker invoker = createConstructorInvoker(clazz, targetCtor, mode);
		if (invoker != null) data.exactCtorCache.put(key, invoker);
		return invoker;
	}

	public static MagicConstructorInvoker createConstructorInvoker(Class<?> clazz, Constructor<?> targetCtor) {
		return createConstructorInvoker(clazz, targetCtor, getEffectiveMode());
	}

	public static MagicConstructorInvoker createConstructorInvoker(Class<?> clazz, Constructor<?> targetCtor,
	                                                               AccessMode mode) {
		if (targetCtor == null) return null;
		if (mode == AccessMode.AUTO) mode = getEffectiveMode();
		targetCtor.setAccessible(true);
		int arity = targetCtor.getParameterCount();
		try {
			MethodHandle ctorMh     = Magic.lookup.unreflectConstructor(targetCtor);
			Class<?>[]   paramTypes = targetCtor.getParameterTypes();
			for (int i = 0; i < arity; i++) {
				MethodHandle filter = JSLinker.getArgumentFilter(paramTypes[i]);
				if (filter != null) {
					if (filter.type().returnType() != paramTypes[i]) {
						filter = filter.asType(filter.type().changeReturnType(paramTypes[i]));
					}
					ctorMh = MethodHandles.filterArguments(ctorMh, i, filter);
				}
			}
			Class<?>[] genericParams = new Class<?>[arity];
			Arrays.fill(genericParams, Object.class);
			MethodHandle finalCtorMh = ctorMh.asType(MethodType.methodType(Object.class, genericParams));
			return switch (arity) {
				case 0 -> new Arity0CtorInvoker(finalCtorMh, targetCtor);
				case 1 -> new Arity1CtorInvoker(finalCtorMh, targetCtor);
				case 2 -> new Arity2CtorInvoker(finalCtorMh, targetCtor);
				case 3 -> new Arity3CtorInvoker(finalCtorMh, targetCtor);
				default -> new GenericCtorInvoker(finalCtorMh.asSpreader(Object[].class, arity));
			};
		} catch (Throwable e) {
			throw new RuntimeException("Failed to generate MagicConstructorInvoker for " + clazz.getName(), e);
		}
	}

	public static MagicConstructorInvoker createConstructorInvoker(Class<?> clazz, int arity) {
		return createConstructorInvoker(clazz, arity, getEffectiveMode());
	}

	public static MagicConstructorInvoker createConstructorInvoker(Class<?> clazz, int arity, AccessMode mode) {
		if (mode == AccessMode.AUTO) mode = getEffectiveMode();
		Constructor<?> targetCtor = MethodResolver.findConstructor(clazz, arity);
		if (targetCtor == null) return null;
		return createConstructorInvoker(clazz, targetCtor, mode);
	}

	public static MethodHandle getFieldGetterStub(Class<?> clazz, String fieldName) {
		ClassJITData data   = JIT_DATA.get(clazz);
		MethodHandle cached = data.getterCache.get(fieldName);
		if (cached != null) return cached;
		MethodHandle stub = createExactFieldGetterStub(clazz, fieldName);
		if (stub != null) data.getterCache.put(fieldName, stub);
		return stub;
	}

	public static MethodHandle getFieldSetterStub(Class<?> clazz, String fieldName) {
		ClassJITData data   = JIT_DATA.get(clazz);
		MethodHandle cached = data.setterCache.get(fieldName);
		if (cached != null) return cached;
		MethodHandle stub = createExactFieldSetterStub(clazz, fieldName);
		if (stub != null) data.setterCache.put(fieldName, stub);
		return stub;
	}

	// 架构优化说明：
	// Java 基础类型字段访问已由 JSLinker.FieldMH (基于 Unsafe 直接偏移读取) 配合 createExactFieldGetterStub 统一覆盖。
	// 原 PRIMITIVE_GETTER_CACHE (ConcurrentHashMap<PrimMemberKey, MethodHandle>)、PrimMemberKey
	// 以及 getPrimitiveFieldGetterStub / createExactPrimitiveFieldGetterStub 属于冗余死代码，
	// 全工程无任何调用处，移除以消除死代码、静态类加载初始化开销及并发容器内存占用。

	public static MethodHandle createExactFieldGetterStub(Class<?> clazz, String fieldName) {
		ClassJITData data   = JIT_DATA.get(clazz);
		MethodHandle cached = data.getterCache.get(fieldName);
		if (cached != null) return cached;
		MethodHandle stub = generateExactFieldGetterStub(clazz, fieldName);
		if (stub != null) data.getterCache.put(fieldName, stub);
		return stub;
	}

	private static MethodHandle generateExactFieldGetterStub(Class<?> clazz, String fieldName) {
		Field field = getDeclaredFieldRecursive(clazz, fieldName);
		if (field == null) return null;
		field.setAccessible(true);
		long         offset = LinkerHelper.getFieldOffset(field);
		Class<?>     fType  = field.getType();
		MethodHandle mh;
		if (fType == int.class) { mh = FieldMH.GET_INT; } else if (fType == double.class) {
			mh = FieldMH.GET_DOUBLE;
		} else if (fType == long.class) {
			mh = FieldMH.GET_LONG;
		} else if (fType == float.class) {
			mh = FieldMH.GET_FLOAT;
		} else if (fType == short.class) {
			mh = FieldMH.GET_SHORT;
		} else if (fType == byte.class) {
			mh = FieldMH.GET_BYTE;
		} else if (fType == char.class) {
			mh = FieldMH.GET_CHAR;
		} else if (fType == boolean.class) {
			mh = FieldMH.GET_BOOLEAN;
		} else { mh = FieldMH.GET_OBJECT; }
		return MethodHandles.insertArguments(mh, 0, offset);
	}

	public static MethodHandle createExactFieldSetterStub(Class<?> clazz, String fieldName) {
		ClassJITData data   = JIT_DATA.get(clazz);
		MethodHandle cached = data.setterCache.get(fieldName);
		if (cached != null) return cached;
		MethodHandle stub = generateExactFieldSetterStub(clazz, fieldName);
		if (stub != null) data.setterCache.put(fieldName, stub);
		return stub;
	}

	private static MethodHandle generateExactFieldSetterStub(Class<?> clazz, String fieldName) {
		Field field = getDeclaredFieldRecursive(clazz, fieldName);
		if (field == null) return null;
		field.setAccessible(true);
		long         offset = LinkerHelper.getFieldOffset(field);
		Class<?>     fType  = field.getType();
		MethodHandle mh;
		if (fType == int.class) { mh = FieldMH.PUT_INT; } else if (fType == double.class) {
			mh = FieldMH.PUT_DOUBLE;
		} else if (fType == long.class) {
			mh = FieldMH.PUT_LONG;
		} else if (fType == float.class) {
			mh = FieldMH.PUT_FLOAT;
		} else if (fType == short.class) {
			mh = FieldMH.PUT_SHORT;
		} else if (fType == byte.class) {
			mh = FieldMH.PUT_BYTE;
		} else if (fType == char.class) {
			mh = FieldMH.PUT_CHAR;
		} else if (fType == boolean.class) {
			mh = FieldMH.PUT_BOOLEAN;
		} else { mh = FieldMH.PUT_OBJECT; }
		return MethodHandles.insertArguments(mh, 0, offset);
	}

	public static MethodHandle createExactMethodStub(Class<?> clazz, Method targetMethod) {
		return getExactMethodStub(clazz, targetMethod, getEffectiveMode());
	}

	public static MethodHandle getExactMethodStub(Class<?> clazz, Method targetMethod) {
		return getExactMethodStub(clazz, targetMethod, getEffectiveMode());
	}

	public static MethodHandle getExactMethodStub(Class<?> clazz, Method targetMethod, AccessMode mode) {
		if (mode == AccessMode.AUTO) mode = getEffectiveMode();
		ClassJITData   data   = JIT_DATA.get(clazz);
		ExactMethodKey key    = new ExactMethodKey(mode, targetMethod);
		MethodHandle   cached = data.exactMethodCache.get(key);
		if (cached != null) return cached;
		MethodHandle stub = generateExactMethodStub(clazz, targetMethod, mode);
		if (stub != null) data.exactMethodCache.put(key, stub);
		return stub;
	}

	public static MethodHandle generateExactMethodStub(Class<?> clazz, Method targetMethod, AccessMode mode) {
		return generateDirectMethodHandleStub(clazz, targetMethod);
	}

	public static MethodHandle createExactConstructorStub(Class<?> clazz, Constructor<?> targetCtor) {
		return getExactConstructorStub(clazz, targetCtor, getEffectiveMode());
	}

	public static MethodHandle getExactConstructorStub(Class<?> clazz, Constructor<?> targetCtor) {
		return getExactConstructorStub(clazz, targetCtor, getEffectiveMode());
	}

	public static MethodHandle getExactConstructorStub(Class<?> clazz, Constructor<?> targetCtor, AccessMode mode) {
		if (mode == AccessMode.AUTO) mode = getEffectiveMode();
		ClassJITData data   = JIT_DATA.get(clazz);
		ExactCtorKey key    = new ExactCtorKey(mode, targetCtor);
		MethodHandle cached = data.exactCtorStubCache.get(key);
		if (cached != null) return cached;
		MethodHandle stub = generateExactConstructorStub(clazz, targetCtor, mode);
		if (stub != null) data.exactCtorStubCache.put(key, stub);
		return stub;
	}

	public static MethodHandle generateExactConstructorStub(Class<?> clazz, Constructor<?> targetCtor, AccessMode mode) {
		return generateDirectConstructorStub(clazz, targetCtor);
	}

	private static MethodHandle generateDirectConstructorStub(Class<?> clazz, Constructor<?> targetCtor) {
		try {
			targetCtor.setAccessible(true);
			MethodHandle mh     = Magic.lookup.unreflectConstructor(targetCtor);
			Class<?>[]   pTypes = targetCtor.getParameterTypes();
			for (int i = 0; i < pTypes.length; i++) {
				MethodHandle filter = JSLinker.getArgumentFilter(pTypes[i]);
				if (filter != null) mh = MethodHandles.filterArguments(mh, i, filter);
			}
			MethodHandle dropped = MethodHandles.dropArguments(mh, 0, Object.class);
			Class<?>[] genericParams = new Class<?>[1 + pTypes.length];
			Arrays.fill(genericParams, Object.class);
			return dropped.asType(MethodType.methodType(Object.class, genericParams));
		} catch (Throwable t) {
			throw new RuntimeException("Failed to unreflect constructor for " + clazz.getName(), t);
		}
	}

	private static MethodHandle generateDirectMethodHandleStub(Class<?> clazz, Method targetMethod) {
		int        arity      = targetMethod.getParameterCount();
		boolean    isStatic   = Modifier.isStatic(targetMethod.getModifiers());
		Class<?>[] paramTypes = targetMethod.getParameterTypes();
		Class<?>   retType    = targetMethod.getReturnType();

		targetMethod.setAccessible(true);
		MethodHandle bound;
		try {
			bound = Magic.lookup.unreflect(targetMethod);
		} catch (Throwable t) {
			throw new RuntimeException("Failed to unreflect MethodHandle for " + clazz.getName() + "#" + targetMethod.getName(), t);
		}

		if (isStatic) {
			bound = MethodHandles.dropArguments(bound, 0, Object.class);
		}

		for (int i = 0; i < arity; i++) {
			MethodHandle filter = JSLinker.getArgumentFilter(paramTypes[i]);
			if (filter != null) {
				if (filter.type().returnType() != paramTypes[i]) {
					filter = filter.asType(filter.type().changeReturnType(paramTypes[i]));
				}
				bound = MethodHandles.filterArguments(bound, 1 + i, filter);
			}
		}

		if (retType == void.class) {
			bound = MethodHandles.filterReturnValue(bound, MethodHandles.constant(Object.class, JSUndefined.INSTANCE));
		}

		Class<?>[] genericParams = new Class<?>[1 + arity];
		Arrays.fill(genericParams, Object.class);
		return bound.asType(MethodType.methodType(Object.class, genericParams));
	}

	public static int getMethodBridgeCacheSize() {
		return 0;
	}

	public static int getCtorBridgeCacheSize() {
		return 0;
	}

	public static void boxPrimitive(MethodVisitor mv, Class<?> pt) {
		if (pt == int.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
		} else if (pt == double.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
		} else if (pt == long.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
		} else if (pt == boolean.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
		} else if (pt == float.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
		} else if (pt == byte.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
		} else if (pt == short.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
		} else if (pt == char.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
		}
	}

	public static Field getDeclaredFieldRecursive(Class<?> clazz, String fieldName) {
		Class<?> cur = clazz;
		while (cur != null && cur != Object.class) {
			for (Field field : cur.getDeclaredFields()) {
				if (field.getName().equals(fieldName)) return field;
			}
			cur = cur.getSuperclass();
		}
		return null;
	}

	public static void pushInt(MethodVisitor mv, int val) {
		if (val >= -1 && val <= 5) {
			mv.visitInsn(ICONST_0 + val);
		} else if (val >= Byte.MIN_VALUE && val <= Byte.MAX_VALUE) {
			mv.visitIntInsn(BIPUSH, val);
		} else if (val >= Short.MIN_VALUE && val <= Short.MAX_VALUE) {
			mv.visitIntInsn(SIPUSH, val);
		} else {
			mv.visitLdcInsn(val);
		}
	}

	public static Object resolveOrFail(byte refKind, Class<?> refc, String name, Object type) throws Throwable {
		Magic.install();
		if (type instanceof MethodType mt) {
			MethodHandle mh = switch (refKind) {
				case 5 -> Magic.lookup.findVirtual(refc, name, mt);
				case 6 -> Magic.lookup.findStatic(refc, name, mt);
				case 7 -> {
					if ("<init>".equals(name)) {
						yield Magic.lookup.findConstructor(refc, mt);
					} else {
						yield Magic.lookup.findSpecial(refc, name, mt, refc);
					}
				}
				default -> throw new IllegalArgumentException("Unsupported refKind: " + refKind);
			};
			return LinkerHelper.extractMemberName(mh);
		} else if (type instanceof Class<?> fieldType) {
			MethodHandle mh = switch (refKind) {
				case 1 -> Magic.lookup.findGetter(refc, name, fieldType);
				case 2 -> Magic.lookup.findStaticGetter(refc, name, fieldType);
				case 3 -> Magic.lookup.findSetter(refc, name, fieldType);
				case 4 -> Magic.lookup.findStaticSetter(refc, name, fieldType);
				default -> throw new IllegalArgumentException("Unsupported refKind: " + refKind);
			};
			return LinkerHelper.extractMemberName(mh);
		}
		throw new IllegalArgumentException("Unsupported type: " + type);
	}

	public static Object resolveOrFail(byte refKind, Class<?> refc, String name, MethodType type) throws Throwable {
		return resolveOrFail(refKind, refc, name, (Object) type);
	}

	//region 动态接口适配器生成 (JIT Interface Adapters)

	public static JSContext enterContext(JSContext cx) {
		if (cx == null) return null;
		JSContext prev = JSContext.CURRENT.get();
		if (prev != cx) {
			JSContext.CURRENT.set(cx);
		}
		return prev;
	}

	public static void exitContext(JSContext cx, JSContext prev) {
		if (cx == null) return;
		try {
			if (prev != cx) {
				cx.drainMicrotasks();
			}
		} finally {
			if (prev != cx) {
				if (prev != null) {
					JSContext.CURRENT.set(prev);
				} else {
					JSContext.CURRENT.remove();
				}
			}
		}
	}

	public static Object getFunctionAdapter(Class<?> targetType, JSFunction fn) {
		if (targetType == null || fn == null) return null;
		if (!targetType.isInterface()) return null;
		try {
			MethodHandle mh = FN_ADAPTER_MH_CACHE.get(targetType);
			if (mh != null) {
				return mh.invoke(fn);
			}
		} catch (Throwable ignored) {
		}
		return createProxyFunctionAdapter(targetType, fn);
	}

	public static Object getObjectAdapter(Class<?> targetType, JSObject jsObj) {
		if (targetType == null || jsObj == null) return null;
		if (!targetType.isInterface()) return null;
		try {
			MethodHandle mh = OBJ_ADAPTER_MH_CACHE.get(targetType);
			if (mh != null) {
				return mh.invoke(jsObj);
			}
		} catch (Throwable ignored) {
		}
		return createProxyObjectAdapter(targetType, jsObj);
	}

	private static MethodHandle createFunctionAdapterHandle(Class<?> targetType) {
		Constructor<?> ctor = createFunctionAdapterConstructor(targetType);
		if (ctor == null) return null;
		try {
			return Magic.lookup.unreflectConstructor(ctor).asType(MethodType.methodType(Object.class, JSFunction.class));
		} catch (Throwable e) {
			return null;
		}
	}

	private static MethodHandle createObjectAdapterHandle(Class<?> targetType) {
		Constructor<?> ctor = createObjectAdapterConstructor(targetType);
		if (ctor == null) return null;
		try {
			return Magic.lookup.unreflectConstructor(ctor).asType(MethodType.methodType(Object.class, JSObject.class));
		} catch (Throwable e) {
			return null;
		}
	}

	private static Constructor<?> createFunctionAdapterConstructor(Class<?> targetType) {
		Method sam = JSOps.getSingleAbstractMethod(targetType);
		if (sam == null) return null;

		try {
			Magic.install();
			Class<?>    hostClass   = getHostClass(targetType);
			String      className   = getInvokerClassName(hostClass, "JSFunctionAdapter");
			ClassWriter cw          = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			String      targetOwner = Type.getInternalName(targetType);
			cw.visit(
			 V1_8,
			 ACC_PUBLIC | ACC_FINAL,
			 className,
			 null,
			 "java/lang/Object",
			 new String[]{targetOwner}
			);

			// public final JSFunction fn;
			cw.visitField(ACC_PUBLIC | ACC_FINAL, "fn", "Lhope/magic/js/runtime/JSFunction;", null, null).visitEnd();
			// public final JSContext cx;
			cw.visitField(ACC_PUBLIC | ACC_FINAL, "cx", "Lhope/magic/js/runtime/JSContext;", null, null).visitEnd();

			// <init>(JSFunction fn, JSContext cx)
			MethodVisitor initMv2 = cw.visitMethod(ACC_PUBLIC, "<init>", "(Lhope/magic/js/runtime/JSFunction;Lhope/magic/js/runtime/JSContext;)V", null, null);
			initMv2.visitCode();
			initMv2.visitVarInsn(ALOAD, 0);
			initMv2.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
			initMv2.visitVarInsn(ALOAD, 0);
			initMv2.visitVarInsn(ALOAD, 1);
			initMv2.visitFieldInsn(PUTFIELD, className, "fn", "Lhope/magic/js/runtime/JSFunction;");
			initMv2.visitVarInsn(ALOAD, 0);
			initMv2.visitVarInsn(ALOAD, 2);
			initMv2.visitFieldInsn(PUTFIELD, className, "cx", "Lhope/magic/js/runtime/JSContext;");
			initMv2.visitInsn(RETURN);
			initMv2.visitMaxs(0, 0);
			initMv2.visitEnd();

			// <init>(JSFunction fn) -> this(fn, JSContext.current())
			MethodVisitor initMv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Lhope/magic/js/runtime/JSFunction;)V", null, null);
			initMv.visitCode();
			initMv.visitVarInsn(ALOAD, 0);
			initMv.visitVarInsn(ALOAD, 1);
			initMv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSContext", "current", "()Lhope/magic/js/runtime/JSContext;", false);
			initMv.visitMethodInsn(INVOKESPECIAL, className, "<init>", "(Lhope/magic/js/runtime/JSFunction;Lhope/magic/js/runtime/JSContext;)V", false);
			initMv.visitInsn(RETURN);
			initMv.visitMaxs(0, 0);
			initMv.visitEnd();

			// SAM method
			emitAdapterSAMMethod(cw, className, sam);

			// Object methods
			emitAdapterObjectMethods(cw, className, "JSFunctionAdapter[" + targetType.getSimpleName() + "]");

			cw.visitEnd();
			byte[]         bytes    = cw.toByteArray();
			ClassLoader    loader   = targetType.getClassLoader() != null ? targetType.getClassLoader() : MagicJIT.class.getClassLoader();
			Class<?>       genClass = defineInvokerClass(hostClass, bytes, loader);
			Constructor<?> ctor     = genClass.getDeclaredConstructor(JSFunction.class);
			ctor.setAccessible(true);
			return ctor;
		} catch (Throwable e) {
			return null;
		}
	}

	/**
	 * 生成优化的 JSFunction 调用字节码。
	 * 根据参数个数选择特化的方法（call0/call1/call2/call3）或通用 call 方法。
	 * 假设 JSFunction 对象已在栈顶，context 和 thisObj 也已被压栈。
	 * @param mv         方法访问器
	 * @param paramTypes 参数类型数组
	 * @param retType    返回类型
	 */
	private static void emitOptimizedJSFunctionCall(MethodVisitor mv, Class<?>[] paramTypes, Class<?> retType) {
		int arity = paramTypes.length;
		if (arity == 0) {
			mv.visitMethodInsn(INVOKEINTERFACE, "hope/magic/js/runtime/JSFunction", "call0", "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;)Ljava/lang/Object;", true);
		} else if (arity == 1) {
			emitLoadAndBox(mv, paramTypes[0], 1);
			mv.visitMethodInsn(INVOKEINTERFACE, "hope/magic/js/runtime/JSFunction", "call1", "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
		} else if (arity == 2) {
			emitLoadAndBox(mv, paramTypes[0], 1);
			int slot2 = (paramTypes[0] == long.class || paramTypes[0] == double.class) ? 3 : 2;
			emitLoadAndBox(mv, paramTypes[1], slot2);
			mv.visitMethodInsn(INVOKEINTERFACE, "hope/magic/js/runtime/JSFunction", "call2", "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
		} else if (arity == 3) {
			int slot = 1;
			for (int i = 0; i < 3; i++) {
				emitLoadAndBox(mv, paramTypes[i], slot);
				slot += (paramTypes[i] == long.class || paramTypes[i] == double.class) ? 2 : 1;
			}
			mv.visitMethodInsn(INVOKEINTERFACE, "hope/magic/js/runtime/JSFunction", "call3", "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
		} else if (arity == 4) {
			int slot = 1;
			for (int i = 0; i < 4; i++) {
				emitLoadAndBox(mv, paramTypes[i], slot);
				slot += (paramTypes[i] == long.class || paramTypes[i] == double.class) ? 2 : 1;
			}
			mv.visitMethodInsn(INVOKEINTERFACE, "hope/magic/js/runtime/JSFunction", "call4", "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
		} else {
			// >4 参数：构建 Object[] args
			pushInt(mv, arity);
			mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");

			int localSlot = 1;
			for (int i = 0; i < arity; i++) {
				Class<?> pt = paramTypes[i];
				mv.visitInsn(DUP);
				pushInt(mv, i);
				emitLoadAndBox(mv, pt, localSlot);
				localSlot += (pt == long.class || pt == double.class) ? 2 : 1;
				mv.visitInsn(AASTORE);
			}
			mv.visitMethodInsn(INVOKEINTERFACE, "hope/magic/js/runtime/JSFunction", "call", "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", true);
		}

		// 返回值拆箱 / 转换 (留栈顶)
		emitAdapterResultConversion(mv, retType);
	}

	private static void emitAdapterSAMMethod(ClassWriter cw, String className, Method sam) {
		String     methodName = sam.getName();
		String     methodDesc = Type.getMethodDescriptor(sam);
		Class<?>[] paramTypes = sam.getParameterTypes();
		Class<?>   retType    = sam.getReturnType();

		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, methodName, methodDesc, null, getExceptionNames(sam));
		mv.visitCode();

		int paramCountSlots = calcTotalParamSlots(paramTypes);
		int cxSlot          = 1 + paramCountSlots;
		int prevSlot        = cxSlot + 1;
		int resSlot         = prevSlot + 1;
		int resSlots        = (retType == long.class || retType == double.class) ? 2 : (retType == void.class ? 0 : 1);
		int exSlot          = resSlot + resSlots;

		// cx = this.cx;
		mv.visitVarInsn(ALOAD, 0);
		mv.visitFieldInsn(GETFIELD, className, "cx", "Lhope/magic/js/runtime/JSContext;");
		mv.visitVarInsn(ASTORE, cxSlot);

		// prev = enterContext(cx);
		mv.visitVarInsn(ALOAD, cxSlot);
		mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/MagicJIT", "enterContext", "(Lhope/magic/js/runtime/JSContext;)Lhope/magic/js/runtime/JSContext;", false);
		mv.visitVarInsn(ASTORE, prevSlot);

		Label tryStart     = new Label();
		Label tryEnd       = new Label();
		Label catchHandler = new Label();
		mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, null);

		mv.visitLabel(tryStart);

		if (isPrimitiveSAM(paramTypes, retType)) {
			// Primitive 特化直调 (Zero-Allocation, 无装箱)
			emitPrimitiveSAMMethodCall(mv, className, paramTypes, retType, cxSlot);
		} else {
			// 获取 fn
			mv.visitVarInsn(ALOAD, 0);
			mv.visitFieldInsn(GETFIELD, className, "fn", "Lhope/magic/js/runtime/JSFunction;");

			// 参数压栈: cx, thisObj
			mv.visitVarInsn(ALOAD, cxSlot);
			mv.visitInsn(ACONST_NULL); // thisObj

			// 尽可能无参数数组 特化直调 (call0, call1, call2, call3, call4)
			emitOptimizedJSFunctionCall(mv, paramTypes, retType);
		}

		if (retType == void.class) {
			mv.visitLabel(tryEnd);
			mv.visitVarInsn(ALOAD, cxSlot);
			mv.visitVarInsn(ALOAD, prevSlot);
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/MagicJIT", "exitContext", "(Lhope/magic/js/runtime/JSContext;Lhope/magic/js/runtime/JSContext;)V", false);
			mv.visitInsn(RETURN);
		} else {
			emitStoreLocal(mv, retType, resSlot);
			mv.visitLabel(tryEnd);
			mv.visitVarInsn(ALOAD, cxSlot);
			mv.visitVarInsn(ALOAD, prevSlot);
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/MagicJIT", "exitContext", "(Lhope/magic/js/runtime/JSContext;Lhope/magic/js/runtime/JSContext;)V", false);
			emitLoadLocal(mv, retType, resSlot);
			emitReturn(mv, retType);
		}

		// catch block
		mv.visitLabel(catchHandler);
		mv.visitVarInsn(ASTORE, exSlot);
		mv.visitVarInsn(ALOAD, cxSlot);
		mv.visitVarInsn(ALOAD, prevSlot);
		mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/MagicJIT", "exitContext", "(Lhope/magic/js/runtime/JSContext;Lhope/magic/js/runtime/JSContext;)V", false);
		mv.visitVarInsn(ALOAD, exSlot);
		mv.visitInsn(ATHROW);

		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static boolean isPrimitiveSAM(Class<?>[] paramTypes, Class<?> retType) {
		if (paramTypes.length > 4) return false;
		if (paramTypes.length == 0 && retType == void.class) return false;
		if (!retType.isPrimitive()) return false;
		for (Class<?> pt : paramTypes) {
			if (!pt.isPrimitive()) return false;
		}
		return true;
	}

	private static void emitPrimitiveSAMMethodCall(MethodVisitor mv, String className, Class<?>[] paramTypes,
	                                               Class<?> retType, int cxSlot) {
		// 1. 获取 fn
		mv.visitVarInsn(ALOAD, 0);
		mv.visitFieldInsn(GETFIELD, className, "fn", "Lhope/magic/js/runtime/JSFunction;");

		// 2. 参数压栈: cx, thisObj
		mv.visitVarInsn(ALOAD, cxSlot);
		mv.visitInsn(ACONST_NULL);

		// 3. 逐个将 primitive 参数加载并转为 double
		int slot = 1;
		for (Class<?> pt : paramTypes) {
			emitLoadPrimitiveAsDouble(mv, pt, slot);
			slot += (pt == long.class || pt == double.class) ? 2 : 1;
		}

		// 4. 调用 JSFunction.call{arity}Double
		int    arity    = paramTypes.length;
		String callName = "call" + arity + "Double";
		String callDesc = getPrimCallDesc(arity);
		mv.visitMethodInsn(INVOKEINTERFACE, "hope/magic/js/runtime/JSFunction", callName, callDesc, true);

		// 5. 将返回的 double 转为 retType (留栈顶)
		emitPrimitiveResultConversion(mv, retType);
	}

	private static String getPrimCallDesc(int arity) {
		return switch (arity) {
			case 0 -> "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;)D";
			case 1 -> "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;D)D";
			case 2 -> "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;DD)D";
			case 3 -> "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;DDD)D";
			case 4 -> "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;DDDD)D";
			default -> throw new IllegalArgumentException("Unsupported primitive arity: " + arity);
		};
	}

	private static void emitLoadPrimitiveAsDouble(MethodVisitor mv, Class<?> pt, int slot) {
		if (pt == double.class) {
			mv.visitVarInsn(DLOAD, slot);
		} else if (pt == float.class) {
			mv.visitVarInsn(FLOAD, slot);
			mv.visitInsn(F2D);
		} else if (pt == long.class) {
			mv.visitVarInsn(LLOAD, slot);
			mv.visitInsn(L2D);
		} else if (pt == int.class || pt == short.class || pt == byte.class || pt == char.class || pt == boolean.class) {
			mv.visitVarInsn(ILOAD, slot);
			mv.visitInsn(I2D);
		} else {
			throw new IllegalArgumentException("Not a primitive type: " + pt);
		}
	}

	private static void emitPrimitiveResultConversion(MethodVisitor mv, Class<?> retType) {
		if (retType == void.class) {
			mv.visitInsn(POP2);
		} else if (retType == double.class) {
			// already double
		} else if (retType == float.class) {
			mv.visitInsn(D2F);
		} else if (retType == long.class) {
			mv.visitInsn(D2L);
		} else if (retType == int.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toInt", "(D)I", false);
		} else if (retType == short.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toInt", "(D)I", false);
			mv.visitInsn(I2S);
		} else if (retType == byte.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toInt", "(D)I", false);
			mv.visitInsn(I2B);
		} else if (retType == char.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toInt", "(D)I", false);
			mv.visitInsn(I2C);
		} else if (retType == boolean.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toBoolean", "(D)Z", false);
		} else {
			throw new IllegalArgumentException("Not a primitive return type: " + retType);
		}
	}

	private static Constructor<?> createObjectAdapterConstructor(Class<?> targetType) {
		try {
			Magic.install();
			Class<?>    hostClass   = getHostClass(targetType);
			String      className   = getInvokerClassName(hostClass, "JSObjectAdapter");
			ClassWriter cw          = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			String      targetOwner = Type.getInternalName(targetType);
			cw.visit(
			 V1_8,
			 ACC_PUBLIC | ACC_FINAL,
			 className,
			 null,
			 "java/lang/Object",
			 new String[]{targetOwner}
			);

			// public final JSObject jsObj;
			cw.visitField(ACC_PUBLIC | ACC_FINAL, "jsObj", "Lhope/magic/js/runtime/JSObject;", null, null).visitEnd();
			// public final JSContext cx;
			cw.visitField(ACC_PUBLIC | ACC_FINAL, "cx", "Lhope/magic/js/runtime/JSContext;", null, null).visitEnd();

			// <init>(JSObject jsObj, JSContext cx)
			MethodVisitor initMv2 = cw.visitMethod(ACC_PUBLIC, "<init>", "(Lhope/magic/js/runtime/JSObject;Lhope/magic/js/runtime/JSContext;)V", null, null);
			initMv2.visitCode();
			initMv2.visitVarInsn(ALOAD, 0);
			initMv2.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
			initMv2.visitVarInsn(ALOAD, 0);
			initMv2.visitVarInsn(ALOAD, 1);
			initMv2.visitFieldInsn(PUTFIELD, className, "jsObj", "Lhope/magic/js/runtime/JSObject;");
			initMv2.visitVarInsn(ALOAD, 0);
			initMv2.visitVarInsn(ALOAD, 2);
			initMv2.visitFieldInsn(PUTFIELD, className, "cx", "Lhope/magic/js/runtime/JSContext;");
			initMv2.visitInsn(RETURN);
			initMv2.visitMaxs(0, 0);
			initMv2.visitEnd();

			// <init>(JSObject jsObj) -> this(jsObj, JSContext.current())
			MethodVisitor initMv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Lhope/magic/js/runtime/JSObject;)V", null, null);
			initMv.visitCode();
			initMv.visitVarInsn(ALOAD, 0);
			initMv.visitVarInsn(ALOAD, 1);
			initMv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/JSContext", "current", "()Lhope/magic/js/runtime/JSContext;", false);
			initMv.visitMethodInsn(INVOKESPECIAL, className, "<init>", "(Lhope/magic/js/runtime/JSObject;Lhope/magic/js/runtime/JSContext;)V", false);
			initMv.visitInsn(RETURN);
			initMv.visitMaxs(0, 0);
			initMv.visitEnd();

			// Implement all methods of interface
			for (Method m : targetType.getMethods()) {
				if (isObjectMethod(m)) continue;
				emitObjectAdapterMethod(cw, className, m);
			}

			// Object methods
			emitAdapterObjectMethods(cw, className, "JSObjectAdapter[" + targetType.getSimpleName() + "]");

			cw.visitEnd();
			byte[]         bytes    = cw.toByteArray();
			ClassLoader    loader   = targetType.getClassLoader() != null ? targetType.getClassLoader() : MagicJIT.class.getClassLoader();
			Class<?>       genClass = defineInvokerClass(hostClass, bytes, loader);
			Constructor<?> ctor     = genClass.getDeclaredConstructor(JSObject.class);
			ctor.setAccessible(true);
			return ctor;
		} catch (Throwable e) {
			return null;
		}
	}

	private static void emitObjectAdapterMethod(ClassWriter cw, String className, Method m) {
		String     methodName = m.getName();
		String     methodDesc = Type.getMethodDescriptor(m);
		Class<?>[] paramTypes = m.getParameterTypes();
		Class<?>   retType    = m.getReturnType();

		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, methodName, methodDesc, null, getExceptionNames(m));
		mv.visitCode();

		int paramCountSlots = calcTotalParamSlots(paramTypes);
		int cxSlot          = 1 + paramCountSlots;
		int prevSlot        = cxSlot + 1;
		int memberSlot      = prevSlot + 1;
		int resSlot         = memberSlot + 1;
		int resSlots        = (retType == long.class || retType == double.class) ? 2 : (retType == void.class ? 0 : 1);
		int exSlot          = resSlot + resSlots;

		// cx = this.cx;
		mv.visitVarInsn(ALOAD, 0);
		mv.visitFieldInsn(GETFIELD, className, "cx", "Lhope/magic/js/runtime/JSContext;");
		mv.visitVarInsn(ASTORE, cxSlot);

		// prev = enterContext(cx);
		mv.visitVarInsn(ALOAD, cxSlot);
		mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/MagicJIT", "enterContext", "(Lhope/magic/js/runtime/JSContext;)Lhope/magic/js/runtime/JSContext;", false);
		mv.visitVarInsn(ASTORE, prevSlot);

		Label tryStart     = new Label();
		Label tryEnd       = new Label();
		Label catchHandler = new Label();
		mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, null);

		mv.visitLabel(tryStart);

		// 1. Object member = this.jsObj.get(propId);
		int propId = SymbolTable.id(methodName);
		mv.visitVarInsn(ALOAD, 0);
		mv.visitFieldInsn(GETFIELD, className, "jsObj", "Lhope/magic/js/runtime/JSObject;");
		pushInt(mv, propId);
		mv.visitMethodInsn(INVOKEVIRTUAL, "hope/magic/js/runtime/JSObject", "get", "(I)Ljava/lang/Object;", false);
		mv.visitVarInsn(ASTORE, memberSlot);

		// 2. if (member instanceof JSFunction)
		mv.visitVarInsn(ALOAD, memberSlot);
		mv.visitTypeInsn(INSTANCEOF, "hope/magic/js/runtime/JSFunction");
		Label notFnLabel = new Label();
		mv.visitJumpInsn(IFEQ, notFnLabel);

		// member.call*(cx, this.jsObj, ...)
		mv.visitVarInsn(ALOAD, memberSlot);
		mv.visitTypeInsn(CHECKCAST, "hope/magic/js/runtime/JSFunction");
		mv.visitVarInsn(ALOAD, cxSlot); // cx
		mv.visitVarInsn(ALOAD, 0);
		mv.visitFieldInsn(GETFIELD, className, "jsObj", "Lhope/magic/js/runtime/JSObject;"); // thisObj = jsObj
		emitOptimizedJSFunctionCall(mv, paramTypes, retType);

		Label doneLabel = new Label();
		mv.visitJumpInsn(GOTO, doneLabel);

		// 3. else if (member != JSUndefined.INSTANCE)
		mv.visitLabel(notFnLabel);
		mv.visitVarInsn(ALOAD, memberSlot);
		mv.visitFieldInsn(GETSTATIC, "hope/magic/js/runtime/JSUndefined", "INSTANCE", "Lhope/magic/js/runtime/JSUndefined;");
		Label undefLabel = new Label();
		mv.visitJumpInsn(IF_ACMPEQ, undefLabel);

		mv.visitVarInsn(ALOAD, memberSlot);
		emitAdapterResultConversion(mv, retType);
		mv.visitJumpInsn(GOTO, doneLabel);

		// 4. Default return value
		mv.visitLabel(undefLabel);
		emitDefaultValue(mv, retType);

		mv.visitLabel(doneLabel);
		if (retType == void.class) {
			mv.visitLabel(tryEnd);
			mv.visitVarInsn(ALOAD, cxSlot);
			mv.visitVarInsn(ALOAD, prevSlot);
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/MagicJIT", "exitContext", "(Lhope/magic/js/runtime/JSContext;Lhope/magic/js/runtime/JSContext;)V", false);
			mv.visitInsn(RETURN);
		} else {
			emitStoreLocal(mv, retType, resSlot);
			mv.visitLabel(tryEnd);
			mv.visitVarInsn(ALOAD, cxSlot);
			mv.visitVarInsn(ALOAD, prevSlot);
			mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/MagicJIT", "exitContext", "(Lhope/magic/js/runtime/JSContext;Lhope/magic/js/runtime/JSContext;)V", false);
			emitLoadLocal(mv, retType, resSlot);
			emitReturn(mv, retType);
		}

		// catch block
		mv.visitLabel(catchHandler);
		mv.visitVarInsn(ASTORE, exSlot);
		mv.visitVarInsn(ALOAD, cxSlot);
		mv.visitVarInsn(ALOAD, prevSlot);
		mv.visitMethodInsn(INVOKESTATIC, "hope/magic/js/runtime/MagicJIT", "exitContext", "(Lhope/magic/js/runtime/JSContext;Lhope/magic/js/runtime/JSContext;)V", false);
		mv.visitVarInsn(ALOAD, exSlot);
		mv.visitInsn(ATHROW);

		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	private static int calcTotalParamSlots(Class<?>[] paramTypes) {
		int count = 0;
		for (Class<?> p : paramTypes) {
			count += (p == long.class || p == double.class) ? 2 : 1;
		}
		return count;
	}

	private static void emitStoreLocal(MethodVisitor mv, Class<?> type, int slot) {
		if (type == int.class || type == boolean.class || type == byte.class || type == short.class || type == char.class) {
			mv.visitVarInsn(ISTORE, slot);
		} else if (type == long.class) {
			mv.visitVarInsn(LSTORE, slot);
		} else if (type == float.class) {
			mv.visitVarInsn(FSTORE, slot);
		} else if (type == double.class) {
			mv.visitVarInsn(DSTORE, slot);
		} else {
			mv.visitVarInsn(ASTORE, slot);
		}
	}

	private static void emitLoadLocal(MethodVisitor mv, Class<?> type, int slot) {
		if (type == int.class || type == boolean.class || type == byte.class || type == short.class || type == char.class) {
			mv.visitVarInsn(ILOAD, slot);
		} else if (type == long.class) {
			mv.visitVarInsn(LLOAD, slot);
		} else if (type == float.class) {
			mv.visitVarInsn(FLOAD, slot);
		} else if (type == double.class) {
			mv.visitVarInsn(DLOAD, slot);
		} else {
			mv.visitVarInsn(ALOAD, slot);
		}
	}

	private static void emitReturn(MethodVisitor mv, Class<?> type) {
		if (type == void.class) {
			mv.visitInsn(RETURN);
		} else if (type == int.class || type == boolean.class || type == byte.class || type == short.class || type == char.class) {
			mv.visitInsn(IRETURN);
		} else if (type == long.class) {
			mv.visitInsn(LRETURN);
		} else if (type == float.class) {
			mv.visitInsn(FRETURN);
		} else if (type == double.class) {
			mv.visitInsn(DRETURN);
		} else {
			mv.visitInsn(ARETURN);
		}
	}

	private static void emitDefaultValue(MethodVisitor mv, Class<?> retType) {
		if (retType == void.class) {
			// nothing
		} else if (retType == int.class || retType == boolean.class || retType == byte.class || retType == short.class || retType == char.class) {
			mv.visitInsn(ICONST_0);
		} else if (retType == long.class) {
			mv.visitInsn(LCONST_0);
		} else if (retType == float.class) {
			mv.visitInsn(FCONST_0);
		} else if (retType == double.class) {
			mv.visitInsn(DCONST_0);
		} else {
			mv.visitInsn(ACONST_NULL);
		}
	}

	private static void emitLoadAndBox(MethodVisitor mv, Class<?> pt, int localSlot) {
		if (pt == int.class) {
			mv.visitVarInsn(ILOAD, localSlot);
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
		} else if (pt == long.class) {
			mv.visitVarInsn(LLOAD, localSlot);
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
		} else if (pt == double.class) {
			mv.visitVarInsn(DLOAD, localSlot);
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
		} else if (pt == float.class) {
			mv.visitVarInsn(FLOAD, localSlot);
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
		} else if (pt == boolean.class) {
			mv.visitVarInsn(ILOAD, localSlot);
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
		} else if (pt == byte.class) {
			mv.visitVarInsn(ILOAD, localSlot);
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
		} else if (pt == short.class) {
			mv.visitVarInsn(ILOAD, localSlot);
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
		} else if (pt == char.class) {
			mv.visitVarInsn(ILOAD, localSlot);
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
		} else {
			mv.visitVarInsn(ALOAD, localSlot);
		}
	}

	private static void emitAdapterResultConversion(MethodVisitor mv, Class<?> retType) {
		if (retType == void.class) {
			mv.visitInsn(POP);
		} else if (retType == int.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toInt", "(Ljava/lang/Object;)I", false);
		} else if (retType == long.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toLong", "(Ljava/lang/Object;)J", false);
		} else if (retType == double.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toDouble", "(Ljava/lang/Object;)D", false);
		} else if (retType == float.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toDouble", "(Ljava/lang/Object;)D", false);
			mv.visitInsn(D2F);
		} else if (retType == boolean.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "isTruthy", "(Ljava/lang/Object;)Z", false);
		} else if (retType == short.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toInt", "(Ljava/lang/Object;)I", false);
			mv.visitInsn(I2S);
		} else if (retType == byte.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toInt", "(Ljava/lang/Object;)I", false);
			mv.visitInsn(I2B);
		} else if (retType == char.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toChar", "(Ljava/lang/Object;)C", false);
		} else if (retType == String.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toStr", "(Ljava/lang/Object;)Ljava/lang/String;", false);
		} else {
			mv.visitLdcInsn(Type.getType(retType));
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "castValue", "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;", false);
			mv.visitTypeInsn(CHECKCAST, Type.getInternalName(retType));
		}
	}

	private static void emitAdapterObjectMethods(ClassWriter cw, String className, String toStringText) {
		// toString()
		MethodVisitor tsMv = cw.visitMethod(ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
		tsMv.visitCode();
		tsMv.visitLdcInsn(toStringText);
		tsMv.visitInsn(ARETURN);
		tsMv.visitMaxs(0, 0);
		tsMv.visitEnd();

		// hashCode()
		MethodVisitor hcMv = cw.visitMethod(ACC_PUBLIC, "hashCode", "()I", null, null);
		hcMv.visitCode();
		hcMv.visitVarInsn(ALOAD, 0);
		hcMv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "identityHashCode", "(Ljava/lang/Object;)I", false);
		hcMv.visitInsn(IRETURN);
		hcMv.visitMaxs(0, 0);
		hcMv.visitEnd();

		// equals(Object)
		MethodVisitor eqMv = cw.visitMethod(ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
		eqMv.visitCode();
		eqMv.visitVarInsn(ALOAD, 0);
		eqMv.visitVarInsn(ALOAD, 1);
		Label notEq = new Label();
		eqMv.visitJumpInsn(IF_ACMPNE, notEq);
		eqMv.visitInsn(ICONST_1);
		eqMv.visitInsn(IRETURN);
		eqMv.visitLabel(notEq);
		eqMv.visitInsn(ICONST_0);
		eqMv.visitInsn(IRETURN);
		eqMv.visitMaxs(0, 0);
		eqMv.visitEnd();
	}

	private static boolean isObjectMethod(Method m) {
		String     name   = m.getName();
		Class<?>[] params = m.getParameterTypes();
		if ("equals".equals(name) && params.length == 1 && params[0] == Object.class) return true;
		if ("hashCode".equals(name) && params.length == 0) return true;
		if ("toString".equals(name) && params.length == 0) return true;
		return false;
	}

	private static String[] getExceptionNames(Method m) {
		Class<?>[] ex = m.getExceptionTypes();
		if (ex.length == 0) return null;
		String[] names = new String[ex.length];
		for (int i = 0; i < ex.length; i++) {
			names[i] = Type.getInternalName(ex[i]);
		}
		return names;
	}

	public static Object createProxyFunctionAdapter(Class<?> targetType, JSFunction fn) {
		return createProxyFunctionAdapter(targetType, fn, JSContext.current());
	}

	public static Object createProxyFunctionAdapter(Class<?> targetType, JSFunction fn, JSContext cx) {
		ClassLoader cl = targetType.getClassLoader() != null ? targetType.getClassLoader() : MagicJIT.class.getClassLoader();
		return java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{targetType}, (proxy, method, methodArgs) -> {
			if (method.getDeclaringClass() == Object.class) {
				String name = method.getName();
				switch (name) {
					case "toString" -> { return "JSFunctionAdapter[" + targetType.getSimpleName() + "]"; }
					case "hashCode" -> { return System.identityHashCode(proxy); }
					case "equals" -> { return proxy == (methodArgs != null && methodArgs.length > 0 ? methodArgs[0] : null); }
				}
			}
			Object[]  safeArgs = methodArgs == null ? new Object[0] : methodArgs;
			JSContext prev     = enterContext(cx);
			try {
				Object   result  = fn.call(cx, null, safeArgs);
				Class<?> retType = method.getReturnType();
				if (retType == void.class) return null;
				return JSOps.castValue(result, retType);
			} finally {
				exitContext(cx, prev);
			}
		});
	}

	public static Object createProxyObjectAdapter(Class<?> targetType, JSObject jsObj) {
		return createProxyObjectAdapter(targetType, jsObj, JSContext.current());
	}

	public static Object createProxyObjectAdapter(Class<?> targetType, JSObject jsObj, JSContext cx) {
		ClassLoader cl = targetType.getClassLoader() != null ? targetType.getClassLoader() : MagicJIT.class.getClassLoader();
		return java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{targetType}, (proxy, method, methodArgs) -> {
			if (method.getDeclaringClass() == Object.class) {
				String name = method.getName();
				switch (name) {
					case "toString" -> { return "JSObjectAdapter[" + targetType.getSimpleName() + "]"; }
					case "hashCode" -> { return System.identityHashCode(proxy); }
					case "equals" -> { return proxy == (methodArgs != null && methodArgs.length > 0 ? methodArgs[0] : null); }
				}
			}
			String methodName = method.getName();
			Object member     = jsObj.get(methodName);
			if (member instanceof JSFunction fn) {
				Object[]  safeArgs = methodArgs == null ? new Object[0] : methodArgs;
				JSContext prev     = enterContext(cx);
				try {
					Object   result  = fn.call(cx, jsObj, safeArgs);
					Class<?> retType = method.getReturnType();
					if (retType == void.class) return null;
					return JSOps.castValue(result, retType);
				} finally {
					exitContext(cx, prev);
				}
			}
			if (method.isDefault()) {
				return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, methodArgs);
			}
			if (member != null && member != JSUndefined.INSTANCE) {
				return JSOps.castValue(member, method.getReturnType());
			}
			Class<?> retType = method.getReturnType();
			if (retType == void.class) return null;
			return JSOps.castValue(null, retType);
		});
	}
	//endregion
}
