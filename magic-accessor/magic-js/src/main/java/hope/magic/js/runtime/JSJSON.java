package hope.magic.js.runtime;

import java.util.*;

/**
 * Standard ECMAScript JSON built-in object (ECMA-262 § 25.5).
 * Implements JSON.parse and JSON.stringify.
 */
public final class JSJSON {

	public static final JSObject JSON = createJSONObject();

	private static JSObject createJSONObject() {
		JSObject json = new JSObject(JSContext.LazyBuiltins.OBJECT_PROTOTYPE);
		json.put(JSSymbol.TO_STRING_TAG, "JSON");

		json.put("parse", JSContext.makeMethod("parse", 2, (cx, thisObj, args) -> {
			if (args.length == 0 || args[0] == null || args[0] == JSUndefined.INSTANCE) {
				throw JSContext.makeSyntaxError("Unexpected end of JSON input");
			}
			String text = JSOps.toStr(args[0]);
			Object reviver = args.length > 1 ? args[1] : null;
			return parse(cx, text, reviver);
		}));

		json.put("stringify", JSContext.makeMethod("stringify", 3, (cx, thisObj, args) -> {
			Object value = args.length > 0 ? args[0] : JSUndefined.INSTANCE;
			Object replacer = args.length > 1 ? args[1] : null;
			Object space = args.length > 2 ? args[2] : null;
			return stringify(cx, value, replacer, space);
		}));

		return json;
	}

