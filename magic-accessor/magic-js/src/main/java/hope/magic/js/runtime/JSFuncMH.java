package hope.magic.js.runtime;

import java.lang.invoke.*;

public final class JSFuncMH {
	public static final MethodHandle
	 CALL  = JSLinker.findVirtualMH(JSFunction.class, "call", MethodType.methodType(Object.class, JSContext.class, Object.class, Object[].class)),
	 CALL0 = JSLinker.findVirtualMH(JSFunction.class, "call0", MethodType.methodType(Object.class, JSContext.class, Object.class)),
	 CALL1 = JSLinker.findVirtualMH(JSFunction.class, "call1", MethodType.methodType(Object.class, JSContext.class, Object.class, Object.class)),
	 CALL2 = JSLinker.findVirtualMH(JSFunction.class, "call2", MethodType.methodType(Object.class, JSContext.class, Object.class, Object.class, Object.class)),
	 CALL3 = JSLinker.findVirtualMH(JSFunction.class, "call3", MethodType.methodType(Object.class, JSContext.class, Object.class, Object.class, Object.class, Object.class)),
	 CALL4 = JSLinker.findVirtualMH(JSFunction.class, "call4", MethodType.methodType(Object.class, JSContext.class, Object.class, Object.class, Object.class, Object.class, Object.class));
}
