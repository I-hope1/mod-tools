package modtools.utils;

import arc.func.Cons;
import arc.struct.Seq;

public class AddedSeq<T> extends Seq<T> {
	/* 是否处理了改变 */
	boolean resolved = true;
	public Seq<T> add(T value) {
		resolved = false;
		return super.add(value);
	}
	public void each(Cons<? super T> consumer) {
		resolved = true;
		super.each(consumer);
	}
	public Seq<T> clear() {
		resolved = false;
		return super.clear();
	}
	public boolean isResolved() {
		return resolved;
	}
}
