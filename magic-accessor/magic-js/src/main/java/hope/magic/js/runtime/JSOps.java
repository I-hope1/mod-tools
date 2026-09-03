package hope.magic.js.runtime;

import java.util.Objects;

@SuppressWarnings("unused")
public class JSOps {
	public static final int  SHIFT_MASK_32 = 0x1F;
	public static final long UINT32_MASK   = 0xFFFFFFFFL;

	public static Object add(Object a, Object b) {
		if (a instanceof Double && b instanceof Double) return (Double) a + (Double) b;
		if (a instanceof Integer && b instanceof Integer) {
			long res = (long) (Integer) a + (long) (Integer) b;
			if (res >= Integer.MIN_VALUE && res <= Integer.MAX_VALUE) return (int) res;
			return (double) res;
		}
		if (a instanceof String || b instanceof String) {
			return toStr(a) + toStr(b);
		}
		return toDouble(a) + toDouble(b);
	}

	//region Primitive 特化 (Zero-Boxing Fast Paths)

	public static double add(double a, double b) {
		return a + b;
	}

	public static int add(int a, int b) {
		return a + b;
	}

	public static double add(int a, double b) {
		return a + b;
	}

	public static double add(double a, int b) {
		return a + b;
	}

	public static String add(String a, String b) {
		return a + b;
	}

	public static String add(String a, Object b) {
		return a + toStr(b);
	}

	public static String add(Object a, String b) {
		return toStr(a) + b;
	}

	public static Object add(Object a, double b) {
		if (a instanceof String s) return s + toStr(b);
		return toDouble(a) + b;
	}

	public static Object add(double a, Object b) {
		if (b instanceof String s) return toStr(a) + s;
		return a + toDouble(b);
	}

	public static Object add(Object a, int b) {
		if (a instanceof String s) return s + b;
		if (a instanceof Integer ai) {
			long res = (long) ai + (long) b;
			if (res >= Integer.MIN_VALUE && res <= Integer.MAX_VALUE) return (int) res;
			return (double) res;
		}
		return toDouble(a) + b;
	}

	public static Object add(int a, Object b) {
		if (b instanceof String s) return a + s;
		if (b instanceof Integer bi) {
			long res = (long) a + (long) bi;
			if (res >= Integer.MIN_VALUE && res <= Integer.MAX_VALUE) return (int) res;
			return (double) res;
		}
		return a + toDouble(b);
	}

	public static Object sub(Object a, Object b) {
		if (a instanceof Double && b instanceof Double) return (Double) a - (Double) b;
		if (a instanceof Integer && b instanceof Integer) {
			long res = (long) (Integer) a - (long) (Integer) b;
			if (res >= Integer.MIN_VALUE && res <= Integer.MAX_VALUE) return (int) res;
			return (double) res;
		}
		return toDouble(a) - toDouble(b);
	}

	public static double sub(double a, double b) {
		return a - b;
	}

	public static int sub(int a, int b) {
		return a - b;
	}

	public static double sub(int a, double b) {
		return a - b;
	}

	public static double sub(double a, int b) {
		return a - b;
	}

	public static double sub(Object a, double b) {
		return toDouble(a) - b;
	}

	public static double sub(double a, Object b) {
		return a - toDouble(b);
	}

	public static Object mul(Object a, Object b) {
		if (a instanceof Double && b instanceof Double) return (Double) a * (Double) b;
		if (a instanceof Integer && b instanceof Integer) {
			long res = (long) (Integer) a * (long) (Integer) b;
			if (res >= Integer.MIN_VALUE && res <= Integer.MAX_VALUE) return (int) res;
			return (double) res;
		}
		return toDouble(a) * toDouble(b);
	}

	public static double mul(double a, double b) {
		return a * b;
	}

	public static int mul(int a, int b) {
		return a * b;
	}

	public static double mul(int a, double b) {
		return a * b;
	}

	public static double mul(double a, int b) {
		return a * b;
	}

	public static double mul(Object a, double b) {
		return toDouble(a) * b;
	}

	public static double mul(double a, Object b) {
		return a * toDouble(b);
	}

