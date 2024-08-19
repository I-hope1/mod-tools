package modtools.struct;

import arc.func.Boolf;
import arc.struct.*;
import modtools.utils.Tools;

import java.util.Iterator;

/**
 * 为了适配v6
 * TODO: 真难适配
 */
public class MySet<T> extends OrderedSet<T> {
	public void filter(Boolf<T> predicate) {
		Iterator<T> iter = iterator();

		// 因为concurrent，所以可能会报错
		// TODO
		Tools.runLoggedException(() -> {
			while (iter.hasNext()) {

				if (!predicate.get(iter.next())) {
					iter.remove();
				}
			}
		});
	}
	public int indexOf(T t) {
		int c = 0;
		for (var v : this) {
			if (v == t) return c;
			c++;
		}
		return -1;
	}
	public Seq<T> toSeq() {
		return iterator().toSeq();
	}
}
