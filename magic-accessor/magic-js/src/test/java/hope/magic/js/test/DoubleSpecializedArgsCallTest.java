package hope.magic.js.test;

import hope.magic.js.runtime.JSContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class DoubleSpecializedArgsCallTest {

	@Test
	public void testDoubleSpecializedFunctionInvocation() {
		JSContext cx = new JSContext();
		Object res1 = cx.eval("""
			function add(a, b) {
				return a + b;
			}
			add(1.5, 2.5);
		""");
		assertEquals(4.0, ((Number) res1).doubleValue(), 1e-9);
	}

	@Test
	public void testDoubleSpecializedMemberMethodInvocation() {
		JSContext cx = new JSContext();
		Object res2 = cx.eval("""
			let calc = {
				mul(x, y) {
					return x * y;
				}
			};
			calc.mul(2.5, 4.0);
		""");
		assertEquals(10.0, ((Number) res2).doubleValue(), 1e-9);
	}

	@Test
	public void testJavaInteropDirectDoubleCall() {
		JSContext cx = new JSContext();
		Object res3 = cx.eval("""
			Math.pow(2.0, 3.0);
		""");
		assertEquals(8.0, ((Number) res3).doubleValue(), 1e-9);

		Object resSin = cx.eval("""
			Math.sin(0.0);
		""");
		assertEquals(0.0, ((Number) resSin).doubleValue(), 1e-9);
	}

	@Test
	public void testMixedTypeArgsInvocation() {
		JSContext cx = new JSContext();
		Object res4 = cx.eval("""
			function format(a, sep, b) {
				return a + sep + b;
			}
			format(1.5, " -> ", 2.5);
		""");
		assertEquals("1.5 -> 2.5", res4.toString());
	}

	@Test
	public void testRecursiveDoubleCall() {
		JSContext cx = new JSContext();
		Object res5 = cx.eval("""
			function sumTo(n, acc) {
				if (n <= 0.0) return acc;
				return sumTo(n - 1.0, acc + n);
			}
			sumTo(10.0, 0.0);
		""");
		assertEquals(55.0, ((Number) res5).doubleValue(), 1e-9);
	}

	@Test
	public void testMathMinMaxVariadic() {
		JSContext cx = new JSContext();
		assertEquals(Double.NEGATIVE_INFINITY, ((Number) cx.eval("Math.max();")).doubleValue());
		assertEquals(Double.POSITIVE_INFINITY, ((Number) cx.eval("Math.min();")).doubleValue());
		assertEquals(42.0, ((Number) cx.eval("Math.max(42);")).doubleValue(), 1e-9);
		assertEquals(42.0, ((Number) cx.eval("Math.min(42);")).doubleValue(), 1e-9);
		assertEquals(20.0, ((Number) cx.eval("Math.max(10, 20);")).doubleValue(), 1e-9);
		assertEquals(10.0, ((Number) cx.eval("Math.min(10, 20);")).doubleValue(), 1e-9);
		assertEquals(30.0, ((Number) cx.eval("Math.max(10, 30, 20);")).doubleValue(), 1e-9);
		assertEquals(10.0, ((Number) cx.eval("Math.min(10, 30, 20);")).doubleValue(), 1e-9);
		assertEquals(40.0, ((Number) cx.eval("Math.max(10, 30, 20, 40);")).doubleValue(), 1e-9);
		assertEquals(10.0, ((Number) cx.eval("Math.min(10, 30, 20, 40);")).doubleValue(), 1e-9);
		assertEquals(50.0, ((Number) cx.eval("Math.max(10, 30, 20, 40, 50, 5);")).doubleValue(), 1e-9);
		assertEquals(5.0, ((Number) cx.eval("Math.min(10, 30, 20, 40, 50, 5);")).doubleValue(), 1e-9);
	}

	@Test
	public void testMathImulClz32Fround() {
		JSContext cx = new JSContext();
		// Math.imul
		assertEquals(8.0, ((Number) cx.eval("Math.imul(2, 4);")).doubleValue());
		assertEquals(-8.0, ((Number) cx.eval("Math.imul(-1, 8);")).doubleValue());
		assertEquals(-5.0, ((Number) cx.eval("Math.imul(0xffffffff, 5);")).doubleValue());
		assertEquals(1.0, ((Number) cx.eval("Math.imul(0x7fffffff, 0x7fffffff);")).doubleValue());

		// Math.clz32
		assertEquals(32.0, ((Number) cx.eval("Math.clz32(0);")).doubleValue());
		assertEquals(31.0, ((Number) cx.eval("Math.clz32(1);")).doubleValue());
		assertEquals(22.0, ((Number) cx.eval("Math.clz32(1000);")).doubleValue());
		assertEquals(0.0, ((Number) cx.eval("Math.clz32(-1);")).doubleValue());
		assertEquals(31.0, ((Number) cx.eval("Math.clz32(true);")).doubleValue());
		assertEquals(32.0, ((Number) cx.eval("Math.clz32(NaN);")).doubleValue());

		// Math.fround
		assertEquals(1.5, ((Number) cx.eval("Math.fround(1.5);")).doubleValue(), 1e-9);
		assertEquals((double) (float) 1.337, ((Number) cx.eval("Math.fround(1.337);")).doubleValue(), 1e-9);
		assertEquals(0.0, ((Number) cx.eval("Math.fround(0);")).doubleValue(), 1e-9);
	}

	@Test
	public void testObjectIsSameValue() {
		JSContext cx = new JSContext();
		assertEquals(true, cx.eval("Object.is(NaN, NaN);"));
		assertEquals(false, cx.eval("Object.is(+0, -0);"));
		assertEquals(true, cx.eval("Object.is(-0, -0);"));
		assertEquals(true, cx.eval("Object.is(+0, +0);"));
		assertEquals(true, cx.eval("Object.is(10, 10);"));
		assertEquals(true, cx.eval("Object.is(10, 10.0);"));
		assertEquals(false, cx.eval("Object.is(10, 20);"));
		assertEquals(true, cx.eval("Object.is(undefined, undefined);"));
		assertEquals(true, cx.eval("Object.is(null, null);"));
		assertEquals(false, cx.eval("Object.is(null, undefined);"));
		assertEquals(true, cx.eval("Object.is('abc', 'abc');"));
		assertEquals(false, cx.eval("Object.is('abc', 'def');"));
		assertEquals(true, cx.eval("Object.is(true, true);"));
		assertEquals(false, cx.eval("Object.is(true, false);"));
		assertEquals(false, cx.eval("Object.is({}, {});"));
		assertEquals(true, cx.eval("let o = {}; Object.is(o, o);"));
	}

	@Test
	public void testStringToDoubleLiterals() {
		JSContext cx = new JSContext();
		// Non-decimal string literals (hex, bin, octal)
		assertEquals(255.0, ((Number) cx.eval("Number('0xFF');")).doubleValue(), 1e-9);
		assertEquals(16.0, ((Number) cx.eval("Number('0X10');")).doubleValue(), 1e-9);
		assertEquals(5.0, ((Number) cx.eval("Number('0b101');")).doubleValue(), 1e-9);
		assertEquals(15.0, ((Number) cx.eval("Number('0B1111');")).doubleValue(), 1e-9);
		assertEquals(63.0, ((Number) cx.eval("Number('0o77');")).doubleValue(), 1e-9);
		assertEquals(8.0, ((Number) cx.eval("Number('0O10');")).doubleValue(), 1e-9);

		// Empty and whitespace strings
		assertEquals(0.0, ((Number) cx.eval("Number('');")).doubleValue(), 1e-9);
		assertEquals(0.0, ((Number) cx.eval("Number('   ');")).doubleValue(), 1e-9);
		assertEquals(0.0, ((Number) cx.eval("Number('\\t\\n\\r');")).doubleValue(), 1e-9);

		// String equality with numbers
		assertEquals(true, cx.eval("'0x10' == 16;"));
		assertEquals(true, cx.eval("16 == '0x10';"));
		assertEquals(true, cx.eval("'0b101' == 5;"));
		assertEquals(true, cx.eval("5 == '0b101';"));
		assertEquals(true, cx.eval("'0o10' == 8;"));
		assertEquals(true, cx.eval("8 == '0o10';"));

		// Arithmetic fast-paths with string
		assertEquals(97.5, ((Number) cx.eval("'100' - 2.5;")).doubleValue(), 1e-9);
		assertEquals(97.5, ((Number) cx.eval("100.0 - '2.5';")).doubleValue(), 1e-9);
		assertEquals(60.0, ((Number) cx.eval("'20' * 3.0;")).doubleValue(), 1e-9);
		assertEquals(60.0, ((Number) cx.eval("20.0 * '3';")).doubleValue(), 1e-9);
		assertEquals(10.0, ((Number) cx.eval("'30' / 3.0;")).doubleValue(), 1e-9);
		assertEquals(10.0, ((Number) cx.eval("30.0 / '3';")).doubleValue(), 1e-9);
		assertEquals(3.0, ((Number) cx.eval("'15' % 4.0;")).doubleValue(), 1e-9);
		assertEquals(3.0, ((Number) cx.eval("15.0 % '4';")).doubleValue(), 1e-9);

		// Relational comparisons with string & number
		assertEquals(true, cx.eval("'5' < 10;"));
		assertEquals(false, cx.eval("10 < '5';"));
		assertEquals(true, cx.eval("15 > '10';"));
		assertEquals(false, cx.eval("'10' > 15;"));
		assertEquals(true, cx.eval("'10' <= 10;"));
		assertEquals(true, cx.eval("10 >= '10';"));
	}

	@Test
	public void testNumberToStringAndFastIntCache() {
		// Verify cached references for [-128, 1023] via JSOps and JSIndexOps
		assertSame(hope.magic.js.runtime.JSOps.fastIntToString(-128), hope.magic.js.runtime.JSOps.fastIntToString(-128));
		assertSame(hope.magic.js.runtime.JSOps.fastIntToString(0), hope.magic.js.runtime.JSOps.fastIntToString(0));
		assertSame(hope.magic.js.runtime.JSOps.fastIntToString(100), hope.magic.js.runtime.JSOps.fastIntToString(100));
		assertSame(hope.magic.js.runtime.JSOps.fastIntToString(1023), hope.magic.js.runtime.JSOps.fastIntToString(1023));

		assertSame(hope.magic.js.runtime.JSOps.fastIntToString(0), hope.magic.js.runtime.JSIndexOps.fastIntToString(0));

		assertEquals("-128", hope.magic.js.runtime.JSOps.fastIntToString(-128));
		assertEquals("0", hope.magic.js.runtime.JSOps.fastIntToString(0));
		assertEquals("1023", hope.magic.js.runtime.JSOps.fastIntToString(1023));
		assertEquals("-129", hope.magic.js.runtime.JSOps.fastIntToString(-129));
		assertEquals("1024", hope.magic.js.runtime.JSOps.fastIntToString(1024));

		// Verify numberToString / toStr hits the cache
		assertSame(hope.magic.js.runtime.JSOps.fastIntToString(1), hope.magic.js.runtime.JSOps.numberToString(1.0));
		assertSame(hope.magic.js.runtime.JSOps.fastIntToString(-10), hope.magic.js.runtime.JSOps.numberToString(-10.0));
		assertEquals("0", hope.magic.js.runtime.JSOps.numberToString(-0.0));
		assertEquals("0", hope.magic.js.runtime.JSOps.numberToString(0.0));
		assertEquals("1.5", hope.magic.js.runtime.JSOps.numberToString(1.5));
		assertEquals("50000", hope.magic.js.runtime.JSOps.numberToString(50000.0));

		// Safe integer fast paths (> 32-bit int, e.g. timestamps and 2^53 - 1)
		assertEquals("1727358000000", hope.magic.js.runtime.JSOps.numberToString(1727358000000.0));
		assertEquals("9007199254740991", hope.magic.js.runtime.JSOps.numberToString(9007199254740991.0));
		assertEquals("-9007199254740991", hope.magic.js.runtime.JSOps.numberToString(-9007199254740991.0));
	}

	@Test
	public void testToPropertyKeySymbolPrimitive() {
		JSContext cx = new JSContext();
		// Test object with Symbol.toPrimitive returning a Symbol as property key
		Object res = cx.eval("""
			const sym = Symbol("secretKey");
			const wrapper = {
				[Symbol.toPrimitive](hint) {
					return sym;
				}
			};
			const obj = { [sym]: "found_it" };
			obj[wrapper];
		""");
		assertEquals("found_it", res);
	}

	@Test
	public void testDoubleConversionToFixedAndToPrecision() {
		JSContext cx = new JSContext();

		// Number.prototype.toFixed
		assertEquals("123.46", cx.eval("(123.456).toFixed(2);"));
		assertEquals("123.400", cx.eval("(123.4).toFixed(3);"));
		assertEquals("0.00", cx.eval("(0.0).toFixed(2);"));
		assertEquals("-2.3", cx.eval("(-2.34).toFixed(1);"));
		assertEquals("5", cx.eval("(5).toFixed();"));

		// Number.prototype.toPrecision
		assertEquals("123.5", cx.eval("(123.456).toPrecision(4);"));
		assertEquals("0.00012", cx.eval("(0.000123).toPrecision(2);"));
		assertEquals("1.2e+4", cx.eval("(12345).toPrecision(2);"));

		// DoubleConversion shortest string (ECMA-262 compliance)
		assertEquals("0.30000000000000004", cx.eval("(0.1 + 0.2).toString();"));
		assertEquals("1e-7", cx.eval("(1e-7).toString();"));
		assertEquals("0.000001", cx.eval("(1e-6).toString();"));
		assertEquals("100000000000000000000", cx.eval("(1e20).toString();"));
		assertEquals("1e+21", cx.eval("(1e21).toString();"));
	}

	@Test
	public void testSchubfachCornerCases() {
		JSContext cx = new JSContext();

		// Min / Max values
		assertEquals("5e-324", cx.eval("Number.MIN_VALUE.toString();"));
		assertEquals("1.7976931348623157e+308", cx.eval("Number.MAX_VALUE.toString();"));

		// Zeros & Specials
		assertEquals("0", cx.eval("(0.0).toString();"));
		assertEquals("0", cx.eval("(-0.0).toString();"));
		assertEquals("Infinity", cx.eval("(1 / 0).toString();"));
		assertEquals("-Infinity", cx.eval("(-1 / 0).toString();"));
		assertEquals("NaN", cx.eval("(0 / 0).toString();"));

		// Powers of 2 and fractions
		assertEquals("0.5", cx.eval("(0.5).toString();"));
		assertEquals("0.25", cx.eval("(0.25).toString();"));
		assertEquals("0.125", cx.eval("(0.125).toString();"));
		assertEquals("0.0625", cx.eval("(0.0625).toString();"));
		assertEquals("0.03125", cx.eval("(0.03125).toString();"));

		// Subnormals
		assertEquals("1e-323", cx.eval("(1e-323).toString();"));
		assertEquals("1e-323", cx.eval("(2 * Number.MIN_VALUE).toString();"));
		assertEquals("1.5e-323", cx.eval("(3 * Number.MIN_VALUE).toString();"));
		assertEquals("2e-323", cx.eval("(4 * Number.MIN_VALUE).toString();"));
	}
}




