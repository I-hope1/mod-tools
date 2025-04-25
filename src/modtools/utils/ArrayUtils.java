package modtools.utils;

import arc.func.*;
import arc.struct.*;
import arc.struct.ObjectMap.Entry;
import arc.util.pooling.Pool.Poolable;
import arc.util.pooling.Pools;

import java.lang.reflect.Array;
import java.util.*;

@SuppressWarnings("unchecked")
public class ArrayUtils {
	public static final Object[] EMPTY_ARRAY = new Object[0];
	static final        Object[] ARG1        = {null}, ARG2 = {null, null};
	public static Object[] ARG(Object arg1) {
		ARG1[0] = arg1;
		return ARG1;
	}
	public static Object[] ARG(Object arg1, Object arg2) {
		ARG2[0] = arg1;
		ARG2[1] = arg2;
		return ARG2;
	}

	public static <K, V> ObjectMap<K, V> autoClear(ObjectMap<K, V> map) {
		Tools.TASKS.add(() -> map.clear());
		return map;
	}
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
			K key = keyFunc.get(val);
			if (map.containsKey(key)) throw new RuntimeException(key + " has already exists");
			map.put(key, val);
		}
		return map;
	}

	public static int rollIndex(int i, int size) {
		if (i < 0) {
			return i + size;
		} else if (i >= size) {
			return i - size;
		}
		return i;
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
	/**
	 * <p>返回List的第i个元素，如果越界就返回null</p>
	 * 负数index为倒数第i个元素
	 * @param list 列表
	 * @param i    索引，可以为负数
	 */
	public static <T> T getBound(Seq<T> list, int i) {
		if (i < 0) i += list.size;
		return 0 <= i && i < list.size ? list.get(i) : null;
	}

	public static void forEach(Object arr, AllCons cons) {
		Class<?> type = arr.getClass().getComponentType();
		if (type == null) throw new IllegalArgumentException("Not an array: " + arr);
		if (!type.isPrimitive()) {
			int len = Array.getLength(arr);
			for (int i = 0; i < len; i++) {
				cons.get(Array.get(arr, i));
			}
			cons.append(null);
			return;
		}
		switch (arr) {
			case int[] ia -> {
				for (int i : ia) cons.get(i);
				cons.append(0);
			}
			case float[] fa -> {
				for (float i : fa) cons.get(i);
				cons.append(0F);
			}
			case double[] da -> {
				for (double i : da) cons.get(i);
				cons.append(0D);
			}
			case long[] la -> {
				for (long i : la) cons.get(i);
				cons.append(0L);
			}
			case boolean[] ba -> {
				for (boolean i : ba) cons.get(i);
				cons.append(false);
			}
			case char[] ca -> {
				for (char i : ca) cons.get(i);
				cons.append('\0');
			}
			case byte[] ba -> {
				for (byte i : ba) cons.get(i);
				cons.append((byte) 0);
			}
			case short[] sa -> {
				for (short i : sa) cons.get(i);
				cons.append((short) 0);
			}
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

	public static <T> Seq<T> seq(T... items) {
		Seq<T> seq = (Seq<T>) Pools.get(DisposableSeq.class, DisposableSeq::new).obtain();
		return seq.add(items);
	}

	public static <T> Seq<T> seq(Iterable<T> items) {
		Seq<T> seq = (Seq<T>) Pools.get(DisposableSeq.class, DisposableSeq::new).obtain();
		return seq.addAll(items);
	}
	public static <T> T findInverse(Seq<T> seq, Boolf<T> condition) {
		T item;
		for (int i = seq.size; i-- > 0; ) {
			if (condition.get(item = seq.get(i))) return item;
		}
		return null;
	}
	@SuppressWarnings("rawtypes")
	public static class DisposableSeq extends Seq implements Poolable {
		public void reset() {
			clear();
		}
	}


	public static abstract class AllCons implements Cons<Object> {
		public abstract void get(Object object);
		public abstract void append(Object object);
		public abstract void get(long i);
		public abstract void append(long item);
		public abstract void get(double f);
		public abstract void append(double item);
		public abstract void get(boolean b);
		public abstract void append(boolean item);
		public abstract void get(char c);
		public abstract void append(char item);
		public void get(short s) { get((int) s); }
		public void append(short s) {
			append((int) s);
		}
		public void get(byte b) { get((long) b); }
		public void append(byte b) {
			append((int) b);
		}
		public void get(float f) {
			get((double) f);
		}
		public void append(float f) {
			append((double) f);
		}
		public void get(int l) {
			get((long) l);
		}
		public void append(int l) {
			append((long) l);
		}
	}
}
