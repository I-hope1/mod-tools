package hope.magic.runtime;

import jdk.internal.vm.annotation.*;
import java.lang.invoke.MethodHandle;

public final class Arity1CtorInvoker extends MagicInvoker {
	@Stable
	private final MethodHandle mh;
	@Stable
	private final MethodHandle spreader;
	@Stable
	private final MethodHandle rawIntCtorMh;

	public Arity1CtorInvoker(MethodHandle mh, MethodHandle spreader, MethodHandle rawIntCtorMh) {
		this.mh = mh;
		this.spreader = spreader != null ? spreader : mh.asSpreader(Object[].class, 1);
		this.rawIntCtorMh = rawIntCtorMh;
	}

	public Arity1CtorInvoker(MethodHandle mh) {
		this(mh, null, null);
	}

	@Hidden
	@ForceInline
	@Override
	public Object invoke(Object target, Object[] args) throws Throwable {
		if (args != null && args.length == 1) {
			Object a0 = args[0];
			if (rawIntCtorMh != null && a0 instanceof Number n0) {
				return rawIntCtorMh.invokeExact(n0.intValue());
			}
			return mh.invokeExact(a0);
		}
		return spreader.invoke(args == null ? EMPTY_ARGS : args);
	}

	@Hidden
	@ForceInline
	@Override
	public Object invoke1(Object target, Object a0) throws Throwable {
		if (rawIntCtorMh != null && a0 instanceof Number n0) {
			return rawIntCtorMh.invokeExact(n0.intValue());
		}
		return mh.invokeExact(a0);
	}

	@Hidden
	@ForceInline
	@Override
	public Object newInstance(Object[] args) throws Throwable {
		if (args != null && args.length == 1) {
			Object a0 = args[0];
			if (rawIntCtorMh != null && a0 instanceof Number n0) {
				return rawIntCtorMh.invokeExact(n0.intValue());
			}
			return mh.invokeExact(a0);
		}
		return spreader.invoke(args == null ? EMPTY_ARGS : args);
	}

	@Hidden
	@ForceInline
	@Override
	public Object newInstance1(Object a0) throws Throwable {
		if (rawIntCtorMh != null && a0 instanceof Number n0) {
			return rawIntCtorMh.invokeExact(n0.intValue());
		}
		return mh.invokeExact(a0);
	}
}
