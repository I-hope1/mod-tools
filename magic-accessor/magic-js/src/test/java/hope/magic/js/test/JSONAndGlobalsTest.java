package hope.magic.js.test;

import hope.magic.js.runtime.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class JSONAndGlobalsTest {

	@Test
	public void testJSONParsePrimitivesAndLiterals() {
		JSContext cx = new JSContext();

		Assertions.assertNull(cx.eval("JSON.parse('null')"));
		Assertions.assertEquals(Boolean.TRUE, cx.eval("JSON.parse('true')"));
		Assertions.assertEquals(Boolean.FALSE, cx.eval("JSON.parse('false')"));
		Assertions.assertEquals(123, ((Number) cx.eval("JSON.parse('123')")).intValue());
		Assertions.assertEquals(-45.67, ((Number) cx.eval("JSON.parse('-45.67')")).doubleValue(), 1e-9);
		Assertions.assertEquals(1200.0, ((Number) cx.eval("JSON.parse('1.2e3')")).doubleValue(), 1e-9);
		Assertions.assertEquals("hello world", cx.eval("JSON.parse('\"hello world\"')"));
		Assertions.assertEquals("escape \" \\ / \b \f \n \r \t A",
				cx.eval("JSON.parse('\"escape \\\\\" \\\\\\\\ \\\\/ \\\\b \\\\f \\\\n \\\\r \\\\t \\\\u0041\"')"));
	}

	@Test
	public void testJSONParseObjectAndArray() {
		JSContext cx = new JSContext();

		Object res = cx.eval("""
			let data = JSON.parse('{"name": "Alice", "age": 30, "scores": [100, 95.5], "nested": {"ok": true}}');
			[data.name, data.age, data.scores[0], data.scores[1], data.nested.ok]
		""");
		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals("Alice", arr.getElement(0));
		Assertions.assertEquals(30, ((Number) arr.getElement(1)).intValue());
		Assertions.assertEquals(100, ((Number) arr.getElement(2)).intValue());
		Assertions.assertEquals(95.5, ((Number) arr.getElement(3)).doubleValue(), 1e-9);
		Assertions.assertEquals(Boolean.TRUE, arr.getElement(4));
	}

	@Test
	public void testJSONParseReviver() {
		JSContext cx = new JSContext();

		Object res = cx.eval("""
			let text = '{"a": 1, "b": 2, "c": 3}';
			JSON.parse(text, (key, value) => {
				if (key === 'b') return undefined; // delete b
				if (typeof value === 'number') return value * 10;
				return value;
			});
		""");
		Assertions.assertInstanceOf(JSObject.class, res);
		JSObject obj = (JSObject) res;
		Assertions.assertEquals(10, ((Number) obj.get("a")).intValue());
		Assertions.assertEquals(JSUndefined.INSTANCE, obj.get("b"));
		Assertions.assertEquals(30, ((Number) obj.get("c")).intValue());
	}

	@Test
	public void testJSONParseErrors() {
		JSContext cx = new JSContext();

		Assertions.assertThrows(Exception.class, () -> cx.eval("JSON.parse('{')"));
		Assertions.assertThrows(Exception.class, () -> cx.eval("JSON.parse('{\"a\": 1,}')"));
		Assertions.assertThrows(Exception.class, () -> cx.eval("JSON.parse('undefined')"));
		Assertions.assertThrows(Exception.class, () -> cx.eval("JSON.parse('')"));
	}

	@Test
	public void testJSONStringifyPrimitives() {
		JSContext cx = new JSContext();

		Assertions.assertEquals("null", cx.eval("JSON.stringify(null)"));
		Assertions.assertEquals("true", cx.eval("JSON.stringify(true)"));
		Assertions.assertEquals("false", cx.eval("JSON.stringify(false)"));
		Assertions.assertEquals("123", cx.eval("JSON.stringify(123)"));
		Assertions.assertEquals("3.14", cx.eval("JSON.stringify(3.14)"));
		Assertions.assertEquals("\"hello\"", cx.eval("JSON.stringify('hello')"));
		Assertions.assertEquals(JSUndefined.INSTANCE, cx.eval("JSON.stringify(undefined)"));
		Assertions.assertEquals(JSUndefined.INSTANCE, cx.eval("JSON.stringify(() => {})"));
	}

	@Test
	public void testJSONStringifyObjectsAndArrays() {
		JSContext cx = new JSContext();

		Assertions.assertEquals("{\"a\":1,\"b\":\"str\",\"c\":[1,2,3]}",
				cx.eval("JSON.stringify({ a: 1, b: 'str', c: [1, 2, 3] })"));

		// undefined / function in object properties are omitted
		Assertions.assertEquals("{\"a\":1}",
				cx.eval("JSON.stringify({ a: 1, b: undefined, c: () => {} })"));

		// undefined / function in arrays become null
		Assertions.assertEquals("[1,null,null,2]",
				cx.eval("JSON.stringify([1, undefined, () => {}, 2])"));
	}

	@Test
	public void testJSONStringifyIndent() {
		JSContext cx = new JSContext();

		String pretty2 = (String) cx.eval("JSON.stringify({ a: 1 }, null, 2)");
		Assertions.assertEquals("{\n  \"a\": 1\n}", pretty2);

		String prettyTab = (String) cx.eval("JSON.stringify({ a: 1 }, null, '\\t')");
		Assertions.assertEquals("{\n\t\"a\": 1\n}", prettyTab);
	}

	@Test
	public void testJSONStringifyReplacer() {
		JSContext cx = new JSContext();

		// Replacer array (whitelist)
		Assertions.assertEquals("{\"a\":1,\"c\":3}",
				cx.eval("JSON.stringify({ a: 1, b: 2, c: 3 }, ['a', 'c'])"));

		// Replacer function
		Assertions.assertEquals("{\"a\":2,\"b\":4}",
				cx.eval("JSON.stringify({ a: 1, b: 2 }, (k, v) => typeof v === 'number' ? v * 2 : v)"));
	}

	@Test
	public void testJSONStringifyToJSON() {
		JSContext cx = new JSContext();

		Assertions.assertEquals("\"custom-date\"",
				cx.eval("JSON.stringify({ toJSON() { return 'custom-date'; } })"));

		Assertions.assertEquals("{\"item\":\"custom-item\"}",
				cx.eval("JSON.stringify({ item: { toJSON(key) { return 'custom-' + key; } } })"));
	}

	@Test
	public void testJSONStringifyCircular() {
		JSContext cx = new JSContext();

		Assertions.assertThrows(Exception.class, () -> cx.eval("""
			let a = {};
			a.self = a;
			JSON.stringify(a);
		"""));
	}

	@Test
	public void testGlobalParseInt() {
		JSContext cx = new JSContext();

		Assertions.assertEquals(123, ((Number) cx.eval("parseInt('123')")).intValue());
		Assertions.assertEquals(-456, ((Number) cx.eval("parseInt('   -456')")).intValue());
		Assertions.assertEquals(26, ((Number) cx.eval("parseInt('0x1a')")).intValue());
		Assertions.assertEquals(26, ((Number) cx.eval("parseInt('0X1A')")).intValue());
		Assertions.assertEquals(10, ((Number) cx.eval("parseInt('1010', 2)")).intValue());
		Assertions.assertEquals(63, ((Number) cx.eval("parseInt('77', 8)")).intValue());
		Assertions.assertEquals(123, ((Number) cx.eval("parseInt('123abc456')")).intValue());
		Assertions.assertTrue(Double.isNaN(((Number) cx.eval("parseInt('abc')")).doubleValue()));
		Assertions.assertTrue(Double.isNaN(((Number) cx.eval("parseInt('')")).doubleValue()));
		Assertions.assertTrue(Double.isNaN(((Number) cx.eval("parseInt('0', 37)")).doubleValue()));
		Assertions.assertEquals(36, ((Number) cx.eval("parseInt('10', 36)")).intValue());
		Assertions.assertEquals(2, ((Number) cx.eval("parseInt.length")).intValue());
		Assertions.assertEquals("parseInt", cx.eval("parseInt.name"));
	}

	@Test
	public void testGlobalParseFloat() {
		JSContext cx = new JSContext();

		Assertions.assertEquals(3.14, ((Number) cx.eval("parseFloat('3.14')")).doubleValue(), 1e-9);
		Assertions.assertEquals(-0.001, ((Number) cx.eval("parseFloat('   -0.001')")).doubleValue(), 1e-9);
		Assertions.assertEquals(0.5, ((Number) cx.eval("parseFloat('.5')")).doubleValue(), 1e-9);
		Assertions.assertEquals(5.0, ((Number) cx.eval("parseFloat('5.')")).doubleValue(), 1e-9);
		Assertions.assertEquals(12300.0, ((Number) cx.eval("parseFloat('1.23e4')")).doubleValue(), 1e-9);
		Assertions.assertEquals(0.0123, ((Number) cx.eval("parseFloat('1.23e-2')")).doubleValue(), 1e-9);
		Assertions.assertEquals(1.23, ((Number) cx.eval("parseFloat('1.23e')")).doubleValue(), 1e-9);
		Assertions.assertEquals(Double.POSITIVE_INFINITY, ((Number) cx.eval("parseFloat('Infinity')")).doubleValue());
		Assertions.assertEquals(Double.NEGATIVE_INFINITY, ((Number) cx.eval("parseFloat('-Infinity')")).doubleValue());
		Assertions.assertTrue(Double.isNaN(((Number) cx.eval("parseFloat('abc')")).doubleValue()));
		Assertions.assertEquals(1, ((Number) cx.eval("parseFloat.length")).intValue());
		Assertions.assertEquals("parseFloat", cx.eval("parseFloat.name"));
	}

	@Test
	public void testGlobalIsNaNAndIsFinite() {
		JSContext cx = new JSContext();

		// isNaN coerces argument
		Assertions.assertEquals(Boolean.TRUE, cx.eval("isNaN(NaN)"));
		Assertions.assertEquals(Boolean.TRUE, cx.eval("isNaN('foo')"));
		Assertions.assertEquals(Boolean.FALSE, cx.eval("isNaN('123')"));
		Assertions.assertEquals(Boolean.FALSE, cx.eval("isNaN(123)"));
		Assertions.assertEquals(Boolean.TRUE, cx.eval("isNaN(undefined)"));
		Assertions.assertEquals(Boolean.FALSE, cx.eval("isNaN(null)"));

		// isFinite coerces argument
		Assertions.assertEquals(Boolean.TRUE, cx.eval("isFinite(123)"));
		Assertions.assertEquals(Boolean.TRUE, cx.eval("isFinite('123')"));
		Assertions.assertEquals(Boolean.FALSE, cx.eval("isFinite(Infinity)"));
		Assertions.assertEquals(Boolean.FALSE, cx.eval("isFinite(-Infinity)"));
		Assertions.assertEquals(Boolean.FALSE, cx.eval("isFinite(NaN)"));
		Assertions.assertEquals(Boolean.FALSE, cx.eval("isFinite('foo')"));
	}

	@Test
	public void testNumberStaticPropertiesAndMethods() {
		JSContext cx = new JSContext();

		// Number.parseInt and Number.parseFloat
		Assertions.assertEquals(Boolean.TRUE, cx.eval("Number.parseInt === parseInt"));
		Assertions.assertEquals(Boolean.TRUE, cx.eval("Number.parseFloat === parseFloat"));

		// Number.isNaN (strict, no coercion)
		Assertions.assertEquals(Boolean.TRUE, cx.eval("Number.isNaN(NaN)"));
		Assertions.assertEquals(Boolean.FALSE, cx.eval("Number.isNaN('foo')"));
		Assertions.assertEquals(Boolean.FALSE, cx.eval("Number.isNaN(undefined)"));

		// Number.isFinite (strict, no coercion)
		Assertions.assertEquals(Boolean.TRUE, cx.eval("Number.isFinite(123)"));
		Assertions.assertEquals(Boolean.FALSE, cx.eval("Number.isFinite('123')"));
		Assertions.assertEquals(Boolean.FALSE, cx.eval("Number.isFinite(Infinity)"));

		// Number.isInteger
		Assertions.assertEquals(Boolean.TRUE, cx.eval("Number.isInteger(123)"));
		Assertions.assertEquals(Boolean.FALSE, cx.eval("Number.isInteger(123.45)"));
		Assertions.assertEquals(Boolean.FALSE, cx.eval("Number.isInteger('123')"));
		Assertions.assertEquals(Boolean.FALSE, cx.eval("Number.isInteger(Infinity)"));

		// Number.isSafeInteger
		Assertions.assertEquals(Boolean.TRUE, cx.eval("Number.isSafeInteger(9007199254740991)"));
		Assertions.assertEquals(Boolean.FALSE, cx.eval("Number.isSafeInteger(9007199254740992)"));
		Assertions.assertEquals(Boolean.TRUE, cx.eval("Number.isSafeInteger(-9007199254740991)"));

		// Constants
		Assertions.assertEquals(2.220446049250313e-16, ((Number) cx.eval("Number.EPSILON")).doubleValue(), 1e-25);
		Assertions.assertEquals(9007199254740991.0, ((Number) cx.eval("Number.MAX_SAFE_INTEGER")).doubleValue());
		Assertions.assertEquals(-9007199254740991.0, ((Number) cx.eval("Number.MIN_SAFE_INTEGER")).doubleValue());
	}

	@Test
	public void testEvalFunction() {
		JSContext cx = new JSContext();

		Assertions.assertEquals(3, ((Number) cx.eval("eval('1 + 2')")).intValue());
		Assertions.assertEquals(20, ((Number) cx.eval("eval('let x = 10; x * 2')")).intValue());
		Assertions.assertEquals(123, ((Number) cx.eval("eval(123)")).intValue());
		Assertions.assertEquals(JSUndefined.INSTANCE, cx.eval("eval()"));
	}
}
