package hope.magic.js.runtime;

import java.lang.invoke.*;

public final class NewMH {
	public static final MethodHandle
	 NEW_JS_FUNC0 = JSLinker.findStaticMH(JSLinker.class, "newJSFunction0", MethodType.methodType(Object.class, JSFunction.class, JSObject.class)),
	 NEW_JS_FUNC1 = JSLinker.findStaticMH(JSLinker.class, "newJSFunction1", MethodType.methodType(Object.class, JSFunction.class, Object.class, JSObject.class)),
	 NEW_JS_FUNC2 = JSLinker.findStaticMH(JSLinker.class, "newJSFunction2", MethodType.methodType(Object.class, JSFunction.class, Object.class, Object.class, JSObject.class)),
	 NEW_JS_FUNC3 = JSLinker.findStaticMH(JSLinker.class, "newJSFunction3", MethodType.methodType(Object.class, JSFunction.class, Object.class, Object.class, Object.class, JSObject.class)),
	 NEW_JS_FUNC4 = JSLinker.findStaticMH(JSLinker.class, "newJSFunction4", MethodType.methodType(Object.class, JSFunction.class, Object.class, Object.class, Object.class, Object.class, JSObject.class));
}
