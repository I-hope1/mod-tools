package modtools.jsfunc;

import arc.util.Reflect;
import modtools.utils.*;
import rhino.*;

import java.lang.reflect.Field;
import java.util.Map;

// @OptimizeReflect
public class JSFuncClass extends NativeJavaClass {
	public JSFuncClass(Scriptable scope) {
		super(scope, JSFunc.class, true);
	}
	protected void initMembers() {
		super.initMembers();
		Object members = Reflect.get(NativeJavaObject.class, this, "members");

		Map<String, Field> map = Reflect.get(Kit.classOrNull("rhino.JavaMembers"), members, "staticMembers");

		for (Class<?> inter : JSFunc.class.getInterfaces()) {
			ArrayUtils.valueArr2Map(inter.getFields(), Field::getName, map);
		}
	}
	public Object get(String name, Scriptable start) {
		RuntimeException ex;
		try {
			return super.get(name, start);
		} catch (RuntimeException e) {ex = e;}
		return switch (name) {
			case "void" -> void.class;
			case "boolean" -> boolean.class;
			case "byte" -> byte.class;
			case "short" -> short.class;
			case "int" -> int.class;
			case "long" -> long.class;
			case "float" -> float.class;
			case "double" -> double.class;
			default -> throw ex;
		};
	}
	public boolean hasInstance(Scriptable value) {
		return false;
	}
	public Scriptable construct(Context cx, Scriptable scope, Object[] args) {
		return null;
	}
}
