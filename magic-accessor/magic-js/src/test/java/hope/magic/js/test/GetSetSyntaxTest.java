package hope.magic.js.test;

import hope.magic.js.runtime.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class GetSetSyntaxTest {

	@Test
	public void testObjectLiteralGetter() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			let obj = {
			    _x: 10,
			    get x() {
			        return this._x * 2;
			    }
			};
			let r1 = obj.x;
			obj.x = 999; // 无 setter，写入静默忽略
			let r2 = obj.x;
			[r1, r2, obj._x];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals(20.0, arr.getElement(0));
		Assertions.assertEquals(20.0, arr.getElement(1));
		Assertions.assertEquals(10.0, arr.getElement(2));
	}

	@Test
	public void testObjectLiteralSetter() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			let obj = {
			    _v: 0,
			    set v(val) {
			        this._v = val + 5;
			    }
			};
			let before = obj.v; // 无 getter，为 undefined
			obj.v = 20;
			let after = obj._v;
			[before, after];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals(JSUndefined.INSTANCE, arr.getElement(0));
		Assertions.assertEquals(25.0, arr.getElement(1));
	}

	@Test
	public void testObjectLiteralGetterAndSetter() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			let obj = {
			    _val: 10,
			    get val() {
			        return this._val;
			    },
			    set val(v) {
			        this._val = v * 3;
			    }
			};
			let v1 = obj.val;
			obj.val = 7;
			let v2 = obj.val;
			let raw = obj._val;
			[v1, v2, raw];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals(10.0, arr.getElement(0));
		Assertions.assertEquals(21.0, arr.getElement(1));
		Assertions.assertEquals(21.0, arr.getElement(2));
	}

	@Test
	public void testObjectLiteralStringAndNumberKeys() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			let obj = {
			    get 'full name'() {
			        return 'Bob Smith';
			    },
			    get 100() {
			        return 'century';
			    }
			};
			[obj['full name'], obj[100]];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals("Bob Smith", arr.getElement(0));
		Assertions.assertEquals("century", arr.getElement(1));
	}

	@Test
	public void testObjectLiteralContextualKeywords() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			let get = 100;
			let set = 200;
			let obj = {
			    set: 2,
			    get() { return 3; }
			};
			let obj2 = {
			    get: 1,
			    set() { return 4; }
			};
			let obj3 = { get, set };
			[obj.get(), obj.set, obj2.get, obj2.set(), obj3.get, obj3.set];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals(3.0, arr.getElement(0));
		Assertions.assertEquals(2.0, arr.getElement(1));
		Assertions.assertEquals(1.0, arr.getElement(2));
		Assertions.assertEquals(4.0, arr.getElement(3));
		Assertions.assertEquals(100.0, arr.getElement(4));
		Assertions.assertEquals(200.0, arr.getElement(5));
	}

	@Test
	public void testObjectLiteralEnumerable() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			let obj = {
			    a: 1,
			    get b() { return 2; },
			    set b(v) {},
			    c: 3
			};
			let keys = Object.keys(obj);
			let desc = Object.getOwnPropertyDescriptor(obj, 'b');
			[keys.join(','), desc.enumerable, desc.configurable];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals("a,b,c", arr.getElement(0));
		Assertions.assertEquals(true, arr.getElement(1));
		Assertions.assertEquals(true, arr.getElement(2));
	}

	@Test
	public void testClassInstanceAccessors() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			class Circle {
			    constructor(radius) {
			        this._radius = radius;
			    }
			    get radius() {
			        return this._radius;
			    }
			    set radius(r) {
			        this._radius = r;
			    }
			    get diameter() {
			        return this._radius * 2;
			    }
			}
			let c = new Circle(5);
			let r1 = c.radius;
			let d1 = c.diameter;
			c.radius = 10;
			let r2 = c.radius;
			let d2 = c.diameter;
			let desc = Object.getOwnPropertyDescriptor(Circle.prototype, 'radius');
			[r1, d1, r2, d2, desc.enumerable];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals(5.0, arr.getElement(0));
		Assertions.assertEquals(10.0, arr.getElement(1));
		Assertions.assertEquals(10.0, arr.getElement(2));
		Assertions.assertEquals(20.0, arr.getElement(3));
		Assertions.assertEquals(false, arr.getElement(4)); // 类属性默认不可枚举
	}

	@Test
	public void testClassStaticAccessors() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			class App {
			    static get version() {
			        return '1.0.0';
			    }
			    static set debug(v) {
			        this._debug = v;
			    }
			    static get debug() {
			        return this._debug;
			    }
			}
			let v = App.version;
			App.debug = true;
			let d = App.debug;
			[v, d];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals("1.0.0", arr.getElement(0));
		Assertions.assertEquals(true, arr.getElement(1));
	}

	@Test
	public void testClassContextualKeywords() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			class Helper {
			    static() { return 'instance static'; }
			    get() { return 'instance get'; }
			    set() { return 'instance set'; }
			    static get() { return 'static get'; }
			    static set() { return 'static set'; }
			}
			let h = new Helper();
			[h.static(), h.get(), h.set(), Helper.get(), Helper.set()];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals("instance static", arr.getElement(0));
		Assertions.assertEquals("instance get", arr.getElement(1));
		Assertions.assertEquals("instance set", arr.getElement(2));
		Assertions.assertEquals("static get", arr.getElement(3));
		Assertions.assertEquals("static set", arr.getElement(4));
	}

	@Test
	public void testJavaSubclassAccessors() {
		JSContext cx = new JSContext();
		Object res = cx.eval(
		 """
			class MyList extends java.util.ArrayList {
			    get count() {
			        return this.size();
			    }
			    set push(item) {
			        this.add(item);
			    }
			}
			let list = new MyList();
			list.push = 'apple';
			list.push = 'banana';
			let cnt = list.count;
			let item0 = list.get(0);
			let item1 = list.get(1);
			[cnt, item0, item1];
			"""
		);

		Assertions.assertInstanceOf(JSArray.class, res);
		JSArray arr = (JSArray) res;
		Assertions.assertEquals(2, arr.getElement(0));
		Assertions.assertEquals("apple", arr.getElement(1));
		Assertions.assertEquals("banana", arr.getElement(2));
	}

	@Test
	public void testInvalidParameterCount() {
		JSContext cx = new JSContext();

		// 对象字面量 getter 带有参数
		Assertions.assertThrows(Exception.class, () -> {
			cx.eval("let o = { get foo(x) {} };");
		});

		// 对象字面量 setter 0 个参数
		Assertions.assertThrows(Exception.class, () -> {
			cx.eval("let o = { set foo() {} };");
		});

		// 对象字面量 setter 多个参数
		Assertions.assertThrows(Exception.class, () -> {
			cx.eval("let o = { set foo(a, b) {} };");
		});

		// 类实例 getter 带有参数
		Assertions.assertThrows(Exception.class, () -> {
			cx.eval("class A { get foo(x) {} }");
		});

		// 类实例 setter 0 个参数
		Assertions.assertThrows(Exception.class, () -> {
			cx.eval("class A { set foo() {} }");
		});

		// 类实例 setter 多个参数
		Assertions.assertThrows(Exception.class, () -> {
			cx.eval("class A { set foo(a, b) {} }");
		});
	}
}
