package hope.magic.runtime;

import jdk.internal.vm.annotation.*;
import java.lang.invoke.MethodHandle;

public final class Arity2Invoker extends MagicInvoker {
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

	public Arity2Invoker(MethodHandle mh, MethodHandle spreader, MethodHandle rawIntMh,
	                     MethodHandle rawLongMh, MethodHandle rawDoubleMh, MethodHandle rawBooleanMh) {
		this.mh = mh;
		this.spreader = spreader != null ? spreader : mh.asSpreader(Object[].class, 2);
		this.rawIntMh = rawIntMh;
		this.rawLongMh = rawLongMh;
		this.rawDoubleMh = rawDoubleMh;
		this.rawBooleanMh = rawBooleanMh;
	}

	public Arity2Invoker(MethodHandle mh) {
		this(mh, null, null, null, null, null);
	}

	@Hidden
	@ForceInline
	@Override
	public Object invoke(Object target, Object[] args) throws Throwable {
		if (args != null && args.length == 2) return invoke2(target, args[0], args[1]);
		return spreader.invoke(target, args == null ? EMPTY_ARGS : args);
	}

	@Hidden
	@ForceInline
	@Override
	public Object invoke2(Object target, Object a0, Object a1) throws Throwable {
		if (a0 instanceof Number n0 && a1 instanceof Number n1) {
			if (rawIntMh != null) return (int) rawIntMh.invokeExact(target, n0.intValue(), n1.intValue());
			if (rawDoubleMh != null) return (double) rawDoubleMh.invokeExact(target, n0.doubleValue(), n1.doubleValue());
			if (rawLongMh != null) return (long) rawLongMh.invokeExact(target, n0.longValue(), n1.longValue());
			if (rawBooleanMh != null) return (boolean) rawBooleanMh.invokeExact(target, n0, n1);
		}
		return (Object) mh.invokeExact(target, a0, a1);
	}

	@Hidden
	@ForceInline
	@Override
	public int invokeInt2(Object target, int a0, int a1) throws Throwable {
		if (rawIntMh != null) return (int) rawIntMh.invokeExact(target, a0, a1);
		return ((Number) invoke2(target, a0, a1)).intValue();
	}

	@Hidden
	@ForceInline
	@Override
	public boolean invokeBoolean2(Object target, Object a0, Object a1) throws Throwable {
		if (rawBooleanMh != null) return (boolean) rawBooleanMh.invokeExact(target, a0, a1);
		Object res = invoke2(target, a0, a1);
		return res instanceof Boolean ? (Boolean) res : (res instanceof Number && ((Number) res).intValue() != 0);
	}

	@Hidden
	@ForceInline
	@Override
	public long invokeLong2(Object target, long a0, long a1) throws Throwable {
		if (rawLongMh != null) return (long) rawLongMh.invokeExact(target, a0, a1);
		return ((Number) invoke2(target, a0, a1)).longValue();
	}

	@Hidden
	@ForceInline
	@Override
	public double invokeDouble2(Object target, double a0, double a1) throws Throwable {
		if (rawDoubleMh != null) return (double) rawDoubleMh.invokeExact(target, a0, a1);
		return ((Number) invoke2(target, a0, a1)).doubleValue();
	}
}
