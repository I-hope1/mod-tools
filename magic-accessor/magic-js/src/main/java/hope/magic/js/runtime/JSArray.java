package hope.magic.js.runtime;

import java.util.ArrayList;
import java.util.List;

public class JSArray extends JSObject {
	private final List<Object> elements = new ArrayList<>();

	public JSArray() {}

	public JSArray(List<Object> initial) {
		if (initial != null) {
			this.elements.addAll(initial);
		}
	}

	public int length() {
		return elements.size();
	}

	public Object getElement(int index) {
		if (index >= 0 && index < elements.size()) {
			return elements.get(index);
		}
		return JSUndefined.INSTANCE;
	}

	public void setElement(int index, Object value) {
		while (elements.size() <= index) {
			elements.add(JSUndefined.INSTANCE);
		}
		elements.set(index, value);
	}

	public void push(Object value) {
		elements.add(value);
	}

	public Object pop() {
		if (elements.isEmpty()) return JSUndefined.INSTANCE;
		return elements.remove(elements.size() - 1);
	}

	@Override
	public Object get(String key) {
		if ("length".equals(key)) {
			return (double) elements.size();
		}
		try {
			int idx = Integer.parseInt(key);
			return getElement(idx);
		} catch (NumberFormatException ignored) {
		}
		return super.get(key);
	}

	@Override
	public void put(String key, Object value) {
		try {
			int idx = Integer.parseInt(key);
			setElement(idx, value);
			return;
		} catch (NumberFormatException ignored) {
		}
		super.put(key, value);
	}

	@Override
	public String toString() {
		return elements.toString();
	}
}
