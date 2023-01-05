package modtools.utils;

import arc.func.*;
import arc.struct.*;

import java.util.Iterator;

/**
 * 为了适配v6
 */
public class MyObjectSet<T> extends ObjectSet<T> {
	public void filter(Boolf<T> predicate) {
		Iterator<T> iter = this.iterator();

		while (iter.hasNext()) {
			if (!predicate.get(iter.next())) {
				iter.remove();
			}
		}
	}

	public Seq<T> toSeq() {
		return iterator().toSeq();
	}
}
