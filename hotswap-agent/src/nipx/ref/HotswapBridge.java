package nipx.ref;

import jdk.internal.misc.Unsafe;
import nipx.Reflect;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

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
 * <p><b>方法名分布（对照 {@code jdk.internal.misc.Unsafe}）</b>：</p>
 * <ul>
 *   <li><b>引用</b>：JDK 9~11 是 {@code compareAndSetObject}/{@code putObjectVolatile}；
 *       JDK 12+ 通过 JDK-8207146 改为 {@code compareAndSetReference}/{@code putReferenceVolatile}，
 *       同时 {@code compareAndSetObject} 作为 @Deprecated 别名保留；
 *       JDK 23+ 通过 JDK-8327729 移除别名。用 {@link Reflect#version} 分流。</li>
 *   <li><b>boolean/byte/char/short/int/long</b>：JDK 9 引入 VarHandle 时就叫
 *       {@code compareAndSetXxx}。没有 {@code compareAndSwapXxx} 的旧名——
 *       那是 {@code sun.misc.Unsafe} 的名字。</li>
 *   <li><b>float/double</b>：{@code jdk.internal.misc.Unsafe} 至今没有
 *       {@code compareAndSetFloat}/{@code compareAndSetDouble}。硬件 {@code cmpxchg}
 *       只作用于整型，JDK 官方（如 VarHandle）用 raw bits + {@code compareAndSetInt/Long}
 *       实现浮点 CAS。本类不做位转换，直接降级为 {@code putFloatVolatile}/
 *       {@code putDoubleVolatile} 无条件写——丢掉条件语义，但保持 volatile 可见性。
 *       float/double 字段极少是 final，影响面很小。</li>
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
public final class HotswapBridge {

    /** protected 成员桥接：owner 是声明类，host 是宿主类。 */
    public static final int KIND_PROTECTED = 0;

    /** 新增字段的条件 CAS 写：owner 是宿主类。 */
    public static final int KIND_CONDITIONAL = 1;

    private static final Unsafe UNSAFE      = Unsafe.getUnsafe();
    private static final Lookup IMPL_LOOKUP = Reflect.IMPL_LOOKUP;

    /**
     * valueClass -> MethodHandle {@code (Object, long, V)void}。
     * <p>键：引用类型统一用 {@link Object Object.class}；基本类型用各自的 {@code Class}。</p>
     * <p>值：优先条件 CAS（expected 已固定为默认值、返回值已 drop）；
     * {@code Unsafe} 缺对应 CAS 的类型（float/double）降级为无条件 volatile 写。
     * 两种形态的剩余签名一致，都是 {@code (receiver, offset, value)}。</p>
     */
    private static final Map<Class<?>, MethodHandle> CONDITIONAL_PUTTERS;

    static {
        Map<Class<?>, MethodHandle> m = new HashMap<>(16);
        try {
            // 引用类型：优先使用 JDK 12+ 的 Reference 名字，不存在则回退到 JDK 11- 的 Object 名字
            MethodHandle refHandle = null;
            try {
                refHandle = conditional(
                    "compareAndSetReference", "putReferenceVolatile", Object.class, null);
            } catch (Throwable ignored) { }
            if (refHandle == null) {
                refHandle = conditional(
                    "compareAndSetObject", "putObjectVolatile", Object.class, null);
            }
            m.put(Object.class, refHandle);

            // 6 种整型/布尔：JDK 9 起就叫 compareAndSetXxx，无需版本分流
            m.put(boolean.class, conditional("compareAndSetBoolean", "putBooleanVolatile",
                                             boolean.class, false));
            m.put(byte.class,    conditional("compareAndSetByte",    "putByteVolatile",
                                             byte.class,    (byte) 0));
            m.put(char.class,    conditional("compareAndSetChar",    "putCharVolatile",
                                             char.class,    '\u0000'));
            m.put(short.class,   conditional("compareAndSetShort",   "putShortVolatile",
                                             short.class,   (short) 0));
            m.put(int.class,     conditional("compareAndSetInt",     "putIntVolatile",
                                             int.class,     0));
            m.put(long.class,    conditional("compareAndSetLong",    "putLongVolatile",
                                             long.class,    0L));

            // float/double：Unsafe 没有 compareAndSetFloat/Double，
            // tryFind 返回 null，自动降级为无条件 volatile 写。
            m.put(float.class,   conditional("compareAndSetFloat",   "putFloatVolatile",
                                             float.class,   0.0f));
            m.put(double.class,  conditional("compareAndSetDouble",  "putDoubleVolatile",
                                             double.class,  0.0d));
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
        CONDITIONAL_PUTTERS = Map.copyOf(m);
    }

    /**
     * 组合条件写模板：
     * <ol>
     *   <li>找 {@code casName}；找到就
     *       {@code bindTo(UNSAFE) + insertArguments(2, expected) + dropReturn}，
     *       得到 {@code (Object, long, V)void} 的条件 CAS。</li>
     *   <li>找不到就找 {@code putName}，{@code bindTo(UNSAFE)} 后形态一致，但无条件写。</li>
     *   <li>都没有则抛异常，让类初始化失败——"JDK 版本超出预期"应该大声暴露。</li>
     * </ol>
     */
    private static MethodHandle conditional(
        String casName, String putName,
        Class<?> valueClass, Object expected
    ) throws Throwable {
        MethodType casType = MethodType.methodType(
            boolean.class, Object.class, long.class, valueClass, valueClass);
        MethodHandle cas = tryFind(casName, casType);
        if (cas != null) {
            MethodHandle bound = cas.bindTo(UNSAFE);
            bound = MethodHandles.insertArguments(bound, 2, expected);
            return MethodHandles.dropReturn(bound);
        }

        MethodType putType = MethodType.methodType(
            void.class, Object.class, long.class, valueClass);
        MethodHandle put = tryFind(putName, putType);
        if (put == null) {
            throw new NoSuchMethodException(
                "no CAS or volatile put found for " + valueClass
                + " (cas=" + casName + ", put=" + putName + ")");
        }
        return put.bindTo(UNSAFE);
    }

    private static MethodHandle tryFind(String name, MethodType type) {
        try {
            return IMPL_LOOKUP.findVirtual(Unsafe.class, name, type);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            return null;
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
                case KIND_PROTECTED   -> protectedCallSite(name, callSiteType, opcode, owner, host);
                case KIND_CONDITIONAL -> conditionalFieldCallSite(name, callSiteType, opcode, owner);
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
        String fieldName, MethodType callSiteType, int opcode, Class<?> owner
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
        String name, MethodType callSiteType, int opcode,
        Class<?> owner, Class<?> host
    ) throws Throwable {
        Lookup hostLookup = MethodHandles.privateLookupIn(host, IMPL_LOOKUP);

        MethodHandle mh = switch (opcode) {
            case Opcodes.GETFIELD ->
                hostLookup.findGetter(owner, name, callSiteType.returnType());
            case Opcodes.GETSTATIC ->
                hostLookup.findStaticGetter(owner, name, callSiteType.returnType());
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
            case Opcodes.INVOKESTATIC ->
                hostLookup.findStatic(owner, name, callSiteType);
            case Opcodes.INVOKESPECIAL ->
                hostLookup.findSpecial(owner, name,
                    callSiteType.dropParameterTypes(0, 1), host);
            default -> throw new IllegalArgumentException(
                "bad opcode for protected bridge: " + opcode);
        };

        return new ConstantCallSite(mh.asType(callSiteType));
    }
}