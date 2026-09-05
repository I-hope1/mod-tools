package hope.magic.js.runtime;

import java.util.*;
import java.util.function.Consumer;

public class JSArray extends JSObject implements Iterable<Object> {
	public static final int  MAX_DENSE_CAPACITY     = 65536;
	public static final int  INITIAL_DENSE_CAPACITY = 8;
	public static final long MAX_ARRAY_INDEX        = 4294967294L; // 2^32 - 2 (ECMAScript 规范标准上限)

	public static final int LENGTH_PROP_ID = SymbolTable.id("length");

	public static final Object HOLE = new Object() {
		@Override
		public String toString() {
			return "<hole>";
		}
	};

	private static final Object   NULL_SENTINEL  = new Object();
	private static final Object[] EMPTY_ELEMENTS = new Object[0];

	public Object[] elements = EMPTY_ELEMENTS;
	public int denseSize = 0;
	private final Map<Long, Object> sparse = new HashMap<>();
	private long length = 0;

	//region 构造函数

	public JSArray() {
		super(JSContext.LazyArray.ARRAY_PROTOTYPE);
	}

	public JSArray(int initialCapacity) {
		super(JSContext.LazyArray.ARRAY_PROTOTYPE);
		if (initialCapacity > 0) {
			int cap = Math.min(initialCapacity, MAX_DENSE_CAPACITY);
			this.elements = new Object[Math.max(cap, INITIAL_DENSE_CAPACITY)];
			Arrays.fill(this.elements, HOLE);
		}
	}

	public JSArray(JSObject prototype) {
		super(prototype != null ? prototype : JSContext.LazyArray.ARRAY_PROTOTYPE);
	}

	public JSArray(Collection<?> initial) {
		super(JSContext.LazyArray.ARRAY_PROTOTYPE);
		if (initial != null) {
			int sz = initial.size();
			if (sz > 0 && sz <= MAX_DENSE_CAPACITY) {
				int cap = Math.max(sz, INITIAL_DENSE_CAPACITY);
				this.elements = new Object[cap];
				int idx = 0;
				for (Object elem : initial) {
					this.elements[idx++] = elem;
				}
				for (int i = idx; i < cap; i++) {
					this.elements[i] = HOLE;
				}
				this.denseSize = sz;
				this.length = sz;
			} else {
				for (Object elem : initial) {
					push(elem);
				}
			}
		}
	}

	public JSArray(Iterable<?> initial) {
		super(JSContext.LazyArray.ARRAY_PROTOTYPE);
		if (initial != null) {
			if (initial instanceof Collection<?> c) {
				int sz = c.size();
				if (sz > 0 && sz <= MAX_DENSE_CAPACITY) {
					int cap = Math.max(sz, INITIAL_DENSE_CAPACITY);
					this.elements = new Object[cap];
					int idx = 0;
					for (Object elem : c) {
						this.elements[idx++] = elem;
					}
					for (int i = idx; i < cap; i++) {
						this.elements[i] = HOLE;
					}
					this.denseSize = sz;
					this.length = sz;
				} else {
					for (Object elem : initial) push(elem);
				}
			} else {
				for (Object elem : initial) {
					push(elem);
				}
			}
		}
	}

	//endregion
	//region 长度属性操作

	public long length() {
		return length;
	}

	public void setLength(double d) {
		if (Double.isFinite(d) && d >= 0 && d <= 4294967295L && d == Math.floor(d)) {
			long newLen = (long) d;
			this.length = newLen;
			if (newLen < denseSize) {
				for (int i = (int) newLen; i < denseSize; i++) {
					elements[i] = HOLE;
				}
				denseSize = (int) newLen;
			}
			if (!sparse.isEmpty()) {
				sparse.keySet().removeIf(k -> k >= newLen);
			}
			return;
		}
		throw new IllegalArgumentException("RangeError: Invalid array length: " + d);
	}

	public void setLength(Object value) {
		if (value instanceof Number num) {
			setLength(num.doubleValue());
			return;
		}
		throw new IllegalArgumentException("RangeError: Invalid array length: " + value);
	}

	//endregion
	//region 元素快速访问 (getElement / setElement)

	public Object getElement(int index) {
		if (index >= 0 && index < denseSize) {
			Object val = elements[index];
			return val == HOLE ? JSUndefined.INSTANCE : val;
		}
		return getElementSlow(index);
	}

