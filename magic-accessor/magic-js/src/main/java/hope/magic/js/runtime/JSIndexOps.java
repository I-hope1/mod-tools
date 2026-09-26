package hope.magic.js.runtime;

import hope.magic.runtime.*;

import java.lang.invoke.*;
import java.lang.reflect.Array;
import java.util.*;

import static hope.magic.js.runtime.SlotMH.*;

@SuppressWarnings({"unused", "unchecked", "rawtypes", "RedundantCast", "UnnecessaryUnboxing"})
public class JSIndexOps {
	public static final MethodHandles.Lookup LOOKUP = Magic.lookup;

	public static final  int      SMALL_INT_MIN     = -128;
	public static final  int      SMALL_INT_MAX     = 1023;
	public static final  int      SMALL_INT_SIZE    = 1024;
	public static final  int      SMALL_INT_OFFSET  = -SMALL_INT_MIN;
	public static final  int      SMALL_INT_COUNT   = SMALL_INT_MAX - SMALL_INT_MIN + 1;
	private static final String[] SMALL_INT_STRINGS = new String[SMALL_INT_COUNT];

	static {
		for (int i = SMALL_INT_MIN; i <= SMALL_INT_MAX; i++) SMALL_INT_STRINGS[i + SMALL_INT_OFFSET] = String.valueOf(i).intern();
	}

	public static final MethodHandle MH_GET_INDEX_JS_ARRAY;
	public static final MethodHandle MH_GET_INDEX_LIST;
	public static final MethodHandle MH_GET_INDEX_OBJECT_ARRAY;
	public static final MethodHandle MH_GET_INDEX_PRIMITIVE_ARRAY;
	public static final MethodHandle MH_GET_INDEX_INT_ARRAY;
	public static final MethodHandle MH_GET_INDEX_DOUBLE_ARRAY;
	public static final MethodHandle MH_GET_INDEX_LONG_ARRAY;
	public static final MethodHandle MH_GET_INDEX_MAP;

	public static final MethodHandle MH_SET_INDEX_JS_ARRAY;
	public static final MethodHandle MH_SET_INDEX_LIST;
	public static final MethodHandle MH_SET_INDEX_OBJECT_ARRAY;
	public static final MethodHandle MH_SET_INDEX_INT_ARRAY;
	public static final MethodHandle MH_SET_INDEX_DOUBLE_ARRAY;
	public static final MethodHandle MH_SET_INDEX_LONG_ARRAY;
	public static final MethodHandle MH_SET_INDEX_PRIMITIVE_ARRAY;
	public static final MethodHandle MH_SET_INDEX_MAP;

	public static final MethodHandle MH_IS_EXACT_CLASS;

