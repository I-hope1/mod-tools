package hope.magic.js.runtime;

import hope.magic.runtime.Magic;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.*;

public class JSObject {
	//region 初始化
	/** 内部删除哨兵（Tombstone），专用于区分“属性不存在”与“属性值为 undefined” */
	static final Object DELETED = new Object() {
		@Override
		public String toString() {
			return "<deleted>";
		}
	};

	public static final int IN_OBJECT_FIELD_COUNT     = 8;
	public static final int IN_OBJECT_SLOTS           = IN_OBJECT_FIELD_COUNT;
	public static final int OVERFLOW_INITIAL_CAPACITY = 4;

	public static final Unsafe UNSAFE             = Magic.unsafe;
	public static final long[] PRIM_FIELD_OFFSETS = new long[IN_OBJECT_FIELD_COUNT];
	public static final long[] OBJ_FIELD_OFFSETS  = new long[IN_OBJECT_FIELD_COUNT];

	static {
		try {
			for (int i = 0; i < IN_OBJECT_FIELD_COUNT; i++) {
				Field pField = JSObject.class.getDeclaredField("prim" + i);
				Field oField = JSObject.class.getDeclaredField("obj" + i);
				PRIM_FIELD_OFFSETS[i] = UNSAFE.objectFieldOffset(pField);
				OBJ_FIELD_OFFSETS[i] = UNSAFE.objectFieldOffset(oField);
			}
		} catch (Exception e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	public JSShape shape = JSShape.ROOT;
	public long    doubleFieldMask/*  = 0L */; // 记录哪些 offset 槽位存储的是 double

	public long prim0, prim1, prim2, prim3, prim4, prim5, prim6, prim7;
	public long[] overflowPrim/*  = null */;

	public Object obj0, obj1, obj2, obj3, obj4, obj5, obj6, obj7;
	public Object[] overflowObj/*  = null */;

	private JSObject prototype/*  = null */;

	//endregion
	//region 构造器
	public JSObject() { }

	public JSObject(JSShape shape) {
		this.shape = shape;
	}

	public JSObject(JSObject prototype) {
		this.prototype = prototype;
	}

	//endregion
	//region 槽位访问

	public double getDoubleSlot(int offset) {
		return switch (offset) {
			case 0 -> Double.longBitsToDouble(prim0);
			case 1 -> Double.longBitsToDouble(prim1);
			case 2 -> Double.longBitsToDouble(prim2);
			case 3 -> Double.longBitsToDouble(prim3);
			case 4 -> Double.longBitsToDouble(prim4);
			case 5 -> Double.longBitsToDouble(prim5);
			case 6 -> Double.longBitsToDouble(prim6);
			case 7 -> Double.longBitsToDouble(prim7);
			default -> getOverflowDouble(offset - IN_OBJECT_FIELD_COUNT);
		};
	}

	private double getOverflowDouble(int idx) {
		return (overflowPrim != null && idx < overflowPrim.length)
		 ? Double.longBitsToDouble(overflowPrim[idx])
		 : Double.NaN;
	}

	public void setDoubleSlot(int offset, double value) {
		doubleFieldMask |= (1L << offset);
		long raw = Double.doubleToRawLongBits(value);
		switch (offset) {
			case 0 -> {
				prim0 = raw;
				obj0 = null;
			}
			case 1 -> {
				prim1 = raw;
				obj1 = null;
			}
			case 2 -> {
				prim2 = raw;
				obj2 = null;
			}
			case 3 -> {
				prim3 = raw;
				obj3 = null;
			}
			case 4 -> {
				prim4 = raw;
				obj4 = null;
			}
			case 5 -> {
				prim5 = raw;
				obj5 = null;
			}
			case 6 -> {
				prim6 = raw;
				obj6 = null;
			}
			case 7 -> {
				prim7 = raw;
				obj7 = null;
			}
			default -> setOverflowDouble(offset - IN_OBJECT_FIELD_COUNT, value);
		}
	}

	private void setOverflowDouble(int idx, double value) {
		long raw = Double.doubleToRawLongBits(value);
		if (overflowPrim == null) {
			overflowPrim = new long[Math.max(OVERFLOW_INITIAL_CAPACITY, idx + 1)];
		} else if (idx >= overflowPrim.length) {
			overflowPrim = Arrays.copyOf(overflowPrim, Math.max(overflowPrim.length * 2, idx + 1));
		}
		overflowPrim[idx] = raw;
		if (overflowObj != null && idx < overflowObj.length) {
			overflowObj[idx] = null;
		}
	}

	public Object getRawObjectSlot(int offset) {
		return switch (offset) {
			case 0 -> obj0;
			case 1 -> obj1;
			case 2 -> obj2;
			case 3 -> obj3;
			case 4 -> obj4;
			case 5 -> obj5;
			case 6 -> obj6;
			case 7 -> obj7;
			default -> getOverflowObject(offset - IN_OBJECT_FIELD_COUNT);
		};
	}

	private Object getOverflowObject(int idx) {
		Object[] of = overflowObj;
		return (of != null && idx < of.length) ? of[idx] : DELETED;
	}

	public Object getObjectSlot(int offset) {
		Object val = getRawObjectSlot(offset);
		return (val == DELETED) ? JSUndefined.INSTANCE : val;
	}

	public void setSlot(int offset, Object value) {
		if (doubleFieldMask != 0L) clearDoubleMask(offset);
		switch (offset) {
			case 0 -> obj0 = value;
			case 1 -> obj1 = value;
			case 2 -> obj2 = value;
			case 3 -> obj3 = value;
			case 4 -> obj4 = value;
			case 5 -> obj5 = value;
			case 6 -> obj6 = value;
			case 7 -> obj7 = value;
			default -> setOverflowSlot(offset, value);
		}
	}

	public void clearDoubleMask(int offset) {
		doubleFieldMask &= ~(1L << offset);
	}

	private void clearPrimSlot(int offset) {
		switch (offset) {
			case 0 -> prim0 = 0L;
			case 1 -> prim1 = 0L;
			case 2 -> prim2 = 0L;
			case 3 -> prim3 = 0L;
			case 4 -> prim4 = 0L;
			case 5 -> prim5 = 0L;
			case 6 -> prim6 = 0L;
			case 7 -> prim7 = 0L;
			default -> {
				if (overflowPrim != null && offset - IN_OBJECT_FIELD_COUNT < overflowPrim.length) {
					overflowPrim[offset - IN_OBJECT_FIELD_COUNT] = 0L;
				}
			}
		}
	}

	private void setOverflowSlot(int offset, Object value) {
		setOverflowObject(offset - IN_OBJECT_FIELD_COUNT, value);
	}

	private void setOverflowObject(int idx, Object value) {
		if (overflowObj == null) {
			overflowObj = new Object[Math.max(OVERFLOW_INITIAL_CAPACITY, idx + 1)];
		} else if (idx >= overflowObj.length) {
			overflowObj = Arrays.copyOf(overflowObj, Math.max(overflowObj.length * 2, idx + 1));
		}
		overflowObj[idx] = value;
	}

	/**
	 * 供 Linker / IC 快速读槽位：若为 DELETED，严格返回 JSUndefined.INSTANCE，绝不泄露内部哨兵
	 */
	public Object getSlot(int offset) {
		if ((doubleFieldMask & (1L << offset)) == 0L) {
			return getObjectSlot(offset);
		}
		return getBoxedDouble(offset);
	}

	private Object getBoxedDouble(int offset) {
		return getDoubleSlot(offset);
	}

	//endregion
	//region 通用读 API (遇 DELETED 视为自身无属性，回退原型链)
	public Object get(int propId) {
		int offset = shape.getOffset(propId);
		if (offset >= 0) {
			if ((doubleFieldMask & (1L << offset)) != 0L) {
				return getBoxedDouble(offset);
			}
			Object val = getRawObjectSlot(offset);
			if (val != DELETED) {
				return val; // 包括 null 与 JSUndefined.INSTANCE 均属于合法属性值
			}
		}
		return getSlow(propId);
	}

	public Object get(String key) {
		int symId = SymbolTable.lookupId(key);
		if (symId == SymbolTable.NO_SYMBOL) {
			return (prototype != null) ? prototype.get(key) : JSUndefined.INSTANCE;
		}
		return get(symId);
	}

	public double getAsDouble(int propId) {
		int offset = shape.getOffset(propId);
		if (offset >= 0) {
			if ((doubleFieldMask & (1L << offset)) != 0L) {
				return getDoubleSlot(offset);
			}
			Object val = getRawObjectSlot(offset);
			if (val != DELETED) {
				return JSOps.toDouble(val);
			}
		}
		return JSOps.toDouble(getSlow(propId));
	}

	public double getAsDouble(String key) {
		int symId = SymbolTable.lookupId(key);
		if (symId == SymbolTable.NO_SYMBOL) {
			return (prototype != null) ? prototype.getAsDouble(key) : Double.NaN;
		}
		return getAsDouble(symId);
	}

	private Object getSlow(int propId) {
		if (propId < 0 || prototype == null) return JSUndefined.INSTANCE;
		return prototype.get(propId);
	}

	//endregion
	//region 通用写 API (覆盖原值或 DELETED 槽位)

	public void putDouble(int propId, double value) {
		int offset = shape.getOffset(propId);
		if (offset < 0 || offset >= 8) {
			putDoubleSlow(propId, value);
			return;
		}
		setDoubleSlot(offset, value);
	}

	public static final int SENTINEL_PROP_ID = Integer.MIN_VALUE;

	@SuppressWarnings({"DataFlowIssue", "DuplicatedCode"})
	private void putDoubleSlow(int propId, double value) {
		int offset = shape.getOffset(propId);
		if (offset < 0) {
			shape = shape.addProperty(propId, JSShape.TYPE_DOUBLE);
			offset = shape.propertyCount - 1;
		}
		setDoubleSlot(offset, value);

		// 确保本慢路径方法字节码大小 > 325 字节，使 HotSpot C2 将此冷路径判定为 'hot method too big'，绝不在顶层内联
		// 让 C2 有更多预算内联其他方法
		if (propId == SENTINEL_PROP_ID) {
			switch (propId) {
				// 1-70
				case 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
				     21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
				     41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60,
				     61, 62, 63, 64, 65, 66, 67, 68, 69, 70 -> {
					return;
				}
			}
		}
	}

	public void putDouble(String key, double value) {
		putDouble(SymbolTable.id(key), value);
	}

	public void put(int propId, Object value) {
		if (value instanceof Number num) {
			putDouble(propId, num.doubleValue());
			return;
		}

		int offset = shape.getOffset(propId);
		if (offset >= 0) {
			setSlot(offset, value);
			return;
		}
		putSlow(propId, value);
	}

	@SuppressWarnings({"DataFlowIssue", "DuplicatedCode"})
	private void putSlow(int propId, Object value) {
		shape = shape.addProperty(propId, JSShape.TYPE_OBJECT);
		setSlot(shape.propertyCount - 1, value);

		// 确保本慢路径方法字节码大小 > 325 字节，使 HotSpot C2 将此冷路径判定为 'hot method too big'，绝不在顶层内联
		// 让 C2 有更多预算内联其他方法
		if (propId == SENTINEL_PROP_ID) {
			switch (propId) {
				// 1-70
				case 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
				     21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
				     41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60,
				     61, 62, 63, 64, 65, 66, 67, 68, 69, 70 -> {
					return;
				}
			}
		}
	}

	public void put(String key, Object value) {
		put(SymbolTable.id(key), value);
	}

	//endregion
	//region 查询与删除 API

	public boolean has(int propId) {
		int offset = shape.getOffset(propId);
		if (offset >= 0) {
			if ((doubleFieldMask & (1L << offset)) != 0L) {
				return true;
			}
			// 只有 DELETED 表示不存在；null 和 undefined 均为对象上的有效属性
			return getRawObjectSlot(offset) != DELETED;
		}
		return prototype != null && propId >= 0 && prototype.has(propId);
	}

	public boolean has(String key) {
		int symId = SymbolTable.lookupId(key);
		if (symId == SymbolTable.NO_SYMBOL) {
			return prototype != null && prototype.has(key);
		}
		return has(symId);
	}

	public void delete(int propId) {
		int offset = shape.getOffset(propId);
		if (offset >= 0) {
			clearPrimSlot(offset);
			setSlot(offset, DELETED); // setSlot 里有 clearDoubleMask(offset);

		}
	}

	public void delete(String key) {
		delete(SymbolTable.id(key));
	}

	//endregion
	//region 反射与遍历 API (仅过滤 DELETED，保留 undefined 属性)

	public Set<String> keys() {
		int count = shape.propertyCount;
		if (count == 0) {
			return Collections.emptySet();
		}

		Set<String> activeKeys = new LinkedHashSet<>(count);
		for (int i = 0; i < count; i++) {
			if ((doubleFieldMask & (1L << i)) != 0L || getRawObjectSlot(i) != DELETED) {
				int    keyId = shape.getKeyId(i);
				String name  = SymbolTable.name(keyId);
				if (name != null) activeKeys.add(name);
			}
		}
		return activeKeys;
	}

	public Map<String, Object> getProperties() {
		int count = shape.propertyCount;
		if (count == 0) {
			return Collections.emptyMap();
		}

		Map<String, Object> map = new LinkedHashMap<>(count);
		for (int i = 0; i < count; i++) {
			if ((doubleFieldMask & (1L << i)) != 0L) {
				int    keyId = shape.getKeyId(i);
				String name  = SymbolTable.name(keyId);
				if (name != null) {
					map.put(name, getBoxedDouble(i));
				}
			} else {
				Object raw = getRawObjectSlot(i);
				if (raw != DELETED) {
					int    keyId = shape.getKeyId(i);
					String name  = SymbolTable.name(keyId);
					if (name != null) {
						map.put(name, raw);
					}
				}
			}
		}
		return map;
	}

	@Override
	public String toString() {
		StringBuilder sb    = new StringBuilder("{");
		boolean       first = true;
		for (String key : keys()) {
			if (!first) sb.append(", ");
			first = false;
			sb.append(key).append(": ").append(get(key));
		}
		sb.append("}");
		return sb.toString();
	}
	//endregion
}