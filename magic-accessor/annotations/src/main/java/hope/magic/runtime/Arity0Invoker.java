package hope.magic.runtime;

import jdk.internal.vm.annotation.*;
import java.lang.invoke.MethodHandle;

public final class Arity0Invoker extends MagicInvoker {
	@Stable
	private final MethodHandle mh;
	@Stable
	private final MethodHandle rawIntMh;
	@Stable
	private final MethodHandle rawLongMh;
	@Stable
	private final MethodHandle rawDoubleMh;
	@Stable
	private final MethodHandle rawBooleanMh;

	public Arity0Invoker(MethodHandle mh, MethodHandle rawIntMh, MethodHandle rawLongMh,
	                     MethodHandle rawDoubleMh, MethodHandle rawBooleanMh) {
		this.mh = mh;
		this.rawIntMh = rawIntMh;
		this.rawLongMh = rawLongMh;
		this.rawDoubleMh = rawDoubleMh;
		this.rawBooleanMh = rawBooleanMh;
	}

	public Arity0Invoker(MethodHandle mh) {
		this(mh, null, null, null, null);
	}

	@Override
	public Object invoke(Object target, Object[] args) throws Throwable {
		return mh.invokeExact(target);
	}

	@Override
	public Object invoke0(Object target) throws Throwable {
		return mh.invokeExact(target);
	}

	@Override
	public int invokeInt0(Object target) throws Throwable {
		if (rawIntMh != null) return (int) rawIntMh.invokeExact(target);
		return ((Number) invoke0(target)).intValue();
	}

	@Override
	public boolean invokeBoolean0(Object target) throws Throwable {
		if (rawBooleanMh != null) return (boolean) rawBooleanMh.invokeExact(target);
		Object res = invoke0(target);
		return res instanceof Boolean ? (Boolean) res : (res instanceof Number && ((Number) res).intValue() != 0);
	}

	@Override
	public double invokeDouble0(Object target) throws Throwable {
		if (rawDoubleMh != null) return (double) rawDoubleMh.invokeExact(target);
		return ((Number) invoke0(target)).doubleValue();
	}

	@Override
	public long invokeLong0(Object target) throws Throwable {
		if (rawLongMh != null) return (long) rawLongMh.invokeExact(target);
		return ((Number) invoke0(target)).longValue();
	}
}
