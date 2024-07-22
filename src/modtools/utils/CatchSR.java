package modtools.utils;

import arc.struct.ObjectMap;
import modtools.utils.SR.SatisfyException;
import modtools.utils.Tools.*;

/**
 *
 * 使用方法:<br />
 * {@link CatchSR#apply(Runnable run)}<br />
 * run是get链<br />
 *
 * <pre>{@code CatchSR.apply(() ->
 * CatchSR.of(() -> MyReflect.lookupGetMethods(cls))
 *        .get(cls::getDeclaredMethods)
 *        .get(() -> new Method[0])
 * )}</pre>
 * @author I-hope1
 * @see #apply(Runnable)
 * @see #of(CProv)
 * @see #get(CProv)
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class CatchSR<R> {
	private R value;
	private CatchSR() { }
	private static final ObjectMap<Thread, CatchSR> caches = new ObjectMap<>();
	public static <R> CatchSR<R> of(CProv<R> prov) {
		CatchSR instance = getInstance();
		instance.value = null;
		return instance.get(prov);
	}
	public static <R> R apply(Runnable run) {
		try {
			run.run();
			throw new IllegalStateException("Cannot meet the requirements.");
		} catch (SatisfyException e) {
			return (R) getInstance().value;
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

	private static CatchSR getInstance() {
		return caches.get(Thread.currentThread(), CatchSR::new);
	}
}
