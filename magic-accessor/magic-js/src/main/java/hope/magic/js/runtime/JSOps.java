package hope.magic.js.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.BaseStream;

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

	public static double trunc(double d) {
		return d < 0 ? Math.ceil(d) : Math.floor(d);
	}

	public static double log2(double d) {
		return Math.log(d) / 0.6931471805599453;
	}

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
		if (a instanceof String s) return stringToDouble(s) - b;
		return toDouble(a) - b;
	}

	public static double sub(double a, Object b) {
		if (b instanceof String s) return a - stringToDouble(s);
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
		if (a instanceof String s) return stringToDouble(s) * b;
		return toDouble(a) * b;
	}

	public static double mul(double a, Object b) {
		if (b instanceof String s) return a * stringToDouble(s);
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
		if (a instanceof String s) return stringToDouble(s) / b;
		return toDouble(a) / b;
	}

	public static double div(double a, Object b) {
		if (b instanceof String s) return a / stringToDouble(s);
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
		if (a instanceof String s) return stringToDouble(s) % b;
		return toDouble(a) % b;
	}

	public static double mod(double a, Object b) {
		if (b instanceof String s) return a % stringToDouble(s);
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
		if (a instanceof String sa && b instanceof Number nb) {
			return stringToDouble(sa) == nb.doubleValue();
		}
		if (a instanceof Number na && b instanceof String sb) {
			return na.doubleValue() == stringToDouble(sb);
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
	 * <p>
	 * <b>性能关键优化：</b><br>
	 * 采用 {@link Double#doubleToLongBits(double)} 代替繁琐的多重分支判断：
	 * <ol>
	 *   <li>{@code doubleToLongBits} 为 HotSpot C2 的 {@code @IntrinsicCandidate} 内在函数，
	 *       在 x86-64 下由 {@code vmovq + ucomisd + cmovp} 指令序列实现，完全无条件跳转预测分支；</li>
	 *   <li>规范性：它会自动将所有形式的 NaN（无论正负、quiet/signaling、payload）归一化为标准的
	 *       {@code 0x7ff8000000000000L}，因此任意两个 NaN 比较天然恒等；</li>
	 *   <li>符号位保留：+0.0 的 bits 为 {@code 0x0L}，-0.0 的 bits 为 {@code 0x8000000000000000L}，天然满足
	 *       {@code Object.is(+0, -0) === false} 与 {@code Object.is(-0, -0) === true}；</li>
	 *   <li>跨数值类型：先通过 {@link Number#doubleValue()} 抹平 Integer、Long、Double 包装类型的差异，
	 *       使 {@code Object.is(10, 10.0) === true}。</li>
	 * </ol>
	 */
	public static boolean sameValue(Object a, Object b) {
		if (a == b) {
			// 同一引用在 SameValue 语义下自身与自身恒等（包括 NaN、-0）
			return true;
		}
		if (a == null || b == null || a == JSUndefined.INSTANCE || b == JSUndefined.INSTANCE) {
			return false; // 前面已知 a != b
		}
		if (a instanceof Number na && b instanceof Number nb) {
			// 利用 C2 Intrinsic 硬件级归一化 NaN 并保留 +0/-0 符号位
			return Double.doubleToLongBits(na.doubleValue()) == Double.doubleToLongBits(nb.doubleValue());
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

	/**
	 * 原生 double 版本的 TC39 SameValue algorithm (ECMA-262 §7.2.14).
	 * 利用 HotSpot C2 Intrinsic 实现硬件级无分支的 SameValue 比较。
	 */
	public static boolean sameValue(double a, double b) {
		return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
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
		if (a instanceof String) return stringToDouble((String) a) == (b ? 1.0 : 0.0);
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
		if (a instanceof String) return stringToDouble((String) a) == (double) b;
		return false;
	}

	public static boolean isEqDouble(Object a, double b) {
		if (a instanceof Number) return ((Number) a).doubleValue() == b;
		if (a instanceof Boolean) return (((Boolean) a) ? 1.0 : 0.0) == b;
		if (a instanceof String) return stringToDouble((String) a) == b;
		return false;
	}

	public static boolean isEqString(Object a, String b) {
		if (a == null || a == JSUndefined.INSTANCE) return false;
		if (a instanceof String) return a.equals(b);
		if (a instanceof Number) return ((Number) a).doubleValue() == stringToDouble(b);
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
		double da = (a instanceof String sa) ? stringToDouble(sa) : toDouble(a);
		double db = (b instanceof String sb) ? stringToDouble(sb) : toDouble(b);
		return da < db ? Boolean.TRUE : Boolean.FALSE;
	}

	public static Object lte(Object a, Object b) {
		if (a instanceof CharSequence sa && b instanceof CharSequence sb) {
			return sa.toString().compareTo(sb.toString()) <= 0 ? Boolean.TRUE : Boolean.FALSE;
		}
		double da = (a instanceof String sa) ? stringToDouble(sa) : toDouble(a);
		double db = (b instanceof String sb) ? stringToDouble(sb) : toDouble(b);
		return da <= db ? Boolean.TRUE : Boolean.FALSE;
	}

	public static Object gt(Object a, Object b) {
		if (a instanceof CharSequence sa && b instanceof CharSequence sb) {
			return sa.toString().compareTo(sb.toString()) > 0 ? Boolean.TRUE : Boolean.FALSE;
		}
		double da = (a instanceof String sa) ? stringToDouble(sa) : toDouble(a);
		double db = (b instanceof String sb) ? stringToDouble(sb) : toDouble(b);
		return da > db ? Boolean.TRUE : Boolean.FALSE;
	}

	public static Object gte(Object a, Object b) {
		if (a instanceof CharSequence sa && b instanceof CharSequence sb) {
			return sa.toString().compareTo(sb.toString()) >= 0 ? Boolean.TRUE : Boolean.FALSE;
		}
		double da = (a instanceof String sa) ? stringToDouble(sa) : toDouble(a);
		double db = (b instanceof String sb) ? stringToDouble(sb) : toDouble(b);
		return da >= db ? Boolean.TRUE : Boolean.FALSE;
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
		if (val instanceof String s) return stringToDouble(s);
		if (val instanceof JSObject jo) {
			Object prim = toPrimitive(jo, "number");
			return toDouble(prim);
		}
		return Double.NaN;
	}
	/**
	 * 严格遵循 ECMAScript (ECMA-262 §7.1.4.1) 的 String-to-Number 算法。
	 * 支持前导/尾随空白去除、空字符串转 0.0、十进制浮点、0x/0X (十六进制)、0b/0B (二进制)、0o/0O (八进制) 及 Infinity。
	 */
	public static double stringToDouble(String s) {
		String trimmed = s.trim();
		if (trimmed.isEmpty()) return 0.0;
		int len = trimmed.length();
		if (len > 2 && trimmed.charAt(0) == '0') {
			char c = trimmed.charAt(1);
			if (c == 'x' || c == 'X') {
				try {
					return (double) Long.parseLong(trimmed.substring(2), 16);
				} catch (NumberFormatException e) {
					try {
						return new java.math.BigInteger(trimmed.substring(2), 16).doubleValue();
					} catch (Exception ex) {
						return Double.NaN;
					}
				}
			} else if (c == 'b' || c == 'B') {
				try {
					return (double) Long.parseLong(trimmed.substring(2), 2);
				} catch (NumberFormatException e) {
					try {
						return new java.math.BigInteger(trimmed.substring(2), 2).doubleValue();
					} catch (Exception ex) {
						return Double.NaN;
					}
				}
			} else if (c == 'o' || c == 'O') {
				try {
					return (double) Long.parseLong(trimmed.substring(2), 8);
				} catch (NumberFormatException e) {
					try {
						return new java.math.BigInteger(trimmed.substring(2), 8).doubleValue();
					} catch (Exception ex) {
						return Double.NaN;
					}
				}
			}
		}
		try {
			return Double.parseDouble(trimmed);
		} catch (NumberFormatException e) {
			return Double.NaN;
		}
	}

	public static long toLong(Object val) {
		if (val instanceof Long) return (Long) val;
		if (val instanceof Integer) return ((Integer) val).longValue();
		// if (val instanceof Double) return ((Double) val).longValue(); // 不常见
		return toLongSlow(val);
	}

	public static long toLongSlow(Object val) {
		if (val instanceof Number n) return n.longValue();
		if (val == null || val == JSUndefined.INSTANCE) return 0L;
		if (val instanceof Boolean b) return b ? 1L : 0L;
		if (val instanceof String s) return (long) stringToDouble(s);
		return (long) toDouble(val);
	}

	public static int toInt(double d) {
		if (Math.abs(d) < 9.2233720368547758E18) { // double精度范围内的整数，直接转换为 long 再转 int
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
		if (val instanceof String s) return toInt(stringToDouble(s));
		return toInt(toDouble(val));
	}
	public static float toFloat(Object val) {
		if (val instanceof Number n) return n.floatValue();
		if (val instanceof String s) return (float) stringToDouble(s);
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
		if (val instanceof Character) return (Character) val;
		if (val instanceof String s && !s.isEmpty()) return s.charAt(0);
		if (val instanceof Number) return (char) ((Number) val).intValue();
		return '\0';
	}

	public static boolean toBoolean(double d) {
		return d != 0.0 && !Double.isNaN(d);
	}

	public static boolean toBoolean(Object val) {
		if (val instanceof Boolean) return (Boolean) val;
		return isTruthy(val);
	}

	public static String toStr(Object val) {
		if (val instanceof String) return (String) val;
		if (val instanceof Integer) return ((Integer) val).toString();
		// if (val instanceof Long) return ((Long)val).toString(); // 如果 |l| > 2^53，不符合规范
		return toStrSlow(val);
	}

	public static String toStrSlow(Object val) {
		if (val == null) return "null";
		if (val == JSUndefined.INSTANCE) return "undefined";
		if (val instanceof JSSymbol) throw JSContext.makeTypeError("Cannot convert a Symbol value to a string");

		if (val instanceof Boolean b) return b.toString();
		if (val instanceof Number num) return numberToString(num.doubleValue());
		if (val instanceof JSObject jo) {
			Object prim = toPrimitive(jo, "string");
			return toStr(prim);
		}
		return String.valueOf(val);
	}

	private static final double MIN_PLAIN = 1e-6;
	private static final double MAX_PLAIN = 1e21;

	/** 严格符合 ECMAScript (ECMA-262) 规范的 Number::toString 算法 */
	public static String numberToString(double d) {
		if (Double.isNaN(d)) return "NaN";
		if (d == 0.0) return "0"; // 涵盖 +0.0 与 -0.0
		if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";

		double abs = Math.abs(d);
		return (abs >= MIN_PLAIN && abs < MAX_PLAIN)
		 ? plainDecimal(d)
		 : scientificNotation(d);
	}

	/** <p>[1e-6, 1e21) 区间：常规十进制，无科学计数法</p>
	 * <p>Java 7 及更早版本会有问题，但无所谓了（0D -> "0.0"）</p>
	 * */
	private static String plainDecimal(double d) {
		return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
	}

	/** 区间外：转换为 JS 规范的科学计数法，如 "1.23e+22" / "1e-7" */
	private static String scientificNotation(double d) {
		String s      = Double.toString(d);
		int    eIndex = s.indexOf('E');

		String mantissa = s.substring(0, eIndex);
		if (mantissa.endsWith(".0")) {
			mantissa = mantissa.substring(0, mantissa.length() - 2);
		}

		String exp = s.substring(eIndex + 1);
		if (!exp.startsWith("-")) {
			exp = "+" + exp;
		}

		return mantissa + "e" + exp;
	}

	public static Object toPrimitive(Object val, boolean preferString) {
		return toPrimitive(val, preferString ? "string" : "default");
	}

	public static Object toPrimitive(Object val, String hint) {
		if (!(val instanceof JSObject jo)) return val;
		boolean preferString = "string".equals(hint);
		if (jo instanceof JSContext.JSDate && !preferString) {
			preferString = true;
		}

		JSContext cx = JSContext.CURRENT.get();

		// ES6 7.1.1 ToPrimitive: check @@toPrimitive method first
		if (jo.has(JSSymbol.TO_PRIMITIVE)) {
			Object toPrim = jo.get(JSSymbol.TO_PRIMITIVE);
			if (toPrim instanceof JSFunction fn) {
				try {
					Object res = fn.call1(cx, jo, hint != null ? hint : "default");
					if (!(res instanceof JSObject)) return res;
					throw JSContext.makeTypeError("Cannot convert object to primitive value");
				} catch (RuntimeException re) {
					throw re;
				} catch (Throwable t) {
					throw new RuntimeException(t);
				}
			}
		}

		String first  = preferString ? "toString" : "valueOf";
		String second = preferString ? "valueOf" : "toString";

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

	/** MagicJIT是直接调用{@link #castValue(Object, Class)} */
	public static Object toInterface(Object val, Class<?> iface) {
		return castValue(val, iface);
	}

	public static Iterator<?> toIterator(Object target) {
		return toIterator(JSContext.current(), target);
	}

	public static Iterator<?> toIterator(JSContext cx, Object target) {
		if (target == null || target == JSUndefined.INSTANCE) {
			throw JSContext.makeTypeError(target + " is not iterable (cannot read property Symbol(Symbol.iterator))");
		}
		// 1. JSObject 自定义 Symbol.iterator 协议支持
		if (target instanceof JSObject jo) {
			// 原生 JSArray 在未被局部重写 Symbol.iterator 且全局迭代器保护器有效时走极速直接迭代器
			if (jo instanceof JSArray arr && !arr.hasOwnProperty(JSSymbol.ITERATOR)) {
				if (BuiltinProtector.isIteratorValid()) {
					return arr.iterator();
				}
			}
			Object iterMethod = jo.get(JSSymbol.ITERATOR);
			if (iterMethod instanceof JSFunction iterFn) {
				Object iterObj;
				try {
					iterObj = iterFn.call(cx, jo, JSFunction.EMPTY_ARGS);
				} catch (Throwable t) {
					if (t instanceof RuntimeException re) throw re;
					throw new RuntimeException(t);
				}
				if (!(iterObj instanceof JSObject itObj)) {
					throw JSContext.makeTypeError("Result of the Symbol.iterator method is not an object");
				}
				Object nextProp = itObj.get("next");
				if (!(nextProp instanceof JSFunction nextFn)) {
					throw JSContext.makeTypeError("iterator.next is not a function");
				}
				return new JSIteratorWrapper(cx, itObj, nextFn);
			}
		}

		// 2. 字符串迭代 (按 Unicode 码点展开)
		if (target instanceof CharSequence cs) {
			return new Iterator<Object>() {
				private final String s     = cs.toString();
				private       int    index = 0;
				private final int    len   = s.length();

				@Override
				public boolean hasNext() {
					return index < len;
				}

				@Override
				public Object next() {
					if (index >= len) throw new NoSuchElementException();
					int    cp  = Character.codePointAt(s, index);
					String res = new String(Character.toChars(cp));
					index += Character.charCount(cp);
					return res;
				}
			};
		}

		// 3. 原生 Java 集合与迭代器互操作
		if (target instanceof Iterable<?> iterable) {
			return iterable.iterator();
		}
		if (target instanceof Iterator<?> iterator) {
			return iterator;
		}
		if (target instanceof Object[] arr) {
			return Arrays.asList(arr).iterator();
		}
		if (target.getClass().isArray()) {
			int                    len  = Array.getLength(target);
			List<Object> list = new ArrayList<>(len);
			for (int i = 0; i < len; i++) {
				list.add(Array.get(target, i));
			}
			return list.iterator();
		}
		if (target instanceof Map<?, ?> map) {
			return map.entrySet().iterator();
		}
		if (target instanceof Map.Entry<?, ?> entry) {
			return List.of(entry.getKey(), entry.getValue()).iterator();
		}
		if (target instanceof BaseStream<?, ?> stream) {
			return stream.iterator();
		}
		if (target instanceof Enumeration<?> en) {
			return en.asIterator();
		}
		if (target instanceof Optional<?> opt) {
			return opt.stream().iterator();
		}

		throw JSContext.makeTypeError(toStr(target) + " is not iterable");
	}

	public static class JSIteratorWrapper implements Iterator<Object> {
		private final JSContext  cx;
		private final JSObject   iterObj;
		private final JSFunction nextFn;
		private       Object     nextValue;
		private       boolean    hasCached = false;
		private       boolean    done      = false;

		public JSIteratorWrapper(JSContext cx, JSObject iterObj, JSFunction nextFn) {
			this.cx = cx;
			this.iterObj = iterObj;
			this.nextFn = nextFn;
		}

		@Override
		public boolean hasNext() {
			if (done) return false;
			if (hasCached) return true;
			try {
				Object res = nextFn.call(cx, iterObj, JSFunction.EMPTY_ARGS);
				if (!(res instanceof JSObject resObj)) {
					throw JSContext.makeTypeError("Iterator next result is not an object");
				}
				Object doneVal = resObj.get("done");
				if (isTruthy(doneVal)) {
					done = true;
					return false;
				}
				nextValue = resObj.get("value");
				hasCached = true;
				return true;
			} catch (Throwable t) {
				if (t instanceof RuntimeException re) throw re;
				throw new RuntimeException(t);
			}
		}

		@Override
		public Object next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			hasCached = false;
			return nextValue;
		}
	}

	public static JSArray toArray(Object target) {
		if (target == null || target == JSUndefined.INSTANCE) {
			throw JSContext.makeTypeError(target + " is not iterable");
		}
		if (target instanceof JSArray arr) {
			return arr;
		}
		Iterator<?> it  = toIterator(target);
		JSArray     res = new JSArray();
		while (it.hasNext()) {
			res.push(it.next());
		}
		return res;
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
		if (target instanceof List<?> list) {
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
			int len = Array.getLength(target);
			if (start < 0) start = Math.max(0, len + start);
			if (start >= len) return new JSArray();
			JSArray res = new JSArray();
			for (int i = start; i < len; i++) {
				res.push(Array.get(target, i));
			}
			return res;
		}
		return new JSArray();
	}

	public static Object restObject(Object target, String excludedCsv) {
		if (!(target instanceof JSObject jsObj)) {
			return new JSObject();
		}
		Set<String> excluded = new HashSet<>();
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

	public static Iterator<?> toKeyIterator(Object target) {
		if (target == null || target == JSUndefined.INSTANCE) {
			return Collections.emptyIterator();
		}
		if (target instanceof JSObject jsObj) {
			return jsObj.keys().iterator();
		}
		if (target instanceof Map<?, ?> map) {
			List<String> keys = new ArrayList<>();
			for (Object k : map.keySet()) keys.add(String.valueOf(k));
			return keys.iterator();
		}
		if (target instanceof CharSequence seq) {
			List<String> indices = new ArrayList<>();
			for (int i = 0; i < seq.length(); i++) indices.add(String.valueOf(i));
			return indices.iterator();
		}
		if (target.getClass().isArray()) {
			int          len     = Array.getLength(target);
			List<String> indices = new ArrayList<>();
			for (int i = 0; i < len; i++) indices.add(String.valueOf(i));
			return indices.iterator();
		}
		return Collections.emptyIterator();
	}

	public static String typeOf(Object val) {
		if (val == null) return "object";
		if (val == JSUndefined.INSTANCE) return "undefined";
		if (val instanceof Boolean) return "boolean";
		if (val instanceof Number) return "number";
		if (val instanceof CharSequence) return "string";
		if (val instanceof JSSymbol) return "symbol";
		if (val instanceof JSFunction) return "function";
		if (val instanceof Executable || val instanceof MethodHandle) return "function";
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
		if (target instanceof Map<?, ?> map) {
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