	public static Object div(Object a, Object b) {
		if (a instanceof Double && b instanceof Double) return (Double) a / (Double) b;
		return toDouble(a) / toDouble(b);
	}

	public static double div(double a, double b) {
		return a / b;
	}

	public static double div(int a, int b) {
		return (double) a / (double) b;
	}

	public static double div(int a, double b) {
		return (double) a / b;
	}

	public static double div(double a, int b) {
		return a / (double) b;
	}

	public static double div(Object a, double b) {
		return toDouble(a) / b;
	}

	public static double div(double a, Object b) {
		return a / toDouble(b);
	}

	public static Object mod(Object a, Object b) {
		if (a instanceof Integer && b instanceof Integer) {
			int bVal = (Integer) b;
			if (bVal != 0) return (Integer) a % bVal;
		}
		if (a instanceof Double && b instanceof Double) return (Double) a % (Double) b;
		return toDouble(a) % toDouble(b);
	}

	public static double mod(double a, double b) {
		return a % b;
	}

	public static double mod(int a, int b) {
		return b == 0 ? Double.NaN : (double) (a % b);
	}

	public static double mod(int a, double b) {
		return (double) a % b;
	}

	public static double mod(double a, int b) {
		return a % (double) b;
	}

	public static double mod(Object a, double b) {
		return toDouble(a) % b;
	}

	public static double mod(double a, Object b) {
		return a % toDouble(b);
	}

	public static boolean isEq(Object a, Object b) {
		// js 中 NaN 的任何比较都应返回 false
		if ((a instanceof Double d1 && d1.isNaN()) || (b instanceof Double d2 && d2.isNaN())) return false;
		if (a == b) return true;
		if (a == null || a == JSUndefined.INSTANCE) {
			return b == null || b == JSUndefined.INSTANCE;
		}
		if (b == null || b == JSUndefined.INSTANCE) return false;
		if (a instanceof Boolean) a = ((Boolean) a) ? 1.0 : 0.0;
		if (b instanceof Boolean) b = ((Boolean) b) ? 1.0 : 0.0;

		if (a instanceof Number && b instanceof Number) {
			return ((Number) a).doubleValue() == ((Number) b).doubleValue();
		}
		if (a instanceof String && b instanceof Number) {
			return toDouble(a) == ((Number) b).doubleValue();
		}
		if (a instanceof Number && b instanceof String) {
			return ((Number) a).doubleValue() == toDouble(b);
		}
		if (a instanceof String || b instanceof String) {
			return Objects.equals(a.toString(), b.toString());
		}
		return Objects.equals(a, b);
	}

	public static boolean isStrictEq(Object a, Object b) {
		// js 中 NaN 的任何比较都应返回 false
		if ((a instanceof Double d1 && d1.isNaN()) || (b instanceof Double d2 && d2.isNaN())) return false;
		if (a == b) return true;
		if (a == null || b == null || a == JSUndefined.INSTANCE || b == JSUndefined.INSTANCE) return false;
		if (a instanceof Number && b instanceof Number) {
			return ((Number) a).doubleValue() == ((Number) b).doubleValue();
		}
		if (a.getClass() != b.getClass()) {
			return false;
		}
		return Objects.equals(a, b);
	}

	public static boolean isEqNull(Object a) {
		return a == null || a == JSUndefined.INSTANCE;
	}

	public static boolean isStrictEqNull(Object a) {
		return a == null;
	}

	public static boolean isStrictEqUndefined(Object a) {
		return a == JSUndefined.INSTANCE;
	}

	public static boolean isStrictEqBool(Object a, boolean b) {
		return a instanceof Boolean && ((Boolean) a) == b;
	}

	public static boolean isEqBool(Object a, boolean b) {
		if (a instanceof Boolean) return ((Boolean) a) == b;
		if (a instanceof Number) return ((Number) a).doubleValue() == (b ? 1.0 : 0.0);
		if (a instanceof String) return toDouble(a) == (b ? 1.0 : 0.0);
		return false;
	}

	public static boolean isStrictEqInt(Object a, int b) {
		return a instanceof Number && ((Number) a).doubleValue() == (double) b;
	}

	public static boolean isStrictEqDouble(Object a, double b) {
		return a instanceof Number && ((Number) a).doubleValue() == b;
	}

