package nipx.util;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class LongObjectMap<V> {

	private static final Object TOMBSTONE   = new Object();
	private static final float  LOAD_FACTOR = 0.75f;

	private long[]   keys;
	private Object[] values;
	private int      size;
	private int      tombstoneCount;
	private int      capacity;

	public int capacity() {
		return capacity;
	}
	public long keyAt(int index) {
		return keys[index];
	}
	public V valueAt(int index) {
		@SuppressWarnings("unchecked")
		V v = (V) values[index];
		if (v == TOMBSTONE) return null;
		return v;
	}


	public LongObjectMap() {
		this(16);
	}

	public LongObjectMap(int initialCapacity) {
		if (initialCapacity <= 0 || Integer.bitCount(initialCapacity) != 1) {
			throw new IllegalArgumentException("capacity must be a positive power of 2, got: " + initialCapacity);
		}
		this.capacity = initialCapacity;
		this.keys = new long[capacity];
		this.values = new Object[capacity];
	}

	public int size() { return size; }
	public boolean isEmpty() { return size == 0; }

	public void put(long key, V value) {
		if ((size + tombstoneCount) > capacity * LOAD_FACTOR) resize();

		// 对 key 进行混合，确保高位信息参与索引计算
		int h            = hash(key);
		int idx          = h & (capacity - 1);
		int tombstoneIdx = -1;

		while (values[idx] != null) {
			if (values[idx] == TOMBSTONE) {
				if (tombstoneIdx == -1) tombstoneIdx = idx;
			} else if (keys[idx] == key) {
				values[idx] = value;
				return;
			}
			idx = (idx + 1) & (capacity - 1);
		}

		int insertIdx = (tombstoneIdx != -1) ? tombstoneIdx : idx;
		if (tombstoneIdx != -1) {
			tombstoneCount--;  // 复用了一个墓碑
		}
		keys[insertIdx] = key;
		values[insertIdx] = value;
		size++;
	}

	private int hash(long key) {
		// 简单的 MurmurHash3 混合常量
		key ^= key >>> 33;
		key *= 0xff51afd7ed558ccdL;
		key ^= key >>> 33;
		key *= 0xc4ceb9fe1a85ec53L;
		key ^= key >>> 33;
		return (int) key;
	}

	@SuppressWarnings("unchecked")
	public V get(long key) {
		int h   = hash(key);
		int idx = h & (capacity - 1);
		while (values[idx] != null) {
			// 注意：墓碑不匹配 Key，但不能停止探测链
			if (values[idx] != TOMBSTONE && keys[idx] == key) return (V) values[idx];
			idx = (idx + 1) & (capacity - 1);
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	public V remove(long key) {
		int h   = hash(key);
		int idx = h & (capacity - 1);
		while (values[idx] != null) {
			if (values[idx] != TOMBSTONE && keys[idx] == key) {
				V old = (V) values[idx];
				values[idx] = TOMBSTONE;
				size--;
				tombstoneCount++;
				return old;
			}
			idx = (idx + 1) & (capacity - 1);
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	public V getOrDefault(long key, Supplier<V> defaultSupplier) {
		V v = get(key);
		return v != null ? v : defaultSupplier.get();
	}

	@SuppressWarnings("unchecked")
	public void forEachValue(Consumer<V> action) {
		for (int i = 0; i < capacity; i++) {
			if (values[i] != null && values[i] != TOMBSTONE) {
				action.accept((V) values[i]);
			}
		}
	}

	public void clear() {
		Arrays.fill(keys, 0L);
		Arrays.fill(values, null);
		size = 0;
		tombstoneCount = 0;
	}

	private void resize() {
		resizeTo(capacity * 2);
	}

	@SuppressWarnings("unchecked")
	private void resizeTo(int newCapacity) {
		long[]   oldKeys   = keys;
		Object[] oldValues = values;
		int      oldCap    = capacity;

		this.keys = new long[newCapacity];
		this.values = new Object[newCapacity];
		this.capacity = newCapacity;
		this.size = 0;
		this.tombstoneCount = 0;

		for (int i = 0; i < oldCap; i++) {
			Object v = oldValues[i];
			if (v != null && v != TOMBSTONE) {
				// 4. 修正：重新放入时，基于新容量再次哈希计算位置
				// 这里直接调用 put 是最稳妥的，或者手动展开以追求极速
				put(oldKeys[i], (V)v);
			}
		}
	}
	public void putAll(LongObjectMap<? extends V> other) {
		if (other == null || other.isEmpty()) return;

		ensureMoreCapacity(other.size());

		// 2. 物理搬移：直接遍历数组，跳过 null 和墓碑
		for (int i = 0; i < other.capacity(); i++) {
			// 直接访问数组比 get 效率更高（减少哈希计算）
			long key   = other.keyAt(i);
			V    value = other.valueAt(i);
			// valueAt 已经处理了墓碑返回 null
			if (value != null) {
				this.put(key, value);
			}
		}
	}
	private void ensureMoreCapacity(int other) {
		// 1. 预估容量：主要矛盾是减少 resize 过程中产生的临时数组分配
		// 合并后的总规模 = 当前占用(含墓碑) + 外部新入成员
		int totalPotentialSize = this.size + this.tombstoneCount + other;
		if (totalPotentialSize > this.capacity * LOAD_FACTOR) {
			int targetCapacity = this.capacity;
			while (totalPotentialSize > targetCapacity * LOAD_FACTOR) {
				targetCapacity <<= 1; // 保持 2 的幂
			}
			resizeTo(targetCapacity); // 抽取出一个显式容量的 resize 方法
		}
	}
	/**
	 * 专门用于从列表或其他集合批量导入数据，并提取复合哈希 Key
	 */
	public <T> void putAll(Collection<T> items, java.util.function.ToLongFunction<T> keyExtractor,
	                       java.util.function.Function<T, V> valueMapper) {
		if (items == null || items.isEmpty()) return;

		// 预扩容检查
		ensureMoreCapacity(items.size());

		for (T item : items) {
			this.put(keyExtractor.applyAsLong(item), valueMapper.apply(item));
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		// 1. 类型校验
		if (!(o instanceof LongObjectMap<?> that)) return false;

		// 2. 规模校验（主要矛盾）：Size 不等直接判否
		if (this.size != that.size) return false;

		// 3. 逻辑内容校验（逐项核查）
		// 遍历当前 Map 的物理数组
		for (int i = 0; i < this.capacity; i++) {
			Object v = this.values[i];

			// 忽略空位和墓碑，只处理有效数据
			if (v != null && v != TOMBSTONE) {
				long key = this.keys[i];

				// 去对方 Map 里查找同一个 Key
				Object thatValue = that.get(key);

				// 判断值是否相等
				if (thatValue == null) return false; // 对方没有这个 Key
				if (!Objects.equals(v, thatValue)) return false; // 值对不上
			}
		}

		return true;
	}

	@Override
	public int hashCode() {
		// Map 的哈希值必须是所有有效项哈希值的累加（符合加法交换律，与顺序无关）
		int h = 0;
		for (int i = 0; i < capacity; i++) {
			Object v = values[i];
			if (v != null && v != TOMBSTONE) {
				// 将 Key 和 Value 的哈希值结合，确保逻辑唯一性
				h += Long.hashCode(keys[i]) ^ Objects.hashCode(v);
			}
		}
		return h;
	}
	public boolean containsKey(long l) {
		return get(l) != null;
	}
}