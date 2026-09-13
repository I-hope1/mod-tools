package hope.magic.js.test;

import hope.magic.js.runtime.JSArray;
import hope.magic.js.runtime.JSContext;
import hope.magic.js.runtime.JSUndefined;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class BugVerificationTest {

	@Test
	public void testBug01_ShapeAndSlotTypeMismatchInIC() {
		JSContext cx = new JSContext();
		String script = """
			function readVal(o) {
				return o.val;
			}
			let obj = { val: 1.5 };
			// 1. 预热 IC 使其绑定 Double 槽位快速读取
			let first = readVal(obj);
			// 2. 将属性重写为字符串
			obj.val = "hello";
			// 3. 再次通过 IC 读取
			let second = readVal(obj);
			second;
			""";
		Object res = cx.eval(script);
		Assertions.assertEquals("hello", res, "IC should return updated String value instead of 0.0 or old double bits");
	}

	@Test
	public void testBug03A_TryFinallyWithReturn() {
		JSContext cx = new JSContext();
		String script = """
			var cleaned = false;
			function testReturn() {
				try {
					return 42;
				} finally {
					cleaned = true;
				}
			}
			var res = testReturn();
			[res, cleaned];
			""";
		Object res = cx.eval(script);
		Assertions.assertEquals(42.0, ((Number) cx.eval("res;")).doubleValue());
		Assertions.assertEquals(true, cx.eval("cleaned;"), "finally block must be executed even if return statement is in try block");
	}

	@Test
	public void testBug03B_PureTryFinallyWithException() {
		JSContext cx = new JSContext();
		String script = """
			var cleaned = false;
			try {
				try {
					throw new Error("boom");
				} finally {
					cleaned = true;
				}
			} catch (e) {
				// caught
			}
			cleaned;
			""";
		Object res = cx.eval(script);
		Assertions.assertEquals(true, res, "finally block must be executed when exception is thrown in try without catch");
	}

	@Test
	public void testBug06_ConstantFolderVarHoistingErasure() {
		JSContext cx = new JSContext();
		String script = """
			var x = 99;
			function f() {
				if (false) {
					var x = 1;
				}
				return x;
			}
			f();
			""";
		Object res = cx.eval(script);
		Assertions.assertEquals(JSUndefined.INSTANCE, res, "var x in dead if-branch should be hoisted to local scope, shadowing global x and returning undefined");
	}

	@Test
	public void testBug09_ObjectLiteralFollowedByDivision() {
		JSContext cx = new JSContext();
		String script = """
			let res = { a: 10 } / 2;
			res;
			""";
		Object res = cx.eval(script);
		Assertions.assertTrue(Double.isNaN(((Number) res).doubleValue()));
	}

	@Test
	public void testBug07_IntAdditionOverflow() {
		JSContext cx = new JSContext();
		String script = """
			let a = 2000000000;
			let b = 2000000000;
			let c = a + b;
			c;
			""";
		Object res = cx.eval(script);
		Assertions.assertEquals(4000000000.0, ((Number) res).doubleValue(), "2000000000 + 2000000000 should overflow to 4000000000.0, not negative int");
	}

	@Test
	public void testBug02_NestedClosureScope() {
		JSContext cx = new JSContext();
		String script = """
			function makeCounter() {
				let count = 0;
				return function() {
					count = count + 1;
					return count;
				};
			}
			let c1 = makeCounter();
			let c2 = makeCounter();
			let a1 = c1();
			let a2 = c1();
			let b1 = c2();
			[a1, a2, b1];
			""";
		Object res = cx.eval(script);
		Assertions.assertEquals(1.0, ((Number) cx.eval("a1;")).doubleValue());
		Assertions.assertEquals(2.0, ((Number) cx.eval("a2;")).doubleValue());
		Assertions.assertEquals(1.0, ((Number) cx.eval("b1;")).doubleValue(), "c2 should have its own independent closure state, starting from 1");
	}

	@Test
	public void testBug02_MultiLevelNestedClosures() {
		JSContext cx = new JSContext();
		String script = """
			function outer(x) {
				return function(y) {
					return function(z) {
						return x + y + z;
					};
				};
			}
			let f = outer(10)(20);
			let r1 = f(30);
			let r2 = f(40);
			[r1, r2];
			""";
		Object res = cx.eval(script);
		Assertions.assertEquals(60.0, ((Number) cx.eval("r1;")).doubleValue());
		Assertions.assertEquals(70.0, ((Number) cx.eval("r2;")).doubleValue());
	}

	@Test
	public void testBug02_SharedMutatedClosureState() {
		JSContext cx = new JSContext();
		String script = """
			function createAccount(initialBalance) {
				let balance = initialBalance;
				return {
					deposit: function(amt) { balance = balance + amt; return balance; },
					withdraw: function(amt) { balance = balance - amt; return balance; },
					getBalance: function() { return balance; }
				};
			}
			let acc = createAccount(100);
			let d1 = acc.deposit(50);
			let w1 = acc.withdraw(20);
			let b = acc.getBalance();
			[d1, w1, b];
			""";
		Object res = cx.eval(script);
		Assertions.assertEquals(150.0, ((Number) cx.eval("d1;")).doubleValue());
		Assertions.assertEquals(130.0, ((Number) cx.eval("w1;")).doubleValue());
		Assertions.assertEquals(130.0, ((Number) cx.eval("b;")).doubleValue());
	}

	@Test
	public void testClassInheritanceWithClosures() {
		JSContext cx = new JSContext();
		String script = """
			function createInheritanceFactory(basePrefix, derivedSuffix) {
				class Base {
					constructor(val) {
						this.val = val;
					}
					greet() {
						return basePrefix + ":" + this.val;
					}
				}
				class Derived extends Base {
					constructor(val, extra) {
						super(val);
						this.extra = extra;
					}
					greet() {
						return super.greet() + ":" + derivedSuffix + ":" + this.extra;
					}
				}
				return Derived;
			}
			let Cls1 = createInheritanceFactory("Hello", "World");
			let Cls2 = createInheritanceFactory("Hi", "Earth");
			let o1 = new Cls1("Alice", 1);
			let o2 = new Cls2("Bob", 2);
			let r1 = o1.greet();
			let r2 = o2.greet();
			[r1, r2];
			""";
		Object res = cx.eval(script);
		Assertions.assertEquals("Hello:Alice:World:1", cx.eval("r1;"));
		Assertions.assertEquals("Hi:Bob:Earth:2", cx.eval("r2;"));
	}

	@Test
	public void testClassCapturedByNameInClosure() {
		JSContext cx = new JSContext();
		String script = """
			function makeFactory(prefix) {
				class Product {
					constructor(id) {
						this.name = prefix + ":" + id;
					}
					getName() {
						return this.name;
					}
				}
				return function(id) {
					return new Product(id);
				};
			}
			let factory1 = makeFactory("A");
			let factory2 = makeFactory("B");
			let p1 = factory1(1);
			let p2 = factory2(2);
			let r1 = p1.getName();
			let r2 = p2.getName();
			[r1, r2];
			""";
		Object res = cx.eval(script);
		Assertions.assertEquals("A:1", cx.eval("r1;"));
		Assertions.assertEquals("B:2", cx.eval("r2;"));
	}

	@Test
	public void testClassInheritanceFullClosureScenario() {
		JSContext cx = new JSContext();
		String script = """
			function createZoo(zooName) {
				let totalAnimals = 0;

				class Animal {
					constructor(kind) {
						this.kind = kind;
						totalAnimals++;
					}
					info() {
						return zooName + " animal: " + this.kind;
					}
				}

				class Bird extends Animal {
					constructor(name, wingspan) {
						super("Bird:" + name);
						this.wingspan = wingspan;
					}
					info() {
						return super.info() + " [span=" + this.wingspan + "m, total=" + totalAnimals + "]";
					}
				}

				return function(name, wingspan) {
					return new Bird(name, wingspan);
				};
			}

			let zooA = createZoo("London Zoo");
			let zooB = createZoo("Beijing Zoo");

			let eagleA = zooA("Eagle", 2.1);
			let parrotA = zooA("Parrot", 0.5);

			let craneB = zooB("Crane", 1.8);

			let rA1 = eagleA.info();
			let rA2 = parrotA.info();
			let rB1 = craneB.info();
			[rA1, rA2, rB1];
			""";
		cx.eval(script);
		Assertions.assertEquals("London Zoo animal: Bird:Eagle [span=2.1m, total=2]", cx.eval("rA1;"));
		Assertions.assertEquals("London Zoo animal: Bird:Parrot [span=0.5m, total=2]", cx.eval("rA2;"));
		Assertions.assertEquals("Beijing Zoo animal: Bird:Crane [span=1.8m, total=1]", cx.eval("rB1;"));
	}

	@Test
	public void testLoopInvariantSlotHoisting() {
		JSContext cx = new JSContext();
		String script = """
			var multiplier = 3;
			function compute(arr) {
				var sum = 0;
				for (var i = 0; i < arr.length; i++) {
					sum += arr[i] * multiplier;
				}
				return sum;
			}
			var list = [1, 2, 3, 4, 5];
			var res = compute(list);
			""";
		cx.eval(script);
		Assertions.assertEquals(45.0, ((Number) cx.eval("res;")).doubleValue());

		// While loop test
		String whileScript = """
			var factor = 2;
			var i = 0;
			var acc = 0;
			while (i < 5) {
				acc += i * factor;
				i++;
			}
			acc;
			""";
		Assertions.assertEquals(20.0, ((Number) cx.eval(whileScript)).doubleValue());

		// Re-assigned variable should not break semantics
		String mutateScript = """
			var step = 1;
			var total = 0;
			for (var j = 0; j < 3; j++) {
				total += step;
				step = step * 2;
			}
			total;
			""";
		Assertions.assertEquals(7.0, ((Number) cx.eval(mutateScript)).doubleValue());
	}

	@Test
	public void testDumpPolyBytecode() throws Exception {
		hope.magic.js.compiler.JSCompiler.ENABLE_INTEGER_MOD_SPECIALIZATION = true;
		hope.magic.js.compiler.JSCompiler.CLASS_DUMP_HOOK = (name, bytes) -> {
			if (name.contains("Function")) {
				try {
					java.nio.file.Files.writeString(java.nio.file.Path.of("poly_disasm.txt"), hope.magic.js.compiler.JSCompiler.disassemble(bytes));
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		};
		JSContext cx = new JSContext();
		String init_poly = """
		 var pool = [
		     { type: 1, val: 10, tag: 5 },
		     { type: 2, val: 20.5, meta: 3.14 },
		     { type: 3, val: 30, flag: 1, note: 100 },
		     { type: 4, val: 40, extra: { base: 200 } },
		     { type: 5, val: 50.25, delta: 1.75 }
		 ];
		 """;
		String code_poly = """
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
		 return total;
		 """;
		cx.eval(init_poly);
		cx.eval("(function() {\n" + code_poly + "\n})");
		hope.magic.js.compiler.JSCompiler.CLASS_DUMP_HOOK = null;
	}

	@Test
	public void testDistinctOffsetShapes() {
		for (int n : new int[]{1, 2, 4, 8, 64}) {
			JSContext cx = new JSContext();
			StringBuilder sb = new StringBuilder();
			sb.append("pool_").append(n).append(" = [\n");
			for (int i = 0; i < n; i++) {
				sb.append("    { ");
				for (int p = 0; p < i; p++) {
					sb.append("dummy_").append(p).append(": 0, ");
				}
				sb.append("val: ").append(10.5 + i)
				  .append(", prop_").append(i).append(": ").append(i * 10)
				  .append(" }");
				if (i < n - 1) sb.append(",\n");
			}
			sb.append("\n];\n\n");
			sb.append("test_data_").append(n).append(" = [];\n");
			sb.append("for (var i = 0; i < 2000; i++) {\n");
			sb.append("    test_data_").append(n).append("[i] = pool_").append(n).append("[i % ").append(n).append("];\n");
			sb.append("}\n");
			cx.eval(sb.toString());

			String access = """
				var data = test_data_%d;
				var total = 0;
				for (var i = 0; i < 2000; i++) {
				    total = total + data[i].val;
				}
				total;
				""".formatted(n);
			Object res = cx.eval(access);
			double expected = switch (n) {
				case 1 -> 21000.0;
				case 2 -> 22000.0;
				case 4 -> 24000.0;
				case 8 -> 28000.0;
				case 64 -> 83616.0;
				default -> 0.0;
			};
			Assertions.assertEquals(expected, ((Number) res).doubleValue(), 1e-6);
		}
	}

	@Test
	public void testBugA_OffsetCollisionWithDifferentTypes() {
		JSContext cx = new JSContext();
		String script = """
			function readVal(o) {
				return o.val;
			}
			let objA = { val: 12.5 };
			let objB = { dummy: 1, val: 25.0 };
			let objC = { val: "text_data", tag: 99 };

			// 预热多态 IC: 依次读 objA 和 objB (促成以 double 为主的多态状态)
			let a = readVal(objA);
			let b = readVal(objB);

			// 现在读 objC: offset 为 0，但类型是 String (TYPE_OBJECT)
			let c = readVal(objC);
			[a, b, c];
			""";
		cx.eval(script);
		Assertions.assertEquals(12.5, ((Number) cx.eval("a;")).doubleValue());
		Assertions.assertEquals(25.0, ((Number) cx.eval("b;")).doubleValue());
		Assertions.assertEquals("text_data", cx.eval("c;"), "Must correctly return String, not 0.0 or corrupted double bits from prim0!");
	}

	@Test
	public void testBugB_SetterNotPrematurelyMegamorphicAtThreeShapes() {
		JSContext cx = new JSContext();
		String script = """
			function writeVal(o, v) {
				o.val = v;
			}
			let o1 = { val: 1.0 };
			let o2 = { d1: 0, val: 2.0 };
			let o3 = { d1: 0, d2: 0, val: 3.0 };
			let o4 = { d1: 0, d2: 0, d3: 0, val: 4.0 };
			let o5 = { d1: 0, d2: 0, d3: 0, d4: 0, val: 5.0 };

			writeVal(o1, 10.0);
			writeVal(o2, 20.0);
			writeVal(o3, 30.0);
			writeVal(o4, 40.0);
			writeVal(o5, 50.0);

			[o1.val, o2.val, o3.val, o4.val, o5.val];
			""";
		cx.eval(script);
		Assertions.assertEquals(10.0, ((Number) cx.eval("o1.val;")).doubleValue());
		Assertions.assertEquals(20.0, ((Number) cx.eval("o2.val;")).doubleValue());
		Assertions.assertEquals(30.0, ((Number) cx.eval("o3.val;")).doubleValue());
		Assertions.assertEquals(40.0, ((Number) cx.eval("o4.val;")).doubleValue());
		Assertions.assertEquals(50.0, ((Number) cx.eval("o5.val;")).doubleValue());
	}

	@Test
	public void testBugC_MegamorphicSetterTypeTransition() {
		JSContext cx = new JSContext();
		StringBuilder init = new StringBuilder();
		init.append("""
			function setVal(o, v) {
				o.val = v;
			}
			""");
		for (int i = 0; i < 10; i++) {
			init.append("let s_").append(i).append(" = { ");
			for (int p = 0; p < i; p++) init.append("d_").append(p).append(": 0, ");
			init.append("val: ").append(i * 1.0).append(" };\n");
			init.append("setVal(s_").append(i).append(", ").append(i * 10.0).append(");\n");
		}
		init.append("""
			let strObj = { val: "initial_string" };
			setVal(strObj, 999.5);
			let finalVal = strObj.val;
			""");
		cx.eval(init.toString());
		Assertions.assertEquals(999.5, ((Number) cx.eval("finalVal;")).doubleValue());
		hope.magic.js.runtime.JSObject jsObj = (hope.magic.js.runtime.JSObject) cx.eval("strObj;");
		int offset = jsObj.shape.getOffset("val");
		Assertions.assertEquals(hope.magic.js.runtime.JSShape.TYPE_DOUBLE, jsObj.shape.getBaseType(offset),
			"strObj's shape must transition to TYPE_DOUBLE instead of remaining TYPE_OBJECT");
	}

	@Test
	public void testPrototypeMethodDispatchDifferentClassesSameShape() {
		JSContext cx = new JSContext();
		String script = """
			class Dog {
				speak() { return "woof"; }
			}
			class Cat {
				speak() { return "meow"; }
			}
			function makeNoise(animal) {
				return animal.speak();
			}
			let dogResult = makeNoise(new Dog());
			let catResult = makeNoise(new Cat());
			[dogResult, catResult];
		""";
		cx.eval(script);
		Assertions.assertEquals("woof", cx.eval("dogResult;"));
		Assertions.assertEquals("meow", cx.eval("catResult;"));
	}

	@Test
	public void testPrototypeMethodCallPerformance() {
		JSContext cx = new JSContext();
		String script = """
			class Dog { speak() { return 1; } }
			class Cat { speak() { return 2; } }
			function runMono(d, n) {
				let sum = 0;
				for (let i = 0; i < n; i++) {
					sum += d.speak();
				}
				return sum;
			}
			function runPoly(d, c, n) {
				let sum = 0;
				for (let i = 0; i < n; i++) {
					let obj = (i % 2 === 0) ? d : c;
					sum += obj.speak();
				}
				return sum;
			}
			let d = new Dog();
			let c = new Cat();
			// Warmup
			for (let w = 0; w < 5; w++) {
				runMono(d, 100000);
				runPoly(d, c, 100000);
			}
			let t0 = java.lang.System.nanoTime();
			let s1 = runMono(d, 5000000);
			let t1 = java.lang.System.nanoTime();
			let s2 = runPoly(d, c, 5000000);
			let t2 = java.lang.System.nanoTime();
			let monoNs = (t1 - t0) / 5000000.0;
			let polyNs = (t2 - t1) / 5000000.0;
			"mono: " + monoNs + " ns/op, poly: " + polyNs + " ns/op, s1=" + s1 + ", s2=" + s2;
		""";
		Object res = cx.eval(script);
		System.out.println("PROTOTYPE IC RESULT: " + res);
		Assertions.assertTrue(((String) res).contains("s1=5000000"));
		Assertions.assertTrue(((String) res).contains("s2=7500000"));
	}

	@Test
	public void testRelationalLoopPerformance() {
		JSContext cx = new JSContext();
		String script = """
			function runParamLimit(n) {
				let sum = 0;
				for (let i = 0; i < n; i++) {
					sum += (i & 1);
				}
				return sum;
			}
			function runConstLimit() {
				let sum = 0;
				for (let i = 0; i < 5000000; i++) {
					sum += (i & 1);
				}
				return sum;
			}
			function runIfCompareParam(n) {
				let cnt = 0;
				for (let i = 0; i < 5000000; i++) {
					if (i < n) cnt++;
				}
				return cnt;
			}
			// Warmup
			for (let w = 0; w < 5; w++) {
				runParamLimit(200000);
				runConstLimit();
				runIfCompareParam(200000);
			}
			let t0 = java.lang.System.nanoTime();
			let s1 = runConstLimit();
			let t1 = java.lang.System.nanoTime();
			let s2 = runParamLimit(5000000);
			let t2 = java.lang.System.nanoTime();
			let s3 = runIfCompareParam(5000000);
			let t3 = java.lang.System.nanoTime();
			let constNs = (t1 - t0) / 5000000.0;
			let paramNs = (t2 - t1) / 5000000.0;
			let ifParamNs = (t3 - t2) / 5000000.0;
			"constLimit: " + constNs + " ns/op, paramLimit: " + paramNs + " ns/op, ifParam: " + ifParamNs + " ns/op, s1=" + s1 + ", s2=" + s2 + ", s3=" + s3;
		""";
		Object res = cx.eval(script);
		System.out.println("RELATIONAL BENCHMARK RESULT: " + res);
		Assertions.assertEquals(2500000.0, ((Number) cx.eval("s1;")).doubleValue());
		Assertions.assertEquals(2500000.0, ((Number) cx.eval("s2;")).doubleValue());
		Assertions.assertEquals(5000000.0, ((Number) cx.eval("s3;")).doubleValue());
	}

	@Test
	public void testMixedNumericEqualitySpecialization() {
		JSContext cx = new JSContext();
		String script = """
			function checkEq(i, d) {
				return [i === d, i !== d, i == d, i != d];
			}
			let res1 = checkEq(42, 42.0);
			let res2 = checkEq(42, 43.5);
			let res3 = checkEq(NaN, NaN);
			let res4 = checkEq(0, -0.0);
			[res1, res2, res3, res4];
		""";
		cx.eval(script);
		Assertions.assertEquals(true, cx.eval("res1[0];"));
		Assertions.assertEquals(false, cx.eval("res1[1];"));
		Assertions.assertEquals(true, cx.eval("res1[2];"));
		Assertions.assertEquals(false, cx.eval("res1[3];"));

		Assertions.assertEquals(false, cx.eval("res2[0];"));
		Assertions.assertEquals(true, cx.eval("res2[1];"));
		Assertions.assertEquals(false, cx.eval("res2[2];"));
		Assertions.assertEquals(true, cx.eval("res2[3];"));

		// NaN === NaN is false in JS
		Assertions.assertEquals(false, cx.eval("res3[0];"));
		Assertions.assertEquals(true, cx.eval("res3[1];"));

		// 0 === -0 is true in JS
		Assertions.assertEquals(true, cx.eval("res4[0];"));
		Assertions.assertEquals(false, cx.eval("res4[1];"));
	}

	@Test
	public void testDenseArrayPerformance() {
		JSContext cx = new JSContext();
		String script = """
			function fillArray(arr, n) {
				for (let i = 0; i < n; i++) {
					arr[i] = i * 1.5;
				}
			}
			function sumArray(arr, n) {
				let sum = 0;
				for (let i = 0; i < n; i++) {
					sum += arr[i];
				}
				return sum;
			}
			let n = 20000;
			let arr = new Array(n);
			// Warmup
			for (let w = 0; w < 5; w++) {
				fillArray(arr, n);
				sumArray(arr, n);
			}
			let t0 = java.lang.System.nanoTime();
			for (let r = 0; r < 20; r++) {
				fillArray(arr, n);
			}
			let t1 = java.lang.System.nanoTime();
			let sum = 0;
			for (let r = 0; r < 20; r++) {
				sum += sumArray(arr, n);
			}
			let t2 = java.lang.System.nanoTime();
			let fillNs = (t1 - t0) / (20.0 * n);
			let sumNs = (t2 - t1) / (20.0 * n);
			"denseFill: " + fillNs + " ns/op, denseSum: " + sumNs + " ns/op, sum=" + sum;
		""";
		Object res = cx.eval(script);
		System.out.println("ARRAY BENCHMARK RESULT: " + res);
		Assertions.assertTrue(((String) res).contains("denseFill:"));
		Assertions.assertTrue(((String) res).contains("denseSum:"));
	}

	@Test
	public void testMathPerformance() {
		JSContext cx = new JSContext();
		String script = """
			function runMath(n) {
				let s = 0.0;
				for (let i = 0; i < n; i++) {
					s += Math.abs(Math.floor(i * 1.5) - Math.ceil(i * 0.5));
				}
				return s;
			}
			let n = 20000;
			// Warmup
			for (let w = 0; w < 5; w++) {
				runMath(n);
			}
			let t0 = java.lang.System.nanoTime();
			let sum = 0;
			for (let r = 0; r < 20; r++) {
				sum += runMath(n);
			}
			let t1 = java.lang.System.nanoTime();
			let mathNs = (t1 - t0) / (20.0 * n);
			"mathBench: " + mathNs + " ns/op, sum=" + sum;
		""";
		Object res = cx.eval(script);
		System.out.println("MATH BENCHMARK RESULT: " + res);
		Assertions.assertTrue(((String) res).contains("mathBench:"));
	}

	@Test
	public void testNumericArrayLiteralSpecialization() {
		JSContext cx = new JSContext();
		cx.eval("var arr = [1.5, 2.5, 3.5, 4.5];");
		JSArray arr = (JSArray) cx.get("arr");
		Assertions.assertNotNull(arr.doubleElements, "doubleElements should be non-null for numeric literal");
		Assertions.assertNull(arr.elements, "elements should be null for numeric literal");
		Assertions.assertEquals(4, arr.length());
		Assertions.assertEquals(1.5, arr.getElementDouble(0));
		Assertions.assertEquals(2.5, arr.getElementDouble(1));
		Assertions.assertEquals(3.5, arr.getElementDouble(2));
		Assertions.assertEquals(4.5, arr.getElementDouble(3));

		// Mixed literal degrades to Object[]
		cx.eval("var mixed = [1.5, 'hello', 3.5];");
		JSArray mixed = (JSArray) cx.get("mixed");
		Assertions.assertNull(mixed.doubleElements, "doubleElements should be null for mixed literal");
		Assertions.assertNotNull(mixed.elements, "elements should be non-null for mixed literal");
		Assertions.assertEquals("hello", mixed.getElement(1));
	}
}



