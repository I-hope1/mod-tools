package hope.magic.runtime;

import jdk.internal.vm.annotation.*;
import java.lang.invoke.MethodHandle;

public final class GenericCtorInvoker extends MagicInvoker {
	@Stable
	private final MethodHandle spreader;

	public GenericCtorInvoker(MethodHandle spreader) {
		this.spreader = spreader;
	}

	@Override
	public Object invoke(Object target, Object[] args) throws Throwable {
		return newInstance(args);
	}

	@Override
	public Object newInstance(Object[] args) throws Throwable {
		return spreader.invoke(args == null ? EMPTY_ARGS : args);
	}
}
