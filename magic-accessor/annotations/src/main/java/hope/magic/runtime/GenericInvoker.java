package hope.magic.runtime;

import jdk.internal.vm.annotation.*;
import java.lang.invoke.MethodHandle;

public final class GenericInvoker extends MagicInvoker {
	@Stable
	private final MethodHandle spreader;

	public GenericInvoker(MethodHandle spreader) {
		this.spreader = spreader;
	}

	@Hidden
	@ForceInline
	@Override
	public Object invoke(Object target, Object[] args) throws Throwable {
		return spreader.invoke(target, args == null ? EMPTY_ARGS : args);
	}
}
