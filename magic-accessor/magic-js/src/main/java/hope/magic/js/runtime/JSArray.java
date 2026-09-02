package hope.magic.js.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Consumer;

public class JSArray extends JSObject implements Iterable<Object> {
	public static final int  MAX_DENSE_CAPACITY     = 65536;
	public static final int  INITIAL_DENSE_CAPACITY = 8;
	public static final long MAX_ARRAY_INDEX        = 4294967294L; // 2^32 - 2 (ECMAScript 规范标准上限)

	private static final Object   NULL_SENTINEL  = new Object();
	private static final Object[] EMPTY_ELEMENTS = new Object[0];

	public Object[] elements = EMPTY_ELEMENTS;
	public int denseSize = 0;
	private final Map<Long, Object> sparse = new HashMap<>();
	private long length = 0;

	public JSArray() {}

	public JSArray(Collection<?> initial) {
		if (initial != null) {
			int sz = initial.size();
			if (sz > 0 && sz <= MAX_DENSE_CAPACITY) {
				this.elements = new Object[Math.max(sz, INITIAL_DENSE_CAPACITY)];
				int idx = 0;
				for (Object elem : initial) {
					this.elements[idx++] = elem;
				}
				for (int i = idx; i < elements.length; i++) {
					this.elements[i] = JSUndefined.INSTANCE;
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
		if (initial != null) {
			if (initial instanceof Collection<?> c) {
				int sz = c.size();
				if (sz > 0 && sz <= MAX_DENSE_CAPACITY) {
					this.elements = new Object[Math.max(sz, INITIAL_DENSE_CAPACITY)];
					int idx = 0;
					for (Object elem : c) {
						this.elements[idx++] = elem;
					}
					for (int i = idx; i < elements.length; i++) {
						this.elements[i] = JSUndefined.INSTANCE;
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

	public long length() {
		return length;
	}

	public Object getElement(int index) {
		if (index >= 0 && index < denseSize) {
			return elements[index];
		}
		return getElementSlow(index);
	}

	public Object getElement(long index) {
		if (index >= 0 && index < denseSize) {
			return elements[(int) index];
		}
		return getElementSlow(index);
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

	private void grow(int minCapacity) {
		int oldCap = elements.length;
		int newCap = oldCap == 0 ? INITIAL_DENSE_CAPACITY : (oldCap + (oldCap >> 1));
		if (newCap < minCapacity) newCap = minCapacity;
		if (newCap > MAX_DENSE_CAPACITY) newCap = MAX_DENSE_CAPACITY;
		Object[] newArr = new Object[newCap];
		System.arraycopy(elements, 0, newArr, 0, denseSize);
		for (int i = denseSize; i < newCap; i++) {
			newArr[i] = JSUndefined.INSTANCE;
		}
		this.elements = newArr;
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
			while (denseSize <= intIdx) {
				elements[denseSize++] = JSUndefined.INSTANCE;
			}
			elements[intIdx] = value;
		} else {
			sparse.put(index, value == null ? NULL_SENTINEL : value);
		}
		if (index + 1L > length) {
			length = index + 1L;
		}
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

	public Object pop() {
		if (length == 0) return JSUndefined.INSTANCE;
		long lastIdx = length - 1;
		Object val = getElement(lastIdx);
		if (lastIdx < denseSize) {
			denseSize = (int) lastIdx;
			elements[denseSize] = JSUndefined.INSTANCE;
		} else {
			sparse.remove(lastIdx);
		}
		length = lastIdx;
		return val;
	}

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
		if (val <= 4294967294L) { // ECMAScript: [0, 2^32 - 2]
			return val;
		}
		return null;
	}

	public static Long toValidArrayIndex(Object index) {
		if (index instanceof Integer i) {
			int v = i.intValue();
			return v >= 0 ? (long) v : null;
		}
		if (index instanceof Long l) {
			long v = l.longValue();
			return (v >= 0 && v <= 4294967294L) ? v : null;
		}
		if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= 4294967294L && val == (long) val) {
				return (long) val;
			}
			return null;
		}
		String key = toPropertyKey(index);
		return parseIndex(key);
	}

	public static Integer toValidJavaArrayIndex(Object index) {
		if (index instanceof Integer i) {
			int v = i.intValue();
			return v >= 0 ? v : null;
		}
		if (index instanceof Double d) {
			double val = d.doubleValue();
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
	public void put(String key, Object value) {
		if ("length".equals(key)) {
			if (value instanceof Number num) {
				double d = num.doubleValue();
				if (Double.isFinite(d) && d >= 0 && d < 4294967295L && d == Math.floor(d)) {
					long newLen = (long) d;
					this.length = newLen;
					if (newLen < denseSize) {
						for (int i = (int) newLen; i < denseSize; i++) {
							elements[i] = JSUndefined.INSTANCE;
						}
						denseSize = (int) newLen;
					}
					sparse.keySet().removeIf(k -> k >= newLen);
					return;
				}
			}
			throw new IllegalArgumentException("RangeError: Invalid array length: " + value);
		}
		Long idx = parseIndex(key);
		if (idx != null) {
			setElement(idx, value);
			return;
		}
		super.put(key, value);
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
}
