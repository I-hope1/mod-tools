package modtools.jsfunc.type;

import modtools.jsfunc.IScript;
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

	static Class<?> basic(Class<?> type) {
		type = box(type);
		if (Number.class.isAssignableFrom(type)) return Number.class;
		if (type == Boolean.class) return Boolean.class;
		return type;
	}
	static Class<?> box(Class<?> type) {
		if (!type.isPrimitive()) return type;
		if (type == boolean.class) return Boolean.class;
		if (type == byte.class) return Byte.class;
		if (type == char.class) return Character.class;
		if (type == short.class) return Short.class;
		if (type == int.class) return Integer.class;
		if (type == float.class) return Float.class;
		if (type == long.class) return Long.class;
		if (type == double.class) return Double.class;
		return type;
		// return TO_BOX_MAP.get(type, type);
	}
	static Class<?> unbox(Class<?> type) {
		if (type.isPrimitive()) return type;
		if (type == Boolean.class) return boolean.class;
		if (type == Byte.class) return byte.class;
		if (type == Character.class) return char.class;
		if (type == Short.class) return short.class;
		if (type == Integer.class) return int.class;
		if (type == Float.class) return float.class;
		if (type == Long.class) return long.class;
		if (type == Double.class) return double.class;
		// it should not reach
		return type;
	}

	static Object cast(Object o, Class<?> cl) {
		return Context.jsToJava(o, cl);
	}
	static Object asJS(Object o) {
		return Context.javaToJS(o, IScript.scope);
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
	static Object toByte(byte i) {
		return i;
	}
	static Object toShort(short i) {
		return i;
	}
}
