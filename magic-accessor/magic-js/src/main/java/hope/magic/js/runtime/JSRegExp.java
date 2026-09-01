package hope.magic.js.runtime;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JSRegExp extends JSObject {
	public static final JSObject REGEXP_PROTOTYPE = new JSObject();

	static {
		REGEXP_PROTOTYPE.put("test", (JSFunction) (cx, thisObj, args) -> {
			if (thisObj instanceof JSRegExp r) {
				String str = args.length > 0 && args[0] != null ? JSOps.toStr(args[0]) : "";
				return r.test(str);
			}
			return false;
		});
		REGEXP_PROTOTYPE.put("exec", (JSFunction) (cx, thisObj, args) -> {
			if (thisObj instanceof JSRegExp r) {
				String str = args.length > 0 && args[0] != null ? JSOps.toStr(args[0]) : "";
				return r.exec(str);
			}
			return null;
		});
	}

	private final String pattern;
	private final String flags;
	private final Pattern compiledPattern;
	private final boolean global;
	private final boolean ignoreCase;
	private final boolean multiline;
	private final boolean dotAll;
	private final boolean unicode;
	private int lastIndex = 0;

	public JSRegExp(String pattern, String flags) {
		super(REGEXP_PROTOTYPE);
		this.pattern = pattern == null ? "" : pattern;
		this.flags = flags == null ? "" : flags;

		boolean g = false, i = false, m = false, s = false, u = false;
		int javaFlags = 0;
		for (int k = 0; k < this.flags.length(); k++) {
			char ch = this.flags.charAt(k);
			switch (ch) {
				case 'g' -> g = true;
				case 'i' -> { i = true; javaFlags |= Pattern.CASE_INSENSITIVE; }
				case 'm' -> { m = true; javaFlags |= Pattern.MULTILINE; }
				case 's' -> { s = true; javaFlags |= Pattern.DOTALL; }
				case 'u' -> { u = true; javaFlags |= (Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS); }
			}
		}
		this.global = g;
		this.ignoreCase = i;
		this.multiline = m;
		this.dotAll = s;
		this.unicode = u;
		this.compiledPattern = Pattern.compile(this.pattern, javaFlags);
	}

	public boolean test(String str) {
		if (str == null) str = "null";
		if (!global) {
			return compiledPattern.matcher(str).find();
		}
		Matcher matcher = compiledPattern.matcher(str);
		if (lastIndex >= str.length()) {
			lastIndex = 0;
			return false;
		}
		if (matcher.find(lastIndex)) {
			lastIndex = matcher.end();
			return true;
		} else {
			lastIndex = 0;
			return false;
		}
	}

	public Object exec(String str) {
		if (str == null) str = "null";
		Matcher matcher = compiledPattern.matcher(str);
		int searchStart = global ? lastIndex : 0;
		if (searchStart > str.length()) {
			if (global) lastIndex = 0;
			return null;
		}
		if (matcher.find(searchStart)) {
			JSArray result = new JSArray();
			result.push(matcher.group(0));
			for (int i = 1; i <= matcher.groupCount(); i++) {
				String g = matcher.group(i);
				result.push(g);
			}
			result.put("index", (double) matcher.start());
			result.put("input", str);
			if (global) {
				lastIndex = matcher.end();
			}
			return result;
		} else {
			if (global) lastIndex = 0;
			return null;
		}
	}

	public int getLastIndex() {
		return lastIndex;
	}

	public void setLastIndex(int lastIndex) {
		this.lastIndex = lastIndex;
	}

	public Pattern getCompiledPattern() {
		return compiledPattern;
	}

	public String getPattern() {
		return pattern;
	}

	public String getFlags() {
		return flags;
	}

	public boolean isGlobal() {
		return global;
	}

	public boolean isIgnoreCase() {
		return ignoreCase;
	}

	public boolean isMultiline() {
		return multiline;
	}

	public boolean isDotAll() {
		return dotAll;
	}

	public boolean isUnicode() {
		return unicode;
	}

	@Override
	public Object get(String key) {
		if ("source".equals(key)) return pattern;
		if ("flags".equals(key)) return flags;
		if ("global".equals(key)) return global;
		if ("ignoreCase".equals(key)) return ignoreCase;
		if ("multiline".equals(key)) return multiline;
		if ("dotAll".equals(key)) return dotAll;
		if ("unicode".equals(key)) return unicode;
		if ("lastIndex".equals(key)) return (double) lastIndex;
		return super.get(key);
	}

	@Override
	public void put(String key, Object value) {
		if ("lastIndex".equals(key)) {
			this.lastIndex = JSOps.toInt(value);
			return;
		}
		super.put(key, value);
	}

	@Override
	public String toString() {
		return "/" + pattern + "/" + flags;
	}
}
