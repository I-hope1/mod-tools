package nipx.util;

import java.util.*;
import java.util.function.*;

/**
 * 高性能 Long -> Object 映射表
 * 采用开放寻址法（线性探测）减少对象开销和 GC 压力
 */
@SuppressWarnings("unchecked")
public final class LongObjectMap<V> {

	// 哨兵对象
	private static final Object TOMBSTONE   = new Object();
	private static final float  LOAD_FACTOR = 0.75f;

	private long[]   keys;
	private Object[] values;
	private int      size;
	private int      tombstoneCount;
	private int      capacity;
	private int      mask; // 缓存 capacity - 1


	public LongObjectMap() {
		this(16);
	}

	public LongObjectMap(int initialCapacity) {
		// 自动修正为 2 的幂次
		int cap = tableSizeFor(initialCapacity);
		init(cap);
	}

	private void init(int cap) {
		this.capacity = cap;
		this.mask = cap - 1;
		this.keys = new long[cap];
		this.values = new Object[cap];
		this.size = 0;
		this.tombstoneCount = 0;
	}

	private static int tableSizeFor(int n) {
		int cap = 1;
		while (cap < n) cap <<= 1;
		return cap;
	}

	public int size() { return size; }
	public boolean isEmpty() { return size == 0; }

	/**
	 * 哈希混合函数 (MurmurHash3 变体)
	 */
	private int hash(long key) {
		key ^= key >>> 33;
		key *= 0xff51afd7ed558ccdL;
		key ^= key >>> 33;
		key *= 0xc4ceb9fe1a85ec53L;
		key ^= key >>> 33;
		return (int) key;
	}

	public void put(long key, V value) {
		if (value == null) throw new NullPointerException("Value cannot be null");
		if ((size + tombstoneCount + 1) > capacity * LOAD_FACTOR) {
			rehash();
		}

		int h            = hash(key);
		int idx          = h & mask;
		int tombstoneIdx = -1;

		while (values[idx] != null) {
			if (values[idx] == TOMBSTONE) {
				if (tombstoneIdx == -1) tombstoneIdx = idx;
			} else if (keys[idx] == key) {
				values[idx] = value;
				return;
			}
			idx = (idx + 1) & mask;
		}

		int insertIdx = (tombstoneIdx != -1) ? tombstoneIdx : idx;
		if (tombstoneIdx != -1) {
			tombstoneCount--;
		}
		keys[insertIdx] = key;
		values[insertIdx] = value;
		size++;
	}

	@SuppressWarnings("unchecked")
	public V get(long key) {
		int h   = hash(key);
		int idx = h & mask;
		while (values[idx] != null) {
			if (values[idx] != TOMBSTONE && keys[idx] == key) {
				return (V) values[idx];
			}
			idx = (idx + 1) & mask;
		}
		return null;
	}

	public boolean containsKey(long key) {
		int h   = hash(key);
		int idx = h & mask;
		while (values[idx] != null) {
			if (values[idx] != TOMBSTONE && keys[idx] == key) {
				return true;
			}
			idx = (idx + 1) & mask;
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	public V remove(long key) {
		int h   = hash(key);
		int idx = h & mask;
		while (values[idx] != null) {
			if (values[idx] != TOMBSTONE && keys[idx] == key) {
				V old = (V) values[idx];
				values[idx] = TOMBSTONE;
				size--;
				tombstoneCount++;
				return old;
			}
			idx = (idx + 1) & mask;
		}
		return null;
	}

	/**
	 * 高性能 computeIfAbsent，避免两次查找
	 */
	@SuppressWarnings("unchecked")
	public V computeIfAbsent(long key, LongFunction<? extends V> mappingFunction) {
		if ((size + tombstoneCount + 1) > capacity * LOAD_FACTOR) rehash();

		int h            = hash(key);
		int idx          = h & mask;
		int tombstoneIdx = -1;

		while (values[idx] != null) {
			if (values[idx] == TOMBSTONE) {
				if (tombstoneIdx == -1) tombstoneIdx = idx;
			} else if (keys[idx] == key) {
				return (V) values[idx];
			}
			idx = (idx + 1) & mask;
		}

		V newValue = mappingFunction.apply(key);
		if (newValue != null) {
			int insertIdx = (tombstoneIdx != -1) ? tombstoneIdx : idx;
			if (tombstoneIdx != -1) tombstoneCount--;
			keys[insertIdx] = key;
			values[insertIdx] = newValue;
			size++;
		}
		return newValue;
	}

	private void rehash() {
		// 如果墓碑太多，我们可以不扩容只清理（这里暂定简单翻倍扩容）
		resizeTo(capacity * 2);
	}

	private void resizeTo(int newCapacity) {
		long[]   oldKeys   = keys;
		Object[] oldValues = values;
		int      oldCap    = capacity;

		init(newCapacity); // 重新分配数组

		for (int i = 0; i < oldCap; i++) {
			Object v = oldValues[i];
			if (v != null && v != TOMBSTONE) {
				// 直接重新插入，无需考虑重复和墓碑，性能更高
				insertInternal(oldKeys[i], v);
			}
		}
	}

	// 内部快速插入：不检查重复，不检查容量，不检查墓碑
	private void insertInternal(long key, Object value) {
		int idx = hash(key) & mask;
		while (values[idx] != null) {
			idx = (idx + 1) & mask;
		}
		keys[idx] = key;
		values[idx] = value;
		size++;
	}

	public void forEachValue(Consumer<? super V> action) {
		for (int i = 0; i < capacity; i++) {
			if (values[i] != null && values[i] != TOMBSTONE) {
				action.accept((V) values[i]);
			}
		}
	}

	public void clear() {
		Arrays.fill(values, null); // keys 不需要 fill，因为根据 values 判断
		size = 0;
		tombstoneCount = 0;
	}

	@Override
	public String toString() {
		if (isEmpty()) return "{}";
		StringBuilder sb = new StringBuilder();
		sb.append('{');
		boolean first = true;
		for (int i = 0; i < capacity; i++) {
			Object v = values[i];
			if (v != null && v != TOMBSTONE) {
				if (!first) sb.append(", ");
				sb.append(keys[i]).append('=').append(v == this ? "(this Map)" : v);
				first = false;
			}
		}
		return sb.append('}').toString();
	}
	public void putAll(LongObjectMap<? extends V> other) {
		if (other == null || other.isEmpty()) return;

		ensureMoreCapacity(other.size());

		// 2. 物理搬移：直接遍历数组，跳过 null 和墓碑
		long[]   keys1   = other.keys;
		Object[] values1 = other.values;
		for (int i = 0, cap = other.capacity; i < cap; i++) {
			// 直接访问数组比 get 效率更高（减少哈希计算）
			long key   = keys1[i];
			V    value = (V) values1[i];
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

	public long[] keys() { return keys; }
	public Object[] values() { return values; }
	/** 快速判断该位置是否有有效值 (逻辑内联) */
	public static boolean isValid(Object value) {
		return value != null && value != TOMBSTONE;
	}
	public int capacity() {
		return capacity;
	}
	/* public long keyAt(int i) {
		return keys[i];
	}
	public V valueAt(int i) {
		Object v = values[i];
		return v == TOMBSTONE ? null : (V) v;
	} */
}