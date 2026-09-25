package hope.magic.runtime;

import jdk.internal.vm.annotation.*;
import java.lang.invoke.MethodHandle;

public final class Arity3CtorInvoker extends MagicInvoker {
	@Stable
	private final MethodHandle mh;
	@Stable
	private final MethodHandle spreader;

	public Arity3CtorInvoker(MethodHandle mh, MethodHandle spreader) {
		this.mh = mh;
		this.spreader = spreader != null ? spreader : mh.asSpreader(Object[].class, 3);
	}

	public Arity3CtorInvoker(MethodHandle mh) {
		this(mh, null);
	}

	@Hidden
	@ForceInline
	@Override
	public Object invoke(Object target, Object[] args) throws Throwable {
		if (args != null && args.length == 3) {
			return (Object) mh.invokeExact(args[0], args[1], args[2]);
		}
		return spreader.invoke(args == null ? EMPTY_ARGS : args);
	}

	@Hidden
	@ForceInline
	@Override
	public Object invoke3(Object target, Object a0, Object a1, Object a2) throws Throwable {
		return (Object) mh.invokeExact(a0, a1, a2);
	}

	@Hidden
	@ForceInline
	@Override
	public Object newInstance(Object[] args) throws Throwable {
		if (args != null && args.length == 3) {
			return (Object) mh.invokeExact(args[0], args[1], args[2]);
		}
		return spreader.invoke(args == null ? EMPTY_ARGS : args);
	}

	@Hidden
	@ForceInline
	@Override
	public Object newInstance3(Object a0, Object a1, Object a2) throws Throwable {
		return (Object) mh.invokeExact(a0, a1, a2);
	}
}
