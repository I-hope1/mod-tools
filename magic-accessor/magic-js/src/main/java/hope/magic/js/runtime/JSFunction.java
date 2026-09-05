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

	default Object call4(JSContext cx, Object thisObj, Object a0, Object a1, Object a2, Object a3) throws Throwable {
		return call(cx, thisObj, new Object[]{ a0, a1, a2, a3 });
	}

	default double call0Double(JSContext cx) throws Throwable {
		return JSOps.toDouble(call0(cx, null));
	}

	default double call1Double(JSContext cx, double a0) throws Throwable {
		return JSOps.toDouble(call1(cx, null, a0));
	}

	default double call2Double(JSContext cx, double a0, double a1) throws Throwable {
		return JSOps.toDouble(call2(cx, null, a0, a1));
	}

	default double call3Double(JSContext cx, double a0, double a1, double a2) throws Throwable {
		return JSOps.toDouble(call3(cx, null, a0, a1, a2));
	}

	default double call4Double(JSContext cx, double a0, double a1, double a2, double a3) throws Throwable {
		return JSOps.toDouble(call4(cx, null, a0, a1, a2, a3));
	}
}
