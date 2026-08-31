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

	public Object[] globalSlots = new Object[64];
	private final Map<String, Object> globals = new HashMap<>();

	public JSContext() {
		initStandardGlobals();
	}

	private void ensureGlobalSlotCapacity(int slot) {
		if (slot >= globalSlots.length) {
			globalSlots = Arrays.copyOf(globalSlots, Math.max(globalSlots.length * 2, slot + 1));
		}
	}

	private void initStandardGlobals() {
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
	}

	public void set(String name, Object value) {
		globals.put(name, value);
		int slot = getGlobalSlot(name);
		ensureGlobalSlotCapacity(slot);
		globalSlots[slot] = value;
	}

	public Object get(String name) {
		int slot = GLOBAL_SLOT_REGISTRY.getOrDefault(name, -1);
		if (slot >= 0 && slot < globalSlots.length) {
			Object val = globalSlots[slot];
			if (val != null) return val;
		}
		Object val = globals.get(name);
		if (val != null) {
			if (slot >= 0) {
				ensureGlobalSlotCapacity(slot);
				globalSlots[slot] = val;
			}
			return val;
		}
		if (globals.containsKey(name)) {
			return null;
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
		if (slot < globalSlots.length) {
			Object val = globalSlots[slot];
			if (val != null) return val;
		}
		return JSUndefined.INSTANCE;
	}

	public final void setSlot(int slot, Object value) {
		if (slot >= globalSlots.length) {
			ensureGlobalSlotCapacity(slot);
		}
		globalSlots[slot] = value;
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
