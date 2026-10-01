package nipx.util;

import java.util.Arrays;


/**
 * <p>专门为 long->long 映射设计的轻量级 Map。</p>
 * <p>内存占用极小，拒绝包装类垃圾。</p>
 * <p>PS：返回值 {@value NOT_FOUND} 是一个特殊值，表示无值。</p>
 */
public class LongLongMap {
	public static final  long  EMPTY_KEY        = 0;
	public static final  long  NOT_FOUND        = Long.MIN_VALUE;
	private static final float LOAD_FACTOR      = 0.75f;
	private static final int   MIN_CAPACITY     = 4;       // 保证必须有空槽，防死循环
	private static final int   MAXIMUM_CAPACITY = 1 << 30; // 2^30


	private long[] keys;
	private long[] values;
	/** size 不包含 zero-key */
	private int    size;
	private int    capacity;
	private int    mask;      // 缓存 capacity - 1
	private int    threshold; // 缓存扩容阈值，避免浮点计算

	private boolean hasZero;
	private long    zeroValue;

	public LongLongMap() {
		this(16);
	}

	public LongLongMap(int initialCapacity) {
		capacity = tableSizeFor(initialCapacity);
		mask = capacity - 1;
		threshold = (int) (capacity * LOAD_FACTOR);
		keys = new long[capacity];
		values = new long[capacity];
	}

	public void put(long key, long value) {
		if (value == NOT_FOUND) throw new IllegalArgumentException("value cannot be NOT_FOUND (" + NOT_FOUND + ")");
		if (key == EMPTY_KEY) {
			hasZero = true;
			zeroValue = value;
			return;
		}

		int idx = hash(key) & mask;
		while (keys[idx] != EMPTY_KEY) {
			if (keys[idx] == key) {
				values[idx] = value;
				return;
			}
			idx = (idx + 1) & mask;
		}

		// 确定要插入新 Key，此时才做容量检查
		if (size >= threshold) {
			rehash();
			// 扩容后 mask 改变，重新定位
			idx = hash(key) & mask;
			while (keys[idx] != EMPTY_KEY) {
				idx = (idx + 1) & mask;
			}
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
		int idx = hash(key) & mask;
		while (keys[idx] != EMPTY_KEY) {
			if (keys[idx] == key) return values[idx];
			idx = (idx + 1) & mask;
		}
		return NOT_FOUND;
	}
	public boolean containsKey(long l) {
		if (l == EMPTY_KEY) {
			return hasZero;
		}
		int idx = hash(l) & mask;
		while (keys[idx] != EMPTY_KEY) {
			if (keys[idx] == l) return true;
			idx = (idx + 1) & mask;
		}
		return false;
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
		if (capacity > MAXIMUM_CAPACITY) {
			throw new IllegalStateException("LongLongMap capacity exceeded: " + MAXIMUM_CAPACITY);
		}

		long[] oldKeys   = keys;
		long[] oldValues = values;
		int    oldCap    = capacity;

		capacity <<= 1;
		mask = capacity - 1;
		threshold = (int) (capacity * LOAD_FACTOR);
		keys = new long[capacity];
		values = new long[capacity];

		// 内部快速搬迁：不检查重复、不走递归 put、不需要更新 size
		for (int i = 0; i < oldCap; i++) {
			long k = oldKeys[i];
			if (k != EMPTY_KEY) {
				int idx = hash(k) & mask;
				while (keys[idx] != EMPTY_KEY) {
					idx = (idx + 1) & mask;
				}
				keys[idx] = k;
				values[idx] = oldValues[i];
			}
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

	/**
	 * 安全计算 >= n 的最小 2 的幂次
	 * @see java.util.HashMap#tableSizeFor(int)
	 */
	private static int tableSizeFor(int cap) {
		if (cap <= MIN_CAPACITY) return MIN_CAPACITY;
		int n = -1 >>> Integer.numberOfLeadingZeros(cap - 1);
		return (n < 0) ? 1 : (n >= MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : n + 1;
	}
}