	private static Object callJS(JSFunction fn, JSContext cx, Object thisObj, Object... args) {
		try {
			return fn.call(cx, thisObj, args);
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}

	public static Object parse(JSContext cx, String text, Object reviver) {
		JsonParser parser = new JsonParser(text);
		Object parsed = parser.parseValue();
		parser.skipWhitespace();
		if (!parser.isAtEnd()) {
			throw JSContext.makeSyntaxError("Unexpected non-whitespace character after JSON at position " + parser.pos);
		}
		if (reviver instanceof JSFunction fn) {
			JSObject root = new JSObject();
			root.put("", parsed);
			return walkReviver(cx, root, "", fn);
		}
		return parsed;
	}

	private static Object walkReviver(JSContext cx, Object holder, String name, JSFunction reviver) {
		Object val;
		if (holder instanceof JSObject jo) {
			val = jo.get(name);
		} else if (holder instanceof JSArray ja) {
			try {
				int idx = Integer.parseInt(name);
				val = ja.getElement(idx);
			} catch (Exception e) {
				val = JSUndefined.INSTANCE;
			}
		} else {
			val = JSUndefined.INSTANCE;
		}

		if (val instanceof JSArray arr) {
			long len = arr.length();
			for (long i = 0; i < len; i++) {
				String key = String.valueOf(i);
				Object newElement = walkReviver(cx, arr, key, reviver);
				if (newElement == JSUndefined.INSTANCE) {
					arr.deleteElement(i);
				} else {
					arr.setElement((int) i, newElement);
				}
			}
		} else if (val instanceof JSObject jo) {
			Set<String> keys = new LinkedHashSet<>(jo.keys());
			for (String key : keys) {
				Object newElement = walkReviver(cx, jo, key, reviver);
				if (newElement == JSUndefined.INSTANCE) {
					jo.delete(key);
				} else {
					jo.put(key, newElement);
				}
			}
		}

		return callJS(reviver, cx, holder, name, val);
	}

	public static Object stringify(JSContext cx, Object value, Object replacer, Object space) {
		String indent = "";
		if (space instanceof Number n) {
			int count = Math.min(10, Math.max(0, n.intValue()));
			indent = " ".repeat(count);
		} else if (space instanceof CharSequence cs) {
			String s = cs.toString();
			indent = s.length() > 10 ? s.substring(0, 10) : s;
		}

		JSFunction replacerFn = (replacer instanceof JSFunction fn) ? fn : null;
		Set<String> propertyList = null;
		if (replacer instanceof JSArray ja) {
			propertyList = new LinkedHashSet<>();
			long len = ja.length();
			for (long i = 0; i < len; i++) {
				Object v = ja.getElement(i);
				if (v instanceof CharSequence || v instanceof Number) {
					propertyList.add(JSOps.toStr(v));
				}
			}
		} else if (replacer instanceof Collection<?> col) {
			propertyList = new LinkedHashSet<>();
			for (Object v : col) {
				if (v instanceof CharSequence || v instanceof Number) {
					propertyList.add(JSOps.toStr(v));
				}
			}
		}

		Serializer s = new Serializer(cx, replacerFn, propertyList, indent);
		JSObject wrapper = new JSObject();
		wrapper.put("", value);
		String res = s.serializeProperty("", wrapper);
		return res == null ? JSUndefined.INSTANCE : res;
	}

	// =========================================================================
	// JSON Parser
	// =========================================================================

	private static final class JsonParser {
		private final String text;
		private final int len;
		private int pos = 0;

		public JsonParser(String text) {
			this.text = text;
			this.len = text.length();
		}

		public boolean isAtEnd() {
			return pos >= len;
		}

		public void skipWhitespace() {
			while (pos < len) {
				char c = text.charAt(pos);
				if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
					pos++;
				} else {
					break;
				}
			}
		}

		public Object parseValue() {
			skipWhitespace();
			if (isAtEnd()) {
				throw JSContext.makeSyntaxError("Unexpected end of JSON input");
			}
			char c = text.charAt(pos);
			if (c == '{') {
				return parseObject();
			} else if (c == '[') {
				return parseArray();
			} else if (c == '"') {
				return parseString();
			} else if (c == 't' || c == 'f') {
				return parseBoolean();
			} else if (c == 'n') {
				return parseNull();
			} else if (c == '-' || (c >= '0' && c <= '9')) {
				return parseNumber();
			}
			throw JSContext.makeSyntaxError("Unexpected token '" + c + "' at position " + pos);
		}

		private JSObject parseObject() {
			consume('{');
			skipWhitespace();
			JSObject obj = new JSObject();
			if (match('}')) {
				return obj;
			}
			while (true) {
				skipWhitespace();
				if (isAtEnd() || text.charAt(pos) != '"') {
					throw JSContext.makeSyntaxError("Expected string key in JSON object at position " + pos);
				}
				String key = parseString();
				skipWhitespace();
				consume(':');
				Object val = parseValue();
				obj.put(key, val);
				skipWhitespace();
				if (match('}')) {
					break;
				}
				consume(',');
			}
			return obj;
		}

		private JSArray parseArray() {
			consume('[');
			skipWhitespace();
			JSArray arr = new JSArray();
			if (match(']')) {
				return arr;
			}
			while (true) {
				Object val = parseValue();
				arr.push(val);
				skipWhitespace();
				if (match(']')) {
					break;
				}
				consume(',');
			}
			return arr;
		}

		private String parseString() {
			consume('"');
			StringBuilder sb = new StringBuilder();
			while (pos < len) {
				char c = text.charAt(pos++);
				if (c == '"') {
					return sb.toString();
				}
				if (c == '\\') {
					if (pos >= len) {
						throw JSContext.makeSyntaxError("Unterminated escape sequence in JSON string");
					}
					char esc = text.charAt(pos++);
					switch (esc) {
						case '"' -> sb.append('"');
						case '\\' -> sb.append('\\');
						case '/' -> sb.append('/');
						case 'b' -> sb.append('\b');
						case 'f' -> sb.append('\f');
						case 'n' -> sb.append('\n');
						case 'r' -> sb.append('\r');
						case 't' -> sb.append('\t');
						case 'u' -> {
							if (pos + 4 > len) {
								throw JSContext.makeSyntaxError("Invalid unicode escape in JSON");
							}
							String hex = text.substring(pos, pos + 4);
							pos += 4;
							try {
								int code = Integer.parseInt(hex, 16);
								sb.append((char) code);
							} catch (NumberFormatException e) {
								throw JSContext.makeSyntaxError("Invalid unicode hex sequence: \\u" + hex);
							}
						}
						default -> throw JSContext.makeSyntaxError("Invalid escape character '\\" + esc + "' in JSON");
					}
				} else if (c < 0x20) {
					throw JSContext.makeSyntaxError("Unescaped control character in JSON string at position " + (pos - 1));
				} else {
					sb.append(c);
				}
			}
			throw JSContext.makeSyntaxError("Unterminated string in JSON");
		}

		private Number parseNumber() {
			int start = pos;
			if (text.charAt(pos) == '-') {
				pos++;
			}
			if (pos >= len) {
				throw JSContext.makeSyntaxError("Invalid number in JSON");
			}
			char c = text.charAt(pos);
			if (c == '0') {
				pos++;
			} else if (c >= '1' && c <= '9') {
				pos++;
				while (pos < len && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') {
					pos++;
				}
			} else {
				throw JSContext.makeSyntaxError("Invalid number in JSON at position " + pos);
			}

			boolean isFloat = false;
			if (pos < len && text.charAt(pos) == '.') {
				isFloat = true;
				pos++;
				if (pos >= len || text.charAt(pos) < '0' || text.charAt(pos) > '9') {
					throw JSContext.makeSyntaxError("Decimal point must be followed by digits in JSON at position " + pos);
				}
				while (pos < len && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') {
					pos++;
				}
			}

			if (pos < len && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
				isFloat = true;
				pos++;
				if (pos < len && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
					pos++;
				}
				if (pos >= len || text.charAt(pos) < '0' || text.charAt(pos) > '9') {
					throw JSContext.makeSyntaxError("Exponent must be followed by digits in JSON at position " + pos);
				}
				while (pos < len && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') {
					pos++;
				}
			}

			String numStr = text.substring(start, pos);
			if (!isFloat) {
				try {
					long val = Long.parseLong(numStr);
					if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
						return (int) val;
					}
					return (double) val;
				} catch (NumberFormatException ignored) {}
			}
			return Double.parseDouble(numStr);
		}

