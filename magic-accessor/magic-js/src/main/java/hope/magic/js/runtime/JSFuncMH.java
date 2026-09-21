package hope.magic.js.runtime;

import java.lang.invoke.*;

public final class JSFuncMH {
	public static final MethodHandle
	 CALL            = JSLinker.findVirtualMH(JSFunction.class, "call", MethodType.methodType(Object.class, JSContext.class, Object.class, Object[].class)),
	 CALL0           = JSLinker.findVirtualMH(JSFunction.class, "call0", MethodType.methodType(Object.class, JSContext.class, Object.class)),
	 CALL1           = JSLinker.findVirtualMH(JSFunction.class, "call1", MethodType.methodType(Object.class, JSContext.class, Object.class, Object.class)),
	 CALL2           = JSLinker.findVirtualMH(JSFunction.class, "call2", MethodType.methodType(Object.class, JSContext.class, Object.class, Object.class, Object.class)),
	 CALL3           = JSLinker.findVirtualMH(JSFunction.class, "call3", MethodType.methodType(Object.class, JSContext.class, Object.class, Object.class, Object.class, Object.class)),
	 CALL4           = JSLinker.findVirtualMH(JSFunction.class, "call4", MethodType.methodType(Object.class, JSContext.class, Object.class, Object.class, Object.class, Object.class, Object.class)),
	 CALL_UNDEFINED  = MethodHandles.insertArguments(CALL, 1, (JSContext) null, JSUndefined.INSTANCE),
	 CALL0_UNDEFINED = MethodHandles.insertArguments(CALL0, 1, (JSContext) null, JSUndefined.INSTANCE),
	 CALL1_UNDEFINED = MethodHandles.insertArguments(CALL1, 1, (JSContext) null, JSUndefined.INSTANCE),
	 CALL2_UNDEFINED = MethodHandles.insertArguments(CALL2, 1, (JSContext) null, JSUndefined.INSTANCE),
	 CALL3_UNDEFINED = MethodHandles.insertArguments(CALL3, 1, (JSContext) null, JSUndefined.INSTANCE),
	 CALL4_UNDEFINED = MethodHandles.insertArguments(CALL4, 1, (JSContext) null, JSUndefined.INSTANCE),
	 CALL_NULL       = MethodHandles.insertArguments(CALL, 1, (JSContext) null),
	 CALL0_NULL      = MethodHandles.insertArguments(CALL0, 1, (JSContext) null),
	 CALL1_NULL      = MethodHandles.insertArguments(CALL1, 1, (JSContext) null),
	 CALL2_NULL      = MethodHandles.insertArguments(CALL2, 1, (JSContext) null),
	 CALL3_NULL      = MethodHandles.insertArguments(CALL3, 1, (JSContext) null),
	 CALL4_NULL      = MethodHandles.insertArguments(CALL4, 1, (JSContext) null)

		//
		;
}
