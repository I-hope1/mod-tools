package modtools.utils.jsfunc;

import modtools.utils.JSFunc;
import rhino.*;

public class JSFuncClass extends NativeJavaClass {
	public JSFuncClass(Scriptable scope) {
		super(scope, JSFunc.class, true);
	}
	public Object get(String name, Scriptable start) {
		RuntimeException ex;
		try {
			return super.get(name, start);
		} catch (RuntimeException e) {ex = e;}
		if (name.equals("void")) return void.class;
		if (name.equals("boolean")) return boolean.class;
		if (name.equals("byte")) return byte.class;
		if (name.equals("short")) return short.class;
		if (name.equals("int")) return int.class;
		if (name.equals("long")) return long.class;
		if (name.equals("float")) return float.class;
		if (name.equals("double")) return double.class;
		throw ex;
	}
	public boolean hasInstance(Scriptable value) {
		return false;
	}
	public Scriptable construct(Context cx, Scriptable scope, Object[] args) {
		return null;
	}
}