	static {
		try {
			MH_IS_EXACT_CLASS = LOOKUP.findStatic(JSIndexOps.class, "isExactClass", MethodType.methodType(boolean.class, Class.class, Object.class));

			MH_GET_INDEX_JS_ARRAY = LOOKUP.findStatic(JSIndexOps.class, "getIndexJSArray", MethodType.methodType(Object.class, Object.class, Object.class));
			MH_GET_INDEX_LIST = LOOKUP.findStatic(JSIndexOps.class, "getIndexList", MethodType.methodType(Object.class, Object.class, Object.class));
			MH_GET_INDEX_OBJECT_ARRAY = LOOKUP.findStatic(JSIndexOps.class, "getIndexObjectArray", MethodType.methodType(Object.class, Object.class, Object.class));
			MH_GET_INDEX_PRIMITIVE_ARRAY = LOOKUP.findStatic(JSIndexOps.class, "getIndexPrimitiveArray", MethodType.methodType(Object.class, Object.class, Object.class));
			MH_GET_INDEX_INT_ARRAY = LOOKUP.findStatic(JSIndexOps.class, "getIndexIntArray", MethodType.methodType(Object.class, Object.class, Object.class));
			MH_GET_INDEX_DOUBLE_ARRAY = LOOKUP.findStatic(JSIndexOps.class, "getIndexDoubleArray", MethodType.methodType(Object.class, Object.class, Object.class));
			MH_GET_INDEX_LONG_ARRAY = LOOKUP.findStatic(JSIndexOps.class, "getIndexLongArray", MethodType.methodType(Object.class, Object.class, Object.class));
			MH_GET_INDEX_MAP = LOOKUP.findStatic(JSIndexOps.class, "getIndexMap", MethodType.methodType(Object.class, Object.class, Object.class));

			MH_SET_INDEX_JS_ARRAY = LOOKUP.findStatic(JSIndexOps.class, "setIndexJSArray", MethodType.methodType(void.class, Object.class, Object.class, Object.class));
			MH_SET_INDEX_LIST = LOOKUP.findStatic(JSIndexOps.class, "setIndexList", MethodType.methodType(void.class, Object.class, Object.class, Object.class));
			MH_SET_INDEX_OBJECT_ARRAY = LOOKUP.findStatic(JSIndexOps.class, "setIndexObjectArray", MethodType.methodType(void.class, Object.class, Object.class, Object.class));
			MH_SET_INDEX_INT_ARRAY = LOOKUP.findStatic(JSIndexOps.class, "setIndexIntArray", MethodType.methodType(void.class, Object.class, Object.class, Object.class));
			MH_SET_INDEX_DOUBLE_ARRAY = LOOKUP.findStatic(JSIndexOps.class, "setIndexDoubleArray", MethodType.methodType(void.class, Object.class, Object.class, Object.class));
			MH_SET_INDEX_LONG_ARRAY = LOOKUP.findStatic(JSIndexOps.class, "setIndexLongArray", MethodType.methodType(void.class, Object.class, Object.class, Object.class));
			MH_SET_INDEX_PRIMITIVE_ARRAY = LOOKUP.findStatic(JSIndexOps.class, "setIndexPrimitiveArray", MethodType.methodType(void.class, Object.class, Object.class, Object.class));
			MH_SET_INDEX_MAP = LOOKUP.findStatic(JSIndexOps.class, "setIndexMap", MethodType.methodType(void.class, Object.class, Object.class, Object.class));
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	public static boolean isExactClass(Class<?> expected, Object target) {
		return target != null && target.getClass() == expected;
	}

	public static String fastIntToString(int i) {
		int idx = i + SMALL_INT_OFFSET;
		if (idx >= 0 && idx < SMALL_INT_COUNT) return SMALL_INT_STRINGS[idx];
		return String.valueOf(i);
	}

	public static Long toValidArrayLongIndex(Object index) {
		return JSArray.toValidArrayIndex(index);
	}

	public static Integer toValidArrayIndex(Object index) {
		return JSArray.toValidJavaArrayIndex(index);
	}

	public static String toPropertyKey(Object index) {
		return JSOps.toPropertyKey(index);
	}

	public static Object getArrayElement(Object target, int idx) {
		if (idx < 0) return JSUndefined.INSTANCE;
		if (target instanceof Object[] a) return idx < a.length ? a[idx] : JSUndefined.INSTANCE;
		if (target instanceof int[] a) return idx < a.length ? (double) a[idx] : JSUndefined.INSTANCE;
		if (target instanceof double[] a) return idx < a.length ? a[idx] : JSUndefined.INSTANCE;
		if (target instanceof long[] a) return idx < a.length ? (double) a[idx] : JSUndefined.INSTANCE;
		if (target instanceof float[] a) return idx < a.length ? (double) a[idx] : JSUndefined.INSTANCE;
		if (target instanceof short[] a) return idx < a.length ? (double) a[idx] : JSUndefined.INSTANCE;
		if (target instanceof byte[] a) return idx < a.length ? (double) a[idx] : JSUndefined.INSTANCE;
		if (target instanceof boolean[] a) return idx < a.length ? a[idx] : JSUndefined.INSTANCE;
		if (target instanceof char[] a) return idx < a.length ? String.valueOf(a[idx]) : JSUndefined.INSTANCE;
		return JSUndefined.INSTANCE;
	}

	public static void setArrayElement(Object target, int idx, Object value) {
		if (idx < 0) return;
		if (target instanceof Object[] a) {
			if (idx < a.length) a[idx] = value;
			return;
		}
		if (target instanceof int[] a) {
			if (idx < a.length) a[idx] = JSOps.toInt(value);
			return;
		}
		if (target instanceof double[] a) {
			if (idx < a.length) a[idx] = JSOps.toDouble(value);
			return;
		}
		if (target instanceof long[] a) {
			if (idx < a.length) a[idx] = JSOps.toLong(value);
			return;
		}
		if (target instanceof float[] a) {
			if (idx < a.length) a[idx] = JSOps.toFloat(value);
			return;
		}
		if (target instanceof short[] a) {
			if (idx < a.length) a[idx] = JSOps.toShort(value);
			return;
		}
		if (target instanceof byte[] a) {
			if (idx < a.length) a[idx] = JSOps.toByte(value);
			return;
		}
		if (target instanceof boolean[] a) {
			if (idx < a.length) a[idx] = JSOps.toBoolean(value);
			return;
		}
		if (target instanceof char[] a) {
			if (idx < a.length) a[idx] = JSOps.toChar(value);
			return;
		}
	}

	public static int getArrayLengthInt(Object target) {
		return target != null && target.getClass().isArray() ? Array.getLength(target) : 0;
	}

	public static double getArrayLengthDouble(Object target) {
		return target != null && target.getClass().isArray() ? (double) Array.getLength(target) : Double.NaN;
	}

	public static Object getIndex(Object target, int index) {
		if (target instanceof JSArray jsArr) return jsArr.getElement(index);
		if (target instanceof Object[] a) {
			return (index >= 0 && index < a.length) ? a[index] : JSUndefined.INSTANCE;
		}
		if (target instanceof List list) {
			return (index >= 0 && index < list.size()) ? list.get(index) : JSUndefined.INSTANCE;
		}
		if (target != null && target.getClass().isArray()) {
			return getArrayElement(target, index);
		}
		if (target instanceof JSObject jsObj) {
			return jsObj.get(fastIntToString(index));
		}
		if (target instanceof CharSequence seq) {
			return (index >= 0 && index < seq.length()) ? String.valueOf(seq.charAt(index)) : JSUndefined.INSTANCE;
		}
		if (target instanceof Map map) {
			return map.get(index);
		}
		if (target instanceof Map.Entry<?, ?> entry) {
			if (index == 0) return entry.getKey();
			if (index == 1) return entry.getValue();
			return JSUndefined.INSTANCE;
		}
		return JSLinker.getPropGeneric(target, fastIntToString(index));
	}

	public static void setIndex(Object target, int index, Object value) {
		if (target instanceof JSArray jsArr) {
			jsArr.setElement(index, value);
			return;
		}
		if (target instanceof Object[] a) {
			if (index >= 0 && index < a.length) a[index] = value;
			return;
		}
		if (target instanceof List list) {
			if (index >= 0 && index < list.size()) {
				list.set(index, value);
			} else if (index >= 0 && index <= list.size() + 1024 && index < 65536) {
				while (list.size() <= index) list.add(null);
				list.set(index, value);
			}
			return;
		}
		if (target != null && target.getClass().isArray()) {
			setArrayElement(target, index, value);
			return;
		}
		if (target instanceof JSObject jsObj) {
			jsObj.put(fastIntToString(index), value);
			return;
		}
		if (target instanceof Map map) {
			map.put(index, value);
			return;
		}
		JSLinker.setPropGeneric(target, value, fastIntToString(index));
	}

	public static Object getIndex(Object target, Object index) {
		if (target == null || target == JSUndefined.INSTANCE) return JSUndefined.INSTANCE;
		if (index instanceof Integer i) {
			return getIndex(target, i.intValue());
		}
		if (index instanceof Double d) {
			double val = d;
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				return getIndex(target, (int) val);
			}
		}
		if (target instanceof JSArray jsArr) {
			Long idx = JSArray.toValidArrayIndex(index);
			if (idx != null) {
				return jsArr.getElement(idx);
			}
			if (index instanceof JSSymbol sym) {
				return jsArr.get(sym);
			}
			return jsArr.get(JSArray.toPropertyKey(index));
		}
		if (target instanceof JSObject jsObj) {
			if (index instanceof JSSymbol sym) {
				return jsObj.get(sym);
			}
			return jsObj.get(JSArray.toPropertyKey(index));
		}
		if (target.getClass().isArray()) {
			Integer idx = JSArray.toValidJavaArrayIndex(index);
			if (idx != null) {
				return getArrayElement(target, idx);
			}
			return JSUndefined.INSTANCE;
		}
		if (target instanceof List list) {
			Integer idx = JSArray.toValidJavaArrayIndex(index);
			if (idx != null && idx >= 0 && idx < list.size()) {
				return list.get(idx);
			}
			return JSUndefined.INSTANCE;
		}
		if (target instanceof CharSequence seq) {
			Integer idx = JSArray.toValidJavaArrayIndex(index);
			if (idx != null && idx >= 0 && idx < seq.length()) {
				return String.valueOf(seq.charAt(idx));
			}
			return JSUndefined.INSTANCE;
		}
		if (target instanceof Map map) {
			Object val = map.get(index);
			if (val == null && !map.containsKey(index)) {
				val = map.get(toPropertyKey(index));
			}
			return val == null && !map.containsKey(index) ? JSUndefined.INSTANCE : val;
		}
		return JSLinker.getPropGeneric(target, toPropertyKey(index));
	}

	public static void setIndex(Object target, Object index, Object value) {
		if (target == null || target == JSUndefined.INSTANCE) return;
		if (index instanceof Integer i) {
			setIndex(target, i.intValue(), value);
			return;
		}
		if (index instanceof Double d) {
			double val = d;
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				setIndex(target, (int) val, value);
				return;
			}
		}
		if (target instanceof JSArray jsArr) {
			Long idx = JSArray.toValidArrayIndex(index);
			if (idx != null) {
				jsArr.setElement(idx, value);
				return;
			}
			if (index instanceof JSSymbol sym) {
				jsArr.put(sym, value);
				return;
			}
			jsArr.put(JSArray.toPropertyKey(index), value);
			return;
		}
		if (target instanceof JSObject jsObj) {
			if (index instanceof JSSymbol sym) {
				jsObj.put(sym, value);
				return;
			}
			jsObj.put(JSArray.toPropertyKey(index), value);
			return;
		}
		if (target.getClass().isArray()) {
			Integer idx = JSArray.toValidJavaArrayIndex(index);
			if (idx != null) {
				setArrayElement(target, idx, value);
			}
			return;
		}
		if (target instanceof List list) {
			Integer idx = JSArray.toValidJavaArrayIndex(index);
			if (idx != null) {
				if (idx >= 0 && idx < list.size()) {
					list.set(idx, value);
				} else if (idx >= 0 && idx <= list.size() + 1024 && idx < 65536) {
					while (list.size() <= idx) list.add(null);
					list.set(idx, value);
				}
			}
			return;
		}
		if (target instanceof Map map) {
			map.put(index, value);
			return;
		}
		JSLinker.setPropGeneric(target, value, toPropertyKey(index));
	}

