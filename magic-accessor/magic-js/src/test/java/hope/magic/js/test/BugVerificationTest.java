package hope.magic.js.test;

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
}

