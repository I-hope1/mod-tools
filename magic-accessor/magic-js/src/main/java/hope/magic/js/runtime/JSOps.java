package hope.magic.js.runtime;

import java.util.Objects;

public class JSOps {

	public static Object add(Object a, Object b) {
		if (a instanceof Double && b instanceof Double) return (Double) a + (Double) b;
		if (a instanceof Integer && b instanceof Integer) return (Integer) a + (Integer) b;
		if (a instanceof String || b instanceof String) {
			return String.valueOf(a) + String.valueOf(b);
		}
		return toDouble(a) + toDouble(b);
	}

	public static Object sub(Object a, Object b) {
		if (a instanceof Double && b instanceof Double) return (Double) a - (Double) b;
		if (a instanceof Integer && b instanceof Integer) return (Integer) a - (Integer) b;
		return toDouble(a) - toDouble(b);
	}

	public static Object mul(Object a, Object b) {
		if (a instanceof Double && b instanceof Double) return (Double) a * (Double) b;
		if (a instanceof Integer && b instanceof Integer) return (Integer) a * (Integer) b;
		return toDouble(a) * toDouble(b);
	}

	public static Object div(Object a, Object b) {
		if (a instanceof Double && b instanceof Double) return (Double) a / (Double) b;
		return toDouble(a) / toDouble(b);
	}

	public static Object mod(Object a, Object b) {
		if (a instanceof Integer && b instanceof Integer) {
			int bVal = (Integer) b;
			if (bVal != 0) return (Integer) a % bVal;
		}
		if (a instanceof Double && b instanceof Double) return (Double) a % (Double) b;
		return toDouble(a) % toDouble(b);
	}

	public static Object eq(Object a, Object b) {
		if (a == b) return Boolean.TRUE;
		if (a == null || a == JSUndefined.INSTANCE) {
			return (b == null || b == JSUndefined.INSTANCE) ? Boolean.TRUE : Boolean.FALSE;
		}
		if (b == null || b == JSUndefined.INSTANCE) return Boolean.FALSE;

		if (a instanceof Number && b instanceof Number) {
			return ((Number) a).doubleValue() == ((Number) b).doubleValue() ? Boolean.TRUE : Boolean.FALSE;
		}
		return Objects.equals(a, b) ? Boolean.TRUE : Boolean.FALSE;
	}

	public static Object strictEq(Object a, Object b) {
		if (a == b) return Boolean.TRUE;
		if (a == null || b == null) return Boolean.FALSE;
		if (a.getClass() != b.getClass() && !(a instanceof Number && b instanceof Number)) {
			return Boolean.FALSE;
		}
		return eq(a, b);
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
		if (val instanceof Number) return ((Number) val).doubleValue() != 0.0 && !Double.isNaN(((Number) val).doubleValue());
		if (val instanceof String) return !((String) val).isEmpty();
		return true;
	}

	public static Object not(Object val) {
		return isTruthy(val) ? Boolean.FALSE : Boolean.TRUE;
	}

	public static double toDouble(Object val) {
		if (val == null || val == JSUndefined.INSTANCE) return Double.NaN;
		if (val instanceof Double) return (Double) val;
		if (val instanceof Integer) return ((Integer) val).doubleValue();
		if (val instanceof Long) return ((Long) val).doubleValue();
		if (val instanceof Number) return ((Number) val).doubleValue();
		if (val instanceof Boolean) return ((Boolean) val) ? 1.0 : 0.0;
		if (val instanceof String) {
			try {
				return Double.parseDouble((String) val);
			} catch (NumberFormatException e) {
				return Double.NaN;
			}
		}
		return Double.NaN;
	}

	public static long toLong(Object val) {
		if (val == null || val == JSUndefined.INSTANCE) return 0L;
		if (val instanceof Integer) return ((Integer) val).longValue();
		if (val instanceof Long) return (Long) val;
		if (val instanceof Number) return ((Number) val).longValue();
		if (val instanceof Boolean) return ((Boolean) val) ? 1L : 0L;
		return (long) toDouble(val);
	}

	public static int toInt(Object val) {
		if (val == null || val == JSUndefined.INSTANCE) return 0;
		if (val instanceof Integer) return (Integer) val;
		if (val instanceof Number) return ((Number) val).intValue();
		if (val instanceof Boolean) return ((Boolean) val) ? 1 : 0;
		return (int) toDouble(val);
	}
}
