package nipx.util;

import java.util.Arrays;


/**
 * 专门为 long->long 映射设计的轻量级 Map。
 * 内存占用极小，拒绝包装类垃圾。
 */
public class LongLongMap {
	private              long[] keys;
	private              long[] values;
	private              int    size;
	private              int    capacity;
	private final        float  loadFactor = 0.75f;
	private static final long   EMPTY_KEY  = 0L; // 假设 0 不作为合法 Key（或通过辅助位判断）

	public LongLongMap(int initialCapacity) {
		this.capacity = powerOfTwo(initialCapacity);
		this.keys = new long[capacity];
		this.values = new long[capacity];
	}

	public void put(long key, long value) {
		if (key == EMPTY_KEY) throw new IllegalArgumentException("Key cannot be 0");
		if (size >= capacity * loadFactor) rehash();

		int idx = hash(key) & (capacity - 1);
		while (keys[idx] != EMPTY_KEY) {
			if (keys[idx] == key) {
				values[idx] = value;
				return;
			}
			idx = (idx + 1) & (capacity - 1);
		}
		keys[idx] = key;
		values[idx] = value;
		size++;
	}

	public long get(long key) {
		if (key == EMPTY_KEY) return -1;
		int idx = hash(key) & (capacity - 1);
		while (keys[idx] != EMPTY_KEY) {
			if (keys[idx] == key) return values[idx];
			idx = (idx + 1) & (capacity - 1);
		}
		return -1; // 未找到
	}

	public void clear() {
		Arrays.fill(keys, EMPTY_KEY);
		size = 0;
	}

	private void rehash() {
		long[] oldKeys   = keys;
		long[] oldValues = values;
		capacity <<= 1;
		keys = new long[capacity];
		values = new long[capacity];
		size = 0;
		for (int i = 0; i < oldKeys.length; i++) {
			if (oldKeys[i] != EMPTY_KEY) put(oldKeys[i], oldValues[i]);
		}
	}

	private int hash(long v) {
		v ^= (v >>> 33);
		v *= 0xff51afd7ed558ccdL;
		v ^= (v >>> 33);
		return (int) v;
	}

	private int powerOfTwo(int n) {
		int res = 1;
		while (res < n) res <<= 1;
		return res;
	}
}
