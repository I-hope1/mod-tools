package modtools.utils;

import modtools.utils.SR.SatisfyException;
import modtools.utils.Tools.*;

/**
 *
 * 使用方法:<br />
 * {@link CatchSR#apply(Runnable run)}<br />
 * run是get链<br />
 * {@snippet lang="java" :
 * CatchSR.apply(() ->
 * CatchSR.of(() -> MyReflect.lookupGetMethods(cls))
 *        .get(cls::getDeclaredMethods)
 *        .get(() -> new Method[0])
 * )}
 * @author I-hope1
 * @see #apply(Runnable)
 * @see #of(CProv)
 * @see #get(CProv)
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class CatchSR<R> {
	private R value;
	private CatchSR() { }
	private static final ThreadLocal<CatchSR> LOCAL = ThreadLocal.withInitial(CatchSR::new);

	public static <R> CatchSR<R> of(CProv<R> prov) {
		CatchSR instance = LOCAL.get();
		instance.value = null;
		return instance.get(prov);
	}
	public static <R> R apply(Runnable run) {
		try {
			run.run();
			throw new IllegalStateException("Failed to meet the requirements.");
		} catch (SatisfyException e) {
			return (R) LOCAL.get().value;
		}
	}
	public CatchSR<R> get(CProv<R> prov) {
		try {
			value = prov.get();
		} catch (Throwable ignored) {
			return this;
		}
		throw new SatisfyException();
	}
}
