package hope.magic.js.runtime;

import hope.magic.js.ast.Node;
import hope.magic.js.ast.Token;
import hope.magic.js.compiler.JSCompiler;
import hope.magic.js.parser.JSLexer;
import hope.magic.js.parser.JSParser;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class JSContext {
	private static final ConcurrentHashMap<String, Integer> GLOBAL_SLOT_REGISTRY = new ConcurrentHashMap<>();
	private static final AtomicInteger NEXT_GLOBAL_SLOT = new AtomicInteger(0);

	public static int getGlobalSlot(String name) {
		return GLOBAL_SLOT_REGISTRY.computeIfAbsent(name, k -> NEXT_GLOBAL_SLOT.getAndIncrement());
	}

	private static final Object NULL_VALUE = new Object();

	public volatile Object[] globalSlots = new Object[64];
	private final ConcurrentHashMap<String, Object> globals = new ConcurrentHashMap<>();

	public JSContext() {
		initStandardGlobals();
	}

	private synchronized void ensureGlobalSlotCapacity(int slot) {
		if (slot >= globalSlots.length) {
			globalSlots = Arrays.copyOf(globalSlots, Math.max(globalSlots.length * 2, slot + 1));
		}
	}

	private void initStandardGlobals() {
		// 标准全局变量
		set("NaN", Double.NaN);
		set("Infinity", Double.POSITIVE_INFINITY);
		set("undefined", JSUndefined.INSTANCE);
		set("JSOps", JSOps.class);

		// print / console.log
		set("print", (JSFunction) (cx, thisObj, args) -> {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < args.length; i++) {
				if (i > 0) sb.append(" ");
				sb.append(args[i]);
			}
			System.out.println(sb);
			return JSUndefined.INSTANCE;
		});

		JSObject console = new JSObject();
		console.put("log", get("print"));
		set("console", console);

		// Math 对象
		JSObject math = new JSObject();
		math.put("PI", Math.PI);
		math.put("E", Math.E);
		math.put("abs", (JSFunction) (cx, thisObj, args) -> Math.abs(JSOps.toDouble(args[0])));
		math.put("max", (JSFunction) (cx, thisObj, args) -> Math.max(JSOps.toDouble(args[0]), JSOps.toDouble(args[1])));
		math.put("min", (JSFunction) (cx, thisObj, args) -> Math.min(JSOps.toDouble(args[0]), JSOps.toDouble(args[1])));
		math.put("sqrt", (JSFunction) (cx, thisObj, args) -> Math.sqrt(JSOps.toDouble(args[0])));
		math.put("floor", (JSFunction) (cx, thisObj, args) -> Math.floor(JSOps.toDouble(args[0])));
		math.put("ceil", (JSFunction) (cx, thisObj, args) -> Math.ceil(JSOps.toDouble(args[0])));
		math.put("round", (JSFunction) (cx, thisObj, args) -> (double) Math.round(JSOps.toDouble(args[0])));
		math.put("sin", (JSFunction) (cx, thisObj, args) -> Math.sin(JSOps.toDouble(args[0])));
		math.put("cos", (JSFunction) (cx, thisObj, args) -> Math.cos(JSOps.toDouble(args[0])));
		math.put("tan", (JSFunction) (cx, thisObj, args) -> Math.tan(JSOps.toDouble(args[0])));
		math.put("asin", (JSFunction) (cx, thisObj, args) -> Math.asin(JSOps.toDouble(args[0])));
		math.put("acos", (JSFunction) (cx, thisObj, args) -> Math.acos(JSOps.toDouble(args[0])));
		math.put("atan", (JSFunction) (cx, thisObj, args) -> Math.atan(JSOps.toDouble(args[0])));
		math.put("atan2", (JSFunction) (cx, thisObj, args) -> Math.atan2(JSOps.toDouble(args[0]), JSOps.toDouble(args[1])));
		math.put("pow", (JSFunction) (cx, thisObj, args) -> Math.pow(JSOps.toDouble(args[0]), JSOps.toDouble(args[1])));
		math.put("random", (JSFunction) (cx, thisObj, args) -> Math.random());
		math.put("log", (JSFunction) (cx, thisObj, args) -> Math.log(JSOps.toDouble(args[0])));
		math.put("exp", (JSFunction) (cx, thisObj, args) -> Math.exp(JSOps.toDouble(args[0])));
		math.put("sign", (JSFunction) (cx, thisObj, args) -> Math.signum(JSOps.toDouble(args[0])));
		math.put("trunc", (JSFunction) (cx, thisObj, args) -> {
			double d = JSOps.toDouble(args[0]);
			return d < 0 ? Math.ceil(d) : Math.floor(d);
		});
		math.put("cbrt", (JSFunction) (cx, thisObj, args) -> Math.cbrt(JSOps.toDouble(args[0])));
		math.put("hypot", (JSFunction) (cx, thisObj, args) -> Math.hypot(JSOps.toDouble(args[0]), JSOps.toDouble(args[1])));
		math.put("log10", (JSFunction) (cx, thisObj, args) -> Math.log10(JSOps.toDouble(args[0])));
		math.put("log2", (JSFunction) (cx, thisObj, args) -> Math.log(JSOps.toDouble(args[0])) / 0.6931471805599453);
		set("Math", math);

		// importClass
		set("importClass", (JSFunction) (cx, thisObj, args) -> {
			if (args.length > 0) {
				if (args[0] instanceof Class<?>) {
					Class<?> c = (Class<?>) args[0];
					set(c.getSimpleName(), c);
				} else if (args[0] instanceof String) {
					try {
						Class<?> c = Class.forName((String) args[0]);
						set(c.getSimpleName(), c);
					} catch (ClassNotFoundException e) {
						throw new RuntimeException(e);
					}
				}
			}
			return JSUndefined.INSTANCE;
		});

		// Packages 对象 (用于 Java 类定位)
		set("Packages", new JSObject() {
			@Override
			public Object get(String key) {
				try {
					return Class.forName(key);
				} catch (ClassNotFoundException e) {
					return super.get(key);
				}
			}
		});

		// RegExp 构造函数
		JSFunction regExpCtor = (cx, thisObj, args) -> {
			if (args.length == 0) return new JSRegExp("", "");
			if (args[0] instanceof JSRegExp oldReg) {
				String flags = args.length > 1 && args[1] != null && args[1] != JSUndefined.INSTANCE ? JSOps.toStr(args[1]) : oldReg.getFlags();
				return new JSRegExp(oldReg.getPattern(), flags);
			}
			String pat = JSOps.toStr(args[0]);
			String flags = args.length > 1 && args[1] != null && args[1] != JSUndefined.INSTANCE ? JSOps.toStr(args[1]) : "";
			return new JSRegExp(pat, flags);
		};
		set("RegExp", regExpCtor);
	}

	public synchronized void set(String name, Object value) {
		globals.put(name, value == null ? NULL_VALUE : value);
		int slot = getGlobalSlot(name);
		ensureGlobalSlotCapacity(slot);
		globalSlots[slot] = value == null ? NULL_VALUE : value;
	}

	public Object get(String name) {
		int slot = GLOBAL_SLOT_REGISTRY.getOrDefault(name, -1);
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
		if (Character.isUpperCase(name.charAt(0))) {
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
		return JSUndefined.INSTANCE;
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
			JSLexer lexer = new JSLexer(code);
			List<Token> tokens = lexer.tokenize();
			JSParser parser = new JSParser(tokens);
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
