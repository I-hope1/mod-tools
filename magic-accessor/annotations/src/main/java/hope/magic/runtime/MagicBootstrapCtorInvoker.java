package hope.magic.runtime;

/**
 * Bootstrap 级别统一构造器调用分发接口。
 * <p>用于配合 Hidden Class (隐式类) 与 Nestmate (同巢类) 实现无泄漏、零装箱的极速对象实例化直调。</p>
 */
public interface MagicBootstrapCtorInvoker {
	Object newInstance(Object[] args) throws Throwable;

	default Object newInstance0() throws Throwable {
		return newInstance(new Object[0]);
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
