package modtools.jsfunc;

import arc.Core;
import arc.util.Reflect;
import mindustry.Vars;
import mindustry.ctype.ContentType;
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

		// 原生不支持访问接口的字段，这里特殊化
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
		} catch (RuntimeException e) { ex = e; }
		Object res = resolveNamedContent(name);
		if (res != null) return res;

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
	private Object resolveNamedContent(String name) {
		String key = Core.bundle.getProperties().findKey(name, false);
		String contentName = key != null ? key.split("\\.")[1] : name;
		if (contentName != null) {
			for (ContentType ctype : ContentType.all) {
				if (Vars.content.getByName(ctype, contentName) != null) {
					return Vars.content.getByName(ctype, contentName);
				}
			}
		}
		return null;
	}
	public boolean hasInstance(Scriptable value) {
		return false;
	}
	public Scriptable construct(Context cx, Scriptable scope, Object[] args) {
		return null;
	}
}
