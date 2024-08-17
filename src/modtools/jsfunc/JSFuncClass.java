package modtools.jsfunc;

import arc.Core;
import arc.util.*;
import mindustry.Vars;
import mindustry.ctype.*;
import modtools.struct.LazyValue;
import modtools.utils.*;
import rhino.*;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

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
			if (!OS.isAndroid && inter.getPackageName().contains("android")) continue;
			/* Delegate annotation = inter.getAnnotation(Delegate.class);
			if (annotation != null) {
				inter = annotation.value();
			} */
			ArrayUtils.valueArr2Map(inter.getFields(), Field::getName, map);
		}
	}
	public Object get(String name, Scriptable start) {
		RuntimeException ex;
		try {
			Object val = super.get(name, start);
			if (val == null || val == Undefined.instance || val == NOT_FOUND) throw new NoSuchElementException(name);
			return val;
		} catch (RuntimeException e) { ex = e; }

		return switch (name) {
			case "void" -> void.class;
			case "boolean" -> boolean.class;
			case "byte" -> byte.class;
			case "short" -> short.class;
			case "int" -> int.class;
			case "long" -> long.class;
			case "float" -> float.class;
			case "double" -> double.class;
			default -> {
				Object o;
				if ((o = resolveNamedContent(name)) != null) yield o;
				throw ex;
			}
		};
	}
	public LazyValue<Object[]> ids = LazyValue.of(super::getIds);
	public Object[] getIds() {
		ArrayList<Object> list = Arrays.stream(ids.get())
		 .collect(Collectors.toCollection(ArrayList::new));
		list.addAll(Arrays.stream(ContentType.all)
		 .flatMap(type -> Arrays.stream(Vars.content.getBy(type).toArray(Content.class)).map(c -> c instanceof UnlockableContent u ? u.name : null))
		 .toList());
		return list.toArray();
	}
	private Object resolveNamedContent(String name) {
		String key         = Core.bundle.getProperties().findKey(name, false);
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


	/* @Retention(RetentionPolicy.RUNTIME)
	public @interface Delegate {
		Class<?> value();
	} */
}
