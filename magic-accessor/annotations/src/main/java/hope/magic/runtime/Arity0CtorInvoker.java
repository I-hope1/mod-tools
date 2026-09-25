package hope.magic.runtime;

import jdk.internal.vm.annotation.*;
import java.lang.invoke.MethodHandle;

public final class Arity0CtorInvoker extends MagicInvoker {
	@Stable
	private final MethodHandle mh;

	public Arity0CtorInvoker(MethodHandle mh) {
		this.mh = mh;
	}

	@Override
	public Object invoke(Object target, Object[] args) throws Throwable {
		return mh.invokeExact();
	}

	@Override
	public Object invoke0(Object target) throws Throwable {
		return mh.invokeExact();
	}

	@Override
	public Object newInstance(Object[] args) throws Throwable {
		return mh.invokeExact();
	}

	@Override
	public Object newInstance0() throws Throwable {
		return mh.invokeExact();
	}
}
