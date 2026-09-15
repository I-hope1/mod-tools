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
 * 支持多方案并存与可插拔的特权 JIT 存根生成器 (参考 AccessorProcessor 架构)。
 * 支持 AccessMode:
 * <ul>
 *   <li><b>AUTO:</b> 自动智能探测并采用最佳方案。</li>
 *   <li><b>UNSAFE_AND_METHODHANDLE:</b> 基于 DirectMethodHandle 纯标准直调 (零动态类生成, 跨平台及Android通用)。</li>
 *   <li><b>UNSAFE_AND_LINKTO:</b> 基于 HotSpot 原生 {@code linkTo*} 指令与 MemberName 绑定直调。
 *       <p><b>核心架构优势：</b></p>
 *       <ol>
 *         <li><b>极低 C2 JIT 内联预算消耗 (Inlining Budget)：</b>标准 MethodHandle 调用链繁复，经历多层 LambdaForm
 *             及适配器，极易吃满 HotSpot C2 的内联预算上限（默认 MaxInlineLevel=9, MaxInlineSize=35），导致业务方法被内联截断。
 *             linkTo 方案由 Invoker 动态类直接 {@code invokestatic} 桥接方法，桥接方法内直接下发 JVM 机器级 {@code linkTo*} 原语并尾随 MemberName 常量，
 *             调用图极其扁平（仅 1~2 层），几乎不消耗 C2 预算，将宝贵的内联额度全部留给业务逻辑。</li>
 *         <li><b>规避同构签名引发的递归与深度检测内联截断：</b>C2 对签名相同的调用链（如通用 Object[] 签名或多层通用 LambdaForm）
 *             具有严格的递归检测上限（MaxRecursiveInlineLevel 默认仅为 1）。linkTo 方案结合针对 arity 0~3 特化生成的专属强类型入参方法
 *             （{@code invoke0~3}、{@code newInstance0~3}），彻底摆脱了泛型同构包装，根绝了 C2 的同构方法递归内联截断。</li>
 *         <li><b>零 MethodHandle 对象头与 LambdaForm 元空间膨胀：</b>传统 MH 组合在堆上分配大量包装器并向 Metaspace 注入大量匿名类；
 *             linkTo 桥接结构极简（单静态方法 + 静态 {@code @Stable MemberName}），GC 与元空间零额外负担。</li>
 *         <li><b>杜绝运行期动态类型校验 (Polymorphic Type-Pollution Free)：</b>避免 {@code invokeExact} 的动态 MethodType 比较开销与去优化风险，
 *             字节码静态校验直通底层。</li>
 *         <li><b>穿透访问权限壁垒：</b>底层 {@code linkToSpecial / linkToStatic / linkToVirtual} 原语直接操作方法指针，突破 private 权限限制。</li>
 *         <li><b>零数组分配与零参数装箱 (Zero-Allocation)：</b>特化方法栈上传参，杜绝通用反射创建 {@code Object[]} 数组的堆分配与 GC 压力。</li>
 *       </ol>
 *   </li>
 *   <li><b>MAGIC_ACCESSOR:</b> 经典 ASM 动态生成特权类字节码直调 (JDK &le; 21)。</li>
 * </ul>
 */
public class MagicJIT implements Opcodes {

	public static final String IN_JSOps = "hope/magic/js/runtime/JSOps";

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

	public static AccessMode getMode() {
		return currentMode;
	}

	public static void setMode(AccessMode mode) {
		currentMode = mode == null ? AccessMode.AUTO : mode;
	}

	public static AccessMode getEffectiveMode() {
		AccessMode m = currentMode;
		if (m == AccessMode.AUTO) {
			return AccessMode.UNSAFE_AND_METHODHANDLE;
		}
		return m;
	}

	private static final class InvokerLookupKey {
		final AccessMode mode;
		final String  methodName;
		final int     arity;
		final boolean isStatic;
		final int     hash;

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
		final Method method;
		final int hash;

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
		final int arity;
		final int hash;

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

	private static final class ClassJITData {
		final Map<InvokerLookupKey, MagicInvoker> invokerCache = new ConcurrentHashMap<>();
		final Map<CtorLookupKey, MagicConstructorInvoker> ctorCache = new ConcurrentHashMap<>();
		final Map<String, MethodHandle> getterCache = new ConcurrentHashMap<>();
		final Map<String, MethodHandle> setterCache = new ConcurrentHashMap<>();
		final Map<ExactMethodKey, MethodHandle> exactMethodCache = new ConcurrentHashMap<>();
	}

	private static final ClassValue<ClassJITData> JIT_DATA = new ClassValue<>() {
		@Override
		protected ClassJITData computeValue(Class<?> type) {
			return new ClassJITData();
		}
	};

	// 架构优化说明：
	// 原各级反射与 JIT 存根缓存采用 ConcurrentHashMap<Key, ...>。
	// 以 Class<?> 为 Key（或持有 Class<?> 强引用）的全局并发 Map 存在两大缺陷：
	// 1. 强引用动态加载的 Class，阻碍其 ClassLoader 垃圾回收，造成元空间（Metaspace）内存泄漏。
	// 2. 并发哈希表读写存在哈希冲突与分段锁/CAS 竞争开销。
	// 改为 JDK 原生 ClassValue<ClassJITData> 后：
	private static final Object[] EMPTY_ARGS = new Object[0];
	private static final Class<?> MEMBER_NAME_CLASS;
	static {
		Class<?> mnClass = null;
		try {
			mnClass = Class.forName("java.lang.invoke.MemberName");
		} catch (Throwable ignored) {
		}
		MEMBER_NAME_CLASS = mnClass;
	}

	private static final AtomicLong                            COUNTER              = new AtomicLong();
	private static final ClassValue<MethodHandle>              FN_ADAPTER_MH_CACHE  = new ClassValue<>() {
		@Override
		protected MethodHandle computeValue(Class<?> type) {
			return createFunctionAdapterHandle(type);
		}
	};
	private static final ClassValue<MethodHandle>              OBJ_ADAPTER_MH_CACHE = new ClassValue<>() {
		@Override
		protected MethodHandle computeValue(Class<?> type) {
			return createObjectAdapterHandle(type);
		}
	};

	@FunctionalInterface
	public interface MagicInvoker {
		Object invoke(Object target, Object[] args) throws Throwable;

		default Object invoke0(Object target) throws Throwable {
			return invoke(target, EMPTY_ARGS);
		}

		default Object invoke1(Object target, Object a0) throws Throwable {
			return invoke(target, new Object[]{ a0 });
		}

		default Object invoke2(Object target, Object a0, Object a1) throws Throwable {
			return invoke(target, new Object[]{ a0, a1 });
		}

		default Object invoke3(Object target, Object a0, Object a1, Object a2) throws Throwable {
			return invoke(target, new Object[]{ a0, a1, a2 });
		}
	}

	@FunctionalInterface
	public interface MagicConstructorInvoker {
		Object newInstance(Object[] args) throws Throwable;

		default Object newInstance0() throws Throwable {
			return newInstance(EMPTY_ARGS);
		}

		default Object newInstance1(Object a0) throws Throwable {
			return newInstance(new Object[]{ a0 });
		}

		default Object newInstance2(Object a0, Object a1) throws Throwable {
			return newInstance(new Object[]{ a0, a1 });
		}

		default Object newInstance3(Object a0, Object a1, Object a2) throws Throwable {
			return newInstance(new Object[]{ a0, a1, a2 });
		}
	}

	private static final class Arity0Invoker implements MagicInvoker {
		private final MethodHandle mh;
		Arity0Invoker(MethodHandle mh) { this.mh = mh; }
		@Override public Object invoke(Object target, Object[] args) throws Throwable { return mh.invokeExact(target); }
		@Override public Object invoke0(Object target) throws Throwable { return mh.invokeExact(target); }
	}

	private static final class Arity1Invoker implements MagicInvoker {
		private final MethodHandle mh;
		private final MethodHandle spreader;
		Arity1Invoker(MethodHandle mh) { this.mh = mh; this.spreader = mh.asSpreader(Object[].class, 1); }
		@Override public Object invoke(Object target, Object[] args) throws Throwable {
			if (args != null && args.length == 1) return mh.invokeExact(target, args[0]);
			return spreader.invoke(target, args == null ? EMPTY_ARGS : args);
		}
		@Override public Object invoke1(Object target, Object a0) throws Throwable { return mh.invokeExact(target, a0); }
	}

	private static final class Arity2Invoker implements MagicInvoker {
		private final MethodHandle mh;
		private final MethodHandle spreader;
		Arity2Invoker(MethodHandle mh) { this.mh = mh; this.spreader = mh.asSpreader(Object[].class, 2); }
		@Override public Object invoke(Object target, Object[] args) throws Throwable {
			if (args != null && args.length == 2) return mh.invokeExact(target, args[0], args[1]);
			return spreader.invoke(target, args == null ? EMPTY_ARGS : args);
		}
		@Override public Object invoke2(Object target, Object a0, Object a1) throws Throwable { return mh.invokeExact(target, a0, a1); }
	}

	private static final class Arity3Invoker implements MagicInvoker {
		private final MethodHandle mh;
		private final MethodHandle spreader;
		Arity3Invoker(MethodHandle mh) { this.mh = mh; this.spreader = mh.asSpreader(Object[].class, 3); }
		@Override public Object invoke(Object target, Object[] args) throws Throwable {
			if (args != null && args.length == 3) return mh.invokeExact(target, args[0], args[1], args[2]);
			return spreader.invoke(target, args == null ? EMPTY_ARGS : args);
		}
		@Override public Object invoke3(Object target, Object a0, Object a1, Object a2) throws Throwable { return mh.invokeExact(target, a0, a1, a2); }
	}