	public static boolean isStrictEqString(Object a, String b) {
		return a instanceof String && a.equals(b);
	}

	public static boolean isEqInt(Object a, int b) {
		if (a instanceof Number) return ((Number) a).doubleValue() == (double) b;
		if (a instanceof Boolean) return (((Boolean) a) ? 1 : 0) == b;
		if (a instanceof String) return toDouble(a) == (double) b;
		return false;
	}

	public static boolean isEqDouble(Object a, double b) {
		if (a instanceof Number) return ((Number) a).doubleValue() == b;
		if (a instanceof Boolean) return (((Boolean) a) ? 1.0 : 0.0) == b;
		if (a instanceof String) return toDouble(a) == b;
		return false;
	}

	public static boolean isEqString(Object a, String b) {
		if (a == null || a == JSUndefined.INSTANCE) return false;
		if (a instanceof String) return a.equals(b);
		if (a instanceof Number) return ((Number) a).doubleValue() == toDouble(b);
		return Objects.equals(a.toString(), b);
	}

	public static Object eq(Object a, Object b) {
		return isEq(a, b) ? Boolean.TRUE : Boolean.FALSE;
	}

	public static Object strictEq(Object a, Object b) {
		return isStrictEq(a, b) ? Boolean.TRUE : Boolean.FALSE;
	}

	public static Object ne(Object a, Object b) {
		return eq(a, b) == Boolean.TRUE ? Boolean.FALSE : Boolean.TRUE;
	}

	public static Object strictNe(Object a, Object b) {
		return strictEq(a, b) == Boolean.TRUE ? Boolean.FALSE : Boolean.TRUE;
	}

	public static Object lt(Object a, Object b) {
		return toDouble(a) < toDouble(b) ? Boolean.TRUE : Boolean.FALSE;
	}

	public static Object lte(Object a, Object b) {
		return toDouble(a) <= toDouble(b) ? Boolean.TRUE : Boolean.FALSE;
	}

	public static Object gt(Object a, Object b) {
		return toDouble(a) > toDouble(b) ? Boolean.TRUE : Boolean.FALSE;
	}

	public static Object gte(Object a, Object b) {
		return toDouble(a) >= toDouble(b) ? Boolean.TRUE : Boolean.FALSE;
	}

	public static Object and(Object a, Object b) {
		return isTruthy(a) ? b : a;
	}

	public static Object or(Object a, Object b) {
		return isTruthy(a) ? a : b;
	}

	public static boolean isTruthy(Object val) {
		if (val == null || val == JSUndefined.INSTANCE) return false;
		if (val instanceof Boolean) return (Boolean) val;
		if (val instanceof Number) {
			return ((Number) val).doubleValue() != 0.0 && !Double.isNaN(((Number) val).doubleValue());
		}
		if (val instanceof String) return !((String) val).isEmpty();
		return true;
	}

	public static Object not(Object val) {
		return isTruthy(val) ? Boolean.FALSE : Boolean.TRUE;
	}

	public static double toDouble(Object val) {
		if (val instanceof Double d) return d;
		return toDoubleSlow(val);
	}

	public static double toDoubleSlow(Object val) {
		if (val instanceof Number n) return n.doubleValue();
		if (val == null || val == JSUndefined.INSTANCE) return Double.NaN;
		if (val instanceof Boolean b) return b ? 1.0 : 0.0;
		if (val instanceof String s) {
			String trimmed = s.trim();
			if (trimmed.isEmpty()) return 0.0;
			try {
				return Double.parseDouble(trimmed);
			} catch (NumberFormatException e) {
				return Double.NaN;
			}
		}
		return Double.NaN;
	}

	public static long toLong(Object val) {
		if (val instanceof Long l) return l;
		if (val instanceof Integer i) return i.longValue();
		if (val instanceof Double d) return d.longValue();
		return toLongSlow(val);
	}

	public static long toLongSlow(Object val) {
		if (val instanceof Number n) return n.longValue();
		if (val == null || val == JSUndefined.INSTANCE) return 0L;
		if (val instanceof Boolean b) return b ? 1L : 0L;
		return (long) toDouble(val);
	}

