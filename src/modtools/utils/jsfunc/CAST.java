package modtools.utils.jsfunc;

import modtools.utils.JSFunc;
import rhino.*;

public interface CAST {

	// 转换方法
	static Object unwrap(Object o) {
		if (o instanceof Wrapper) {
			return ((Wrapper) o).unwrap();
		}
		if (o instanceof Undefined) {
			return "undefined";
		}
		return o;
	}
	static Object cast(Object o, Class cl) {
		return Context.jsToJava(o, cl);
	}
	static Object asJS(Object o) {
		return Context.javaToJS(o, JSFunc.scope);
	}
	static Object toFloat(float f) {
		return f;
	}
	static Object toInt(int i) {
		return i;
	}
	static Object toDouble(double d) {
		return d;
	}
	static Object toLong(long l) {
		return l;
	}
}
