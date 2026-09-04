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

	private static class LazyBuiltins {
		static final JSFunction PRINT = (cx, thisObj, args) -> {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < args.length; i++) {
				if (i > 0) sb.append(" ");
				sb.append(args[i]);
			}
			System.out.println(sb);
			return JSUndefined.INSTANCE;
		};

		static final JSObject CONSOLE = createConsole();
		private static JSObject createConsole() {
			JSObject c = new JSObject();
			c.put("log", PRINT);
			return c;
		}

		static final JSObject MATH = createMath();
		private static JSObject createMath() {
			JSObject math = new JSObject();
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

	private Object resolveLazyGlobal(int slot) {
		Object val = null;
		if (slot == SLOT_NAN) { val = Double.NaN; } else if (slot == SLOT_INFINITY) {
			val = Double.POSITIVE_INFINITY;
		} else if (slot == SLOT_UNDEFINED) {
			val = JSUndefined.INSTANCE;
		} else if (slot == SLOT_JSOPS) {
			val = JSOps.class;
		} else if (slot == SLOT_PRINT) {
			val = LazyBuiltins.PRINT;
		} else if (slot == SLOT_CONSOLE) {
			val = LazyBuiltins.CONSOLE;
		} else if (slot == SLOT_MATH) {
			val = LazyBuiltins.MATH;
		} else if (slot == SLOT_IMPORT_CLASS) {
			val = LazyBuiltins.IMPORT_CLASS;
		} else if (slot == SLOT_PACKAGES) {
			val = LazyBuiltins.PACKAGES;
		} else if (slot == SLOT_REGEXP)
			val = LazyBuiltins.REGEXP;

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