	public static int toInt(Object val) {
		if (val instanceof Integer i) return i;
		if (val instanceof Double d) return d.intValue();
		return toIntSlow(val);
	}

	public static int toIntSlow(Object val) {
		if (val instanceof Number n) return n.intValue();
		if (val == null || val == JSUndefined.INSTANCE) return 0;
		if (val instanceof Boolean b) return b ? 1 : 0;
		return (int) toDouble(val);
	}

	public static float toFloat(Object val) {
		if (val instanceof Number n) return n.floatValue();
		return (float) toDouble(val);
	}

	public static short toShort(Object val) {
		if (val instanceof Number n) return n.shortValue();
		return (short) toInt(val);
	}

	public static byte toByte(Object val) {
		if (val instanceof Number n) return n.byteValue();
		return (byte) toInt(val);
	}

	public static char toChar(Object val) {
		if (val instanceof Character c) return c;
		if (val instanceof String s && !s.isEmpty()) return s.charAt(0);
		if (val instanceof Number n) return (char) n.intValue();
		return '\0';
	}

	public static boolean toBoolean(Object val) {
		if (val instanceof Boolean b) return b;
		return isTruthy(val);
	}

	public static String toStr(Object val) {
		if (val instanceof String s) return s;
		if (val instanceof Integer i) return i.toString();
		return toStrSlow(val);
	}

	public static String toStrSlow(Object val) {
		if (val == null) return "null";
		if (val == JSUndefined.INSTANCE) return "undefined";
		if (val instanceof Double d) {
			if (d == d.longValue() && !Double.isInfinite(d) && !Double.isNaN(d)) {
				return String.valueOf(d.longValue());
			}
		}
		if (val instanceof Float f) {
			if (f == f.longValue() && !Float.isInfinite(f) && !Float.isNaN(f)) {
				return String.valueOf(f.longValue());
			}
		}
		return String.valueOf(val);
	}

	public static java.util.Iterator<?> toIterator(Object target) {
		if (target == null || target == JSUndefined.INSTANCE) {
			return java.util.Collections.emptyIterator();
		}
		if (target instanceof Iterable<?> iterable) {
			return iterable.iterator();
		}
		if (target instanceof java.util.Iterator<?> iterator) {
			return iterator;
		}
		if (target instanceof Object[] arr) {
			return java.util.Arrays.asList(arr).iterator();
		}
		if (target.getClass().isArray()) {
			int                    len  = java.lang.reflect.Array.getLength(target);
			java.util.List<Object> list = new java.util.ArrayList<>(len);
			for (int i = 0; i < len; i++) {
				list.add(java.lang.reflect.Array.get(target, i));
			}
			return list.iterator();
		}
		if (target instanceof java.util.Map<?, ?> map) {
			return map.entrySet().iterator();
		}
		return java.util.Collections.singletonList(target).iterator();
	}

	public static Object slice(Object target, int start) {
		if (target == null || target == JSUndefined.INSTANCE) {
			return new JSArray();
		}
		if (target instanceof JSArray arr) {
			long len = arr.length();
			if (start < 0) start = (int) Math.max(0, len + start);
			if (start >= len) return new JSArray();
			JSArray res = new JSArray();
			for (long i = start; i < len; i++) {
				res.push(arr.getElement(i));
			}
			return res;
		}
		if (target instanceof java.util.List<?> list) {
			int len = list.size();
			if (start < 0) start = Math.max(0, len + start);
			if (start >= len) return new JSArray();
			JSArray res = new JSArray();
			for (int i = start; i < len; i++) {
				res.push(list.get(i));
			}
			return res;
		}
		if (target instanceof Object[] arr) {
			int len = arr.length;
			if (start < 0) start = Math.max(0, len + start);
			if (start >= len) return new JSArray();
			JSArray res = new JSArray();
			for (int i = start; i < len; i++) {
				res.push(arr[i]);
			}
			return res;
		}
		if (target.getClass().isArray()) {
			int len = java.lang.reflect.Array.getLength(target);
			if (start < 0) start = Math.max(0, len + start);
			if (start >= len) return new JSArray();
			JSArray res = new JSArray();
			for (int i = start; i < len; i++) {
				res.push(java.lang.reflect.Array.get(target, i));
			}
			return res;
		}
		return new JSArray();
	}

