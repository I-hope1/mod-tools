package modtools.utils;

import arc.func.Func;
import arc.struct.ObjectMap;
import arc.struct.ObjectMap.Entry;

import java.lang.reflect.Array;
import java.util.List;

public class ArrayUtils {
	public static <K, V, R> R[] map2Arr(Class<R> cl, ObjectMap<K, V> map, Func<Entry<K, V>, R> func) {
		R[] tableSeq = (R[]) Array.newInstance(cl, map.size);
		int c = 0;
		for (var entry : map) {
			tableSeq[c++] = func.get(entry);
		}
		return tableSeq;
	}

	public static <T> T getBound(T[] arr, int index) {
		if (index >= arr.length || index < -arr.length) return null;
		if (index < 0) index += arr.length;
		return arr[index];
	}
	/**
	 * <p>返回List的第i个元素，如果越界就返回null</p>
	 * 负数index为倒数第i个元素
	 * @param list 列表
	 * @param i    索引，可以为负数
	 */
	public static <T> T getBound(List<T> list, int i) {
		if (i < 0) i += list.size();
		return 0 <= i && i < list.size() ? list.get(i) : null;
	}
}
