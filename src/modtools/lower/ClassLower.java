package modtools.lower;

import arc.func.Cons;
import modtools.utils.ByteCodeTools.MyClass;

/** 适配一些低版本或安卓上没有的类 */
public class ClassLower {
	public static void lower() {
		// checkOrDefine("java.lang.Record", Object.class, cl -> {
		// 	cl.setFunc("<init>", null, Modifier.PUBLIC | Modifier.ABSTRACT, false);
		// 	cl.setFunc("equals", (Func2)null, Modifier.PUBLIC | Modifier.ABSTRACT, false, boolean.class, Object.class);
		// 	cl.setFunc("hashCode", (Func2)null, Modifier.PUBLIC | Modifier.ABSTRACT, false, int.class);
		// 	cl.setFunc("toString", (Func2)null, Modifier.PUBLIC | Modifier.ABSTRACT, false, String.class);
		// });
	}

	public static void checkOrDefine(String name, Class<?> superClass, Cons<MyClass<?>> builder) {
		try {
			Class.forName(name);
		} catch (ClassNotFoundException e) {
			var newClass = new MyClass<>(name, superClass);
			builder.get(newClass);
			newClass.define();
		}
	}
}
