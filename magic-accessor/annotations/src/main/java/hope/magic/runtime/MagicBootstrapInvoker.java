package hope.magic.runtime;

/**
 * Bootstrap 级别统一方法调用分发接口。
 * <p>用于配合 Hidden Class (隐式类) 与 Nestmate (同巢类) 实现无泄漏、零装箱的极速直调。</p>
 */
public interface MagicBootstrapInvoker {
	Object invoke(Object target, Object[] args) throws Throwable;

	default Object invoke0(Object target) throws Throwable {
		return invoke(target, new Object[0]);
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
