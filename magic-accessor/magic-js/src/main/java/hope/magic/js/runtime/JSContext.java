package hope.magic.js.runtime;

import hope.magic.js.ast.*;
import hope.magic.js.compiler.JSCompiler;
import hope.magic.js.parser.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
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

	public static final int                 INITIAL_GLOBAL_SLOTS_CAPACITY = 64;
	public volatile     Object[]            globalSlots                   = new Object[INITIAL_GLOBAL_SLOTS_CAPACITY];
	// 架构优化说明：
	// 原 globals 采用 ConcurrentHashMap<String, Object> 作为实例字段，
	// 导致每个 JSContext 实例化时均需要分配包含并发分段/计数器单元的重型哈希表，增加了堆分配与 GC 压力。
	// 实际上，JS 执行的热点全局变量均由 globalSlots[] 密集数组直接索引（1 指令寻址），
	// 仅在首次冷加载或反射兜底时才会访问 globals。
	// 故将其替换为轻量 HashMap<String, Object>，写操作集中在 synchronized 的 set() 中，
	// 读操作通过 synchronized (globals) 块保证复合原子性与线程安全，大幅减少 Context 创建开销。
	private final       Map<String, Object> globals                       = new HashMap<>();

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
		public JSObject getPrototype() {
			JSObject p = super.getPrototype();
			return (p != null && p != LazyObject.OBJECT_PROTOTYPE) ? p : LazyFunction.FUNCTION_PROTOTYPE;
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

	public static final int SLOT_NAN             = getGlobalSlot("NaN");
	public static final int SLOT_INFINITY        = getGlobalSlot("Infinity");
	public static final int SLOT_UNDEFINED       = getGlobalSlot("undefined");
	public static final int SLOT_JSOPS           = getGlobalSlot("JSOps");
	public static final int SLOT_PRINT           = getGlobalSlot("print");
	public static final int SLOT_CONSOLE         = getGlobalSlot("console");
	public static final int SLOT_MATH            = getGlobalSlot("Math");
	public static final int SLOT_IMPORT_CLASS    = getGlobalSlot("importClass");
	public static final int SLOT_PACKAGES        = getGlobalSlot("Packages");
	public static final int SLOT_REGEXP          = getGlobalSlot("RegExp");
	public static final int SLOT_OBJECT          = getGlobalSlot("Object");
	public static final int SLOT_ARRAY           = getGlobalSlot("Array");
	public static final int SLOT_JAVA            = getGlobalSlot("Java");
	public static final int SLOT_JAVA_PKG        = getGlobalSlot("java");
	public static final int SLOT_JAVAX_PKG       = getGlobalSlot("javax");
	public static final int SLOT_ERROR           = getGlobalSlot("Error");
	public static final int SLOT_TYPE_ERROR      = getGlobalSlot("TypeError");
	public static final int SLOT_RANGE_ERROR     = getGlobalSlot("RangeError");
	public static final int SLOT_SYNTAX_ERROR    = getGlobalSlot("SyntaxError");
	public static final int SLOT_REFERENCE_ERROR = getGlobalSlot("ReferenceError");
	public static final int SLOT_URI_ERROR       = getGlobalSlot("URIError");
	public static final int SLOT_EVAL_ERROR      = getGlobalSlot("EvalError");
	public static final int SLOT_BOOLEAN         = getGlobalSlot("Boolean");
	public static final int SLOT_NUMBER          = getGlobalSlot("Number");
	public static final int SLOT_STRING          = getGlobalSlot("String");
	public static final int SLOT_FUNCTION        = getGlobalSlot("Function");
	public static final int SLOT_PROXY           = getGlobalSlot("Proxy");
	public static final int SLOT_REFLECT         = getGlobalSlot("Reflect");
	public static final int SLOT_DATE            = getGlobalSlot("Date");
	public static final int SLOT_PROMISE         = getGlobalSlot("Promise");
	public static final int SLOT_QUEUE_MICROTASK = getGlobalSlot("queueMicrotask");
	public static final int SLOT_GLOBAL_THIS     = getGlobalSlot("globalThis");
	public static final int SLOT_DOLLAR_262      = getGlobalSlot("$262");

	public static class JSBuiltinMethod extends JSObject implements JSFunction {
		private static final List<String> BUILTIN_METHOD_PROPS = List.of("name", "length");
		private static final JSShape      METHOD_SHAPE         = JSShape.createStaticPrototypeShape(
		 BUILTIN_METHOD_PROPS,
		 new byte[]{
			(byte) (JSShape.TYPE_OBJECT | JSShape.FLAG_NOT_WRITABLE | JSShape.FLAG_NOT_ENUMERABLE),
			(byte) (JSShape.TYPE_DOUBLE | JSShape.FLAG_NOT_WRITABLE | JSShape.FLAG_NOT_ENUMERABLE)
		 }
		);

		private final JSFunction fn;

		public JSBuiltinMethod(String name, int length, JSFunction fn) {
			super(METHOD_SHAPE, null);
			this.obj0 = name;
			this.prim1 = Double.doubleToRawLongBits((double) length);
			this.doubleFieldMask = (1L << 1);
			this.fn = fn;
		}

		public String getMethodName() {
			return obj0 instanceof String s ? s : "";
		}

		@Override
		public JSObject getPrototype() {
			JSObject p = super.getPrototype();
			return p != null ? p : LazyFunction.FUNCTION_PROTOTYPE;
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

	public static JSBuiltinMethod makeMethod(String name, int length, JSFunction fn) {
		return new JSBuiltinMethod(name, length, fn);
	}

	public static class BoundFunction extends JSObject implements JSFunction {
		private final JSFunction target;
		private final Object     boundThis;
		private final Object[]   boundArgs;

		public BoundFunction(JSFunction target, Object boundThis, Object[] boundArgs, JSObject prototype) {
			super(prototype);
			this.target = target;
			this.boundThis = boundThis;
			this.boundArgs = boundArgs;
			put("name", "bound ");
			put("length", 0);
		}

		@Override
		public JSObject getPrototype() {
			JSObject p = super.getPrototype();
			return p != null ? p : LazyFunction.FUNCTION_PROTOTYPE;
		}

		@Override
		public Object call(JSContext cx, Object thisObj, Object[] args) throws Throwable {
			Object[] fullArgs = new Object[boundArgs.length + args.length];
			System.arraycopy(boundArgs, 0, fullArgs, 0, boundArgs.length);
			System.arraycopy(args, 0, fullArgs, boundArgs.length, args.length);
			return target.call(cx, boundThis, fullArgs);
		}

		@Override
		public String toString() {
			return "function () { [native code] }";
		}
	}

	public static class JSArguments extends JSObject {
		public JSArguments(JSFunction callee, Object[] args) {
			super(LazyObject.OBJECT_PROTOTYPE);
			int len = (args != null) ? args.length : 0;
			put("length", (double) len);
			if (callee != null) {
				put("callee", callee);
			}
			if (args != null) {
				for (int i = 0; i < len; i++) {
					Object v = args[i];
					put(String.valueOf(i), v != null ? v : JSUndefined.INSTANCE);
				}
			}
		}
	}

	public static class JSBuiltinConstructor extends JSObject implements JSFunction {
		private final JSFunction fn;

		public JSBuiltinConstructor(String name, int length, JSObject prototype, JSFunction fn) {
			super(LazyFunction.FUNCTION_PROTOTYPE);
			put("name", name);
			put("length", length);
			put("prototype", prototype);
			if (prototype != null) {
				prototype.put("constructor", this);
			}
			this.fn = fn;
		}

		@Override
		public JSObject getPrototype() {
			JSObject p = super.getPrototype();
			return (p != null && p != LazyObject.OBJECT_PROTOTYPE) ? p : LazyFunction.FUNCTION_PROTOTYPE;
		}

		@Override
		public Object call(JSContext cx, Object thisObj, Object[] args) throws Throwable {
			return fn.call(cx, thisObj, args);
		}

		@Override
		public String toString() {
			return "function " + get("name") + "() { [native code] }";
		}
	}

	static class LazyMisc {
		static final JSFunction PRINT = (cx, thisObj, args) -> {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < args.length; i++) {
				if (i > 0) sb.append(" ");
				sb.append(JSOps.toStr(args[i]));
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

		public static class PackageObject extends JSObject {
			private final String prefix;

			public PackageObject(String prefix) {
				this.prefix = prefix;
			}

			@Override
			public Object get(String name) {
				String fullName = (prefix == null || prefix.isEmpty()) ? name : prefix + "." + name;
				try {
					return Class.forName(fullName);
				} catch (ClassNotFoundException e) {
					Object existing = super.get(name);
					if (existing != JSUndefined.INSTANCE) return existing;
					return new PackageObject(fullName);
				}
			}

			@Override
			public String toString() {
				return "[JavaPackage " + (prefix == null || prefix.isEmpty() ? "<root>" : prefix) + "]";
			}
		}

		static final JSObject PACKAGES  = new PackageObject("");
		static final JSObject JAVA_PKG  = new PackageObject("java");
		static final JSObject JAVAX_PKG = new PackageObject("javax");
		static final JSObject JAVA      = createJavaObject();

		private static JSObject createJavaObject() {
			JSObject javaObj = new JSObject();
			javaObj.put("type", (JSFunction) (cx, thisObj, args) -> {
				if (args.length == 0 || args[0] == null) {
					throw new IllegalArgumentException("Java.type() requires a class name");
				}
				String className = JSOps.toStr(args[0]);
				try {
					return Class.forName(className);
				} catch (ClassNotFoundException e) {
					throw new RuntimeException("ClassNotFoundException: " + className, e);
				}
			});

			javaObj.put("extend", (JSFunction) (cx, thisObj, args) -> {
				if (args.length == 0 || !(args[0] instanceof Class<?> targetClass)) {
					throw new IllegalArgumentException("Java.extend() requires a Java Class as the first argument");
				}
				Map<String, JSFunction> methods = new LinkedHashMap<>();
				if (args.length > 1 && args[1] instanceof JSObject overrides) {
					for (String key : overrides.keys()) {
						Object val = overrides.get(key);
						if (val instanceof JSFunction fn) {
							methods.put(key, fn);
						}
					}
				}
				return JavaClassExtender.createClassConstructor(targetClass, methods, null);
			});

			return javaObj;
		}

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
			JSShape  shape = JSShape.createStaticPrototypeShape(List.of("log"));
			JSObject c     = new JSObject(shape, null);
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
		private static final JSShape      MATH_SHAPE = JSShape.createStaticPrototypeShape(MATH_PROPS);
		static final         JSObject     MATH       = createMath();

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
		 "hasOwnProperty", "toString", "valueOf", "constructor", "propertyIsEnumerable", "isPrototypeOf"
		);
		private static final List<String> OBJECT_CTOR_PROPS  = List.of(
		 "name", "length", "prototype", "is", "getPrototypeOf", "setPrototypeOf", "create", "getOwnPropertyNames",
		 "defineProperty", "defineProperties", "getOwnPropertyDescriptor", "getOwnPropertyDescriptors", "keys"
		);

		static final JSObject            OBJECT_PROTOTYPE = createObjectPrototype();
		static final JSObjectConstructor OBJECT           = createObjectConstructor(OBJECT_PROTOTYPE);

		private static JSObject createObjectPrototype() {
			// 原型链顶端：Object.prototype 原型严格为 null，采用批量烘焙终态 Shape
			JSShape  shape = JSShape.createStaticPrototypeShape(OBJECT_PROTO_PROPS);
			JSObject proto = new JSObject(shape, null);
			proto.put("hasOwnProperty", makeMethod("hasOwnProperty", 1, (cx, thisObj, args) -> {
				if (args.length == 0) return Boolean.FALSE;
				String key = JSOps.toStr(args[0]);
				if (thisObj instanceof JSObject jsObj) {
					return jsObj.hasOwnProperty(key);
				}
				return Boolean.FALSE;
			}));
			proto.put("toString", makeMethod("toString", 0, (cx, thisObj, args) -> {
				if (thisObj == null || thisObj == JSUndefined.INSTANCE) {
					return "[object Undefined]";
				}

				// 2. Built-in tag checks:
				String tag;
				if (thisObj instanceof JSArray) {
					tag = "Array";
				} else if (thisObj instanceof JSFunction) {
					tag = "Function";
				} else if (thisObj instanceof JSDate) { // adjust to your class names
					tag = "Date";
				} else if (thisObj instanceof JSRegExp) {
					tag = "RegExp";
				} else {
					tag = "Object";
				}

				return "[object " + tag + "]";
			}));
			proto.put("valueOf", makeMethod("valueOf", 0, (cx, thisObj, args) -> thisObj));
			proto.put("propertyIsEnumerable", makeMethod("propertyIsEnumerable", 1, (cx, thisObj, args) -> {
				if (args.length == 0 || !(thisObj instanceof JSObject jsObj)) return Boolean.FALSE;
				String key   = JSOps.toStr(args[0]);
				int    symId = SymbolTable.lookupId(key);
				if (symId == SymbolTable.NO_SYMBOL) return Boolean.FALSE;
				int offset = jsObj.shape.getOffset(symId);
				if (offset < 0 || (!jsObj.isDoubleSlot(offset) && jsObj.getRawObjectSlot(offset) == JSObject.DELETED)) {
					return Boolean.FALSE;
				}
				return jsObj.shape.isEnumerable(offset) ? Boolean.TRUE : Boolean.FALSE;
			}));
			proto.put("isPrototypeOf", makeMethod("isPrototypeOf", 1, (cx, thisObj, args) -> {
				if (args.length == 0 || !(args[0] instanceof JSObject target)) return Boolean.FALSE;
				if (!(thisObj instanceof JSObject protoObj)) return Boolean.FALSE;
				JSObject p = target.getPrototype();
				while (p != null) {
					if (p == protoObj) return Boolean.TRUE;
					p = p.getPrototype();
				}
				return Boolean.FALSE;
			}));
			return proto;
		}

		private static JSObjectConstructor createObjectConstructor(JSObject proto) {
			JSShape             shape = JSShape.createStaticPrototypeShape(proto.shape, OBJECT_CTOR_PROPS);
			JSObjectConstructor ctor  = new JSObjectConstructor(shape, proto);
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

			ctor.put("setPrototypeOf", (JSFunction) (cx, thisObj, args) -> {
				if (args.length < 2 || args[0] == null || args[0] == JSUndefined.INSTANCE) {
					throw new RuntimeException("TypeError: Object.setPrototypeOf called on null or undefined");
				}
				Object p = args[1];
				if (p != null && p != JSUndefined.INSTANCE && !(p instanceof JSObject)) {
					throw new RuntimeException("TypeError: Object prototype may only be an Object or null");
				}
				if (args[0] instanceof JSObject jsObj) {
					jsObj.setPrototype(p == null || p == JSUndefined.INSTANCE ? null : (JSObject) p);
				}
				return args[0];
			});

			ctor.put("create", (JSFunction) (cx, thisObj, args) -> {
				if (args.length == 0) throw new RuntimeException("TypeError: Object.create requires at least 1 argument");
				Object p = args[0];
				if (p != null && p != JSUndefined.INSTANCE && !(p instanceof JSObject)) {
					throw new RuntimeException("TypeError: Object prototype may only be an Object or null");
				}
				JSObject res = new JSObject(p == null || p == JSUndefined.INSTANCE ? null : (JSObject) p);
				if (args.length > 1 && args[1] instanceof JSObject props) {
					for (String k : props.keys()) {
						definePropertyCore(cx, res, k, props.get(k));
					}
				}
				return res;
			});

			ctor.put("getOwnPropertyNames", (JSFunction) (cx, thisObj, args) -> {
				if (args.length == 0 || args[0] == null || args[0] == JSUndefined.INSTANCE) {
					throw new RuntimeException("TypeError: Cannot convert undefined or null to object");
				}
				if (args[0] instanceof JSObject jsObj) {
					JSArray arr = new JSArray();
					for (String k : jsObj.getOwnPropertyNames()) {
						arr.push(k);
					}
					return arr;
				}
				return new JSArray();
			});

			ctor.put("keys", (JSFunction) (cx, thisObj, args) -> {
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

			ctor.put("defineProperty", (JSFunction) (cx, thisObj, args) -> {
				Object target = args.length > 0 ? args[0] : null;
				Object prop   = args.length > 1 ? args[1] : null;
				Object desc   = args.length > 2 ? args[2] : null;
				return definePropertyCore(cx, target, prop, desc);
			});

			ctor.put("defineProperties", (JSFunction) (cx, thisObj, args) -> {
				if (args.length == 0 || args[0] == null || args[0] == JSUndefined.INSTANCE) {
					throw new RuntimeException("TypeError: Object.defineProperties called on non-object");
				}
				Object target   = args[0];
				Object propsObj = args.length > 1 ? args[1] : null;
				if (propsObj == null || propsObj == JSUndefined.INSTANCE || !(propsObj instanceof JSObject props)) {
					throw new RuntimeException("TypeError: Properties must be an object");
				}
				for (String k : props.keys()) {
					definePropertyCore(cx, target, k, props.get(k));
				}
				return target;
			});

			ctor.put("getOwnPropertyDescriptor", (JSFunction) (cx, thisObj, args) -> {
				Object target = args.length > 0 ? args[0] : null;
				Object prop   = args.length > 1 ? args[1] : null;
				return getOwnPropertyDescriptorCore(cx, target, prop);
			});

			ctor.put("getOwnPropertyDescriptors", (JSFunction) (cx, thisObj, args) -> {
				if (args.length == 0 || args[0] == null || args[0] == JSUndefined.INSTANCE) {
					throw new RuntimeException("TypeError: Cannot convert undefined or null to object");
				}
				Object target = args[0];
				JSObject jsObj = (target instanceof JSBridgedObject bridged)
				 ? bridged.getJSObject()
				 : (target instanceof JSObject obj ? obj : null);
				JSObject res = new JSObject();
				if (jsObj != null) {
					for (String k : jsObj.getOwnPropertyNames()) {
						Object d = getOwnPropertyDescriptorCore(cx, target, k);
						if (d != JSUndefined.INSTANCE) {
							res.put(k, d);
						}
					}
				}
				return res;
			});

			return ctor;
		}

		private static Object definePropertyCore(JSContext cx, Object target, Object propKey, Object descObj) {
			if (target == null || target == JSUndefined.INSTANCE || !(target instanceof JSObject || target instanceof JSBridgedObject)) {
				throw new RuntimeException("TypeError: Object.defineProperty called on non-object");
			}
			JSObject jsObj = (target instanceof JSBridgedObject bridged) ? bridged.getJSObject() : (JSObject) target;
			if (jsObj == null) {
				throw new RuntimeException("TypeError: Object.defineProperty called on non-object");
			}

			if (descObj == null || descObj == JSUndefined.INSTANCE || !(descObj instanceof JSObject desc)) {
				throw new RuntimeException("TypeError: Property description must be an object: " + descObj);
			}

			boolean hasValue        = desc.hasOwnProperty("value");
			boolean hasWritable     = desc.hasOwnProperty("writable");
			boolean hasGet          = desc.hasOwnProperty("get");
			boolean hasSet          = desc.hasOwnProperty("set");
			boolean hasEnumerable   = desc.hasOwnProperty("enumerable");
			boolean hasConfigurable = desc.hasOwnProperty("configurable");

			if ((hasValue || hasWritable) && (hasGet || hasSet)) {
				throw new RuntimeException("TypeError: Invalid property descriptor. Cannot both specify accessors and a value or writable attribute");
			}

			Object getVal = hasGet ? desc.get("get") : null;
			Object setVal = hasSet ? desc.get("set") : null;

			if (hasGet && getVal != JSUndefined.INSTANCE && getVal != null && !(getVal instanceof JSFunction)) {
				throw new RuntimeException("TypeError: Getter must be a function: " + getVal);
			}
			if (hasSet && setVal != JSUndefined.INSTANCE && setVal != null && !(setVal instanceof JSFunction)) {
				throw new RuntimeException("TypeError: Setter must be a function: " + setVal);
			}

			JSFunction getter = (getVal instanceof JSFunction fn) ? fn : null;
			JSFunction setter = (setVal instanceof JSFunction fn) ? fn : null;

			String key    = JSOps.toStr(propKey);
			int    propId = SymbolTable.id(key);

			int     offset = jsObj.shape.getOffset(propId);
			boolean exists = offset >= 0 && (jsObj.isDoubleSlot(offset) || jsObj.getRawObjectSlot(offset) != JSObject.DELETED);

			if (!exists) {
				int targetOffset;
				if (hasGet || hasSet) {
					boolean enumerable   = hasEnumerable && JSOps.toBoolean(desc.get("enumerable"));
					boolean configurable = hasConfigurable && JSOps.toBoolean(desc.get("configurable"));
					byte    type         = JSShape.FLAG_ACCESSOR;
					if (!enumerable) type |= JSShape.FLAG_NOT_ENUMERABLE;
					if (!configurable) type |= JSShape.FLAG_NOT_CONFIGURABLE;

					if (offset >= 0) {
						jsObj.shape = jsObj.shape.updatePropertyType(offset, type);
						targetOffset = offset;
					} else {
						jsObj.shape = jsObj.shape.addProperty(propId, type);
						targetOffset = jsObj.shape.getOffset(propId);
					}
					jsObj.setSlot(targetOffset, new PropertyAccessor(getter, setter));
				} else {
					Object  value        = hasValue ? desc.get("value") : JSUndefined.INSTANCE;
					boolean writable     = hasWritable && JSOps.toBoolean(desc.get("writable"));
					boolean enumerable   = hasEnumerable && JSOps.toBoolean(desc.get("enumerable"));
					boolean configurable = hasConfigurable && JSOps.toBoolean(desc.get("configurable"));

					byte type = (value instanceof Number) ? JSShape.TYPE_DOUBLE : JSShape.TYPE_OBJECT;
					if (!writable) type |= JSShape.FLAG_NOT_WRITABLE;
					if (!enumerable) type |= JSShape.FLAG_NOT_ENUMERABLE;
					if (!configurable) type |= JSShape.FLAG_NOT_CONFIGURABLE;

					if (offset >= 0) {
						jsObj.shape = jsObj.shape.updatePropertyType(offset, type);
						targetOffset = offset;
					} else {
						jsObj.shape = jsObj.shape.addProperty(propId, type);
						targetOffset = jsObj.shape.getOffset(propId);
					}
					if ((type & JSShape.TYPE_MASK) == JSShape.TYPE_DOUBLE) {
						jsObj.setDoubleSlot(targetOffset, JSOps.toDouble(value));
					} else {
						jsObj.setSlot(targetOffset, value);
					}
				}
			} else {
				byte             currentType         = jsObj.shape.getSlotType(offset);
				boolean          currentIsAccessor   = (currentType & JSShape.FLAG_ACCESSOR) != 0;
				boolean          currentWritable     = (currentType & JSShape.FLAG_NOT_WRITABLE) == 0;
				boolean          currentEnumerable   = (currentType & JSShape.FLAG_NOT_ENUMERABLE) == 0;
				boolean          currentConfigurable = (currentType & JSShape.FLAG_NOT_CONFIGURABLE) == 0;
				Object           currentValue        = currentIsAccessor ? null : jsObj.getSlot(offset);
				PropertyAccessor currentAcc          = currentIsAccessor ? (PropertyAccessor) jsObj.getRawObjectSlot(offset) : null;

				if (!currentConfigurable) {
					if (hasConfigurable && JSOps.toBoolean(desc.get("configurable"))) {
						throw new RuntimeException("TypeError: Cannot redefine property: " + key);
					}
					if (hasEnumerable && JSOps.toBoolean(desc.get("enumerable")) != currentEnumerable) {
						throw new RuntimeException("TypeError: Cannot redefine property: " + key);
					}
					if ((hasGet || hasSet) != currentIsAccessor) {
						throw new RuntimeException("TypeError: Cannot redefine property: " + key);
					}
					if (currentIsAccessor) {
						if (hasGet && getter != (currentAcc != null ? currentAcc.getter : null)) {
							throw new RuntimeException("TypeError: Cannot redefine property: " + key);
						}
						if (hasSet && setter != (currentAcc != null ? currentAcc.setter : null)) {
							throw new RuntimeException("TypeError: Cannot redefine property: " + key);
						}
					} else {
						if (!currentWritable) {
							if (hasWritable && JSOps.toBoolean(desc.get("writable"))) {
								throw new RuntimeException("TypeError: Cannot redefine property: " + key);
							}
							if (hasValue && !JSOps.sameValue(desc.get("value"), currentValue)) {
								throw new RuntimeException("TypeError: Cannot redefine property: " + key);
							}
						}
					}
				}

				boolean newConfigurable = hasConfigurable ? JSOps.toBoolean(desc.get("configurable")) : currentConfigurable;
				boolean newEnumerable   = hasEnumerable ? JSOps.toBoolean(desc.get("enumerable")) : currentEnumerable;

				if ((hasGet || hasSet) && !currentIsAccessor) {
					JSFunction newGetter = hasGet ? getter : null;
					JSFunction newSetter = hasSet ? setter : null;
					byte       newType   = JSShape.FLAG_ACCESSOR;
					if (!newEnumerable) newType |= JSShape.FLAG_NOT_ENUMERABLE;
					if (!newConfigurable) newType |= JSShape.FLAG_NOT_CONFIGURABLE;

					jsObj.shape = jsObj.shape.updatePropertyType(offset, newType);
					jsObj.clearDoubleMask(offset);
					jsObj.setSlot(offset, new PropertyAccessor(newGetter, newSetter));
				} else if (!(hasGet || hasSet) && (hasValue || hasWritable) && currentIsAccessor) {
					Object  newValue    = hasValue ? desc.get("value") : JSUndefined.INSTANCE;
					boolean newWritable = hasWritable && JSOps.toBoolean(desc.get("writable"));
					byte    newType     = (newValue instanceof Number) ? JSShape.TYPE_DOUBLE : JSShape.TYPE_OBJECT;
					if (!newWritable) newType |= JSShape.FLAG_NOT_WRITABLE;
					if (!newEnumerable) newType |= JSShape.FLAG_NOT_ENUMERABLE;
					if (!newConfigurable) newType |= JSShape.FLAG_NOT_CONFIGURABLE;

					jsObj.shape = jsObj.shape.updatePropertyType(offset, newType);
					if ((newType & JSShape.TYPE_MASK) == JSShape.TYPE_DOUBLE) {
						jsObj.setDoubleSlot(offset, JSOps.toDouble(newValue));
					} else {
						jsObj.setSlot(offset, newValue);
					}
				} else if (currentIsAccessor) {
					JSFunction newGetter = hasGet ? getter : (currentAcc != null ? currentAcc.getter : null);
					JSFunction newSetter = hasSet ? setter : (currentAcc != null ? currentAcc.setter : null);
					byte       newType   = JSShape.FLAG_ACCESSOR;
					if (!newEnumerable) newType |= JSShape.FLAG_NOT_ENUMERABLE;
					if (!newConfigurable) newType |= JSShape.FLAG_NOT_CONFIGURABLE;

					jsObj.shape = jsObj.shape.updatePropertyType(offset, newType);
					jsObj.setSlot(offset, new PropertyAccessor(newGetter, newSetter));
				} else {
					boolean newWritable = hasWritable ? JSOps.toBoolean(desc.get("writable")) : currentWritable;
					Object  newValue    = hasValue ? desc.get("value") : currentValue;
					byte    newType     = (newValue instanceof Number) ? JSShape.TYPE_DOUBLE : JSShape.TYPE_OBJECT;
					if (!newWritable) newType |= JSShape.FLAG_NOT_WRITABLE;
					if (!newEnumerable) newType |= JSShape.FLAG_NOT_ENUMERABLE;
					if (!newConfigurable) newType |= JSShape.FLAG_NOT_CONFIGURABLE;

					jsObj.shape = jsObj.shape.updatePropertyType(offset, newType);
					if (hasValue) {
						if ((newType & JSShape.TYPE_MASK) == JSShape.TYPE_DOUBLE) {
							jsObj.setDoubleSlot(offset, JSOps.toDouble(newValue));
						} else {
							jsObj.setSlot(offset, newValue);
						}
					}
				}
			}

			return target;
		}

		private static Object getOwnPropertyDescriptorCore(JSContext cx, Object target, Object propKey) {
			if (target == null || target == JSUndefined.INSTANCE) {
				throw new RuntimeException("TypeError: Cannot convert undefined or null to object");
			}
			JSObject jsObj = (target instanceof JSBridgedObject bridged)
			 ? bridged.getJSObject()
			 : (target instanceof JSObject obj ? obj : null);
			if (jsObj == null) return JSUndefined.INSTANCE;

			String key    = JSOps.toStr(propKey);
			int    propId = SymbolTable.id(key);
			int    offset = jsObj.shape.getOffset(propId);
			if (offset < 0 || (!jsObj.isDoubleSlot(offset) && jsObj.getRawObjectSlot(offset) == JSObject.DELETED)) {
				return JSUndefined.INSTANCE;
			}

			JSObject desc = new JSObject();
			if (jsObj.shape.isAccessor(offset)) {
				PropertyAccessor acc = (PropertyAccessor) jsObj.getRawObjectSlot(offset);
				desc.put("get", acc != null && acc.getter != null ? acc.getter : JSUndefined.INSTANCE);
				desc.put("set", acc != null && acc.setter != null ? acc.setter : JSUndefined.INSTANCE);
				desc.put("enumerable", jsObj.shape.isEnumerable(offset));
				desc.put("configurable", jsObj.shape.isConfigurable(offset));
			} else {
				desc.put("value", jsObj.getSlot(offset));
				desc.put("writable", jsObj.shape.isWritable(offset));
				desc.put("enumerable", jsObj.shape.isEnumerable(offset));
				desc.put("configurable", jsObj.shape.isConfigurable(offset));
			}
			return desc;
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
		private static final List<String> ARRAY_CTOR_PROPS  = List.of(
		 "name", "length", "prototype", "isArray", "of", "from"
		);

		private static final byte[] ARRAY_CTOR_TYPES = new byte[]{
		 (byte) (JSShape.TYPE_OBJECT | JSShape.FLAG_NOT_WRITABLE | JSShape.FLAG_NOT_ENUMERABLE),
		 (byte) (JSShape.TYPE_DOUBLE | JSShape.FLAG_NOT_WRITABLE | JSShape.FLAG_NOT_ENUMERABLE),
		 (byte) (JSShape.TYPE_OBJECT | JSShape.FLAG_NOT_WRITABLE | JSShape.FLAG_NOT_ENUMERABLE | JSShape.FLAG_NOT_CONFIGURABLE),
		 (byte) (JSShape.TYPE_OBJECT | JSShape.FLAG_NOT_ENUMERABLE),
		 (byte) (JSShape.TYPE_OBJECT | JSShape.FLAG_NOT_ENUMERABLE),
		 (byte) (JSShape.TYPE_OBJECT | JSShape.FLAG_NOT_ENUMERABLE),
		 };

		static final JSArray            ARRAY_PROTOTYPE = createArrayPrototype(LazyObject.OBJECT_PROTOTYPE);
		static final JSArrayConstructor ARRAY           = createArrayConstructor(ARRAY_PROTOTYPE);

		private static JSArray createArrayPrototype(JSObject objectProto) {
			JSShape shape = JSShape.createStaticPrototypeShape(objectProto.shape, ARRAY_PROTO_PROPS);
			return new JSArray(shape, objectProto);
		}

		static boolean isArray(Object arg) {
			Object cur = arg;
			while (cur instanceof JSProxy proxy) {
				if (proxy.revoked) {
					throw makeTypeError("Cannot perform 'isArray' on a revoked proxy");
				}
				cur = proxy.target;
			}
			return cur instanceof JSArray;
		}

		private static JSArrayConstructor createArrayConstructor(JSObject proto) {
			JSShape            shape = JSShape.createStaticPrototypeShape(ARRAY_CTOR_PROPS, ARRAY_CTOR_TYPES);
			JSArrayConstructor ctor  = new JSArrayConstructor(shape, LazyFunction.FUNCTION_PROTOTYPE);
			proto.put("constructor", ctor);
			proto.put("length", 0.0);

			ctor.setSlot(0, "Array");
			ctor.setDoubleSlot(1, 1.0);
			ctor.setSlot(2, proto);
			ctor.setSlot(3, makeMethod("isArray", 1, (cx, thisObj, args) -> {
				if (args.length == 0) return Boolean.FALSE;
				return isArray(args[0]) ? Boolean.TRUE : Boolean.FALSE;
			}));
			ctor.setSlot(4, makeMethod("of", 0, (cx, thisObj, args) -> {
				JSArray arr = new JSArray();
				for (Object a : args) arr.push(a);
				return arr;
			}));
			ctor.setSlot(5, makeMethod("from", 1, (cx, thisObj, args) -> {
				if (args.length == 0 || args[0] == null || args[0] == JSUndefined.INSTANCE) {
					throw makeTypeError("Cannot convert undefined or null to object");
				}
				Object     items = args[0];
				JSFunction mapFn = null;
				if (args.length > 1 && args[1] != JSUndefined.INSTANCE && args[1] != null) {
					if (args[1] instanceof JSFunction fn) {
						mapFn = fn;
					} else {
						throw makeTypeError("Array.from: mapfn is not callable");
					}
				}
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
				long    len            = toLength(O);
				long    k              = len - 1;
				Object  accumulator    = null;
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
				Object     O         = toObject(thisObj);
				JSFunction compareFn = null;
				if (args.length > 0 && args[0] != null && args[0] != JSUndefined.INSTANCE) {
					if (!(args[0] instanceof JSFunction)) {
						throw new RuntimeException("TypeError: The comparison function must be either a function or undefined");
					}
					compareFn = (JSFunction) args[0];
				}
				long len = toLength(O);
				if (len <= 1) return O;

				List<Object> definedItems   = new ArrayList<>();
				int          undefinedCount = 0;
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
							double d   = JSOps.toDouble(res);
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
				Object  thisArg = args.length > 1 ? args[1] : JSUndefined.INSTANCE;
				long    len     = toLength(O);
				JSArray result  = new JSArray((int) Math.min(len, 65536));
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
				long   len     = toLength(O);
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
				long   len     = toLength(O);
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
				long   len     = toLength(O);
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
				long   len     = toLength(O);
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
				long   len     = toLength(O);
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
				Object O   = toObject(thisObj);
				long   len = toLength(O);
				if (len == 0 || args.length == 0) return -1.0;
				Object searchElement = args[0];
				long   fromIndex     = 0;
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
				Object O   = toObject(thisObj);
				long   len = toLength(O);
				if (len == 0 || args.length == 0) return -1.0;
				Object searchElement = args[0];
				long   fromIndex     = len - 1;
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
				Object O   = toObject(thisObj);
				long   len = toLength(O);
				if (len == 0 || args.length == 0) return Boolean.FALSE;
				Object searchElement = args[0];
				long   fromIndex     = 0;
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
				Object O   = toObject(thisObj);
				long   len = toLength(O);
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
				Object O     = toObject(thisObj);
				long   len   = toLength(O);
				long   start = 0;
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
				Object O   = toObject(thisObj);
				long   len = toLength(O);
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

				int  insertCount = Math.max(0, args.length - 2);
				long newLen      = len - actualDeleteCount + insertCount;
				if (insertCount < actualDeleteCount) {
					for (long k = actualStart; k < len - actualDeleteCount; k++) {
						long from = k + actualDeleteCount;
						long to   = k + insertCount;
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
						long to   = k + insertCount - 1;
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
				Object  O      = toObject(thisObj);
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
				long   newLen = len - 1;
				Object val    = getProperty(O, newLen);
				deleteProperty(O, newLen);
				if (O instanceof JSObject jsObj) jsObj.put("length", (double) newLen);
				return val;
			}));

			proto.put("shift", makeMethod("shift", 0, (cx, thisObj, args) -> {
				Object O   = toObject(thisObj);
				long   len = toLength(O);
				if (len == 0) {
					if (O instanceof JSArray arr) { arr.setLength(0.0); } else if (O instanceof JSObject jsObj) {
						jsObj.put("length", 0.0);
					}
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
				if (O instanceof JSArray arr) { arr.setLength((double) newLen); } else if (O instanceof JSObject jsObj) {
					jsObj.put("length", (double) newLen);
				}
				return first;
			}));

			proto.put("unshift", makeMethod("unshift", 1, (cx, thisObj, args) -> {
				Object O        = toObject(thisObj);
				long   len      = toLength(O);
				int    argCount = args.length;
				if (argCount > 0) {
					for (long k = len; k > 0; k--) {
						long from = k - 1;
						long to   = k + argCount - 1;
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
				if (O instanceof JSArray arr) { arr.setLength((double) newLen); } else if (O instanceof JSObject jsObj) {
					jsObj.put("length", (double) newLen);
				}
				return (double) newLen;
			}));

			proto.put("reverse", makeMethod("reverse", 0, (cx, thisObj, args) -> {
				Object O      = toObject(thisObj);
				long   len    = toLength(O);
				long   middle = len / 2;
				for (long lower = 0; lower < middle; lower++) {
					long    upper       = len - lower - 1;
					boolean lowerExists = hasProperty(O, lower);
					boolean upperExists = hasProperty(O, upper);
					Object  lowerVal    = lowerExists ? getProperty(O, lower) : null;
					Object  upperVal    = upperExists ? getProperty(O, upper) : null;
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
				Object O     = toObject(thisObj);
				long   len   = toLength(O);
				Object value = args.length > 0 ? args[0] : JSUndefined.INSTANCE;
				long   start = 0;
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
				Object O     = toObject(thisObj);
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

		private static Object fastDenseReduce(JSContext cx, JSArray jsArr, JSFunction callback, Object[] args)
		 throws Throwable {
			long    len            = jsArr.length();
			int     k              = 0;
			Object  accumulator    = null;
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
			long    len            = toLength(O);
			long    k              = 0;
			Object  accumulator    = null;
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

		private static JSArray fastDenseFilter(JSContext cx, JSArray jsArr, JSFunction callback, Object thisArg)
		 throws Throwable {
			long    len    = jsArr.length();
			JSArray result = new JSArray();
			for (int i = 0; i < len; i++) {
				if (!jsArr.isDense() || i >= jsArr.denseSize) {
					for (long k = i; k < len; k++) {
						if (hasProperty(jsArr, k)) {
							Object kValue   = getProperty(jsArr, k);
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
			long    len    = toLength(O);
			JSArray result = new JSArray();
			for (long k = 0; k < len; k++) {
				if (hasProperty(O, k)) {
					Object kValue   = getProperty(O, k);
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
			if (obj instanceof JSArray arr) return arr.has(index);
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

	public static final ThreadLocal<JSContext> CURRENT = new ThreadLocal<>();

	public static JSContext current() {
		return CURRENT.get();
	}

	private final ArrayDeque<Runnable> microtaskQueue = new ArrayDeque<>();
	private final Object               microtaskLock  = new Object();

	public void queueMicrotask(Runnable task) {
		synchronized (microtaskLock) {
			microtaskQueue.add(task);
		}
	}

	public void drainMicrotasks() {
		JSContext old = CURRENT.get();
		CURRENT.set(this);
		try {
			while (true) {
				Runnable task;
				synchronized (microtaskLock) {
					task = microtaskQueue.poll();
				}
				if (task == null) break;
				try {
					task.run();
				} catch (Throwable ignored) {
				}
			}
		} finally {
			CURRENT.set(old);
		}
	}

	public JSContext() {
		// 100% 零成本实例化：按需懒加载所有 Built-in 对象，首调 0 类加载突发
	}

	public static class LazyBuiltins {
		public static final JSObject            OBJECT_PROTOTYPE  = LazyObject.OBJECT_PROTOTYPE;
		public static final JSObject            ARRAY_PROTOTYPE   = LazyArray.ARRAY_PROTOTYPE;
		public static final JSObjectConstructor OBJECT            = LazyObject.OBJECT;
		public static final JSArrayConstructor  ARRAY             = LazyArray.ARRAY;
		public static final JSObject            CONSOLE           = LazyConsole.CONSOLE;
		public static final JSObject            MATH              = LazyMath.MATH;
		public static final JSFunction          PRINT             = LazyMisc.PRINT;
		public static final JSFunction          IMPORT_CLASS      = LazyMisc.IMPORT_CLASS;
		public static final JSObject            PACKAGES          = LazyMisc.PACKAGES;
		public static final JSFunction          REGEXP            = LazyMisc.REGEXP;
		public static final JSObject            JAVA              = LazyMisc.JAVA;
		public static final JSObject            JAVA_PKG          = LazyMisc.JAVA_PKG;
		public static final JSObject            JAVAX_PKG         = LazyMisc.JAVAX_PKG;
		public static final JSObject            ERROR             = LazyErrors.ERROR;
		public static final JSObject            TYPE_ERROR        = LazyErrors.TYPE_ERROR;
		public static final JSObject            RANGE_ERROR       = LazyErrors.RANGE_ERROR;
		public static final JSObject            SYNTAX_ERROR      = LazyErrors.SYNTAX_ERROR;
		public static final JSObject            REFERENCE_ERROR   = LazyErrors.REFERENCE_ERROR;
		public static final JSObject            URI_ERROR         = LazyErrors.URI_ERROR;
		public static final JSObject            EVAL_ERROR        = LazyErrors.EVAL_ERROR;
		public static final JSObject            PROMISE_PROTOTYPE = LazyPromise.PROMISE_PROTOTYPE;
		public static final JSObject            PROMISE           = LazyPromise.PROMISE;
		public static final JSFunction          QUEUE_MICROTASK   = LazyPromise.QUEUE_MICROTASK;
	}

	public static JSOps.JSException makeTypeError(String message) {
		return new JSOps.JSException(LazyErrors.createErrorInstance(LazyErrors.TYPE_ERROR, message));
	}

	public static JSOps.JSException makeRangeError(String message) {
		return new JSOps.JSException(LazyErrors.createErrorInstance(LazyErrors.RANGE_ERROR, message));
	}

	public static class LazyErrors {
		public static final JSObject ERROR           = createErrorConstructor("Error");
		public static final JSObject TYPE_ERROR      = createErrorConstructor("TypeError");
		public static final JSObject RANGE_ERROR     = createErrorConstructor("RangeError");
		public static final JSObject SYNTAX_ERROR    = createErrorConstructor("SyntaxError");
		public static final JSObject REFERENCE_ERROR = createErrorConstructor("ReferenceError");
		public static final JSObject URI_ERROR       = createErrorConstructor("URIError");
		public static final JSObject EVAL_ERROR      = createErrorConstructor("EvalError");

		public static JSObject createErrorInstance(JSObject constructor, String message) {
			try {
				if (constructor instanceof JSFunction fn) {
					Object res = fn.call(null, null, new Object[]{message});
					if (res instanceof JSObject o) return o;
				}
			} catch (Throwable ignored) { }
			JSObject err = new JSObject();
			err.put("message", message);
			return err;
		}

		public static JSObject createErrorConstructor(String errorName) {
			JSObject proto = new JSObject();
			proto.put("name", errorName);
			proto.put("message", "");
			proto.put("toString", (JSFunction) (cx, thisObj, args) -> {
				if (thisObj instanceof JSObject o) {
					Object n       = o.get("name");
					Object m       = o.get("message");
					String nameStr = (n != JSUndefined.INSTANCE && n != null) ? JSOps.toStr(n) : errorName;
					String msgStr  = (m != JSUndefined.INSTANCE && m != null) ? JSOps.toStr(m) : "";
					return msgStr.isEmpty() ? nameStr : nameStr + ": " + msgStr;
				}
				return errorName;
			});

			class JSErrorConstructor extends JSObject implements JSFunction {
				public JSErrorConstructor() {
					super(LazyFunction.FUNCTION_PROTOTYPE);
					put("prototype", proto);
					put("name", errorName);
					put("length", 1);
					proto.put("constructor", this);
				}

				@Override
				public JSObject getPrototype() {
					JSObject p = super.getPrototype();
					return (p != null && p != LazyObject.OBJECT_PROTOTYPE) ? p : LazyFunction.FUNCTION_PROTOTYPE;
				}

				@Override
				public String toString() {
					return "function " + errorName + "() { [native code] }";
				}

				@Override
				public Object call(JSContext cx, Object thisObj, Object[] args) {
					JSObject err = (thisObj instanceof JSObject o && o.getPrototype() == proto) ? o : new JSObject(proto);
					err.put("name", errorName);
					if (args.length > 0 && args[0] != JSUndefined.INSTANCE && args[0] != null) {
						err.put("message", JSOps.toStr(args[0]));
					} else {
						err.put("message", "");
					}
					return err;
				}
			}

			return new JSErrorConstructor();
		}
	}

	public static class JSProxy extends JSObject {
		public Object  target;
		public Object  handler;
		public boolean revoked;

		public JSProxy(Object target, Object handler) {
			super(LazyObject.OBJECT_PROTOTYPE);
			this.target = target;
			this.handler = handler;
		}

		@Override
		public Object get(String key) {
			if (revoked) throw makeTypeError("Cannot perform 'get' on a revoked proxy");
			if (target instanceof JSObject jo) return jo.get(key);
			return super.get(key);
		}
	}

	public static class ProxyConstructor extends JSObject implements JSFunction {
		public ProxyConstructor() {
			super(LazyFunction.FUNCTION_PROTOTYPE);
			put("name", "Proxy");
			put("length", 2);
			put("revocable", makeMethod("revocable", 2, (cx, thisObj, args) -> {
				if (args.length < 2) throw makeTypeError("Cannot create proxy with less than 2 arguments");
				JSProxy  proxy  = new JSProxy(args[0], args[1]);
				JSObject result = new JSObject();
				result.put("proxy", proxy);
				result.put("revoke", makeMethod("revoke", 0, (c, t, a) -> {
					proxy.revoked = true;
					proxy.target = null;
					proxy.handler = null;
					return JSUndefined.INSTANCE;
				}));
				return result;
			}));
		}

		@Override
		public JSObject getPrototype() {
			JSObject p = super.getPrototype();
			return (p != null && p != LazyObject.OBJECT_PROTOTYPE) ? p : LazyFunction.FUNCTION_PROTOTYPE;
		}

		@Override
		public Object call(JSContext cx, Object thisObj, Object[] args) {
			if (args.length < 2) throw makeTypeError("Cannot create proxy with less than 2 arguments");
			return new JSProxy(args[0], args[1]);
		}
	}

	public static class LazyProxy {
		public static final JSObject PROXY = new ProxyConstructor();
	}

	public static class LazyReflect {
		public static final JSObject REFLECT = createReflect();

		private static JSObject createReflect() {
			JSObject reflect = new JSObject();
			reflect.put("construct", makeMethod("construct", 2, (cx, thisObj, args) -> {
				if (args.length < 2) throw makeTypeError("Reflect.construct requires at least 2 arguments");
				Object target = args[0];
				if (target instanceof JSBuiltinMethod || !(target instanceof JSFunction)) {
					throw makeTypeError("Target is not a constructor");
				}
				Object newTarget = args.length > 2 ? args[2] : target;
				if (newTarget instanceof JSBuiltinMethod || !(newTarget instanceof JSFunction)) {
					throw makeTypeError("newTarget is not a constructor");
				}
				Object[] constructArgs;
				if (args[1] instanceof JSArray arr) {
					constructArgs = arr.toArray();
				} else if (args[1] instanceof Object[] oa) {
					constructArgs = oa;
				} else {
					constructArgs = JSFunction.EMPTY_ARGS;
				}
				try {
					return JSLinker.newGeneric(target, constructArgs);
				} catch (Throwable t) {
					if (t instanceof RuntimeException re) throw re;
					throw new RuntimeException(t);
				}
			}));
			return reflect;
		}
	}

	public static class LazyPrimitiveConstructors {
		public static final JSObject BOOLEAN_PROTOTYPE = createBooleanPrototype();
		public static final JSObject BOOLEAN           = createBooleanConstructor(BOOLEAN_PROTOTYPE);

		public static final JSObject NUMBER_PROTOTYPE = createNumberPrototype();
		public static final JSObject NUMBER           = createNumberConstructor(NUMBER_PROTOTYPE);

		public static final JSObject STRING_PROTOTYPE = createStringPrototype();
		public static final JSObject STRING           = createStringConstructor(STRING_PROTOTYPE);

		private static JSObject createBooleanPrototype() {
			JSObject proto = new JSObject(LazyObject.OBJECT_PROTOTYPE);
			proto.put("name", "Boolean");
			proto.put("valueOf", makeMethod("valueOf", 0, (cx, thisObj, args) -> {
				if (thisObj instanceof Boolean b) return b;
				if (thisObj instanceof JSObject jo && jo.has("[[PrimitiveValue]]")) {
					return jo.get("[[PrimitiveValue]]");
				}
				throw new RuntimeException("TypeError: Boolean.prototype.valueOf requires that 'this' be a Boolean");
			}));
			proto.put("toString", makeMethod("toString", 0, (cx, thisObj, args) -> {
				if (thisObj instanceof Boolean b) return b.toString();
				if (thisObj instanceof JSObject jo && jo.has("[[PrimitiveValue]]")) {
					return JSOps.toStr(jo.get("[[PrimitiveValue]]"));
				}
				throw new RuntimeException("TypeError: Boolean.prototype.toString requires that 'this' be a Boolean");
			}));
			return proto;
		}

		private static JSObject createBooleanConstructor(JSObject proto) {
			return new JSBuiltinConstructor("Boolean", 1, proto, (cx, thisObj, args) -> {
				boolean b = args.length > 0 && JSOps.isTruthy(args[0]);
				if (thisObj instanceof JSObject jo && jo.getPrototype() == proto) {
					jo.put("[[PrimitiveValue]]", b);
					return jo;
				}
				return b ? Boolean.TRUE : Boolean.FALSE;
			});
		}

		private static JSObject createNumberPrototype() {
			JSObject proto = new JSObject(LazyObject.OBJECT_PROTOTYPE);
			proto.put("name", "Number");
			proto.put("valueOf", makeMethod("valueOf", 0, (cx, thisObj, args) -> {
				if (thisObj instanceof Number n) return n;
				if (thisObj instanceof JSObject jo && jo.has("[[PrimitiveValue]]")) {
					return jo.get("[[PrimitiveValue]]");
				}
				throw new RuntimeException("TypeError: Number.prototype.valueOf requires that 'this' be a Number");
			}));
			proto.put("toString", makeMethod("toString", 0, (cx, thisObj, args) -> {
				if (thisObj instanceof Number n) return JSOps.toStr(n);
				if (thisObj instanceof JSObject jo && jo.has("[[PrimitiveValue]]")) {
					return JSOps.toStr(jo.get("[[PrimitiveValue]]"));
				}
				throw new RuntimeException("TypeError: Number.prototype.toString requires that 'this' be a Number");
			}));
			return proto;
		}

		private static JSObject createNumberConstructor(JSObject proto) {
			JSObject ctor = new JSBuiltinConstructor("Number", 1, proto, (cx, thisObj, args) -> {
				double d = args.length > 0 ? JSOps.toDouble(args[0]) : 0.0;
				if (thisObj instanceof JSObject jo && jo.getPrototype() == proto) {
					jo.put("[[PrimitiveValue]]", d);
					return jo;
				}
				return d;
			});
			ctor.put("NaN", Double.NaN);
			ctor.put("POSITIVE_INFINITY", Double.POSITIVE_INFINITY);
			ctor.put("NEGATIVE_INFINITY", Double.NEGATIVE_INFINITY);
			ctor.put("MAX_VALUE", Double.MAX_VALUE);
			ctor.put("MIN_VALUE", Double.MIN_VALUE);
			return ctor;
		}

		private static JSObject createStringPrototype() {
			JSObject proto = new JSObject(LazyObject.OBJECT_PROTOTYPE);
			proto.put("name", "String");
			proto.put("valueOf", makeMethod("valueOf", 0, (cx, thisObj, args) -> {
				if (thisObj instanceof CharSequence s) return s.toString();
				if (thisObj instanceof JSObject jo && jo.has("[[PrimitiveValue]]")) {
					return jo.get("[[PrimitiveValue]]");
				}
				throw new RuntimeException("TypeError: String.prototype.valueOf requires that 'this' be a String");
			}));
			proto.put("toString", makeMethod("toString", 0, (cx, thisObj, args) -> {
				if (thisObj instanceof CharSequence s) return s.toString();
				if (thisObj instanceof JSObject jo && jo.has("[[PrimitiveValue]]")) {
					return JSOps.toStr(jo.get("[[PrimitiveValue]]"));
				}
				throw new RuntimeException("TypeError: String.prototype.toString requires that 'this' be a String");
			}));
			return proto;
		}

		private static JSObject createStringConstructor(JSObject proto) {
			return new JSBuiltinConstructor("String", 1, proto, (cx, thisObj, args) -> {
				String s = args.length > 0 ? JSOps.toStr(args[0]) : "";
				if (thisObj instanceof JSObject jo && jo.getPrototype() == proto) {
					jo.put("[[PrimitiveValue]]", s);
					return jo;
				}
				return s;
			});
		}
	}

	public static class JSDate extends JSObject {
		private double time;

		public JSDate(double time, JSObject prototype) {
			super(prototype);
			this.time = time;
		}

		public double getTime() {
			return time;
		}

		public void setTime(double time) {
			this.time = time;
		}

		@Override
		public String toString() {
			if (Double.isNaN(time)) return "Invalid Date";
			return new java.util.Date((long) time).toString();
		}
	}

	public static class LazyDate {
		private static final List<String> DATE_PROTO_PROPS = List.of(
		 "constructor", "getTime", "valueOf", "toString", "toUTCString", "toGMTString",
		 "toISOString", "toJSON", "toDateString", "toTimeString",
		 "getFullYear", "getUTCFullYear", "getMonth", "getUTCMonth", "getDate", "getUTCDate",
		 "getDay", "getUTCDay", "getHours", "getUTCHours", "getMinutes", "getUTCMinutes",
		 "getSeconds", "getUTCSeconds", "getMilliseconds", "getUTCMilliseconds", "getTimezoneOffset",
		 "setTime", "setMilliseconds", "setUTCMilliseconds", "setSeconds", "setUTCSeconds",
		 "setMinutes", "setUTCMinutes", "setHours", "setUTCHours", "setDate", "setUTCDate",
		 "setMonth", "setUTCMonth", "setFullYear", "setUTCFullYear"
		);

		private static final DateTimeFormatter UTC_FORMATTER = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

		public static final JSObject DATE_PROTOTYPE = createDatePrototype();
		public static final JSObject DATE           = createDateConstructor(DATE_PROTOTYPE);

		private static JSObject createDatePrototype() {
			JSShape  shape = JSShape.createStaticPrototypeShape(LazyObject.OBJECT_PROTOTYPE.shape, DATE_PROTO_PROPS);
			JSObject proto = new JSObject(shape, LazyObject.OBJECT_PROTOTYPE);

			proto.put("getTime", makeMethod("getTime", 0, (cx, thisObj, args) -> {
				if (thisObj instanceof JSDate d) return d.getTime();
				throw makeTypeError("this is not a Date object");
			}));
			proto.put("valueOf", makeMethod("valueOf", 0, (cx, thisObj, args) -> {
				if (thisObj instanceof JSDate d) return d.getTime();
				throw makeTypeError("this is not a Date object");
			}));
			proto.put("toString", makeMethod("toString", 0, (cx, thisObj, args) -> {
				if (thisObj instanceof JSDate d) return d.toString();
				throw makeTypeError("this is not a Date object");
			}));
			proto.put("toDateString", makeMethod("toDateString", 0, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return "Invalid Date";
				Calendar cal = Calendar.getInstance();
				cal.setTimeInMillis((long) t);
				return String.format(Locale.US, "%ta %tb %02d %tY", cal, cal, cal.get(Calendar.DAY_OF_MONTH), cal);
			}));
			proto.put("toTimeString", makeMethod("toTimeString", 0, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return "Invalid Date";
				Calendar cal = Calendar.getInstance();
				cal.setTimeInMillis((long) t);
				return String.format(Locale.US, "%02d:%02d:%02d GMT%tz", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND), cal);
			}));
			JSBuiltinMethod toUTCStringMethod = makeMethod("toUTCString", 0, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return "Invalid Date";
				Instant instant = Instant.ofEpochMilli((long) t);
				return UTC_FORMATTER.format(instant);
			});
			proto.put("toUTCString", toUTCStringMethod);
			proto.put("toGMTString", toUTCStringMethod);

			proto.put("toISOString", makeMethod("toISOString", 0, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) throw makeRangeError("Invalid time value");
				return Instant.ofEpochMilli((long) t).toString();
			}));
			proto.put("toJSON", makeMethod("toJSON", 1, (cx, thisObj, args) -> {
				if (thisObj instanceof JSDate d) {
					double t = d.getTime();
					if (Double.isNaN(t)) return null;
					return Instant.ofEpochMilli((long) t).toString();
				}
				if (thisObj instanceof JSObject jo) {
					Object toISO = jo.get("toISOString");
					if (toISO instanceof JSFunction fn) {
						return fn.call(cx, thisObj, JSFunction.EMPTY_ARGS);
					}
				}
				throw makeTypeError("this is not a Date object");
			}));
			proto.put("getFullYear", makeMethod("getFullYear", 0, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance();
				cal.setTimeInMillis((long) t);
				return (double) cal.get(Calendar.YEAR);
			}));
			proto.put("getUTCFullYear", makeMethod("getUTCFullYear", 0, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
				cal.setTimeInMillis((long) t);
				return (double) cal.get(Calendar.YEAR);
			}));
			proto.put("getMonth", makeMethod("getMonth", 0, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance();
				cal.setTimeInMillis((long) t);
				return (double) cal.get(Calendar.MONTH);
			}));
			proto.put("getUTCMonth", makeMethod("getUTCMonth", 0, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
				cal.setTimeInMillis((long) t);
				return (double) cal.get(Calendar.MONTH);
			}));
			proto.put("getDate", makeMethod("getDate", 0, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance();
				cal.setTimeInMillis((long) t);
				return (double) cal.get(Calendar.DAY_OF_MONTH);
			}));
			proto.put("getUTCDate", makeMethod("getUTCDate", 0, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
				cal.setTimeInMillis((long) t);
				return (double) cal.get(Calendar.DAY_OF_MONTH);
			}));
			proto.put("getDay", makeMethod("getDay", 0, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance();
				cal.setTimeInMillis((long) t);
				return (double) (cal.get(Calendar.DAY_OF_WEEK) - 1);
			}));
			proto.put("getUTCDay", makeMethod("getUTCDay", 0, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
				cal.setTimeInMillis((long) t);
				return (double) (cal.get(Calendar.DAY_OF_WEEK) - 1);
			}));
			proto.put("getHours", makeMethod("getHours", 0, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance();
				cal.setTimeInMillis((long) t);
				return (double) cal.get(Calendar.HOUR_OF_DAY);
			}));
			proto.put("getUTCHours", makeMethod("getUTCHours", 0, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
				cal.setTimeInMillis((long) t);
				return (double) cal.get(Calendar.HOUR_OF_DAY);
			}));
			proto.put("getMinutes", makeMethod("getMinutes", 0, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance();
				cal.setTimeInMillis((long) t);
				return (double) cal.get(Calendar.MINUTE);
			}));
			proto.put("getUTCMinutes", makeMethod("getUTCMinutes", 0, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
				cal.setTimeInMillis((long) t);
				return (double) cal.get(Calendar.MINUTE);
			}));
			proto.put("getSeconds", makeMethod("getSeconds", 0, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance();
				cal.setTimeInMillis((long) t);
				return (double) cal.get(Calendar.SECOND);
			}));
			proto.put("getUTCSeconds", makeMethod("getUTCSeconds", 0, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
				cal.setTimeInMillis((long) t);
				return (double) cal.get(Calendar.SECOND);
			}));
			proto.put("getMilliseconds", makeMethod("getMilliseconds", 0, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance();
				cal.setTimeInMillis((long) t);
				return (double) cal.get(Calendar.MILLISECOND);
			}));
			proto.put("getUTCMilliseconds", makeMethod("getUTCMilliseconds", 0, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
				cal.setTimeInMillis((long) t);
				return (double) cal.get(Calendar.MILLISECOND);
			}));
			proto.put("getTimezoneOffset", makeMethod("getTimezoneOffset", 0, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				TimeZone tz = TimeZone.getDefault();
				return (double) (-tz.getOffset((long) t) / 60000);
			}));
			proto.put("setTime", makeMethod("setTime", 1, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = args.length > 0 ? JSOps.toDouble(args[0]) : Double.NaN;
				d.setTime(t);
				return t;
			}));
			proto.put("setMilliseconds", makeMethod("setMilliseconds", 1, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance();
				cal.setTimeInMillis((long) t);
				if (args.length > 0) cal.set(Calendar.MILLISECOND, JSOps.toInt(args[0]));
				double newT = (double) cal.getTimeInMillis();
				d.setTime(newT);
				return newT;
			}));
			proto.put("setUTCMilliseconds", makeMethod("setUTCMilliseconds", 1, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
				cal.setTimeInMillis((long) t);
				if (args.length > 0) cal.set(Calendar.MILLISECOND, JSOps.toInt(args[0]));
				double newT = (double) cal.getTimeInMillis();
				d.setTime(newT);
				return newT;
			}));
			proto.put("setSeconds", makeMethod("setSeconds", 2, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance();
				cal.setTimeInMillis((long) t);
				if (args.length > 0) cal.set(Calendar.SECOND, JSOps.toInt(args[0]));
				if (args.length > 1) cal.set(Calendar.MILLISECOND, JSOps.toInt(args[1]));
				double newT = (double) cal.getTimeInMillis();
				d.setTime(newT);
				return newT;
			}));
			proto.put("setUTCSeconds", makeMethod("setUTCSeconds", 2, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
				cal.setTimeInMillis((long) t);
				if (args.length > 0) cal.set(Calendar.SECOND, JSOps.toInt(args[0]));
				if (args.length > 1) cal.set(Calendar.MILLISECOND, JSOps.toInt(args[1]));
				double newT = (double) cal.getTimeInMillis();
				d.setTime(newT);
				return newT;
			}));
			proto.put("setMinutes", makeMethod("setMinutes", 3, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance();
				cal.setTimeInMillis((long) t);
				if (args.length > 0) cal.set(Calendar.MINUTE, JSOps.toInt(args[0]));
				if (args.length > 1) cal.set(Calendar.SECOND, JSOps.toInt(args[1]));
				if (args.length > 2) cal.set(Calendar.MILLISECOND, JSOps.toInt(args[2]));
				double newT = (double) cal.getTimeInMillis();
				d.setTime(newT);
				return newT;
			}));
			proto.put("setUTCMinutes", makeMethod("setUTCMinutes", 3, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
				cal.setTimeInMillis((long) t);
				if (args.length > 0) cal.set(Calendar.MINUTE, JSOps.toInt(args[0]));
				if (args.length > 1) cal.set(Calendar.SECOND, JSOps.toInt(args[1]));
				if (args.length > 2) cal.set(Calendar.MILLISECOND, JSOps.toInt(args[2]));
				double newT = (double) cal.getTimeInMillis();
				d.setTime(newT);
				return newT;
			}));
			proto.put("setHours", makeMethod("setHours", 4, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance();
				cal.setTimeInMillis((long) t);
				if (args.length > 0) cal.set(Calendar.HOUR_OF_DAY, JSOps.toInt(args[0]));
				if (args.length > 1) cal.set(Calendar.MINUTE, JSOps.toInt(args[1]));
				if (args.length > 2) cal.set(Calendar.SECOND, JSOps.toInt(args[2]));
				if (args.length > 3) cal.set(Calendar.MILLISECOND, JSOps.toInt(args[3]));
				double newT = (double) cal.getTimeInMillis();
				d.setTime(newT);
				return newT;
			}));
			proto.put("setUTCHours", makeMethod("setUTCHours", 4, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
				cal.setTimeInMillis((long) t);
				if (args.length > 0) cal.set(Calendar.HOUR_OF_DAY, JSOps.toInt(args[0]));
				if (args.length > 1) cal.set(Calendar.MINUTE, JSOps.toInt(args[1]));
				if (args.length > 2) cal.set(Calendar.SECOND, JSOps.toInt(args[2]));
				if (args.length > 3) cal.set(Calendar.MILLISECOND, JSOps.toInt(args[3]));
				double newT = (double) cal.getTimeInMillis();
				d.setTime(newT);
				return newT;
			}));
			proto.put("setDate", makeMethod("setDate", 1, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance();
				cal.setTimeInMillis((long) t);
				if (args.length > 0) cal.set(Calendar.DAY_OF_MONTH, JSOps.toInt(args[0]));
				double newT = (double) cal.getTimeInMillis();
				d.setTime(newT);
				return newT;
			}));
			proto.put("setUTCDate", makeMethod("setUTCDate", 1, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
				cal.setTimeInMillis((long) t);
				if (args.length > 0) cal.set(Calendar.DAY_OF_MONTH, JSOps.toInt(args[0]));
				double newT = (double) cal.getTimeInMillis();
				d.setTime(newT);
				return newT;
			}));
			proto.put("setMonth", makeMethod("setMonth", 2, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance();
				cal.setTimeInMillis((long) t);
				if (args.length > 0) cal.set(Calendar.MONTH, JSOps.toInt(args[0]));
				if (args.length > 1) cal.set(Calendar.DAY_OF_MONTH, JSOps.toInt(args[1]));
				double newT = (double) cal.getTimeInMillis();
				d.setTime(newT);
				return newT;
			}));
			proto.put("setUTCMonth", makeMethod("setUTCMonth", 2, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				if (Double.isNaN(t)) return Double.NaN;
				Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
				cal.setTimeInMillis((long) t);
				if (args.length > 0) cal.set(Calendar.MONTH, JSOps.toInt(args[0]));
				if (args.length > 1) cal.set(Calendar.DAY_OF_MONTH, JSOps.toInt(args[1]));
				double newT = (double) cal.getTimeInMillis();
				d.setTime(newT);
				return newT;
			}));
			proto.put("setFullYear", makeMethod("setFullYear", 3, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				Calendar cal = Calendar.getInstance();
				if (!Double.isNaN(t)) cal.setTimeInMillis((long) t);
				if (args.length > 0) cal.set(Calendar.YEAR, JSOps.toInt(args[0]));
				if (args.length > 1) cal.set(Calendar.MONTH, JSOps.toInt(args[1]));
				if (args.length > 2) cal.set(Calendar.DAY_OF_MONTH, JSOps.toInt(args[2]));
				double newT = (double) cal.getTimeInMillis();
				d.setTime(newT);
				return newT;
			}));
			proto.put("setUTCFullYear", makeMethod("setUTCFullYear", 3, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSDate d)) throw makeTypeError("this is not a Date object");
				double t = d.getTime();
				Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
				if (!Double.isNaN(t)) cal.setTimeInMillis((long) t);
				if (args.length > 0) cal.set(Calendar.YEAR, JSOps.toInt(args[0]));
				if (args.length > 1) cal.set(Calendar.MONTH, JSOps.toInt(args[1]));
				if (args.length > 2) cal.set(Calendar.DAY_OF_MONTH, JSOps.toInt(args[2]));
				double newT = (double) cal.getTimeInMillis();
				d.setTime(newT);
				return newT;
			}));
			return proto;
		}

		private static JSObject createDateConstructor(JSObject proto) {
			JSBuiltinConstructor ctor = new JSBuiltinConstructor("Date", 7, proto, (cx, thisObj, args) -> {
				double time;
				if (args.length == 0) {
					time = (double) System.currentTimeMillis();
				} else if (args.length == 1) {
					time = JSOps.toDouble(args[0]);
				} else {
					int                year  = JSOps.toInt(args[0]);
					int                month = JSOps.toInt(args[1]);
					int                day   = args.length > 2 ? JSOps.toInt(args[2]) : 1;
					int                hour  = args.length > 3 ? JSOps.toInt(args[3]) : 0;
					int                min   = args.length > 4 ? JSOps.toInt(args[4]) : 0;
					int                sec   = args.length > 5 ? JSOps.toInt(args[5]) : 0;
					int                ms    = args.length > 6 ? JSOps.toInt(args[6]) : 0;
					Calendar           cal   = Calendar.getInstance();
					cal.set(year < 100 ? 1900 + year : year, month, day, hour, min, sec);
					cal.set(Calendar.MILLISECOND, ms);
					time = (double) cal.getTimeInMillis();
				}
				if (thisObj == null || thisObj == JSUndefined.INSTANCE || thisObj instanceof JSContext.JSGlobalThis) {
					return new java.util.Date((long) time).toString();
				}
				if (thisObj instanceof JSDate d) {
					d.setTime(time);
					return d;
				}
				return new JSDate(time, proto);
			});
			proto.put("constructor", ctor);
			ctor.put("now", makeMethod("now", 0, (cx, thisObj, args) -> (double) System.currentTimeMillis()));
			ctor.put("parse", makeMethod("parse", 1, (cx, thisObj, args) -> {
				if (args.length == 0) return Double.NaN;
				String s = JSOps.toStr(args[0]);
				try {
					return (double) Instant.parse(s).toEpochMilli();
				} catch (Exception ignored) {
					try {
						return (double) java.util.Date.parse(s);
					} catch (Exception e) {
						return Double.NaN;
					}
				}
			}));
			ctor.put("UTC", makeMethod("UTC", 7, (cx, thisObj, args) -> {
				if (args.length == 0) return Double.NaN;
				int year = JSOps.toInt(args[0]);
				int month = args.length > 1 ? JSOps.toInt(args[1]) : 0;
				int day = args.length > 2 ? JSOps.toInt(args[2]) : 1;
				int hour = args.length > 3 ? JSOps.toInt(args[3]) : 0;
				int min = args.length > 4 ? JSOps.toInt(args[4]) : 0;
				int sec = args.length > 5 ? JSOps.toInt(args[5]) : 0;
				int ms = args.length > 6 ? JSOps.toInt(args[6]) : 0;
				Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
				cal.set(year < 100 ? 1900 + year : year, month, day, hour, min, sec);
				cal.set(Calendar.MILLISECOND, ms);
				return (double) cal.getTimeInMillis();
			}));
			return ctor;
		}
	}

	public static class LazyFunction {
		public static final JSObject FUNCTION_PROTOTYPE = createFunctionPrototype();
		public static final JSObject FUNCTION           = createFunctionConstructor(FUNCTION_PROTOTYPE);

		private static JSObject createFunctionPrototype() {
			JSObject proto = new JSObject(LazyObject.OBJECT_PROTOTYPE);
			proto.put("name", "");
			proto.put("length", 0);
			proto.put("call", makeMethod("call", 1, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSFunction fn)) {
					throw new RuntimeException("TypeError: Function.prototype.call called on non-function");
				}
				Object   newThis = args.length > 0 ? args[0] : JSUndefined.INSTANCE;
				Object[] newArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : JSFunction.EMPTY_ARGS;
				return fn.call(cx, newThis, newArgs);
			}));
			proto.put("apply", makeMethod("apply", 2, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSFunction fn)) {
					throw new RuntimeException("TypeError: Function.prototype.apply called on non-function");
				}
				Object   newThis = args.length > 0 ? args[0] : JSUndefined.INSTANCE;
				Object[] newArgs;
				if (args.length > 1 && args[1] != null && args[1] != JSUndefined.INSTANCE) {
					if (args[1] instanceof JSArray arr) {
						newArgs = arr.toArray();
					} else if (args[1] instanceof Object[] oa) {
						newArgs = oa;
					} else {
						newArgs = JSFunction.EMPTY_ARGS;
					}
				} else {
					newArgs = JSFunction.EMPTY_ARGS;
				}
				return fn.call(cx, newThis, newArgs);
			}));
			proto.put("bind", makeMethod("bind", 1, (cx, thisObj, args) -> {
				if (!(thisObj instanceof JSFunction fn)) {
					throw new RuntimeException("TypeError: Function.prototype.bind called on non-function");
				}
				Object   boundThis = args.length > 0 ? args[0] : JSUndefined.INSTANCE;
				Object[] boundArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : JSFunction.EMPTY_ARGS;
				return new BoundFunction(fn, boundThis, boundArgs, proto);
			}));
			proto.put("toString", makeMethod("toString", 0, (cx, thisObj, args) -> {
				if (thisObj instanceof JSFunction) {
					return "function () { [native code] }";
				}
				throw new RuntimeException("TypeError: Function.prototype.toString requires that 'this' be a Function");
			}));
			return proto;
		}

		private static JSObject createFunctionConstructor(JSObject proto) {
			return new JSBuiltinConstructor("Function", 1, proto, (cx, thisObj, args) -> {
				StringBuilder sb = new StringBuilder("function anonymous(");
				for (int i = 0; i < args.length - 1; i++) {
					if (i > 0) sb.append(", ");
					sb.append(JSOps.toStr(args[i]));
				}
				sb.append(") {\n");
				if (args.length > 0) sb.append(JSOps.toStr(args[args.length - 1]));
				sb.append("\n}");
				JSScript script = JSCompiler.compile(sb.toString());
				return script.run(cx);
			});
		}
	}

	public static class LazyPromise {
		public static final JSObject   PROMISE_PROTOTYPE = createPromisePrototype();
		public static final JSObject   PROMISE           = createPromiseConstructor(PROMISE_PROTOTYPE);
		public static final JSFunction QUEUE_MICROTASK   = (cx, thisObj, args) -> {
			if (args.length > 0 && args[0] instanceof JSFunction fn) {
				JSContext current = cx != null ? cx : JSContext.current();
				if (current != null) {
					current.queueMicrotask(() -> {
						try {
							fn.call0(current, null);
						} catch (Throwable ignored) {
						}
					});
				}
			}
			return JSUndefined.INSTANCE;
		};

		private static JSObject createPromisePrototype() {
			JSObject proto = new JSObject(LazyObject.OBJECT_PROTOTYPE);
			proto.put("name", "Promise");
			proto.put("then", makeMethod("then", 2, (cx, thisObj, args) -> {
				if (thisObj instanceof JSPromise p) {
					JSContext current = cx != null ? cx : (p.cx != null ? p.cx : JSContext.current());
					Object onF = args.length > 0 ? args[0] : null;
					Object onR = args.length > 1 ? args[1] : null;
					return p.then(current, onF, onR);
				}
				throw makeTypeError("Promise.prototype.then called on non-promise");
			}));
			proto.put("catch", makeMethod("catch", 1, (cx, thisObj, args) -> {
				if (thisObj instanceof JSPromise p) {
					JSContext current = cx != null ? cx : (p.cx != null ? p.cx : JSContext.current());
					Object onR = args.length > 0 ? args[0] : null;
					return p.catch_(current, onR);
				}
				throw makeTypeError("Promise.prototype.catch called on non-promise");
			}));
			proto.put("finally", makeMethod("finally", 1, (cx, thisObj, args) -> {
				if (thisObj instanceof JSPromise p) {
					JSContext current = cx != null ? cx : (p.cx != null ? p.cx : JSContext.current());
					Object onFin = args.length > 0 ? args[0] : null;
					return p.finally_(current, onFin);
				}
				throw makeTypeError("Promise.prototype.finally called on non-promise");
			}));
			return proto;
		}

		private static JSObject createPromiseConstructor(JSObject proto) {
			JSBuiltinConstructor ctor = new JSBuiltinConstructor("Promise", 1, proto, (cx, thisObj, args) -> {
				if (args.length == 0 || !(args[0] instanceof JSFunction executor)) {
					throw makeTypeError("Promise resolver undefined is not a function");
				}
				JSContext current = cx != null ? cx : JSContext.current();
				JSPromise promise = (thisObj instanceof JSPromise p && p.getPrototype() == proto) ? p : new JSPromise(current, proto);
				java.util.concurrent.atomic.AtomicBoolean called = new java.util.concurrent.atomic.AtomicBoolean(false);
				JSFunction resolveFn = (c, self, a) -> {
					if (called.compareAndSet(false, true)) {
						promise.resolve(a.length > 0 ? a[0] : JSUndefined.INSTANCE);
					}
					return JSUndefined.INSTANCE;
				};
				JSFunction rejectFn = (c, self, a) -> {
					if (called.compareAndSet(false, true)) {
						promise.reject(a.length > 0 ? a[0] : JSUndefined.INSTANCE);
					}
					return JSUndefined.INSTANCE;
				};
				try {
					executor.call2(current, null, resolveFn, rejectFn);
				} catch (Throwable t) {
					if (called.compareAndSet(false, true)) {
						promise.reject(t instanceof JSOps.JSException je ? je.value : t);
					}
				}
				return promise;
			});

			ctor.put("resolve", makeMethod("resolve", 1, (cx, thisObj, args) -> {
				JSContext current = cx != null ? cx : JSContext.current();
				Object val = args.length > 0 ? args[0] : JSUndefined.INSTANCE;
				return JSPromise.resolve(current, val);
			}));
			ctor.put("reject", makeMethod("reject", 1, (cx, thisObj, args) -> {
				JSContext current = cx != null ? cx : JSContext.current();
				Object reason = args.length > 0 ? args[0] : JSUndefined.INSTANCE;
				return JSPromise.reject(current, reason);
			}));
			ctor.put("all", makeMethod("all", 1, (cx, thisObj, args) -> {
				JSContext current = cx != null ? cx : JSContext.current();
				Object iterable = args.length > 0 ? args[0] : null;
				return JSPromise.all(current, iterable);
			}));
			ctor.put("allSettled", makeMethod("allSettled", 1, (cx, thisObj, args) -> {
				JSContext current = cx != null ? cx : JSContext.current();
				Object iterable = args.length > 0 ? args[0] : null;
				return JSPromise.allSettled(current, iterable);
			}));
			ctor.put("race", makeMethod("race", 1, (cx, thisObj, args) -> {
				JSContext current = cx != null ? cx : JSContext.current();
				Object iterable = args.length > 0 ? args[0] : null;
				return JSPromise.race(current, iterable);
			}));
			ctor.put("any", makeMethod("any", 1, (cx, thisObj, args) -> {
				JSContext current = cx != null ? cx : JSContext.current();
				Object iterable = args.length > 0 ? args[0] : null;
				return JSPromise.any(current, iterable);
			}));
			return ctor;
		}
	}

	public static class JSGlobalThis extends JSObject {
		private final JSContext cx;

		public JSGlobalThis(JSContext cx) {
			super(LazyObject.OBJECT_PROTOTYPE);
			this.cx = cx;
			put("globalThis", this);
			put("window", this);
			put("global", this);
		}

		@Override
		public Object get(String name) {
			Object val = cx.get(name);
			if (val != JSUndefined.INSTANCE) return val;
			return super.get(name);
		}

		@Override
		public void put(String name, Object value) {
			cx.set(name, value);
		}
	}

	public static class Dollar262 extends JSObject {
		public Dollar262(JSContext cx) {
			super(LazyObject.OBJECT_PROTOTYPE);
			put("global", cx.getGlobalThis());
			put("destroy", makeMethod("destroy", 0, (c, thisObj, args) -> JSUndefined.INSTANCE));
			put("gc", makeMethod("gc", 0, (c, thisObj, args) -> {
				System.gc();
				return JSUndefined.INSTANCE;
			}));
			put("evalScript", makeMethod("evalScript", 1, (c, thisObj, args) -> {
				if (args.length == 0 || args[0] == null) return JSUndefined.INSTANCE;
				String src = JSOps.toStr(args[0]);
				try {
					JSScript script = JSCompiler.compile(src);
					Object   val    = script.run(c);
					JSObject res    = new JSObject();
					res.put("type", "normal");
					res.put("value", val);
					return res;
				} catch (Throwable t) {
					JSObject res = new JSObject();
					res.put("type", "throw");
					res.put("value", t);
					return res;
				}
			}));
			put("getGlobal", makeMethod("getGlobal", 1, (c, thisObj, args) -> {
				String name = args.length > 0 ? JSOps.toStr(args[0]) : "";
				return c.get(name);
			}));
			put("setGlobal", makeMethod("setGlobal", 2, (c, thisObj, args) -> {
				String name = args.length > 0 ? JSOps.toStr(args[0]) : "";
				Object val  = args.length > 1 ? args[1] : JSUndefined.INSTANCE;
				c.set(name, val);
				return JSUndefined.INSTANCE;
			}));
			put("createRealm", makeMethod("createRealm", 0, (c, thisObj, args) -> {
				JSContext realmCtx = new JSContext();
				return realmCtx.get("$262");
			}));
			put("drainMicrotasks", makeMethod("drainMicrotasks", 0, (c, thisObj, args) -> {
				c.drainMicrotasks();
				return JSUndefined.INSTANCE;
			}));
		}
	}

	private JSObject globalThisObject;

	public synchronized JSObject getGlobalThis() {
		if (globalThisObject == null) {
			globalThisObject = new JSGlobalThis(this);
		}
		return globalThisObject;
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
		} else if (slot == SLOT_JAVA) {
			val = LazyMisc.JAVA;
		} else if (slot == SLOT_JAVA_PKG) {
			val = LazyMisc.JAVA_PKG;
		} else if (slot == SLOT_JAVAX_PKG) {
			val = LazyMisc.JAVAX_PKG;
		} else if (slot == SLOT_ERROR) {
			val = LazyErrors.ERROR;
		} else if (slot == SLOT_TYPE_ERROR) {
			val = LazyErrors.TYPE_ERROR;
		} else if (slot == SLOT_RANGE_ERROR) {
			val = LazyErrors.RANGE_ERROR;
		} else if (slot == SLOT_SYNTAX_ERROR) {
			val = LazyErrors.SYNTAX_ERROR;
		} else if (slot == SLOT_REFERENCE_ERROR) {
			val = LazyErrors.REFERENCE_ERROR;
		} else if (slot == SLOT_URI_ERROR) {
			val = LazyErrors.URI_ERROR;
		} else if (slot == SLOT_EVAL_ERROR) {
			val = LazyErrors.EVAL_ERROR;
		} else if (slot == SLOT_BOOLEAN) {
			val = LazyPrimitiveConstructors.BOOLEAN;
		} else if (slot == SLOT_NUMBER) {
			val = LazyPrimitiveConstructors.NUMBER;
		} else if (slot == SLOT_STRING) {
			val = LazyPrimitiveConstructors.STRING;
		} else if (slot == SLOT_FUNCTION) {
			val = LazyFunction.FUNCTION;
		} else if (slot == SLOT_PROXY) {
			val = LazyProxy.PROXY;
		} else if (slot == SLOT_REFLECT) {
			val = LazyReflect.REFLECT;
		} else if (slot == SLOT_DATE) {
			val = LazyDate.DATE;
		} else if (slot == SLOT_PROMISE) {
			val = LazyBuiltins.PROMISE;
		} else if (slot == SLOT_QUEUE_MICROTASK) {
			val = LazyBuiltins.QUEUE_MICROTASK;
		} else if (slot == SLOT_GLOBAL_THIS) {
			val = getGlobalThis();
		} else if (slot == SLOT_DOLLAR_262) {
			val = new Dollar262(this);
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
		synchronized (globals) {
			globals.put(name, value == null ? NULL_VALUE : value);
		}
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
		Object val;
		synchronized (globals) {
			val = globals.get(name);
		}
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
				synchronized (globals) {
					globals.put(name, c);
				}
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
		JSContext old = CURRENT.get();
		CURRENT.set(this);
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
		} finally {
			CURRENT.set(old);
		}
	}
}