	public Object getElement(long index) {
		if (index >= 0 && index < denseSize) {
			Object val = elements[(int) index];
			return val == HOLE ? JSUndefined.INSTANCE : val;
		}
		return getElementSlow(index);
	}

	public double getElementDouble(int index) {
		if (index >= 0 && index < denseSize) {
			Object val = elements[index];
			return val == HOLE ? Double.NaN : JSOps.toDouble(val);
		}
		return JSOps.toDouble(getElementSlow(index));
	}

	public double getElementDouble(long index) {
		if (index >= 0 && index < denseSize) {
			Object val = elements[(int) index];
			return val == HOLE ? Double.NaN : JSOps.toDouble(val);
		}
		return JSOps.toDouble(getElementSlow(index));
	}

	private Object getElementSlow(long index) {
		if (index < 0 || index > MAX_ARRAY_INDEX) {
			return super.get(String.valueOf(index));
		}
		Object sparseVal = sparse.get(index);
		if (sparseVal != null) {
			return sparseVal == NULL_SENTINEL ? null : sparseVal;
		}
		return JSUndefined.INSTANCE;
	}

	public void setElement(int index, Object value) {
		if (index >= 0 && index < denseSize) {
			elements[index] = value;
			return;
		}
		setElement((long) index, value);
	}

	public void setElementDouble(int index, double value) {
		setElement(index, (Double) value);
	}

	public void setElementDouble(long index, double value) {
		setElement(index, (Double) value);
	}

	public void setElement(long index, Object value) {
		if (index < 0 || index > MAX_ARRAY_INDEX) {
			super.put(String.valueOf(index), value);
			return;
		}
		if (index < MAX_DENSE_CAPACITY && index <= denseSize + 1024) {
			int intIdx = (int) index;
			if (intIdx >= elements.length) {
				grow(intIdx + 1);
			}
			while (denseSize < intIdx) {
				elements[denseSize++] = HOLE;
			}
			if (denseSize == intIdx) {
				denseSize++;
			}
			elements[intIdx] = value;
		} else {
			sparse.put(index, value == null ? NULL_SENTINEL : value);
		}
		if (index + 1L > length) {
			length = index + 1L;
		}
	}

	private void grow(int minCapacity) {
		int oldCap = elements.length;
		int newCap = oldCap == 0 ? INITIAL_DENSE_CAPACITY : (oldCap + (oldCap >> 1));
		if (newCap < minCapacity) newCap = minCapacity;
		if (newCap > MAX_DENSE_CAPACITY) newCap = MAX_DENSE_CAPACITY;
		Object[] newArr = new Object[newCap];
		if (denseSize > 0) {
			System.arraycopy(elements, 0, newArr, 0, denseSize);
		}
		for (int i = denseSize; i < newCap; i++) {
			newArr[i] = HOLE;
		}
		this.elements = newArr;
	}

	public void push(Object value) {
		if (length == denseSize && denseSize < MAX_DENSE_CAPACITY) {
			if (denseSize == elements.length) {
				grow(denseSize + 1);
			}
			elements[denseSize++] = value;
			length++;
			return;
		}
		setElement(length, value);
	}

	public void pushDouble(double value) {
		push(Double.valueOf(value));
	}

	public Object pop() {
		if (length == 0) return JSUndefined.INSTANCE;
		long lastIdx = length - 1;
		Object val = getElement(lastIdx);
		if (lastIdx < denseSize) {
			denseSize = (int) lastIdx;
			elements[denseSize] = HOLE;
		} else {
			sparse.remove(lastIdx);
		}
		length = lastIdx;
		return val;
	}

	public boolean hasElement(long index) {
		if (index >= 0 && index < denseSize) {
			return elements[(int) index] != HOLE;
		}
		if (index >= 0 && index <= MAX_ARRAY_INDEX) {
			return sparse.containsKey(index);
		}
		return false;
	}

	public void deleteElement(long index) {
		if (index >= 0 && index < denseSize) {
			elements[(int) index] = HOLE;
		} else if (index >= 0 && index <= MAX_ARRAY_INDEX) {
			sparse.remove(index);
		}
	}

	public boolean isDense() {
		return sparse.isEmpty() && length == denseSize;
	}

	//endregion
	//region 继承自 JSObject 的属性存取体系对接

	@Override
	public Object get(int propId) {
		if (propId == LENGTH_PROP_ID) {
			return (double) length;
		}
		int offset = shape.getOffset(propId);
		if (offset >= 0) {
			return getSlot(offset);
		}
		String name = SymbolTable.name(propId);
		if (name != null) {
			Long idx = parseIndex(name);
			if (idx != null) {
				return getElement(idx);
			}
		}
		return super.get(propId);
	}

