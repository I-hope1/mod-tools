package nipx.util;

import java.util.Arrays;


/**
 * <p>专门为 long->long 映射设计的轻量级 Map。</p>
 * <p>内存占用极小，拒绝包装类垃圾。</p>
 * <p>PS：返回值 -1 是一个特殊值，表示无值。</p>
 */
public class LongLongMap {
	public static final long EMPTY_KEY = 0;
	public static final long NOT_FOUND = Long.MIN_VALUE;

	private       long[] keys;
	private       long[] values;
	/** size 不包含 zero-key */
	private       int    size;
	private       int    capacity;
	private final float  loadFactor = 0.75f;

	private boolean hasZero;
	private long    zeroValue;

	public LongLongMap(int initialCapacity) {
		this.capacity = powerOfTwo(initialCapacity);
		this.keys = new long[capacity];
		this.values = new long[capacity];
	}

	public void put(long key, long value) {
		if (value == NOT_FOUND) throw new IllegalArgumentException("value == " + NOT_FOUND);
		if (key == EMPTY_KEY) {
			hasZero = true;
			zeroValue = value;
			return;
		}

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

	/** 返回值 {@link #NOT_FOUND} {@value #NOT_FOUND} 是一个特殊值，表示无值。 */
	public long get(long key) {
		if (key == EMPTY_KEY) {
			if (hasZero) return zeroValue;
			return NOT_FOUND;
		}
		int idx = hash(key) & (capacity - 1);
		while (keys[idx] != EMPTY_KEY) {
			if (keys[idx] == key) return values[idx];
			idx = (idx + 1) & (capacity - 1);
		}
		return NOT_FOUND;
	}

	public void clear() {
		Arrays.fill(keys, EMPTY_KEY);
		Arrays.fill(values, EMPTY_KEY);

		hasZero = false;
		zeroValue = 0;
		size = 0;
	}

	public int size() { return size + (hasZero ? 1 : 0); }
	public boolean isEmpty() { return size() == 0; }

	private void rehash() {
		if (capacity > (1 << 30)) throw new OutOfMemoryError("Capacity overflow");

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
		v *= 0xc4ceb9fe1a85ec53L; // 额外常量混高位
		v ^= (v >>> 33);
		return (int) v;
	}

	private int powerOfTwo(int n) {
		int res = 1;
		while (res < n) res <<= 1;
		return res;
	}
}
