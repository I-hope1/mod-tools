package hope.magic.js.runtime;

import java.util.*;

public class JSObject {
	public static final int IN_OBJECT_SLOTS = 8;
	public JSShape shape = JSShape.ROOT;
	public Object slot0;
	public Object slot1;
	public Object slot2;
	public Object slot3;
	public Object slot4;
	public Object slot5;
	public Object slot6;
	public Object slot7;
	public Object[] overflowSlots = null;
	private JSObject prototype = null;

	public double[] doubleSlots = null;
	public long doubleFieldMask = 0L;

	public JSObject() {}

	public JSObject(JSObject prototype) {
		this.prototype = prototype;
	}

	public Object getSlot(int offset) {
		if (offset < 64 && ((doubleFieldMask >>> offset) & 1L) != 0L) {
			return doubleSlots[offset];
		}
		if (offset == 0) return slot0;
		if (offset == 1) return slot1;
		if (offset == 2) return slot2;
		if (offset == 3) return slot3;
		return getSlotSlow(offset);
	}

	public double getDoubleSlot(int offset) {
		if (offset < 64 && ((int) (doubleFieldMask >>> offset) & 1) != 0) {
			return doubleSlots[offset];
		}
		return getDoubleSlotSlow(offset);
	}

	public double getDoubleSlotSlow(int offset) {
		Object v = getSlot(offset);
		return JSOps.toDouble(v);
	}

	public Object getSlotSlow(int offset) {
		if (offset == 4) return slot4;
		if (offset == 5) return slot5;
		if (offset == 6) return slot6;
		if (offset == 7) return slot7;
		Object[] of = overflowSlots;
		return (of != null && offset - 8 < of.length) ? of[offset - 8] : JSUndefined.INSTANCE;
	}

	public void setDoubleSlot(int offset, double value) {
		if (doubleSlots == null || offset >= doubleSlots.length) {
			ensureDoubleCapacity(offset);
		}
		doubleSlots[offset] = value;
		doubleFieldMask |= (1L << offset);
	}
	private void ensureDoubleCapacity(int offset) {
		double[] newArr = new double[Math.max(IN_OBJECT_SLOTS, offset + 1)];
		if (doubleSlots != null) System.arraycopy(doubleSlots, 0, newArr, 0, doubleSlots.length);
		doubleSlots = newArr;
	}

	public void setSlot(int offset, Object value) {
		if (value instanceof Number num) {
			setDoubleSlot(offset, num.doubleValue());
		} else {
			doubleFieldMask &= ~(1L << offset);
		}
		switch (offset) {
			case 0 -> slot0 = value;
			case 1 -> slot1 = value;
			case 2 -> slot2 = value;
			case 3 -> slot3 = value;
			case 4 -> slot4 = value;
			case 5 -> slot5 = value;
			case 6 -> slot6 = value;
			case 7 -> slot7 = value;
			default -> {
				int idx = offset - 8;
				if (overflowSlots == null) {
					overflowSlots = new Object[Math.max(4, idx + 1)];
				} else if (idx >= overflowSlots.length) {
					overflowSlots = Arrays.copyOf(overflowSlots, Math.max(overflowSlots.length * 2, idx + 1));
				}
				overflowSlots[idx] = value;
			}
		}
	}

	public Object get(int propId) {
		int offset = shape.getOffset(propId);
		if (offset >= 0) return getSlot(offset);
		return getSlow(SymbolTable.name(propId));
	}

	public double getAsDouble(int propId) {
		int offset = shape.getOffset(propId);
		if (offset >= 0) return getDoubleSlot(offset);
		Object val = getSlow(SymbolTable.name(propId));
		return JSOps.toDouble(val);
	}

	public Object get(String key) {
		int propId = SymbolTable.id(key);
		return get(propId);
	}

	public double getAsDouble(String key) {
		int propId = SymbolTable.id(key);
		return getAsDouble(propId);
	}

	private Object getSlow(String key) {
		if (key == null) return JSUndefined.INSTANCE;
		return prototype != null ? prototype.get(key) : JSUndefined.INSTANCE;
	}

	public void put(int propId, Object value) {
		int offset = shape.getOffset(propId);
		if (offset >= 0) {
			setSlot(offset, value);
			return;
		}

		// Shape 迁移 Transition
		byte type = (value instanceof Number) ? JSShape.TYPE_DOUBLE : JSShape.TYPE_OBJECT;
		shape = shape.addProperty(propId, type);
		offset = shape.getOffset(propId);
		setSlot(offset, value);
	}

	public void putDouble(int propId, double value) {
		int offset = shape.getOffset(propId);
		if (offset >= 0) {
			setDoubleSlot(offset, value);
			setSlot(offset, value);
			return;
		}

		shape = shape.addProperty(propId, JSShape.TYPE_DOUBLE);
		offset = shape.getOffset(propId);
		setDoubleSlot(offset, value);
		setSlot(offset, value);
	}

	public void put(String key, Object value) {
		put(SymbolTable.id(key), value);
	}

	public void putDouble(String key, double value) {
		putDouble(SymbolTable.id(key), value);
	}

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

	public Set<String> keys() {
		Set<String> activeKeys = new LinkedHashSet<>();
		int[] keyIds = shape.keyIds;
		int[] offsets = shape.offsets;
		for (int i = 0; i < keyIds.length; i++) {
			int offset = offsets[i];
			if (offset >= 0 && getSlot(offset) != JSUndefined.INSTANCE) {
				String name = SymbolTable.name(keyIds[i]);
				if (name != null) activeKeys.add(name);
			}
		}
		return activeKeys;
	}

	public Map<String, Object> getProperties() {
		Map<String, Object> map = new LinkedHashMap<>();
		int[] keyIds = shape.keyIds;
		int[] offsets = shape.offsets;
		for (int i = 0; i < keyIds.length; i++) {
			int offset = offsets[i];
			if (offset >= 0) {
				Object val = getSlot(offset);
				if (val != JSUndefined.INSTANCE) {
					String name = SymbolTable.name(keyIds[i]);
					if (name != null) map.put(name, val);
				}
			}
		}
		return map;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (String key : keys()) {
			if (!first) sb.append(", ");
			first = false;
			sb.append(key).append(": ").append(get(key));
		}
		sb.append("}");
		return sb.toString();
	}
}
