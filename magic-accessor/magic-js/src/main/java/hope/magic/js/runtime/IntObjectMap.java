package hope.magic.js.runtime;

import java.util.*;
import java.util.function.*;

/**
 * 高性能 int -> Object 映射表
 * 采用开放寻址法（线性探测）减少对象开销和 GC 压力
 */
@SuppressWarnings("unchecked")
public final class IntObjectMap<V> {

	// 哨兵对象
	private static final Object TOMBSTONE   = new Object();
	private static final float  LOAD_FACTOR = 0.75f;

	private static final int    MIN_CAPACITY     = 4;
	/** @see HashMap#MAXIMUM_CAPACITY */
	static final int MAXIMUM_CAPACITY = 1 << 30;

	private int[]    keys;
	private Object[] values;
	private int      size;
	private int      tombstoneCount;
	private int      capacity;
	private int      mask; // 缓存 capacity - 1


	public IntObjectMap() {
		this(16);
	}

	public IntObjectMap(int initialCapacity) {
		// 自动修正为 2 的幂次
		int cap = tableSizeFor(initialCapacity);
		init(cap);
	}

	private void init(int cap) {
		this.capacity = cap;
		this.mask = cap - 1;
		this.keys = new int[cap];
		this.values = new Object[cap];
		this.size = 0;
		this.tombstoneCount = 0;
	}

	/** @see HashMap#tableSizeFor(int)   */
	private static int tableSizeFor(int cap) {
		if (cap <= MIN_CAPACITY) return MIN_CAPACITY;
		int n = -1 >>> Integer.numberOfLeadingZeros(cap - 1);
		return (n >= MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : n + 1;
	}

	public int size() { return size; }
	public boolean isEmpty() { return size == 0; }
	public int capacity() { return capacity; }

	/** 哈希混合函数 (MurmurHash3 32-bit finalizer) */
	private int hash(int key) {
		key ^= (key >>> 16);
		key *= 0x85ebca6b;
		key ^= (key >>> 13);
		key *= 0xc2b2ae35;
		key ^= (key >>> 16);
		return key;
	}

	public void put(int key, V value) {
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
	public V get(int key) {
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

	public boolean containsKey(int key) {
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
	public V remove(int key) {
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

	/**  高性能 computeIfAbsent，避免两次查找*/
	@SuppressWarnings("unchecked")
	public V computeIfAbsent(int key, IntFunction<? extends V> mappingFunction) {
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

	/**
	 * 智能重哈希：
	 * 1. 达到最大容量时拒绝扩容；
	 * 2. 若大部分是墓碑（有效数据不足容量的一半），保持原容量仅清理墓碑，彻底阻断 OOM；
	 * 3. 真正需要空间时才翻倍扩容。
	 */
	private void rehash() {
		if (capacity >= MAXIMUM_CAPACITY) {
			if (size >= MAXIMUM_CAPACITY * LOAD_FACTOR) {
				throw new IllegalStateException("IntObjectMap capacity exceeded: " + MAXIMUM_CAPACITY);
			}
			resizeTo(capacity); // 仅做墓碑清理
			return;
		}

		// 如果有效负载并不高，说明是墓碑占位过多导致的，原地清理不扩容！
		if (size < (capacity * LOAD_FACTOR * 0.5f)) {
			resizeTo(capacity);
		} else {
			resizeTo(capacity << 1);
		}
	}

	private void resizeTo(int newCapacity) {
		int[]    oldKeys   = keys;
		Object[] oldValues = values;
		int      oldCap    = capacity;

		init(newCapacity); // 重新分配数组与重置计数器

		for (int i = 0; i < oldCap; i++) {
			Object v = oldValues[i];
			if (v != null && v != TOMBSTONE) {
				// 直接重新插入，无需考虑重复和墓碑，性能更高
				insertInternal(oldKeys[i], v);
			}
		}
	}

	// 内部快速插入：不检查重复，不检查容量，不检查墓碑
	private void insertInternal(int key, Object value) {
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
			Object v = values[i];
			if (v != null && v != TOMBSTONE) {
				action.accept((V) v);
			}
		}
	}

	public void clear() {
		Arrays.fill(values, null);  // keys 为基础类型无需清理，无 GC 泄漏风险
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
	public void putAll(IntObjectMap<? extends V> other) {
		if (other == null || other.isEmpty()) return;

		ensureMoreCapacity(other.size());

		// 直接遍历数组，跳过 null 和墓碑
		int[]    otherKeys   = other.keys;
		Object[] otherValues = other.values;
		for (int i = 0, cap = other.capacity; i < cap; i++) {
			Object value = otherValues[i];
			// valueAt 已经处理了墓碑返回 null
			if (value != null && value != TOMBSTONE) {
				this.put(otherKeys[i], (V) value);
			}
		}
	}
	private void ensureMoreCapacity(int countToAdd) {
		//预估容量：主要是减少 resize 过程中产生的临时数组分配
		// 合并后的总规模 = 当前占用(含墓碑) + 外部新入成员
		long totalPotentialSize = this.size + this.tombstoneCount + countToAdd;
		if (totalPotentialSize > this.capacity * LOAD_FACTOR) {
			int targetCapacity = this.capacity;
			while (totalPotentialSize > targetCapacity * LOAD_FACTOR) {
				if (targetCapacity >= MAXIMUM_CAPACITY) {
					targetCapacity = MAXIMUM_CAPACITY;
					break;
				}
				targetCapacity <<= 1; // 保持 2 的幂
			}
			resizeTo(targetCapacity);
		}
	}
	/** 专门用于从列表或其他集合批量导入数据，并提取复合哈希 Key */
	public <T> void putAll(Collection<T> items, java.util.function.ToIntFunction<T> keyExtractor,
	                       java.util.function.Function<T, V> valueMapper) {
		if (items == null || items.isEmpty()) return;

		// 预扩容检查
		ensureMoreCapacity(items.size());

		for (T item : items) {
			this.put(keyExtractor.applyAsInt(item), valueMapper.apply(item));
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof IntObjectMap<?> that)) return false;

		if (this.size != that.size) return false;

		for (int i = 0; i < this.capacity; i++) {
			Object v = this.values[i];

			// 忽略空位和墓碑，只处理有效数据
			if (v != null && v != TOMBSTONE) {
				int key = this.keys[i];
				Object thatValue = that.get(key);
				if (thatValue == null || !Objects.equals(v, thatValue)) {
					return false;
				}
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
				h +=  keys[i] ^ Objects.hashCode(v);
			}
		}
		return h;
	}

	/** 内部使用，请勿修改 */
	public int[] keys() { return keys; }
	/** 内部使用，请勿修改 */
	public Object[] values() { return values; }
	/** 快速判断该位置是否有有效值 (逻辑内联) */
	public static boolean isValid(Object value) {
		return value != null && value != TOMBSTONE;
	}
	/* public long keyAt(int i) {
		return keys[i];
	}
	public V valueAt(int i) {
		Object v = values[i];
		return v == TOMBSTONE ? null : (V) v;
	} */
}