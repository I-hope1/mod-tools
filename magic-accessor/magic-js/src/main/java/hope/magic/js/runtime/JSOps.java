package hope.magic.js.runtime;

import java.lang.reflect.*;
import java.util.Objects;

@SuppressWarnings("unused")
public class JSOps {
	public static final int  SHIFT_MASK_32 = 0x1F;
	public static final long UINT32_MASK   = 0xFFFFFFFFL;

	public static Object add(Object a, Object b) {
		if (a instanceof JSSymbol || b instanceof JSSymbol) {
			throw JSContext.makeTypeError("Cannot convert a Symbol value to a string / number");
		}
		if (a instanceof Double && b instanceof Double) return (Double) a + (Double) b;
		if (a instanceof Integer && b instanceof Integer) {
			long res = (long) (Integer) a + (long) (Integer) b;
			if (res >= Integer.MIN_VALUE && res <= Integer.MAX_VALUE) return (int) res;
			return (double) res;
		}
		if (a instanceof JSObject || b instanceof JSObject) {
			Object p1 = (a instanceof JSObject) ? toPrimitive(a, false) : a;
			Object p2 = (b instanceof JSObject) ? toPrimitive(b, false) : b;
			if (p1 instanceof String || p2 instanceof String) {
				return toStr(p1) + toStr(p2);
			}
			return toDouble(p1) + toDouble(p2);
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
		if (b instanceof JSObject) {
			return a + toStr(toPrimitive(b, false));
		}
		return a + toStr(b);
	}

	public static String add(Object a, String b) {
		if (a instanceof JSObject) {
			return toStr(toPrimitive(a, false)) + b;
		}
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

	public static int mod(int a, int b) {
		return b == 0 ? 0 : a % b;
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
		if (a == b && !(a instanceof Number n && Double.isNaN(n.doubleValue()))) return true;
		if (a == null || a == JSUndefined.INSTANCE) {
			return b == null || b == JSUndefined.INSTANCE;
		}
		if (b == null || b == JSUndefined.INSTANCE) return false;
		if (a instanceof Boolean) a = ((Boolean) a) ? 1.0 : 0.0;
		if (b instanceof Boolean) b = ((Boolean) b) ? 1.0 : 0.0;
		if (a instanceof JSSymbol || b instanceof JSSymbol) return false; // 前面已知 a != b

		if (a instanceof Number && b instanceof Number) {
			return ((Number) a).doubleValue() == ((Number) b).doubleValue();
		}
		if (a instanceof String && b instanceof Number) {
			return toDouble(a) == ((Number) b).doubleValue();
		}
		if (a instanceof Number && b instanceof String) {
			return ((Number) a).doubleValue() == toDouble(b);
		}
		if (a instanceof JSObject && (b instanceof Number || b instanceof String)) {
			return isEq(toPrimitive(a, false), b);
		}
		if ((a instanceof Number || a instanceof String) && b instanceof JSObject) {
			return isEq(a, toPrimitive(b, false));
		}
		if (a instanceof String || b instanceof String) {
			return Objects.equals(a.toString(), b.toString());
		}
		return Objects.equals(a, b);
	}

	public static boolean isStrictEq(Object a, Object b) {
		// js 中 NaN 的任何比较都应返回 false
		if (a == b && !(a instanceof Number n && Double.isNaN(n.doubleValue()))) return true;
		if (a == null || b == null || a == JSUndefined.INSTANCE || b == JSUndefined.INSTANCE) return false;
		if (a instanceof Number && b instanceof Number) {
			return ((Number) a).doubleValue() == ((Number) b).doubleValue();
		}
		if (a.getClass() != b.getClass()) {
			return false;
		}
		if (a instanceof String || a instanceof Boolean) {
			return Objects.equals(a, b);
		}
		return false;
	}

	/**
	 * TC39 SameValue algorithm (ECMA-262 §7.2.14).
	 * 用于 Object.is，严格区分 +0 与 -0，判定所有 NaN 互相相等，并抹平底层 Number 存储差异。
	 */
	public static boolean sameValue(Object a, Object b) {
		if (a == b) {
			if (a instanceof Number n) {
				double d = n.doubleValue();
				if (d == 0.0) {
					return Double.doubleToRawLongBits(d) == Double.doubleToRawLongBits(((Number) b).doubleValue());
				}
			}
			return true;
		}
		if (a == null || b == null || a == JSUndefined.INSTANCE || b == JSUndefined.INSTANCE) {
			return false; // 前面已知 a != b
		}
		if (a instanceof Number na && b instanceof Number nb) {
			double da = na.doubleValue();
			double db = nb.doubleValue();
			// IEEE 754: 只要双方都是 NaN (无论 payload / quiet / signaling 差异)，在 JS 中均视为相同
			if (Double.isNaN(da) && Double.isNaN(db)) {
				return true;
			}
			if (Double.isNaN(da) || Double.isNaN(db)) {
				return false;
			}
			// 严格区分 +0.0 与 -0.0
			if (da == 0.0 && db == 0.0) {
				return Double.doubleToRawLongBits(da) == Double.doubleToRawLongBits(db);
			}
			// 跨数值类型对齐：例如 10 与 10.0 相等
			return da == db;
		}
		if (a instanceof CharSequence && b instanceof CharSequence) {
			return a.toString().equals(b.toString());
		}
		if (a instanceof Boolean && b instanceof Boolean) {
			return a.equals(b);
		}
		if (a instanceof Character && b instanceof Character) {
			return a.equals(b);
		}
		return false;
	}

	public static boolean sameValue(double a, double b) {
		if (Double.isNaN(a) && Double.isNaN(b)) return true;
		return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
	}

	public static boolean sameValue(int a, int b) {
		return a == b;
	}

	public static boolean sameValue(long a, long b) {
		return a == b;
	}

	public static boolean sameValue(boolean a, boolean b) {
		return a == b;
	}

	public static Object sameValueBoxed(Object a, Object b) {
		return sameValue(a, b) ? Boolean.TRUE : Boolean.FALSE;
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
		if (a instanceof CharSequence sa && b instanceof CharSequence sb) {
			return sa.toString().compareTo(sb.toString()) < 0 ? Boolean.TRUE : Boolean.FALSE;
		}
		return toDouble(a) < toDouble(b) ? Boolean.TRUE : Boolean.FALSE;
	}

	public static Object lte(Object a, Object b) {
		if (a instanceof CharSequence sa && b instanceof CharSequence sb) {
			return sa.toString().compareTo(sb.toString()) <= 0 ? Boolean.TRUE : Boolean.FALSE;
		}
		return toDouble(a) <= toDouble(b) ? Boolean.TRUE : Boolean.FALSE;
	}

	public static Object gt(Object a, Object b) {
		if (a instanceof CharSequence sa && b instanceof CharSequence sb) {
			return sa.toString().compareTo(sb.toString()) > 0 ? Boolean.TRUE : Boolean.FALSE;
		}
		return toDouble(a) > toDouble(b) ? Boolean.TRUE : Boolean.FALSE;
	}

	public static Object gte(Object a, Object b) {
		if (a instanceof CharSequence sa && b instanceof CharSequence sb) {
			return sa.toString().compareTo(sb.toString()) >= 0 ? Boolean.TRUE : Boolean.FALSE;
		}
		return toDouble(a) >= toDouble(b) ? Boolean.TRUE : Boolean.FALSE;
	}

	public static boolean instanceOf(Object left, Object right) {
		if (right instanceof Class<?> clazz) {
			return clazz.isInstance(left);
		}
		if (!(right instanceof JSObject ctor)) {
			throw JSContext.makeTypeError("Right-hand side of 'instanceof' is not callable");
		}
		Object proto = ctor.get("prototype");
		if (!(proto instanceof JSObject targetProto)) {
			throw JSContext.makeTypeError("Function has non-object prototype in instanceof check");
		}
		if (left instanceof JSObject current) {
			JSObject p = current.getPrototype();
			while (p != null) {
				if (p == targetProto) {
					return true;
				}
				p = p.getPrototype();
			}
		}
		return false;
	}

	public static boolean in(Object left, Object right) {
		if (!(right instanceof JSObject jsObj)) {
			throw JSContext.makeTypeError("Cannot use 'in' operator to search for '" + left + "' in " + right);
		}
		String key = toStr(left);
		return jsObj.has(key);
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

	@SuppressWarnings("UnnecessaryUnboxing")
	public static double toDouble(Object val) {
		if (val instanceof Double) return ((Double) val).doubleValue();
		if (val instanceof Integer) return ((Integer) val).doubleValue();
		return toDoubleSlow(val);
	}

	public static double toDoubleSlow(Object val) {
		if (val instanceof JSSymbol) throw JSContext.makeTypeError("Cannot convert a Symbol value to a number");
		if (val instanceof Number n) return n.doubleValue();
		if (val == null) return 0.0;
		if (val == JSUndefined.INSTANCE) return Double.NaN;
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
		if (val instanceof JSObject jo) {
			Object prim = toPrimitive(jo, false);
			return toDouble(prim);
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

	public static int toInt(double d) {
		if (Math.abs(d) < 9.2233720368547758E18) {
			return (int) (long) d;
		}
		return toIntSlow(d);
	}
	private static int toIntSlow(double d) {
		if (Double.isNaN(d) || Double.isInfinite(d)) {
			return 0;
		}
		return (int) (long) (d % 4294967296.0);
	}
	public static int toInt(Object val) {
		// 覆盖绝大多数整数场景，字节码仅 20 字节，100% 毫无悬念进入任何深度的内联！
		if (val instanceof Integer) return (int) val;
		return toIntSlow(val);
	}
	public static int toIntSlow(Object val) {
		if (val instanceof Double) return toInt(((Double) val).doubleValue());
		if (val == null || val == JSUndefined.INSTANCE) return 0;
		if (val instanceof Boolean) return (Boolean) val ? 1 : 0;
		return toInt(toDouble(val));
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

	public static boolean toBoolean(double d) {
		return d != 0.0 && !Double.isNaN(d);
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
		if (val instanceof JSSymbol sym) return sym.toString();
		if (val instanceof Boolean b) {
			return b ? "true" : "false";
		}
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
		if (val instanceof JSObject jo) {
			Object prim = toPrimitive(jo, true);
			return toStr(prim);
		}
		return String.valueOf(val);
	}

	public static Object toPrimitive(Object val, boolean preferString) {
		if (!(val instanceof JSObject jo)) return val;
		if (jo instanceof JSContext.JSDate && !preferString) {
			preferString = true;
		}
		String first  = preferString ? "toString" : "valueOf";
		String second = preferString ? "valueOf" : "toString";

		JSContext cx = JSContext.CURRENT.get();

		Object m1 = jo.get(first);
		if (m1 instanceof JSFunction fn) {
			try {
				Object res = fn.call0(cx, jo);
				if (!(res instanceof JSObject)) return res;
			} catch (RuntimeException re) {
				throw re;
			} catch (Throwable t) {
				throw new RuntimeException(t);
			}
		}

		Object m2 = jo.get(second);
		if (m2 instanceof JSFunction fn) {
			try {
				Object res = fn.call0(cx, jo);
				if (!(res instanceof JSObject)) return res;
			} catch (RuntimeException re) {
				throw re;
			} catch (Throwable t) {
				throw new RuntimeException(t);
			}
		}

		if (jo.has("[[PrimitiveValue]]")) {
			Object prim = jo.get("[[PrimitiveValue]]");
			return preferString ? toStr(prim) : prim;
		}

		throw new RuntimeException("TypeError: Cannot convert object to primitive value");
	}

	/** MagicJIT是直接调用{@link #castValue(Object, Class)}  */
	public static Object toInterface(Object val, Class<?> iface) {
		return castValue(val, iface);
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
		if (val instanceof JSSymbol) return "symbol";
		if (val instanceof JSFunction) return "function";
		if (val instanceof java.lang.reflect.Executable || val instanceof java.lang.invoke.MethodHandle) return "function";
		return "object";
	}

	public static boolean delete(Object target, Object key) {
		if (target == null || target == JSUndefined.INSTANCE) return true;
		if (target instanceof JSArray jsArr) {
			Long idx = JSArray.toValidArrayIndex(key);
			if (idx != null) {
				jsArr.deleteElement(idx);
				return true;
			}
			jsArr.delete(JSArray.toPropertyKey(key));
			return true;
		}
		if (target instanceof JSObject obj) {
			obj.delete(JSArray.toPropertyKey(key));
			return true;
		}
		if (target instanceof java.util.Map<?, ?> map) {
			map.remove(key);
			return true;
		}
		return true;
	}
	public static Method getSingleAbstractMethod(Class<?> iface) {
		if (!iface.isInterface()) return null;
		Method sam = null;
		for (Method m : iface.getMethods()) {
			if (Modifier.isAbstract(m.getModifiers()) && !isObjectMethod(m)) {
				if (sam != null && !isSameSignature(sam, m)) {
					return null;
				}
				sam = m;
			}
		}
		return sam;
	}
	private static boolean isObjectMethod(Method m) {
		String     name   = m.getName();
		Class<?>[] params = m.getParameterTypes();
		if ("equals".equals(name) && params.length == 1 && params[0] == Object.class) return true;
		if ("hashCode".equals(name) && params.length == 0) return true;
		if ("toString".equals(name) && params.length == 0) return true;
		return false;
	}
	private static boolean isSameSignature(Method m1, Method m2) {
		if (!m1.getName().equals(m2.getName())) return false;
		if (m1.getParameterCount() != m2.getParameterCount()) return false;
		Class<?>[] p1 = m1.getParameterTypes();
		Class<?>[] p2 = m2.getParameterTypes();
		for (int i = 0; i < p1.length; i++) {
			if (p1[i] != p2[i]) return false;
		}
		return true;
	}
	public static Object createInterfaceAdapter(Class<?> targetType, JSFunction fn) {
		return MagicJIT.getFunctionAdapter(targetType, fn);
	}
	public static Object createInterfaceAdapter(Class<?> targetType, JSObject jsObj) {
		return MagicJIT.getObjectAdapter(targetType, jsObj);
	}
	public static Object castValue(Object val, Class<?> targetType) {
		if (val == null) return castNull(targetType);
		if (targetType == Object.class || targetType.isInstance(val)) return val;
		return castValueSlow(val, targetType);
	}
	public static Object castNull(Class<?> targetType) {
		if (!targetType.isPrimitive()) return null;
		if (targetType == int.class) return 0;
		if (targetType == double.class) return 0.0;
		if (targetType == boolean.class) return false;
		if (targetType == long.class) return 0L;
		if (targetType == float.class) return 0.0f;
		if (targetType == short.class) return (short) 0;
		if (targetType == byte.class) return (byte) 0;
		if (targetType == char.class) return '\0';
		return null;
	}
	public static Object castValueSlow(Object val, Class<?> targetType) {
		if (targetType == void.class || targetType == Void.class) return null;
		if (targetType == int.class || targetType == Integer.class) return toInt(val);
		if (targetType == double.class || targetType == Double.class) return toDouble(val);
		if (targetType == long.class || targetType == Long.class) return toLong(val);
		if (targetType == boolean.class || targetType == Boolean.class) return toBoolean(val);
		if (targetType == String.class || targetType == CharSequence.class) return toStr(val);
		if (targetType == float.class || targetType == Float.class) return toFloat(val);
		if (targetType == short.class || targetType == Short.class) return toShort(val);
		if (targetType == byte.class || targetType == Byte.class) return toByte(val);
		if (targetType == char.class || targetType == Character.class) return toChar(val);
		if (targetType.isInterface()) {
			if (val instanceof JSFunction fn && getSingleAbstractMethod(targetType) != null) {
				return createInterfaceAdapter(targetType, fn);
			}
			if (val instanceof JSObject jsObj) {
				return createInterfaceAdapter(targetType, jsObj);
			}
		}
		return val;
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
		if (t != null) {
			String msg = t.getMessage();
			if (msg != null && msg.startsWith("TypeError: ")) {
				return JSContext.LazyErrors.createErrorInstance(JSContext.LazyErrors.TYPE_ERROR, msg.substring(11));
			}
			if (msg != null && msg.startsWith("RangeError: ")) {
				return JSContext.LazyErrors.createErrorInstance(JSContext.LazyErrors.RANGE_ERROR, msg.substring(12));
			}
			if (msg != null && msg.startsWith("ReferenceError: ")) {
				return JSContext.LazyErrors.createErrorInstance(JSContext.LazyErrors.REFERENCE_ERROR, msg.substring(16));
			}
			if (msg != null && msg.startsWith("SyntaxError: ")) {
				return JSContext.LazyErrors.createErrorInstance(JSContext.LazyErrors.SYNTAX_ERROR, msg.substring(13));
			}
			return msg != null ? msg : t.toString();
		}
		return "Error";
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
		int res = toInt(a) >>> toInt(b);
		// 只有最高位为 1 (res < 0) 时才越界需要提拔为 Double，90%+ 的正数直接走 int (0 堆分配)
		return res >= 0 ? (Integer) res : (double) ((long) res & UINT32_MASK);
	}
	// 纯基本类型特化：内联后是绝对纯净的单条 CPU 机器指令（andl, orl, xorl, shll, sarl）
	public static int bitAnd(int a, int b) { return a & b; }
	public static int bitOr(int a, int b) { return a | b; }
	public static int bitXor(int a, int b) { return a ^ b; }
	public static int bitNot(int a) { return ~a; }
	public static int shl(int a, int b) { return a << b; }
	public static int shr(int a, int b) { return a >> b; }
	//endregion
}
