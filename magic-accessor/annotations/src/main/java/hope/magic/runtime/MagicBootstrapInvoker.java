package hope.magic.runtime;

/**
 * Bootstrap 级别统一分发接口。
 * <p>用于配合 Hidden Class (隐式类) 实现无泄漏、零装箱的 MemberName 常量折叠直调。</p>
 */
public interface MagicBootstrapInvoker {
	Object invoke(Object target, Object[] args) throws Throwable;
	Object invoke2(Object target, Object a, Object b) throws Throwable;
	int invokeInt2(Object target, int a, int b) throws Throwable;
}