	@Override
	public Object get(String key) {
		if ("length".equals(key)) {
			return (double) length;
		}
		Long idx = parseIndex(key);
		if (idx != null) {
			return getElement(idx);
		}
		return super.get(key);
	}

	@Override
	public double getAsDouble(int propId) {
		if (propId == LENGTH_PROP_ID) {
			return (double) length;
		}
		int offset = shape.getOffset(propId);
		if (offset >= 0) {
			if (isDoubleSlot(offset)) {
				return getDoubleSlot(offset);
			}
			return JSOps.toDouble(getObjectSlot(offset));
		}
		String name = SymbolTable.name(propId);
		if (name != null) {
			Long idx = parseIndex(name);
			if (idx != null) {
				return getElementDouble(idx);
			}
		}
		return super.getAsDouble(propId);
	}

	@Override
	public double getAsDouble(String key) {
		if ("length".equals(key)) {
			return (double) length;
		}
		Long idx = parseIndex(key);
		if (idx != null) {
			return getElementDouble(idx);
		}
		return super.getAsDouble(key);
	}

	@Override
	public void put(int propId, Object value) {
		if (propId == LENGTH_PROP_ID) {
			setLength(value);
			return;
		}
		String name = SymbolTable.name(propId);
		if (name != null) {
			Long idx = parseIndex(name);
			if (idx != null) {
				setElement(idx, value);
				return;
			}
		}
		super.put(propId, value);
	}

	@Override
	public void put(String key, Object value) {
		if ("length".equals(key)) {
			setLength(value);
			return;
		}
		Long idx = parseIndex(key);
		if (idx != null) {
			setElement(idx, value);
			return;
		}
		super.put(key, value);
	}

	@Override
	public void putDouble(int propId, double value) {
		if (propId == LENGTH_PROP_ID) {
			setLength(value);
			return;
		}
		String name = SymbolTable.name(propId);
		if (name != null) {
			Long idx = parseIndex(name);
			if (idx != null) {
				setElementDouble(idx, value);
				return;
			}
		}
		super.putDouble(propId, value);
	}

	@Override
	public void putDouble(String key, double value) {
		if ("length".equals(key)) {
			setLength(value);
			return;
		}
		Long idx = parseIndex(key);
		if (idx != null) {
			setElementDouble(idx, value);
			return;
		}
		super.putDouble(key, value);
	}

	@Override
	public boolean has(int propId) {
		if (propId == LENGTH_PROP_ID) {
			return true;
		}
		String name = SymbolTable.name(propId);
		if (name != null) {
			Long idx = parseIndex(name);
			if (idx != null) {
				return hasElement(idx);
			}
		}
		return super.has(propId);
	}

	@Override
	public boolean has(String key) {
		if ("length".equals(key)) {
			return true;
		}
		Long idx = parseIndex(key);
		if (idx != null) {
			return hasElement(idx);
		}
		return super.has(key);
	}

	@Override
	public boolean hasOwnProperty(String key) {
		if ("length".equals(key)) {
			return true;
		}
		Long idx = parseIndex(key);
		if (idx != null) {
			return hasElement(idx);
		}
		return super.hasOwnProperty(key);
	}

	@Override
	public boolean hasOwnProperty(int propId) {
		if (propId == LENGTH_PROP_ID) {
			return true;
		}
		String name = SymbolTable.name(propId);
		if (name != null) {
			Long idx = parseIndex(name);
			if (idx != null) {
				return hasElement(idx);
			}
		}
		return super.hasOwnProperty(propId);
	}

	@Override
	public void delete(int propId) {
		if (propId == LENGTH_PROP_ID) {
			return; // ECMAScript: array length is non-configurable
		}
		String name = SymbolTable.name(propId);
		if (name != null) {
			Long idx = parseIndex(name);
			if (idx != null) {
				deleteElement(idx);
				return;
			}
		}
		super.delete(propId);
	}

	@Override
	public void delete(String key) {
		if ("length".equals(key)) {
			return;
		}
		Long idx = parseIndex(key);
		if (idx != null) {
			deleteElement(idx);
			return;
		}
		super.delete(key);
	}

	//endregion
	//region 遍历与反射支持

	@Override
	public JSObject getPrototype() {
		JSObject p = super.getPrototype();
		return p != null ? p : JSContext.LazyArray.ARRAY_PROTOTYPE;
	}

