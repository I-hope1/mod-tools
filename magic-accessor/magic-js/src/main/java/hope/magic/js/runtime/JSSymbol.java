package hope.magic.js.runtime;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ECMAScript Symbol primitive value.
 */
public final class JSSymbol {
	public static final int ID_ITERATOR             = 0;
	public static final int ID_ASYNC_ITERATOR       = 1;
	public static final int ID_TO_STRING_TAG        = 2;
	public static final int ID_HAS_INSTANCE         = 3;
	public static final int ID_IS_CONCAT_SPREADABLE = 4;
	public static final int ID_SPECIES              = 5;
	public static final int ID_TO_PRIMITIVE         = 6;
	public static final int ID_UNSCOPABLES          = 7;
	public static final int ID_MATCH                = 8;
	public static final int ID_REPLACE              = 9;
	public static final int ID_SEARCH               = 10;
	public static final int ID_SPLIT                = 11;
	public static final int WELL_KNOWN_COUNT        = 12;

	public static final String SYMBOL_PREFIX = "\u0000Symbol(";

	public static final JSSymbol ITERATOR             = new JSSymbol("Symbol.iterator", ID_ITERATOR, 1);
	public static final JSSymbol ASYNC_ITERATOR       = new JSSymbol("Symbol.asyncIterator", ID_ASYNC_ITERATOR, 2);
	public static final JSSymbol TO_STRING_TAG        = new JSSymbol("Symbol.toStringTag", ID_TO_STRING_TAG, 3);
	public static final JSSymbol HAS_INSTANCE         = new JSSymbol("Symbol.hasInstance", ID_HAS_INSTANCE, 4);
	public static final JSSymbol IS_CONCAT_SPREADABLE = new JSSymbol("Symbol.isConcatSpreadable", ID_IS_CONCAT_SPREADABLE, 5);
	public static final JSSymbol SPECIES              = new JSSymbol("Symbol.species", ID_SPECIES, 6);
	public static final JSSymbol TO_PRIMITIVE         = new JSSymbol("Symbol.toPrimitive", ID_TO_PRIMITIVE, 7);
	public static final JSSymbol UNSCOPABLES          = new JSSymbol("Symbol.unscopables", ID_UNSCOPABLES, 8);
	public static final JSSymbol MATCH                = new JSSymbol("Symbol.match", ID_MATCH, 9);
	public static final JSSymbol REPLACE              = new JSSymbol("Symbol.replace", ID_REPLACE, 10);
	public static final JSSymbol SEARCH               = new JSSymbol("Symbol.search", ID_SEARCH, 11);
	public static final JSSymbol SPLIT                = new JSSymbol("Symbol.split", ID_SPLIT, 12);

	private static final Map<String, JSSymbol> WELL_KNOWN_MAP = new HashMap<>(16);
	static {
		WELL_KNOWN_MAP.put(ITERATOR.getKey(), ITERATOR);
		WELL_KNOWN_MAP.put(ASYNC_ITERATOR.getKey(), ASYNC_ITERATOR);
		WELL_KNOWN_MAP.put(TO_STRING_TAG.getKey(), TO_STRING_TAG);
		WELL_KNOWN_MAP.put(HAS_INSTANCE.getKey(), HAS_INSTANCE);
		WELL_KNOWN_MAP.put(IS_CONCAT_SPREADABLE.getKey(), IS_CONCAT_SPREADABLE);
		WELL_KNOWN_MAP.put(SPECIES.getKey(), SPECIES);
		WELL_KNOWN_MAP.put(TO_PRIMITIVE.getKey(), TO_PRIMITIVE);
		WELL_KNOWN_MAP.put(UNSCOPABLES.getKey(), UNSCOPABLES);
		WELL_KNOWN_MAP.put(MATCH.getKey(), MATCH);
		WELL_KNOWN_MAP.put(REPLACE.getKey(), REPLACE);
		WELL_KNOWN_MAP.put(SEARCH.getKey(), SEARCH);
		WELL_KNOWN_MAP.put(SPLIT.getKey(), SPLIT);
	}

	private static final AtomicLong NEXT_ID = new AtomicLong(13);
	private static final ConcurrentHashMap<String, WeakReference<JSSymbol>> WEAK_DYNAMIC_SYMBOLS = new ConcurrentHashMap<>();

	private final long   id;
	private final String description;
	private final String internalKey;
	private volatile int symbolId;

	/** Internal constructor for well-known symbols */
	private JSSymbol(String description, int wellKnownId, long id) {
		this.id = id;
		this.description = description;
		this.internalKey = SYMBOL_PREFIX + (description != null ? description : "") + ")#" + id;
		this.symbolId = wellKnownId;
	}

	public JSSymbol(String description) {
		this.id = NEXT_ID.getAndIncrement();
		this.description = description;
		this.internalKey = SYMBOL_PREFIX + (description != null ? description : "") + ")#" + id;
		this.symbolId = SymbolTable.NO_SYMBOL;
		WEAK_DYNAMIC_SYMBOLS.put(this.internalKey, new WeakReference<>(this));
	}

	public String getDescription() {
		return description;
	}

	public String getKey() {
		return internalKey;
	}

	public int getSymbolId() {
		int sid = this.symbolId;
		if (sid == SymbolTable.NO_SYMBOL) {
			sid = SymbolTable.id(this.internalKey);
			this.symbolId = sid;
		}
		return sid;
	}

	public boolean isWellKnown() {
		return symbolId >= 0 && symbolId < WELL_KNOWN_COUNT;
	}

	public static boolean isSymbolKey(String key) {
		return key != null && key.startsWith(SYMBOL_PREFIX);
	}

	public static JSSymbol fromKey(String key) {
		if (key == null) return null;
		JSSymbol wk = WELL_KNOWN_MAP.get(key);
		if (wk != null) return wk;
		WeakReference<JSSymbol> ref = WEAK_DYNAMIC_SYMBOLS.get(key);
		return ref != null ? ref.get() : null;
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