	private static final class GenericInvoker implements MagicInvoker {
		private final MethodHandle spreader;
		GenericInvoker(MethodHandle spreader) { this.spreader = spreader; }
		@Override public Object invoke(Object target, Object[] args) throws Throwable {
			return spreader.invoke(target, args == null ? EMPTY_ARGS : args);
		}
	}

	private static final class Arity0CtorInvoker implements MagicConstructorInvoker {
		private final MethodHandle mh;
		Arity0CtorInvoker(MethodHandle mh) { this.mh = mh; }
		@Override public Object newInstance(Object[] args) throws Throwable { return mh.invokeExact(); }
		@Override public Object newInstance0() throws Throwable { return mh.invokeExact(); }
	}

	private static final class Arity1CtorInvoker implements MagicConstructorInvoker {
		private final MethodHandle mh;
		private final MethodHandle spreader;
		Arity1CtorInvoker(MethodHandle mh) { this.mh = mh; this.spreader = mh.asSpreader(Object[].class, 1); }
		@Override public Object newInstance(Object[] args) throws Throwable {
			if (args != null && args.length == 1) return mh.invokeExact(args[0]);
			return spreader.invoke(args == null ? EMPTY_ARGS : args);
		}
		@Override public Object newInstance1(Object a0) throws Throwable { return mh.invokeExact(a0); }
	}

	private static final class Arity2CtorInvoker implements MagicConstructorInvoker {
		private final MethodHandle mh;
		private final MethodHandle spreader;
		Arity2CtorInvoker(MethodHandle mh) { this.mh = mh; this.spreader = mh.asSpreader(Object[].class, 2); }
		@Override public Object newInstance(Object[] args) throws Throwable {
			if (args != null && args.length == 2) return mh.invokeExact(args[0], args[1]);
			return spreader.invoke(args == null ? EMPTY_ARGS : args);
		}
		@Override public Object newInstance2(Object a0, Object a1) throws Throwable { return mh.invokeExact(a0, a1); }
	}

	private static final class Arity3CtorInvoker implements MagicConstructorInvoker {
		private final MethodHandle mh;
		private final MethodHandle spreader;
		Arity3CtorInvoker(MethodHandle mh) { this.mh = mh; this.spreader = mh.asSpreader(Object[].class, 3); }
		@Override public Object newInstance(Object[] args) throws Throwable {
			if (args != null && args.length == 3) return mh.invokeExact(args[0], args[1], args[2]);
			return spreader.invoke(args == null ? EMPTY_ARGS : args);
		}
		@Override public Object newInstance3(Object a0, Object a1, Object a2) throws Throwable { return mh.invokeExact(a0, a1, a2); }
	}

	private static final class GenericCtorInvoker implements MagicConstructorInvoker {
		private final MethodHandle spreader;
		GenericCtorInvoker(MethodHandle spreader) { this.spreader = spreader; }
		@Override public Object newInstance(Object[] args) throws Throwable {
			return spreader.invoke(args == null ? EMPTY_ARGS : args);
		}
	}

	public static MagicInvoker getMethodInvoker(Class<?> clazz, String methodName, int arity, boolean isStatic) {
		return getMethodInvoker(clazz, methodName, arity, isStatic, getEffectiveMode());
	}

	public static MagicInvoker getMethodInvoker(Class<?> clazz, String methodName, int arity, boolean isStatic, AccessMode mode) {
		if (mode == AccessMode.AUTO) mode = getEffectiveMode();
		ClassJITData     data   = JIT_DATA.get(clazz);
		InvokerLookupKey key    = new InvokerLookupKey(mode, methodName, arity, isStatic);
		MagicInvoker     cached = data.invokerCache.get(key);
		if (cached != null) return cached;
		MagicInvoker invoker = createMethodInvoker(clazz, methodName, arity, isStatic, mode);
		if (invoker != null) data.invokerCache.put(key, invoker);
		return invoker;
	}

	public static MagicConstructorInvoker getConstructorInvoker(Class<?> clazz, int arity) {
		return getConstructorInvoker(clazz, arity, getEffectiveMode());
	}

	public static MagicConstructorInvoker getConstructorInvoker(Class<?> clazz, int arity, AccessMode mode) {
		if (mode == AccessMode.AUTO) mode = getEffectiveMode();
		ClassJITData            data    = JIT_DATA.get(clazz);
		CtorLookupKey           key     = new CtorLookupKey(mode, arity);
		MagicConstructorInvoker cached = data.ctorCache.get(key);
		if (cached != null) return cached;
		MagicConstructorInvoker invoker = createConstructorInvoker(clazz, arity, mode);
		if (invoker != null) data.ctorCache.put(key, invoker);
		return invoker;
	}

	public static MagicInvoker createMethodInvoker(Class<?> clazz, String methodName, int arity, boolean isStatic) {
		return createMethodInvoker(clazz, methodName, arity, isStatic, getEffectiveMode());
	}

	public static MagicInvoker createMethodInvoker(Class<?> clazz, String methodName, int arity, boolean isStatic, AccessMode mode) {
		if (mode == AccessMode.AUTO) mode = getEffectiveMode();
		Method targetMethod = MethodResolver.findMethod(clazz, methodName, arity, isStatic);
		if (targetMethod == null) return null;
		targetMethod.setAccessible(true);
		try {
			if (mode == AccessMode.MAGIC_ACCESSOR) {
				if (!Modifier.isPrivate(targetMethod.getModifiers())) {
					MagicInvoker invoker = generateAsmMethodInvoker(clazz, targetMethod);
					if (invoker != null) return invoker;
				}
				MagicInvoker linkToInvoker = generateLinkToMethodInvoker(clazz, targetMethod);
				if (linkToInvoker != null) return linkToInvoker;
			} else if (mode == AccessMode.UNSAFE_AND_LINKTO) {
				MagicInvoker linkToInvoker = generateLinkToMethodInvoker(clazz, targetMethod);
				if (linkToInvoker != null) return linkToInvoker;
			}

			// Fallback / UNSAFE_AND_METHODHANDLE
			MethodHandle exactMh = generateDirectMethodHandleStub(clazz, targetMethod);
			if (exactMh == null) return null;
			switch (arity) {
				case 0: return new Arity0Invoker(exactMh);
				case 1: return new Arity1Invoker(exactMh);
				case 2: return new Arity2Invoker(exactMh);
				case 3: return new Arity3Invoker(exactMh);
				default: return new GenericInvoker(exactMh.asSpreader(Object[].class, arity));
			}
		} catch (Throwable e) {
			throw new RuntimeException("Failed to generate MagicInvoker for " + clazz.getName() + "#" + methodName + " (mode=" + mode + ")", e);
		}
	}

	public static MagicConstructorInvoker createConstructorInvoker(Class<?> clazz, int arity) {
		return createConstructorInvoker(clazz, arity, getEffectiveMode());
	}

