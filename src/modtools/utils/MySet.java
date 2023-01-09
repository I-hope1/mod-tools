package modtools.utils;

import arc.func.*;
import arc.struct.*;

import java.util.Iterator;

/**
 * 为了适配v6
 */
public class MySet<T> extends OrderedSet<T> {
	public void filter(Boolf<T> predicate) {
		Iterator<T> iter = this.iterator();

		while (iter.hasNext()) {
			if (!predicate.get(iter.next())) {
				iter.remove();
			}
		}
	}

	public T get(int index) {
		return orderedItems().get(index);
	}

	public int getIndex(T t) {
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
