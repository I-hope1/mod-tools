package hope.magic.js.runtime;

import java.lang.invoke.*;

public final class InvokeMH {
	public static final MethodHandle
	 INVOKE_GENERIC  = JSLinker.findStaticMH(JSLinker.class, "invokeGeneric", MethodType.methodType(Object.class, Object.class, Object[].class, String.class)),
	 INVOKE_FALLBACK = JSLinker.findStaticMH(JSLinker.class, "invokeFallback", MethodType.methodType(Object.class, ChainedCallSite.class, Object.class, Object[].class, String.class)),
	 NEW_GENERIC     = JSLinker.findStaticMH(JSLinker.class, "newGeneric", MethodType.methodType(Object.class, Object.class, Object[].class)),
	 NEW_FALLBACK    = JSLinker.findStaticMH(JSLinker.class, "newFallback", MethodType.methodType(Object.class, ChainedCallSite.class, Object.class, Object[].class));
}
