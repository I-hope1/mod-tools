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
 * <p><b>用途二（{@link #KIND_FINAL}）</b>：补丁类不是宿主的 &lt;init&gt;/&lt;clinit&gt;，
 * 直接写 final 字段会抛 {@code IllegalAccessError}；nestmate 权限也不放宽 final 语义。
 * 用 {@link Unsafe} 直接写内存，引用类型走 {@code putReferenceVolatile}/{@code putObjectVolatile}
 * （JDK 版本分流），基本类型走 {@code putXxxVolatile}，与 JMM volatile 语义一致。</p>
 *
 * <p><b>bsmArgs 布局</b>：{@code [int kind, int opcode, Class owner, Class host]}。
 * <ul>
 *   <li>{@code KIND_PROTECTED}：{@code owner} = 成员声明类，{@code host} = 宿主类。
 *       indy 的 {@code name} 是字段/方法名，{@code callSiteType} 携带描述符。</li>
 *   <li>{@code KIND_FINAL}：{@code owner} = 宿主类，{@code host} = 宿主类（占位）。
 *       字段类型从 {@code callSiteType} 直接派生：静态字段在参数 0，实例字段在参数 1。
 *       不需要额外的 {@code String desc} 参数。</li>
 * </ul>
 *
 * <p><b>volatile 写入模板预缓存</b>：{@code Unsafe} 的 9 个 {@code putXxxVolatile}
 * 在静态块里一次解析，模板形式 {@code (Object, long, V)void}（{@code UNSAFE} 已 bind）。
 * BSM 每次调用点链接时只做 {@code insertArguments}，不再走反射解析。</p>
 */
public final class HotswapBridge {

	/** protected 成员桥接：owner 是声明类，host 是宿主类。 */
	public static final int KIND_PROTECTED = 0;

	/** final 字段的 Unsafe volatile 写入：owner 是宿主类。 */
	public static final int KIND_FINAL = 1;

	private static final Unsafe UNSAFE      = Unsafe.getUnsafe();
	private static final Lookup IMPL_LOOKUP = Reflect.IMPL_LOOKUP;

	/**
	 * valueClass -> MethodHandle {@code (Object, long, V)void}。
	 * <p>键：引用类型统一用 {@link Object#getClass Object.class}；基本类型用各自的 {@code Class}。</p>
	 * <p>值：已 {@code bindTo(UNSAFE)}，剩余参数是 {@code (receiver, offset, value)}。</p>
	 */
	private static final Map<Class<?>, MethodHandle> VOLATILE_PUTTERS;

	static {
		Map<Class<?>, MethodHandle> m = new HashMap<>(16);
		try {
			// String refName = Reflect.version >= 12
			//     ? "putReferenceVolatile"
			//     : "putObjectVolatile";
			// putTemplate(m, Object.class,  refName);
			// 更稳健的探测方式：
			MethodHandle refHandle;
			MethodType   refType = MethodType.methodType(void.class, Object.class, long.class, Object.class);
			try {
				refHandle = IMPL_LOOKUP.findVirtual(Unsafe.class, "putReferenceVolatile", refType);
			} catch (NoSuchMethodException e) {
				refHandle = IMPL_LOOKUP.findVirtual(Unsafe.class, "putObjectVolatile", refType);
			}
			m.put(Object.class, refHandle.bindTo(UNSAFE));
			putTemplate(m, boolean.class, "putBooleanVolatile");
			putTemplate(m, byte.class, "putByteVolatile");
			putTemplate(m, char.class, "putCharVolatile");
			putTemplate(m, short.class, "putShortVolatile");
			putTemplate(m, int.class, "putIntVolatile");
			putTemplate(m, long.class, "putLongVolatile");
			putTemplate(m, float.class, "putFloatVolatile");
			putTemplate(m, double.class, "putDoubleVolatile");
		} catch (Throwable t) {
			throw new ExceptionInInitializerError(t);
		}
		VOLATILE_PUTTERS = new HashMap<>(m);
	}

	private static void putTemplate(
	 Map<Class<?>, MethodHandle> out,
	 Class<?> valueClass, String unsafeName
	) throws Throwable {
		MethodType type = MethodType.methodType(
		 void.class, Object.class, long.class, valueClass);
		MethodHandle h = IMPL_LOOKUP.findVirtual(Unsafe.class, unsafeName, type);
		// 绑定 UNSAFE 单例，剩下 (Object, long, V)void
		out.put(valueClass, h.bindTo(UNSAFE));
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
				case KIND_PROTECTED -> protectedCallSite(name, callSiteType, opcode, owner, host);
				case KIND_FINAL -> finalCallSite(name, callSiteType, opcode, owner);
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

	// ==================== final 字段：Unsafe volatile 写 ====================

	private static CallSite finalCallSite(
	 String fieldName, MethodType callSiteType, int opcode, Class<?> owner
	) throws Throwable {
		boolean isStatic = opcode == Opcodes.PUTSTATIC;

		// 从 callSiteType 派生字段类型：静态在参数 0，实例在参数 1
		Class<?> valType = callSiteType.parameterType(isStatic ? 0 : 1);
		Class<?> key     = valType.isPrimitive() ? valType : Object.class;

		MethodHandle template = VOLATILE_PUTTERS.get(key);
		if (template == null) {
			throw new IllegalStateException("no volatile putter for " + valType);
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
	 * 热更注入的 final 字段就在 owner 上，第一次迭代即命中，O(1)。
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