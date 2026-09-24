package hope.magic.runtime;

import jdk.internal.vm.annotation.*;

/**
 * Bootstrap 级别统一方法调用分发接口。
 * <p>用于配合 Hidden Class (隐式类) 与 Nestmate (同巢类) 实现无泄漏、零装箱的极速直调。</p>
 */
public interface MagicBootstrapInvoker {
	Object invoke(Object target, Object[] args) throws Throwable;

	@Hidden
	@ForceInline
	default Object invoke0(Object target) throws Throwable {
		return invoke(target, new Object[0]);
	}
	@Hidden
	@ForceInline
	default Object invoke1(Object target, Object a0) throws Throwable {
		return invoke(target, new Object[]{a0});
	}
	@Hidden
	@ForceInline
	default Object invoke2(Object target, Object a0, Object a1) throws Throwable {
		return invoke(target, new Object[]{a0, a1});
	}
	@Hidden
	@ForceInline
	default Object invoke3(Object target, Object a0, Object a1, Object a2) throws Throwable {
		return invoke(target, new Object[]{a0, a1, a2});
	}

	// --- Primitive Fast-Path (Zero-Boxing Direct Call) ---
	@Hidden
	@ForceInline
	default int invokeInt0(Object target) throws Throwable {
		return ((Number) invoke0(target)).intValue();
	}
	@Hidden
	@ForceInline
	default int invokeInt1(Object target, int a0) throws Throwable {
		return ((Number) invoke1(target, a0)).intValue();
	}
	@Hidden
	@ForceInline
	default int invokeInt2(Object target, int a0, int a1) throws Throwable {
		return ((Number) invoke2(target, a0, a1)).intValue();
	}
	@Hidden
	@ForceInline
	default int invokeInt3(Object target, int a0, int a1, int a2) throws Throwable {
		return ((Number) invoke3(target, a0, a1, a2)).intValue();
	}

	@Hidden
	@ForceInline
	default boolean invokeBoolean0(Object target) throws Throwable {
		Object res = invoke0(target);
		return res instanceof Boolean b ? b : (res instanceof Number n && n.intValue() != 0);
	}
	@Hidden
	@ForceInline
	default boolean invokeBoolean1(Object target, Object a0) throws Throwable {
		Object res = invoke1(target, a0);
		return res instanceof Boolean b ? b : (res instanceof Number n && n.intValue() != 0);
	}
	@Hidden
	@ForceInline
	default boolean invokeBoolean2(Object target, Object a0, Object a1) throws Throwable {
		Object res = invoke2(target, a0, a1);
		return res instanceof Boolean b ? b : (res instanceof Number n && n.intValue() != 0);
	}

	@Hidden
	@ForceInline
	default double invokeDouble0(Object target) throws Throwable {
		return ((Number) invoke0(target)).doubleValue();
	}
	@Hidden
	@ForceInline
	default double invokeDouble1(Object target, double a0) throws Throwable {
		return ((Number) invoke1(target, a0)).doubleValue();
	}
	@Hidden
	@ForceInline
	default double invokeDouble2(Object target, double a0, double a1) throws Throwable {
		return ((Number) invoke2(target, a0, a1)).doubleValue();
	}
	@Hidden
	@ForceInline
	default double invokeDouble3(Object target, double a0, double a1, double a2) throws Throwable {
		return ((Number) invoke3(target, a0, a1, a2)).doubleValue();
	}

	@Hidden
	@ForceInline
	default long invokeLong0(Object target) throws Throwable {
		return ((Number) invoke0(target)).longValue();
	}
	@Hidden
	@ForceInline
	default long invokeLong1(Object target, long a0) throws Throwable {
		return ((Number) invoke1(target, a0)).longValue();
	}
	@Hidden
	@ForceInline
	default long invokeLong2(Object target, long a0, long a1) throws Throwable {
		return ((Number) invoke2(target, a0, a1)).longValue();
	}
}