	public static MagicConstructorInvoker createConstructorInvoker(Class<?> clazz, int arity, AccessMode mode) {
		if (mode == AccessMode.AUTO) mode = getEffectiveMode();
		if (mode == AccessMode.MAGIC_ACCESSOR) {
			MagicConstructorInvoker asmInvoker = generateAsmConstructorInvoker(clazz, arity);
			if (asmInvoker != null) return asmInvoker;
			MagicConstructorInvoker linkToCtor = generateLinkToConstructorInvoker(clazz, arity);
			if (linkToCtor != null) return linkToCtor;
		} else if (mode == AccessMode.UNSAFE_AND_LINKTO) {
			MagicConstructorInvoker linkToCtor = generateLinkToConstructorInvoker(clazz, arity);
			if (linkToCtor != null) return linkToCtor;
		}

		Constructor<?> targetCtor = MethodResolver.findConstructor(clazz, arity);
		if (targetCtor == null) return null;
		targetCtor.setAccessible(true);
		try {
			MethodHandle ctorMh = Magic.lookup.unreflectConstructor(targetCtor);
			Class<?>[] paramTypes = targetCtor.getParameterTypes();
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
			switch (arity) {
				case 0: return new Arity0CtorInvoker(finalCtorMh);
				case 1: return new Arity1CtorInvoker(finalCtorMh);
				case 2: return new Arity2CtorInvoker(finalCtorMh);
				case 3: return new Arity3CtorInvoker(finalCtorMh);
				default: return new GenericCtorInvoker(finalCtorMh.asSpreader(Object[].class, arity));
			}
		} catch (Throwable e) {
			throw new RuntimeException("Failed to generate MagicConstructorInvoker for " + clazz.getName() + " (mode=" + mode + ")", e);
		}
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
		} else mh = FieldMH.GET_OBJECT;
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
		} else mh = FieldMH.PUT_OBJECT;
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
		ClassJITData     data   = JIT_DATA.get(clazz);
		ExactMethodKey   key    = new ExactMethodKey(mode, targetMethod);
		MethodHandle     cached = data.exactMethodCache.get(key);
		if (cached != null) return cached;
		MethodHandle stub = generateExactMethodStub(clazz, targetMethod, mode);
		if (stub != null) data.exactMethodCache.put(key, stub);
		return stub;
	}

	public static MethodHandle generateExactMethodStub(Class<?> clazz, Method targetMethod, AccessMode mode) {
		if (mode == AccessMode.MAGIC_ACCESSOR) {
			if (!Modifier.isPrivate(targetMethod.getModifiers())) {
				try {
					MethodHandle stub = generateAsmExactMethodStub(clazz, targetMethod);
					if (stub != null) return stub;
				} catch (Throwable ignored) {
				}
			}
		}
		return generateDirectMethodHandleStub(clazz, targetMethod);
	}

	private static MethodHandle generateDirectMethodHandleStub(Class<?> clazz, Method targetMethod) {
		int        arity       = targetMethod.getParameterCount();
		boolean    isStatic    = Modifier.isStatic(targetMethod.getModifiers());
		Class<?>[] paramTypes  = targetMethod.getParameterTypes();
		Class<?>   retType     = targetMethod.getReturnType();

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

	private static void emitBootstrapUnbox(MethodVisitor mv, Class<?> pType) {
		if (pType == int.class) {
			mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
			mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
		} else if (pType == double.class) {
			mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
			mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false);
		} else if (pType == long.class) {
			mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
			mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false);
		} else if (pType == boolean.class) {
			mv.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
			mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
		} else if (pType == float.class) {
			mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
			mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false);
		} else if (pType == short.class) {
			mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
			mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "shortValue", "()S", false);
		} else if (pType == byte.class) {
			mv.visitTypeInsn(CHECKCAST, "java/lang/Number");
			mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "byteValue", "()B", false);
		} else if (pType == char.class) {
			mv.visitTypeInsn(CHECKCAST, "java/lang/Character");
			mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
		}
	}

	private static void emitBootstrapBox(MethodVisitor mv, Class<?> retType) {
		if (retType == int.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
		} else if (retType == double.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
		} else if (retType == long.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
		} else if (retType == boolean.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
		} else if (retType == float.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
		} else if (retType == short.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
		} else if (retType == byte.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
		} else if (retType == char.class) {
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
		}
	}

	private static boolean isClassVisibleToBootstrap(Class<?> cls) {
		if (cls == null) return false;
		if (cls.isPrimitive()) return true;
		if (cls.isArray()) return isClassVisibleToBootstrap(cls.getComponentType());
		if (cls.getClassLoader() == null) return true;
		try {
			return Class.forName(cls.getName(), false, ClassLoader.getSystemClassLoader()) == cls;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static boolean isMethodVisibleToBootstrap(Class<?> clazz, Method method) {
		if (!isClassVisibleToBootstrap(clazz)) return false;
		if (!isClassVisibleToBootstrap(method.getReturnType())) return false;
		for (Class<?> p : method.getParameterTypes()) {
			if (!isClassVisibleToBootstrap(p)) return false;
		}
		return true;
	}

	private static boolean isConstructorVisibleToBootstrap(Class<?> clazz, Constructor<?> ctor) {
		if (!isClassVisibleToBootstrap(clazz)) return false;
		for (Class<?> p : ctor.getParameterTypes()) {
			if (!isClassVisibleToBootstrap(p)) return false;
		}
		return true;
	}

	private static void emitPushClassForBootstrap(MethodVisitor mv, Class<?> clazz) {
		if (clazz.getClassLoader() == null) {
			mv.visitLdcInsn(Type.getType(clazz));
		} else {
			mv.visitLdcInsn(clazz.getName());
			mv.visitInsn(ICONST_1);
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/ClassLoader", "getSystemClassLoader", "()Ljava/lang/ClassLoader;", false);
			mv.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
		}
	}

	private static final class LinkToBridgeInfo {
		final Class<?> bridgeClass;
		final String bridgeInternalName;
		final String bridgeDesc;
		final String linkToName;

		LinkToBridgeInfo(Class<?> bridgeClass, String bridgeInternalName, String bridgeDesc, String linkToName) {
			this.bridgeClass = bridgeClass;
			this.bridgeInternalName = bridgeInternalName;
			this.bridgeDesc = bridgeDesc;
			this.linkToName = linkToName;
		}
	}

	/**
	 * 在 Bootstrap ClassLoader 的 {@code java.lang.invoke} 包下动态生成 {@code MagicBridge} 类。
	 * <p>该桥接类包含静态成员 {@code @Stable MemberName MN} 以及静态方法 {@code x0}，
	 * 内部直接发射 JVM 虚拟机底层原语指令（{@code linkToVirtual / linkToStatic / linkToSpecial / linkToInterface}）。</p>
	 *
	 * <b>性能与 C2 优化优势：</b>
	 * <ul>
	 *   <li><b>C2 Inlining Budget 极度节约：</b>调用图深仅 1 层，无需经历 LambdaForm、rebind、guard 等繁复中转，杜绝 C2 内联预算耗尽。</li>
	 *   <li><b>消除同构签名递归中断：</b>无多层泛型签名中转，不会触发 C2 的同构方法内联截断（MaxRecursiveInlineLevel）。</li>
	 *   <li><b>特权穿透：</b>原生原语直接操作方法槽，天然支持访问私有方法。</li>
	 * </ul>
	 */
	private static LinkToBridgeInfo createLinkToBridge(Class<?> clazz, Method targetMethod) {
		if (MEMBER_NAME_CLASS == null || LinkerHelper.IS_ANDROID) return null;
		int        arity       = targetMethod.getParameterCount();
		boolean    isStatic    = Modifier.isStatic(targetMethod.getModifiers());
		boolean    isSpecial   = Modifier.isPrivate(targetMethod.getModifiers());
		boolean    isInterface = clazz.isInterface();
		Class<?>[] paramTypes  = targetMethod.getParameterTypes();
		Class<?>   retType     = targetMethod.getReturnType();

		String linkToName;
		byte refKind;
		if (isStatic) {
			linkToName = "linkToStatic";
			refKind = 6;
		} else if (isSpecial) {
			linkToName = "linkToSpecial";
			refKind = 7;
		} else if (isInterface) {
			linkToName = "linkToInterface";
			refKind = 9;
		} else {
			linkToName = "linkToVirtual";
			refKind = 5;
		}

		boolean visible = isMethodVisibleToBootstrap(clazz, targetMethod);
		Object fallbackMn = null;

		try {
			targetMethod.setAccessible(true);
			if (!visible) {
				MethodHandle raw = Magic.lookup.unreflect(targetMethod);
				fallbackMn = LinkerHelper.extractMemberName(raw);
				if (fallbackMn == null) return null;
			}

			Magic.install();
			String bridgeSimpleName = "MagicBridge_" + COUNTER.incrementAndGet();
			String bridgeInternalName = "java/lang/invoke/" + bridgeSimpleName;

			ClassWriter bw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			bw.visit(V1_8, ACC_PUBLIC | ACC_FINAL, bridgeInternalName, null, "java/lang/Object", null);

			if (visible) {
				FieldVisitor fv = bw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "MN", "Ljava/lang/invoke/MemberName;", null, null);
				fv.visitAnnotation("Ljdk/internal/vm/annotation/Stable;", true).visitEnd();
				fv.visitEnd();
			} else {
				FieldVisitor fv = bw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_VOLATILE, "MN", "Ljava/lang/Object;", null, null);
				fv.visitAnnotation("Ljdk/internal/vm/annotation/Stable;", true).visitEnd();
				fv.visitEnd();
			}

			StringBuilder bridgeDesc = new StringBuilder("(");
			bridgeDesc.append("Ljava/lang/Object;"); // target (slot 0)
			for (int i = 0; i < arity; i++) {
				bridgeDesc.append("Ljava/lang/Object;");
			}
			bridgeDesc.append(")Ljava/lang/Object;");

			MethodVisitor mv = bw.visitMethod(ACC_PUBLIC | ACC_STATIC, "x0", bridgeDesc.toString(), null, new String[]{"java/lang/Throwable"});
			mv.visitAnnotation("Ljdk/internal/vm/annotation/ForceInline;", true).visitEnd();
			mv.visitAnnotation("Ljava/lang/invoke/ForceInline;", true).visitEnd();
			mv.visitAnnotation("Ljdk/internal/vm/annotation/Hidden;", true).visitEnd();
			mv.visitAnnotation("Ljava/lang/invoke/LambdaForm$Hidden;", true).visitEnd();
			mv.visitCode();

			if (!isStatic) {
				mv.visitVarInsn(ALOAD, 0);
			}
			for (int i = 0; i < arity; i++) {
				mv.visitVarInsn(ALOAD, 1 + i);
				emitBootstrapUnbox(mv, paramTypes[i]);
			}

			if (visible) {
				mv.visitFieldInsn(GETSTATIC, bridgeInternalName, "MN", "Ljava/lang/invoke/MemberName;");
			} else {
				mv.visitFieldInsn(GETSTATIC, bridgeInternalName, "MN", "Ljava/lang/Object;");
				mv.visitTypeInsn(CHECKCAST, "java/lang/invoke/MemberName");
			}

			StringBuilder linkToDesc = new StringBuilder("(");
			if (!isStatic) {
				linkToDesc.append("Ljava/lang/Object;");
			}
			for (int i = 0; i < arity; i++) {
				if (paramTypes[i].isPrimitive()) {
					linkToDesc.append(Type.getDescriptor(paramTypes[i]));
				} else {
					linkToDesc.append("Ljava/lang/Object;");
				}
			}
			linkToDesc.append("Ljava/lang/invoke/MemberName;)");
			if (retType == void.class) {
				linkToDesc.append("V");
			} else if (retType.isPrimitive()) {
				linkToDesc.append(Type.getDescriptor(retType));
			} else {
				linkToDesc.append("Ljava/lang/Object;");
			}

			mv.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/MethodHandle", linkToName, linkToDesc.toString(), false);

			if (retType == void.class) {
				mv.visitInsn(ACONST_NULL);
			} else if (retType.isPrimitive()) {
				emitBootstrapBox(mv, retType);
			}
			mv.visitInsn(ARETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();

			if (visible) {
				MethodVisitor clinit = bw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
				clinit.visitCode();

				clinit.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/MemberName", "getFactory", "()Ljava/lang/invoke/MemberName$Factory;", false);
				clinit.visitIntInsn(BIPUSH, refKind);
				clinit.visitTypeInsn(NEW, "java/lang/invoke/MemberName");
				clinit.visitInsn(DUP);

				emitPushClassForBootstrap(clinit, clazz);
				clinit.visitLdcInsn(targetMethod.getName());
				clinit.visitLdcInsn(Type.getMethodDescriptor(targetMethod));
				clinit.visitMethodInsn(INVOKESTATIC, "java/lang/ClassLoader", "getSystemClassLoader", "()Ljava/lang/ClassLoader;", false);
				clinit.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/MethodType", "fromMethodDescriptorString",
					"(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;", false);

				clinit.visitIntInsn(BIPUSH, refKind);
				clinit.visitMethodInsn(INVOKESPECIAL, "java/lang/invoke/MemberName", "<init>",
					"(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;B)V", false);

				clinit.visitInsn(ACONST_NULL);
				clinit.visitInsn(ICONST_M1);
				clinit.visitLdcInsn(Type.getType(NoSuchMethodException.class));
				clinit.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MemberName$Factory", "resolveOrFail",
					"(BLjava/lang/invoke/MemberName;Ljava/lang/Class;ILjava/lang/Class;)Ljava/lang/invoke/MemberName;", false);
				clinit.visitFieldInsn(PUTSTATIC, bridgeInternalName, "MN", "Ljava/lang/invoke/MemberName;");

				clinit.visitInsn(RETURN);
				clinit.visitMaxs(0, 0);
				clinit.visitEnd();
			}

			bw.visitEnd();

			Class<?> bridgeClass = Magic.defineClass(null, bw.toByteArray());
			if (!visible) {
				Field mnField = bridgeClass.getDeclaredField("MN");
				Magic.unsafe.putObject(Magic.unsafe.staticFieldBase(mnField), Magic.unsafe.staticFieldOffset(mnField), fallbackMn);
			}

			return new LinkToBridgeInfo(bridgeClass, bridgeInternalName, bridgeDesc.toString(), linkToName);
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * 动态生成基于 {@code linkTo*} 方案的 {@link MagicInvoker} 实例。
	 * <p>通过直接发射字节码 {@code invokestatic MagicBridge_N.x0} 直连底层原语，
	 * 特化实现 {@code invoke0} ~ {@code invoke3}：</p>
	 * <ul>
	 *   <li><b>0 个 MethodHandle 实例与 0 次 invokeExact 调用；</b></li>
	 *   <li><b>0 次通用参数数组分配 (杜绝 new Object[])；</b></li>
	 *   <li><b>扁平调用图，最大化保留 C2 JIT 内联预算。</b></li>
	 * </ul>
	 */
	private static MagicInvoker generateLinkToMethodInvoker(Class<?> clazz, Method targetMethod) {
		LinkToBridgeInfo bridge = createLinkToBridge(clazz, targetMethod);
		if (bridge == null) return null;

		int      arity   = targetMethod.getParameterCount();
		Class<?> retType = targetMethod.getReturnType();

		try {
			ClassWriter iw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			String invokerClassName = "hope/magic/gen/MagicLinkToInvoker_" + COUNTER.incrementAndGet();
			iw.visit(V1_8, ACC_PUBLIC | ACC_FINAL, invokerClassName, null, "java/lang/Object",
				new String[]{Type.getInternalName(MagicInvoker.class)});

			MethodVisitor initMv = iw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
			initMv.visitCode();
			initMv.visitVarInsn(ALOAD, 0);
			initMv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
			initMv.visitInsn(RETURN);
			initMv.visitMaxs(0, 0);
			initMv.visitEnd();

			// invoke(Object target, Object[] args)
			MethodVisitor invMv = iw.visitMethod(ACC_PUBLIC, "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
			invMv.visitCode();
			invMv.visitVarInsn(ALOAD, 1);
			for (int i = 0; i < arity; i++) {
				invMv.visitVarInsn(ALOAD, 2);
				pushInt(invMv, i);
				invMv.visitInsn(AALOAD);
			}
			invMv.visitMethodInsn(INVOKESTATIC, bridge.bridgeInternalName, "x0", bridge.bridgeDesc, false);
			emitBridgeReturnCheck(invMv, retType);
			invMv.visitInsn(ARETURN);
			invMv.visitMaxs(0, 0);
			invMv.visitEnd();

			if (arity == 0) {
				MethodVisitor m0 = iw.visitMethod(ACC_PUBLIC, "invoke0", "(Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
				m0.visitCode();
				m0.visitVarInsn(ALOAD, 1);
				m0.visitMethodInsn(INVOKESTATIC, bridge.bridgeInternalName, "x0", bridge.bridgeDesc, false);
				emitBridgeReturnCheck(m0, retType);
				m0.visitInsn(ARETURN);
				m0.visitMaxs(0, 0);
				m0.visitEnd();
			} else if (arity == 1) {
				MethodVisitor m1 = iw.visitMethod(ACC_PUBLIC, "invoke1", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
				m1.visitCode();
				m1.visitVarInsn(ALOAD, 1);
				m1.visitVarInsn(ALOAD, 2);
				m1.visitMethodInsn(INVOKESTATIC, bridge.bridgeInternalName, "x0", bridge.bridgeDesc, false);
				emitBridgeReturnCheck(m1, retType);
				m1.visitInsn(ARETURN);
				m1.visitMaxs(0, 0);
				m1.visitEnd();
			} else if (arity == 2) {
				MethodVisitor m2 = iw.visitMethod(ACC_PUBLIC, "invoke2", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
				m2.visitCode();
				m2.visitVarInsn(ALOAD, 1);
				m2.visitVarInsn(ALOAD, 2);
				m2.visitVarInsn(ALOAD, 3);
				m2.visitMethodInsn(INVOKESTATIC, bridge.bridgeInternalName, "x0", bridge.bridgeDesc, false);
				emitBridgeReturnCheck(m2, retType);
				m2.visitInsn(ARETURN);
				m2.visitMaxs(0, 0);
				m2.visitEnd();
			} else if (arity == 3) {
				MethodVisitor m3 = iw.visitMethod(ACC_PUBLIC, "invoke3", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
				m3.visitCode();
				m3.visitVarInsn(ALOAD, 1);
				m3.visitVarInsn(ALOAD, 2);
				m3.visitVarInsn(ALOAD, 3);
				m3.visitVarInsn(ALOAD, 4);
				m3.visitMethodInsn(INVOKESTATIC, bridge.bridgeInternalName, "x0", bridge.bridgeDesc, false);
				emitBridgeReturnCheck(m3, retType);
				m3.visitInsn(ARETURN);
				m3.visitMaxs(0, 0);
				m3.visitEnd();
			}

			iw.visitEnd();
			ClassLoader loader = clazz.getClassLoader() != null ? clazz.getClassLoader() : MagicJIT.class.getClassLoader();
			Class<?> invokerClass = Magic.defineClass(loader, iw.toByteArray());
			return (MagicInvoker) invokerClass.getDeclaredConstructor().newInstance();
		} catch (Throwable t) {
			return null;
		}
	}

	private static void emitBridgeReturnCheck(MethodVisitor mv, Class<?> retType) {
		if (retType == void.class) {
			mv.visitInsn(POP);
			mv.visitFieldInsn(GETSTATIC, "hope/magic/js/runtime/JSUndefined", "INSTANCE", "Lhope/magic/js/runtime/JSUndefined;");
		}
	}

	private static MagicInvoker generateAsmMethodInvoker(Class<?> clazz, Method targetMethod) {
		int        arity       = targetMethod.getParameterCount();
		boolean    isStatic    = Modifier.isStatic(targetMethod.getModifiers());
		Class<?>[] paramTypes  = targetMethod.getParameterTypes();
		Class<?>   retType     = targetMethod.getReturnType();

		try {
			Magic.install();
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			String className = "hope/magic/gen/MagicDirectInvoker_" + COUNTER.incrementAndGet();
			cw.visit(V1_8, ACC_PUBLIC | ACC_FINAL, className, null, "hope/magic/runtime/MAGICIMPL",
				new String[]{Type.getInternalName(MagicInvoker.class)});

			createMagicInitMethod(cw);

			String owner = Type.getInternalName(clazz);
			String methodDesc = Type.getMethodDescriptor(targetMethod);

			// 1. invoke(Object target, Object[] args)
			MethodVisitor invMv = cw.visitMethod(ACC_PUBLIC, "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
			invMv.visitCode();
			if (!isStatic) {
				invMv.visitVarInsn(ALOAD, 1);
				invMv.visitTypeInsn(CHECKCAST, owner);
			}
			for (int i = 0; i < arity; i++) {
				invMv.visitVarInsn(ALOAD, 2);
				pushInt(invMv, i);
				invMv.visitInsn(AALOAD);
				emitArgumentCast(invMv, paramTypes[i]);
			}
			emitInvokeTarget(invMv, clazz, targetMethod, owner, methodDesc, isStatic);
			emitReturnBox(invMv, retType);
			invMv.visitInsn(ARETURN);
			invMv.visitMaxs(0, 0);
			invMv.visitEnd();

			if (arity == 0) {
				MethodVisitor m0 = cw.visitMethod(ACC_PUBLIC, "invoke0", "(Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
				m0.visitCode();
				if (!isStatic) {
					m0.visitVarInsn(ALOAD, 1);
					m0.visitTypeInsn(CHECKCAST, owner);
				}
				emitInvokeTarget(m0, clazz, targetMethod, owner, methodDesc, isStatic);
				emitReturnBox(m0, retType);
				m0.visitInsn(ARETURN);
				m0.visitMaxs(0, 0);
				m0.visitEnd();
			} else if (arity == 1) {
				MethodVisitor m1 = cw.visitMethod(ACC_PUBLIC, "invoke1", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
				m1.visitCode();
				if (!isStatic) {
					m1.visitVarInsn(ALOAD, 1);
					m1.visitTypeInsn(CHECKCAST, owner);
				}
				m1.visitVarInsn(ALOAD, 2);
				emitArgumentCast(m1, paramTypes[0]);
				emitInvokeTarget(m1, clazz, targetMethod, owner, methodDesc, isStatic);
				emitReturnBox(m1, retType);
				m1.visitInsn(ARETURN);
				m1.visitMaxs(0, 0);
				m1.visitEnd();
			} else if (arity == 2) {
				MethodVisitor m2 = cw.visitMethod(ACC_PUBLIC, "invoke2", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
				m2.visitCode();
				if (!isStatic) {
					m2.visitVarInsn(ALOAD, 1);
					m2.visitTypeInsn(CHECKCAST, owner);
				}
				m2.visitVarInsn(ALOAD, 2);
				emitArgumentCast(m2, paramTypes[0]);
				m2.visitVarInsn(ALOAD, 3);
				emitArgumentCast(m2, paramTypes[1]);
				emitInvokeTarget(m2, clazz, targetMethod, owner, methodDesc, isStatic);
				emitReturnBox(m2, retType);
				m2.visitInsn(ARETURN);
				m2.visitMaxs(0, 0);
				m2.visitEnd();
			} else if (arity == 3) {
				MethodVisitor m3 = cw.visitMethod(ACC_PUBLIC, "invoke3", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
				m3.visitCode();
				if (!isStatic) {
					m3.visitVarInsn(ALOAD, 1);
					m3.visitTypeInsn(CHECKCAST, owner);
				}
				m3.visitVarInsn(ALOAD, 2);
				emitArgumentCast(m3, paramTypes[0]);
				m3.visitVarInsn(ALOAD, 3);
				emitArgumentCast(m3, paramTypes[1]);
				m3.visitVarInsn(ALOAD, 4);
				emitArgumentCast(m3, paramTypes[2]);
				emitInvokeTarget(m3, clazz, targetMethod, owner, methodDesc, isStatic);
				emitReturnBox(m3, retType);
				m3.visitInsn(ARETURN);
				m3.visitMaxs(0, 0);
				m3.visitEnd();
			}

			cw.visitEnd();
			ClassLoader loader = clazz.getClassLoader() != null ? clazz.getClassLoader() : MagicJIT.class.getClassLoader();
			Class<?> genClass = Magic.defineClass(loader, cw.toByteArray());
			return (MagicInvoker) genClass.getDeclaredConstructor().newInstance();
		} catch (Throwable e) {
			return null;
		}
	}

	private static void emitInvokeTarget(MethodVisitor mv, Class<?> clazz, Method targetMethod, String owner, String methodDesc, boolean isStatic) {
		if (isStatic) {
			mv.visitMethodInsn(INVOKESTATIC, owner, targetMethod.getName(), methodDesc, clazz.isInterface());
		} else if (clazz.isInterface()) {
			mv.visitMethodInsn(INVOKEINTERFACE, owner, targetMethod.getName(), methodDesc, true);
		} else {
			mv.visitMethodInsn(INVOKEVIRTUAL, owner, targetMethod.getName(), methodDesc, false);
		}
	}

	private static MethodHandle generateAsmExactMethodStub(Class<?> clazz, Method targetMethod) {
		int     arity    = targetMethod.getParameterCount();
		boolean isStatic = Modifier.isStatic(targetMethod.getModifiers());

		try {
			Magic.install();
			ClassWriter cw        = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			String      className = "hope/magic/gen/MagicExactMethod_" + COUNTER.incrementAndGet();
			cw.visit(V1_8, ACC_PUBLIC | ACC_FINAL, className, null, "hope/magic/runtime/MAGICIMPL", null);

			createMagicInitMethod(cw);

			Class<?>[] argTypes = new Class<?>[1 + arity];
			argTypes[0] = Object.class;
			for (int i = 0; i < arity; i++) argTypes[1 + i] = Object.class;
			MethodType stubType = MethodType.methodType(Object.class, argTypes);

			MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "invoke", stubType.toMethodDescriptorString(), null, null);
			mv.visitCode();
			String owner = Type.getInternalName(clazz);
			if (!isStatic) {
				mv.visitVarInsn(ALOAD, 0);
				mv.visitTypeInsn(CHECKCAST, owner);
			}

			Class<?>[] paramTypes = targetMethod.getParameterTypes();
			for (int i = 0; i < arity; i++) {
				mv.visitVarInsn(ALOAD, 1 + i);
				emitArgumentCast(mv, paramTypes[i]);
			}

			String methodDesc = Type.getMethodDescriptor(targetMethod);
			emitInvokeTarget(mv, clazz, targetMethod, owner, methodDesc, isStatic);

			emitReturnBox(mv, targetMethod.getReturnType());
			mv.visitInsn(ARETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();

			cw.visitEnd();
			ClassLoader loader = clazz.getClassLoader() != null ? clazz.getClassLoader() : MagicJIT.class.getClassLoader();
			Class<?> genClass = Magic.defineClass(loader, cw.toByteArray());
			return Magic.lookup.findStatic(genClass, "invoke", stubType);
		} catch (Throwable e) {
			return null;
		}
	}

	private static MagicConstructorInvoker generateAsmConstructorInvoker(Class<?> clazz, int arity) {
		Constructor<?> targetCtor = MethodResolver.findConstructor(clazz, arity);
		if (targetCtor == null || Modifier.isPrivate(targetCtor.getModifiers())) return null;
		targetCtor.setAccessible(true);
		try {
			Magic.install();
			ClassWriter cw        = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			String      className = "hope/magic/gen/MagicCtorInvoker_" + COUNTER.incrementAndGet();
			cw.visit(V1_8, ACC_PUBLIC | ACC_FINAL, className, null, "hope/magic/runtime/MAGICIMPL", new String[]{Type.getInternalName(MagicConstructorInvoker.class)});

			createMagicInitMethod(cw);

			String targetOwner = Type.getInternalName(clazz);
			String ctorDesc = Type.getConstructorDescriptor(targetCtor);
			Class<?>[] paramTypes = targetCtor.getParameterTypes();

			// newInstance(Object[] args)
			MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "newInstance", "([Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
			mv.visitCode();
			mv.visitTypeInsn(NEW, targetOwner);
			mv.visitInsn(DUP);
			for (int i = 0; i < arity; i++) {
				mv.visitVarInsn(ALOAD, 1);
				pushInt(mv, i);
				mv.visitInsn(AALOAD);
				emitArgumentCast(mv, paramTypes[i]);
			}
			mv.visitMethodInsn(INVOKESPECIAL, targetOwner, "<init>", ctorDesc, false);
			mv.visitInsn(ARETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();

			if (arity == 0) {
				MethodVisitor n0 = cw.visitMethod(ACC_PUBLIC, "newInstance0", "()Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
				n0.visitCode();
				n0.visitTypeInsn(NEW, targetOwner);
				n0.visitInsn(DUP);
				n0.visitMethodInsn(INVOKESPECIAL, targetOwner, "<init>", "()V", false);
				n0.visitInsn(ARETURN);
				n0.visitMaxs(0, 0);
				n0.visitEnd();
			} else if (arity == 1) {
				MethodVisitor n1 = cw.visitMethod(ACC_PUBLIC, "newInstance1", "(Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
				n1.visitCode();
				n1.visitTypeInsn(NEW, targetOwner);
				n1.visitInsn(DUP);
				n1.visitVarInsn(ALOAD, 1);
				emitArgumentCast(n1, paramTypes[0]);
				n1.visitMethodInsn(INVOKESPECIAL, targetOwner, "<init>", ctorDesc, false);
				n1.visitInsn(ARETURN);
				n1.visitMaxs(0, 0);
				n1.visitEnd();
			} else if (arity == 2) {
				MethodVisitor n2 = cw.visitMethod(ACC_PUBLIC, "newInstance2", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
				n2.visitCode();
				n2.visitTypeInsn(NEW, targetOwner);
				n2.visitInsn(DUP);
				n2.visitVarInsn(ALOAD, 1);
				emitArgumentCast(n2, paramTypes[0]);
				n2.visitVarInsn(ALOAD, 2);
				emitArgumentCast(n2, paramTypes[1]);
				n2.visitMethodInsn(INVOKESPECIAL, targetOwner, "<init>", ctorDesc, false);
				n2.visitInsn(ARETURN);
				n2.visitMaxs(0, 0);
				n2.visitEnd();
			} else if (arity == 3) {
				MethodVisitor n3 = cw.visitMethod(ACC_PUBLIC, "newInstance3", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
				n3.visitCode();
				n3.visitTypeInsn(NEW, targetOwner);
				n3.visitInsn(DUP);
				n3.visitVarInsn(ALOAD, 1);
				emitArgumentCast(n3, paramTypes[0]);
				n3.visitVarInsn(ALOAD, 2);
				emitArgumentCast(n3, paramTypes[1]);
				n3.visitVarInsn(ALOAD, 3);
				emitArgumentCast(n3, paramTypes[2]);
				n3.visitMethodInsn(INVOKESPECIAL, targetOwner, "<init>", ctorDesc, false);
				n3.visitInsn(ARETURN);
				n3.visitMaxs(0, 0);
				n3.visitEnd();
			}

			cw.visitEnd();
			byte[]      bytes  = cw.toByteArray();
			ClassLoader loader = clazz.getClassLoader() != null ? clazz.getClassLoader() : MagicJIT.class.getClassLoader();
			Class<?>    genClass = Magic.defineClass(loader, bytes);
			return (MagicConstructorInvoker) genClass.getDeclaredConstructor().newInstance();
		} catch (Throwable e) {
			return null;
		}
	}

	/**
	 * 动态生成基于 {@code linkToSpecial} 与 {@code Unsafe.allocateInstance} 方案的 {@link MagicConstructorInvoker}。
	 * <p>通过 {@code Unsafe.allocateInstance} 分配未初始化的堆对象，
	 * 并通过 {@code linkToSpecial(<init>)} 原生原语调用构造器，完美解决私有构造器特权访问与零 MH/零数组开销：</p>
	 * <ul>
	 *   <li><b>支持任意私有构造器直调；</b></li>
	 *   <li><b>0 个 MethodHandle、0 次反射中转；</b></li>
	 *   <li><b>特化 {@code newInstance0~3} 杜绝参数数组分配；</b></li>
	 *   <li><b>极度扁平指令序列，易于 C2 深度内联与逃逸分析标量替换 (Scalar Replacement)。</b></li>
	 * </ul>
	 */
	private static MagicConstructorInvoker generateLinkToConstructorInvoker(Class<?> clazz, int arity) {
		if (MEMBER_NAME_CLASS == null || LinkerHelper.IS_ANDROID) return null;
		Constructor<?> targetCtor = MethodResolver.findConstructor(clazz, arity);
		if (targetCtor == null) return null;
		boolean visible = isConstructorVisibleToBootstrap(clazz, targetCtor);
		Object fallbackMn = null;

		try {
			if (!visible) {
				MethodHandle rawCtor = Magic.lookup.unreflectConstructor(targetCtor);
				fallbackMn = LinkerHelper.extractMemberName(rawCtor);
				if (fallbackMn == null) return null;
			}

			Magic.install();
			String bridgeSimpleName = "MagicCtorBridge_" + COUNTER.incrementAndGet();
			String bridgeInternalName = "java/lang/invoke/" + bridgeSimpleName;

			ClassWriter bw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			bw.visit(V1_8, ACC_PUBLIC | ACC_FINAL, bridgeInternalName, null, "java/lang/Object", null);

			if (visible) {
				FieldVisitor fv = bw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "MN", "Ljava/lang/invoke/MemberName;", null, null);
				fv.visitAnnotation("Ljdk/internal/vm/annotation/Stable;", true).visitEnd();
				fv.visitEnd();
			} else {
				FieldVisitor fv = bw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_VOLATILE, "MN", "Ljava/lang/Object;", null, null);
				fv.visitAnnotation("Ljdk/internal/vm/annotation/Stable;", true).visitEnd();
				fv.visitEnd();
			}

			StringBuilder bridgeDesc = new StringBuilder("(Ljava/lang/Class;");
			for (int i = 0; i < arity; i++) {
				bridgeDesc.append("Ljava/lang/Object;");
			}
			bridgeDesc.append(")Ljava/lang/Object;");

			MethodVisitor mv = bw.visitMethod(ACC_PUBLIC | ACC_STATIC, "newInstance", bridgeDesc.toString(), null, new String[]{"java/lang/Throwable"});
			mv.visitAnnotation("Ljdk/internal/vm/annotation/ForceInline;", true).visitEnd();
			mv.visitAnnotation("Ljava/lang/invoke/ForceInline;", true).visitEnd();
			mv.visitAnnotation("Ljdk/internal/vm/annotation/Hidden;", true).visitEnd();
			mv.visitAnnotation("Ljava/lang/invoke/LambdaForm$Hidden;", true).visitEnd();
			mv.visitCode();

			mv.visitMethodInsn(INVOKESTATIC, "jdk/internal/misc/Unsafe", "getUnsafe", "()Ljdk/internal/misc/Unsafe;", false);
			mv.visitVarInsn(ALOAD, 0); // Class<?> cls
			mv.visitMethodInsn(INVOKEVIRTUAL, "jdk/internal/misc/Unsafe", "allocateInstance", "(Ljava/lang/Class;)Ljava/lang/Object;", false);
			mv.visitInsn(DUP);

			Class<?>[] paramTypes = targetCtor.getParameterTypes();
			for (int i = 0; i < arity; i++) {
				mv.visitVarInsn(ALOAD, 1 + i);
				emitBootstrapUnbox(mv, paramTypes[i]);
			}

			if (visible) {
				mv.visitFieldInsn(GETSTATIC, bridgeInternalName, "MN", "Ljava/lang/invoke/MemberName;");
			} else {
				mv.visitFieldInsn(GETSTATIC, bridgeInternalName, "MN", "Ljava/lang/Object;");
				mv.visitTypeInsn(CHECKCAST, "java/lang/invoke/MemberName");
			}

			StringBuilder linkToDesc = new StringBuilder("(Ljava/lang/Object;");
			for (int i = 0; i < arity; i++) {
				if (paramTypes[i].isPrimitive()) {
					linkToDesc.append(Type.getDescriptor(paramTypes[i]));
				} else {
					linkToDesc.append("Ljava/lang/Object;");
				}
			}
			linkToDesc.append("Ljava/lang/invoke/MemberName;)V");

			mv.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/MethodHandle", "linkToSpecial", linkToDesc.toString(), false);
			mv.visitInsn(ARETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();

			if (visible) {
				byte refKind = 7; // REF_invokeSpecial for constructor
				String ctorDesc = Type.getConstructorDescriptor(targetCtor);

				MethodVisitor clinit = bw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
				clinit.visitCode();

				clinit.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/MemberName", "getFactory", "()Ljava/lang/invoke/MemberName$Factory;", false);
				clinit.visitIntInsn(BIPUSH, refKind);
				clinit.visitTypeInsn(NEW, "java/lang/invoke/MemberName");
				clinit.visitInsn(DUP);

				emitPushClassForBootstrap(clinit, clazz);
				clinit.visitLdcInsn("<init>");
				clinit.visitLdcInsn(ctorDesc);
				clinit.visitMethodInsn(INVOKESTATIC, "java/lang/ClassLoader", "getSystemClassLoader", "()Ljava/lang/ClassLoader;", false);
				clinit.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/MethodType", "fromMethodDescriptorString",
					"(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;", false);

				clinit.visitIntInsn(BIPUSH, refKind);
				clinit.visitMethodInsn(INVOKESPECIAL, "java/lang/invoke/MemberName", "<init>",
					"(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;B)V", false);

				clinit.visitInsn(ACONST_NULL);
				clinit.visitInsn(ICONST_M1);
				clinit.visitLdcInsn(Type.getType(NoSuchMethodException.class));
				clinit.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MemberName$Factory", "resolveOrFail",
					"(BLjava/lang/invoke/MemberName;Ljava/lang/Class;ILjava/lang/Class;)Ljava/lang/invoke/MemberName;", false);
				clinit.visitFieldInsn(PUTSTATIC, bridgeInternalName, "MN", "Ljava/lang/invoke/MemberName;");

				clinit.visitInsn(RETURN);
				clinit.visitMaxs(0, 0);
				clinit.visitEnd();
			}

			bw.visitEnd();

			Class<?> bridgeClass = Magic.defineClass(null, bw.toByteArray());
			if (!visible) {
				Field mnField = bridgeClass.getDeclaredField("MN");
				Magic.unsafe.putObject(Magic.unsafe.staticFieldBase(mnField), Magic.unsafe.staticFieldOffset(mnField), fallbackMn);
			}

			String invokerClassName = "hope/magic/gen/MagicLinkToCtorInvoker_" + COUNTER.incrementAndGet();
			ClassWriter iw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			iw.visit(V1_8, ACC_PUBLIC | ACC_FINAL, invokerClassName, null, "java/lang/Object",
				new String[]{Type.getInternalName(MagicConstructorInvoker.class)});

			FieldVisitor tfv = iw.visitField(ACC_PUBLIC | ACC_STATIC, "TARGET_CLS", "Ljava/lang/Class;", null, null);
			tfv.visitEnd();

			MethodVisitor initMv = iw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
			initMv.visitCode();
			initMv.visitVarInsn(ALOAD, 0);
			initMv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
			initMv.visitInsn(RETURN);
			initMv.visitMaxs(0, 0);
			initMv.visitEnd();

			MethodVisitor newMv = iw.visitMethod(ACC_PUBLIC, "newInstance", "([Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
			newMv.visitCode();
			newMv.visitFieldInsn(GETSTATIC, invokerClassName, "TARGET_CLS", "Ljava/lang/Class;");
			for (int i = 0; i < arity; i++) {
				newMv.visitVarInsn(ALOAD, 1);
				pushInt(newMv, i);
				newMv.visitInsn(AALOAD);
			}
			newMv.visitMethodInsn(INVOKESTATIC, bridgeInternalName, "newInstance", bridgeDesc.toString(), false);
			newMv.visitInsn(ARETURN);
			newMv.visitMaxs(0, 0);
			newMv.visitEnd();

			if (arity == 0) {
				MethodVisitor n0 = iw.visitMethod(ACC_PUBLIC, "newInstance0", "()Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
				n0.visitCode();
				n0.visitFieldInsn(GETSTATIC, invokerClassName, "TARGET_CLS", "Ljava/lang/Class;");
				n0.visitMethodInsn(INVOKESTATIC, bridgeInternalName, "newInstance", bridgeDesc.toString(), false);
				n0.visitInsn(ARETURN);
				n0.visitMaxs(0, 0);
				n0.visitEnd();
			} else if (arity == 1) {
				MethodVisitor n1 = iw.visitMethod(ACC_PUBLIC, "newInstance1", "(Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
				n1.visitCode();
				n1.visitFieldInsn(GETSTATIC, invokerClassName, "TARGET_CLS", "Ljava/lang/Class;");
				n1.visitVarInsn(ALOAD, 1);
				n1.visitMethodInsn(INVOKESTATIC, bridgeInternalName, "newInstance", bridgeDesc.toString(), false);
				n1.visitInsn(ARETURN);
				n1.visitMaxs(0, 0);
				n1.visitEnd();
			} else if (arity == 2) {
				MethodVisitor n2 = iw.visitMethod(ACC_PUBLIC, "newInstance2", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
				n2.visitCode();
				n2.visitFieldInsn(GETSTATIC, invokerClassName, "TARGET_CLS", "Ljava/lang/Class;");
				n2.visitVarInsn(ALOAD, 1);
				n2.visitVarInsn(ALOAD, 2);
				n2.visitMethodInsn(INVOKESTATIC, bridgeInternalName, "newInstance", bridgeDesc.toString(), false);
				n2.visitInsn(ARETURN);
				n2.visitMaxs(0, 0);
				n2.visitEnd();
			} else if (arity == 3) {
				MethodVisitor n3 = iw.visitMethod(ACC_PUBLIC, "newInstance3", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", null, new String[]{"java/lang/Throwable"});
				n3.visitCode();
				n3.visitFieldInsn(GETSTATIC, invokerClassName, "TARGET_CLS", "Ljava/lang/Class;");
				n3.visitVarInsn(ALOAD, 1);
				n3.visitVarInsn(ALOAD, 2);
				n3.visitVarInsn(ALOAD, 3);
				n3.visitMethodInsn(INVOKESTATIC, bridgeInternalName, "newInstance", bridgeDesc.toString(), false);
				n3.visitInsn(ARETURN);
				n3.visitMaxs(0, 0);
				n3.visitEnd();
			}

			iw.visitEnd();
			ClassLoader loader = clazz.getClassLoader() != null ? clazz.getClassLoader() : MagicJIT.class.getClassLoader();
			Class<?> invokerClass = Magic.defineClass(loader, iw.toByteArray());
			invokerClass.getField("TARGET_CLS").set(null, clazz);
			return (MagicConstructorInvoker) invokerClass.getDeclaredConstructor().newInstance();
		} catch (Throwable t) {
			return null;
		}
	}

	private static void createMagicInitMethod(ClassWriter cw) {
		MethodVisitor initMv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
		initMv.visitCode();
		initMv.visitVarInsn(ALOAD, 0);
		initMv.visitMethodInsn(INVOKESPECIAL, "hope/magic/runtime/MAGICIMPL", "<init>", "()V", false);
		initMv.visitInsn(RETURN);
		initMv.visitMaxs(0, 0);
		initMv.visitEnd();
	}

	public static Field getDeclaredFieldRecursive(Class<?> clazz, String fieldName) {
		Class<?> cur = clazz;
		while (cur != null && cur != Object.class) {
			// if (LinkerHelper.FAST_OFFSET && jdk.internal.misc.Unsafe.getUnsafe().objectFieldOffset(clazz, fieldName) > 0)
			for (Field field : cur.getDeclaredFields()) {
				if (field.getName().equals(fieldName)) return field;
			}
			cur = cur.getSuperclass();
		}
		return null;
	}

	private static Method findMatchingMethod(Class<?> clazz, String methodName, int arity) {
		return MethodResolver.findMethod(clazz, methodName, arity);
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

	private static void emitArgumentCast(MethodVisitor mv, Class<?> pType) {
		if (pType == int.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toInt", "(Ljava/lang/Object;)I", false);
		} else if (pType == long.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toLong", "(Ljava/lang/Object;)J", false);
		} else if (pType == double.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toDouble", "(Ljava/lang/Object;)D", false);
		} else if (pType == float.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toFloat", "(Ljava/lang/Object;)F", false);
		} else if (pType == boolean.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toBoolean", "(Ljava/lang/Object;)Z", false);
		} else if (pType == short.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toShort", "(Ljava/lang/Object;)S", false);
		} else if (pType == byte.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toByte", "(Ljava/lang/Object;)B", false);
		} else if (pType == char.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toChar", "(Ljava/lang/Object;)C", false);
		} else if (pType == String.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toStr", "(Ljava/lang/Object;)Ljava/lang/String;", false);
		} else if (pType.isInterface() && pType != JSFunction.class && pType != JSObject.class) {
			mv.visitLdcInsn(Type.getType(pType));
			// mv.visitInsn(SWAP);
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "castValue", "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;", false);
			mv.visitTypeInsn(CHECKCAST, Type.getInternalName(pType));
		} else if (pType != Object.class) {
			mv.visitTypeInsn(CHECKCAST, Type.getInternalName(pType));
		}
	}

	static void boxPrimitive(MethodVisitor mv, Class<?> pt) {
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
	private static void emitReturnBox(MethodVisitor mv, Class<?> retType) {
		if (retType == void.class) {
			mv.visitFieldInsn(GETSTATIC, "hope/magic/js/runtime/JSUndefined", "INSTANCE", "Lhope/magic/js/runtime/JSUndefined;");
		} else {
			boxPrimitive(mv, retType);
		}
	}

	//region 动态接口适配器生成 (JIT Interface Adapters)

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
			ClassWriter cw          = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			String      className   = "hope/magic/gen/JSFunctionAdapter_" + COUNTER.incrementAndGet();
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

			// <init>(JSFunction fn)
			MethodVisitor initMv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Lhope/magic/js/runtime/JSFunction;)V", null, null);
			initMv.visitCode();
			initMv.visitVarInsn(ALOAD, 0);
			initMv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
			initMv.visitVarInsn(ALOAD, 0);
			initMv.visitVarInsn(ALOAD, 1);
			initMv.visitFieldInsn(PUTFIELD, className, "fn", "Lhope/magic/js/runtime/JSFunction;");
			initMv.visitInsn(RETURN);
			initMv.visitMaxs(0, 0);
			initMv.visitEnd();

			// SAM method
			emitAdapterSAMMethod(cw, className, sam);

			// Object methods
			emitAdapterObjectMethods(cw, className, "JSFunctionAdapter[" + targetType.getSimpleName() + "]");

			cw.visitEnd();
			byte[]         bytes    = cw.toByteArray();
			Class<?>       genClass = Magic.defineClass(targetType.getClassLoader() != null ? targetType.getClassLoader() : MagicJIT.class.getClassLoader(), bytes);
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
		if (paramTypes.length == 0) {
			mv.visitMethodInsn(INVOKEINTERFACE, "hope/magic/js/runtime/JSFunction", "call0", "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;)Ljava/lang/Object;", true);
		} else if (paramTypes.length == 1) {
			emitLoadAndBox(mv, paramTypes[0], 1);
			mv.visitMethodInsn(INVOKEINTERFACE, "hope/magic/js/runtime/JSFunction", "call1", "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
		} else if (paramTypes.length == 2) {
			emitLoadAndBox(mv, paramTypes[0], 1);
			int slot2 = (paramTypes[0] == long.class || paramTypes[0] == double.class) ? 3 : 2;
			emitLoadAndBox(mv, paramTypes[1], slot2);
			mv.visitMethodInsn(INVOKEINTERFACE, "hope/magic/js/runtime/JSFunction", "call2", "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
		} else if (paramTypes.length == 3) {
			int slot = 1;
			for (int i = 0; i < 3; i++) {
				emitLoadAndBox(mv, paramTypes[i], slot);
				slot += (paramTypes[i] == long.class || paramTypes[i] == double.class) ? 2 : 1;
			}
			mv.visitMethodInsn(INVOKEINTERFACE, "hope/magic/js/runtime/JSFunction", "call3", "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
		} else if (paramTypes.length == 4) {
			int slot = 1;
			for (int i = 0; i < 4; i++) {
				emitLoadAndBox(mv, paramTypes[i], slot);
				slot += (paramTypes[i] == long.class || paramTypes[i] == double.class) ? 2 : 1;
			}
			mv.visitMethodInsn(INVOKEINTERFACE, "hope/magic/js/runtime/JSFunction", "call4", "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
		} else {
			// >4 参数：构建 Object[] args
			pushInt(mv, paramTypes.length);
			mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");

			int localSlot = 1;
			for (int i = 0; i < paramTypes.length; i++) {
				Class<?> pt = paramTypes[i];
				mv.visitInsn(DUP);
				pushInt(mv, i);
				emitLoadAndBox(mv, pt, localSlot);
				localSlot += (pt == long.class || pt == double.class) ? 2 : 1;
				mv.visitInsn(AASTORE);
			}
			mv.visitMethodInsn(INVOKEINTERFACE, "hope/magic/js/runtime/JSFunction", "call", "(Lhope/magic/js/runtime/JSContext;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", true);
		}

		// 返回值拆箱 / 转换
		emitAdapterReturn(mv, retType);
	}

	private static void emitAdapterSAMMethod(ClassWriter cw, String className, Method sam) {
		String     methodName = sam.getName();
		String     methodDesc = Type.getMethodDescriptor(sam);
		Class<?>[] paramTypes = sam.getParameterTypes();
		Class<?>   retType    = sam.getReturnType();

		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, methodName, methodDesc, null, getExceptionNames(sam));
		mv.visitCode();

		if (isPrimitiveSAM(paramTypes, retType)) {
			// Primitive 特化直调 (Zero-Allocation, 无装箱)
			emitPrimitiveSAMMethodCall(mv, className, paramTypes, retType);
		} else {
			// 1. 获取 fn
			mv.visitVarInsn(ALOAD, 0);
			mv.visitFieldInsn(GETFIELD, className, "fn", "Lhope/magic/js/runtime/JSFunction;");

			// 2. 参数压栈: cx, thisObj
			mv.visitInsn(ACONST_NULL); // cx
			mv.visitInsn(ACONST_NULL); // thisObj

			// 3. Zero-Allocation 特化直调 (call0, call1, call2, call3, call4)
			emitOptimizedJSFunctionCall(mv, paramTypes, retType);
		}

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
	                                               Class<?> retType) {
		// 1. 获取 fn
		mv.visitVarInsn(ALOAD, 0);
		mv.visitFieldInsn(GETFIELD, className, "fn", "Lhope/magic/js/runtime/JSFunction;");

		// 2. 参数压栈: cx (null)
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

		// 5. 将返回的 double 转为 retType 并返回
		emitPrimitiveReturn(mv, retType);
	}

	private static String getPrimCallDesc(int arity) {
		return switch (arity) {
			case 0 -> "(Lhope/magic/js/runtime/JSContext;)D";
			case 1 -> "(Lhope/magic/js/runtime/JSContext;D)D";
			case 2 -> "(Lhope/magic/js/runtime/JSContext;DD)D";
			case 3 -> "(Lhope/magic/js/runtime/JSContext;DDD)D";
			case 4 -> "(Lhope/magic/js/runtime/JSContext;DDDD)D";
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

	private static void emitPrimitiveReturn(MethodVisitor mv, Class<?> retType) {
		if (retType == void.class) {
			mv.visitInsn(POP2);
			mv.visitInsn(RETURN);
		} else if (retType == double.class) {
			mv.visitInsn(DRETURN);
		} else if (retType == float.class) {
			mv.visitInsn(D2F);
			mv.visitInsn(FRETURN);
		} else if (retType == long.class) {
			mv.visitInsn(D2L);
			mv.visitInsn(LRETURN);
		} else if (retType == int.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toInt", "(D)I", false);
			mv.visitInsn(IRETURN);
		} else if (retType == short.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toInt", "(D)I", false);
			mv.visitInsn(I2S);
			mv.visitInsn(IRETURN);
		} else if (retType == byte.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toInt", "(D)I", false);
			mv.visitInsn(I2B);
			mv.visitInsn(IRETURN);
		} else if (retType == char.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toInt", "(D)I", false);
			mv.visitInsn(I2C);
			mv.visitInsn(IRETURN);
		} else if (retType == boolean.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toBoolean", "(D)Z", false);
			mv.visitInsn(IRETURN);
		} else {
			throw new IllegalArgumentException("Not a primitive return type: " + retType);
		}
	}

	private static Constructor<?> createObjectAdapterConstructor(Class<?> targetType) {
		try {
			Magic.install();
			ClassWriter cw          = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
			String      className   = "hope/magic/gen/JSObjectAdapter_" + COUNTER.incrementAndGet();
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

			// <init>(JSObject jsObj)
			MethodVisitor initMv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Lhope/magic/js/runtime/JSObject;)V", null, null);
			initMv.visitCode();
			initMv.visitVarInsn(ALOAD, 0);
			initMv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
			initMv.visitVarInsn(ALOAD, 0);
			initMv.visitVarInsn(ALOAD, 1);
			initMv.visitFieldInsn(PUTFIELD, className, "jsObj", "Lhope/magic/js/runtime/JSObject;");
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
			Class<?>       genClass = Magic.defineClass(targetType.getClassLoader() != null ? targetType.getClassLoader() : MagicJIT.class.getClassLoader(), bytes);
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

		// 1. Object member = this.jsObj.get(propId);
		int propId = SymbolTable.id(methodName);
		mv.visitVarInsn(ALOAD, 0);
		mv.visitFieldInsn(GETFIELD, className, "jsObj", "Lhope/magic/js/runtime/JSObject;");
		pushInt(mv, propId);
		mv.visitMethodInsn(INVOKEVIRTUAL, "hope/magic/js/runtime/JSObject", "get", "(I)Ljava/lang/Object;", false);

		// Calculate slot for member
		int memberSlot = calcTotalParamSlots(paramTypes) + 1;
		mv.visitVarInsn(ASTORE, memberSlot);

		// 2. if (member instanceof JSFunction)
		mv.visitVarInsn(ALOAD, memberSlot);
		mv.visitTypeInsn(INSTANCEOF, "hope/magic/js/runtime/JSFunction");
		org.objectweb.asm.Label notFnLabel = new org.objectweb.asm.Label();
		mv.visitJumpInsn(IFEQ, notFnLabel);

		// member.call*(null, this.jsObj, ...)
		mv.visitVarInsn(ALOAD, memberSlot);
		mv.visitTypeInsn(CHECKCAST, "hope/magic/js/runtime/JSFunction");
		mv.visitInsn(ACONST_NULL); // cx
		mv.visitVarInsn(ALOAD, 0);
		mv.visitFieldInsn(GETFIELD, className, "jsObj", "Lhope/magic/js/runtime/JSObject;"); // thisObj = jsObj
		emitOptimizedJSFunctionCall(mv, paramTypes, retType);

		// 3. else if (member != JSUndefined.INSTANCE)
		mv.visitLabel(notFnLabel);
		mv.visitVarInsn(ALOAD, memberSlot);
		mv.visitFieldInsn(GETSTATIC, "hope/magic/js/runtime/JSUndefined", "INSTANCE", "Lhope/magic/js/runtime/JSUndefined;");
		org.objectweb.asm.Label undefLabel = new org.objectweb.asm.Label();
		mv.visitJumpInsn(IF_ACMPEQ, undefLabel);

		mv.visitVarInsn(ALOAD, memberSlot);
		emitAdapterReturn(mv, retType);

		// 4. Default return
		mv.visitLabel(undefLabel);
		emitDefaultReturn(mv, retType);

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

	private static void emitAdapterReturn(MethodVisitor mv, Class<?> retType) {
		if (retType == void.class) {
			mv.visitInsn(POP);
			mv.visitInsn(RETURN);
		} else if (retType == int.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toInt", "(Ljava/lang/Object;)I", false);
			mv.visitInsn(IRETURN);
		} else if (retType == long.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toLong", "(Ljava/lang/Object;)J", false);
			mv.visitInsn(LRETURN);
		} else if (retType == double.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toDouble", "(Ljava/lang/Object;)D", false);
			mv.visitInsn(DRETURN);
		} else if (retType == float.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toDouble", "(Ljava/lang/Object;)D", false);
			mv.visitInsn(D2F);
			mv.visitInsn(FRETURN);
		} else if (retType == boolean.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "isTruthy", "(Ljava/lang/Object;)Z", false);
			mv.visitInsn(IRETURN);
		} else if (retType == short.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toInt", "(Ljava/lang/Object;)I", false);
			mv.visitInsn(I2S);
			mv.visitInsn(IRETURN);
		} else if (retType == byte.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toInt", "(Ljava/lang/Object;)I", false);
			mv.visitInsn(I2B);
			mv.visitInsn(IRETURN);
		} else if (retType == char.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toChar", "(Ljava/lang/Object;)C", false);
			mv.visitInsn(IRETURN);
		} else if (retType == String.class) {
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "toStr", "(Ljava/lang/Object;)Ljava/lang/String;", false);
			mv.visitInsn(ARETURN);
		} else {
			mv.visitLdcInsn(Type.getType(retType));
			mv.visitMethodInsn(INVOKESTATIC, IN_JSOps, "castValue", "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;", false);
			mv.visitTypeInsn(CHECKCAST, Type.getInternalName(retType));
			mv.visitInsn(ARETURN);
		}
	}

	private static void emitDefaultReturn(MethodVisitor mv, Class<?> retType) {
		if (retType == void.class) {
			mv.visitInsn(RETURN);
		} else if (retType == int.class || retType == boolean.class || retType == byte.class || retType == short.class || retType == char.class) {
			mv.visitInsn(ICONST_0);
			mv.visitInsn(IRETURN);
		} else if (retType == long.class) {
			mv.visitInsn(LCONST_0);
			mv.visitInsn(LRETURN);
		} else if (retType == float.class) {
			mv.visitInsn(FCONST_0);
			mv.visitInsn(FRETURN);
		} else if (retType == double.class) {
			mv.visitInsn(DCONST_0);
			mv.visitInsn(DRETURN);
		} else {
			mv.visitInsn(ACONST_NULL);
			mv.visitInsn(ARETURN);
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
		org.objectweb.asm.Label notEq = new org.objectweb.asm.Label();
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
			Object[] safeArgs = methodArgs == null ? new Object[0] : methodArgs;
			Object   result   = fn.call(null, null, safeArgs);
			Class<?> retType  = method.getReturnType();
			if (retType == void.class) return null;
			return JSOps.castValue(result, retType);
		});
	}

	public static Object createProxyObjectAdapter(Class<?> targetType, JSObject jsObj) {
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
				Object[] safeArgs = methodArgs == null ? new Object[0] : methodArgs;
				Object   result   = fn.call(null, jsObj, safeArgs);
				Class<?> retType  = method.getReturnType();
				if (retType == void.class) return null;
				return JSOps.castValue(result, retType);
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
