package modtools.misc;

import arc.func.Func;
import modtools.jsfunc.type.CAST;

import java.util.Objects;

public class CacheFunc<R, T> implements Func<T, R> {
	T          lastObj;
	R          lastT;
	Func<T, R> func;
	public CacheFunc(Func<T, R> func) {
		this.func = func;
	}

	public R get(T val) {
		if (val == null && lastObj == null) return lastT;
		if (val != null) {
			Class<?> it = val.getClass();
			if (CAST.unbox(it).isPrimitive() || it == String.class) {
				if (Objects.equals(lastObj, val)) return lastT;
			}
		}
		return lastT = func.get(val);
	}
}
