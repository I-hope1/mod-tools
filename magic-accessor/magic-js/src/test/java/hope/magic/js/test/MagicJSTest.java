package hope.magic.js.test;

import hope.magic.js.runtime.JSArray;
import hope.magic.js.runtime.JSContext;
import hope.magic.js.runtime.JSObject;
import hope.magic.js.runtime.JSScript;
import hope.magic.js.runtime.JSShape;
import hope.magic.js.runtime.SymbolTable;
import hope.magic.js.compiler.JSCompiler;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.lang.reflect.Method;

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

		public String overloadTest(int a) { return "int:" + a; }
		public String overloadTest(String s) { return "str:" + s; }

		public String nullOverload(Object o) { return "object"; }
		public String nullOverload(String s) { return "string"; }

		public String specificOverload(CharSequence cs) { return "charseq"; }
		public String specificOverload(String s) { return "string"; }
		public String specificOverload(Object o) { return "object"; }

		public String wideningOverload(double d) { return "double"; }
		public String wideningOverload(long l) { return "long"; }
		public String wideningOverload(Object o) { return "object"; }

		public String varargOverload(String s) { return "fixed:" + s; }
		public String varargOverload(String... ss) { return "vararg:" + ss.length; }

		public interface CustomCalculator {
			int add(int a, int b);
			int multiply(int a, int b);
		}

		public boolean callbackExecuted = false;
		public void runCallback(Runnable r) {
			callbackExecuted = true;
			r.run();
		}

		public String processString(java.util.function.Function<String, String> mapper, String input) {
			return mapper.apply(input);
		}

		public int computeBinary(java.util.function.IntBinaryOperator op, int a, int b) {
			return op.applyAsInt(a, b);
		}

		public int executeCalc(CustomCalculator calc, int a, int b) {
			return calc.add(a, b) + calc.multiply(a, b);
		}

		public double doubleVal = 3.14;
		public float floatVal = 1.5f;
		public long longVal = 10000000000L;
		public short shortVal = 300;
		public byte byteVal = 12;
		public boolean boolVal = true;

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

	@Test
	public void testArrayIndexAndNegativeIndex() {
		JSContext cx = new JSContext();
		// 1. 数组使用数字下标并验证真正底层元素数组扩容与读取 (Issue 1)
		Object r1 = cx.eval("""
			var arr = [1, 2, 3];
			arr[3] = 4;
			arr.length;
		""");
		Assertions.assertEquals(4.0, ((Number) r1).doubleValue());

		Object r2 = cx.eval("""
			var arr = [10, 20, 30];
			arr[5] = 60;
			arr[0] + arr[1] + arr[2] + arr[5];
		""");
		Assertions.assertEquals(120.0, ((Number) r2).doubleValue());

		Object r3 = cx.eval("""
			var arr = [10, 20, 30];
			arr[5] = 60;
			arr[4] === undefined;
		""");
		Assertions.assertEquals(true, r3);

		Object r4 = cx.eval("var arr = ['a', 'b', 'c', 'd']; arr[3];");
		Assertions.assertEquals("d", r4);

		// 2. 负数下标作为普通对象属性处理且不改变数组实际长度 (Issue 10)
		Object r5 = cx.eval("var arr = [10, 20]; arr[-1] = 99; arr[-1];");
		Assertions.assertEquals(99.0, ((Number) r5).doubleValue());

		Object r6 = cx.eval("var arr = [10, 20]; arr[-1] = 99; arr.length;");
		Assertions.assertEquals(2.0, ((Number) r6).doubleValue());

		// 3. 负数数字下标与负数字符串下标是同一个属性
		Object r7 = cx.eval("""
			var arr = [];
			arr[-1] = 10;
			arr["-1"];
		""");
		Assertions.assertEquals(10.0, ((Number) r7).doubleValue());

		// 4. 超大合法内部索引稀疏存储与防止 OOM，以及支持存储 null
		Object r8 = cx.eval("""
			var arr = [];
			arr[1000000000] = 1;
			arr.length;
		""");
		Assertions.assertEquals(1000000001.0, ((Number) r8).doubleValue());

		Object r9 = cx.eval("""
			var arr = [];
			arr[1000000000] = 1;
			arr[1000000000];
		""");
		Assertions.assertEquals(1.0, ((Number) r9).doubleValue());

		Object r10 = cx.eval("""
			var arr = [];
			arr[1000000000] = null;
			arr[1000000000] === null;
		""");
		Assertions.assertEquals(true, r10);
	}

	@Test
	public void testNullPropertyAndUndefined() {
		JSContext cx = new JSContext();
		// Issue 2: null 属性不能被读成 undefined
		Object r1 = cx.eval("var obj = { x: null }; obj.x === null;");
		Assertions.assertEquals(true, r1);

		Object r2 = cx.eval("var obj = { x: null }; obj.x === undefined;");
		Assertions.assertEquals(false, r2);

		Object r3 = cx.eval("var obj = {}; obj.x === undefined;");
		Assertions.assertEquals(true, r3);
	}

	@Test
	public void testPrimitiveFieldTypes() {
		JSContext cx = new JSContext();
		TargetJavaClass target = new TargetJavaClass(123, "test");
		cx.set("target", target);

		// Issue 3 & 4: 各类基本字段宽度正确读取 (通用 Object 路径)
		Object r1 = cx.eval("var d = target.doubleVal; d;");
		Assertions.assertEquals(3.14, ((Number) r1).doubleValue(), 0.0001);

		Object r2 = cx.eval("var f = target.floatVal; f;");
		Assertions.assertEquals(1.5, ((Number) r2).doubleValue(), 0.0001);

		Object r3 = cx.eval("var l = target.longVal; l;");
		Assertions.assertEquals(10000000000.0, ((Number) r3).doubleValue(), 0.0001);

		Object r4 = cx.eval("var s = target.shortVal; s;");
		Assertions.assertEquals(300.0, ((Number) r4).doubleValue(), 0.0001);

		Object r5 = cx.eval("var b = target.byteVal; b;");
		Assertions.assertEquals(12.0, ((Number) r5).doubleValue(), 0.0001);

		Object r6 = cx.eval("var bool = target.boolVal; bool;");
		Assertions.assertEquals(true, r6);

		// 测试类型专用 getter (getPropInt, getPropDouble) 读取不同宽度的基本类型字段
		Object rIntFromLong = cx.eval("var x = 0; x = target.longVal; x;");
		Assertions.assertEquals(10000000000.0, ((Number) rIntFromLong).doubleValue(), 0.0001);

		Object rDoubleFromShort = cx.eval("var x = 0.0; x = target.shortVal; x;");
		Assertions.assertEquals(300.0, ((Number) rDoubleFromShort).doubleValue(), 0.0001);

		Object rIntFromSecret = cx.eval("var x = 0; x = target.secretCode; x;");
		Assertions.assertEquals(123.0, ((Number) rIntFromSecret).doubleValue(), 0.0001);

		// 测试基本类型字段写入 (Setter 路径及 Java 原生实例同步检验)
		cx.eval("""
			target.doubleVal = 9.99;
			target.floatVal = 2.5;
			target.longVal = 50000000000;
			target.shortVal = 1234;
			target.byteVal = 56;
			target.boolVal = false;
		""");
		Assertions.assertEquals(9.99, target.doubleVal, 0.0001);
		Assertions.assertEquals(2.5f, target.floatVal, 0.0001);
		Assertions.assertEquals(50000000000L, target.longVal);
		Assertions.assertEquals((short) 1234, target.shortVal);
		Assertions.assertEquals((byte) 56, target.byteVal);
		Assertions.assertFalse(target.boolVal);

		Object rReadAfterWrite = cx.eval("target.doubleVal + target.floatVal + target.shortVal;");
		Assertions.assertEquals(9.99 + 2.5 + 1234.0, ((Number) rReadAfterWrite).doubleValue(), 0.0001);
	}

	@Test
	public void testFloatModulo() {
		JSContext cx = new JSContext();
		// Issue 5: 5.5 % 2 应为 1.5，5.5 % 0 应为 NaN
		Object r1 = cx.eval("5.5 % 2;");
		Assertions.assertEquals(1.5, ((Number) r1).doubleValue(), 0.0001);

		Object r2 = cx.eval("5.5 % 0;");
		Assertions.assertTrue(Double.isNaN(((Number) r2).doubleValue()));
	}

	@Test
	public void testCompoundDivision() {
		JSContext cx = new JSContext();
		// Issue 6: var x = 1; x /= 2 应得到 0.5
		Object r1 = cx.eval("var x = 1; x /= 2; x;");
		Assertions.assertEquals(0.5, ((Number) r1).doubleValue(), 0.0001);
	}

	@Test
	public void testIntegerAdditionOverflowSafety() {
		JSContext cx = new JSContext();
		// Issue 7: 2147483647 + 1 应为 2147483648
		Object r1 = cx.eval("2147483647 + 1;");
		Assertions.assertEquals(2147483648.0, ((Number) r1).doubleValue(), 0.0001);
	}

	@Test
	public void testShortCircuitLogic() {
		JSContext cx = new JSContext();
		// Issue 8: 短路逻辑，右侧未定义变量或方法不应执行抛错
		Object r1 = cx.eval("false && nonexistentVar.nonexistentMethod();");
		Assertions.assertEquals(false, r1);

		Object r2 = cx.eval("true || nonexistentVar.nonexistentMethod();");
		Assertions.assertEquals(true, r2);

		Object r3 = cx.eval("'hello' || 'world';");
		Assertions.assertEquals("hello", r3);

		Object r4 = cx.eval("null || 'fallback';");
		Assertions.assertEquals("fallback", r4);
	}

	@Test
	public void testAssignmentReturnValues() {
		JSContext cx = new JSContext();
		// Issue 9: (obj.x = 3) + 1 应得到 4
		Object r1 = cx.eval("var obj = {}; (obj.x = 3) + 1;");
		Assertions.assertEquals(4.0, ((Number) r1).doubleValue());

		Object r2 = cx.eval("var arr = [0]; (arr[0] = 5) * 2;");
		Assertions.assertEquals(10.0, ((Number) r2).doubleValue());
	}

	@Test
	public void testOverloadResolution() {
		JSContext cx = new JSContext();
		TargetJavaClass target = new TargetJavaClass(1, "test");
		cx.set("target", target);

		// Issue 11: 区分 f(int) 与 f(String)，并验证多脚本调用与逆序调用无缓存污染
		Object r1 = cx.eval("target.overloadTest(123);");
		Assertions.assertEquals("int:123", r1);

		Object r2 = cx.eval("target.overloadTest('hello');");
		Assertions.assertEquals("str:hello", r2);

		// 逆序与连续重载调用
		Object r3 = cx.eval("target.overloadTest('world'); target.overloadTest(456);");
		Assertions.assertEquals("int:456", r3);

		Object r4 = cx.eval("target.overloadTest(789); target.overloadTest('final');");
		Assertions.assertEquals("str:final", r4);

		// 1. null 匹配最具体子类型 (String 优先于 Object)
		cx.eval("var n = null;");
		Object rNull = cx.eval("target.nullOverload(n);");
		Assertions.assertEquals("string", rNull);

		// 2. 继承树最具体类型 (String 优先于 CharSequence 优先于 Object)
		Object rSpecific = cx.eval("target.specificOverload('hello');");
		Assertions.assertEquals("string", rSpecific);

		// 3. 基本类型拓宽距离 (Double 字面量精确匹配 double；Java Integer 优先拓宽为 long 而非 double)
		Object rDoubleLiteral = cx.eval("target.wideningOverload(12345);");
		Assertions.assertEquals("double", rDoubleLiteral);

		cx.set("javaInt", Integer.valueOf(12345));
		Object rWidening = cx.eval("target.wideningOverload(javaInt);");
		Assertions.assertEquals("long", rWidening);

		// 4. 固定参数 vs Varargs 优先级
		Object rFixed = cx.eval("target.varargOverload('one');");
		Assertions.assertEquals("fixed:one", rFixed);

		Object rVarargs = cx.eval("target.varargOverload('a', 'b', 'c');");
		Assertions.assertEquals("vararg:3", rVarargs);
	}

	@Test
	public void testCanonicalArrayIndexAndDirectAPI() {
		JSContext cx = new JSContext();
		// 1. JSArray Java Direct API 不应抛出 IndexOutOfBoundsException
		JSArray rawArr = new JSArray();
		rawArr.setElement(-1, 999);
		Assertions.assertEquals(999.0, ((Number)rawArr.getElement(-1)).doubleValue());
		Assertions.assertEquals(0, rawArr.length());

		// 2. 规范数组索引 vs 普通字符串属性 (ECMAScript 规范测试)
		Object r1 = cx.eval("""
			var arr = [10, 20];
			arr["01"] = 100;
			arr["-0"] = 200;
			arr["1.0"] = 300;
			arr.length;
		""");
		// "01", "-0", "1.0" 均不是规范数组下标，不改变 length
		Assertions.assertEquals(2.0, ((Number) r1).doubleValue());

		Object r2 = cx.eval("""
			var arr = [10, 20];
			arr["01"] = 100;
			arr["-0"] = 200;
			arr["1.0"] = 300;
			arr["01"] + arr["-0"] + arr["1.0"];
		""");
		Assertions.assertEquals(600.0, ((Number) r2).doubleValue());

		// 3. 数字浮点 1.0 作为下标访问时，转换为字符串 "1"（属于规范下标）
		Object r3 = cx.eval("""
			var arr = [10, 20, 30];
			arr[1.0] = 99;
			arr[1];
		""");
		Assertions.assertEquals(99.0, ((Number) r3).doubleValue());

		// 4. 超大索引符合 ECMAScript 规范：4294967294 稀疏存储且 length 变为 4294967295；4294967295 超出范围作为普通属性
		Object r4 = cx.eval("""
			var arr = [10, 20];
			arr[4294967294] = 999;
			arr.length;
		""");
		Assertions.assertEquals(4294967295.0, ((Number) r4).doubleValue());

		Object r5 = cx.eval("""
			var arr = [10, 20];
			arr[4294967294] = 999;
			arr[4294967294];
		""");
		Assertions.assertEquals(999.0, ((Number) r5).doubleValue());

		Object r6 = cx.eval("""
			var arr = [10, 20];
			arr[4294967295] = 999;
			arr.length;
		""");
		Assertions.assertEquals(2.0, ((Number) r6).doubleValue());

		Object r7 = cx.eval("""
			var arr = [10, 20];
			arr[4294967295] = 999;
			arr[4294967295];
		""");
		Assertions.assertEquals(999.0, ((Number) r7).doubleValue());

		// 5. 非法 length 赋值应严格抛错拒绝 (1.5, NaN, Infinity, -1)
		Assertions.assertTrue(Double.isNaN(((Number) cx.eval("NaN;")).doubleValue()));
		Assertions.assertTrue(Double.isInfinite(((Number) cx.eval("Infinity;")).doubleValue()));
		Assertions.assertThrows(IllegalArgumentException.class, () -> cx.eval("var arr = [1, 2]; arr.length = 1.5;"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> cx.eval("var arr = [1, 2]; arr.length = -1;"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> cx.eval("var arr = [1, 2]; arr.length = NaN;"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> cx.eval("var arr = [1, 2]; arr.length = Infinity;"));
	}

	@Test
	public void testJavaPrimitiveArraysInterop() {
		JSContext cx = new JSContext();

		// 0. Java List 超大索引安全拦截 (防 OOM)
		java.util.List<Object> list = new java.util.ArrayList<>(java.util.List.of("a", "b"));
		cx.set("list", list);
		cx.eval("list[1000000000] = 'huge';");
		Assertions.assertEquals(2, list.size()); // 超界未发生无休止扩容
		cx.eval("list[2] = 'c';");
		Assertions.assertEquals(3, list.size());
		Assertions.assertEquals("c", list.get(2));

		// 1. int[] 测试与非法/浮点下标测试 (1.5, NaN, Infinity)
		int[] intArr = new int[]{10, 20, 30};
		cx.set("intArr", intArr);
		Object rInt = cx.eval("intArr[0] + intArr[1] + intArr[2];");
		Assertions.assertEquals(60.0, ((Number) rInt).doubleValue());
		cx.eval("intArr[1] = 99; intArr.length;");
		Assertions.assertEquals(99, intArr[1]);
		Object rIntLen = cx.eval("intArr.length;");
		Assertions.assertEquals(3.0, ((Number) rIntLen).doubleValue());

		// 浮点小数与非法数字下标不应被截断访问
		Object rFrac = cx.eval("intArr[1.5] === undefined;");
		Assertions.assertEquals(true, rFrac);

		Object rNaN = cx.eval("intArr[NaN] === undefined;");
		Assertions.assertEquals(true, rNaN);

		Object rInf = cx.eval("intArr[Infinity] === undefined;");
		Assertions.assertEquals(true, rInf);

		cx.eval("intArr[1.5] = 888;");
		Assertions.assertEquals(99, intArr[1]); // 原数组未被误截断写入

		// 2. double[] 测试
		double[] doubleArr = new double[]{1.5, 2.5};
		cx.set("doubleArr", doubleArr);
		Object rDouble = cx.eval("doubleArr[0] + doubleArr[1];");
		Assertions.assertEquals(4.0, ((Number) rDouble).doubleValue());
		cx.eval("doubleArr[0] = 10.5;");
		Assertions.assertEquals(10.5, doubleArr[0], 0.0001);

		// 3. long[] 测试
		long[] longArr = new long[]{10000000000L, 20000000000L};
		cx.set("longArr", longArr);
		Object rLong = cx.eval("longArr[0] + longArr[1];");
		Assertions.assertEquals(30000000000.0, ((Number) rLong).doubleValue());

		// 4. boolean[] 测试
		boolean[] boolArr = new boolean[]{true, false};
		cx.set("boolArr", boolArr);
		Object rBool = cx.eval("boolArr[0];");
		Assertions.assertEquals(true, rBool);
		cx.eval("boolArr[1] = true;");
		Assertions.assertTrue(boolArr[1]);

		// 5. char[] 测试
		char[] charArr = new char[]{'a', 'b', 'c'};
		cx.set("charArr", charArr);
		Object rChar = cx.eval("charArr[1];");
		Assertions.assertEquals("b", rChar);
		cx.eval("charArr[1] = 'z';");
		Assertions.assertEquals('z', charArr[1]);
	}

	@Test
	public void testJSObjectDeleteAndKeys() {
		var obj = new hope.magic.js.runtime.JSObject();
		obj.put("a", 100);
		obj.put("b", 200);
		Assertions.assertTrue(obj.has("a"));
		Assertions.assertTrue(obj.has("b"));
		Assertions.assertEquals(2, obj.keys().size());
		Assertions.assertEquals(2, obj.getProperties().size());

		obj.delete("a");
		Assertions.assertFalse(obj.has("a"));
		Assertions.assertTrue(obj.has("b"));
		Assertions.assertEquals(1, obj.keys().size());
		Assertions.assertTrue(obj.keys().contains("b"));
		Assertions.assertFalse(obj.keys().contains("a"));
		Assertions.assertEquals(1, obj.getProperties().size());
	}

	@Test
	public void testShapeTransitionCachedByPropIdKeepsOldType() {
		// 验证：JSShape.transitions 仅以 propId 为 key 时，重复 addProperty(propId, differentType)
		// 会命中之前缓存的 shape，导致返回的 shape 中 slotTypes 使用第一次添加的类型。
		JSShape root = JSShape.ROOT;
		int propId = SymbolTable.id("x_test_transition");
		JSShape s1 = root.addProperty(propId, JSShape.TYPE_INT);
		int off1 = s1.getOffset(propId);
		Assertions.assertEquals(JSShape.TYPE_INT, s1.getSlotType(off1));
		JSShape s2 = root.addProperty(propId, JSShape.TYPE_DOUBLE);
		// 如果 transitions 只用 propId 寻址，则 s2 会是第一次创建的 shape（类型为 INT）
		Assertions.assertNotSame(s1, s2);
		int off2 = s2.getOffset(propId);
		Assertions.assertEquals(JSShape.TYPE_DOUBLE, s2.getSlotType(off2));
	}

	@Test
	public void testMultiThreadIsolatedContexts() throws Exception {
		int threadCount = 16;
		int iterations = 100;
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
		List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

		for (int t = 0; t < threadCount; t++) {
			final int threadId = t;
			futures.add(pool.submit(() -> {
				JSContext cx = new JSContext();
				for (int i = 0; i < iterations; i++) {
					cx.eval("var obj = { a: " + (threadId * 1000 + i) + " }; obj.b = obj.a + 1; obj.b;");
					cx.set("var_" + threadId + "_" + i, i);
					Object res = cx.eval("var sum = 0; for (var k = 0; k < 10; k++) { sum += k; } sum;");
					Assertions.assertEquals(45.0, ((Number) res).doubleValue());
					Assertions.assertEquals(i, cx.get("var_" + threadId + "_" + i));
				}
			}));
		}

		for (java.util.concurrent.Future<?> future : futures) {
			future.get();
		}
		pool.shutdown();
	}

	@Test
	public void testConcurrentCallSiteInitializationWithCAS() throws Exception {
		// 单一编译产物，跨线程并发复用，针对同一调用点并发争抢不同 Shape
		JSScript script = JSCompiler.compile("obj.x");
		int threadCount = 16;
		int iterations = 500;
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
		java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
		List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

		for (int t = 0; t < threadCount; t++) {
			final int threadId = t;
			futures.add(pool.submit(() -> {
				startLatch.await();
				JSContext cx = new JSContext();
				for (int i = 0; i < iterations; i++) {
					// 偶数线程持 Shape A，奇数线程持 Shape B
					JSObject obj = new JSObject();
					if (threadId % 2 == 0) {
						obj.put("x", 100.0);
						obj.put("extraA", 1.0);
					} else {
						obj.put("extraB", 2.0);
						obj.put("x", 200.0);
					}
					cx.set("obj", obj);
					Object res;
					try {
						res = script.run(cx);
					} catch (Throwable ex) {
						throw new RuntimeException(ex);
					}
					double expected = (threadId % 2 == 0) ? 100.0 : 200.0;
					Assertions.assertEquals(expected, ((Number) res).doubleValue());
				}
				return null;
			}));
		}

		startLatch.countDown();
		for (java.util.concurrent.Future<?> f : futures) {
			f.get();
		}
		pool.shutdown();

		// 验证重构后生成的脚本类无任何冗余静态 $ic 字段，彻底避免元空间膨胀与静态锁竞争
		Class<?> scriptClass = script.getClass();
		boolean hasOldIcField = false;
		for (java.lang.reflect.Field f : scriptClass.getDeclaredFields()) {
			if (f.getName().startsWith("$ic_")) {
				hasOldIcField = true;
				break;
			}
		}
		Assertions.assertFalse(hasOldIcField, "重构后的 Indy PIC 不得在脚本类中展开任何静态 $ic 槽位字段");
	}

	@Test
	public void testNaNComparisonSemantics() {
		JSContext cx = new JSContext();
		// IEEE 754 规范: 任何与 NaN 的关系比较必须恒为 false
		Assertions.assertEquals(false, cx.eval("var x = NaN; x > 1"));
		Assertions.assertEquals(false, cx.eval("var x = NaN; x >= 1"));
		Assertions.assertEquals(false, cx.eval("var x = NaN; x < 1"));
		Assertions.assertEquals(false, cx.eval("var x = NaN; x <= 1"));
		Assertions.assertEquals(false, cx.eval("var x = NaN; 1 > x"));
		Assertions.assertEquals(false, cx.eval("var x = NaN; 1 >= x"));
		Assertions.assertEquals(false, cx.eval("var x = NaN; 1 < x"));
		Assertions.assertEquals(false, cx.eval("var x = NaN; 1 <= x"));

		// 三元表达式判定
		Assertions.assertEquals("OK", cx.eval("var x = NaN; x > 1 ? 'ERR' : 'OK';"));
		Assertions.assertEquals("OK", cx.eval("var x = NaN; x >= 1 ? 'ERR' : 'OK';"));
		Assertions.assertEquals("OK", cx.eval("var x = NaN; x < 1 ? 'ERR' : 'OK';"));
		Assertions.assertEquals("OK", cx.eval("var x = NaN; x <= 1 ? 'ERR' : 'OK';"));

		// 分支语句中的跳转判定
		Assertions.assertEquals("OK", cx.eval("var res; var x = NaN; if (x > 1) res = 'ERR'; else res = 'OK'; res;"));
		Assertions.assertEquals("OK", cx.eval("var res; var x = NaN; if (x >= 1) res = 'ERR'; else res = 'OK'; res;"));
		Assertions.assertEquals("OK", cx.eval("var res; var x = NaN; if (x < 1) res = 'ERR'; else res = 'OK'; res;"));
		Assertions.assertEquals("OK", cx.eval("var res; var x = NaN; if (x <= 1) res = 'ERR'; else res = 'OK'; res;"));
	}

	@Test
	public void testSlashAssignDivisionSemantics() {
		JSContext cx = new JSContext();
		// JS 中 5 /= 2 的结果是浮点 2.5，不能被 IDIV 截断为 2
		Assertions.assertEquals(2.5, ((Number) cx.eval("var a = 5; a /= 2; a;")).doubleValue());
		Assertions.assertEquals(0.5, ((Number) cx.eval("var a = 1; a /= 2; a;")).doubleValue());
	}

	@Test
	public void testBitwiseOperationsAndPrecedence() {
		JSContext cx = new JSContext();

		// 基础位运算
		Assertions.assertEquals(3.0, ((Number) cx.eval("1 | 2")).doubleValue());
		Assertions.assertEquals(2.0, ((Number) cx.eval("6 & 3")).doubleValue());
		Assertions.assertEquals(5.0, ((Number) cx.eval("6 ^ 3")).doubleValue());
		Assertions.assertEquals(-6.0, ((Number) cx.eval("~5")).doubleValue());
		Assertions.assertEquals(16.0, ((Number) cx.eval("2 << 3")).doubleValue());
		Assertions.assertEquals(2.0, ((Number) cx.eval("16 >> 3")).doubleValue());
		Assertions.assertEquals(-1.0, ((Number) cx.eval("-4 >> 2")).doubleValue());

		// 零填充右移 (>>>) 与无符号 32 位溢出转换
		Assertions.assertEquals(4294967295.0, ((Number) cx.eval("-1 >>> 0")).doubleValue());
		Assertions.assertEquals(1073741823.0, ((Number) cx.eval("-4 >>> 2")).doubleValue());

		// 运算符优先级: Additive > Shift > Relational > Equality > BitwiseAnd > BitwiseXor > BitwiseOr
		// 1. Additive vs Shift: 3 << 1 + 2 => 3 << 3 = 24
		Assertions.assertEquals(24.0, ((Number) cx.eval("3 << 1 + 2")).doubleValue());
		// 2. BitwiseAnd vs BitwiseOr: 1 | 2 & 3 => 1 | (2 & 3) = 1 | 2 = 3
		Assertions.assertEquals(3.0, ((Number) cx.eval("1 | 2 & 3")).doubleValue());
		// 3. BitwiseXor vs BitwiseOr: 1 | 2 ^ 2 => 1 | (2 ^ 2) = 1 | 0 = 1
		Assertions.assertEquals(1.0, ((Number) cx.eval("1 | 2 ^ 2")).doubleValue());
		// 4. BitwiseAnd vs BitwiseXor: 1 ^ 2 & 3 => 1 ^ 2 = 3
		Assertions.assertEquals(3.0, ((Number) cx.eval("1 ^ 2 & 3")).doubleValue());
	}

	@Test
	public void testBitwiseCompoundAssignments() {
		JSContext cx = new JSContext();

		Assertions.assertEquals(7.0, ((Number) cx.eval("var a = 5; a |= 2; a;")).doubleValue());
		Assertions.assertEquals(1.0, ((Number) cx.eval("var a = 5; a &= 3; a;")).doubleValue());
		Assertions.assertEquals(6.0, ((Number) cx.eval("var a = 5; a ^= 3; a;")).doubleValue());
		Assertions.assertEquals(20.0, ((Number) cx.eval("var a = 5; a <<= 2; a;")).doubleValue());
		Assertions.assertEquals(5.0, ((Number) cx.eval("var a = 20; a >>= 2; a;")).doubleValue());
		Assertions.assertEquals(4294967295.0, ((Number) cx.eval("var a = -1; a >>>= 0; a;")).doubleValue());
	}

	public static class MegaType0 { public int val = 0; public int getVal() { return 0; } }
	public static class MegaType1 { public int val = 1; public int getVal() { return 1; } }
	public static class MegaType2 { public int val = 2; public int getVal() { return 2; } }
	public static class MegaType3 { public int val = 3; public int getVal() { return 3; } }
	public static class MegaType4 { public int val = 4; public int getVal() { return 4; } }
	public static class MegaType5 { public int val = 5; public int getVal() { return 5; } }
	public static class MegaType6 { public int val = 6; public int getVal() { return 6; } }
	public static class MegaType7 { public int val = 7; public int getVal() { return 7; } }
	public static class MegaType8 { public int val = 8; public int getVal() { return 8; } }
	public static class MegaType9 { public int val = 9; public int getVal() { return 9; } }

	@Test
	public void testMegamorphicCallSite() {
		JSContext cx = new JSContext();
		Object[] javaObjects = new Object[]{
			new MegaType0(), new MegaType1(), new MegaType2(), new MegaType3(), new MegaType4(),
			new MegaType5(), new MegaType6(), new MegaType7(), new MegaType8(), new MegaType9()
		};
		cx.set("javaObjects", javaObjects);

		// 测试通过同一个多态调用点访问 10 种不同 Java 类型的字段和方法 (触发退化至 Megamorphic 稳定分派)
		Object r1 = cx.eval("""
			var sumField = 0;
			for (var i = 0; i < javaObjects.length; i++) {
				sumField += javaObjects[i].val;
			}
			sumField;
		""");
		Assertions.assertEquals(45.0, ((Number) r1).doubleValue());

		Object r2 = cx.eval("""
			var sumMethod = 0;
			for (var i = 0; i < javaObjects.length; i++) {
				sumMethod += javaObjects[i].getVal();
			}
			sumMethod;
		""");
		Assertions.assertEquals(45.0, ((Number) r2).doubleValue());

		// 测试 10 种不同 Shape 的 JSObject 经过同一个调用点
		Object r3 = cx.eval("""
			var objs = [
				{ a: 1 },
				{ b: 0, a: 2 },
				{ c: 0, b: 0, a: 3 },
				{ d: 0, c: 0, b: 0, a: 4 },
				{ e: 0, d: 0, c: 0, b: 0, a: 5 },
				{ f: 0, e: 0, d: 0, c: 0, b: 0, a: 6 },
				{ g: 0, f: 0, e: 0, d: 0, c: 0, b: 0, a: 7 },
				{ h: 0, g: 0, f: 0, e: 0, d: 0, c: 0, b: 0, a: 8 },
				{ i: 0, h: 0, g: 0, f: 0, e: 0, d: 0, c: 0, b: 0, a: 9 },
				{ j: 0, i: 0, h: 0, g: 0, f: 0, e: 0, d: 0, c: 0, b: 0, a: 10 }
			];
			var sumA = 0;
			for (var k = 0; k < objs.length; k++) {
				sumA += objs[k].a;
			}
			sumA;
		""");
		Assertions.assertEquals(55.0, ((Number) r3).doubleValue());
	}

	@Test
	public void testInterfaceAndSAMConversion() {
		JSContext cx = new JSContext();
		TargetJavaClass target = new TargetJavaClass(100, "hello");
		cx.set("target", target);

		// 1. 测试 Runnable 接口自动适配
		cx.eval("""
			var flag = 0;
			target.runCallback(function() {
				flag = 999;
			});
		""");
		Assertions.assertTrue(target.callbackExecuted);
		Assertions.assertEquals(999.0, ((Number) cx.get("flag")).doubleValue());

		// 2. 测试 Function<String, String> 接口自动适配与返回值转换
		Object r1 = cx.eval("""
			target.processString(function(str) {
				return "prefix_" + str;
			}, "world");
		""");
		Assertions.assertEquals("prefix_world", r1);

		// 3. 测试 IntBinaryOperator 基本类型函数式接口自动装箱/拆箱
		Object r2 = cx.eval("""
			target.computeBinary(function(a, b) {
				return a * b + 10;
			}, 3, 4);
		""");
		Assertions.assertEquals(22.0, ((Number) r2).doubleValue());

		// 4. 测试多方法接口通过 JSObject 进行动态代理分派
		Object r3 = cx.eval("""
			var calc = {
				add: function(x, y) { return x + y; },
				multiply: function(x, y) { return x * y; }
			};
			target.executeCalc(calc, 3, 5);
		""");
		// (3 + 5) + (3 * 5) = 8 + 15 = 23
		Assertions.assertEquals(23.0, ((Number) r3).doubleValue());
	}

	@Test
	public void testArrowFunctions() {
		JSContext cx = new JSContext();
		TargetJavaClass target = new TargetJavaClass(100, "hello");
		cx.set("target", target);

		// 1. 单参数无括号箭头函数 x => x * 2
		Object r1 = cx.eval("""
			var doubleFn = x => x * 2;
			doubleFn(21);
		""");
		Assertions.assertEquals(42.0, ((Number) r1).doubleValue());

		// 2. 多参数带括号箭头函数 (a, b) => a + b
		Object r2 = cx.eval("""
			var addFn = (a, b) => a + b;
			addFn(15, 27);
		""");
		Assertions.assertEquals(42.0, ((Number) r2).doubleValue());

		// 3. 无参箭头函数 () => expr
		Object r3 = cx.eval("""
			var getConst = () => 100;
			getConst();
		""");
		Assertions.assertEquals(100.0, ((Number) r3).doubleValue());

		// 4. 块级箭头函数 (x, y) => { var z = x * y; return z + 1; }
		Object r4 = cx.eval("""
			var blockArrow = (x, y) => {
				var z = x * y;
				return z + 1;
			};
			blockArrow(6, 7);
		""");
		Assertions.assertEquals(43.0, ((Number) r4).doubleValue());

		// 5. 箭头函数直接作为 Java SAM 接口参数传递
		Object r5 = cx.eval("""
			target.processString(str => "arrow_" + str, "test");
		""");
		Assertions.assertEquals("arrow_test", r5);

		Object r6 = cx.eval("""
			target.computeBinary((x, y) => x * y + 5, 4, 5);
		""");
		Assertions.assertEquals(25.0, ((Number) r6).doubleValue());
	}

	@Test
	public void testForOfIterable() {
		JSContext cx = new JSContext();

		// 1. 遍历 Java List
		List<Integer> list = List.of(10, 20, 30, 40);
		cx.set("javaList", list);
		Object sum1 = cx.eval("""
			var sum = 0;
			for (var item of javaList) {
				sum += item;
			}
			sum;
		""");
		Assertions.assertEquals(100.0, ((Number) sum1).doubleValue());

		// 2. 遍历 Java Set (Iterable)
		Set<String> set = new LinkedHashSet<>(List.of("a", "b", "c"));
		cx.set("javaSet", set);
		Object joined = cx.eval("""
			var res = "";
			for (let s of javaSet) {
				res += s;
			}
			res;
		""");
		Assertions.assertEquals("abc", joined);

		// 3. 遍历 JS 原生数组 JSArray
		Object sum2 = cx.eval("""
			var arr = [1, 2, 3, 4, 5];
			var total = 0;
			for (var x of arr) {
				total += x;
			}
			total;
		""");
		Assertions.assertEquals(15.0, ((Number) sum2).doubleValue());

		// 4. 遍历 Java 原生数组
		String[] strArr = new String[]{"foo", "bar", "baz"};
		cx.set("strArr", strArr);
		Object arrRes = cx.eval("""
			var out = "";
			for (const item of strArr) {
				out += item + "-";
			}
			out;
		""");
		Assertions.assertEquals("foo-bar-baz-", arrRes);

		// 5. for..of 支持 break 与 continue
		Object sum3 = cx.eval("""
			var nums = [10, 20, 30, 40, 50];
			var s = 0;
			for (var n of nums) {
				if (n === 20) continue;
				if (n === 50) break;
				s += n;
			}
			s;
		""");
		// 10 + 30 + 40 = 80
		Assertions.assertEquals(80.0, ((Number) sum3).doubleValue());
	}

	@Test
	public void testRegExpSupport() throws Exception {
		JSContext cx = new JSContext();

		// 1. 正则字面量与基础属性/方法
		Object test1 = cx.eval("""
			var r = /^hello\\s+world/i;
			var t1 = r.test("HELLO   world, magic js!");
			var t2 = r.test("world hello");
			var src = r.source;
			var flags = r.flags;
			var isI = r.ignoreCase;
			var isG = r.global;
			[t1, t2, src, flags, isI, isG];
		""");
		Assertions.assertInstanceOf(JSArray.class, test1);
		JSArray arr1 = (JSArray) test1;
		Assertions.assertEquals(Boolean.TRUE, arr1.getElement(0));
		Assertions.assertEquals(Boolean.FALSE, arr1.getElement(1));
		Assertions.assertEquals("^hello\\s+world", arr1.getElement(2));
		Assertions.assertEquals("i", arr1.getElement(3));
		Assertions.assertEquals(Boolean.TRUE, arr1.getElement(4));
		Assertions.assertEquals(Boolean.FALSE, arr1.getElement(5));

		// 2. exec 捕获组与元数据
		Object test2 = cx.eval("""
			var r = /(\\w+)\\s+(\\w+)/;
			var match = r.exec("John Smith 123");
			[match[0], match[1], match[2], match.index, match.input];
		""");
		Assertions.assertInstanceOf(JSArray.class, test2);
		JSArray arr2 = (JSArray) test2;
		Assertions.assertEquals("John Smith", arr2.getElement(0));
		Assertions.assertEquals("John", arr2.getElement(1));
		Assertions.assertEquals("Smith", arr2.getElement(2));
		Assertions.assertEquals(0.0, ((Number) arr2.getElement(3)).doubleValue());
		Assertions.assertEquals("John Smith 123", arr2.getElement(4));

		// 3. 全局 g 标志位与 lastIndex 状态推进
		Object test3 = cx.eval("""
			var r = /\\d+/g;
			var str = "a12 b34 c56";
			var m1 = r.exec(str)[0];
			var idx1 = r.lastIndex;
			var m2 = r.exec(str)[0];
			var idx2 = r.lastIndex;
			var m3 = r.exec(str)[0];
			var idx3 = r.lastIndex;
			var m4 = r.exec(str);
			[m1, idx1, m2, idx2, m3, idx3, m4];
		""");
		JSArray arr3 = (JSArray) test3;
		Assertions.assertEquals("12", arr3.getElement(0));
		Assertions.assertEquals(3.0, ((Number) arr3.getElement(1)).doubleValue());
		Assertions.assertEquals("34", arr3.getElement(2));
		Assertions.assertEquals(7.0, ((Number) arr3.getElement(3)).doubleValue());
		Assertions.assertEquals("56", arr3.getElement(4));
		Assertions.assertEquals(11.0, ((Number) arr3.getElement(5)).doubleValue());
		Assertions.assertNull(arr3.getElement(6));

		// 4. 全局构造函数 new RegExp
		Object test4 = cx.eval("""
			var r1 = new RegExp("\\\\d+", "g");
			var r2 = RegExp(r1, "i");
			[r1.test("123"), r2.ignoreCase, r2.source];
		""");
		JSArray arr4 = (JSArray) test4;
		Assertions.assertEquals(Boolean.TRUE, arr4.getElement(0));
		Assertions.assertEquals(Boolean.TRUE, arr4.getElement(1));
		Assertions.assertEquals("\\d+", arr4.getElement(2));

		// 5. String.prototype.match
		Object test5 = cx.eval("""
			var str = "item1, item2, item3";
			var gMatches = str.match(/item\\d/g);
			var singleMatch = str.match(/item(\\d)/);
			[gMatches[0], gMatches[1], gMatches[2], singleMatch[1]];
		""");
		JSArray arr5 = (JSArray) test5;
		Assertions.assertEquals("item1", arr5.getElement(0));
		Assertions.assertEquals("item2", arr5.getElement(1));
		Assertions.assertEquals("item3", arr5.getElement(2));
		Assertions.assertEquals("1", arr5.getElement(3));

		// 6. String.prototype.search
		Object test6 = cx.eval("""
			var s = "hello beautiful world";
			var pos1 = s.search(/beautiful/);
			var pos2 = s.search(/not_exist/);
			[pos1, pos2];
		""");
		JSArray arr6 = (JSArray) test6;
		Assertions.assertEquals(6.0, ((Number) arr6.getElement(0)).doubleValue());
		Assertions.assertEquals(-1.0, ((Number) arr6.getElement(1)).doubleValue());

		// 7. String.prototype.replace 与 函数式 replacer 回调
		Object test7 = cx.eval("""
			var s = "2026-08-31";
			var r1 = s.replace(/(\\d{4})-(\\d{2})-(\\d{2})/, "$2/$3/$1");
			var text = "a1 b2 c3";
			var r2 = text.replace(/(\\w)(\\d)/g, (match, letter, digit) => letter.toUpperCase() + "_" + (digit * 10));
			[r1, r2];
		""");
		JSArray arr7 = (JSArray) test7;
		Assertions.assertEquals("08/31/2026", arr7.getElement(0));
		Assertions.assertEquals("A_10 B_20 C_30", arr7.getElement(1));

		// 8. String.prototype.split
		Object test8 = cx.eval("""
			var data = "apple,  banana;  orange grape";
			var parts = data.split(/[,;\\s]+/);
			[parts.length, parts[0], parts[1], parts[2], parts[3]];
		""");
		JSArray arr8 = (JSArray) test8;
		Assertions.assertEquals(4.0, ((Number) arr8.getElement(0)).doubleValue());
		Assertions.assertEquals("apple", arr8.getElement(1));
		Assertions.assertEquals("banana", arr8.getElement(2));
		Assertions.assertEquals("orange", arr8.getElement(3));
		Assertions.assertEquals("grape", arr8.getElement(4));

		// 9. 区分除法运算符 / 与正则字面量 /pattern/
		Object test9 = cx.eval("""
			var a = 100 / 2;
			var b = (a + 50) / 2;
			var isDigit = /^\\d+$/.test("12345");
			var arr = [10 / 2, /foo/.test("foobar")];
			[a, b, isDigit, arr[0], arr[1]];
		""");
		JSArray arr9 = (JSArray) test9;
		Assertions.assertEquals(50.0, ((Number) arr9.getElement(0)).doubleValue());
		Assertions.assertEquals(50.0, ((Number) arr9.getElement(1)).doubleValue());
		Assertions.assertEquals(Boolean.TRUE, arr9.getElement(2));
		Assertions.assertEquals(5.0, ((Number) arr9.getElement(3)).doubleValue());
		Assertions.assertEquals(Boolean.TRUE, arr9.getElement(4));
	}

	@Test
	public void testPolymorphicComplexPipeline() {
		JSContext cx = new JSContext();
		Object res = cx.eval("""
			var pool = [
				{ type: 1, val: 10, tag: 5 },
				{ type: 2, val: 20.5, meta: 3.14 },
				{ type: 3, val: 30, flag: 1, note: 100 },
				{ type: 4, val: 40, extra: { base: 200 } },
				{ type: 5, val: 50.25, delta: 1.75 }
			];

			var total = 0;
			var factor = 1;

			for (var i = 0; i < 2000; i++) {
				var item = pool[i % 5];
				var t = item.type;

				if (i % 2 === 0) {
					factor = i % 10;
				} else {
					factor = (i % 10) + 0.5;
				}

				var contribution = 0;
				if (t === 1) {
					contribution = item.val * factor + item.tag;
				} else if (t === 2) {
					contribution = item.val * 1.5 + factor * item.meta;
				} else if (t === 3) {
					contribution = (item.val + factor) * item.flag + item.note;
				} else if (t === 4) {
					contribution = item.extra.base + item.val * factor;
				} else {
					contribution = item.val * factor - item.delta;
				}

				total = total + contribution;
			}
			total;
		""");
		Assertions.assertNotNull(res);
		System.out.println("Polymorphic pipeline result: " + res);
	}

	@Test
	public void testES6DestructuringAndTernary() {
		JSContext cx = new JSContext();

		// 1. 对象解构声明 (基础、重命名别名、默认值、嵌套)
		Object r1 = cx.eval("""
			var obj = { x: 10, y: 20, z: 30 };
			var { x, y } = obj;
			x + y;
		""");
		Assertions.assertEquals(30.0, ((Number) r1).doubleValue());

		Object r2 = cx.eval("""
			var point = { x: 100, y: 200 };
			const { x: posX, y: posY } = point;
			posX + posY;
		""");
		Assertions.assertEquals(300.0, ((Number) r2).doubleValue());

		Object r3 = cx.eval("""
			var obj = { a: 5 };
			let { a, b = 15, c: customC = 25 } = obj;
			a + b + customC;
		""");
		Assertions.assertEquals(45.0, ((Number) r3).doubleValue());

		Object r4 = cx.eval("""
			var data = { user: { name: "Alice", score: 95 } };
			const { user: { name, score } } = data;
			name + "_" + score;
		""");
		Assertions.assertEquals("Alice_95", String.valueOf(r4));

		// 2. 对象解构 Rest 模式 (...rest)
		Object r5 = cx.eval("""
			var full = { a: 1, b: 2, c: 3, d: 4 };
			var { a, b, ...others } = full;
			others.c + others.d;
		""");
		Assertions.assertEquals(7.0, ((Number) r5).doubleValue());

		// 3. 数组解构声明 (基础、空槽忽略、默认值、嵌套、Rest)
		Object r6 = cx.eval("""
			var arr = [10, 20, 30, 40];
			var [ first, second ] = arr;
			first + second;
		""");
		Assertions.assertEquals(30.0, ((Number) r6).doubleValue());

		Object r7 = cx.eval("""
			var arr = [10, 20, 30, 40];
			var [ a, , c ] = arr;
			a + c;
		""");
		Assertions.assertEquals(40.0, ((Number) r7).doubleValue());

		Object r8 = cx.eval("""
			var arr = [5];
			var [ m = 1, n = 99 ] = arr;
			m + n;
		""");
		Assertions.assertEquals(104.0, ((Number) r8).doubleValue());

		Object r9 = cx.eval("""
			var nested = [[1, 2], [3, 4]];
			var [[x1, y1], [x2, y2]] = nested;
			x1 + y1 + x2 + y2;
		""");
		Assertions.assertEquals(10.0, ((Number) r9).doubleValue());

		Object r10 = cx.eval("""
			var list = [1, 2, 3, 4, 5];
			var [ head, ...tail ] = list;
			head + tail.length;
		""");
		Assertions.assertEquals(5.0, ((Number) r10).doubleValue());

		// 4. 赋值解构 (Array swap & Object destructuring assignment)
		Object r11 = cx.eval("""
			var a = 1, b = 2;
			[ a, b ] = [ b, a ];
			a * 10 + b;
		""");
		Assertions.assertEquals(21.0, ((Number) r11).doubleValue());

		Object r12 = cx.eval("""
			var x = 0, y = 0;
			({ x, y } = { x: 50, y: 60 });
			x + y;
		""");
		Assertions.assertEquals(110.0, ((Number) r12).doubleValue());

		// 5. 函数参数解构 (FunctionDecl, FunctionExpr, ArrowFunction)
		Object r13 = cx.eval("""
			function addCoords({ x, y = 5 }) {
				return x + y;
			}
			addCoords({ x: 10 });
		""");
		Assertions.assertEquals(15.0, ((Number) r13).doubleValue());

		Object r14 = cx.eval("""
			var sumPair = ([ a, b ]) => a + b;
			sumPair([12, 18]);
		""");
		Assertions.assertEquals(30.0, ((Number) r14).doubleValue());

		Object r15 = cx.eval("""
			var getUserId = ({ user: { id = 1001 } = {} }) => id;
			getUserId({ user: { id: 2024 } });
		""");
		Assertions.assertEquals(2024.0, ((Number) r15).doubleValue());

		// 6. ES6 对象属性简写 { x, y }
		Object r16 = cx.eval("""
			var p = 7, q = 8;
			var obj = { p, q };
			obj.p * obj.q;
		""");
		Assertions.assertEquals(56.0, ((Number) r16).doubleValue());

		// 7. 三元条件运算符 (Ternary ? :)
		Object r17 = cx.eval("""
			var val = 42;
			var res = val > 50 ? "high" : "low";
			res;
		""");
		Assertions.assertEquals("low", String.valueOf(r17));

		Object r18 = cx.eval("""
			var score = 85;
			var grade = score >= 90 ? "A" : (score >= 80 ? "B" : "C");
			grade;
		""");
		Assertions.assertEquals("B", String.valueOf(r18));
	}

	@Test
	public void testJSArrayIterable() {
		JSArray arr = new JSArray();
		arr.push(10);
		arr.push(20);
		arr.push(30);

		// 1. Java enhanced for-loop over JSArray
		java.util.List<Object> collected = new java.util.ArrayList<>();
		for (Object item : arr) {
			collected.add(item);
		}
		Assertions.assertEquals(java.util.List.of(10, 20, 30), collected);

		// 2. JSArray.forEach
		java.util.List<Object> forEachList = new java.util.ArrayList<>();
		arr.forEach(forEachList::add);
		Assertions.assertEquals(java.util.List.of(10, 20, 30), forEachList);

		// 3. JSOps.toIterator with JSArray
		java.util.Iterator<?> it = hope.magic.js.runtime.JSOps.toIterator(arr);
		Assertions.assertTrue(it.hasNext());
		Assertions.assertEquals(10, it.next());
		Assertions.assertEquals(20, it.next());
		Assertions.assertEquals(30, it.next());
		Assertions.assertFalse(it.hasNext());
	}

	@Test
	public void testPrimitiveEqualitySpecializations() {
		JSContext cx = new JSContext();

		// 1. null 特化 (=== null, !== null, == null, != null)
		Assertions.assertEquals(true, cx.eval("var x = null; x === null;"));
		Assertions.assertEquals(true, cx.eval("null === null;"));
		Assertions.assertEquals(false, cx.eval("var x = 123; x === null;"));
		Assertions.assertEquals(true, cx.eval("var x = 123; x !== null;"));
		Assertions.assertEquals(true, cx.eval("var x = undefined; x == null;"));
		Assertions.assertEquals(true, cx.eval("var x = null; x == null;"));
		Assertions.assertEquals(false, cx.eval("var x = 0; x == null;"));
		Assertions.assertEquals(false, cx.eval("var x = ''; x == null;"));
		Assertions.assertEquals(true, cx.eval("var x = 0; x != null;"));

		// 2. undefined 特化 (=== undefined, !== undefined, == undefined, != undefined)
		Assertions.assertEquals(true, cx.eval("var x = undefined; x === undefined;"));
		Assertions.assertEquals(false, cx.eval("var x = null; x === undefined;"));
		Assertions.assertEquals(true, cx.eval("var x = null; x == undefined;"));
		Assertions.assertEquals(true, cx.eval("var x = 42; x !== undefined;"));
		Assertions.assertEquals(false, cx.eval("var x = 42; x == undefined;"));

		// 3. boolean 特化 (=== true, === false)
		Assertions.assertEquals(true, cx.eval("var x = true; x === true;"));
		Assertions.assertEquals(false, cx.eval("var x = 1; x === true;"));
		Assertions.assertEquals(true, cx.eval("var x = 1; x == true;"));
		Assertions.assertEquals(true, cx.eval("var x = false; x === false;"));
		Assertions.assertEquals(false, cx.eval("var x = 0; x === false;"));
		Assertions.assertEquals(true, cx.eval("var x = 0; x == false;"));

		// 4. number 特化 (=== 0, === 42, === 3.14)
		Assertions.assertEquals(true, cx.eval("var x = 0; x === 0;"));
		Assertions.assertEquals(true, cx.eval("var x = 42; x === 42;"));
		Assertions.assertEquals(true, cx.eval("var x = 42.0; x === 42;"));
		Assertions.assertEquals(false, cx.eval("var x = '42'; x === 42;"));
		Assertions.assertEquals(true, cx.eval("var x = '42'; x == 42;"));
		Assertions.assertEquals(true, cx.eval("var x = 3.14; x === 3.14;"));

		// 5. string 特化 (=== 'abc', !== 'abc', == 'abc')
		Assertions.assertEquals(true, cx.eval("var x = 'hello'; x === 'hello';"));
		Assertions.assertEquals(false, cx.eval("var x = 'world'; x === 'hello';"));
		Assertions.assertEquals(true, cx.eval("var x = 'world'; x !== 'hello';"));
		Assertions.assertEquals(true, cx.eval("var x = 100; x == '100';"));
		Assertions.assertEquals(false, cx.eval("var x = 100; x === '100';"));

		// 6. 控制流分支与三元表达式中的极速跳转验证
		Object branchResult = cx.eval("""
			var status = 'active';
			var isNull = (status === null);
			var isDefined = (status !== undefined);
			var match = (status === 'active' ? 100 : 200);
			var count = 0;
			if (status === 'active') {
				count += 10;
			}
			if (status === null) {
				count += 999;
			}
			count + match;
		""");
		Assertions.assertEquals(110.0, ((Number) branchResult).doubleValue());
	}

	@Test
	public void testLocalVariableAssignmentExpressions() {
		JSContext cx = new JSContext();
		// 1. INT 变量赋值作为表达式返回值 (Issue 11 DUP/ISTORE 修复)
		Object r1 = cx.eval("var x = 1; var y = (x = 5) + 1; y;");
		Assertions.assertEquals(6.0, ((Number) r1).doubleValue());

		Object r2 = cx.eval("var x = 10; var y = (x += 5) * 2; y;");
		Assertions.assertEquals(30.0, ((Number) r2).doubleValue());

		// 2. DOUBLE 变量赋值作为表达式返回值
		Object r3 = cx.eval("var d = 1.5; var res = (d = 3.5) + 2.0; res;");
		Assertions.assertEquals(5.5, ((Number) r3).doubleValue(), 0.0001);

		// 3. LONG 变量赋值作为表达式返回值
		Object r4 = cx.eval("var l = 10000000000; var res = (l = 20000000000) + 5; res;");
		Assertions.assertEquals(20000000005.0, ((Number) r4).doubleValue(), 0.0001);
	}

	@Test
	public void testNestedFunctionScope() {
		JSContext cx = new JSContext();
		// 嵌套函数不应泄露至全局作用域
		Object res = cx.eval("""
			function outer() {
				function inner() {
					return 42;
				}
				return inner();
			}
			outer();
		""");
		Assertions.assertEquals(42.0, ((Number) res).doubleValue());
		Assertions.assertEquals(hope.magic.js.runtime.JSUndefined.INSTANCE, cx.get("inner"));
	}

	@Test
	public void testFunctionDeclarationHoisting() {
		JSContext cx = new JSContext();
		// 1. 顶层函数声明提升
		Object r1 = cx.eval("""
			var r = add(10, 20);
			function add(a, b) {
				return a + b;
			}
			r;
		""");
		Assertions.assertEquals(30.0, ((Number) r1).doubleValue());

		// 2. 嵌套函数声明提升
		Object r2 = cx.eval("""
			function calculate() {
				return helper(5);
				function helper(n) {
					return n * 3;
				}
			}
			calculate();
		""");
		Assertions.assertEquals(15.0, ((Number) r2).doubleValue());
	}

	@Test
	public void testLargeIntegerArithmeticNoOverflow() {
		JSContext cx = new JSContext();
		// 运行时变量大整数乘法与加减法溢出自动提升为 double
		Object rMul = cx.eval("var a = 2000000000; var b = 2; a * b;");
		Assertions.assertEquals(4000000000.0, ((Number) rMul).doubleValue(), 0.0001);

		Object rAdd = cx.eval("var a = 2147483647; var b = 1; a + b;");
		Assertions.assertEquals(2147483648.0, ((Number) rAdd).doubleValue(), 0.0001);

		Object rSub = cx.eval("var a = -2000000000; var b = 2000000000; a - b;");
		Assertions.assertEquals(-4000000000.0, ((Number) rSub).doubleValue(), 0.0001);
	}

	@Test
	public void testStringConcatenationWithVariables() {
		JSContext cx = new JSContext();
		// 字符串变量拼接不应被误判为 double 导致 NaN
		Object r1 = cx.eval("var a = 'hello '; var b = 'world'; a + b;");
		Assertions.assertEquals("hello world", r1);

		Object r2 = cx.eval("var s = 'count: '; var n = 42; s + n;");
		Assertions.assertEquals("count: 42", r2);

		Object r3 = cx.eval("var n = 42; var s = ' items'; n + s;");
		Assertions.assertEquals("42 items", r3);
	}

	@Test
	public void testMathObjectStandardFunctions() {
		JSContext cx = new JSContext();
		Object rSin = cx.eval("Math.sin(0);");
		Assertions.assertEquals(0.0, ((Number) rSin).doubleValue(), 0.0001);

		Object rCos = cx.eval("Math.cos(0);");
		Assertions.assertEquals(1.0, ((Number) rCos).doubleValue(), 0.0001);

		Object rPow = cx.eval("Math.pow(2, 3);");
		Assertions.assertEquals(8.0, ((Number) rPow).doubleValue(), 0.0001);

		Object rTrunc = cx.eval("Math.trunc(4.9);");
		Assertions.assertEquals(4.0, ((Number) rTrunc).doubleValue(), 0.0001);

		Object rRandom = cx.eval("var r = Math.random(); r >= 0 && r < 1;");
		Assertions.assertEquals(true, rRandom);
	}

	@Test
	public void testStandaloneFunctionThis() {
		JSContext cx = new JSContext();
		Object res = cx.eval("function getThis() { return this; } getThis() === undefined;");
		Assertions.assertEquals(true, res);
	}

	@Test
	public void testConstantFoldingStringPreservation() {
		JSContext cx = new JSContext();
		Object r1 = cx.eval("var str = 'world'; 0 + str;");
		Assertions.assertEquals("0world", r1);

		Object r2 = cx.eval("var str = 'hello'; str + 0;");
		Assertions.assertEquals("hello0", r2);
	}

	@Test
	public void testTypeOfOperator() {
		JSContext cx = new JSContext();
		Assertions.assertEquals("number", cx.eval("typeof 42;"));
		Assertions.assertEquals("string", cx.eval("typeof 'hello';"));
		Assertions.assertEquals("boolean", cx.eval("typeof true;"));
		Assertions.assertEquals("undefined", cx.eval("typeof undefined;"));
		Assertions.assertEquals("object", cx.eval("typeof null;"));
		Assertions.assertEquals("object", cx.eval("typeof { a: 1 };"));
		Assertions.assertEquals("function", cx.eval("typeof function() {};"));
		Assertions.assertEquals("undefined", cx.eval("typeof undeclaredVar;"));
	}

	@Test
	public void testVoidOperator() {
		JSContext cx = new JSContext();
		Assertions.assertEquals(hope.magic.js.runtime.JSUndefined.INSTANCE, cx.eval("void 0;"));
		Assertions.assertEquals(hope.magic.js.runtime.JSUndefined.INSTANCE, cx.eval("var x = 1; void (x = 10);"));
		Assertions.assertEquals(10.0, ((Number) cx.eval("var x = 1; void (x = 10); x;")).doubleValue());
	}

	@Test
	public void testDeleteOperator() {
		JSContext cx = new JSContext();
		Object res = cx.eval("""
			var obj = { a: 100, b: 200 };
			var d1 = delete obj.a;
			var d2 = delete obj['b'];
			obj.a === undefined && obj.b === undefined && d1 === true && d2 === true;
		""");
		Assertions.assertEquals(true, res);
	}

	@Test
	public void testDoWhileLoop() {
		JSContext cx = new JSContext();
		Object res = cx.eval("""
			var sum = 0;
			var i = 1;
			do {
				sum += i;
				i++;
			} while (i <= 5);
			sum;
		""");
		Assertions.assertEquals(15.0, ((Number) res).doubleValue());
	}

	@Test
	public void testForInLoop() {
		JSContext cx = new JSContext();
		Object res = cx.eval("""
			var obj = { x: 10, y: 20, z: 30 };
			var keys = "";
			for (var k in obj) {
				keys += k;
			}
			keys;
		""");
		Assertions.assertEquals("xyz", res);
	}

	@Test
	public void testTryCatchFinally() {
		JSContext cx = new JSContext();
		Object r1 = cx.eval("""
			var msg = "";
			try {
				throw "custom_error";
			} catch (e) {
				msg = "caught: " + e;
			}
			msg;
		""");
		Assertions.assertTrue(r1.toString().contains("custom_error"));

		Object r2 = cx.eval("""
			var step = 0;
			try {
				step = 1;
			} finally {
				step = 2;
			}
			step;
		""");
		Assertions.assertEquals(2.0, ((Number) r2).doubleValue());
	}

	@Test
	public void testSwitchStatement() {
		JSContext cx = new JSContext();
		Object r1 = cx.eval("""
			var res = "";
			var x = 2;
			switch (x) {
				case 1:
					res = "one";
					break;
				case 2:
					res = "two";
					break;
				default:
					res = "default";
					break;
			}
			res;
		""");
		Assertions.assertEquals("two", r1);

		// Fallthrough test
		Object r2 = cx.eval("""
			var sum = 0;
			var x = 1;
			switch (x) {
				case 1:
					sum += 10;
				case 2:
					sum += 20;
					break;
				default:
					sum += 30;
			}
			sum;
		""");
		Assertions.assertEquals(30.0, ((Number) r2).doubleValue());
	}

	@Test
	public void testNumericLiteralsHexBinaryOctalScientific() {
		JSContext cx = new JSContext();
		Assertions.assertEquals(255.0, ((Number) cx.eval("0xFF;")).doubleValue(), 0.0001);
		Assertions.assertEquals(11.0, ((Number) cx.eval("0b1011;")).doubleValue(), 0.0001);
		Assertions.assertEquals(63.0, ((Number) cx.eval("0o77;")).doubleValue(), 0.0001);
		Assertions.assertEquals(1000.0, ((Number) cx.eval("1e3;")).doubleValue(), 0.0001);
		Assertions.assertEquals(250.0, ((Number) cx.eval("2.5e2;")).doubleValue(), 0.0001);
		Assertions.assertEquals(0.01, ((Number) cx.eval("1e-2;")).doubleValue(), 0.0001);
	}

	@Test
	public void testStringEscapeSequencesAndTemplateString() {
		JSContext cx = new JSContext();
		Assertions.assertEquals("A", cx.eval("'\\u0041';"));
		Assertions.assertEquals("B", cx.eval("'\\x42';"));
		Assertions.assertEquals("hello template", cx.eval("`hello template`;"));
	}

	@Test
	public void testStringReplaceTokens() {
		JSContext cx = new JSContext();
		Assertions.assertEquals("foo[abc]bar", cx.eval("'fooabcbar'.replace('abc', '[$&]');"));
	}

	@Test
	public void testShapeSentinelNonCollidabilityAndNeverNull() throws Exception {
		// 1. 数学不变量验证：测试所有合法类型 (0..3) 在极大/极小边界 propId 下，生成的 encoded 严格不等于 SENTINEL_ENCODED
		byte[] validTypes = { JSShape.TYPE_UNKNOWN, JSShape.TYPE_DOUBLE, JSShape.TYPE_INT, JSShape.TYPE_OBJECT };
		int[] testPropIds = {
			0, 1, 2, 100, 1000, 65535, 1_000_000,
			0x0FFFFFFF, 0x0FFFFFFF >> 1, 0x0FFFFFFF >> 2,
			Integer.MAX_VALUE >> 3, (Integer.MAX_VALUE >> 3) - 1
		};

		for (int propId : testPropIds) {
			for (byte type : validTypes) {
				int encoded = JSShape.encodeKey(propId, type);
				// 契约 1: 绝对不能等于哨兵值 0x7FFFFFFF
				Assertions.assertNotEquals(JSShape.SENTINEL_ENCODED, encoded,
					() -> "Collision detected! propId=" + propId + ", type=" + type);
				// 契约 2: 低 3 位的值必然在 [0, 3] 区间，第 2 位 (权重 4) 恒等于 0
				Assertions.assertTrue((encoded & 0x7) <= 3);
				Assertions.assertEquals(0, encoded & 0x4);
			}
		}

		// 验证 SENTINEL_ENCODED 的低 3 位确为 7 (0b111)
		Assertions.assertEquals(7, JSShape.SENTINEL_ENCODED & 0x7);

		// 2. 极端属性添加测试：验证绝不返回 null
		JSShape shape = JSShape.ROOT;
		for (int propId : testPropIds) {
			for (byte type : validTypes) {
				JSShape next = shape.addProperty(propId, type);
				Assertions.assertNotNull(next, "addProperty must never return null for propId=" + propId);
				Assertions.assertTrue(next.propertyCount() > 0);
				shape = next;
			}
		}

		// 3. 防御契约终极验证：通过反射直接注入 SENTINEL_ENCODED 调用 addPropertySlow，确保绝对不会返回 null
		Method slowMethod = JSShape.class.getDeclaredMethod("addPropertySlow", int.class, int.class, byte.class);
		slowMethod.setAccessible(true);
		JSShape res = (JSShape) slowMethod.invoke(JSShape.ROOT, JSShape.SENTINEL_ENCODED, 12345, JSShape.TYPE_DOUBLE);
		Assertions.assertNotNull(res, "Defensive contract: addPropertySlow must NEVER return null even if sentinel is hit!");
	}

	@Test
	public void testContextCreationBenchmark() {
		// 测量第 1 次创建时延 (包含类加载与静态初始化)
		long t0 = System.nanoTime();
		JSContext c1 = new JSContext();
		long coldNs = System.nanoTime() - t0;

		// 测量后续 1000 次创建的平均时延
		int N = 1000;
		long t1 = System.nanoTime();
		for (int i = 0; i < N; i++) {
			JSContext c = new JSContext();
		}
		long warmNs = (System.nanoTime() - t1) / N;

		System.out.printf(">>> 首次 new JSContext() 时延: %.3f ms, 稳态单次: %.3f µs%n",
			coldNs / 1_000_000.0, warmNs / 1_000.0);
		Assertions.assertNotNull(c1);
		Assertions.assertTrue(coldNs < 50_000_000L, "First new JSContext() should take less than 50ms");
		Assertions.assertTrue(warmNs < 50_000L, "Warm new JSContext() should take less than 50µs");
	}
}
