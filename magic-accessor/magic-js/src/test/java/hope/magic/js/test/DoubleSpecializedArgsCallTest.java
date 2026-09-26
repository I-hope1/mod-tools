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
}
