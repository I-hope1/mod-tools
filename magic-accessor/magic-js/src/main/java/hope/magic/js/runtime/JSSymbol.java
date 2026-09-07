package hope.magic.js.runtime;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ECMAScript Symbol primitive value.
 */
public final class JSSymbol {
	private static final AtomicLong                          NEXT_ID       = new AtomicLong(1);
	private static final ConcurrentHashMap<String, JSSymbol> KEY_TO_SYMBOL = new ConcurrentHashMap<>();

	public static final String SYMBOL_PREFIX = "\u0000Symbol(";

	public static final JSSymbol ITERATOR             = new JSSymbol("Symbol.iterator");
	public static final JSSymbol ASYNC_ITERATOR       = new JSSymbol("Symbol.asyncIterator");
	public static final JSSymbol TO_STRING_TAG        = new JSSymbol("Symbol.toStringTag");
	public static final JSSymbol HAS_INSTANCE         = new JSSymbol("Symbol.hasInstance");
	public static final JSSymbol IS_CONCAT_SPREADABLE = new JSSymbol("Symbol.isConcatSpreadable");
	public static final JSSymbol SPECIES              = new JSSymbol("Symbol.species");
	public static final JSSymbol TO_PRIMITIVE         = new JSSymbol("Symbol.toPrimitive");
	public static final JSSymbol UNSCOPABLES          = new JSSymbol("Symbol.unscopables");
	public static final JSSymbol MATCH                = new JSSymbol("Symbol.match");
	public static final JSSymbol REPLACE              = new JSSymbol("Symbol.replace");
	public static final JSSymbol SEARCH               = new JSSymbol("Symbol.search");
	public static final JSSymbol SPLIT                = new JSSymbol("Symbol.split");

	private final long   id;
	private final String description;
	private final String internalKey;

	public JSSymbol(String description) {
		this.id = NEXT_ID.getAndIncrement();
		this.description = description;
		this.internalKey = SYMBOL_PREFIX + (description != null ? description : "") + ")#" + id;
		KEY_TO_SYMBOL.put(this.internalKey, this);
	}

	public String getDescription() {
		return description;
	}

	public String getKey() {
		return internalKey;
	}

	public static boolean isSymbolKey(String key) {
		return key != null && key.startsWith(SYMBOL_PREFIX);
	}

	public static JSSymbol fromKey(String key) {
		if (key == null) return null;
		return KEY_TO_SYMBOL.get(key);
	}

	@Override
	public String toString() {
		return "Symbol(" + (description != null ? description : "") + ")";
	}

	@Override
	public boolean equals(Object obj) {
		return this == obj;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(id);
	}
}
