package hope.magic.runtime;

import jdk.internal.vm.annotation.*;
import java.lang.invoke.MethodHandle;

public final class Arity3Invoker extends MagicInvoker {
	@Stable
	private final MethodHandle mh;
	@Stable
	private final MethodHandle spreader;
	@Stable
	private final MethodHandle rawIntMh;
	@Stable
	private final MethodHandle rawDoubleMh;

	public Arity3Invoker(MethodHandle mh, MethodHandle spreader, MethodHandle rawIntMh, MethodHandle rawDoubleMh) {
		this.mh = mh;
		this.spreader = spreader != null ? spreader : mh.asSpreader(Object[].class, 3);
		this.rawIntMh = rawIntMh;
		this.rawDoubleMh = rawDoubleMh;
	}

	public Arity3Invoker(MethodHandle mh) {
		this(mh, null, null, null);
	}

	@Override
	public Object invoke(Object target, Object[] args) throws Throwable {
		if (args != null && args.length == 3) return invoke3(target, args[0], args[1], args[2]);
		return spreader.invoke(target, args == null ? EMPTY_ARGS : args);
	}

	@Override
	public Object invoke3(Object target, Object a0, Object a1, Object a2) throws Throwable {
		if (a0 instanceof Number n0 && a1 instanceof Number n1 && a2 instanceof Number n2) {
			if (rawIntMh != null) {
				return (int) rawIntMh.invokeExact(target, n0.intValue(), n1.intValue(), n2.intValue());
			}
			if (rawDoubleMh != null) {
				return (double) rawDoubleMh.invokeExact(target, n0.doubleValue(), n1.doubleValue(), n2.doubleValue());
			}
		}
		return (Object) mh.invokeExact(target, a0, a1, a2);
	}

	@Override
	public int invokeInt3(Object target, int a0, int a1, int a2) throws Throwable {
		if (rawIntMh != null) return (int) rawIntMh.invokeExact(target, a0, a1, a2);
		return ((Number) invoke3(target, a0, a1, a2)).intValue();
	}

	@Override
	public double invokeDouble3(Object target, double a0, double a1, double a2) throws Throwable {
		if (rawDoubleMh != null) return (double) rawDoubleMh.invokeExact(target, a0, a1, a2);
		return ((Number) invoke3(target, a0, a1, a2)).doubleValue();
	}
}
