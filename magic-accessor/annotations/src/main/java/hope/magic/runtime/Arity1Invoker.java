package hope.magic.runtime;

import jdk.internal.vm.annotation.*;
import java.lang.invoke.MethodHandle;

public final class Arity1Invoker extends MagicInvoker {
	@Stable
	private final MethodHandle mh;
	@Stable
	private final MethodHandle spreader;
	@Stable
	private final MethodHandle rawIntMh;
	@Stable
	private final MethodHandle rawLongMh;
	@Stable
	private final MethodHandle rawDoubleMh;
	@Stable
	private final MethodHandle rawBooleanMh;

	public Arity1Invoker(MethodHandle mh, MethodHandle spreader, MethodHandle rawIntMh,
	                     MethodHandle rawLongMh, MethodHandle rawDoubleMh, MethodHandle rawBooleanMh) {
		this.mh = mh;
		this.spreader = spreader != null ? spreader : mh.asSpreader(Object[].class, 1);
		this.rawIntMh = rawIntMh;
		this.rawLongMh = rawLongMh;
		this.rawDoubleMh = rawDoubleMh;
		this.rawBooleanMh = rawBooleanMh;
	}

	public Arity1Invoker(MethodHandle mh) {
		this(mh, null, null, null, null, null);
	}

	@Hidden
	@ForceInline
	@Override
	public Object invoke(Object target, Object[] args) throws Throwable {
		if (args != null && args.length == 1) return invoke1(target, args[0]);
		return spreader.invoke(target, args == null ? EMPTY_ARGS : args);
	}

	@Hidden
	@ForceInline
	@Override
	public Object invoke1(Object target, Object a0) throws Throwable {
		if (a0 instanceof Number n0) {
			if (rawIntMh != null) return (int) rawIntMh.invokeExact(target, n0.intValue());
			if (rawDoubleMh != null) return (double) rawDoubleMh.invokeExact(target, n0.doubleValue());
			if (rawLongMh != null) return (long) rawLongMh.invokeExact(target, n0.longValue());
		}
		return (Object) mh.invokeExact(target, a0);
	}

	@Hidden
	@ForceInline
	@Override
	public int invokeInt1(Object target, int a0) throws Throwable {
		if (rawIntMh != null) return (int) rawIntMh.invokeExact(target, a0);
		return ((Number) invoke1(target, a0)).intValue();
	}

	@Hidden
	@ForceInline
	@Override
	public boolean invokeBoolean1(Object target, Object a0) throws Throwable {
		if (rawBooleanMh != null) return (boolean) rawBooleanMh.invokeExact(target, a0);
		Object res = invoke1(target, a0);
		return res instanceof Boolean ? (Boolean) res : (res instanceof Number && ((Number) res).intValue() != 0);
	}

	@Hidden
	@ForceInline
	@Override
	public double invokeDouble1(Object target, double a0) throws Throwable {
		if (rawDoubleMh != null) return (double) rawDoubleMh.invokeExact(target, a0);
		return ((Number) invoke1(target, a0)).doubleValue();
	}

	@Hidden
	@ForceInline
	@Override
	public long invokeLong1(Object target, long a0) throws Throwable {
		if (rawLongMh != null) return (long) rawLongMh.invokeExact(target, a0);
		return ((Number) invoke1(target, a0)).longValue();
	}
}
