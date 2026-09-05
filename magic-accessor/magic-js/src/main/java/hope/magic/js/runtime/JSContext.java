package hope.magic.js.runtime;

import hope.magic.js.ast.*;
import hope.magic.js.compiler.JSCompiler;
import hope.magic.js.parser.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class JSContext {
	private static final ConcurrentHashMap<String, Integer> GLOBAL_SLOT_REGISTRY = new ConcurrentHashMap<>();
	private static final AtomicInteger                      NEXT_GLOBAL_SLOT     = new AtomicInteger(0);

	public static int getGlobalSlot(String name) {
		return GLOBAL_SLOT_REGISTRY.computeIfAbsent(name, k -> NEXT_GLOBAL_SLOT.getAndIncrement());
	}

	private static final Object NULL_VALUE = new Object();

	public static final int                               INITIAL_GLOBAL_SLOTS_CAPACITY = 64;
	public volatile     Object[]                          globalSlots                   = new Object[INITIAL_GLOBAL_SLOTS_CAPACITY];
	private final       ConcurrentHashMap<String, Object> globals                       = new ConcurrentHashMap<>();

	public static class JSMathFunction implements JSFunction {
		public static final int OP_ABS    = 0;
		public static final int OP_SQRT   = 1;
		public static final int OP_FLOOR  = 2;
		public static final int OP_CEIL   = 3;
		public static final int OP_ROUND  = 4;
		public static final int OP_SIN    = 5;
		public static final int OP_COS    = 6;
		public static final int OP_TAN    = 7;
		public static final int OP_ASIN   = 8;
		public static final int OP_ACOS   = 9;
		public static final int OP_ATAN   = 10;
		public static final int OP_EXP    = 11;
		public static final int OP_LOG    = 12;
		public static final int OP_LOG10  = 13;
		public static final int OP_LOG2   = 14;
		public static final int OP_CBRT   = 15;
		public static final int OP_SIGN   = 16;
		public static final int OP_TRUNC  = 17;
		public static final int OP_RANDOM = 18;
		public static final int OP_MAX    = 19;
		public static final int OP_MIN    = 20;
		public static final int OP_POW    = 21;
		public static final int OP_ATAN2  = 22;
		public static final int OP_HYPOT  = 23;

		private static final double LN2 = 0.6931471805599453; // Math.log(2)

		private final int op;
		public JSMathFunction(int op) { this.op = op; }

		@Override
		public Object call(JSContext cx, Object thisObj, Object[] args) {
			double a0 = args.length > 0 ? JSOps.toDouble(args[0]) : Double.NaN;
			double a1 = args.length > 1 ? JSOps.toDouble(args[1]) : Double.NaN;
			return eval(a0, a1);
		}

		@Override
		public Object call0(JSContext cx, Object thisObj) {
			return op == OP_RANDOM ? Math.random() : Double.NaN;
		}

		@Override
		public Object call1(JSContext cx, Object thisObj, Object a0) {
			return eval(JSOps.toDouble(a0), Double.NaN);
		}

		@Override
		public Object call2(JSContext cx, Object thisObj, Object a0, Object a1) {
			return eval(JSOps.toDouble(a0), JSOps.toDouble(a1));
		}

		private Object eval(double a0, double a1) {
			return switch (op) {
				case OP_ABS -> Math.abs(a0);
				case OP_SQRT -> Math.sqrt(a0);
				case OP_FLOOR -> Math.floor(a0);
				case OP_CEIL -> Math.ceil(a0);
				case OP_ROUND -> (double) Math.round(a0);
				case OP_SIN -> Math.sin(a0);
				case OP_COS -> Math.cos(a0);
				case OP_TAN -> Math.tan(a0);
				case OP_ASIN -> Math.asin(a0);
				case OP_ACOS -> Math.acos(a0);
				case OP_ATAN -> Math.atan(a0);
				case OP_EXP -> Math.exp(a0);
				case OP_LOG -> Math.log(a0);
				case OP_LOG10 -> Math.log10(a0);
				case OP_LOG2 -> Math.log(a0) / LN2;
				case OP_CBRT -> Math.cbrt(a0);
				case OP_SIGN -> Math.signum(a0);
				case OP_TRUNC -> a0 < 0 ? Math.ceil(a0) : Math.floor(a0);
				case OP_RANDOM -> Math.random();
				case OP_MAX -> Math.max(a0, a1);
				case OP_MIN -> Math.min(a0, a1);
				case OP_POW -> Math.pow(a0, a1);
				case OP_ATAN2 -> Math.atan2(a0, a1);
				case OP_HYPOT -> Math.hypot(a0, a1);
				default -> Double.NaN;
			};
		}
	}

	public static class JSObjectIsFunction extends JSObject implements JSFunction {
		public static final JSObjectIsFunction INSTANCE = new JSObjectIsFunction();

		public JSObjectIsFunction() {
			put("name", "is");
			put("length", 2);
		}

		@Override
		public Object call(JSContext cx, Object thisObj, Object[] args) {
			Object a0 = args.length > 0 ? args[0] : JSUndefined.INSTANCE;
			Object a1 = args.length > 1 ? args[1] : JSUndefined.INSTANCE;
			return JSOps.sameValue(a0, a1);
		}

		@Override
		public Object call0(JSContext cx, Object thisObj) {
			return Boolean.TRUE;
		}

		@Override
		public Object call1(JSContext cx, Object thisObj, Object a0) {
			return JSOps.sameValue(a0, JSUndefined.INSTANCE);
		}

		@Override
		public Object call2(JSContext cx, Object thisObj, Object a0, Object a1) {
			return JSOps.sameValue(a0, a1);
		}

		@Override
		public String toString() {
			return "function is() { [native code] }";
		}
	}

	public static class JSObjectConstructor extends JSObject implements JSFunction {
		public JSObjectConstructor(JSObject prototype) {
			super(prototype);
		}

		public JSObjectConstructor(JSShape shape, JSObject prototype) {
			super(shape, prototype);
		}

		@Override
		public Object call(JSContext cx, Object thisObj, Object[] args) {
			if (args.length > 0 && args[0] != null && args[0] != JSUndefined.INSTANCE) {
				if (args[0] instanceof JSObject) {
					return args[0];
				}
				return args[0];
			}
			return new JSObject(LazyObject.OBJECT_PROTOTYPE);
		}

		@Override
		public Object call0(JSContext cx, Object thisObj) {
			return new JSObject(LazyObject.OBJECT_PROTOTYPE);
		}

		@Override
		public Object call1(JSContext cx, Object thisObj, Object a0) {
			if (a0 != null && a0 != JSUndefined.INSTANCE) {
				return a0;
			}
			return new JSObject(LazyObject.OBJECT_PROTOTYPE);
		}

		@Override
		public String toString() {
			return "function Object() { [native code] }";
		}
	}

	public static class JSArrayConstructor extends JSObject implements JSFunction {
		public JSArrayConstructor(JSObject prototype) {
			super(prototype);
		}

		public JSArrayConstructor(JSShape shape, JSObject prototype) {
			super(shape, prototype);
		}

		@Override
		public Object call(JSContext cx, Object thisObj, Object[] args) {
			if (args.length == 1 && args[0] instanceof Number num) {
				return createSizedArray(num.doubleValue());
			}
			JSArray arr = new JSArray();
			for (Object arg : args) {
				arr.push(arg);
			}
			return arr;
		}

		@Override
		public Object call0(JSContext cx, Object thisObj) {
			return new JSArray();
		}

		@Override
		public Object call1(JSContext cx, Object thisObj, Object a0) {
			if (a0 instanceof Number num) {
				return createSizedArray(num.doubleValue());
			}
			JSArray arr = new JSArray();
			arr.push(a0);
			return arr;
		}

		@Override
		public Object call2(JSContext cx, Object thisObj, Object a0, Object a1) {
			JSArray arr = new JSArray();
			arr.push(a0);
			arr.push(a1);
			return arr;
		}

		private static JSArray createSizedArray(double len) {
			if (!Double.isFinite(len) || len < 0 || len > 4294967295L || len != Math.floor(len)) {
				throw new RuntimeException("RangeError: Invalid array length");
			}
			JSArray arr = new JSArray((int) Math.min(len, 65536));
			arr.setLength(len);
			return arr;
		}

		@Override
		public String toString() {
			return "function Array() { [native code] }";
		}
	}

	public static final int SLOT_NAN          = getGlobalSlot("NaN");
	public static final int SLOT_INFINITY     = getGlobalSlot("Infinity");
	public static final int SLOT_UNDEFINED    = getGlobalSlot("undefined");
	public static final int SLOT_JSOPS        = getGlobalSlot("JSOps");
	public static final int SLOT_PRINT        = getGlobalSlot("print");
	public static final int SLOT_CONSOLE      = getGlobalSlot("console");
	public static final int SLOT_MATH         = getGlobalSlot("Math");
	public static final int SLOT_IMPORT_CLASS = getGlobalSlot("importClass");
	public static final int SLOT_PACKAGES     = getGlobalSlot("Packages");
	public static final int SLOT_REGEXP       = getGlobalSlot("RegExp");
	public static final int SLOT_OBJECT       = getGlobalSlot("Object");
	public static final int SLOT_ARRAY        = getGlobalSlot("Array");

	public static class JSBuiltinMethod extends JSObject implements JSFunction {
		private static final List<String> BUILTIN_METHOD_PROPS = List.of("name", "length");
		private static final JSShape METHOD_SHAPE = JSShape.createStaticPrototypeShape(BUILTIN_METHOD_PROPS);

		private final JSFunction fn;

		public JSBuiltinMethod(String name, int length, JSFunction fn) {
			super(METHOD_SHAPE, null);
			this.obj0 = name;
			this.prim1 = Double.doubleToRawLongBits((double) length);
			this.doubleFieldMask = (1L << 1);
			this.fn = fn;
		}

		@Override
		public Object call(JSContext cx, Object thisObj, Object[] args) throws Throwable {
			return fn.call(cx, thisObj, args);
		}

		@Override
		public Object call0(JSContext cx, Object thisObj) throws Throwable {
			return fn.call0(cx, thisObj);
		}

		@Override
		public Object call1(JSContext cx, Object thisObj, Object a0) throws Throwable {
			return fn.call1(cx, thisObj, a0);
		}

		@Override
		public Object call2(JSContext cx, Object thisObj, Object a0, Object a1) throws Throwable {
			return fn.call2(cx, thisObj, a0, a1);
		}

		@Override
		public Object call3(JSContext cx, Object thisObj, Object a0, Object a1, Object a2) throws Throwable {
			return fn.call3(cx, thisObj, a0, a1, a2);
		}

		@Override
		public Object call4(JSContext cx, Object thisObj, Object a0, Object a1, Object a2, Object a3) throws Throwable {
			return fn.call4(cx, thisObj, a0, a1, a2, a3);
		}

		@Override
		public double call0Double(JSContext cx) throws Throwable {
			return fn.call0Double(cx);
		}

		@Override
		public double call1Double(JSContext cx, double a0) throws Throwable {
			return fn.call1Double(cx, a0);
		}

		@Override
		public double call2Double(JSContext cx, double a0, double a1) throws Throwable {
			return fn.call2Double(cx, a0, a1);
		}

		@Override
		public double call3Double(JSContext cx, double a0, double a1, double a2) throws Throwable {
			return fn.call3Double(cx, a0, a1, a2);
		}

		@Override
		public double call4Double(JSContext cx, double a0, double a1, double a2, double a3) throws Throwable {
			return fn.call4Double(cx, a0, a1, a2, a3);
		}

		@Override
		public String toString() {
			return "function " + obj0 + "() { [native code] }";
		}
	}

	static class LazyMisc {
		static final JSFunction PRINT = (cx, thisObj, args) -> {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < args.length; i++) {
				if (i > 0) sb.append(" ");
				sb.append(args[i]);
			}
			System.out.println(sb);
			return JSUndefined.INSTANCE;
		};

		static final JSFunction IMPORT_CLASS = (cx, thisObj, args) -> {
			if (args.length > 0) {
				if (args[0] instanceof Class<?>) {
					Class<?> c = (Class<?>) args[0];
					cx.set(c.getSimpleName(), c);
				} else if (args[0] instanceof String) {
					try {
						Class<?> c = Class.forName((String) args[0]);
						cx.set(c.getSimpleName(), c);
					} catch (ClassNotFoundException e) {
						throw new RuntimeException(e);
					}
				}
			}
			return JSUndefined.INSTANCE;
		};

		static final JSObject PACKAGES = new JSObject() {
			@Override
			public Object get(String key) {
				try {
					return Class.forName(key);
				} catch (ClassNotFoundException e) {
					return super.get(key);
				}
			}
		};

		static final JSFunction REGEXP = (cx, thisObj, args) -> {
			if (args.length == 0) return new JSRegExp("", "");
			if (args[0] instanceof JSRegExp oldReg) {
				String flags = args.length > 1 && args[1] != null && args[1] != JSUndefined.INSTANCE ? JSOps.toStr(args[1]) : oldReg.getFlags();
				return new JSRegExp(oldReg.getPattern(), flags);
			}
			String pat   = JSOps.toStr(args[0]);
			String flags = args.length > 1 && args[1] != null && args[1] != JSUndefined.INSTANCE ? JSOps.toStr(args[1]) : "";
			return new JSRegExp(pat, flags);
		};
	}

	static class LazyConsole {
		static final JSObject CONSOLE = createConsole();
		private static JSObject createConsole() {
			JSShape shape = JSShape.createStaticPrototypeShape(List.of("log"));
			JSObject c = new JSObject(shape, null);
			c.put("log", LazyMisc.PRINT);
			return c;
		}
	}

	static class LazyMath {
		private static final List<String> MATH_PROPS = List.of(
			"PI", "E", "abs", "sqrt", "floor", "ceil", "round",
			"sin", "cos", "tan", "asin", "acos", "atan", "exp",
			"log", "log10", "log2", "cbrt", "sign", "trunc",
			"random", "max", "min", "pow", "atan2", "hypot"
		);
		private static final JSShape MATH_SHAPE = JSShape.createStaticPrototypeShape(MATH_PROPS);
		static final JSObject MATH = createMath();

		private static JSObject createMath() {
			JSObject math = new JSObject(MATH_SHAPE, null);
			math.put("PI", Math.PI);
			math.put("E", Math.E);
			math.put("abs", new JSMathFunction(JSMathFunction.OP_ABS));
			math.put("sqrt", new JSMathFunction(JSMathFunction.OP_SQRT));
			math.put("floor", new JSMathFunction(JSMathFunction.OP_FLOOR));
			math.put("ceil", new JSMathFunction(JSMathFunction.OP_CEIL));
			math.put("round", new JSMathFunction(JSMathFunction.OP_ROUND));
			math.put("sin", new JSMathFunction(JSMathFunction.OP_SIN));
			math.put("cos", new JSMathFunction(JSMathFunction.OP_COS));
			math.put("tan", new JSMathFunction(JSMathFunction.OP_TAN));
			math.put("asin", new JSMathFunction(JSMathFunction.OP_ASIN));
			math.put("acos", new JSMathFunction(JSMathFunction.OP_ACOS));
			math.put("atan", new JSMathFunction(JSMathFunction.OP_ATAN));
			math.put("exp", new JSMathFunction(JSMathFunction.OP_EXP));
			math.put("log", new JSMathFunction(JSMathFunction.OP_LOG));
			math.put("log10", new JSMathFunction(JSMathFunction.OP_LOG10));
			math.put("log2", new JSMathFunction(JSMathFunction.OP_LOG2));
			math.put("cbrt", new JSMathFunction(JSMathFunction.OP_CBRT));
			math.put("sign", new JSMathFunction(JSMathFunction.OP_SIGN));
			math.put("trunc", new JSMathFunction(JSMathFunction.OP_TRUNC));
			math.put("random", new JSMathFunction(JSMathFunction.OP_RANDOM));
			math.put("max", new JSMathFunction(JSMathFunction.OP_MAX));
			math.put("min", new JSMathFunction(JSMathFunction.OP_MIN));
			math.put("pow", new JSMathFunction(JSMathFunction.OP_POW));
			math.put("atan2", new JSMathFunction(JSMathFunction.OP_ATAN2));
			math.put("hypot", new JSMathFunction(JSMathFunction.OP_HYPOT));
			return math;
		}
	}

	static class LazyObject {
		private static final List<String> OBJECT_PROTO_PROPS = List.of(
			"hasOwnProperty", "toString", "valueOf", "constructor"
		);
		private static final List<String> OBJECT_CTOR_PROPS = List.of(
			"name", "length", "prototype", "is", "getPrototypeOf", "getOwnPropertyNames"
		);

		static final JSObject            OBJECT_PROTOTYPE = createObjectPrototype();
		static final JSObjectConstructor OBJECT           = createObjectConstructor(OBJECT_PROTOTYPE);

		private static JSObject createObjectPrototype() {
			// 原型链顶端：Object.prototype 原型严格为 null，采用批量烘焙终态 Shape
			JSShape shape = JSShape.createStaticPrototypeShape(OBJECT_PROTO_PROPS);
			JSObject proto = new JSObject(shape, null);
			proto.put("hasOwnProperty", (JSFunction) (cx, thisObj, args) -> {
				if (args.length == 0) return Boolean.FALSE;
				String key = JSOps.toStr(args[0]);
				if (thisObj instanceof JSObject jsObj) {
					return jsObj.hasOwnProperty(key);
				}
				return Boolean.FALSE;
			});
			proto.put("toString", (JSFunction) (cx, thisObj, args) -> "[object Object]");
			proto.put("valueOf", (JSFunction) (cx, thisObj, args) -> thisObj);
			return proto;
		}

		private static JSObjectConstructor createObjectConstructor(JSObject proto) {
			JSShape shape = JSShape.createStaticPrototypeShape(proto.shape, OBJECT_CTOR_PROPS);
			JSObjectConstructor ctor = new JSObjectConstructor(shape, proto);
			proto.put("constructor", ctor);

			// 固有属性 (Own Properties)
			ctor.put("name", "Object");
			ctor.put("length", 1);
			ctor.put("prototype", proto);
			ctor.put("is", JSObjectIsFunction.INSTANCE);

			// 元编程静态方法
			ctor.put("getPrototypeOf", (JSFunction) (cx, thisObj, args) -> {
				if (args.length == 0 || args[0] == null || args[0] == JSUndefined.INSTANCE) {
					throw new RuntimeException("TypeError: Cannot convert undefined or null to object");
				}
				if (args[0] instanceof JSObject jsObj) {
					return jsObj.getPrototype();
				}
				return null;
			});

			ctor.put("getOwnPropertyNames", (JSFunction) (cx, thisObj, args) -> {
				if (args.length == 0 || args[0] == null || args[0] == JSUndefined.INSTANCE) {
					throw new RuntimeException("TypeError: Cannot convert undefined or null to object");
				}
				if (args[0] instanceof JSObject jsObj) {
					JSArray arr = new JSArray();
					for (String k : jsObj.keys()) {
						arr.push(k);
					}
					return arr;
				}
				return new JSArray();
			});

			return ctor;
		}
	}

	static class LazyArray {
		private static final List<String> ARRAY_PROTO_PROPS = List.of(
			"constructor", "length", "reduce", "reduceRight", "filter", "sort",
			"map", "forEach", "find", "findIndex", "some", "every",
			"includes", "indexOf", "lastIndexOf", "slice", "splice",
			"concat", "push", "pop", "shift", "unshift", "reverse",
			"fill", "flat", "toString", "join"
		);
		private static final List<String> ARRAY_CTOR_PROPS = List.of(
			"name", "length", "prototype", "isArray", "of", "from"
		);

		static final JSObject            ARRAY_PROTOTYPE = createArrayPrototype(LazyObject.OBJECT_PROTOTYPE);
		static final JSArrayConstructor  ARRAY           = createArrayConstructor(ARRAY_PROTOTYPE);

		private static JSObject createArrayPrototype(JSObject objectProto) {
			JSShape shape = JSShape.createStaticPrototypeShape(objectProto.shape, ARRAY_PROTO_PROPS);
			return new JSObject(shape, objectProto);
		}

		private static JSArrayConstructor createArrayConstructor(JSObject proto) {
			JSShape shape = JSShape.createStaticPrototypeShape(LazyObject.OBJECT_PROTOTYPE.shape, ARRAY_CTOR_PROPS);
			JSArrayConstructor ctor = new JSArrayConstructor(shape, LazyObject.OBJECT_PROTOTYPE);
			proto.put("constructor", ctor);
			proto.put("length", 0.0);

			ctor.put("name", "Array");
			ctor.put("length", 1);
			ctor.put("prototype", proto);

			ctor.put("isArray", makeMethod("isArray", 1, (cx, thisObj, args) -> {
				if (args.length == 0) return Boolean.FALSE;
				return args[0] instanceof JSArray ? Boolean.TRUE : Boolean.FALSE;
			}));

			ctor.put("of", makeMethod("of", 0, (cx, thisObj, args) -> {
				JSArray arr = new JSArray();
				for (Object a : args) arr.push(a);
				return arr;
			}));

			ctor.put("from", makeMethod("from", 1, (cx, thisObj, args) -> {
				if (args.length == 0 || args[0] == null || args[0] == JSUndefined.INSTANCE) {
					throw new RuntimeException("TypeError: Cannot convert undefined or null to object");
				}
				Object items = args[0];
				JSFunction mapFn = args.length > 1 && args[1] instanceof JSFunction ? (JSFunction) args[1] : null;
				Object thisArg = args.length > 2 ? args[2] : JSUndefined.INSTANCE;

				JSArray res = new JSArray();
				if (items instanceof Iterable<?> it) {
					long idx = 0;
					for (Object item : it) {
						if (mapFn != null) {
							try {
								res.push(mapFn.call2(cx, thisArg, item, (double) idx++));
							} catch (Throwable t) {
								if (t instanceof RuntimeException re) throw re;
								throw new RuntimeException(t);
							}
						} else {
							res.push(item);
						}
					}
					return res;
				}
				long len = toLength(items);
				for (long k = 0; k < len; k++) {
					Object val = getProperty(items, k);
					if (mapFn != null) {
						try {
							res.push(mapFn.call2(cx, thisArg, val, (double) k));
						} catch (Throwable t) {
							if (t instanceof RuntimeException re) throw re;
							throw new RuntimeException(t);
						}
					} else {
						res.push(val);
					}
				}
				return res;
			}));

			mountArrayPrototypeMethods(proto);
			return ctor;
		}

		private static void mountArrayPrototypeMethods(JSObject proto) {
			proto.put("reduce", makeMethod("reduce", 1, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				if (args.length == 0 || !(args[0] instanceof JSFunction callback)) {
					throw new RuntimeException("TypeError: " + (args.length > 0 ? args[0] : "undefined") + " is not a function");
				}
				if (O instanceof JSArray jsArr && jsArr.isDense()) {
					return fastDenseReduce(cx, jsArr, callback, args);
				}
				return genericReduce(cx, O, callback, args);
			}));

			proto.put("reduceRight", makeMethod("reduceRight", 1, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				if (args.length == 0 || !(args[0] instanceof JSFunction callback)) {
					throw new RuntimeException("TypeError: " + (args.length > 0 ? args[0] : "undefined") + " is not a function");
				}
				long len = toLength(O);
				long k = len - 1;
				Object accumulator = null;
				boolean hasAccumulator = false;
				if (args.length > 1) {
					accumulator = args[1];
					hasAccumulator = true;
				}
				if (!hasAccumulator) {
					while (k >= 0) {
						if (hasProperty(O, k)) {
							accumulator = getProperty(O, k);
							hasAccumulator = true;
							k--;
							break;
						}
						k--;
					}
					if (!hasAccumulator) {
						throw new RuntimeException("TypeError: Reduce of empty array with no initial value");
					}
				}
				while (k >= 0) {
					if (hasProperty(O, k)) {
						Object kValue = getProperty(O, k);
						try {
							accumulator = callback.call4(cx, JSUndefined.INSTANCE, accumulator, kValue, (double) k, O);
						} catch (Throwable t) {
							if (t instanceof RuntimeException re) throw re;
							throw new RuntimeException(t);
						}
					}
					k--;
				}
				return accumulator;
			}));

			proto.put("filter", makeMethod("filter", 1, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				if (args.length == 0 || !(args[0] instanceof JSFunction callback)) {
					throw new RuntimeException("TypeError: " + (args.length > 0 ? args[0] : "undefined") + " is not a function");
				}
				Object thisArg = args.length > 1 ? args[1] : JSUndefined.INSTANCE;
				if (O instanceof JSArray jsArr && jsArr.isDense()) {
					return fastDenseFilter(cx, jsArr, callback, thisArg);
				}
				return genericFilter(cx, O, callback, thisArg);
			}));

			proto.put("sort", makeMethod("sort", 1, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				JSFunction compareFn = null;
				if (args.length > 0 && args[0] != null && args[0] != JSUndefined.INSTANCE) {
					if (!(args[0] instanceof JSFunction)) {
						throw new RuntimeException("TypeError: The comparison function must be either a function or undefined");
					}
					compareFn = (JSFunction) args[0];
				}
				long len = toLength(O);
				if (len <= 1) return O;

				List<Object> definedItems = new ArrayList<>();
				int undefinedCount = 0;
				for (long k = 0; k < len; k++) {
					if (hasProperty(O, k)) {
						Object val = getProperty(O, k);
						if (val == JSUndefined.INSTANCE) {
							undefinedCount++;
						} else {
							definedItems.add(val);
						}
					}
				}

				final JSFunction cmp = compareFn;
				if (cmp != null) {
					definedItems.sort((a, b) -> {
						try {
							Object res = cmp.call2(cx, JSUndefined.INSTANCE, a, b);
							double d = JSOps.toDouble(res);
							if (Double.isNaN(d)) return 0;
							return Double.compare(d, 0.0);
						} catch (Throwable t) {
							if (t instanceof RuntimeException re) throw re;
							throw new RuntimeException(t);
						}
					});
				} else {
					definedItems.sort((a, b) -> JSOps.toStr(a).compareTo(JSOps.toStr(b)));
				}

				long idx = 0;
				for (Object item : definedItems) {
					setProperty(O, idx++, item);
				}
				for (int i = 0; i < undefinedCount; i++) {
					setProperty(O, idx++, JSUndefined.INSTANCE);
				}
				for (; idx < len; idx++) {
					deleteProperty(O, idx);
				}
				return O;
			}));

			proto.put("map", makeMethod("map", 1, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				if (args.length == 0 || !(args[0] instanceof JSFunction callback)) {
					throw new RuntimeException("TypeError: " + (args.length > 0 ? args[0] : "undefined") + " is not a function");
				}
				Object thisArg = args.length > 1 ? args[1] : JSUndefined.INSTANCE;
				long len = toLength(O);
				JSArray result = new JSArray((int) Math.min(len, 65536));
				result.setLength((double) len);
				for (long k = 0; k < len; k++) {
					if (hasProperty(O, k)) {
						Object kValue = getProperty(O, k);
						try {
							Object mapped = callback.call3(cx, thisArg, kValue, (double) k, O);
							result.setElement(k, mapped);
						} catch (Throwable t) {
							if (t instanceof RuntimeException re) throw re;
							throw new RuntimeException(t);
						}
					}
				}
				return result;
			}));

			proto.put("forEach", makeMethod("forEach", 1, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				if (args.length == 0 || !(args[0] instanceof JSFunction callback)) {
					throw new RuntimeException("TypeError: " + (args.length > 0 ? args[0] : "undefined") + " is not a function");
				}
				Object thisArg = args.length > 1 ? args[1] : JSUndefined.INSTANCE;
				long len = toLength(O);
				for (long k = 0; k < len; k++) {
					if (hasProperty(O, k)) {
						Object kValue = getProperty(O, k);
						try {
							callback.call3(cx, thisArg, kValue, (double) k, O);
						} catch (Throwable t) {
							if (t instanceof RuntimeException re) throw re;
							throw new RuntimeException(t);
						}
					}
				}
				return JSUndefined.INSTANCE;
			}));

			proto.put("find", makeMethod("find", 1, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				if (args.length == 0 || !(args[0] instanceof JSFunction callback)) {
					throw new RuntimeException("TypeError: " + (args.length > 0 ? args[0] : "undefined") + " is not a function");
				}
				Object thisArg = args.length > 1 ? args[1] : JSUndefined.INSTANCE;
				long len = toLength(O);
				for (long k = 0; k < len; k++) {
					if (hasProperty(O, k)) {
						Object kValue = getProperty(O, k);
						try {
							if (JSOps.toBoolean(callback.call3(cx, thisArg, kValue, (double) k, O))) {
								return kValue;
							}
						} catch (Throwable t) {
							if (t instanceof RuntimeException re) throw re;
							throw new RuntimeException(t);
						}
					}
				}
				return JSUndefined.INSTANCE;
			}));

			proto.put("findIndex", makeMethod("findIndex", 1, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				if (args.length == 0 || !(args[0] instanceof JSFunction callback)) {
					throw new RuntimeException("TypeError: " + (args.length > 0 ? args[0] : "undefined") + " is not a function");
				}
				Object thisArg = args.length > 1 ? args[1] : JSUndefined.INSTANCE;
				long len = toLength(O);
				for (long k = 0; k < len; k++) {
					if (hasProperty(O, k)) {
						Object kValue = getProperty(O, k);
						try {
							if (JSOps.toBoolean(callback.call3(cx, thisArg, kValue, (double) k, O))) {
								return (double) k;
							}
						} catch (Throwable t) {
							if (t instanceof RuntimeException re) throw re;
							throw new RuntimeException(t);
						}
					}
				}
				return -1.0;
			}));

			proto.put("some", makeMethod("some", 1, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				if (args.length == 0 || !(args[0] instanceof JSFunction callback)) {
					throw new RuntimeException("TypeError: " + (args.length > 0 ? args[0] : "undefined") + " is not a function");
				}
				Object thisArg = args.length > 1 ? args[1] : JSUndefined.INSTANCE;
				long len = toLength(O);
				for (long k = 0; k < len; k++) {
					if (hasProperty(O, k)) {
						Object kValue = getProperty(O, k);
						try {
							if (JSOps.toBoolean(callback.call3(cx, thisArg, kValue, (double) k, O))) {
								return Boolean.TRUE;
							}
						} catch (Throwable t) {
							if (t instanceof RuntimeException re) throw re;
							throw new RuntimeException(t);
						}
					}
				}
				return Boolean.FALSE;
			}));

			proto.put("every", makeMethod("every", 1, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				if (args.length == 0 || !(args[0] instanceof JSFunction callback)) {
					throw new RuntimeException("TypeError: " + (args.length > 0 ? args[0] : "undefined") + " is not a function");
				}
				Object thisArg = args.length > 1 ? args[1] : JSUndefined.INSTANCE;
				long len = toLength(O);
				for (long k = 0; k < len; k++) {
					if (hasProperty(O, k)) {
						Object kValue = getProperty(O, k);
						try {
							if (!JSOps.toBoolean(callback.call3(cx, thisArg, kValue, (double) k, O))) {
								return Boolean.FALSE;
							}
						} catch (Throwable t) {
							if (t instanceof RuntimeException re) throw re;
							throw new RuntimeException(t);
						}
					}
				}
				return Boolean.TRUE;
			}));

			proto.put("indexOf", makeMethod("indexOf", 1, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				long len = toLength(O);
				if (len == 0 || args.length == 0) return -1.0;
				Object searchElement = args[0];
				long fromIndex = 0;
				if (args.length > 1) {
					double from = JSOps.toDouble(args[1]);
					if (Double.isNaN(from)) from = 0;
					if (from < 0) from = Math.max(0, len + (long) from);
					fromIndex = (long) from;
				}
				for (long k = fromIndex; k < len; k++) {
					if (hasProperty(O, k)) {
						Object val = getProperty(O, k);
						if (JSOps.isStrictEq(val, searchElement)) {
							return (double) k;
						}
					}
				}
				return -1.0;
			}));

			proto.put("lastIndexOf", makeMethod("lastIndexOf", 1, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				long len = toLength(O);
				if (len == 0 || args.length == 0) return -1.0;
				Object searchElement = args[0];
				long fromIndex = len - 1;
				if (args.length > 1) {
					double from = JSOps.toDouble(args[1]);
					if (Double.isNaN(from)) from = len - 1;
					if (from < 0) from = len + from;
					fromIndex = Math.min((long) from, len - 1);
				}
				for (long k = fromIndex; k >= 0; k--) {
					if (hasProperty(O, k)) {
						Object val = getProperty(O, k);
						if (JSOps.isStrictEq(val, searchElement)) {
							return (double) k;
						}
					}
				}
				return -1.0;
			}));

			proto.put("includes", makeMethod("includes", 1, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				long len = toLength(O);
				if (len == 0 || args.length == 0) return Boolean.FALSE;
				Object searchElement = args[0];
				long fromIndex = 0;
				if (args.length > 1) {
					double from = JSOps.toDouble(args[1]);
					if (Double.isNaN(from)) from = 0;
					if (from < 0) from = Math.max(0, len + (long) from);
					fromIndex = (long) from;
				}
				for (long k = fromIndex; k < len; k++) {
					Object val = getProperty(O, k);
					if (val == searchElement) return Boolean.TRUE;
					if (val instanceof Number n1 && searchElement instanceof Number n2) {
						double d1 = n1.doubleValue();
						double d2 = n2.doubleValue();
						if (Double.isNaN(d1) && Double.isNaN(d2)) return Boolean.TRUE;
						if (d1 == d2) return Boolean.TRUE;
					} else if (Objects.equals(val, searchElement)) {
						return Boolean.TRUE;
					}
				}
				return Boolean.FALSE;
			}));

			proto.put("join", makeMethod("join", 1, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				long len = toLength(O);
				String sep = args.length > 0 && args[0] != JSUndefined.INSTANCE ? JSOps.toStr(args[0]) : ",";
				if (len == 0) return "";
				StringBuilder sb = new StringBuilder();
				for (long k = 0; k < len; k++) {
					if (k > 0) sb.append(sep);
					Object val = getProperty(O, k);
					if (val != null && val != JSUndefined.INSTANCE) {
						sb.append(JSOps.toStr(val));
					}
				}
				return sb.toString();
			}));

			proto.put("slice", makeMethod("slice", 2, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				long len = toLength(O);
				long start = 0;
				if (args.length > 0 && args[0] != JSUndefined.INSTANCE) {
					double d = JSOps.toDouble(args[0]);
					if (Double.isNaN(d)) d = 0;
					start = d < 0 ? Math.max(0, len + (long) d) : Math.min(len, (long) d);
				}
				long end = len;
				if (args.length > 1 && args[1] != JSUndefined.INSTANCE) {
					double d = JSOps.toDouble(args[1]);
					if (Double.isNaN(d)) d = 0;
					end = d < 0 ? Math.max(0, len + (long) d) : Math.min(len, (long) d);
				}
				JSArray result = new JSArray();
				for (long k = start; k < end; k++) {
					if (hasProperty(O, k)) {
						result.push(getProperty(O, k));
					} else {
						result.push(JSArray.HOLE);
					}
				}
				return result;
			}));

			proto.put("splice", makeMethod("splice", 2, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				long len = toLength(O);
				if (args.length == 0) return new JSArray();

				double startDouble = JSOps.toDouble(args[0]);
				if (Double.isNaN(startDouble)) startDouble = 0;
				long actualStart = startDouble < 0 ? Math.max(0, len + (long) startDouble) : Math.min(len, (long) startDouble);

				long actualDeleteCount;
				if (args.length == 1) {
					actualDeleteCount = len - actualStart;
				} else {
					double dcDouble = JSOps.toDouble(args[1]);
					if (Double.isNaN(dcDouble) || dcDouble < 0) dcDouble = 0;
					actualDeleteCount = Math.min((long) dcDouble, len - actualStart);
				}

				JSArray deleted = new JSArray();
				for (long k = 0; k < actualDeleteCount; k++) {
					long from = actualStart + k;
					if (hasProperty(O, from)) {
						deleted.push(getProperty(O, from));
					} else {
						deleted.push(JSArray.HOLE);
					}
				}

				int insertCount = Math.max(0, args.length - 2);
				long newLen = len - actualDeleteCount + insertCount;
				if (insertCount < actualDeleteCount) {
					for (long k = actualStart; k < len - actualDeleteCount; k++) {
						long from = k + actualDeleteCount;
						long to = k + insertCount;
						if (hasProperty(O, from)) {
							setProperty(O, to, getProperty(O, from));
						} else {
							deleteProperty(O, to);
						}
					}
					for (long k = len; k > newLen; k--) {
						deleteProperty(O, k - 1);
					}
				} else if (insertCount > actualDeleteCount) {
					for (long k = len - actualDeleteCount; k > actualStart; k--) {
						long from = k + actualDeleteCount - 1;
						long to = k + insertCount - 1;
						if (hasProperty(O, from)) {
							setProperty(O, to, getProperty(O, from));
						} else {
							deleteProperty(O, to);
						}
					}
				}

				for (int i = 0; i < insertCount; i++) {
					setProperty(O, actualStart + i, args[2 + i]);
				}

				if (O instanceof JSArray arr) {
					arr.setLength((double) newLen);
				} else if (O instanceof JSObject jsObj) {
					jsObj.put("length", (double) newLen);
				}
				return deleted;
			}));

			proto.put("concat", makeMethod("concat", 1, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				JSArray result = new JSArray();
				appendConcatItem(result, O);
				for (Object arg : args) {
					appendConcatItem(result, arg);
				}
				return result;
			}));

			proto.put("push", makeMethod("push", 1, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				if (O instanceof JSArray arr) {
					for (Object arg : args) arr.push(arg);
					return (double) arr.length();
				}
				long len = toLength(O);
				for (Object arg : args) {
					setProperty(O, len++, arg);
				}
				if (O instanceof JSObject jsObj) jsObj.put("length", (double) len);
				return (double) len;
			}));

			proto.put("pop", makeMethod("pop", 0, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				if (O instanceof JSArray arr) return arr.pop();
				long len = toLength(O);
				if (len == 0) {
					if (O instanceof JSObject jsObj) jsObj.put("length", 0.0);
					return JSUndefined.INSTANCE;
				}
				long newLen = len - 1;
				Object val = getProperty(O, newLen);
				deleteProperty(O, newLen);
				if (O instanceof JSObject jsObj) jsObj.put("length", (double) newLen);
				return val;
			}));

			proto.put("shift", makeMethod("shift", 0, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				long len = toLength(O);
				if (len == 0) {
					if (O instanceof JSArray arr) arr.setLength(0.0);
					else if (O instanceof JSObject jsObj) jsObj.put("length", 0.0);
					return JSUndefined.INSTANCE;
				}
				Object first = getProperty(O, 0);
				for (long k = 1; k < len; k++) {
					if (hasProperty(O, k)) {
						setProperty(O, k - 1, getProperty(O, k));
					} else {
						deleteProperty(O, k - 1);
					}
				}
				deleteProperty(O, len - 1);
				long newLen = len - 1;
				if (O instanceof JSArray arr) arr.setLength((double) newLen);
				else if (O instanceof JSObject jsObj) jsObj.put("length", (double) newLen);
				return first;
			}));

			proto.put("unshift", makeMethod("unshift", 1, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				long len = toLength(O);
				int argCount = args.length;
				if (argCount > 0) {
					for (long k = len; k > 0; k--) {
						long from = k - 1;
						long to = k + argCount - 1;
						if (hasProperty(O, from)) {
							setProperty(O, to, getProperty(O, from));
						} else {
							deleteProperty(O, to);
						}
					}
					for (int j = 0; j < argCount; j++) {
						setProperty(O, j, args[j]);
					}
				}
				long newLen = len + argCount;
				if (O instanceof JSArray arr) arr.setLength((double) newLen);
				else if (O instanceof JSObject jsObj) jsObj.put("length", (double) newLen);
				return (double) newLen;
			}));

			proto.put("reverse", makeMethod("reverse", 0, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				long len = toLength(O);
				long middle = len / 2;
				for (long lower = 0; lower < middle; lower++) {
					long upper = len - lower - 1;
					boolean lowerExists = hasProperty(O, lower);
					boolean upperExists = hasProperty(O, upper);
					Object lowerVal = lowerExists ? getProperty(O, lower) : null;
					Object upperVal = upperExists ? getProperty(O, upper) : null;
					if (lowerExists && upperExists) {
						setProperty(O, lower, upperVal);
						setProperty(O, upper, lowerVal);
					} else if (!lowerExists && upperExists) {
						setProperty(O, lower, upperVal);
						deleteProperty(O, upper);
					} else if (lowerExists && !upperExists) {
						deleteProperty(O, lower);
						setProperty(O, upper, lowerVal);
					}
				}
				return O;
			}));

			proto.put("fill", makeMethod("fill", 1, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				long len = toLength(O);
				Object value = args.length > 0 ? args[0] : JSUndefined.INSTANCE;
				long start = 0;
				if (args.length > 1 && args[1] != JSUndefined.INSTANCE) {
					double d = JSOps.toDouble(args[1]);
					if (Double.isNaN(d)) d = 0;
					start = d < 0 ? Math.max(0, len + (long) d) : Math.min(len, (long) d);
				}
				long end = len;
				if (args.length > 2 && args[2] != JSUndefined.INSTANCE) {
					double d = JSOps.toDouble(args[2]);
					if (Double.isNaN(d)) d = 0;
					end = d < 0 ? Math.max(0, len + (long) d) : Math.min(len, (long) d);
				}
				for (long k = start; k < end; k++) {
					setProperty(O, k, value);
				}
				return O;
			}));

			proto.put("flat", makeMethod("flat", 0, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				double depth = args.length > 0 && args[0] != JSUndefined.INSTANCE ? JSOps.toDouble(args[0]) : 1.0;
				if (Double.isNaN(depth) || depth < 0) depth = 0;
				JSArray result = new JSArray();
				flattenIntoArray(cx, result, O, (int) Math.min(depth, 1000));
				return result;
			}));

			proto.put("toString", makeMethod("toString", 0, (cx, thisObj, args) -> {
				Object O = toObject(thisObj);
				if (O instanceof JSObject jsObj) {
					Object joinFn = jsObj.get("join");
					if (joinFn instanceof JSFunction fn) {
						try {
							return fn.call0(cx, O);
						} catch (Throwable t) {
							if (t instanceof RuntimeException re) throw re;
							throw new RuntimeException(t);
						}
					}
				}
				return "[object Array]";
			}));
		}

		private static Object fastDenseReduce(JSContext cx, JSArray jsArr, JSFunction callback, Object[] args) throws Throwable {
			long len = jsArr.length();
			int k = 0;
			Object accumulator = null;
			boolean hasAccumulator = false;
			if (args.length > 1) {
				accumulator = args[1];
				hasAccumulator = true;
			} else {
				while (k < len && k < jsArr.denseSize) {
					Object val = jsArr.elements[k];
					if (val != JSArray.HOLE) {
						accumulator = val;
						hasAccumulator = true;
						k++;
						break;
					}
					k++;
				}
				if (!hasAccumulator) {
					throw new RuntimeException("TypeError: Reduce of empty array with no initial value");
				}
			}
			while (k < len) {
				if (!jsArr.isDense() || k >= jsArr.denseSize) {
					while (k < len) {
						if (hasProperty(jsArr, k)) {
							Object kVal = getProperty(jsArr, k);
							accumulator = callback.call4(cx, JSUndefined.INSTANCE, accumulator, kVal, (double) k, jsArr);
						}
						k++;
					}
					return accumulator;
				}
				Object kVal = jsArr.elements[k];
				if (kVal != JSArray.HOLE) {
					accumulator = callback.call4(cx, JSUndefined.INSTANCE, accumulator, kVal, (double) k, jsArr);
				}
				k++;
			}
			return accumulator;
		}

		private static Object genericReduce(JSContext cx, Object O, JSFunction callback, Object[] args) throws Throwable {
			long len = toLength(O);
			long k = 0;
			Object accumulator = null;
			boolean hasAccumulator = false;
			if (args.length > 1) {
				accumulator = args[1];
				hasAccumulator = true;
			}
			if (!hasAccumulator) {
				while (k < len) {
					if (hasProperty(O, k)) {
						accumulator = getProperty(O, k);
						hasAccumulator = true;
						k++;
						break;
					}
					k++;
				}
				if (!hasAccumulator) {
					throw new RuntimeException("TypeError: Reduce of empty array with no initial value");
				}
			}
			while (k < len) {
				if (hasProperty(O, k)) {
					Object kValue = getProperty(O, k);
					accumulator = callback.call4(cx, JSUndefined.INSTANCE, accumulator, kValue, (double) k, O);
				}
				k++;
			}
			return accumulator;
		}

		private static JSArray fastDenseFilter(JSContext cx, JSArray jsArr, JSFunction callback, Object thisArg) throws Throwable {
			long len = jsArr.length();
			JSArray result = new JSArray();
			for (int i = 0; i < len; i++) {
				if (!jsArr.isDense() || i >= jsArr.denseSize) {
					for (long k = i; k < len; k++) {
						if (hasProperty(jsArr, k)) {
							Object kValue = getProperty(jsArr, k);
							Object selected = callback.call3(cx, thisArg, kValue, (double) k, jsArr);
							if (JSOps.toBoolean(selected)) result.push(kValue);
						}
					}
					return result;
				}
				Object kValue = jsArr.elements[i];
				if (kValue == JSArray.HOLE) continue;
				Object selected = callback.call3(cx, thisArg, kValue, (double) i, jsArr);
				if (JSOps.toBoolean(selected)) {
					result.push(kValue);
				}
			}
			return result;
		}

		private static JSArray genericFilter(JSContext cx, Object O, JSFunction callback, Object thisArg) throws Throwable {
			long len = toLength(O);
			JSArray result = new JSArray();
			for (long k = 0; k < len; k++) {
				if (hasProperty(O, k)) {
					Object kValue = getProperty(O, k);
					Object selected = callback.call3(cx, thisArg, kValue, (double) k, O);
					if (JSOps.toBoolean(selected)) {
						result.push(kValue);
					}
				}
			}
			return result;
		}

		private static JSObject makeMethod(String name, int length, JSFunction fn) {
			return new JSBuiltinMethod(name, length, fn);
		}

		private static Object toObject(Object value) {
			if (value == null || value == JSUndefined.INSTANCE) {
				throw new RuntimeException("TypeError: Cannot convert undefined or null to object");
			}
			return value;
		}

		private static long toLength(Object obj) {
			if (obj instanceof JSArray arr) return arr.length();
			if (obj instanceof CharSequence seq) return seq.length();
			if (obj instanceof List<?> list) return list.size();
			if (obj instanceof JSObject jsObj) {
				Object lenVal = jsObj.get("length");
				if (lenVal == null || lenVal == JSUndefined.INSTANCE) return 0;
				double d = JSOps.toDouble(lenVal);
				if (Double.isNaN(d) || d <= 0) return 0;
				if (d >= 9007199254740991.0) return 9007199254740991L;
				return (long) d;
			}
			return 0;
		}

		private static boolean hasProperty(Object obj, long index) {
			if (obj instanceof JSArray arr) return arr.hasElement(index);
			if (obj instanceof CharSequence seq) return index >= 0 && index < seq.length();
			if (obj instanceof List<?> list) return index >= 0 && index < list.size();
			if (obj instanceof JSObject jsObj) return jsObj.has(String.valueOf(index));
			return false;
		}

		private static Object getProperty(Object obj, long index) {
			if (obj instanceof JSArray arr) return arr.getElement(index);
			if (obj instanceof CharSequence seq) {
				return (index >= 0 && index < seq.length()) ? String.valueOf(seq.charAt((int) index)) : JSUndefined.INSTANCE;
			}
			if (obj instanceof List<?> list) {
				return (index >= 0 && index < list.size()) ? list.get((int) index) : JSUndefined.INSTANCE;
			}
			if (obj instanceof JSObject jsObj) return jsObj.get(String.valueOf(index));
			return JSUndefined.INSTANCE;
		}

		private static void setProperty(Object obj, long index, Object value) {
			if (obj instanceof JSArray arr) {
				arr.setElement(index, value);
			} else if (obj instanceof JSObject jsObj) {
				jsObj.put(String.valueOf(index), value);
			}
		}

		private static void deleteProperty(Object obj, long index) {
			if (obj instanceof JSArray arr) {
				arr.deleteElement(index);
			} else if (obj instanceof JSObject jsObj) {
				jsObj.delete(String.valueOf(index));
			}
		}

		private static void appendConcatItem(JSArray result, Object item) {
			if (item instanceof JSArray arr) {
				long len = arr.length();
				for (long k = 0; k < len; k++) {
					if (arr.hasElement(k)) {
						result.push(arr.getElement(k));
					} else {
						result.push(JSArray.HOLE);
					}
				}
			} else {
				result.push(item);
			}
		}

		private static void flattenIntoArray(JSContext cx, JSArray target, Object source, int depth) {
			long len = toLength(source);
			for (long k = 0; k < len; k++) {
				if (hasProperty(source, k)) {
					Object val = getProperty(source, k);
					if (depth > 0 && (val instanceof JSArray)) {
						flattenIntoArray(cx, target, val, depth - 1);
					} else {
						target.push(val);
					}
				}
			}
		}
	}

	public long rawReturnBits;

	public final double getReturnDouble() {
		return Double.longBitsToDouble(rawReturnBits);
	}
	public final int getReturnInt() {
		return (int) rawReturnBits;
	}

	public JSContext() {
		// 100% 零成本实例化：按需懒加载所有 Built-in 对象，首调 0 类加载突发
	}

	public static class LazyBuiltins {
		public static final JSObject            OBJECT_PROTOTYPE = LazyObject.OBJECT_PROTOTYPE;
		public static final JSObject            ARRAY_PROTOTYPE  = LazyArray.ARRAY_PROTOTYPE;
		public static final JSObjectConstructor OBJECT           = LazyObject.OBJECT;
		public static final JSArrayConstructor  ARRAY            = LazyArray.ARRAY;
		public static final JSObject            CONSOLE          = LazyConsole.CONSOLE;
		public static final JSObject            MATH             = LazyMath.MATH;
		public static final JSFunction          PRINT            = LazyMisc.PRINT;
		public static final JSFunction          IMPORT_CLASS     = LazyMisc.IMPORT_CLASS;
		public static final JSObject            PACKAGES         = LazyMisc.PACKAGES;
		public static final JSFunction          REGEXP           = LazyMisc.REGEXP;
	}

	private Object resolveLazyGlobal(int slot) {
		Object val = null;
		if (slot == SLOT_NAN) { val = Double.NaN; } else if (slot == SLOT_INFINITY) {
			val = Double.POSITIVE_INFINITY;
		} else if (slot == SLOT_UNDEFINED) {
			val = JSUndefined.INSTANCE;
		} else if (slot == SLOT_JSOPS) {
			val = JSOps.class;
		} else if (slot == SLOT_PRINT) {
			val = LazyMisc.PRINT;
		} else if (slot == SLOT_CONSOLE) {
			val = LazyConsole.CONSOLE;
		} else if (slot == SLOT_MATH) {
			val = LazyMath.MATH;
		} else if (slot == SLOT_IMPORT_CLASS) {
			val = LazyMisc.IMPORT_CLASS;
		} else if (slot == SLOT_PACKAGES) {
			val = LazyMisc.PACKAGES;
		} else if (slot == SLOT_REGEXP) {
			val = LazyMisc.REGEXP;
		} else if (slot == SLOT_OBJECT) {
			val = LazyObject.OBJECT;
		} else if (slot == SLOT_ARRAY) {
			val = LazyArray.ARRAY;
		}

		if (val != null) {
			ensureGlobalSlotCapacity(slot);
			globalSlots[slot] = val;
			return val;
		}
		return JSUndefined.INSTANCE;
	}

	private synchronized void ensureGlobalSlotCapacity(int slot) {
		if (slot >= globalSlots.length) {
			globalSlots = Arrays.copyOf(globalSlots, Math.max(globalSlots.length * 2, slot + 1));
		}
	}

	public synchronized void set(String name, Object value) {
		globals.put(name, value == null ? NULL_VALUE : value);
		int slot = getGlobalSlot(name);
		ensureGlobalSlotCapacity(slot);
		globalSlots[slot] = value == null ? NULL_VALUE : value;
	}

	public Object get(String name) {
		int      slot  = GLOBAL_SLOT_REGISTRY.getOrDefault(name, -1);
		Object[] slots = this.globalSlots;
		if (slot >= 0 && slot < slots.length) {
			Object val = slots[slot];
			if (val == NULL_VALUE) return null;
			if (val != null) return val;
		}
		Object val = globals.get(name);
		if (val != null) {
			if (val == NULL_VALUE) return null;
			if (slot >= 0) {
				ensureGlobalSlotCapacity(slot);
				globalSlots[slot] = val;
			}
			return val;
		}
		if (slot >= 0) {
			Object resolved = resolveLazyGlobal(slot);
			if (resolved != JSUndefined.INSTANCE) {
				return resolved;
			}
		}
		if (!name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
			try {
				Class<?> c = Class.forName(name);
				globals.put(name, c);
				if (slot >= 0) {
					ensureGlobalSlotCapacity(slot);
					globalSlots[slot] = c;
				}
				return c;
			} catch (ClassNotFoundException ignored) {
			}
		}
		return JSUndefined.INSTANCE;
	}

	public final Object getSlot(int slot) {
		Object[] slots = this.globalSlots;
		if (slot < slots.length) {
			Object val = slots[slot];
			if (val == NULL_VALUE) return null;
			if (val != null) return val;
		}
		return resolveLazyGlobal(slot);
	}

	public synchronized final void setSlot(int slot, Object value) {
		ensureGlobalSlotCapacity(slot);
		globalSlots[slot] = value == null ? NULL_VALUE : value;
	}

	public Map<String, Object> getGlobals() {
		return globals;
	}

	public Object eval(String code) {
		try {
			JSLexer      lexer   = new JSLexer(code);
			List<Token>  tokens  = lexer.tokenize();
			JSParser     parser  = new JSParser(tokens);
			Node.Program program = parser.parse();

			JSScript script = JSCompiler.compile(program);
			return script.run(this);
		} catch (Throwable t) {
			if (t instanceof RuntimeException) {
				throw (RuntimeException) t;
			}
			throw new RuntimeException("Script execution error: " + t.getMessage(), t);
		}
	}
}
