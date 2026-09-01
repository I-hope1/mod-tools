package hope.magic.js.runtime;

@FunctionalInterface
public interface JSFunction {

	Object[] EMPTY_ARGS = new Object[0];

	Object call(JSContext cx, Object thisObj, Object[] args) throws Throwable;

	default Object call0(JSContext cx, Object thisObj) throws Throwable {
		return call(cx, thisObj, EMPTY_ARGS);
	}

	default Object call1(JSContext cx, Object thisObj, Object a0) throws Throwable {
		return call(cx, thisObj, new Object[]{ a0 });
	}

	default Object call2(JSContext cx, Object thisObj, Object a0, Object a1) throws Throwable {
		return call(cx, thisObj, new Object[]{ a0, a1 });
	}

	default Object call3(JSContext cx, Object thisObj, Object a0, Object a1, Object a2) throws Throwable {
		return call(cx, thisObj, new Object[]{ a0, a1, a2 });
	}
}
