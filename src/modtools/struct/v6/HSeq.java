package modtools.struct.v6;

import arc.struct.Seq;
import modtools.ui.IntUI;

public class HSeq<T> extends Seq<T> {
	public HSeq() {
	}
	public HSeq(Class<?> arrayType) {
		super(arrayType);
	}
	public HSeq(int capacity) {
		super(capacity);
	}
	static final Object[] ONE_ARG = {null};
	public Seq<T> add(T value) {
		if (!IntUI.isV6) return super.add(value);
		ONE_ARG[0] = value;
		addAll((T[])ONE_ARG);
		return this;
	}
}
