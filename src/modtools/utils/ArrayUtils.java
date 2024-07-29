package modtools.utils;

import arc.func.*;
import arc.struct.*;
import arc.struct.ObjectMap.Entry;

import java.lang.reflect.Array;
import java.util.*;

@SuppressWarnings("unchecked")
public class ArrayUtils {
	public static final Object[] EMPTY_ARRAY = new Object[0];

	public static <K, V, R> R[] map2Arr(Class<R> cl, ObjectMap<K, V> map, Func<Entry<K, V>, R> func) {
		R[] tableSeq = (R[]) Array.newInstance(cl, map.size);
		int c        = 0;
		for (var entry : map) {
			tableSeq[c++] = func.get(entry);
		}
		return tableSeq;
	}
	public static <K, V> Map<K, V> keyArr2Map(K[] keys, Func<K, V> valueFunc) {
		Map<K, V> map = new HashMap<>();
		for (K key : keys) {
			map.put(key, valueFunc.get(key));
		}
		return map;
	}
	public static <T, K, V> Map<K, V> keyArr2Map(T[] keys, Func<T, K> keyFunc, Func<T, V> valueFunc) {
		Map<K, V> map = new HashMap<>();
		for (T key : keys) {
			map.put(keyFunc.get(key), valueFunc.get(key));
		}
		return map;
	}
	public static <K, V> Map<K, V> valueArr2Map(V[] values, Func<V, K> keyFunc) {
		return valueArr2Map(values, keyFunc, new HashMap<>());
	}
	public static <K, V> Map<K, V> valueArr2Map(V[] values, Func<V, K> keyFunc, Map<K, V> map) {
		for (V val : values) {
			map.put(keyFunc.get(val), val);
		}
		return map;
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

	public static void forEach(Object arr, AllCons cons) {
		Class<?> type = arr.getClass().getComponentType();
		if (type == null) throw new IllegalArgumentException("Not an array: " + arr);
		if (!type.isPrimitive()) {
			int len = Array.getLength(arr);
			for (int i = 0; i < len; i++) cons.get(Array.get(arr, i));
			return;
		}
		switch (arr) {
			case int[] ia -> { for (int i : ia) cons.get(i); }
			case float[] fa -> { for (float i : fa) cons.get(i); }
			case double[] da -> { for (double i : da) cons.get(i); }
			case long[] la -> { for (long i : la) cons.get(i); }
			case boolean[] ba -> { for (boolean i : ba) cons.get(i); }
			case char[] ca -> { for (char i : ca) cons.get(i); }
			case byte[] ba -> { for (byte i : ba) cons.get(i); }
			case short[] sa -> { for (short i : sa) cons.get(i); }
			default -> throw new IllegalStateException("Unexpected value: " + arr);
		}
	}

	public static float sumf(FloatSeq seq, int fromIndex, int toIndex) {
		float sum = 0;
		for (int i = fromIndex; i < toIndex && i < seq.size; i++) {
			sum += seq.get(i);
		}
		return sum;
	}

	public static <T> float sumf(List<T> list, Floatf<T> summer) {
		float sum = 0;
		for (T t : list) {
			sum += summer.get(t);
		}
		return sum;
	}
	public static <T> int sum(List<T> list, Intf<T> summer) {
		int sum = 0;
		for (T t : list) {
			sum += summer.get(t);
		}
		return sum;
	}


	public static abstract class AllCons implements Cons<Object> {
		public abstract void get(Object object);
		public abstract void get(int i);
		public abstract void get(float f);
		public abstract void get(double d);
		public abstract void get(long l);
		public abstract void get(boolean b);
		public abstract void get(char c);
	}
}
