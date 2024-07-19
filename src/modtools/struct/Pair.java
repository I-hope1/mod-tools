package modtools.struct;

import arc.func.Prov;

import java.util.Objects;

public class Pair<T1, T2> {
	T1 first;
	T2 second;
	public Pair(T1 first, T2 second) {
		this.first = first;
		this.second = second;
	}
	public Pair() { }
	public T1 getFirst() {
		return first;
	}
	public T1 getFirst(Prov<T1> prov) {
		return first = first == null ? prov.get() : first;
	}
	public T2 getSecond() {
		return second;
	}
	public T2 getSecond(Prov<T2> prov) {
		return second = second == null ? prov.get() : second;
	}
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof Pair<?, ?> pair)) return false;
		return Objects.equals(first, pair.first) && Objects.equals(second, pair.second);
	}
	public int hashCode() {
		return Objects.hash(first, second);
	}
	public String toString() {
		return STR."Pair[\{first}, \{second}\{']'}";
	}
}