	public static Object restObject(Object target, String excludedCsv) {
		if (!(target instanceof JSObject jsObj)) {
			return new JSObject();
		}
		java.util.Set<String> excluded = new java.util.HashSet<>();
		if (excludedCsv != null && !excludedCsv.isEmpty()) {
			for (String k : excludedCsv.split(",")) {
				excluded.add(k.trim());
			}
		}
		JSObject res = new JSObject();
		for (String k : jsObj.shape.keys()) {
			if (!excluded.contains(k)) {
				res.put(k, jsObj.get(k));
			}
		}
		return res;
	}

	public static java.util.Iterator<?> toKeyIterator(Object target) {
		if (target == null || target == JSUndefined.INSTANCE) {
			return java.util.Collections.emptyIterator();
		}
		if (target instanceof JSObject jsObj) {
			return jsObj.keys().iterator();
		}
		if (target instanceof java.util.Map<?, ?> map) {
			java.util.List<String> keys = new java.util.ArrayList<>();
			for (Object k : map.keySet()) keys.add(String.valueOf(k));
			return keys.iterator();
		}
		if (target instanceof CharSequence seq) {
			java.util.List<String> indices = new java.util.ArrayList<>();
			for (int i = 0; i < seq.length(); i++) indices.add(String.valueOf(i));
			return indices.iterator();
		}
		if (target.getClass().isArray()) {
			int                    len     = java.lang.reflect.Array.getLength(target);
			java.util.List<String> indices = new java.util.ArrayList<>();
			for (int i = 0; i < len; i++) indices.add(String.valueOf(i));
			return indices.iterator();
		}
		return java.util.Collections.emptyIterator();
	}

	public static String typeOf(Object val) {
		if (val == null) return "object";
		if (val == JSUndefined.INSTANCE) return "undefined";
		if (val instanceof Boolean) return "boolean";
		if (val instanceof Number) return "number";
		if (val instanceof CharSequence) return "string";
		if (val instanceof JSFunction) return "function";
		if (val instanceof java.lang.reflect.Executable || val instanceof java.lang.invoke.MethodHandle) return "function";
		return "object";
	}

	public static boolean delete(Object target, Object key) {
		if (target == null || target == JSUndefined.INSTANCE) return true;
		if (target instanceof JSObject obj) {
			obj.delete(String.valueOf(key));
			return true;
		}
		if (target instanceof java.util.Map<?, ?> map) {
			map.remove(key);
			return true;
		}
		return true;
	}

	public static class JSException extends RuntimeException {
		public final Object value;

		public JSException(Object value) {
			super(JSOps.toStr(value));
			this.value = value;
		}
	}

	public static RuntimeException throwValue(Object val) {
		if (val instanceof RuntimeException re) return re;
		if (val instanceof Throwable t) return new RuntimeException(t);
		return new JSException(val);
	}

	public static Object unwrapException(Throwable t) {
		if (t instanceof JSException jse) return jse.value;
		if (t != null && t.getCause() instanceof JSException jse) return jse.value;
		return t != null ? (t.getMessage() != null ? t.getMessage() : t.toString()) : "Error";
	}

	//region 位运算操作 (Bitwise Operations)
	public static Object bitAnd(Object a, Object b) {
		return toInt(a) & toInt(b);
	}

	public static Object bitOr(Object a, Object b) {
		return toInt(a) | toInt(b);
	}

	public static Object bitXor(Object a, Object b) {
		return toInt(a) ^ toInt(b);
	}

	public static Object bitNot(Object a) {
		return ~toInt(a);
	}

	public static Object shl(Object a, Object b) {
		return toInt(a) << (toInt(b) & SHIFT_MASK_32);
	}

	public static Object shr(Object a, Object b) {
		return toInt(a) >> (toInt(b) & SHIFT_MASK_32);
	}

	public static Object ushr(Object a, Object b) {
		return (double) ((long) (toInt(a) >>> (toInt(b) & SHIFT_MASK_32)) & UINT32_MASK);
	}
	//endregion
}
