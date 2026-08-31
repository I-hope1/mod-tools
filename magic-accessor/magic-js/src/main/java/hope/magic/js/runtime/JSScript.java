package hope.magic.js.runtime;

public abstract class JSScript {
	public abstract Object run(JSContext cx) throws Throwable;
}
