package hope.magic.runtime;

import jdk.internal.vm.annotation.*;

/**
 * Bootstrap 级别统一构造器调用分发接口。
 * <p>用于配合 Hidden Class (隐式类) 与 Nestmate (同巢类) 实现无泄漏、零装箱的极速对象实例化直调。</p>
 */
public interface MagicConstructorInvoker {
	Object[] EMPTY_ARGS = new Object[0];

	Object newInstance(Object[] args) throws Throwable;

	@Hidden
	@ForceInline
	default Object newInstance0() throws Throwable {
		return newInstance(EMPTY_ARGS);
	}
	@Hidden
	@ForceInline
	default Object newInstance1(Object a0) throws Throwable {
		return newInstance(new Object[]{a0});
	}
	@Hidden
	@ForceInline
	default Object newInstance2(Object a0, Object a1) throws Throwable {
		return newInstance(new Object[]{a0, a1});
	}
	@Hidden
	@ForceInline
	default Object newInstance3(Object a0, Object a1, Object a2) throws Throwable {
		return newInstance(new Object[]{a0, a1, a2});
	}
}
