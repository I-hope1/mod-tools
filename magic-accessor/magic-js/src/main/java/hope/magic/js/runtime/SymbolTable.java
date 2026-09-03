package hope.magic.js.runtime;

import java.lang.invoke.*;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 纯 Java 用户态符号表 (User-Space SymbolTable)。
 * 提供编译期与运行期字符串到稠密整数 ID 的双向极速映射（O(1) 数组反向查找）。
 * 彻底消除属性名字符串在热路径上的比较开销。
 */
public final class SymbolTable {

	private static final    ConcurrentHashMap<String, String>  TABLE      = new ConcurrentHashMap<>(1024);
	private static final    ConcurrentHashMap<String, Integer> NAME_TO_ID = new ConcurrentHashMap<>(1024);
	private static volatile String[]                           ID_TO_NAME = new String[1024];
	private static final    AtomicInteger                      ID_GEN     = new AtomicInteger(0);

	// private static final VarHandle ID_ARR_VH = MethodHandles.arrayElementVarHandle(String[].class);

	public static final int NO_SYMBOL = -1;

	private SymbolTable() { }

	/**
	 * 获取或注册符号字符串（纯 Java 缓存，零原生开销）
	 */
	public static String symbol(String name) {
		if (name == null) return null;
		String existing = TABLE.get(name);
		if (existing != null) return existing;
		TABLE.putIfAbsent(name, name);
		return TABLE.get(name);
	}

	/** 只读安全查找：若符号未注册，返回 {@link #NO_SYMBOL} */
	public static int lookupId(String name) {
		if (name == null) return  NO_SYMBOL;
		return NAME_TO_ID.getOrDefault(name, NO_SYMBOL);
	}

	/** 获取或分配字符串的唯一全局稠密整数 ID */
	public static int id(String name) {
		if (name == null) return NO_SYMBOL;
		Integer existingId = NAME_TO_ID.get(name);
		if (existingId != null) return existingId;
		return register(name);
	}

	private static synchronized int register(String name) {
		Integer existingId = NAME_TO_ID.get(name);
		if (existingId != null) return existingId;
		String sym   = symbol(name);
		int    newId = ID_GEN.getAndIncrement();
		if (newId >= ID_TO_NAME.length) {
			ID_TO_NAME = Arrays.copyOf(ID_TO_NAME, Math.max(ID_TO_NAME.length * 2, newId + 1));
		}
		ID_TO_NAME[newId]= sym;
		NAME_TO_ID.put(sym, newId);
		return newId;
	}

	/** 根据整数 ID 极速获取对应的符号字符串（O(1) 数组直读） */
	public static String name(int id) {
		if (id < 0) return null;
		String[] arr = ID_TO_NAME;
		if (id >= arr.length) return null;
		return arr[id];
	}
}
