package modtools.struct;

import arc.func.Prov;

public class LazyValue<T> {
	private T       t;
	private Prov<T> prov;
	private LazyValue(Prov<T> prov) {
		if (prov == null) throw new IllegalArgumentException("The prov cannot be null.");
		this.prov = prov;
	}
	public static <T> LazyValue<T> of(Prov<T> prov) {
		return new LazyValue<>(prov);
	}
	public T get() {
		if (prov == null) return t;

		t = prov.get();
		prov = null;
		return t;
	}
}
