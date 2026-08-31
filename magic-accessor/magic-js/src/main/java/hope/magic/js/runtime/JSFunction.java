package hope.magic.js.runtime;

@FunctionalInterface
public interface JSFunction {
	Object call(JSContext cx, Object thisObj, Object[] args) throws Throwable;
}
