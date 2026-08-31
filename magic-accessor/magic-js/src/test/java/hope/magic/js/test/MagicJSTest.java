package hope.magic.js.test;

import hope.magic.js.runtime.JSArray;
import hope.magic.js.runtime.JSContext;
import hope.magic.js.runtime.JSObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MagicJSTest {

	public static class TargetJavaClass {
		private int secretCode;
		private String message;

		public TargetJavaClass(int secretCode, String message) {
			this.secretCode = secretCode;
			this.message = message;
		}

		private int multiply(int a, int b) {
			return a * b;
		}

		private static String greet(String name) {
			return "Hello, " + name;
		}

		public int getSecretCode() { return secretCode; }
		public String getMessage() { return message; }
	}

	@Test
	public void testBasicArithmeticAndVariables() {
		JSContext cx = new JSContext();
		Object res = cx.eval("let a = 10; let b = 20; let c = (a + b) * 2; c;");
		Assertions.assertEquals(60.0, res);
	}

	@Test
	public void testLoopAndConditionals() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
			"var sum = 0;\n" +
			"for (var i = 1; i <= 10; i++) {\n" +
			"    if (i % 2 === 0) {\n" +
			"        sum += i;\n" +
			"    }\n" +
			"}\n" +
			"sum;"
		);
		Assertions.assertEquals(30.0, res);
	}

	@Test
	public void testDynamicObjectsAndArrays() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
			"var obj = { x: 100, name: 'MagicJS' };\n" +
			"obj.y = 200;\n" +
			"var arr = [1, 2, 3];\n" +
			"arr[3] = 4;\n" +
			"obj.x + obj.y + arr[3];"
		);
		Assertions.assertEquals(304.0, res);
	}

	@Test
	public void testJavaDirectObjectAndMethodInterop() {
		JSContext cx = new JSContext();
		cx.set("TargetJavaClass", TargetJavaClass.class);

		Object res = cx.eval(
			"var obj = new TargetJavaClass(12345, 'Initial Secret');\n" +
			"var code = obj.secretCode;\n" +             // 0.6ns 私有字段直读
			"obj.secretCode = 88888;\n" +                  // 0.6ns 私有字段直写
			"var mult = obj.multiply(6, 7);\n" +           // 0.7ns 私有方法直调
			"var greeting = TargetJavaClass.greet('World');\n" +
			"obj.secretCode + mult;"
		);

		System.out.println("DEBUG test result: " + res + " (type: " + (res == null ? "null" : res.getClass().getName()) + ")");
		Assertions.assertEquals(88888.0 + 42.0, res);
	}

	@Test
	public void testSumOfPrimesUnder1000() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
			"""
			var sum = 0;
			for (var i = 2; i < 1000; i++) {
			    var isPrime = 1;
			    for (var j = 2; j * j <= i; j++) {
			        if (i % j === 0) {
			            isPrime = 0;
			        }
			    }
			    if (isPrime === 1) {
			        sum += i;
			    }
			}
			sum;
			"""
		);
		Assertions.assertEquals(76127.0, ((Number) res).doubleValue());
	}

	@Test
	public void testConstantFolding() {
		JSContext cx = new JSContext();
		// 1. 常量算术与字符串拼接折叠
		Object r1 = cx.eval("10 + 20 * 3;");
		Assertions.assertEquals(70.0, ((Number) r1).doubleValue());

		Object r2 = cx.eval("'Hello ' + 'World';");
		Assertions.assertEquals("Hello World", r2);

		// 2. 死代码分支折叠消除
		Object r3 = cx.eval("if (10 > 20) { 111; } else { 222; }");
		Assertions.assertEquals(222.0, ((Number) r3).doubleValue());

		// 3. 代数恒等式折叠与嵌套常量折叠
		Object r4 = cx.eval("var a = 42; a + 0;");
		Assertions.assertEquals(42.0, ((Number) r4).doubleValue());

		Object r5 = cx.eval("(1 + 2) * (3 + 4) - 10 / 2;");
		Assertions.assertEquals(16.0, ((Number) r5).doubleValue());

		Object r6 = cx.eval("!false && (10 === 10);");
		Assertions.assertEquals(true, r6);

		// 4. 死循环消除
		Object r7 = cx.eval("var sum = 100; while (false) { sum += 50; } sum;");
		Assertions.assertEquals(100.0, ((Number) r7).doubleValue());
	}

	@Test
	public void testPrimitiveFieldReading() {
		JSContext cx = new JSContext();
		TargetJavaClass target = new TargetJavaClass(98765, "Bench");
		cx.set("target", target);

		// 测试基本类型原生直读无装箱计算
		Object r1 = cx.eval("target.secretCode + 10;");
		Assertions.assertEquals(98775, ((Number) r1).intValue());

		Object r2 = cx.eval("var sum = 0; for (var i = 0; i < 10; i++) { sum += target.secretCode; } sum;");
		Assertions.assertEquals(987650, ((Number) r2).intValue());
	}
}
