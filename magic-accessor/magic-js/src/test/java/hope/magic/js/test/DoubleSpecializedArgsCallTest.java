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
}
