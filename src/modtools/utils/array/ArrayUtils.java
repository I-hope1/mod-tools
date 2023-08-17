package modtools.utils.array;

import arc.func.Func;
import arc.struct.ObjectMap;
import arc.struct.ObjectMap.Entry;

import java.lang.reflect.Array;

public class ArrayUtils {
	public static <K, V, R> R[] map2Arr(Class<R> cl, ObjectMap<K, V> map, Func<Entry<K, V>, R> func) {
		R[] tableSeq = (R[]) Array.newInstance(cl, map.size);
		int c = 0;
		for (var entry : map) {
			tableSeq[c++] = func.get(entry);
		}
		return tableSeq;
	}
}