	public static Object getIndexJSArray(Object target, Object index) {
		JSArray jsArr = (JSArray) target;
		if (index instanceof Integer i) {
			int val = i.intValue();
			return (val >= 0 && val < jsArr.length()) ? jsArr.getElement(val) : jsArr.get(fastIntToString(val));
		}
		if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				int idx = (int) val;
				return (idx < jsArr.length()) ? jsArr.getElement(idx) : jsArr.get(fastIntToString(idx));
			}
		}
		Long idx = JSArray.toValidArrayIndex(index);
		if (idx != null) {
			return jsArr.getElement(idx);
		}
		if (index instanceof JSSymbol sym) {
			return jsArr.get(sym);
		}
		return jsArr.get(JSArray.toPropertyKey(index));
	}

	public static Object getIndexList(Object target, Object index) {
		List<?> list = (List<?>) target;
		if (index instanceof Integer i) {
			int idx = i.intValue();
			return (idx >= 0 && idx < list.size()) ? list.get(idx) : JSUndefined.INSTANCE;
		}
		if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				int idx = (int) val;
				return (idx >= 0 && idx < list.size()) ? list.get(idx) : JSUndefined.INSTANCE;
			}
		}
		Integer idx = JSArray.toValidJavaArrayIndex(index);
		if (idx != null && idx >= 0 && idx < list.size()) {
			return list.get(idx);
		}
		return JSUndefined.INSTANCE;
	}

	public static Object getIndexObjectArray(Object target, Object index) {
		Object[] a = (Object[]) target;
		if (index instanceof Integer i) {
			int idx = i.intValue();
			return (idx >= 0 && idx < a.length) ? a[idx] : JSUndefined.INSTANCE;
		}
		if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				int idx = (int) val;
				return (idx < a.length) ? a[idx] : JSUndefined.INSTANCE;
			}
		}
		Integer idx = JSArray.toValidJavaArrayIndex(index);
		if (idx != null && idx >= 0 && idx < a.length) {
			return a[idx];
		}
		return JSUndefined.INSTANCE;
	}

	public static Object getIndexPrimitiveArray(Object target, Object index) {
		if (index instanceof Integer i) {
			return getArrayElement(target, i.intValue());
		}
		if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				return getArrayElement(target, (int) val);
			}
		}
		Integer idx = JSArray.toValidJavaArrayIndex(index);
		if (idx != null) {
			return getArrayElement(target, idx);
		}
		return JSUndefined.INSTANCE;
	}

	public static Object getIndexIntArray(Object target, Object index) {
		int[] a   = (int[]) target;
		int   idx = -1;
		if (index instanceof Integer i) {
			idx = i.intValue();
		} else if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				idx = (int) val;
			}
		} else {
			Integer validIdx = JSArray.toValidJavaArrayIndex(index);
			if (validIdx != null) idx = validIdx;
		}
		return (idx >= 0 && idx < a.length) ? (double) a[idx] : JSUndefined.INSTANCE;
	}

	public static Object getIndexDoubleArray(Object target, Object index) {
		double[] a   = (double[]) target;
		int      idx = -1;
		if (index instanceof Integer i) {
			idx = i.intValue();
		} else if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				idx = (int) val;
			}
		} else {
			Integer validIdx = JSArray.toValidJavaArrayIndex(index);
			if (validIdx != null) idx = validIdx;
		}
		return (idx >= 0 && idx < a.length) ? a[idx] : JSUndefined.INSTANCE;
	}

	public static Object getIndexLongArray(Object target, Object index) {
		long[] a   = (long[]) target;
		int    idx = -1;
		if (index instanceof Integer i) {
			idx = i.intValue();
		} else if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				idx = (int) val;
			}
		} else {
			Integer validIdx = JSArray.toValidJavaArrayIndex(index);
			if (validIdx != null) idx = validIdx;
		}
		return (idx >= 0 && idx < a.length) ? (double) a[idx] : JSUndefined.INSTANCE;
	}

	public static Object getIndexMap(Object target, Object index) {
		Map<?, ?> map = (Map<?, ?>) target;
		Object    val = map.get(index);
		if (val == null && !map.containsKey(index)) {
			val = map.get(toPropertyKey(index));
		}
		return val == null && !map.containsKey(index) ? JSUndefined.INSTANCE : val;
	}

	public static void setIndexJSArray(Object target, Object index, Object value) {
		JSArray jsArr = (JSArray) target;
		if (index instanceof Integer i) {
			int val = i.intValue();
			if (val >= 0) {
				jsArr.setElement(val, value);
				return;
			}
		} else if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				jsArr.setElement((int) val, value);
				return;
			}
		}
		Long idx = JSArray.toValidArrayIndex(index);
		if (idx != null) {
			jsArr.setElement(idx, value);
			return;
		}
		if (index instanceof JSSymbol sym) {
			jsArr.put(sym, value);
			return;
		}
		jsArr.put(JSArray.toPropertyKey(index), value);
	}

	public static void setIndexList(Object target, Object index, Object value) {
		List<Object> list = (List<Object>) target;
		int          idx  = -1;
		if (index instanceof Integer i) {
			idx = i.intValue();
		} else if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				idx = (int) val;
			}
		} else {
			Integer validIdx = JSArray.toValidJavaArrayIndex(index);
			if (validIdx != null) idx = validIdx;
		}
		if (idx >= 0) {
			if (idx < list.size()) {
				list.set(idx, value);
			} else if (idx <= list.size() + 1024 && idx < 65536) {
				while (list.size() <= idx) list.add(null);
				list.set(idx, value);
			}
		}
	}

	public static void setIndexObjectArray(Object target, Object index, Object value) {
		Object[] a   = (Object[]) target;
		int      idx = -1;
		if (index instanceof Integer i) {
			idx = i.intValue();
		} else if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				idx = (int) val;
			}
		} else {
			Integer validIdx = JSArray.toValidJavaArrayIndex(index);
			if (validIdx != null) idx = validIdx;
		}
		if (idx >= 0 && idx < a.length) {
			a[idx] = value;
		}
	}

	public static void setIndexIntArray(Object target, Object index, Object value) {
		int[] a   = (int[]) target;
		int   idx = -1;
		if (index instanceof Integer i) {
			idx = i.intValue();
		} else if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				idx = (int) val;
			}
		} else {
			Integer validIdx = JSArray.toValidJavaArrayIndex(index);
			if (validIdx != null) idx = validIdx;
		}
		if (idx >= 0 && idx < a.length) {
			a[idx] = JSOps.toInt(value);
		}
	}

	public static void setIndexDoubleArray(Object target, Object index, Object value) {
		double[] a   = (double[]) target;
		int      idx = -1;
		if (index instanceof Integer i) {
			idx = i.intValue();
		} else if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				idx = (int) val;
			}
		} else {
			Integer validIdx = JSArray.toValidJavaArrayIndex(index);
			if (validIdx != null) idx = validIdx;
		}
		if (idx >= 0 && idx < a.length) {
			a[idx] = JSOps.toDouble(value);
		}
	}

	public static void setIndexLongArray(Object target, Object index, Object value) {
		long[] a   = (long[]) target;
		int    idx = -1;
		if (index instanceof Integer i) {
			idx = i.intValue();
		} else if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				idx = (int) val;
			}
		} else {
			Integer validIdx = JSArray.toValidJavaArrayIndex(index);
			if (validIdx != null) idx = validIdx;
		}
		if (idx >= 0 && idx < a.length) {
			a[idx] = JSOps.toLong(value);
		}
	}

	public static void setIndexPrimitiveArray(Object target, Object index, Object value) {
		int idx = -1;
		if (index instanceof Integer i) {
			idx = i.intValue();
		} else if (index instanceof Double d) {
			double val = d.doubleValue();
			if (val >= 0 && val <= Integer.MAX_VALUE && val == (int) val) {
				idx = (int) val;
			}
		} else {
			Integer validIdx = JSArray.toValidJavaArrayIndex(index);
			if (validIdx != null) idx = validIdx;
		}
		if (idx >= 0) {
			setArrayElement(target, idx, value);
		}
	}

	public static void setIndexMap(Object target, Object index, Object value) {
		Map<Object, Object> map = (Map<Object, Object>) target;
		if (map.containsKey(index)) {
			map.put(index, value);
			return;
		}
		String strKey = toPropertyKey(index);
		if (map.containsKey(strKey)) {
			map.put(strKey, value);
			return;
		}
		map.put(index, value);
	}

	public static CallSite bootstrapGetIndex(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type
	) {
		ChainedCallSite site     = new ChainedCallSite(type, IndexMH.GET.asType(type));
		MethodHandle    fallback = IndexMH.GET_FALLBACK.bindTo(site).asType(type);
		site.setInitialFallback(fallback);
		site.setTarget(fallback);
		return site;
	}

	public static CallSite bootstrapSetIndex(
	 MethodHandles.Lookup caller,
	 String name,
	 MethodType type
	) {
		ChainedCallSite site     = new ChainedCallSite(type, IndexMH.SET.asType(type));
		MethodHandle    fallback = IndexMH.SET_FALLBACK.bindTo(site).asType(type);
		site.setInitialFallback(fallback);
		site.setTarget(fallback);
		return site;
	}

	@SuppressWarnings("EqualsReplaceableByObjectsCall")
	public static boolean isExactShapeAndKey(JSShape expectedShape, String expectedKey, Object target, Object key) {
		return target instanceof JSObject jsObj
		       && jsObj.shape == expectedShape
		       && (key == expectedKey || (key != null && (key.equals(expectedKey) || (key instanceof JSSymbol sym && sym.getKey().equals(expectedKey)))));
	}

	public static boolean isExactShapeAndStringKey(JSShape expectedShape, String expectedKey, Object target, Object key) {
		return target instanceof JSObject jsObj
		       && jsObj.shape == expectedShape
		       && (key == expectedKey || expectedKey.equals(key));
	}

	public static boolean isExactShapeAndSymbol(JSShape expectedShape, JSSymbol expectedSymbol, Object target,
	                                            Object key) {
		return target instanceof JSObject jsObj
		       && jsObj.shape == expectedShape
		       && key == expectedSymbol;
	}

	public static boolean isExactShapeAndProtoAndStringKey(JSShape expectedShape, JSObject expectedProto,
	                                                       String expectedKey, Object target, Object key) {
		return target instanceof JSObject jsObj
		       && jsObj.shape == expectedShape
		       && jsObj.getPrototype() == expectedProto
		       && (key == expectedKey || (key instanceof String s && expectedKey.equals(s)));
	}

	public static boolean isExactShapeAndProtoAndSymbol(JSShape expectedShape, JSObject expectedProto,
	                                                    JSSymbol expectedSymbol, Object target, Object key) {
		return target instanceof JSObject jsObj
		       && jsObj.shape == expectedShape
		       && jsObj.getPrototype() == expectedProto
		       && key == expectedSymbol;
	}

	public static Object getIndexDynamicFallback(ChainedCallSite site, Object target, Object index) throws Throwable {
		if (target instanceof JSContext.JSGlobalThis globalThis) {
			return globalThis.get(toPropertyKey(index));
		}
		if (target instanceof JSObject jsObj) {
			if (index instanceof String strKey) {
				JSShape s      = jsObj.shape;
				int     offset = s.getOffset(strKey);

				if (offset >= 0 && (!s.hasAccessors || !s.isAccessor(offset)) && site.getChainDepth() < 3) {
					MethodHandle test = LOOKUP.findStatic(
					 JSIndexOps.class,
					 "isExactShapeAndStringKey",
					 MethodType.methodType(boolean.class, JSShape.class, String.class, Object.class, Object.class)
					).bindTo(s).bindTo(strKey);

					MethodHandle getter = offset < 8
					 ? MH_GET_SLOT_OBJECT[offset]
					 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, offset);
					MethodHandle directTarget = MethodHandles.dropArguments(getter, 1, Object.class);

					site.installGuardOrSwitchMegamorphic(test, directTarget.asType(site.type()));
					return jsObj.getSlot(offset);
				} else if (offset < 0 && site.getChainDepth() < 3) {
					JSObject proto = jsObj.getPrototype();
					if (proto != null) {
						JSObject       current      = proto;
						JSObject       holder       = null;
						int            holderOffset = -1;
						List<JSObject> chain        = null;

						while (current != null) {
							int pOff = current.shape.getOffset(strKey);
							if (pOff >= 0 && (current.isDoubleSlot(pOff) || current.getRawObjectSlot(pOff) != JSObject.NOT_FOUND)) {
								holder = current;
								holderOffset = pOff;
								break;
							}
							if (chain == null) chain = new ArrayList<>(2);
							chain.add(current);
							current = current.getPrototype();
						}

						if (holder != null && (!holder.shape.hasAccessors || !holder.shape.isAccessor(holderOffset))) {
							Object val = holder.getSlot(holderOffset);
							MethodHandle test = LOOKUP.findStatic(
							 JSIndexOps.class,
							 "isExactShapeAndProtoAndStringKey",
							 MethodType.methodType(boolean.class, JSShape.class, JSObject.class, String.class, Object.class, Object.class)
							).bindTo(s).bindTo(proto).bindTo(strKey);

							MethodHandle fb      = site.getInitialFallback();
							MethodHandle fbTyped = (fb != null) ? fb.asType(site.type()) : null;
							MethodHandle constTarget = MethodHandles.dropArguments(
							 MethodHandles.constant(Object.class, val), 0, site.type().parameterList()
							).asType(site.type());
							if (chain != null && fbTyped != null) {
								for (JSObject p : chain) {
									constTarget = p.getOrCreateProtoSwitchPoint().guardWithTest(constTarget, fbTyped);
								}
							}
							site.installProtoGuard(holder.getOrCreateProtoSwitchPoint(), test, constTarget);
							return val;
						}
					}
				}
			} else if (index instanceof JSSymbol symKey) {
				JSShape s      = jsObj.shape;
				int     offset = s.getOffset(symKey.getSymbolId());

				if (offset >= 0 && (!s.hasAccessors || !s.isAccessor(offset)) && site.getChainDepth() < 3) {
					MethodHandle test = LOOKUP.findStatic(
					 JSIndexOps.class,
					 "isExactShapeAndSymbol",
					 MethodType.methodType(boolean.class, JSShape.class, JSSymbol.class, Object.class, Object.class)
					).bindTo(s).bindTo(symKey);

					MethodHandle getter = offset < 8
					 ? MH_GET_SLOT_OBJECT[offset]
					 : MethodHandles.insertArguments(MH_GET_JS_OBJ_SLOT, 0, offset);
					MethodHandle directTarget = MethodHandles.dropArguments(getter, 1, Object.class);

					site.installGuardOrSwitchMegamorphic(test, directTarget.asType(site.type()));
					return jsObj.getSlot(offset);
				} else if (offset < 0 && site.getChainDepth() < 3) {
					JSObject proto = jsObj.getPrototype();
					if (proto != null) {
						JSObject       current      = proto;
						JSObject       holder       = null;
						int            holderOffset = -1;
						List<JSObject> chain        = null;

						while (current != null) {
							int pOff = current.shape.getOffset(symKey.getSymbolId());
							if (pOff >= 0 && (current.isDoubleSlot(pOff) || current.getRawObjectSlot(pOff) != JSObject.NOT_FOUND)) {
								holder = current;
								holderOffset = pOff;
								break;
							}
							if (chain == null) chain = new ArrayList<>(2);
							chain.add(current);
							current = current.getPrototype();
						}

						if (holder != null && (!holder.shape.hasAccessors || !holder.shape.isAccessor(holderOffset))) {
							Object val = holder.getSlot(holderOffset);
							MethodHandle test = LOOKUP.findStatic(
							 JSIndexOps.class,
							 "isExactShapeAndProtoAndSymbol",
							 MethodType.methodType(boolean.class, JSShape.class, JSObject.class, JSSymbol.class, Object.class, Object.class)
							).bindTo(s).bindTo(proto).bindTo(symKey);

							MethodHandle fb      = site.getInitialFallback();
							MethodHandle fbTyped = (fb != null) ? fb.asType(site.type()) : null;
							MethodHandle constTarget = MethodHandles.dropArguments(
							 MethodHandles.constant(Object.class, val), 0, site.type().parameterList()
							).asType(site.type());
							if (chain != null && fbTyped != null) {
								for (JSObject p : chain) {
									constTarget = p.getOrCreateProtoSwitchPoint().guardWithTest(constTarget, fbTyped);
								}
							}
							site.installProtoGuard(holder.getOrCreateProtoSwitchPoint(), test, constTarget);
							return val;
						}
					}
				}
			}
		}
		if (target instanceof JSArray) {
			MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
			if (site.type().parameterCount() > 1) {
				test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
			}
			site.installGuardOrSwitchMegamorphic(test, MH_GET_INDEX_JS_ARRAY.asType(site.type()));
			return getIndexJSArray(target, index);
		}
		if (target instanceof List) {
			MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
			if (site.type().parameterCount() > 1) {
				test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
			}
			site.installGuardOrSwitchMegamorphic(test, MH_GET_INDEX_LIST.asType(site.type()));
			return getIndexList(target, index);
		}
		if (target != null && target.getClass().isArray()) {
			MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
			if (site.type().parameterCount() > 1) {
				test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
			}
			MethodHandle directTarget;
			if (target instanceof Object[]) { directTarget = MH_GET_INDEX_OBJECT_ARRAY; } else if (target instanceof int[]) {
				directTarget = MH_GET_INDEX_INT_ARRAY;
			} else if (target instanceof double[]) {
				directTarget = MH_GET_INDEX_DOUBLE_ARRAY;
			} else if (target instanceof long[]) { directTarget = MH_GET_INDEX_LONG_ARRAY; } else {
				directTarget = MH_GET_INDEX_PRIMITIVE_ARRAY;
			}

			site.installGuardOrSwitchMegamorphic(test, directTarget.asType(site.type()));
			if (target instanceof Object[]) return getIndexObjectArray(target, index);
			if (target instanceof int[]) return getIndexIntArray(target, index);
			if (target instanceof double[]) return getIndexDoubleArray(target, index);
			if (target instanceof long[]) return getIndexLongArray(target, index);
			return getIndexPrimitiveArray(target, index);
		}
		if (target instanceof Map) {
			MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
			if (site.type().parameterCount() > 1) {
				test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
			}
			site.installGuardOrSwitchMegamorphic(test, MH_GET_INDEX_MAP.asType(site.type()));
			return getIndexMap(target, index);
		}
		return getIndex(target, index);
	}

	public static void setIndexDynamicFallback(ChainedCallSite site, Object target, Object index, Object value)
	 throws Throwable {
		if (target == null || target == JSUndefined.INSTANCE) return;
		if (target instanceof JSContext.JSGlobalThis globalThis) {
			globalThis.put(toPropertyKey(index), value);
			return;
		}
		if (target instanceof JSObject jsObj) {
			boolean isPrototype = jsObj.getProtoSwitchPoint() != null || jsObj == JSContext.LazyArray.ARRAY_PROTOTYPE;
			if (index instanceof String strKey) {
				JSShape s      = jsObj.shape;
				int     offset = s.getOffset(strKey);

				if (offset >= 0 && (!s.hasAccessors || !s.isAccessor(offset)) && s.isWritable(offset) && site.getChainDepth() < 3) {
					byte type            = s.getSlotType(offset);
					byte currentBaseType = (byte) (type & JSShape.TYPE_MASK);
					byte newBaseType     = (value instanceof Number) ? JSShape.TYPE_DOUBLE : JSShape.TYPE_OBJECT;
					if (currentBaseType == newBaseType) {
						boolean isDouble = currentBaseType == JSShape.TYPE_DOUBLE;
						if (!isPrototype) {
							MethodHandle test = LOOKUP.findStatic(
							 JSIndexOps.class,
							 "isExactShapeAndStringKey",
							 MethodType.methodType(boolean.class, JSShape.class, String.class, Object.class, Object.class)
							).bindTo(s).bindTo(strKey);
							test = MethodHandles.dropArguments(test, 2, Object.class);

							MethodHandle directSlotSetter;
							if (offset < 8) {
								directSlotSetter = isDouble ? MH_SET_SLOT_DOUBLE_AS_OBJ[offset] : MH_SET_SLOT_OBJECT[offset];
							} else {
								directSlotSetter = isDouble
								 ? MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT_DOUBLE_AS_OBJ, 0, offset)
								 : MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT, 0, offset);
							}
							MethodHandle directTarget = MethodHandles.dropArguments(directSlotSetter, 1, Object.class);
							site.installGuardOrSwitchMegamorphic(test, directTarget.asType(site.type()));
						}
						if (isDouble) {
							jsObj.setDoubleSlot(offset, JSOps.toDouble(value));
						} else {
							jsObj.setSlot(offset, value);
						}
						jsObj.onStructuralOrPropertyChange();
						return;
					}
				}
			} else if (index instanceof JSSymbol symKey) {
				JSShape s      = jsObj.shape;
				int     offset = s.getOffset(symKey.getSymbolId());

				if (offset >= 0 && (!s.hasAccessors || !s.isAccessor(offset)) && s.isWritable(offset) && site.getChainDepth() < 3) {
					byte type            = s.getSlotType(offset);
					byte currentBaseType = (byte) (type & JSShape.TYPE_MASK);
					byte newBaseType     = (value instanceof Number) ? JSShape.TYPE_DOUBLE : JSShape.TYPE_OBJECT;
					if (currentBaseType == newBaseType) {
						boolean isDouble = currentBaseType == JSShape.TYPE_DOUBLE;
						if (!isPrototype) {
							MethodHandle test = LOOKUP.findStatic(
							 JSIndexOps.class,
							 "isExactShapeAndSymbol",
							 MethodType.methodType(boolean.class, JSShape.class, JSSymbol.class, Object.class, Object.class)
							).bindTo(s).bindTo(symKey);
							test = MethodHandles.dropArguments(test, 2, Object.class);

							MethodHandle directSlotSetter;
							if (offset < 8) {
								directSlotSetter = isDouble ? MH_SET_SLOT_DOUBLE_AS_OBJ[offset] : MH_SET_SLOT_OBJECT[offset];
							} else {
								directSlotSetter = isDouble
								 ? MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT_DOUBLE_AS_OBJ, 0, offset)
								 : MethodHandles.insertArguments(MH_SET_JS_OBJ_SLOT, 0, offset);
							}
							MethodHandle directTarget = MethodHandles.dropArguments(directSlotSetter, 1, Object.class);
							site.installGuardOrSwitchMegamorphic(test, directTarget.asType(site.type()));
						}
						if (isDouble) {
							jsObj.setDoubleSlot(offset, JSOps.toDouble(value));
						} else {
							jsObj.setSlot(offset, value);
						}
						jsObj.onStructuralOrPropertyChange();
						return;
					}
				}
			}
		}
		if (target instanceof JSArray) {
			MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
			if (site.type().parameterCount() > 1) {
				test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
			}
			site.installGuardOrSwitchMegamorphic(test, MH_SET_INDEX_JS_ARRAY.asType(site.type()));
			setIndexJSArray(target, index, value);
			return;
		}
		if (target instanceof List) {
			MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
			if (site.type().parameterCount() > 1) {
				test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
			}
			site.installGuardOrSwitchMegamorphic(test, MH_SET_INDEX_LIST.asType(site.type()));
			setIndexList(target, index, value);
			return;
		}
		if (target.getClass().isArray()) {
			MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
			if (site.type().parameterCount() > 1) {
				test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
			}
			MethodHandle directTarget;
			if (target instanceof Object[]) { directTarget = MH_SET_INDEX_OBJECT_ARRAY; } else if (target instanceof int[]) {
				directTarget = MH_SET_INDEX_INT_ARRAY;
			} else if (target instanceof double[]) {
				directTarget = MH_SET_INDEX_DOUBLE_ARRAY;
			} else if (target instanceof long[]) { directTarget = MH_SET_INDEX_LONG_ARRAY; } else {
				directTarget = MH_SET_INDEX_PRIMITIVE_ARRAY;
			}

			site.installGuardOrSwitchMegamorphic(test, directTarget.asType(site.type()));
			if (target instanceof Object[]) { setIndexObjectArray(target, index, value); } else if (target instanceof int[]) {
				setIndexIntArray(target, index, value);
			} else if (target instanceof double[]) {
				setIndexDoubleArray(target, index, value);
			} else if (target instanceof long[]) { setIndexLongArray(target, index, value); } else {
				setIndexPrimitiveArray(target, index, value);
			}
			return;
		}
		if (target instanceof Map) {
			MethodHandle test = MH_IS_EXACT_CLASS.bindTo(target.getClass());
			if (site.type().parameterCount() > 1) {
				test = MethodHandles.dropArguments(test, 1, site.type().parameterList().subList(1, site.type().parameterCount()));
			}
			site.installGuardOrSwitchMegamorphic(test, MH_SET_INDEX_MAP.asType(site.type()));
			setIndexMap(target, index, value);
			return;
		}
		setIndex(target, index, value);
	}
}
