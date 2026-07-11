package modtools.utils;

import modtools.utils.Tools.CProv;

/**
 *
 * 使用方法:<br />
 * {@link CatchSR#apply(Runnable run)}<br />
 * run是get链<br />
 * {@snippet lang = "java":
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
	private boolean nullable;
	private R       value;
	private boolean satisfied;
	private CatchSR() { }
	private static final ThreadLocal<CatchSR> LOCAL = ThreadLocal.withInitial(CatchSR::new);

	public static <R> R apply(Runnable run) {
		return apply(false, run);
	}

	public static <R> R apply(boolean nullable, Runnable run) {
		CatchSR sr = LOCAL.get();
		sr.nullable = nullable;
		run.run();
		if (sr.satisfied) {
			R val = (R) sr.value;
			sr.satisfied = false;
			sr.value = null;
			sr.nullable = true;
			return val;
		}

		throw new IllegalStateException("Failed to meet the requirements.");
	}
	public static <R> CatchSR<R> of(CProv<R> prov) {
		return LOCAL.get().get(prov);
	}
	public CatchSR<R> get(CProv<R> prov) {
		if (satisfied) return this;
		try {
			value = prov.get();
			satisfied = value != null || nullable;
		} catch (Throwable ignored) { }
		return this;
	}
}
