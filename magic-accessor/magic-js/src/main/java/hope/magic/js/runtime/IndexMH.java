package hope.magic.js.runtime;

import java.lang.invoke.*;

public final class IndexMH {
	public static final MethodHandle
	 GET          = JSLinker.findStaticMH(JSLinker.class, "getIndex", MethodType.methodType(Object.class, Object.class, Object.class)),
	 SET          = JSLinker.findStaticMH(JSLinker.class, "setIndex", MethodType.methodType(void.class, Object.class, Object.class, Object.class)),
	 GET_FALLBACK = JSLinker.findStaticMH(JSLinker.class, "getIndexDynamicFallback", MethodType.methodType(Object.class, ChainedCallSite.class, Object.class, Object.class));
}
