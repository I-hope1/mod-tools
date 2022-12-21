package hope_rhino;

import rhino.*;

import java.lang.reflect.Field;

public class LinkRhino189012201 {

	static Field factory;
	/*static DynamicMaker def = DynamicMaker.getDefault();

	static DynamicClass dynamicClass = DynamicClass.get("aiqjoinjPQPKQO");*/

	static {
		try {
			factory = Context.class.getDeclaredField("factory");
			factory.setAccessible(true);
		} catch (NoSuchFieldException e) {
			throw new RuntimeException(e);
		}
		/*dynamicClass.setFunction("call", (self, superPoint, args) -> {
			try {
				return superPoint.invokeFunc("call", args);
			} catch (Throwable e) {
				tester.makeError(e);
			}
			return null;
		}, Object.class);*/
	}

	public static void init(Context cx) {
		// cx.setErrorReporter(MyErrorReporter.instance);
		/*try {
			Log.info(cx.getFactory().getClass().isAnonymousClass());
			factory.set(cx, def.newInstance(cx.getFactory().getClass(), dynamicClass,
					Vars.mobile ? new Object[]{Vars.tmpDirectory.child("amkxnajk").file()} : new Object[]{}));
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}*/
	}
}
