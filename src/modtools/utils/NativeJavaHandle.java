package modtools.utils;

import modtools.utils.ui.MethodBuilder;
import rhino.*;

import java.lang.invoke.MethodHandle;

public class NativeJavaHandle extends BaseFunction {
	private final MethodHandle handle;
	public NativeJavaHandle(Scriptable scope, MethodHandle handle) {
		super(scope, null);
		this.handle = handle;
	}
	public String toString() {
		return handle.toString();
	}
	public Object get(Object key) {
		if ("__javaObject__".equals(key)) return handle;
		return super.get(key);
	}
	public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
		try {
			return cx.getWrapFactory().wrap(cx, scope, MethodBuilder.invokeForHandle(handle, args), handle.type().returnType());
		} catch (Throwable ex) {
			throw new RuntimeException(ex);
		}
	}
}
