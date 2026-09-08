package hope.magic.js.runtime;

import java.lang.invoke.*;

public final class PropMH {
	public static final MethodHandle
	 GET_GENERIC            = JSLinker.findStaticMH(JSLinker.class, "getPropGeneric", MethodType.methodType(Object.class, Object.class, String.class)),
	 GET_FALLBACK           = JSLinker.findStaticMH(JSLinker.class, "getPropFallback", MethodType.methodType(Object.class, ChainedCallSite.class, Object.class, String.class)),
	 GET_MEGAMORPHIC        = JSLinker.findStaticMH(JSLinker.class, "getPropMegamorphic", MethodType.methodType(Object.class, ChainedCallSite.class, Object.class, String.class)),
	 GET_INT_GENERIC        = JSLinker.findStaticMH(JSLinker.class, "getPropIntGeneric", MethodType.methodType(int.class, Object.class, String.class)),
	 GET_INT_FALLBACK       = JSLinker.findStaticMH(JSLinker.class, "getPropIntFallback", MethodType.methodType(int.class, ChainedCallSite.class, Object.class, String.class)),
	 GET_INT_MEGAMORPHIC    = JSLinker.findStaticMH(JSLinker.class, "getPropIntMegamorphic", MethodType.methodType(int.class, ChainedCallSite.class, Object.class, String.class)),
	 GET_DOUBLE_GENERIC     = JSLinker.findStaticMH(JSLinker.class, "getPropDoubleGeneric", MethodType.methodType(double.class, Object.class, String.class)),
	 GET_DOUBLE_FALLBACK    = JSLinker.findStaticMH(JSLinker.class, "getPropDoubleFallback", MethodType.methodType(double.class, ChainedCallSite.class, Object.class, String.class)),
	 GET_DOUBLE_MEGAMORPHIC = JSLinker.findStaticMH(JSLinker.class, "getPropDoubleMegamorphic", MethodType.methodType(double.class, ChainedCallSite.class, Object.class, String.class)),
	 GET_LONG_GENERIC       = JSLinker.findStaticMH(JSLinker.class, "getPropLongGeneric", MethodType.methodType(long.class, Object.class, String.class)),
	 GET_LONG_FALLBACK      = JSLinker.findStaticMH(JSLinker.class, "getPropLongFallback", MethodType.methodType(long.class, ChainedCallSite.class, Object.class, String.class)),
	 GET_LONG_MEGAMORPHIC   = JSLinker.findStaticMH(JSLinker.class, "getPropLongMegamorphic", MethodType.methodType(long.class, ChainedCallSite.class, Object.class, String.class)),
	 SET_GENERIC            = JSLinker.findStaticMH(JSLinker.class, "setPropGeneric", MethodType.methodType(void.class, Object.class, Object.class, String.class)),
	 SET_FALLBACK           = JSLinker.findStaticMH(JSLinker.class, "setPropFallback", MethodType.methodType(void.class, ChainedCallSite.class, Object.class, Object.class, String.class)),
	 SET_MEGAMORPHIC        = JSLinker.findStaticMH(JSLinker.class, "setPropMegamorphic", MethodType.methodType(void.class, ChainedCallSite.class, Object.class, Object.class, String.class)),
	 SET_DOUBLE_GENERIC     = JSLinker.findStaticMH(JSLinker.class, "setPropDoubleGeneric", MethodType.methodType(void.class, Object.class, double.class, String.class)),
	 SET_DOUBLE_FALLBACK    = JSLinker.findStaticMH(JSLinker.class, "setPropDoubleFallback", MethodType.methodType(void.class, ChainedCallSite.class, Object.class, double.class, String.class)),
	 SET_DOUBLE_MEGAMORPHIC = JSLinker.findStaticMH(JSLinker.class, "setPropDoubleMegamorphic", MethodType.methodType(void.class, ChainedCallSite.class, Object.class, double.class, String.class));
}
