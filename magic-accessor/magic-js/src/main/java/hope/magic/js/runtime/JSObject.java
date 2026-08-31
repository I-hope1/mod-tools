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

	public JSObject() {}

	public JSObject(JSObject prototype) {
		this.prototype = prototype;
	}

	public Object getSlot(int offset) {
		Object val = switch (offset) {
			case 0 -> slot0;
			case 1 -> slot1;
			case 2 -> slot2;
			case 3 -> slot3;
			case 4 -> slot4;
			case 5 -> slot5;
			case 6 -> slot6;
			case 7 -> slot7;
			default -> (overflowSlots != null && offset - 8 < overflowSlots.length) ? overflowSlots[offset - 8] : null;
		};
		return val == null ? JSUndefined.INSTANCE : val;
	}

	public void setSlot(int offset, Object value) {
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

	public Object get(String key) {
		int offset = shape.getOffset(key);
		if (offset >= 0) {
			return getSlot(offset);
		}
		if (prototype != null) {
			return prototype.get(key);
		}
		return JSUndefined.INSTANCE;
	}

	public void put(String key, Object value) {
		int offset = shape.getOffset(key);
		if (offset >= 0) {
			setSlot(offset, value);
			return;
		}

		// Shape 迁移 Transition
		shape = shape.addProperty(key);
		offset = shape.getOffset(key);
		setSlot(offset, value);
	}

	public boolean has(String key) {
		return shape.getOffset(key) >= 0 || (prototype != null && prototype.has(key));
	}

	public void delete(String key) {
		int offset = shape.getOffset(key);
		if (offset >= 0) {
			setSlot(offset, JSUndefined.INSTANCE);
		}
	}

	public Set<String> keys() {
		return shape.keys();
	}

	public Map<String, Object> getProperties() {
		Map<String, Object> map = new LinkedHashMap<>();
		for (String key : shape.keys()) {
			map.put(key, get(key));
		}
		return map;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (String key : shape.keys()) {
			if (!first) sb.append(", ");
			first = false;
			sb.append(key).append(": ").append(get(key));
		}
		sb.append("}");
		return sb.toString();
	}
}
