package modtools.struct;

import arc.func.Prov;

public class LazyValue<T> {
	T       t;
	final Prov<T> prov;
	private LazyValue(Prov<T> prov) {
		this.prov = prov;
	}
	public static <T> LazyValue<T> of(Prov<T> prov) {
		return new LazyValue<>(prov);
	}
	public T get() {
		if (t == null) t = prov.get();
		return t;
	}
}
