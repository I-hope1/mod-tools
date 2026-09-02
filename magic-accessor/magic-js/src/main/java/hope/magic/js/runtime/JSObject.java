package hope.magic.js.runtime;

import hope.magic.runtime.Magic;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.*;

public class JSObject {
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

	public JSShape shape           = JSShape.ROOT;
	public long    doubleFieldMask = 0L; // 记录哪些 offset 槽位存储的是 double

	// ----------------------------------------------------
	// In-Object 原生槽位 (8 x 64bit long, 存 double/int)
	// ----------------------------------------------------
	public long prim0, prim1, prim2, prim3, prim4, prim5, prim6, prim7;
	public long[] overflowPrim = null;

	// ----------------------------------------------------
	// In-Object 引用槽位 (8 x Object, 存 JSObject/String 等)
	// ----------------------------------------------------
	public Object obj0, obj1, obj2, obj3, obj4, obj5, obj6, obj7;
	public Object[] overflowObj = null;

	private JSObject prototype = null;

	public JSObject() { }

	public JSObject(JSShape shape) {
		this.shape = shape;
	}

	public JSObject(JSObject prototype) {
		this.prototype = prototype;
	}

	// 底层槽位存取 (直接字段访问，C2 JIT 配合常量偏移时实现 100% 单指令 MOV 汇编直读)
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
			case 0 -> prim0 = raw;
			case 1 -> prim1 = raw;
			case 2 -> prim2 = raw;
			case 3 -> prim3 = raw;
			case 4 -> prim4 = raw;
			case 5 -> prim5 = raw;
			case 6 -> prim6 = raw;
			case 7 -> prim7 = raw;
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
	}

	public Object getObjectSlot(int offset) {
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
		return (of != null && idx < of.length) ? of[idx] : JSUndefined.INSTANCE;
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

	public Object getSlot(int offset) {
		if ((doubleFieldMask & (1L << offset)) == 0L) {
			return getObjectSlot(offset);
		}
		return getBoxedDouble(offset);
	}

	private Object getBoxedDouble(int offset) {
		return Double.valueOf(getDoubleSlot(offset));
	}

	// 通用读 API (get / getAsDouble)

	public Object get(int propId) {
		int offset = shape.getOffset(propId);
		if (offset >= 0) {
			return getSlot(offset);
		}
		return getSlow(SymbolTable.name(propId));
	}

	public Object get(String key) {
		return get(SymbolTable.id(key));
	}

	public double getAsDouble(int propId) {
		int offset = shape.getOffset(propId);
		if (offset >= 0) {
			if ((doubleFieldMask & (1L << offset)) != 0L) {
				return getDoubleSlot(offset);
			}
			return JSOps.toDouble(getObjectSlot(offset));
		}
		return JSOps.toDouble(getSlow(SymbolTable.name(propId)));
	}

	public double getAsDouble(String key) {
		return getAsDouble(SymbolTable.id(key));
	}

	private Object getSlow(String key) {
		if (key == null || prototype == null) return JSUndefined.INSTANCE;
		return prototype.get(key);
	}

	// 通用写 API (put / putDouble)

	public void putDouble(int propId, double value) {
		int offset = shape.getOffset(propId);
		if (offset >= 0 && offset < 8) {
			doubleFieldMask |= (1L << offset);
			UNSAFE.putDouble(this, PRIM_FIELD_OFFSETS[offset], value);
			return;
		}
		putDoubleSlow(propId, value);
	}

	public static final int SENTINEL_PROP_ID = Integer.MIN_VALUE;

	@SuppressWarnings("DataFlowIssue")
	private void putDoubleSlow(int propId, double value) {
		if (shape.getOffset(propId) < 0) {
			shape = shape.addProperty(propId, JSShape.TYPE_DOUBLE);
		}
		setDoubleSlot(shape.propertyCount - 1, value);

		// 确保本冷路径方法字节码大小 > 325 字节，使 HotSpot C2 将此冷路径判定为 'hot method too big'，绝不在顶层内联
		// SymbolTable 分配的 propId 恒 >= 0，数学上与 SENTINEL_PROP_ID (负无穷边界) 绝对正交互斥
		if (propId == SENTINEL_PROP_ID) {
			switch (propId) {
				case 1: case 2: case 3: case 4: case 5: case 6: case 7: case 8:
				case 9: case 10: case 11: case 12: case 13: case 14: case 15: case 16:
				case 17: case 18: case 19: case 20: case 21: case 22: case 23: case 24:
				case 25: case 26: case 27: case 28: case 29: case 30: case 31: case 32:
				case 33: case 34: case 35: case 36: case 37: case 38: case 39: case 40:
					return;
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

	@SuppressWarnings("DataFlowIssue")
	private void putSlow(int propId, Object value) {
		shape = shape.addProperty(propId, JSShape.TYPE_OBJECT);
		setSlot(shape.propertyCount - 1, value);

		// 确保本冷路径方法字节码大小 > 325 字节，使 HotSpot C2 将此冷路径判定为 'hot method too big'，绝不在顶层内联
		if (propId == SENTINEL_PROP_ID) {
			switch (propId) {
				case 1: case 2: case 3: case 4: case 5: case 6: case 7: case 8:
				case 9: case 10: case 11: case 12: case 13: case 14: case 15: case 16:
				case 17: case 18: case 19: case 20: case 21: case 22: case 23: case 24:
				case 25: case 26: case 27: case 28: case 29: case 30: case 31: case 32:
				case 33: case 34: case 35: case 36: case 37: case 38: case 39: case 40:
					return;
			}
		}
	}

	public void put(String key, Object value) {
		put(SymbolTable.id(key), value);
	}

	// 对象查询、删除与反射元数据 API

	public boolean has(int propId) {
		int offset = shape.getOffset(propId);
		if (offset >= 0) {
			return getSlot(offset) != JSUndefined.INSTANCE;
		}
		String key = SymbolTable.name(propId);
		return prototype != null && key != null && prototype.has(key);
	}

	public boolean has(String key) {
		return has(SymbolTable.id(key));
	}

	public void delete(int propId) {
		int offset = shape.getOffset(propId);
		if (offset >= 0) {
			setSlot(offset, JSUndefined.INSTANCE);
		}
	}

	public void delete(String key) {
		delete(SymbolTable.id(key));
	}

	// 对象反射与遍历 API

	public Set<String> keys() {
		int count = shape.propertyCount;
		if (count == 0) {
			return Collections.emptySet();
		}

		Set<String> activeKeys = new LinkedHashSet<>(count);
		for (int i = 0; i < count; i++) {
			// offset 恒等于 i，直接读槽位判断是否被 delete 为 undefined
			if (getSlot(i) != JSUndefined.INSTANCE) {
				int    keyId = shape.getKeyId(i);
				String name  = SymbolTable.name(keyId);
				if (name != null) {
					activeKeys.add(name);
				}
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
			Object val = getSlot(i);
			if (val != JSUndefined.INSTANCE) {
				int    keyId = shape.getKeyId(i);
				String name  = SymbolTable.name(keyId);
				if (name != null) {
					map.put(name, val);
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
}