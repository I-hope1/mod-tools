package modtools.annotations.unsafe;

import com.sun.tools.javac.util.Context;

import java.util.HashMap;
import java.util.function.Supplier;

import static modtools.annotations.HopeReflect.getAccess;

/**
 * 直接改写 javac {@link Context} 里已注册组件的工具。
 * <p>
 * javac 的组件都在自己的构造函数里 {@code context.put(key, this)} 完成自我注册，
 * 想换掉其中一个，唯一的办法是把它在 {@code Context.ht} 里的条目摘掉，
 * 再让 {@code instance(context)} 重建，或者直接塞一个新实例进去。
 */
public final class ContextPatcher {
	private ContextPatcher() {}

	/**
	 * 摘掉 {@code cls} 对应的 Context 键。
	 *
	 * @param newVal 为 {@code null} 时只删不建；否则用它的返回值作为新实例。
	 */
	public static <T> void removeKey(Context context, Class<T> cls, Supplier<T> newVal) {
		removeKey(context, keyNameOf(cls), cls, newVal);
	}

	public static <T> void removeKey(Context context, String keyName, Class<T> cls, Supplier<T> newVal) {
		Context.Key<T>                  key = getAccess(cls, null, keyName);
		HashMap<Context.Key<?>, Object> ht  = getAccess(Context.class, context, "ht");
		ht.remove(key);
		if (newVal == null) return;

		T value = newVal.get();
		// 组件在构造函数里会把自己注册进同一个键（Resolve 这类），别把它覆盖掉
		if (!ht.containsKey(key)) ht.put(key, value);
	}

	/**
	 * 由组件类名推出它在 {@code Context} 里的键名。
	 * <p>
	 * 规则和 javac 的写法一致：{@code Resolve -> resolveKey}、{@code Log.DeferredDiagnosticHandler -> logDeferredDiagnosticHandlerKey}、
	 * {@code JCCompilationUnit -> compilationUnitKey}。
	 */
	public static String keyNameOf(Class<?> cls) {
		String s = (cls.isMemberClass() ? cls.getNestHost().getSimpleName() : "") + cls.getSimpleName();
		if (s.startsWith("JC")) s = s.substring(2);
		return Character.toLowerCase(s.charAt(0)) + s.substring(1) + "Key";
	}

	/** 跑一段“锦上添花”的补丁：失败就算了，绝不能让它影响编译。 */
	public static void runIgnoredException(Runnable r) {
		try {
			r.run();
		} catch (Throwable ignored) { }
	}
}
