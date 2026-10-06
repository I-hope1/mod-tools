package nipx.ref;

import sun.misc.Unsafe;
import nipx.Reflect;
import org.objectweb.asm.*;

import java.lang.invoke.*;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 补丁类引用的两个 bridge 的合并实现，统一走 {@code invokedynamic} + 单一 bootstrap。
 *
 * <p><b>用途一（{@link #KIND_PROTECTED}）</b>：宿主访问跨包 {@code protected} 成员时，
 * hidden class 不是宿主子类，直接调用会因 JVMS §5.4.4 receiver check 抛
 * {@code IllegalAccessError}。用宿主特权 Lookup 在此解析出 {@link MethodHandle}，
 * 调用点直接命中。</p>
 *
 * <p><b>用途二（{@link #KIND_CONDITIONAL}）</b>：新增字段的写入（final 与非 final 统一）
 * 走 Unsafe 条件 CAS——仅当字段当前等于该类型默认值（引用 {@code null}、数值 {@code 0}、
 * {@code boolean} {@code false}、{@code char} {@code '\u0000'}）时才写入。
 * CAS 本身即 volatile 语义，原子读-比较-写一次完成，无 TOCTOU 竞态。
 * 这样 redefine 之后、补丁执行之前或期间其他线程给字段赋过值时，补丁不会覆盖；
 * 代价是字段已是默认值以外时跳过（保守方向）。</p>
 *
 * <p><b>方法名分布（对照 {@code jdk.internal.misc.Unsafe} / {@code sun.misc.Unsafe}）</b>：</p>
 * <ul>
 *   <li><b>引用</b>：JDK 8 是 {@code compareAndSwapObject}；JDK 9~11 是 {@code compareAndSetObject}/{@code putObjectVolatile}；
 *       JDK 12+ 通过 JDK-8207146 改为 {@code compareAndSetReference}/{@code putReferenceVolatile}，
 *       同时 {@code compareAndSetObject} 作为 @Deprecated 别名保留；
 *       JDK 23+ 通过 JDK-8327729 移除别名。用 fallback 链分流。</li>
 *   <li><b>boolean/byte/char/short/int/long</b>：JDK 9 引入 VarHandle 时就叫
 *       {@code compareAndSetXxx}。JDK 8 下 {@code sun.misc.Unsafe} 仅包含 {@code compareAndSwapInt/Long}，
 *       无子字 CAS（boolean/byte/char/short 找不到 CAS 时自动降级为 volatile 写）。</li>
 *   <li><b>float/double</b>：<b>不依赖 {@code compareAndSetFloat/Double} 是否存在</b>，
 *       一律用 raw bits 转成 int/long CAS（见 {@link #rawBitsConditional}）。
 *       原因：{@code jdk.internal.misc.Unsafe}（JDK 9+）<b>有</b>
 *       {@code compareAndSetFloat/Double}，而 JDK 8 的 {@code sun.misc.Unsafe}
 *       <b>没有</b>（实测 1.8.0_332：absent；25.0.2：present）。若按名字探测，
 *       同一补丁在 JDK 9+ 是条件 CAS、在 JDK 8 退化成 {@code putFloatVolatile}
 *       无条件写 —— 会覆盖其他线程已写入的值，与本节"保守方向"相反。
 *       统一走 raw bits 后两条 JDK 语义一致，且与 §4.1 T0 的<b>按位判零</b>口径对齐：
 *       {@code -0.0f} 位模式非零，被视为"已有值"而跳过。</li>
 * </ul>
 *
 * <p><b>bsmArgs 布局</b>：{@code [int kind, int opcode, Class owner, Class host]}。
 * <ul>
 *   <li>{@code KIND_PROTECTED}：{@code owner} = 成员声明类，{@code host} = 宿主类。</li>
 *   <li>{@code KIND_CONDITIONAL}：{@code owner} = 宿主类，{@code host} = 宿主类（占位）。
 *       字段类型从 {@code callSiteType} 派生：静态字段在参数 0，实例字段在参数 1。</li>
 * </ul>
 *
 * <p><b>模板预缓存</b>：9 个模板在静态块里一次组合成 {@code (Object, long, V)void}，
 * BSM 每次调用点链接时只做 {@code insertArguments(offset)}（静态字段再加一次
 * {@code insertArguments(base)}）。</p>
 */
@SuppressWarnings("removal")
public final class HotswapBridge {

	/** protected 成员桥接：owner 是声明类，host 是宿主类。 */
	public static final int KIND_PROTECTED = 0;

	/** 新增字段的条件 CAS 写：owner 是宿主类。 */
	public static final int KIND_CONDITIONAL = 1;

	/**
	 * 强制写（{@code @HotswapReinit(mode = OVERWRITE)}，{@code docs/INIT_FIX.md} §1.1）：
	 * owner 是宿主类。
	 * <p>与 {@link #KIND_CONDITIONAL} 的唯一差别是<b>不比较旧值</b>：无条件 volatile 写。
	 * 之所以必须经 Unsafe 而不是 {@code putfield}：{@code final} 字段只允许在声明类的
	 * 构造器里被赋值，而补丁是宿主的一个 hidden nestmate class，直接 {@code putfield}
	 * 会在链接期抛 {@code IllegalAccessError}。</p>
	 */
	public static final int KIND_FORCE = 2;

	private static final Unsafe UNSAFE      = Reflect.UNSAFE;
	private static final Lookup IMPL_LOOKUP = Reflect.IMPL_LOOKUP;

	/**
	 * JDK 9+ 的 {@code jdk.internal.misc.Unsafe}（经 IMPL_LOOKUP 取得，无需 --add-exports）。
	 * 它自带 {@code compareAndSetByte/Short/Char/Boolean/Int/Long/Reference}，条件 CAS 语义完整；
	 * 而 JDK 9+ 的 {@code sun.misc.Unsafe} 只剩 {@code compareAndSwapInt/Long/Object}（且无子字 CAS）。
	 * JDK 8 / Android 没有该类，两者为 null，退回 {@code sun.misc.Unsafe} 并对子字做模拟。
	 */
	private static final Class<?> INTERNAL_UNSAFE_CLASS;
	private static final Object   INTERNAL_UNSAFE;

	static {
		Class<?> c = null;
		Object   o = null;
		try {
			c = Class.forName("jdk.internal.misc.Unsafe");
			o = IMPL_LOOKUP.findStatic(c, "getUnsafe", MethodType.methodType(c)).invoke();
		} catch (Throwable ignored) {
			c = null;
			o = null;
		}
		INTERNAL_UNSAFE_CLASS = c;
		INTERNAL_UNSAFE = o;
	}

	private static final boolean BIG_ENDIAN = java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.BIG_ENDIAN;

	// ==================== 条件 CAS 的写入/跳过统计 ====================

	/**
	 * {@code "owner#field"} -> {@code [written, skipped]}（{@code LongAdder} 抗竞争）。
	 *
	 * <p>存在的理由：条件 CAS 的 boolean 结果原本被丢弃，"字段已被别人写过所以补丁没生效"
	 * 与"补丁生效"在驱动侧完全无法区分 —— 跳过是<b>静默</b>的。计数让 {@code InitFix}
	 * 能在补丁结束后把跳过数汇总进告警。</p>
	 *
	 * <p>用 {@code ConcurrentHashMap} + {@code LongAdder}：写入发生在被补丁线程上
	 * （多线程/多实例），读清零发生在补丁线程。增长在 {@link #drainConditionalStats} 时移除空条目。</p>
	 */
	private static final ConcurrentHashMap<String, LongAdder[]> CONDITIONAL_STATS =
	 new ConcurrentHashMap<>();

	/**
	 * 统计句柄：{@code (LongAdder[], boolean)void}。
	 * 用 {@code filterReturnValue} 接在条件 CAS 之后，把 boolean 送进计数器。
	 */
	private static final MethodHandle RECORD_CONDITIONAL;

	static {
		try {
			RECORD_CONDITIONAL = MethodHandles.lookup().findStatic(
			 HotswapBridge.class, "recordConditional",
			 MethodType.methodType(void.class, LongAdder[].class, boolean.class));
		} catch (Throwable t) {
			throw new ExceptionInInitializerError(t);
		}
	}

	private static void recordConditional(LongAdder[] counters, boolean written) {
		counters[written ? 0 : 1].increment();
	}

	/** 取出（或创建）某字段的计数器；{@code owner#field} 为键。 */
	private static LongAdder[] countersFor(Class<?> owner, String fieldName) {
		return CONDITIONAL_STATS.computeIfAbsent(owner.getName() + "#" + fieldName,
		 k -> new LongAdder[]{new LongAdder(), new LongAdder()});
	}

	/**
	 * 读取并清零全部条件 CAS 统计（补丁结束时调用一次）。
	 *
	 * @return {@code "owner#field"} -> {@code [written, skipped]}；无数据时返回空 Map。
	 *         清零后条目被移除：下一轮重新开始计数，不会把历史累计混进本轮告警。
	 */
	public static Map<String, long[]> drainConditionalStats() {
		Map<String, long[]> out = new LinkedHashMap<>();
		for (Map.Entry<String, LongAdder[]> e : CONDITIONAL_STATS.entrySet()) {
			LongAdder[] c = CONDITIONAL_STATS.remove(e.getKey());
			if (c == null) continue;
			long written = c[0].sum();
			long skipped = c[1].sum();
			if (written == 0 && skipped == 0) continue;
			out.put(e.getKey(), new long[]{written, skipped});
		}
		return out;
	}

	/**
	 * valueClass -> MethodHandle {@code (Object, long, V)boolean}。
	 * <p>键：引用类型统一用 {@link Object Object.class}；基本类型用各自的 {@code Class}。</p>
	 *
	 * <p><b>保留 boolean 返回值</b>：条件 CAS 的成败必须能被上层区分 ——
	 * "字段已被别人写过所以跳过"与"补丁生效"是完全不同的事实，丢弃它就等于
	 * 对用户隐瞒了跳过。<b>唯一例外是 float/double 以外的无 CAS 回退</b>
	 * （JDK 8 的子字类型走模拟、以及任何落到 volatile put 的情形）：
	 * 那些路径没有条件语义可报告，统一返回 {@code true}（"已写入"）。</p>
	 */
	private static final Map<Class<?>, MethodHandle> CONDITIONAL_PUTTERS;

	/**
	 * valueClass -> MethodHandle {@code (Object, long, V)void}，无条件 volatile 写。
	 * <p>供 {@link #KIND_FORCE} 使用。这些方法在 {@code sun.misc.Unsafe} 与
	 * {@code jdk.internal.misc.Unsafe} 上都存在且命名一致（不存在 JDK 版本差异），
	 * 不需要像条件 CAS 那样维护 fallback 链。</p>
	 */
	private static final Map<Class<?>, MethodHandle> FORCE_PUTTERS;

	static {
		Map<Class<?>, MethodHandle> m = new HashMap<>(16);
		try {
			m.put(Object.class, volatilePutter(
			 new String[]{"putReferenceVolatile", "putObjectVolatile"}, Object.class));
			m.put(boolean.class, volatilePutter(new String[]{"putBooleanVolatile"}, boolean.class));
			m.put(byte.class, volatilePutter(new String[]{"putByteVolatile"}, byte.class));
			m.put(char.class, volatilePutter(new String[]{"putCharVolatile"}, char.class));
			m.put(short.class, volatilePutter(new String[]{"putShortVolatile"}, short.class));
			m.put(int.class, volatilePutter(new String[]{"putIntVolatile"}, int.class));
			m.put(long.class, volatilePutter(new String[]{"putLongVolatile"}, long.class));
			m.put(float.class, volatilePutter(new String[]{"putFloatVolatile"}, float.class));
			m.put(double.class, volatilePutter(new String[]{"putDoubleVolatile"}, double.class));
		} catch (Throwable t) {
			throw new ExceptionInInitializerError(t);
		}
		FORCE_PUTTERS = Collections.unmodifiableMap(m);
	}

	/**
	 * 组合无条件写句柄：依次尝试 {@code names} 中第一个存在的方法，得到
	 * {@code (Object, long, V)void}。都不存在则抛异常，让类初始化失败
	 * （"JDK 版本超出预期"应该大声暴露，而不是退化成静默不写）。
	 */
	private static MethodHandle volatilePutter(String[] names, Class<?> valueClass)
	 throws NoSuchMethodException {
		MethodType type = MethodType.methodType(void.class, Object.class, long.class, valueClass);
		for (String name : names) {
			MethodHandle put = tryFind(name, type);
			if (put != null) return put;
		}
		throw new NoSuchMethodException(
		 "no volatile put found for " + valueClass + " (tried " + Arrays.toString(names) + ")");
	}

	static {
		Map<Class<?>, MethodHandle> m = new HashMap<>(16);
		try {
			// 引用类型：JDK 12+ (Reference) -> JDK 9~11 (Object) -> JDK 8 (compareAndSwapObject)
			m.put(Object.class, conditional(
			 new String[]{"compareAndSetReference", "compareAndSetObject", "compareAndSwapObject"},
			 new String[]{"putReferenceVolatile", "putObjectVolatile"},
			 Object.class, null));

			// 子字类型：JDK 9+ 内部 Unsafe 原生支持；JDK 8 / Android 用 int CAS 模拟
			m.put(boolean.class, conditional(
			 new String[]{"compareAndSetBoolean"}, new String[]{"putBooleanVolatile"},
			 boolean.class, false));
			m.put(byte.class, conditional(
			 new String[]{"compareAndSetByte"}, new String[]{"putByteVolatile"},
			 byte.class, (byte) 0));
			m.put(char.class, conditional(
			 new String[]{"compareAndSetChar"}, new String[]{"putCharVolatile"},
			 char.class, '\u0000'));
			m.put(short.class, conditional(
			 new String[]{"compareAndSetShort"}, new String[]{"putShortVolatile"},
			 short.class, (short) 0));

			// int / long：JDK 9+ 叫 compareAndSetXxx，JDK 8 / sun.misc 叫 compareAndSwapXxx
			m.put(int.class, conditional(
			 new String[]{"compareAndSetInt", "compareAndSwapInt"}, new String[]{"putIntVolatile"},
			 int.class, 0));
			m.put(long.class, conditional(
			 new String[]{"compareAndSetLong", "compareAndSwapLong"}, new String[]{"putLongVolatile"},
			 long.class, 0L));

			// float/double：一律用 raw bits 走 int/long CAS，不依赖
			// compareAndSetFloat/Double 是否存在（JDK 8 没有；见类注释）。
			// 这样 JDK 8 与 JDK 9+ 语义一致，且与 T0 的按位判零口径对齐。
			m.put(float.class, rawBitsConditional(float.class));
			m.put(double.class, rawBitsConditional(double.class));
		} catch (Throwable t) {
			throw new ExceptionInInitializerError(t);
		}
		CONDITIONAL_PUTTERS = Collections.unmodifiableMap(m);
	}

	/**
	 * float/double 的条件 CAS：在 raw bits（int/long）上做比较-交换，
	 * 再把句柄签名适配回 {@code (Object, long, V)void}。
	 *
	 * <p>为什么不用 {@code compareAndSetFloat/Double}：JDK 8 的 {@code sun.misc.Unsafe}
	 * 没有这两个方法（实测 1.8.0_332 absent），按名字探测会静默退化成无条件 volatile 写，
	 * 覆盖其他线程已写入的值。raw bits 在所有目标 JDK 上都可用（{@code compareAndSwapInt/Long}
	 * 从 JDK 8 起就有），语义一致。</p>
	 *
	 * <p><b>按位判零</b>：expected 取 {@code floatToRawIntBits(0.0f) == 0} / {@code doubleToRawLongBits(0.0d) == 0L}，
	 * 与 §4.1 T0 的判零口径一致。于是 {@code -0.0f}（位模式 {@code 0x80000000}）与
	 * 各类 NaN 都被视为"字段已有值"，条件 CAS 失败、补丁跳过 —— 这正是保守方向所需。</p>
	 *
	 * <p>句柄组合：{@code casInt(Object,long,int,int)boolean}
	 * --insertArguments(expected=0)--> {@code (Object,long,int)boolean}
	 * --filterArguments(floatToRawIntBits)--> {@code (Object,long,float)boolean}。</p>
	 */
	private static MethodHandle rawBitsConditional(Class<?> valueClass) throws Throwable {
		boolean isFloat = valueClass == float.class;
		Class<?> bitsClass = isFloat ? int.class : long.class;

		MethodType casType = MethodType.methodType(
		 boolean.class, Object.class, long.class, bitsClass, bitsClass);
		MethodHandle cas = tryFind("compareAndSet" + (isFloat ? "Int" : "Long"), casType);
		if (cas == null) {
			cas = tryFind("compareAndSwap" + (isFloat ? "Int" : "Long"), casType);
		}
		if (cas == null) {
			throw new NoSuchMethodException(
			 "no " + bitsClass.getName() + " CAS for raw-bits " + valueClass.getName());
		}

		// expected = 零值的 raw bits（两者都是 0）
		cas = MethodHandles.insertArguments(cas, 2, isFloat ? (Object) 0 : (Object) 0L);

		// 把传入的 float/double 参数转成 raw bits
		MethodHandle toBits = MethodHandles.lookup().findStatic(
		 isFloat ? Float.class : Double.class,
		 isFloat ? "floatToRawIntBits" : "doubleToRawLongBits",
		 MethodType.methodType(bitsClass, valueClass));
		return MethodHandles.filterArguments(cas, 2, toBits);
	}

	/**
	 * 组合条件写模板，依次尝试：
	 * <ol>
	 *   <li>{@code casNames} 中第一个存在的真 CAS（先查内部 Unsafe，再查 sun.misc.Unsafe）；
	 *       得到 {@code (Object, long, V)boolean} 的条件 CAS。<b>必须先于 put 回退</b>，
	 *       否则找不到 {@code compareAndSetXxx} 就会误降级成无条件写。</li>
	 *   <li>boolean/byte/char/short：用同字 int CAS 模拟（JDK 8 / Android）。</li>
	 *   <li>{@code putNames} 中第一个存在的 volatile put——无条件写（仅无 CAS 的类型会走到这里）。</li>
	 *   <li>都没有则抛异常，让类初始化失败——"JDK 版本超出预期"应该大声暴露。</li>
	 * </ol>
	 */
	private static MethodHandle conditional(
	 String[] casNames, String[] putNames, Class<?> valueClass, Object expected
	) throws Throwable {
		MethodType casType = MethodType.methodType(
		 boolean.class, Object.class, long.class, valueClass, valueClass);
		for (String casName : casNames) {
			MethodHandle cas = tryFind(casName, casType);
			if (cas != null) {
				// 保留 boolean：调用点要用它统计"因已有值而跳过"
				return MethodHandles.insertArguments(cas, 2, expected);
			}
		}

		MethodHandle emulated = emulatedSubWordCas(valueClass, expected);
		if (emulated != null) return emulated;

		MethodType putType = MethodType.methodType(
		 void.class, Object.class, long.class, valueClass);
		for (String putName : putNames) {
			MethodHandle put = tryFind(putName, putType);
			if (put != null) {
				// 无条件写没有条件语义可报告：包成恒 true 的 (Object,long,V)boolean，
				// 让上层统一按 boolean 处理（此处"写入成功"是事实）。
				return MethodHandles.dropArguments(
				 MethodHandles.insertArguments(
				  MethodHandles.constant(boolean.class, true), 0),
				 0, Object.class, long.class, valueClass);
			}
		}
		throw new NoSuchMethodException(
		 "no CAS or volatile put found for " + valueClass
		 + " (cas=" + Arrays.toString(casNames) + ", put=" + Arrays.toString(putNames) + ")");
	}

	/** 返回已绑定 Unsafe 接收者的句柄；先查 JDK 9+ 内部 Unsafe，再查 sun.misc.Unsafe。 */
	private static MethodHandle tryFind(String name, MethodType type) {
		if (INTERNAL_UNSAFE != null) {
			try {
				return IMPL_LOOKUP.findVirtual(INTERNAL_UNSAFE_CLASS, name, type).bindTo(INTERNAL_UNSAFE);
			} catch (NoSuchMethodException | IllegalAccessException ignored) { }
		}
		try {
			return IMPL_LOOKUP.findVirtual(Unsafe.class, name, type).bindTo(UNSAFE);
		} catch (NoSuchMethodException | IllegalAccessException e) {
			return null;
		}
	}

	// ==================== 子字 CAS 模拟（仅 JDK 8 / Android） ====================

	private static MethodHandle emulatedSubWordCas(Class<?> valueClass, Object expected) throws Throwable {
		String name;
		if (valueClass == boolean.class) { name = "casBoolean"; } else if (valueClass == byte.class) {
			name = "casByte";
		} else if (valueClass == char.class) {
			name = "casChar";
		} else if (valueClass == short.class) {
			name = "casShort";
		} else return null;
		MethodHandle h = MethodHandles.lookup().findStatic(HotswapBridge.class, name,
		 MethodType.methodType(boolean.class, Object.class, long.class, valueClass, valueClass));
		// 保留 boolean：调用点要统计"因已有值而跳过"
		return MethodHandles.insertArguments(h, 2, expected);
	}

	private static boolean casBoolean(Object o, long off, boolean e, boolean x) {
		return casSubWord(o, off, 1, e ? 1 : 0, x ? 1 : 0);
	}

	private static boolean casByte(Object o, long off, byte e, byte x) {
		return casSubWord(o, off, 1, e, x);
	}

	private static boolean casChar(Object o, long off, char e, char x) {
		return casSubWord(o, off, 2, e, x);
	}

	private static boolean casShort(Object o, long off, short e, short x) {
		return casSubWord(o, off, 2, e, x);
	}

	/**
	 * 在包含该子字的 4 字节对齐字上做 int CAS：读整字 -> 比较目标子字 -> 替换子字位 -> CAS 整字，
	 * 其他字节的并发写入会令 CAS 失败并重试，不会被覆盖。字段偏移必然落在对象体内，
	 * 向下对齐到 4 字节不会越过对象头。
	 */
	private static boolean casSubWord(Object o, long offset, int bytes, int expected, int x) {
		long wordOffset = offset & ~3L;
		int  shift      = (int) (offset & 3L) << 3;
		if (BIG_ENDIAN) shift = 32 - (bytes << 3) - shift;
		int valueMask = (1 << (bytes << 3)) - 1;
		int e         = expected & valueMask;
		int v         = (x & valueMask) << shift;
		int clear     = ~(valueMask << shift);
		for (; ; ) {
			int cur = UNSAFE.getIntVolatile(o, wordOffset);
			if (((cur >>> shift) & valueMask) != e) return false;
			if (UNSAFE.compareAndSwapInt(o, wordOffset, cur, (cur & clear) | v)) return true;
		}
	}

	private HotswapBridge() { }

	public static CallSite bootstrap(
	 Lookup caller,
	 String name,
	 MethodType callSiteType,
	 int kind,
	 int opcode,
	 Class<?> owner,
	 Class<?> host
	) {
		try {
			return switch (kind) {
				case KIND_PROTECTED -> protectedCallSite(caller, name, callSiteType, opcode, owner, host);
				case KIND_CONDITIONAL -> conditionalFieldCallSite(caller, name, callSiteType, opcode, owner);
				case KIND_FORCE -> forceFieldCallSite(caller, name, callSiteType, opcode, owner);
				default -> throw new IllegalArgumentException("unknown bridge kind: " + kind);
			};
		} catch (BootstrapMethodError bme) {
			throw bme;
		} catch (Throwable t) {
			throw new BootstrapMethodError(
			 "Cannot link bridge for " + owner.getName() + "." + name
			 + " (kind=" + kind + ", opcode=" + opcode + ")", t);
		}
	}

	// ==================== 字段条件 CAS 写 ====================

	private static CallSite conditionalFieldCallSite(
	 Lookup caller, String fieldName, MethodType callSiteType, int opcode, Class<?> owner
	) throws Throwable {
		boolean isStatic = opcode == Opcodes.PUTSTATIC;

		// 从 callSiteType 派生字段类型：静态在参数 0，实例在参数 1
		Class<?> valType = callSiteType.parameterType(isStatic ? 0 : 1);
		Class<?> key     = valType.isPrimitive() ? valType : Object.class;

		MethodHandle template = CONDITIONAL_PUTTERS.get(key);
		if (template == null) {
			throw new IllegalStateException("no putter for " + valType);
		}

		Field field = findField(owner, fieldName, Type.getDescriptor(valType));
		long offset = isStatic
		 ? UNSAFE.staticFieldOffset(field)
		 : UNSAFE.objectFieldOffset(field);
		Object base = isStatic ? UNSAFE.staticFieldBase(field) : null;

		MethodHandle writer = MethodHandles.insertArguments(template, 1, offset);
		if (isStatic) {
			writer = MethodHandles.insertArguments(writer, 0, base);
		}

		// 接上计数器：把条件 CAS 的 boolean 结果记进 [written, skipped]。
		// 这是"跳过"唯一的出口 —— 不接的话，字段因已有值而未补的事实对用户完全不可见。
		LongAdder[] counters = countersFor(owner, fieldName);
		writer = MethodHandles.filterReturnValue(writer,
		 MethodHandles.insertArguments(RECORD_CONDITIONAL, 0, (Object) counters));

		return new ConstantCallSite(writer.asType(callSiteType));
	}

	// ==================== 字段强制写（@HotswapReinit OVERWRITE） ====================

	/**
	 * {@link #KIND_FORCE} 的调用点：与 {@link #conditionalFieldCallSite} 同构，
	 * 只是换成无条件 volatile 写（因此 final 字段也能写）。
	 */
	private static CallSite forceFieldCallSite(
	 Lookup caller, String fieldName, MethodType callSiteType, int opcode, Class<?> owner
	) throws Throwable {
		boolean isStatic = opcode == Opcodes.PUTSTATIC;

		Class<?> valType = callSiteType.parameterType(isStatic ? 0 : 1);
		Class<?> key     = valType.isPrimitive() ? valType : Object.class;

		MethodHandle template = FORCE_PUTTERS.get(key);
		if (template == null) {
			throw new IllegalStateException("no force putter for " + valType);
		}

		Field field = findField(owner, fieldName, Type.getDescriptor(valType));
		long offset = isStatic
		 ? UNSAFE.staticFieldOffset(field)
		 : UNSAFE.objectFieldOffset(field);
		Object base = isStatic ? UNSAFE.staticFieldBase(field) : null;

		MethodHandle writer = MethodHandles.insertArguments(template, 1, offset);
		if (isStatic) {
			writer = MethodHandles.insertArguments(writer, 0, base);
		}
		return new ConstantCallSite(writer.asType(callSiteType));
	}

	/**
	 * 沿 owner -> 父类链逐层 {@code getDeclaredFields()} 查找。
	 * 热更注入的字段就在 owner 上，第一次迭代即命中。
	 */
	private static Field findField(Class<?> owner, String name, String desc)
	 throws NoSuchFieldException {
		for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
			for (Field f : c.getDeclaredFields()) {
				if (name.equals(f.getName()) && Type.getDescriptor(f.getType()).equals(desc)) {
					return f;
				}
			}
		}
		throw new NoSuchFieldException(owner.getName() + "." + name + ":" + desc);
	}

	// ==================== protected 成员桥接 ====================

	private static CallSite protectedCallSite(
	 Lookup caller, String name, MethodType callSiteType, int opcode,
	 Class<?> owner, Class<?> host
	) throws Throwable {
		Lookup hostLookup = Reflect.isAndroid ? caller : IMPL_LOOKUP;

		MethodHandle mh = switch (opcode) {
			case Opcodes.GETFIELD -> hostLookup.findGetter(owner, name, callSiteType.returnType());
			case Opcodes.GETSTATIC -> hostLookup.findStaticGetter(owner, name, callSiteType.returnType());
			case Opcodes.PUTFIELD -> {
				Class<?> t = callSiteType.parameterType(callSiteType.parameterCount() - 1);
				yield hostLookup.findSetter(owner, name, t);
			}
			case Opcodes.PUTSTATIC -> {
				Class<?> t = callSiteType.parameterType(0);
				yield hostLookup.findStaticSetter(owner, name, t);
			}
			case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE ->
			 hostLookup.findVirtual(owner, name, callSiteType.dropParameterTypes(0, 1));
			case Opcodes.INVOKESTATIC -> hostLookup.findStatic(owner, name, callSiteType);
			case Opcodes.INVOKESPECIAL -> hostLookup.findSpecial(owner, name,
			 callSiteType.dropParameterTypes(0, 1), host);
			default -> throw new IllegalArgumentException(
			 "bad opcode for protected bridge: " + opcode);
		};

		return new ConstantCallSite(mh.asType(callSiteType));
	}
}