	@Override
	public Set<String> keys() {
		Set<String> allKeys = new LinkedHashSet<>();
		// 1. 数组索引按升序遍历
		for (int i = 0; i < denseSize; i++) {
			if (elements[i] != HOLE) {
				allKeys.add(String.valueOf(i));
			}
		}
		if (!sparse.isEmpty()) {
			List<Long> sparseKeys = new ArrayList<>(sparse.keySet());
			Collections.sort(sparseKeys);
			for (Long k : sparseKeys) {
				allKeys.add(String.valueOf(k));
			}
		}
		// 2. 继承自 JSObject 的自定义属性
		allKeys.addAll(super.keys());
		return allKeys;
	}

	@Override
	public Map<String, Object> getProperties() {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < denseSize; i++) {
			Object val = elements[i];
			if (val != HOLE) {
				map.put(String.valueOf(i), val == JSUndefined.INSTANCE ? JSUndefined.INSTANCE : val);
			}
		}
		if (!sparse.isEmpty()) {
			List<Long> sparseKeys = new ArrayList<>(sparse.keySet());
			Collections.sort(sparseKeys);
			for (Long k : sparseKeys) {
				Object val = sparse.get(k);
				map.put(String.valueOf(k), val == NULL_SENTINEL ? null : val);
			}
		}
		map.putAll(super.getProperties());
		return map;
	}

	@Override
	public Iterator<Object> iterator() {
		return new Iterator<>() {
			private long cursor = 0;

			@Override
			public boolean hasNext() {
				return cursor < length;
			}

			@Override
			public Object next() {
				if (cursor >= length) {
					throw new NoSuchElementException();
				}
				return getElement(cursor++);
			}
		};
	}

	@Override
	public void forEach(Consumer<? super Object> action) {
		Objects.requireNonNull(action);
		long len = this.length;
		for (long i = 0; i < len; i++) {
			action.accept(getElement(i));
		}
	}

	@Override
	public String toString() {
		if (length <= 100) {
			List<Object> list = new ArrayList<>();
			for (int i = 0; i < length; i++) {
				list.add(getElement(i));
			}
			return list.toString();
		}
		return "[JSArray (length: " + length + ")]";
	}

	//endregion
	//region 静态工具方法

	public static String toPropertyKey(Object key) {
		if (key instanceof String s) return s;
		if (key instanceof Integer i) return i.toString();
		if (key instanceof Long l) return l.toString();
		if (key instanceof Number num) {
			double d = num.doubleValue();
			if (Double.isFinite(d) && d == Math.floor(d)) {
				if (d == 0.0) return "0"; // -0.0 -> "0"
				if (d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) {
					return Long.toString((long) d);
				}
			}
			return JSOps.toStr(num);
		}
		return JSOps.toStr(key);
	}

	public static Long parseIndex(String key) {
		if (key == null || key.isEmpty()) return null;
		int len = key.length();
		if (len > 10) return null;
		char first = key.charAt(0);
		if (first < '0' || first > '9') return null;
		if (len > 1 && first == '0') return null; // 排除非规范前导零如 "01", "00"
		long val = 0;
		for (int i = 0; i < len; i++) {
			char ch = key.charAt(i);
			if (ch < '0' || ch > '9') return null;
			val = val * 10 + (ch - '0');
		}
		if (val <= MAX_ARRAY_INDEX) {
			return val;
		}
		return null;
	}

	public static Long toValidArrayIndex(Object index) {
		if (index instanceof Integer i) {
			return i >= 0 ? (long) i : null;
		}
		if (index instanceof Long l) {
			long v = l;
			return (v >= 0 && v <= MAX_ARRAY_INDEX) ? l : null; // 不必重新装箱
		}
		if (index instanceof Double d) {
			double val = d;
			if (val >= 0 && val <= MAX_ARRAY_INDEX && val == (long) val) {
				return (long) val;
			}
			return null;
		}
		String key = toPropertyKey(index);
		return parseIndex(key);
	}

	public static Integer toValidJavaArrayIndex(Object index) {
		if (index instanceof Integer i) {
			return i >= 0 ? i : null; // 不必重新装箱
		}
		if (index instanceof Double d) {
			double val = d;
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				return (int) val;
			}
			return null;
		}
		Long idx = toValidArrayIndex(index);
		if (idx != null && idx <= Integer.MAX_VALUE) {
			return idx.intValue();
		}
		return null;
	}

	//endregion
}