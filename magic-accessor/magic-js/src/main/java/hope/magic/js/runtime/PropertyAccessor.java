package hope.magic.js.runtime;

/**
 * 访问器属性对（Accessor Pair）。
 * 存储 Object.defineProperty 定义的 getter 和 setter 函数。
 * 该对象直接保存在 JSObject 的属性槽位中，由 JSShape 标记为 FLAG_ACCESSOR。
 */
public final class PropertyAccessor {
	public final JSFunction getter;
	public final JSFunction setter;

	public PropertyAccessor(JSFunction getter, JSFunction setter) {
		this.getter = getter;
		this.setter = setter;
	}

	public Object callGetter(JSContext cx, Object receiver) {
		if (getter == null) {
			return JSUndefined.INSTANCE;
		}
		try {
			return getter.call0(cx, receiver);
		} catch (Throwable t) {
			if (t instanceof RuntimeException re) throw re;
			throw new RuntimeException(t);
		}
	}

	public void callSetter(JSContext cx, Object receiver, Object value) {
		if (setter == null) {
			return; // 非严格模式下静默忽略
		}
		try {
			setter.call1(cx, receiver, value);
		} catch (Throwable t) {
			if (t instanceof RuntimeException re) throw re;
			throw new RuntimeException(t);
		}
	}

	@Override
	public String toString() {
		return "[PropertyAccessor getter=" + getter + ", setter=" + setter + "]";
	}
}
