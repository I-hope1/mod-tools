package hope.magic.runtime;

import jdk.internal.vm.annotation.*;
import java.lang.invoke.MethodHandle;

public final class Arity2CtorInvoker extends MagicInvoker {
	@Stable
	private final MethodHandle mh;
	@Stable
	private final MethodHandle spreader;
	@Stable
	private final MethodHandle rawCtorMh;
	@Stable
	private final boolean      p0IsInt;
	@Stable
	private final boolean      p1IsString;

	public Arity2CtorInvoker(MethodHandle mh, MethodHandle spreader, MethodHandle rawCtorMh,
	                         boolean p0IsInt, boolean p1IsString) {
		this.mh = mh;
		this.spreader = spreader != null ? spreader : mh.asSpreader(Object[].class, 2);
		this.rawCtorMh = rawCtorMh;
		this.p0IsInt = p0IsInt;
		this.p1IsString = p1IsString;
	}

	public Arity2CtorInvoker(MethodHandle mh) {
		this(mh, null, null, false, false);
	}

	@Hidden
	@ForceInline
	@Override
	public Object invoke(Object target, Object[] args) throws Throwable {
		if (args != null && args.length == 2) {
			Object a0 = args[0];
			Object a1 = args[1];
			if (rawCtorMh != null && p0IsInt && p1IsString && a0 instanceof Number n0 && (a1 == null || a1 instanceof String)) {
				return rawCtorMh.invokeExact(n0.intValue(), (String) a1);
			}
			return mh.invokeExact(a0, a1);
		}
		return spreader.invoke(args == null ? EMPTY_ARGS : args);
	}

	@Hidden
	@ForceInline
	@Override
	public Object invoke2(Object target, Object a0, Object a1) throws Throwable {
		if (rawCtorMh != null && p0IsInt && p1IsString && a0 instanceof Number n0 && (a1 == null || a1 instanceof String)) {
			return rawCtorMh.invokeExact(n0.intValue(), (String) a1);
		}
		return mh.invokeExact(a0, a1);
	}

	@Hidden
	@ForceInline
	@Override
	public Object newInstance(Object[] args) throws Throwable {
		if (args != null && args.length == 2) {
			Object a0 = args[0];
			Object a1 = args[1];
			if (rawCtorMh != null && p0IsInt && p1IsString && a0 instanceof Number n0 && (a1 == null || a1 instanceof String)) {
				return rawCtorMh.invokeExact(n0.intValue(), (String) a1);
			}
			return mh.invokeExact(a0, a1);
		}
		return spreader.invoke(args == null ? EMPTY_ARGS : args);
	}

	@Hidden
	@ForceInline
	@Override
	public Object newInstance2(Object a0, Object a1) throws Throwable {
		if (rawCtorMh != null && p0IsInt && p1IsString && a0 instanceof Number n0 && (a1 == null || a1 instanceof String)) {
			return rawCtorMh.invokeExact(n0.intValue(), (String) a1);
		}
		return mh.invokeExact(a0, a1);
	}
}