		private Boolean parseBoolean() {
			if (text.startsWith("true", pos)) {
				pos += 4;
				return Boolean.TRUE;
			}
			if (text.startsWith("false", pos)) {
				pos += 5;
				return Boolean.FALSE;
			}
			throw JSContext.makeSyntaxError("Unexpected token in JSON at position " + pos);
		}

		private Object parseNull() {
			if (text.startsWith("null", pos)) {
				pos += 4;
				return null;
			}
			throw JSContext.makeSyntaxError("Unexpected token in JSON at position " + pos);
		}

		private void consume(char expected) {
			if (pos >= len || text.charAt(pos) != expected) {
				throw JSContext.makeSyntaxError("Expected '" + expected + "' in JSON at position " + pos);
			}
			pos++;
		}

		private boolean match(char c) {
			if (pos < len && text.charAt(pos) == c) {
				pos++;
				return true;
			}
			return false;
		}
	}

	// =========================================================================
	// JSON Serializer
	// =========================================================================

	private static final class Serializer {
		private final JSContext cx;
		private final JSFunction replacerFn;
		private final Set<String> propertyList;
		private final String indent;
		private final Set<Object> stack = Collections.newSetFromMap(new IdentityHashMap<>());

		public Serializer(JSContext cx, JSFunction replacerFn, Set<String> propertyList, String indent) {
			this.cx = cx;
			this.replacerFn = replacerFn;
			this.propertyList = propertyList;
			this.indent = indent;
		}

		public String serializeProperty(String key, Object holder) {
			Object value;
			if (holder instanceof JSObject jo) {
				value = jo.get(key);
			} else if (holder instanceof JSArray ja) {
				try {
					value = ja.getElement(Integer.parseInt(key));
				} catch (Exception e) {
					value = JSUndefined.INSTANCE;
				}
			} else if (holder instanceof Map<?, ?> m) {
				value = m.get(key);
			} else {
				value = JSUndefined.INSTANCE;
			}

			// 1. toJSON check
			if (value instanceof JSObject jo) {
				Object toJSON = jo.get("toJSON");
				if (toJSON instanceof JSFunction fn) {
					value = callJS(fn, cx, jo, key);
				}
			}

			// 2. Replacer function check
			if (replacerFn != null) {
				value = callJS(replacerFn, cx, holder, key, value);
			}

			return serializeValue(value, "");
		}

		private String serializeValue(Object value, String currentIndent) {
			if (value == null) {
				return "null";
			}
			if (value == JSUndefined.INSTANCE || value instanceof JSFunction || value instanceof JSSymbol) {
				return null;
			}
			if (value instanceof Boolean b) {
				return b ? "true" : "false";
			}
			if (value instanceof Number n) {
				double d = n.doubleValue();
				if (Double.isNaN(d) || Double.isInfinite(d)) {
					return "null";
				}
				if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
					return value.toString();
				}
				return JSOps.numberToString(d);
			}
			if (value instanceof CharSequence cs) {
				return quoteString(cs.toString());
			}

			if (value instanceof JSArray ja) {
				return serializeArray(ja, currentIndent);
			}
			if (value instanceof Collection<?> col) {
				return serializeCollection(col, currentIndent);
			}
			if (value.getClass().isArray()) {
				return serializeJavaArray(value, currentIndent);
			}
			if (value instanceof JSObject jo) {
				return serializeObject(jo, currentIndent);
			}
			if (value instanceof Map<?, ?> m) {
				return serializeMap(m, currentIndent);
			}

			return quoteString(value.toString());
		}

		private String serializeArray(JSArray arr, String currentIndent) {
			if (stack.contains(arr)) {
				throw JSContext.makeTypeError("Converting circular structure to JSON");
			}
			stack.add(arr);
			try {
				long len = arr.length();
				if (len == 0) return "[]";

				String nextIndent = currentIndent + indent;
				boolean pretty = !indent.isEmpty();
				StringBuilder sb = new StringBuilder();
				sb.append('[');
				if (pretty) sb.append('\n');

				for (long i = 0; i < len; i++) {
					if (i > 0) {
						sb.append(',');
						if (pretty) sb.append('\n');
					}
					if (pretty) sb.append(nextIndent);
					Object elem = arr.getElement(i);
					if (elem instanceof JSObject jo) {
						Object toJSON = jo.get("toJSON");
						if (toJSON instanceof JSFunction fn) {
							elem = callJS(fn, cx, jo, String.valueOf(i));
						}
					}
					if (replacerFn != null) {
						elem = callJS(replacerFn, cx, arr, String.valueOf(i), elem);
					}
					String str = serializeValue(elem, nextIndent);
					sb.append(str == null ? "null" : str);
				}

				if (pretty) {
					sb.append('\n').append(currentIndent);
				}
				sb.append(']');
				return sb.toString();
			} finally {
				stack.remove(arr);
			}
		}

		private String serializeCollection(Collection<?> col, String currentIndent) {
			if (stack.contains(col)) {
				throw JSContext.makeTypeError("Converting circular structure to JSON");
			}
			stack.add(col);
			try {
				if (col.isEmpty()) return "[]";

				String nextIndent = currentIndent + indent;
				boolean pretty = !indent.isEmpty();
				StringBuilder sb = new StringBuilder();
				sb.append('[');
				if (pretty) sb.append('\n');

				int idx = 0;
				for (Object elem : col) {
					if (idx > 0) {
						sb.append(',');
						if (pretty) sb.append('\n');
					}
					if (pretty) sb.append(nextIndent);
					if (replacerFn != null) {
						elem = callJS(replacerFn, cx, col, String.valueOf(idx), elem);
					}
					String str = serializeValue(elem, nextIndent);
					sb.append(str == null ? "null" : str);
					idx++;
				}

				if (pretty) {
					sb.append('\n').append(currentIndent);
				}
				sb.append(']');
				return sb.toString();
			} finally {
				stack.remove(col);
			}
		}

		private String serializeJavaArray(Object arr, String currentIndent) {
			int len = java.lang.reflect.Array.getLength(arr);
			if (len == 0) return "[]";

			String nextIndent = currentIndent + indent;
			boolean pretty = !indent.isEmpty();
			StringBuilder sb = new StringBuilder();
			sb.append('[');
			if (pretty) sb.append('\n');

			for (int i = 0; i < len; i++) {
				if (i > 0) {
					sb.append(',');
					if (pretty) sb.append('\n');
				}
				if (pretty) sb.append(nextIndent);
				Object elem = java.lang.reflect.Array.get(arr, i);
				String str = serializeValue(elem, nextIndent);
				sb.append(str == null ? "null" : str);
			}

			if (pretty) {
				sb.append('\n').append(currentIndent);
			}
			sb.append(']');
			return sb.toString();
		}

		private String serializeObject(JSObject obj, String currentIndent) {
			if (stack.contains(obj)) {
				throw JSContext.makeTypeError("Converting circular structure to JSON");
			}
			stack.add(obj);
			try {
				Collection<String> keys = propertyList != null ? propertyList : obj.keys();
				String nextIndent = currentIndent + indent;
				boolean pretty = !indent.isEmpty();
				StringBuilder sb = new StringBuilder();
				boolean first = true;

				for (String key : keys) {
					Object val = obj.get(key);
					if (val instanceof JSObject jo) {
						Object toJSON = jo.get("toJSON");
						if (toJSON instanceof JSFunction fn) {
							val = callJS(fn, cx, jo, key);
						}
					}
					if (replacerFn != null) {
						val = callJS(replacerFn, cx, obj, key, val);
					}
					String valStr = serializeValue(val, nextIndent);
					if (valStr == null) {
						continue; // undefined / function / symbol properties are omitted
					}

					if (first) {
						sb.append('{');
						if (pretty) sb.append('\n');
						first = false;
					} else {
						sb.append(',');
						if (pretty) sb.append('\n');
					}

					if (pretty) sb.append(nextIndent);
					sb.append(quoteString(key));
					sb.append(':');
					if (pretty) sb.append(' ');
					sb.append(valStr);
				}

				if (first) {
					return "{}";
				}
				if (pretty) {
					sb.append('\n').append(currentIndent);
				}
				sb.append('}');
				return sb.toString();
			} finally {
				stack.remove(obj);
			}
		}

		private String serializeMap(Map<?, ?> map, String currentIndent) {
			if (stack.contains(map)) {
				throw JSContext.makeTypeError("Converting circular structure to JSON");
			}
			stack.add(map);
			try {
				String nextIndent = currentIndent + indent;
				boolean pretty = !indent.isEmpty();
				StringBuilder sb = new StringBuilder();
				boolean first = true;

				for (Map.Entry<?, ?> entry : map.entrySet()) {
					String key = JSOps.toStr(entry.getKey());
					if (propertyList != null && !propertyList.contains(key)) {
						continue;
					}
					Object val = entry.getValue();
					if (replacerFn != null) {
						val = callJS(replacerFn, cx, map, key, val);
					}
					String valStr = serializeValue(val, nextIndent);
					if (valStr == null) {
						continue;
					}

					if (first) {
						sb.append('{');
						if (pretty) sb.append('\n');
						first = false;
					} else {
						sb.append(',');
						if (pretty) sb.append('\n');
					}

					if (pretty) sb.append(nextIndent);
					sb.append(quoteString(key));
					sb.append(':');
					if (pretty) sb.append(' ');
					sb.append(valStr);
				}

				if (first) {
					return "{}";
				}
				if (pretty) {
					sb.append('\n').append(currentIndent);
				}
				sb.append('}');
				return sb.toString();
			} finally {
				stack.remove(map);
			}
		}

		private static String quoteString(String str) {
			StringBuilder sb = new StringBuilder(str.length() + 16);
			sb.append('"');
			for (int i = 0; i < str.length(); i++) {
				char c = str.charAt(i);
				switch (c) {
					case '"' -> sb.append("\\\"");
					case '\\' -> sb.append("\\\\");
					case '\b' -> sb.append("\\b");
					case '\f' -> sb.append("\\f");
					case '\n' -> sb.append("\\n");
					case '\r' -> sb.append("\\r");
					case '\t' -> sb.append("\\t");
					default -> {
						if (c < 0x20) {
							sb.append(String.format("\\u%04x", (int) c));
						} else {
							sb.append(c);
						}
					}
				}
			}
			sb.append('"');
			return sb.toString();
		}
	}
}
