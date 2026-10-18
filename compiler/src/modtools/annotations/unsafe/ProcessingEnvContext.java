package modtools.annotations.unsafe;

import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.util.Context;

import javax.annotation.processing.ProcessingEnvironment;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

/**
 * 从 {@link ProcessingEnvironment} 里挖出 javac 的 {@link Context}。
 * <p>
 * 多数情况下参数就是 {@link JavacProcessingEnvironment}，直接 {@code getContext()} 就完了。
 * 麻烦的是 IDE 和部分构建工具会套一层：要么是动态代理（IDEA 的增量编译），要么是自带
 * {@code delegate} 字段的包装类。这里把这几种情况都拆到底。
 * <p>
 * 拆不出来时抛异常而不是返回 null —— 没有 Context 后面什么都做不了，早点炸比半路 NPE 好查。
 */
public final class ProcessingEnvContext {
	/** 包装层数上限，防止代理和它的 handler 互相引用把栈打爆 */
	private static final int MAX_DEPTH = 8;

	private ProcessingEnvContext() {}

	public static Context of(ProcessingEnvironment env) {
		Context context = find(env, 0);
		if (context != null) return context;
		throw new IllegalArgumentException("can't find javac Context in " + describe(env));
	}

	/** @return 挖到的 Context；挖不到返回 {@code null} */
	private static Context find(ProcessingEnvironment env, int depth) {
		if (env == null || depth > MAX_DEPTH) return null;
		if (env instanceof JavacProcessingEnvironment javac) return javac.getContext();

		// 代理：真正的对象在 InvocationHandler 的某个字段里
		Object holder = Proxy.isProxyClass(env.getClass()) ? Proxy.getInvocationHandler(env) : env;
		return findInFields(holder, depth + 1);
	}

	private static Context findInFields(Object holder, int depth) {
		for (Field field : holder.getClass().getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers())) continue;

			Object value;
			try {
				field.setAccessible(true);
				value = field.get(holder);
			} catch (Throwable _) {
				// 读不到就跳过：不可 open 的包会抛 InaccessibleObjectException，那不是 RuntimeException 的子类
				continue;
			}
			if (!(value instanceof ProcessingEnvironment inner)) continue;

			Context context = find(inner, depth);
			if (context != null) return context;
		}
		return null;
	}

	private static String describe(ProcessingEnvironment env) {
		if (env == null) return "null";
		Class<?> cls = env.getClass();
		StringBuilder sb = new StringBuilder(cls.getName());
		if (Proxy.isProxyClass(cls)) {
			sb.append(" (proxy of ").append(Proxy.getInvocationHandler(env).getClass().getName()).append(')');
		}
		sb.append(", fields:");
		for (Field field : cls.getDeclaredFields()) {
			sb.append(' ').append(field.getName());
		}
		return sb.toString();
	}
}
